package dev.pranav.reef.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.reef.MindfulLaunchActivity
import dev.pranav.reef.R
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.BLOCKER_CHANNEL_ID
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.MindfulLaunchManager
import dev.pranav.reef.util.MindfulLaunchTargetEvaluator
import dev.pranav.reef.util.NotificationHelper
import dev.pranav.reef.util.NotificationHelper.BLOCKER_GROUP_KEY
import dev.pranav.reef.util.NotificationHelper.createNotificationChannel
import dev.pranav.reef.util.NotificationHelper.syncRoutineNotification
import dev.pranav.reef.util.WebsiteBlocklist
import dev.pranav.reef.util.WebsiteLimits
import dev.pranav.reef.util.Whitelist
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("AccessibilityPolicy")
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var keyguardManager: KeyguardManager? = null
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private var screenReceiverRegistered = false
    private var lastCheckedPackage: String? = null
    private var lastAppCheckAtMs = 0L
    private var foregroundPackage: String? = null
    private var enforcedForegroundPackage: String? = null

    /** Package whose remaining routine budget is currently shown in the status bar. */
    private var budgetPackage: String? = null

    /** Resolved launcher package, used to tell "went home" apart from a transient system window. */
    private var homePackage: String? = null

    /** Cached answers to "does this package have a launcher icon", i.e. is it a real app. */
    private val launcherIntentCache = mutableMapOf<String, Boolean>()

    /** Cached answers to "is this a system package". */
    private val systemAppCache = mutableMapOf<String, Boolean>()

    /** Small always-on-top pill showing the remaining budget of the current app. */
    private var budgetOverlay: View? = null

    /** Guards against re-posting the "overlay failed" diagnostic on every refresh. */
    private var overlayFailureReported = false

    /** Throttle bookkeeping for the budget tracking that now runs on every event type. */
    private var lastBudgetEventPkg: String? = null
    private var lastBudgetEventAt = 0L

    /** Worker used so UsageStatsManager queries never block the accessibility callback. */
    private val budgetExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var activeBrowserPackage: String? = null
    private var activeBrowserConfig: BrowserConfig? = null
    private val browserPackages = mutableSetOf<String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    WebsiteUsageTracker.stopTracking()
                    stopBudgetTicker()
                }

                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    // Start tracking again if we are already in a browser with a domain
                    // but we will let onAccessibilityEvent handle the current domain state
                }
            }
        }
    }

    private val websiteLimitPollRunnable = object : Runnable {
        override fun run() {
            try {
                val currentDomain = WebsiteUsageTracker.getCurrentTrackingDomain()
                if (currentDomain != null && WebsiteLimits.hasLimit(
                        currentDomain
                    )
                ) {
                    val limit = WebsiteLimits.getLimit(currentDomain)
                    val usage = WebsiteUsageTracker.getDailyUsage(currentDomain)
                    Log.d("BlockerService", "limit=$limit, usage=$usage for $currentDomain")
                    if (usage >= limit) {
                        WebsiteUsageTracker.stopTracking()
                        activeBrowserConfig?.let { config ->
                            performRedirect(config)
                            showBlockedNotification(
                                currentDomain,
                                UsageTracker.BlockReason.DAILY_LIMIT,
                                isWebsite = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BlockerService", "Website limit poll error", e)
            }
            handler.postDelayed(this, 5000L) // Poll every 5 seconds
        }
    }

    private val routinePollRunnable = object : Runnable {
        override fun run() {
            try {
                RoutineSessionManager.evaluateAndSync(this@BlockerService)
                syncRoutineNotification(this@BlockerService)
                // A routine window may have just ended (or been disabled): drop the
                // "time left" notification if it no longer applies.
                refreshBudgetNotification()
            } catch (e: Exception) {
                Log.e("BlockerService", "Routine poll error", e)
            }
            handler.postDelayed(this, ROUTINE_POLL_INTERVAL_MS)
        }
    }

    private val budgetTickRunnable = object : Runnable {
        override fun run() {
            refreshBudgetNotification()
            handler.postDelayed(this, BUDGET_TICK_INTERVAL_MS)
        }
    }

    private data class BrowserConfig(
        val urlBarId: String,
        val suggestionBoxId: String,
        val isSuggestionBoxEqualToGo: Boolean = false,
        val suggestionBoxChildIndex: Int = 0
    )

    private val commonResourceConfigs = listOf(
        Triple("url_bar", "omnibox_suggestions_dropdown", false), // Chrome, Brave, Edge, Vivaldi
        Triple("mozac_browser_toolbar_url_view", "sfcnt", false), // Firefox
        Triple("url_field", "right_state_button", true), // Opera
        Triple("location_bar_edit_text", "location_bar_edit_text", false), // Samsung
        Triple("omnibarTextInput", "omnibarTextInput", false) // DuckDuckGo
    )

    private val redirectUrl = "about:blank"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isBound = true
        lastEventEpochMs = System.currentTimeMillis()
        _connectionState.value = false
        configureService()
        createNotificationChannel()
        startKeepAliveForeground()
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        WebsiteUsageTracker.init(this)
        WebsiteLimits.init(this)
        refreshBrowserPackages()
        homePackage = resolveHomePackage()

        if (!isPrefsInitialized) {
            val deviceContext = createDeviceProtectedStorageContext()
            prefs = deviceContext.getSharedPreferences("prefs", MODE_PRIVATE)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }

        handler.removeCallbacks(routinePollRunnable)
        handler.removeCallbacks(websiteLimitPollRunnable)
        handler.post(routinePollRunnable)
        handler.post(websiteLimitPollRunnable)
        _connectionState.value = true
    }

    private fun configureService() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventEpochMs = System.currentTimeMillis()
        if (keyguardManager?.isKeyguardLocked == true) return

        val pkg = event.packageName?.toString() ?: return

        // Diagnostics: which event types does this ROM actually deliver?
        Log.d(DEBUG_TAG, "event type=${event.eventType} pkg=$pkg")

        // Some ROMs deliver only TYPE_WINDOW_CONTENT_CHANGED for app switches, so the budget
        // tracking must not depend on TYPE_WINDOW_STATE_CHANGED alone. Throttled to one call
        // per package change plus every two seconds.
        //
        // Our own package is deliberately excluded here: Reef's background UI keeps emitting
        // content-change events, and treating those as "the user came back to Reef" made the
        // pill alternate between added and removed several times per second (visible flicker).
        // Reef itself is handled on real window-state changes further down.
        val nowMs = SystemClock.elapsedRealtime()
        if (pkg != packageName &&
            (pkg != lastBudgetEventPkg || nowMs - lastBudgetEventAt > 2_000L)
        ) {
            lastBudgetEventPkg = pkg
            lastBudgetEventAt = nowMs
            onForegroundApp(pkg)
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (foregroundPackage != pkg) {
                foregroundPackage = pkg
                enforcedForegroundPackage = null
            }

            // The user really switched to Reef: that is the one signal that hides the pill.
            // Guard against our own overlay: adding the pill produces a window-state event for
            // our own package too, and treating that as "Reef opened" made the pill remove
            // itself ~140 ms after being added. Only real activity windows count.
            if (pkg == packageName &&
                event.className?.toString()?.contains("Activity") == true
            ) {
                stopBudgetTicker()
            }

            if (!browserPackages.contains(pkg)) {
                WebsiteUsageTracker.stopTracking()
                activeBrowserPackage = null
                activeBrowserConfig = null
            } else {
                activeBrowserPackage = pkg
            }
        }

        if (pkg == packageName) return

        // Content events can arrive after another app has already taken the foreground.
        // Ignoring those stale events prevents repeatedly forcing Home for the same launch.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            foregroundPackage != null &&
            pkg != foregroundPackage
        ) {
            return
        }

        if (activeBrowserPackage == pkg) {
            val root = rootInActiveWindow ?: event.source ?: return
            val config = findBrowserConfig(root, pkg)
            if (config != null) {
                activeBrowserConfig = config
                val urlBarNode = findUrlBarNode(root, config.urlBarId)
                if (urlBarNode != null) {
                    val url = extractUrlFromNode(urlBarNode)
                    if (url != null) {

                        Log.d("BlockerService", "Found url=$url in node $urlBarNode")
                        val domain = sanitizeUrl(url)

                        if (WebsiteBlocklist.isBlocked(domain)) {
                            WebsiteUsageTracker.stopTracking()
                            performRedirect(config)
                            showBlockedNotification(
                                domain,
                                UsageTracker.BlockReason.DAILY_LIMIT,
                                isWebsite = true
                            )
                            return
                        }

                        if (WebsiteLimits.hasLimit(domain)) {
                            WebsiteUsageTracker.startTracking(domain)
                            val limit = WebsiteLimits.getLimit(domain)
                            val usage = WebsiteUsageTracker.getDailyUsage(domain)
                            if (usage >= limit) {
                                WebsiteUsageTracker.stopTracking()
                                performRedirect(config)
                                showBlockedNotification(
                                    domain,
                                    UsageTracker.BlockReason.DAILY_LIMIT,
                                    isWebsite = true
                                )
                                return
                            }
                        } else {
                            WebsiteUsageTracker.stopTracking()
                        }
                    } else {
                        WebsiteUsageTracker.stopTracking()
                    }
                }
            }
        }

        val shouldCheckApp = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                event.contentChangeTypes !=
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION

            else -> false
        }

        if (shouldCheckApp && shouldHandleAppCheck(pkg)) {
            handleAppBlockCheck(pkg, event.className?.toString())
        }
    }

    /**
     * Raises the process priority so the system does not freeze it while Reef sits in the
     * background. On MIUI/HyperOS the accessibility binding alone is not enough - the process
     * gets marked "power idle" (cached-app freezer) and then receives no more accessibility
     * events at all, which is exactly the "it works for a second, then never again" symptom.
     */
    private fun startKeepAliveForeground() {
        try {
            val notification = NotificationCompat.Builder(this, ROUTINE_BUDGET_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Reef")
                .setContentText("专注计划守护中")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            startForeground(KEEP_ALIVE_NOTIFICATION_ID, notification)
            diag("foreground ok")
        } catch (e: Exception) {
            diag("foreground fail ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun shouldHandleAppCheck(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val isDuplicate =
            pkg == lastCheckedPackage && now - lastAppCheckAtMs < APP_CHECK_DEBOUNCE_MS
        if (!isDuplicate) {
            lastCheckedPackage = pkg
            lastAppCheckAtMs = now
        }
        return !isDuplicate
    }

    @Suppress("DEPRECATION")
    private fun resolveHomePackage(): String? = try {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    /**
     * True when [pkg] is a normal, launchable application (something with an icon in the
     * app drawer). System UI variants, notification shades, installers, input methods and
     * other floating windows do not qualify, so they never interrupt an active countdown.
     */
    private fun hasLauncherIntent(pkg: String): Boolean =
        launcherIntentCache.getOrPut(pkg) {
            try {
                packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
        }

    /** True for pre-installed / system packages (shade, launcher, Settings, installers...). */
    private fun isSystemApp(pkg: String): Boolean =
        systemAppCache.getOrPut(pkg) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Decides what the countdown notification should do when [pkg] takes the foreground.
     *
     * Design rule: once the countdown is on screen it is NOT removed by app switching.
     * Window events are far too noisy on MIUI/HyperOS (shade, launcher, screen recorders,
     * floating windows), and every "cancel on switch" variant ended up killing the
     * notification seconds after it appeared.
     */
    private fun onForegroundApp(pkg: String) {
        diag("fg $pkg")
        if (pkg == packageName) {
            stopBudgetTicker()
            return
        }
        // Going home never cancels: on MIUI the shade and the launcher can report the same
        // package, and cancelling here is what made the notification vanish on pull-down.
        if (pkg == homePackage) return

        // An app that actually has a routine budget is always followed, even if it is a
        // pre-installed one (a system browser, for example).
        if (RoutineSessionManager.getLimitMs(pkg) != null) {
            startBudgetTicker(pkg)
            return
        }

        // Everything that is not a normal user app (shade, Settings, installers, IME,
        // system overlays) is ignored: it is not a real app switch.
        if (pkg in TRANSIENT_PACKAGES) return
        if (isSystemApp(pkg)) return
        if (!hasLauncherIntent(pkg)) return

        // A completely different, unrestricted app. Deliberately does nothing: the countdown
        // is only ever cleared when the budget is used up, the screen is turned off, or Reef
        // itself is opened. Any "cancel after switching" rule kept firing on screen recorders,
        // floating windows and other helper apps, which made the notification flash and die.
    }

    private fun startBudgetTicker(pkg: String) {
        if (budgetPackage == pkg) {
            refreshBudgetNotification()
            return
        }
        budgetPackage = pkg
        handler.removeCallbacks(budgetTickRunnable)
        refreshBudgetNotification()
        handler.postDelayed(budgetTickRunnable, BUDGET_TICK_INTERVAL_MS)
    }

    private fun stopBudgetTicker() {
        budgetPackage = null
        handler.removeCallbacks(budgetTickRunnable)
        removeBudgetOverlay()
        NotificationHelper.cancelBudgetNotification(this)
        diag("stop")
    }

    /**
     * Starts the budget refresh on a worker thread. Querying UsageStatsManager inside the
     * accessibility callback blocks the main thread; when that happens often enough the
     * system decides the service is unresponsive and silently stops delivering events to it
     * (which looked exactly like "the pill works for a second and then never again").
     */
    private fun refreshBudgetNotification() {
        val pkg = budgetPackage ?: return
        budgetExecutor.execute {
            val remainingMs = try {
                RoutineSessionManager.getRemainingMs(this, pkg)
            } catch (e: Exception) {
                diag("remaining error: ${e.javaClass.simpleName}")
                null
            }
            handler.post { applyBudget(pkg, remainingMs) }
        }
    }

    /** Main-thread part: draw the pill and post the notification. */
    private fun applyBudget(pkg: String, remainingMs: Long?) {
        try {
            if (remainingMs == null || remainingMs <= 0L) {
                stopBudgetTicker()
                return
            }
            updateBudgetOverlay(pkg, remainingMs)
            val notification = NotificationHelper.buildBudgetNotification(this, pkg, remainingMs)
            if (notification != null) {
                // Plain notification, exactly like the blocker alerts that this device renders
                // reliably. Deliberately NOT a foreground-service notification: binding it to
                // the accessibility service made the system tear it down with the service.
                NotificationManagerCompat.from(this)
                    .notify(NotificationHelper.ROUTINE_BUDGET_NOTIFICATION_ID, notification)
            } else {
                debugReport("通知构造失败: 可能没给通知权限")
            }
        } catch (e: Exception) {
            debugReport("异常: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Temporary on-device diagnostics, posted through the blocker channel. */
    private fun debugReport(message: String) {
        diag(message)
        try {
            val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("诊断")
                .setContentText(message)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(this).notify(9997, notification)
        } catch (_: Exception) {
        }
    }

    /**
     * Draws (or updates) a small non-interactive pill in the top-right corner showing the
     * remaining budget. It uses TYPE_ACCESSIBILITY_OVERLAY, which an accessibility service is
     * allowed to add without the "display over other apps" permission.
     */
    private fun updateBudgetOverlay(pkg: String, remainingMs: Long) {
        val minutes = ((remainingMs + 59_999L) / 60_000L).toInt()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                .toString()
        } catch (_: Exception) {
            pkg
        }
        val text = "$label 还剩 $minutes 分"

        debugReport("尝试显示胶囊: $text")

        val existing = budgetOverlay
        if (existing is TextView && existing.isAttachedToWindow) {
            existing.text = text
            return
        }
        removeBudgetOverlay()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Preferred: an accessibility overlay needs no extra permission. Some OEM ROMs
        // (MIUI among them) refuse it, so fall back to a normal "draw over other apps"
        // overlay when that permission happens to be granted.
        val candidates = mutableListOf(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        if (Settings.canDrawOverlays(this)) {
            candidates.add(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }

        var lastError: Exception? = null
        for (type in candidates) {
            // A fresh view per attempt: a view whose addView() failed must not be reused.
            val view = TextView(this).apply {
                setText(text)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                setPadding(28, 12, 28, 12)
                background = GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(0xCC202124.toInt())
                }
                isClickable = false
                isFocusable = false
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 160
            }
            try {
                windowManager.addView(view, params)
                budgetOverlay = view
                overlayFailureReported = false
                debugReport("胶囊已添加 type=$type")
                return
            } catch (e: Exception) {
                lastError = e
                debugReport("添加失败 type=$type: ${e.javaClass.simpleName}")
                Log.w("BlockerService", "Overlay type $type rejected", e)
            }
        }

        lastError?.let { reportOverlayFailure(it) }
    }

    /**
     * Posts a one-off diagnostic through the blocker channel when the pill cannot be shown,
     * so the failure is visible on the device instead of only in logcat.
     */
    private fun reportOverlayFailure(error: Exception) {
        if (overlayFailureReported) return
        overlayFailureReported = true
        try {
            val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("悬浮窗没显示出来")
                .setContentText("${error.javaClass.simpleName}: ${error.message}")
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(this).notify(9998, notification)
        } catch (_: Exception) {
        }
    }

    /**
     * Appends a line to files/diag.log. MIUI suppresses third-party Logcat output, so this is
     * the only reliable way to observe the service from `adb shell run-as`.
     */
    private fun diag(message: String) {
        try {
            java.io.File(filesDir, "diag.log").appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Exception) {
        }
    }

    private fun removeBudgetOverlay() {
        val view = budgetOverlay ?: return
        budgetOverlay = null
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun refreshBrowserPackages() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"))
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
            browserPackages.clear()
            for (info in resolveInfos) {
                browserPackages.add(info.activityInfo.packageName)
            }
            // Always include known ones as fallback
            browserPackages.addAll(
                listOf(
                    "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
                    "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser"
                )
            )
        } catch (e: Exception) {
            Log.e("BlockerService", "Error refreshing browsers", e)
        }
    }

    private fun findBrowserConfig(root: AccessibilityNodeInfo, pkg: String): BrowserConfig? {
        // 1. Try common resource names with current package first
        for ((urlRes, suggestRes, isGo) in commonResourceConfigs) {
            val urlId = "$pkg:id/$urlRes"
            val nodes = root.findAccessibilityNodeInfosByViewId(urlId)
            if (!nodes.isNullOrEmpty()) {
                return BrowserConfig(urlId, "$pkg:id/$suggestRes", isGo)
            }
        }

        // 2. Try hardcoded full IDs in priority order (Chrome, Mozilla, Opera)
        val hardcoded = listOf(
            Triple(
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/omnibox_suggestions_dropdown",
                false
            ),
            Triple(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/sfcnt",
                false
            ),
            Triple(
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/right_state_button",
                true
            )
        )
        for ((urlId, suggestId, isGo) in hardcoded) {
            val nodes = root.findAccessibilityNodeInfosByViewId(urlId)
            if (!nodes.isNullOrEmpty()) {
                return BrowserConfig(urlId, suggestId, isGo)
            }
        }
        return null
    }

    private fun findUrlBarNode(
        root: AccessibilityNodeInfo,
        fullId: String
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
        return if (!nodes.isNullOrEmpty()) nodes[0] else null
    }

    private fun extractUrlFromNode(node: AccessibilityNodeInfo): String? {
        if (node.isFocused) return null
        val text = node.text?.toString() ?: return null
        if (text.isBlank() || !text.contains('.') || text.contains(' ')) return null
        return text
    }

    private fun sanitizeUrl(url: String): String {
        return url.lowercase()
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .substringBefore('/')
    }

    private fun performRedirect(config: BrowserConfig) {
        val initialRoot = rootInActiveWindow ?: return
        val urlBar = findUrlBarNode(initialRoot, config.urlBarId) ?: return
        urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        handler.postDelayed({
            val editRoot = rootInActiveWindow ?: return@postDelayed
            val editText = findUrlBarNode(editRoot, config.urlBarId) ?: return@postDelayed
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    redirectUrl
                )
            }
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                val finalRoot = rootInActiveWindow ?: return@postDelayed
                performGoAction(finalRoot, config)
            }, 300)
        }, 300)
    }

    private fun performGoAction(root: AccessibilityNodeInfo, config: BrowserConfig) {
        val nodes = root.findAccessibilityNodeInfosByViewId(config.suggestionBoxId) ?: return
        val box = nodes.firstOrNull() ?: return
        if (config.isSuggestionBoxEqualToGo) {
            box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val child = box.getChild(config.suggestionBoxChildIndex)
            if (child != null) {
                child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun handleAppBlockCheck(pkg: String, className: String? = null) {
        if (enforcedForegroundPackage == pkg) return

        if (prefs.getBoolean("focus_mode", false)) {
            if (Whitelist.isWhitelisted(pkg)) return

            FocusStats.recordBlockEvent(pkg, "focus_mode")
            enforcedForegroundPackage = pkg
            performGlobalAction(GLOBAL_ACTION_HOME)
            showFocusModeNotification(pkg)
            return
        }

        // Resolve Google Search + Robin activity into the configured Gemini package
        val targetPkg = MindfulLaunchTargetEvaluator.getTargetPackage(pkg, className)

        if (MindfulLaunchManager.isEnabled() && MindfulLaunchManager.isMindfulApp(targetPkg)) {
            if (!MindfulLaunchManager.isCurrentlyUnlocked(targetPkg)) {
                enforcedForegroundPackage = pkg
                val intent = Intent(this, MindfulLaunchActivity::class.java).apply {
                    putExtra("target_package", targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                return
            }
        }

        val blockReason = UsageTracker.checkBlockReason(this, targetPkg)
        if (blockReason != UsageTracker.BlockReason.NONE) {
            enforcedForegroundPackage = pkg
            performGlobalAction(GLOBAL_ACTION_HOME)
            showBlockedNotification(targetPkg, blockReason)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBlockedNotification(
        pkgOrUrl: String,
        reason: UsageTracker.BlockReason,
        isWebsite: Boolean = false
    ) {
        if (!notificationManager.areNotificationsEnabled()) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkgOrUrl, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            pkgOrUrl
        }
        val contentText = when (reason) {
            UsageTracker.BlockReason.ROUTINE_LIMIT -> if (isWebsite) getString(R.string.website_blocked_by_routine) else getString(
                R.string.blocked_by_routine,
                appName
            )

            else -> if (isWebsite) getString(
                R.string.website_reached_limit,
                appName
            ) else getString(R.string.reached_limit, appName)
        }

        val titleText =
            if (isWebsite) getString(R.string.website_blocked) else getString(R.string.app_blocked)

        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(BLOCKER_GROUP_KEY)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(pkgOrUrl.hashCode(), notification)

        val summary = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setGroup(BLOCKER_GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        notificationManager.notify(NotificationHelper.BLOCKER_SUMMARY_ID, summary)
    }

    @SuppressLint("MissingPermission")
    private fun showFocusModeNotification(pkg: String) {
        if (!notificationManager.areNotificationsEnabled()) return
        if (!prefs.getBoolean("focus_reminders", true)) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.distraction_blocked))
            .setContentText(getString(R.string.you_were_using, appName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("focus_$pkg".hashCode(), notification)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        cleanupConnection()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isBound = true
        lastEventEpochMs = System.currentTimeMillis()
    }

    override fun onDestroy() {
        isBound = false
        cleanupConnection()
        super.onDestroy()
    }

    private fun cleanupConnection() {
        _connectionState.value = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            screenReceiverRegistered = false
        }
        handler.removeCallbacks(websiteLimitPollRunnable)
        handler.removeCallbacks(routinePollRunnable)
        handler.removeCallbacks(budgetTickRunnable)
        removeBudgetOverlay()
        budgetPackage = null
        NotificationHelper.cancelBudgetNotification(this)
        foregroundPackage = null
        enforcedForegroundPackage = null
        activeBrowserPackage = null
        activeBrowserConfig = null
        WebsiteUsageTracker.stopTracking()
    }

    companion object {
        private const val DEBUG_TAG = "REEFDBG"
        private const val KEEP_ALIVE_NOTIFICATION_ID = 6001
        private const val ROUTINE_POLL_INTERVAL_MS = 30_000L
        private const val APP_CHECK_DEBOUNCE_MS = 500L
        private const val BUDGET_TICK_INTERVAL_MS = 60_000L

        /**
         * Windows that briefly steal the foreground while another app is being opened or
         * while the notification shade is pulled down. They must never be treated as
         * "the user switched to another app", otherwise the countdown notification is
         * cancelled the instant it appears.
         */
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android"
        )

        @Volatile
        var isBound: Boolean = false

        @Volatile
        var lastEventEpochMs: Long = 0L

        private val _connectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

        val isConnected: Boolean
            get() = _connectionState.value && isBound
    }
}
