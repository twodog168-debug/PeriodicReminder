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

        // 更新通知计数并保存前，当前这条通知序号就是 sentCount + 1。
        val occurrence = r.sentCount + 1
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, NotificationIds.next(context), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (r.totalCount == 0) "第 $occurrence 次提醒" else "第 $occurrence 次提醒（共 ${r.totalCount} 次）"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(1)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NotificationIds.next(context), notification)
        } catch (_: SecurityException) { }

        r.sentCount = occurrence
        if (r.totalCount > 0 && r.sentCount >= r.totalCount) {
            r.enabled = false
            AlarmScheduler.cancel(context, id)
        } else {
            // 下一次间隔从本次实际触发处理时刻开始计算。
            r.nextAt = System.currentTimeMillis() + r.intervalMillis
            AlarmScheduler.schedule(context, r)
        }
        ReminderStore.save(context, items)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "提醒通知", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object { const val CHANNEL_ID = "periodic_reminders" }
}
