package com.example.periodicreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    val title: String,
    val intervalMillis: Long,
    val startAt: Long,
    val totalCount: Int, // 0 = unlimited
    var sentCount: Int = 0,
    var nextAt: Long = startAt,
    var enabled: Boolean = true
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("intervalMillis", intervalMillis)
        put("startAt", startAt); put("totalCount", totalCount); put("sentCount", sentCount)
        put("nextAt", nextAt); put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            id = o.getInt("id"),
            title = o.getString("title"),
            intervalMillis = o.getLong("intervalMillis"),
            startAt = o.getLong("startAt"),
            totalCount = o.getInt("totalCount"),
            sentCount = o.optInt("sentCount", 0),
            nextAt = o.optLong("nextAt", o.getLong("startAt")),
            enabled = o.optBoolean("enabled", true)
        )
    }
}

object ReminderStore {
    private const val PREFS = "reminders"
    private const val KEY = "items"

    fun all(context: Context): MutableList<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return MutableList(array.length()) { Reminder.fromJson(array.getJSONObject(it)) }
    }

    fun save(context: Context, reminders: List<Reminder>) {
        val array = JSONArray()
        reminders.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun nextId(context: Context): Int = (all(context).maxOfOrNull { it.id } ?: 0) + 1
}
