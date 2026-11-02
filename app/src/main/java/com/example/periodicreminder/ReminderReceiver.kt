package com.example.periodicreminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("reminder_id", -1)
        if (id < 0) return
        val items = ReminderStore.all(context)
        val r = items.firstOrNull { it.id == id } ?: return
        if (!r.enabled) return

        createChannel(context)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("周期提醒")
            .setContentText(r.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        try { NotificationManagerCompat.from(context).notify(id, notification) } catch (_: SecurityException) { }

        r.sentCount += 1
        if (r.totalCount > 0 && r.sentCount >= r.totalCount) {
            r.enabled = false
            AlarmScheduler.cancel(context, id)
        } else {
            // Next interval starts when this receiver actually handles the alarm.
            r.nextAt = System.currentTimeMillis() + r.intervalMillis
            AlarmScheduler.schedule(context, r)
        }
        ReminderStore.save(context, items)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "周期提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object { const val CHANNEL_ID = "periodic_reminders" }
}
