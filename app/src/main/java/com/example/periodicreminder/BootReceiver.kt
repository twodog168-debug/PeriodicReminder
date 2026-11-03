package com.example.periodicreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val items = ReminderStore.all(context)
        val now = System.currentTimeMillis()
        items.filter { it.enabled }.forEach { r ->
            // 设备关机期间错过的提醒不补发，直接定位到原周期中最近的未来时刻。
            if (r.nextAt <= now) {
                r.nextAt = ReminderTiming.nextOccurrenceAfter(r.nextAt, r.intervalMillis, now)
            }
            AlarmScheduler.schedule(context, r)
        }
        ReminderStore.save(context, items)
    }
}
