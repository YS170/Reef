package dev.pranav.reef.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.reef.MainActivity
import dev.pranav.reef.R
import dev.pranav.reef.services.routines.RoutineSessionManager

object NotificationHelper {
    const val ROUTINE_STATUS_NOTIFICATION_ID = 5001
    const val ROUTINE_BUDGET_NOTIFICATION_ID = 5002
    const val ROUTINE_BUDGET_CHANNEL_ID = "reef_routine_budget_v2"
    private const val REMINDER_NOTIFICATION_ID = 200

    const val BLOCKER_GROUP_KEY = "dev.pranav.reef.BLOCKER_GROUP"
    const val BLOCKER_SUMMARY_ID = 5000

    fun Context.createNotificationChannel() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                BLOCKER_CHANNEL_ID,
                getString(R.string.blocker_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.blocker_channel_description)
                setSound(null, null)
                setBypassDnd(true)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ROUTINE_STATUS_CHANNEL_ID,
                getString(R.string.routine_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.routine_channel_description)
                setShowBadge(false)
                setSound(null, null)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.reminder_channel_description)
                setBypassDnd(true)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FOCUS_MODE_CHANNEL_ID,
                getString(R.string.focus_mode_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.focus_mode_channel_description)
                setBypassDnd(true)
            }
        )
        // Silent (no sound, no heads-up), but at DEFAULT importance so that MIUI/HyperOS
        // does not fold it into the "silent notifications" bucket and hide it from the
        // shade. Channel id carries a version suffix because channel settings are
        // immutable once created.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ROUTINE_BUDGET_CHANNEL_ID,
                "剩余可用时间",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "专注时间段内显示当前应用的剩余可用时间"
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    fun syncRoutineNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeNames = RoutineSessionManager.getActiveRoutineNames()

        if (activeNames.isEmpty()) {
            manager.cancel(ROUTINE_STATUS_NOTIFICATION_ID)
            return
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                putExtra(
                    "navigate_to_routines",
                    true
                )
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (activeNames.size == 1) {
            context.getString(R.string.routine_active_single, activeNames[0])
        } else {
            context.getString(R.string.active_routines_list, activeNames.joinToString(", "))
        }

        val notification = NotificationCompat.Builder(context, ROUTINE_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.routines))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        manager.notify(ROUTINE_STATUS_NOTIFICATION_ID, notification)
    }

    /**
     * Shows (or updates in place) the ongoing "how much longer can I use this app" notification
     * for [packageName]. Silent, non-dismissible by the user, refreshed roughly once a minute by
     * BlockerService.
     */
    fun showBudgetNotification(context: Context, packageName: String, remainingMs: Long) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val appName = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

        // Round up: while there is still time left, never show "0 minutes".
        val minutes = ((remainingMs + 59_999L) / 60_000L).toInt()

        val notification = NotificationCompat.Builder(context, ROUTINE_BUDGET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(appName)
            .setContentText("本段还能玩 $minutes 分钟")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context)
            .notify(ROUTINE_BUDGET_NOTIFICATION_ID, notification)
    }

    fun cancelBudgetNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(ROUTINE_BUDGET_NOTIFICATION_ID)
    }

    fun showReminderNotification(context: Context, packageName: String, timeRemaining: Long) {
        val appName = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

        val minutes = (timeRemaining / 60000).toInt()

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.time_limit_reminder))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.app_will_be_blocked_in,
                    minutes,
                    appName,
                    minutes
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(
                REMINDER_NOTIFICATION_ID + packageName.hashCode(),
                builder.build()
            )
        }
    }
}
