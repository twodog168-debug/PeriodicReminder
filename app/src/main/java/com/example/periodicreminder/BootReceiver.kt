package com.example.periodicreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val items = ReminderStore.all(context)
        val now = System.currentTimeMillis()
        items.filter { it.enabled }.forEach { r ->
            // If the device was off when a reminder was due, notify once after reboot,
            // then continue from the actual delivery time rather than sending a backlog.
            if (r.nextAt < now) r.nextAt = now + 1000L
            AlarmScheduler.schedule(context, r)
        }
        ReminderStore.save(context, items)
    }
}
