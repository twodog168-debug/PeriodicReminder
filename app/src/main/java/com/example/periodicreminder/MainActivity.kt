package com.example.periodicreminder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.graphics.Rect
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var countInput: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var startLabel: TextView
    private lateinit var startModeGroup: RadioGroup
    private lateinit var runningCountLabel: TextView
    private var keyboardWasVisible = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var chosenStart = System.currentTimeMillis() + 60_000L
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::list.isInitialized) renderList()
            refreshHandler.postDelayed(this, 1000L)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && ::root.isInitialized) {
            val focused = currentFocus
            if (focused is EditText) {
                val rect = Rect()
                focused.getGlobalVisibleRect(rect)
                val tappedAnotherInput = findEditTextAt(root, ev.rawX.toInt(), ev.rawY.toInt())
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt()) && tappedAnotherInput == null) {
                    focused.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(focused.windowToken, 0)
                    root.requestFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun findEditTextAt(view: View, x: Int, y: Int): EditText? {
        if (view is EditText) {
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            if (rect.contains(x, y)) return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findEditTextAt(view.getChildAt(i), x, y)
                if (found != null) return found
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.statusBarColor = Color.rgb(245, 247, 251)
        window.navigationBarColor = Color.rgb(245, 247, 251)
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        requestNotificationPermission()
        buildUi()
        restoreAlarms()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) renderList()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 1000L)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildUi() {
        scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(245, 247, 251)); clipToPadding = false }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            isFocusableInTouchMode = true
        }
        scroll.addView(root)
        setContentView(scroll)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            root.getWindowVisibleDisplayFrame(visibleFrame)
            val keyboardHeight = root.rootView.height - visibleFrame.bottom
            val keyboardVisible = keyboardHeight > dp(150)
            if (keyboardWasVisible && !keyboardVisible) {
                (currentFocus as? EditText)?.let { focused ->
                    focused.clearFocus()
                    root.requestFocus()
                }
            }
            keyboardWasVisible = keyboardVisible
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(8), dp(2), dp(18)) }
        header.addView(TextView(this).apply {
            text = "周期提醒"
            textSize = 28f
            setTextColor(Color.rgb(27, 40, 60))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "把重要的事，交给提醒来记住。"
            textSize = 14f
            setTextColor(Color.rgb(119, 130, 148))
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(header)

        val formCard = card()
        formCard.addView(sectionTitle("新建提醒", "设置内容和重复规则"))
        formCard.addView(fieldLabel("提醒内容"))
        titleInput = EditText(this).apply {
            hint = "例如：喝水、休息、吃药"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.rgb(38, 48, 65))
            setHintTextColor(Color.rgb(155, 165, 180))
            background = rounded(Color.rgb(246, 248, 251), 12, Color.rgb(230, 234, 241), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        formCard.addView(titleInput, matchWrap())

        formCard.addView(fieldLabel("提醒间隔"))
        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        intervalInput = EditText(this).apply {
            hint = "数值"; setText("60"); inputType = 2; textSize = 15f; setSingleLine(true)
            setTextColor(Color.rgb(38, 48, 65)); background = rounded(Color.rgb(246, 248, 251), 12, Color.rgb(230, 234, 241), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("分钟", "小时", "天"))
            background = rounded(Color.rgb(246, 248, 251), 12, Color.rgb(230, 234, 241), 1)
        }
        intervalRow.addView(intervalInput, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        intervalRow.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        intervalRow.addView(unitSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        formCard.addView(intervalRow, matchWrap())

        formCard.addView(fieldLabel("首次提醒时间"))
        startModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "按创建时间开始（创建后等待一个间隔）"
                textSize = 13f
                setTextColor(Color.rgb(65, 79, 99))
            })
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "自定义首次提醒时间"
                textSize = 13f
                setTextColor(Color.rgb(65, 79, 99))
            })
            check(getChildAt(0).id)
        }
        formCard.addView(startModeGroup, matchWrap())
        startLabel = TextView(this).apply {
            text = "◷   " + dateFormat.format(Date(chosenStart)) + "     选择时间"
            textSize = 14f
            setTextColor(Color.rgb(45, 91, 139))
            background = rounded(Color.rgb(239, 246, 253), 12, Color.rgb(218, 232, 247), 1)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        startLabel.setOnClickListener { chooseDateTime() }
        formCard.addView(startLabel, matchWrap())
        startLabel.visibility = View.GONE
        startModeGroup.setOnCheckedChangeListener { _, checkedId ->
            startLabel.visibility = if (checkedId == startModeGroup.getChildAt(1).id) View.VISIBLE else View.GONE
        }

        formCard.addView(fieldLabel("循环次数"))
        countInput = EditText(this).apply {
            hint = "默认 10 次，填写 0 表示无限循环"
            setText("10"); inputType = 2; textSize = 15f; setSingleLine(true)
            setTextColor(Color.rgb(38, 48, 65)); background = rounded(Color.rgb(246, 248, 251), 12, Color.rgb(230, 234, 241), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        formCard.addView(countInput, matchWrap())

        val add = TextView(this).apply {
            text = "＋   创建提醒"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(41, 111, 183), 13)
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true
            isFocusable = true
        }
        val addLp = matchWrap().apply { topMargin = dp(18) }
        formCard.addView(add, addLp)
        add.setOnClickListener { createReminder() }
        root.addView(formCard, matchWrap())

        val taskHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(24), dp(2), dp(10))
        }
        taskHeader.addView(TextView(this).apply {
            text = "提醒列表"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(34, 45, 62))
        })
        taskHeader.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        runningCountLabel = TextView(this).apply {
            text = "0 个提醒运行中"; textSize = 11f; setTextColor(Color.rgb(40, 132, 104))
            background = rounded(Color.rgb(230, 246, 239), 20); setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        taskHeader.addView(runningCountLabel)
        root.addView(taskHeader)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, matchWrap())
        renderList()
    }

    private fun createReminder() {
        val name = titleInput.text.toString().trim()
        val n = intervalInput.text.toString().toLongOrNull()
        val loops = countInput.text.toString().toIntOrNull()
        if (name.isEmpty()) { toast("请填写提醒内容"); titleInput.requestFocus(); return }
        if (n == null || n <= 0L) { toast("提醒间隔必须大于0"); intervalInput.requestFocus(); return }
        if (loops == null || loops < 0) { toast("循环次数不能小于0"); countInput.requestFocus(); return }
        val multiplier = when (unitSpinner.selectedItemPosition) { 0 -> 60_000L; 1 -> 3_600_000L; else -> 86_400_000L }
        val now = System.currentTimeMillis()
        val useCustomTime = startModeGroup.checkedRadioButtonId == startModeGroup.getChildAt(1).id
        val firstAt = if (useCustomTime) {
            if (chosenStart <= now) now + 1000L else chosenStart
        } else {
            now + n * multiplier
        }
        val r = Reminder(
            id = ReminderStore.nextId(this), title = name, intervalMillis = n * multiplier,
            startAt = firstAt, totalCount = loops, nextAt = firstAt
        )
        val all = ReminderStore.all(this).apply { add(r) }
        ReminderStore.save(this, all)
        AlarmScheduler.schedule(this, r)
        titleInput.setText("")
        startModeGroup.check(startModeGroup.getChildAt(0).id)
        titleInput.clearFocus(); intervalInput.clearFocus(); countInput.clearFocus(); root.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(titleInput.windowToken, 0)
        renderList()
        toast("提醒已创建")
    }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = title; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(34, 45, 62)) })
        addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 12f; setTextColor(Color.rgb(139, 149, 165)); setPadding(0, dp(3), 0, dp(10)) })
    }

    private fun fieldLabel(s: String) = TextView(this).apply {
        text = s; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.rgb(82, 96, 116)); setPadding(dp(1), dp(15), 0, dp(7))
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        val oldScrollY = scroll.scrollY
        list.removeAllViews()
        val reminders = ReminderStore.all(this).sortedByDescending { it.id }
        if (::runningCountLabel.isInitialized) {
            val running = reminders.count { it.enabled }
            runningCountLabel.text = "$running 个提醒运行中"
        }
        reminders.forEach { r ->
            val itemCard = card().apply { setPadding(dp(15), dp(14), dp(15), dp(13)) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = r.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(34, 45, 62)); maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val statusText = if (r.enabled) "运行中" else "已停止"
            val statusColor = if (r.enabled) Color.rgb(34, 132, 98) else Color.rgb(183, 91, 69)
            val statusBg = if (r.enabled) Color.rgb(231, 246, 239) else Color.rgb(252, 239, 235)
            top.addView(TextView(this).apply {
                text = statusText; textSize = 11f; setTextColor(statusColor); background = rounded(statusBg, 20)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
            itemCard.addView(top)

            val unitText = when {
                r.intervalMillis % 86_400_000L == 0L -> "${r.intervalMillis / 86_400_000L} 天"
                r.intervalMillis % 3_600_000L == 0L -> "${r.intervalMillis / 3_600_000L} 小时"
                else -> "${r.intervalMillis / 60_000L} 分钟"
            }
            val total = if (r.totalCount == 0) "无限" else r.totalCount.toString()
            itemCard.addView(TextView(this).apply {
                text = "每隔 $unitText   ·   已通知 ${r.sentCount}/$total"
                textSize = 12f; setTextColor(Color.rgb(119, 131, 149)); setPadding(0, dp(8), 0, dp(8))
            })
            val nextText = when {
                r.enabled -> "下次提醒  ${dateFormat.format(Date(r.nextAt))}"
                else -> "下次提醒  —"
            }
            itemCard.addView(TextView(this).apply {
                text = nextText; textSize = 13f; setTextColor(Color.rgb(54, 91, 129))
                background = rounded(Color.rgb(242, 247, 252), 9); setPadding(dp(10), dp(9), dp(10), dp(9))
            })

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            val edit = actionButton("编辑", Color.rgb(235, 241, 248), Color.rgb(57, 91, 127))
            edit.setOnClickListener { showEditDialog(r) }
            val stop = actionButton("停止", Color.rgb(252, 241, 235), Color.rgb(172, 91, 62)).apply {
                isEnabled = r.enabled
                alpha = if (r.enabled) 1f else 0.45f
            }
            stop.setOnClickListener {
                val all = ReminderStore.all(this@MainActivity)
                val item = all.firstOrNull { it.id == r.id } ?: return@setOnClickListener
                item.enabled = false; item.nextAt = 0L
                AlarmScheduler.cancel(this@MainActivity, item.id)
                ReminderStore.save(this@MainActivity, all); renderList(); toast("提醒已停止")
            }
            val delete = actionButton("删除", Color.rgb(244, 245, 247), Color.rgb(111, 120, 134))
            delete.setOnClickListener {
                AlarmScheduler.cancel(this@MainActivity, r.id)
                ReminderStore.save(this@MainActivity, ReminderStore.all(this@MainActivity).filterNot { it.id == r.id })
                renderList()
            }
            actions.addView(edit, LinearLayout.LayoutParams(0, dp(40), 1f))
            actions.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            actions.addView(stop, LinearLayout.LayoutParams(0, dp(40), 1f))
            actions.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            actions.addView(delete, LinearLayout.LayoutParams(0, dp(40), 1f))
            itemCard.addView(actions)
            list.addView(itemCard, matchWrap().apply { bottomMargin = dp(12) })
        }
        if (list.childCount == 0) {
            val empty = card().apply { gravity = android.view.Gravity.CENTER; setPadding(dp(20), dp(28), dp(20), dp(28)) }
            empty.addView(TextView(this).apply { text = "还没有提醒"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER; setTextColor(Color.rgb(87, 101, 120)) })
            empty.addView(TextView(this).apply { text = "在上方创建一条提醒，它就会出现在这里。"; textSize = 12f; gravity = android.view.Gravity.CENTER; setTextColor(Color.rgb(145, 154, 168)); setPadding(0, dp(6), 0, 0) })
            list.addView(empty, matchWrap())
        }
        if (oldScrollY > 0) scroll.post { scroll.scrollTo(0, oldScrollY) }
    }

    private fun showEditDialog(reminder: Reminder) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val nameInput = EditText(this).apply {
            setSingleLine(true); setText(reminder.title); hint = "提醒内容"; textSize = 15f
        }
        content.addView(fieldLabel("提醒名称")); content.addView(nameInput, matchWrap())

        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val intervalEdit = EditText(this).apply {
            setSingleLine(true); inputType = 2; textSize = 15f
            val (value, unitIndex) = when {
                reminder.intervalMillis % 86_400_000L == 0L -> Pair(reminder.intervalMillis / 86_400_000L, 2)
                reminder.intervalMillis % 3_600_000L == 0L -> Pair(reminder.intervalMillis / 3_600_000L, 1)
                else -> Pair(reminder.intervalMillis / 60_000L, 0)
            }
            setText(value.toString())
            tag = unitIndex
        }
        val unitEdit = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("分钟", "小时", "天"))
            setSelection(intervalEdit.tag as Int)
        }
        intervalRow.addView(intervalEdit, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        intervalRow.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        intervalRow.addView(unitEdit, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(fieldLabel("提醒间隔")); content.addView(intervalRow, matchWrap())

        var editedAt = if (reminder.nextAt > System.currentTimeMillis()) reminder.nextAt else System.currentTimeMillis() + reminder.intervalMillis
        val timeButton = TextView(this).apply {
            text = "◷   " + dateFormat.format(Date(editedAt)) + "     修改时间"
            textSize = 14f; setTextColor(Color.rgb(45, 91, 139))
            background = rounded(Color.rgb(239, 246, 253), 12, Color.rgb(218, 232, 247), 1)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        timeButton.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = editedAt }
            DatePickerDialog(this, { _, y, m, d ->
                c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d)
                TimePickerDialog(this, { _, h, min ->
                    c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0)
                    editedAt = c.timeInMillis
                    timeButton.text = "◷   " + dateFormat.format(Date(editedAt)) + "     修改时间"
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        content.addView(fieldLabel("下次提醒时间")); content.addView(timeButton, matchWrap())
        content.addView(TextView(this).apply {
            text = if (reminder.enabled) "保存后将按新的时间和间隔继续提醒。" else "这条提醒已经停止；编辑只修改内容，不会自动重新启动。"
            textSize = 12f; setTextColor(Color.rgb(130, 140, 155)); setPadding(0, dp(10), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑提醒")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = nameInput.text.toString().trim()
                val value = intervalEdit.text.toString().toLongOrNull()
                if (newName.isEmpty()) { toast("提醒名称不能为空"); return@setOnClickListener }
                if (value == null || value <= 0L) { toast("提醒间隔必须大于0"); return@setOnClickListener }
                val factor = when (unitEdit.selectedItemPosition) { 0 -> 60_000L; 1 -> 3_600_000L; else -> 86_400_000L }
                val newInterval = value * factor
                val all = ReminderStore.all(this)
                val index = all.indexOfFirst { it.id == reminder.id }
                if (index < 0) { dialog.dismiss(); return@setOnClickListener }
                val old = all[index]
                val safeAt = if (editedAt <= System.currentTimeMillis()) System.currentTimeMillis() + 1000L else editedAt
                val updated = old.copy(
                    title = newName,
                    intervalMillis = newInterval,
                    startAt = safeAt,
                    nextAt = if (old.enabled) safeAt else 0L
                )
                all[index] = updated
                AlarmScheduler.cancel(this, updated.id)
                ReminderStore.save(this, all)
                if (updated.enabled) AlarmScheduler.schedule(this, updated)
                dialog.dismiss()
                renderList()
                toast("提醒已更新")
            }
        }
        dialog.show()
    }

    private fun actionButton(textValue: String, bg: Int, fg: Int) = TextView(this).apply {
        text = textValue; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = android.view.Gravity.CENTER; setTextColor(fg); background = rounded(bg, 10)
        isClickable = true; isFocusable = true
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, 17, Color.rgb(235, 238, 243), 1)
        elevation = dp(2).toFloat()
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(height, 1) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun chooseDateTime() {
        val c = Calendar.getInstance().apply { timeInMillis = chosenStart }
        DatePickerDialog(this, { _, y, m, d ->
            c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(this, { _, h, min ->
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0)
                chosenStart = c.timeInMillis
                startLabel.text = "◷   " + dateFormat.format(Date(chosenStart)) + "     修改时间"
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun restoreAlarms() {
        val all = ReminderStore.all(this)
        var changed = false
        val now = System.currentTimeMillis()
        all.filter { it.enabled }.forEach { r ->
            if (r.nextAt <= now) { r.nextAt = now + 1000L; changed = true }
            AlarmScheduler.schedule(this, r)
        }
        if (changed) ReminderStore.save(this, all)
        renderList()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
