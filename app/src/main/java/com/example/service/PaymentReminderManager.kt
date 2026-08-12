package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

data class PaymentReminderItem(
    val id: Long = System.currentTimeMillis(),
    val partyName: String,
    val amount: Double,
    val reminderType: String, // "COLLECTION" or "PAYMENT"
    val scheduledTimeMillis: Long,
    val note: String = ""
)

object PaymentReminderManager {

    /**
     * @return null on success, or the reason the reminder cannot be delivered.
     *
     * This used to return Unit and swallow SecurityException, so on Android 12+ without
     * the exact-alarm grant — or Android 13+ without notification permission — the app
     * reported the reminder as scheduled and it simply never fired.
     */
    fun scheduleReminder(context: Context, reminder: PaymentReminderItem): String? {
        com.example.utils.ReminderPermissions.blockingReason(context)?.let { return it }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return "This device does not provide an alarm service."
        val intent = Intent(context, PaymentReminderReceiver::class.java).apply {
            putExtra("REMINDER_TITLE", if (reminder.reminderType == "COLLECTION") "Customer Collection Due" else "Vendor Payment Due")
            putExtra("PARTY_NAME", reminder.partyName)
            putExtra("AMOUNT", reminder.amount)
            putExtra("REMINDER_TYPE", reminder.reminderType)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.scheduledTimeMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, reminder.scheduledTimeMillis, pendingIntent)
            }
            Log.d("PaymentReminderManager", "Scheduled reminder ID ${reminder.id} for ${reminder.partyName} at ${reminder.scheduledTimeMillis}")
            return null
        } catch (e: Exception) {
            Log.e("PaymentReminderManager", "Error scheduling alarm", e)
            return "Could not schedule the reminder: ${e.message ?: "the system refused the alarm"}"
        }
    }

    fun cancelReminder(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, PaymentReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
