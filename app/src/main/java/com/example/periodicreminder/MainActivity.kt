package com.example.periodicreminder

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var chosenStart = System.currentTimeMillis() + 60_000L
    private lateinit var startLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        buildUi()
        restoreAlarms()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "周期提醒"
            textSize = 26f
            setTextColor(0xFF202124.toInt())
        })
        root.addView(TextView(this).apply {
            text = "设置一次，按分钟、小时或天自动重复通知。"
            textSize = 14f
            setPadding(0, 8, 0, 20)
        })

        val title = EditText(this).apply { hint = "提醒内容，例如：喝水、休息" }
        root.addView(label("提醒内容"))
        root.addView(title)

        root.addView(label("提醒间隔"))
        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val interval = EditText(this).apply {
            hint = "间隔数值"; setText("60"); inputType = 2
        }
        intervalRow.addView(interval, LinearLayout.LayoutParams(0, -2, 1f))
        val unit = Spinner(this)
        unit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("分钟", "小时", "天"))
        intervalRow.addView(unit, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(intervalRow)

        root.addView(label("首次提醒时间"))
        startLabel = TextView(this).apply {
            textSize = 16f
            setPadding(0, 12, 0, 12)
            text = "选择开始时间：" + dateFormat.format(Date(chosenStart))
        }
        startLabel.setOnClickListener { chooseDateTime() }
        root.addView(startLabel)

        root.addView(label("循环次数（0 = 无限循环）"))
        val count = EditText(this).apply { hint = "例如 10；0 表示无限"; setText("0"); inputType = 2 }
        root.addView(count)

        val add = Button(this).apply { text = "创建提醒" }
        root.addView(add)
        add.setOnClickListener {
            val name = title.text.toString().trim()
            val n = interval.text.toString().toLongOrNull()
            val loops = count.text.toString().toIntOrNull()
            if (name.isEmpty()) { toast("请填写提醒内容"); return@setOnClickListener }
            if (n == null || n <= 0L) { toast("提醒间隔必须大于0"); return@setOnClickListener }
            if (loops == null || loops < 0) { toast("循环次数不能小于0"); return@setOnClickListener }
            val multiplier = when (unit.selectedItemPosition) {
                0 -> 60_000L
                1 -> 3_600_000L
                else -> 86_400_000L
            }
            val r = Reminder(
                id = ReminderStore.nextId(this),
                title = name,
                intervalMillis = n * multiplier,
                startAt = chosenStart,
                totalCount = loops,
                nextAt = chosenStart
            )
            val all = ReminderStore.all(this).apply { add(r) }
            ReminderStore.save(this, all)
            AlarmScheduler.schedule(this, r)
            title.setText("")
            renderList()
            toast("提醒已创建")
        }

        val permissionButton = Button(this).apply { text = "检查通知和精确闹钟权限" }
        root.addView(permissionButton)
        permissionButton.setOnClickListener { openAlarmSettings() }

        root.addView(label("已创建的提醒"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        renderList()
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setPadding(0, 18, 0, 4)
        setTextColor(0xFF444444.toInt())
    }

    private fun chooseDateTime() {
        val c = Calendar.getInstance().apply { timeInMillis = chosenStart }
        DatePickerDialog(this, { _, y, m, d ->
            c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(this, { _, h, min ->
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0)
                chosenStart = c.timeInMillis
                startLabel.text = "选择开始时间：" + dateFormat.format(Date(chosenStart))
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        ReminderStore.all(this).forEach { r ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFFF1F3F4.toInt())
            }
            val unitText = when {
                r.intervalMillis % 86_400_000L == 0L -> "${r.intervalMillis / 86_400_000L}天"
                r.intervalMillis % 3_600_000L == 0L -> "${r.intervalMillis / 3_600_000L}小时"
                else -> "${r.intervalMillis / 60_000L}分钟"
            }
            card.addView(TextView(this).apply {
                text = r.title
                textSize = 18f
                setTextColor(0xFF202124.toInt())
            })
            card.addView(TextView(this).apply {
                val total = if (r.totalCount == 0) "无限" else r.totalCount.toString()
                text = "间隔：$unitText · 已通知：${r.sentCount}/$total · " +
                    if (r.enabled) "运行中；下次：" + dateFormat.format(Date(r.nextAt)) else "已停止"
                textSize = 13f
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val toggle = Button(this).apply { text = if (r.enabled) "暂停" else "恢复" }
            toggle.setOnClickListener {
                val all = ReminderStore.all(this@MainActivity)
                val item = all.firstOrNull { it.id == r.id } ?: return@setOnClickListener
                if (item.enabled) {
                    item.enabled = false
                    AlarmScheduler.cancel(this@MainActivity, item.id)
                } else {
                    item.enabled = true
                    if (item.nextAt <= System.currentTimeMillis()) item.nextAt = System.currentTimeMillis() + item.intervalMillis
                    AlarmScheduler.schedule(this@MainActivity, item)
                }
                ReminderStore.save(this@MainActivity, all); renderList()
            }
            val delete = Button(this).apply { text = "删除" }
            delete.setOnClickListener {
                AlarmScheduler.cancel(this@MainActivity, r.id)
                ReminderStore.save(this@MainActivity, ReminderStore.all(this@MainActivity).filterNot { it.id == r.id })
                renderList()
            }
            actions.addView(toggle); actions.addView(delete)
            card.addView(actions)
            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
            list.addView(card, lp)
        }
        if (list.childCount == 0) list.addView(TextView(this).apply { text = "还没有提醒任务"; setPadding(0, 12, 0, 12) })
    }

    private fun restoreAlarms() {
        ReminderStore.all(this).filter { it.enabled }.forEach { AlarmScheduler.schedule(this, it) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun openAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                return
            }
        }
        toast("请同时确认系统设置中允许本应用发送通知，并允许自启动/后台弹出通知。")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
