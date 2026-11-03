package com.example.periodicreminder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.graphics.Rect
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val BRAND_RED = Color.rgb(204, 48, 78)

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var countInput: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var startModeGroup: RadioGroup
    private lateinit var runningCountLabel: TextView
    private var keyboardWasVisible = false
    private var interactionInProgress = false
    private var spinnerInteractionInProgress = false
    private var spinnerImeTarget: EditText? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var editDialogOpen = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var chosenStart = System.currentTimeMillis() + 60_000L

    // 列表 ViewHolder 缓存，避免每秒重建整棵 View 树。
    private val cardHolders = HashMap<Int, ReminderCardHolder>()
    private var lastReminders: List<Reminder> = emptyList()
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // 用存储变更监听替代 1 秒轮询。
    private val cardPrefs by lazy {
        getSharedPreferences(ReminderStore.PREFS, MODE_PRIVATE)
    }
    private val storageListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ReminderStore.KEY &&
                !showingCreatePage && !editDialogOpen && !interactionInProgress) {
                tickList()
            }
        }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            interactionInProgress = true
            touchDownX = ev.rawX
            touchDownY = ev.rawY
        }

        val handled = super.dispatchTouchEvent(ev)

        if (ev.action == MotionEvent.ACTION_UP) {
            val dx = ev.rawX - touchDownX
            val dy = ev.rawY - touchDownY
            val isTap = dx * dx + dy * dy <= touchSlop * touchSlop

            // 只有轻点非输入框区域才收起键盘；滑动页面时保留焦点和键盘。
            if (isTap && ::root.isInitialized &&
                findEditTextAt(root, ev.rawX.toInt(), ev.rawY.toInt()) == null &&
                findSpinnerAt(root, ev.rawX.toInt(), ev.rawY.toInt()) == null) {
                val focused = currentFocus
                if (focused is EditText) {
                    focused.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(focused.windowToken, 0)
                    root.requestFocus()
                }
            }
            interactionInProgress = false
        } else if (ev.action == MotionEvent.ACTION_CANCEL) {
            interactionInProgress = false
        }
        return handled
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

    // Spinner 弹出选项时可能暂时收起输入法；点击 Spinner 不应触发全局清焦点逻辑。
    private fun findSpinnerAt(view: View, x: Int, y: Int): Spinner? {
        if (view is Spinner) {
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            if (rect.contains(x, y)) return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSpinnerAt(view.getChildAt(i), x, y)
                if (found != null) return found
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.rgb(255, 247, 248)
        window.navigationBarColor = Color.rgb(255, 247, 248)
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        requestNotificationPermission()
        buildUi()
        restoreAlarms()
    }

    override fun onResume() {
        super.onResume()
        cardPrefs.registerOnSharedPreferenceChangeListener(storageListener)
        if (::list.isInitialized && !showingCreatePage) renderList()
    }

    override fun onPause() {
        cardPrefs.unregisterOnSharedPreferenceChangeListener(storageListener)
        super.onPause()
    }

    private var showingCreatePage = false

    override fun onBackPressed() {
        if (showingCreatePage) {
            showMainPage()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildUi() {
        showingCreatePage = false
        scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(255, 247, 248)); clipToPadding = false; isFillViewport = true }
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
            if (keyboardWasVisible && !keyboardVisible && !spinnerInteractionInProgress) {
                (currentFocus as? EditText)?.let { focused -> focused.clearFocus(); root.requestFocus() }
            }
            keyboardWasVisible = keyboardVisible
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(22))
        }
        val headerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerText.addView(TextView(this).apply {
            text = "自设通知"; textSize = 28f; setTextColor(Color.rgb(67, 39, 62))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        headerText.addView(TextView(this).apply {
            text = "把重要的事，交给通知来记住。"; textSize = 14f
            setTextColor(Color.rgb(137, 112, 132)); setPadding(0, dp(5), 0, 0)
        })
        header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        val create = TextView(this).apply {
            text = "+"; textSize = 30f; typeface = Typeface.DEFAULT
            gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(BRAND_RED, 16)
            isClickable = true; isFocusable = true
            setOnClickListener { showCreatePage() }
        }
        header.addView(create, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val taskHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(10))
        }
        taskHeader.addView(TextView(this).apply {
            text = "通知列表"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(67, 39, 62))
        })
        taskHeader.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        runningCountLabel = TextView(this).apply {
            text = "0 个通知运行中"; textSize = 11f; setTextColor(Color.rgb(111, 76, 160))
            background = rounded(Color.rgb(245, 239, 252), 20); setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        taskHeader.addView(runningCountLabel)
        root.addView(taskHeader)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, matchWrap())
        renderList()
    }

    private fun showMainPage() {
        val wasCreate = showingCreatePage
        buildUi()
        if (wasCreate) scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun showCreatePage() {
        showingCreatePage = true
        chosenStart = System.currentTimeMillis() + 3_600_000L
        scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(255, 247, 248)); clipToPadding = false; isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            isFocusableInTouchMode = true
        }
        scroll.addView(root)
        // 创建按钮放在 ScrollView 外部，固定于页面底部；键盘弹出时由 adjustResize 将其顶到键盘上方。
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(255, 247, 248))
        }
        pageLayout.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(pageLayout)
        // 键盘弹出时，只滚动到当前正在编辑的输入框，绝不直接跳到表单底部。
        // 这样点击“通知内容”时，页面不会突然跳到“循环次数”。
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val frame = Rect()
            scroll.getWindowVisibleDisplayFrame(frame)
            val visible = scroll.rootView.height - frame.bottom > dp(150)
            if (visible && !keyboardWasVisible) {
                scroll.post {
                    val focused = currentFocus as? EditText
                    if (focused != null) {
                        val rect = Rect()
                        if (focused.getGlobalVisibleRect(rect)) {
                            val bottomLimit = frame.bottom - dp(16)
                            val overflow = rect.bottom - bottomLimit
                            if (overflow > 0) scroll.smoothScrollBy(0, overflow)
                        }
                    }
                }
            } else if (!visible && keyboardWasVisible) {
                // Spinner 弹出菜单可能暂时隐藏输入法；不要在选择期间清除原输入框焦点。
                if (!spinnerInteractionInProgress) {
                    (currentFocus as? EditText)?.let { focused -> focused.clearFocus(); root.requestFocus() }
                }
            }
            keyboardWasVisible = visible
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "新建通知"; textSize = 23f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(67, 39, 62))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        val backButton = TextView(this).apply {
            text = "返回"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = rounded(BRAND_RED, 11)
            isClickable = true
            isFocusable = true
            setOnClickListener { hideKeyboardAndClearFocus(); showMainPage() }
        }
        heading.addView(backButton, LinearLayout.LayoutParams(dp(68), dp(40)).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        root.addView(heading, matchWrap().apply { bottomMargin = dp(18) })

        val formCard = card()
        formCard.addView(fieldLabel("通知内容"))
        titleInput = EditText(this).apply {
            hint = "例如：喝水、休息、吃药"; textSize = 15f; setSingleLine(true)
            setTextColor(Color.rgb(73, 48, 69)); setHintTextColor(Color.rgb(155, 165, 180))
            background = rounded(Color.rgb(255, 249, 250), 12, Color.rgb(237, 222, 230), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        formCard.addView(titleInput, matchWrap())

        formCard.addView(fieldLabel("通知间隔"))
        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        intervalInput = EditText(this).apply {
            hint = "数值"; setText("1"); inputType = 2; textSize = 15f; setSingleLine(true)
            setTextColor(Color.rgb(73, 48, 69)); background = rounded(Color.rgb(255, 249, 250), 12, Color.rgb(237, 222, 230), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("分钟", "小时", "天"))
            background = rounded(Color.rgb(255, 249, 250), 12, Color.rgb(237, 222, 230), 1)
            setSelection(1)
            preserveImeWhenChoosingUnit(this)
        }
        intervalRow.addView(intervalInput, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        intervalRow.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        intervalRow.addView(unitSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        formCard.addView(intervalRow, matchWrap())

        formCard.addView(fieldLabel("首次通知时间"))
        startModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId(); text = "按创建时间开始（创建后等待一个间隔）"
                textSize = 13f; setTextColor(Color.rgb(91, 67, 91))
            })
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId(); text = "自定义首次通知时间"
                textSize = 13f; setTextColor(Color.rgb(91, 67, 91))
            })
            check(getChildAt(0).id)
        }
        formCard.addView(startModeGroup, matchWrap())
        val startDateValue = TextView(this).apply {
            text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(chosenStart))
            textSize = 13f; setTextColor(Color.rgb(111, 76, 160)); gravity = android.view.Gravity.CENTER
        }
        val startClockValue = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(chosenStart))
            textSize = 13f; setTextColor(Color.rgb(111, 76, 160)); gravity = android.view.Gravity.CENTER
        }
        val dateButton = actionButton("选择日期", Color.rgb(245, 239, 252), Color.rgb(111, 76, 160)).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { chooseDateOnly(chosenStart) { chosenStart = it; startDateValue.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) } }
        }
        val clockButton = actionButton("调整时间", Color.rgb(245, 239, 252), Color.rgb(111, 76, 160)).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { chooseTimeOnly(chosenStart) { chosenStart = it; startClockValue.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } }
        }
        val dateTimeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val dateColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        dateColumn.addView(dateButton, matchWrap()); dateColumn.addView(startDateValue, matchWrap())
        val clockColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        clockColumn.addView(clockButton, matchWrap()); clockColumn.addView(startClockValue, matchWrap())
        dateTimeRow.addView(dateColumn, LinearLayout.LayoutParams(0, -2, 1f))
        dateTimeRow.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        dateTimeRow.addView(clockColumn, LinearLayout.LayoutParams(0, -2, 1f))
        formCard.addView(dateTimeRow, matchWrap())
        dateTimeRow.visibility = View.GONE
        startModeGroup.setOnCheckedChangeListener { _, checkedId ->
            dateTimeRow.visibility = if (checkedId == startModeGroup.getChildAt(1).id) View.VISIBLE else View.GONE
        }

        formCard.addView(fieldLabel("循环次数"))
        countInput = EditText(this).apply {
            hint = "默认 10 次，填写 0 表示无限循环"; setText("10")
            inputType = 2; textSize = 15f; setSingleLine(true)
            setTextColor(Color.rgb(73, 48, 69)); background = rounded(Color.rgb(255, 249, 250), 12, Color.rgb(237, 222, 230), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        formCard.addView(countInput, matchWrap())
        root.addView(formCard, matchWrap())

        val add = actionButton("创建通知", BRAND_RED, Color.WHITE).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { createReminder() }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
            setBackgroundColor(Color.rgb(255, 247, 248))
            elevation = dp(8).toFloat()
            addView(add, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ))
        }
        pageLayout.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun hideKeyboardAndClearFocus() {
        (currentFocus as? EditText)?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        if (::root.isInitialized) root.requestFocus()
    }

    private fun createReminder() {
        val name = titleInput.text.toString().trim()
        val n = intervalInput.text.toString().toLongOrNull()
        val loops = countInput.text.toString().toIntOrNull()
        if (name.isEmpty()) { toast("请填写通知内容"); titleInput.requestFocus(); return }
        if (n == null || n <= 0L) { toast("通知间隔必须大于0"); intervalInput.requestFocus(); return }
        if (loops == null || loops < 0) { toast("循环次数不能小于0"); countInput.requestFocus(); return }
        val multiplier = when (unitSpinner.selectedItemPosition) { 0 -> 60_000L; 1 -> 3_600_000L; else -> 86_400_000L }
        val now = System.currentTimeMillis()
        val useCustomTime = startModeGroup.checkedRadioButtonId == startModeGroup.getChildAt(1).id
        val intervalMillis = n * multiplier
        val firstAt = if (useCustomTime) chosenStart else now + intervalMillis
        // 自定义时间即使已经过去，也只计算最近的未来时刻，不补发历史通知。
        val nextAt = if (useCustomTime) ReminderTiming.nextOccurrenceAfter(firstAt, intervalMillis, now) else firstAt
        val r = Reminder(
            id = ReminderStore.nextId(this), title = name, intervalMillis = intervalMillis,
            startAt = firstAt, totalCount = loops, nextAt = nextAt
        )
        val all = ReminderStore.all(this).apply { add(r) }
        ReminderStore.save(this, all)
        AlarmScheduler.schedule(this, r)
        hideKeyboardAndClearFocus()
        toast("通知已创建")
        showMainPage()
    }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = title; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(67, 39, 62)) })
        addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 12f; setTextColor(Color.rgb(139, 149, 165)); setPadding(0, dp(3), 0, dp(10)) })
    }

    private fun fieldLabel(s: String) = TextView(this).apply {
        text = s; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.rgb(111, 76, 112)); setPadding(dp(1), dp(15), 0, dp(7))
    }

    /** 存储变更时的增量刷新入口：结构性变化才重建整列表，否则只更新文本。 */
    private fun tickList() {
        if (!::list.isInitialized) return
        val fresh = ReminderStore.all(this).sortedByDescending { it.id }
        if (fresh == lastReminders) return

        val structural = fresh.size != lastReminders.size ||
            fresh.indices.any { i ->
                val a = fresh[i]; val b = lastReminders[i]
                a.id != b.id || a.enabled != b.enabled ||
                a.title != b.title || a.intervalMillis != b.intervalMillis ||
                a.totalCount != b.totalCount
            }
        lastReminders = fresh

        if (structural) {
            renderList()
        } else {
            fresh.forEach { r -> cardHolders[r.id]?.updateTexts(r) }
        }
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        val oldScrollY = scroll.scrollY

        list.removeAllViews()
        cardHolders.clear()

        val reminders = ReminderStore.all(this).sortedByDescending { it.id }
        lastReminders = reminders

        if (::runningCountLabel.isInitialized) {
            val running = reminders.count { it.enabled }
            runningCountLabel.text = "$running 个通知运行中"
        }

        reminders.forEach { r ->
            val holder = buildReminderCard(r)
            cardHolders[r.id] = holder
            list.addView(holder.card, matchWrap().apply { bottomMargin = dp(12) })
        }

        if (reminders.isEmpty()) {
            val empty = card().apply { gravity = android.view.Gravity.CENTER; setPadding(dp(20), dp(28), dp(20), dp(28)) }
            empty.addView(TextView(this).apply { text = "还没有通知"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER; setTextColor(Color.rgb(111, 76, 112)) })
            empty.addView(TextView(this).apply { text = "点击右上角“＋”，添加你的第一条通知。"; textSize = 12f; gravity = android.view.Gravity.CENTER; setTextColor(Color.rgb(145, 154, 168)); setPadding(0, dp(6), 0, 0) })
            list.addView(empty, matchWrap())
        }

        if (oldScrollY > 0) scroll.post { scroll.scrollTo(0, oldScrollY) }
    }

    private fun buildReminderCard(r: Reminder): ReminderCardHolder {
        val itemCard = card().apply { setPadding(dp(15), dp(14), dp(15), dp(13)) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = r.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(67, 39, 62)); maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val statusText = if (r.enabled) "运行中" else "已停止"
        val statusColor = if (r.enabled) Color.rgb(111, 76, 160) else Color.rgb(183, 91, 69)
        val statusBg = if (r.enabled) Color.rgb(245, 239, 252) else Color.rgb(255, 231, 235)
        top.addView(TextView(this).apply {
            text = statusText; textSize = 11f; setTextColor(statusColor); background = rounded(statusBg, 20)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        itemCard.addView(top)

        val meta = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(137, 112, 132)); setPadding(0, dp(8), 0, dp(8))
        }
        itemCard.addView(meta)

        val nextText = TextView(this).apply {
            textSize = 13f; setTextColor(Color.rgb(111, 76, 160))
            background = rounded(Color.rgb(255, 241, 244), 9); setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        itemCard.addView(nextText)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        val edit = actionButton("编辑", Color.rgb(255, 235, 239), Color.rgb(111, 76, 160))
        edit.setOnClickListener { showEditDialog(r) }
        val stop = actionButton(if (r.enabled) "停止" else "开始",
            if (r.enabled) Color.rgb(255, 235, 239) else Color.rgb(245, 239, 252),
            if (r.enabled) Color.rgb(172, 91, 62) else Color.rgb(111, 76, 160))
        stop.setOnClickListener {
            val all = ReminderStore.all(this@MainActivity)
            val index = all.indexOfFirst { it.id == r.id }
            if (index < 0) return@setOnClickListener
            val item = all[index]
            if (item.enabled) {
                item.enabled = false
                item.nextAt = 0L
                AlarmScheduler.cancel(this@MainActivity, item.id)
                ReminderStore.save(this@MainActivity, all)
                toast("通知已停止")
            } else {
                val restartAt = System.currentTimeMillis() + item.intervalMillis
                val restarted = item.copy(startAt = restartAt, nextAt = restartAt, sentCount = 0, enabled = true)
                all[index] = restarted
                ReminderStore.save(this@MainActivity, all)
                AlarmScheduler.schedule(this@MainActivity, restarted)
                toast("通知已重新开始")
            }
            renderList()
        }
        val delete = actionButton("删除", Color.rgb(245, 239, 247), Color.rgb(111, 120, 134))
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

        val holder = ReminderCardHolder(itemCard, meta, nextText)
        holder.updateTexts(r)
        return holder
    }

    private inner class ReminderCardHolder(
        val card: LinearLayout,
        val meta: TextView,
        val nextText: TextView
    ) {
        fun updateTexts(r: Reminder) {
            val total = if (r.totalCount == 0) "无限" else r.totalCount.toString()
            val metaStr = "每隔 ${formatInterval(r.intervalMillis)}   ·   已通知 ${r.sentCount}/$total"
            if (meta.text?.toString() != metaStr) meta.text = metaStr

            val nextStr = if (r.enabled)
                "下次通知  ${dateFormat.format(Date(r.nextAt))}"
            else
                "下次通知  —"
            if (nextText.text?.toString() != nextStr) nextText.text = nextStr
        }
    }

    private fun formatInterval(ms: Long): String = when {
        ms % 86_400_000L == 0L -> "${ms / 86_400_000L} 天"
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L} 小时"
        else -> "${ms / 60_000L} 分钟"
    }

    private fun showEditDialog(reminder: Reminder) {
        if (editDialogOpen) return
        editDialogOpen = true
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val nameInput = EditText(this).apply {
            setSingleLine(true); setText(reminder.title); hint = "通知内容"; textSize = 15f
        }
        content.addView(fieldLabel("通知名称")); content.addView(nameInput, matchWrap())

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
            preserveImeWhenChoosingUnit(this)
        }
        intervalRow.addView(intervalEdit, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        intervalRow.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        intervalRow.addView(unitEdit, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(fieldLabel("通知间隔")); content.addView(intervalRow, matchWrap())

        val countEdit = EditText(this).apply {
            setSingleLine(true); inputType = 2; textSize = 15f
            hint = "0 表示无限循环"; setText(reminder.totalCount.toString())
        }
        content.addView(fieldLabel("循环次数")); content.addView(countEdit, matchWrap())

        var editedAt = if (reminder.nextAt > System.currentTimeMillis()) reminder.nextAt else System.currentTimeMillis() + reminder.intervalMillis
        val startModeEdit = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId(); text = "按创建时间开始（保存后等待一个间隔）"
                textSize = 13f; setTextColor(Color.rgb(91, 67, 91))
            })
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId(); text = "自定义首次通知时间"
                textSize = 13f; setTextColor(Color.rgb(91, 67, 91))
            })
            // 编辑页面默认与新建页面一致：按创建时间开始。
            check(getChildAt(0).id)
        }
        content.addView(fieldLabel("首次通知时间")); content.addView(startModeEdit, matchWrap())
        val dateValue = TextView(this).apply {
            text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(editedAt)); textSize = 13f
            setTextColor(Color.rgb(111, 76, 160)); gravity = android.view.Gravity.CENTER
        }
        val clockValue = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(editedAt)); textSize = 13f
            setTextColor(Color.rgb(111, 76, 160)); gravity = android.view.Gravity.CENTER
        }
        val dateButton = actionButton("修改日期", Color.rgb(245, 239, 252), Color.rgb(111, 76, 160)).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { chooseDateOnly(editedAt) { editedAt = it; dateValue.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) } }
        }
        val clockButton = actionButton("调整时间", Color.rgb(245, 239, 252), Color.rgb(111, 76, 160)).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { chooseTimeOnly(editedAt) { editedAt = it; clockValue.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } }
        }
        val dateTimeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val dateColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        dateColumn.addView(dateButton, matchWrap()); dateColumn.addView(dateValue, matchWrap())
        val clockColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        clockColumn.addView(clockButton, matchWrap()); clockColumn.addView(clockValue, matchWrap())
        dateTimeRow.addView(dateColumn, LinearLayout.LayoutParams(0, -2, 1f))
        dateTimeRow.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        dateTimeRow.addView(clockColumn, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(dateTimeRow, matchWrap())
        dateTimeRow.visibility = View.GONE
        startModeEdit.setOnCheckedChangeListener { _, checkedId ->
            dateTimeRow.visibility = if (checkedId == startModeEdit.getChildAt(1).id) View.VISIBLE else View.GONE
        }
        content.addView(TextView(this).apply {
            text = if (reminder.enabled) "保存后将按新的时间和间隔继续通知。" else "这条通知已经停止；编辑只修改内容，不会自动重新启动。"
            textSize = 12f; setTextColor(Color.rgb(130, 140, 155)); setPadding(0, dp(10), 0, 0)
        })

        val dialogContent = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑通知")
            .setView(dialogContent)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnDismissListener { editDialogOpen = false }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = nameInput.text.toString().trim()
                val value = intervalEdit.text.toString().toLongOrNull()
                if (newName.isEmpty()) { toast("通知名称不能为空"); return@setOnClickListener }
                if (value == null || value <= 0L) { toast("通知间隔必须大于0"); return@setOnClickListener }
                val newCount = countEdit.text.toString().toIntOrNull()
                if (newCount == null || newCount < 0) { toast("循环次数不能小于0"); return@setOnClickListener }
                if (newCount > 0 && newCount <= reminder.sentCount) { toast("循环次数必须大于已通知次数"); return@setOnClickListener }
                val factor = when (unitEdit.selectedItemPosition) { 0 -> 60_000L; 1 -> 3_600_000L; else -> 86_400_000L }
                val newInterval = value * factor
                val all = ReminderStore.all(this)
                val index = all.indexOfFirst { it.id == reminder.id }
                if (index < 0) { dialog.dismiss(); return@setOnClickListener }
                val old = all[index]
                val now = System.currentTimeMillis()
                val useCustomTime = startModeEdit.checkedRadioButtonId == startModeEdit.getChildAt(1).id
                val newStartAt = if (useCustomTime) editedAt else now + newInterval
                val nextAt = if (old.enabled) {
                    if (useCustomTime) ReminderTiming.nextOccurrenceAfter(newStartAt, newInterval, now) else newStartAt
                } else 0L
                val updated = old.copy(
                    title = newName,
                    intervalMillis = newInterval,
                    startAt = newStartAt,
                    totalCount = newCount,
                    nextAt = nextAt
                )
                all[index] = updated
                AlarmScheduler.cancel(this, updated.id)
                ReminderStore.save(this, all)
                if (updated.enabled) AlarmScheduler.schedule(this, updated)
                dialog.dismiss()
                renderList()
                toast("通知已更新")
            }
        }
        dialog.show()
    }

    // 打开单位下拉框时保留当时正在输入的 EditText；选择完成后恢复同一个输入框和键盘，
    // 不强制把焦点转移到“间隔数值”输入框。
    private fun preserveImeWhenChoosingUnit(spinner: Spinner) {
        spinner.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                spinnerImeTarget = currentFocus as? EditText
                spinnerInteractionInProgress = true
            }
            false
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                spinnerInteractionInProgress = false
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerInteractionInProgress) {
                    val target = spinnerImeTarget
                    spinner.postDelayed({
                        if (target != null && target.isAttachedToWindow) {
                            target.requestFocus()
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
                        }
                        spinnerInteractionInProgress = false
                        spinnerImeTarget = null
                    }, 120L)
                }
            }
        }
    }

    private fun actionButton(textValue: String, bg: Int, fg: Int) = TextView(this).apply {
        text = textValue; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = android.view.Gravity.CENTER; setTextColor(fg); background = rounded(bg, 10)
        isClickable = true; isFocusable = true
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, 17, Color.rgb(239, 226, 234), 1)
        elevation = dp(2).toFloat()
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(height, 1) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun chooseDateOnly(timestamp: Long, onSelected: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = timestamp }
        DatePickerDialog(this, { _, y, m, d ->
            c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d)
            onSelected(c.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun chooseTimeOnly(timestamp: Long, onSelected: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = timestamp }
        TimePickerDialog(this, { _, h, min ->
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            onSelected(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun restoreAlarms() {
        val all = ReminderStore.all(this)
        var changed = false
        val now = System.currentTimeMillis()
        all.filter { it.enabled }.forEach { r ->
            if (r.nextAt <= now) {
                r.nextAt = ReminderTiming.nextOccurrenceAfter(r.nextAt, r.intervalMillis, now)
                changed = true
            }
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