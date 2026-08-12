package com.example.utils

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime permission state for payment reminders.
 *
 * `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM` were declared in the manifest and never
 * requested or checked anywhere in the app — a repo-wide search for `checkSelfPermission`
 * or `canScheduleExactAlarms` found nothing. On Android 13+ notifications are denied until
 * asked for, and on Android 12+ exact alarms need an explicit grant, so
 * `setExactAndAllowWhileIdle` threw `SecurityException` into a catch block and the user
 * was told the reminder was scheduled. It silently never fired.
 */
object ReminderPermissions {

    /** Android 13+ requires an explicit grant before any notification can be posted. */
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /**
     * Android 12+ gates exact alarms behind a user-granted special access. Without it,
     * `setExactAndAllowWhileIdle` throws.
     */
    fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.canScheduleExactAlarms() ?: false
        } else true

    /** Null when a reminder can actually be delivered; otherwise what is missing. */
    fun blockingReason(context: Context): String? = when {
        !hasNotificationPermission(context) ->
            "Notifications are turned off for this app, so the reminder cannot appear."
        !canScheduleExactAlarms(context) ->
            "Exact alarms are not permitted for this app, so the reminder cannot fire at the time you set."
        else -> null
    }

    /** Opens the exact-alarm settings page. Only reachable from an explicit user action. */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    fun openAppNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
