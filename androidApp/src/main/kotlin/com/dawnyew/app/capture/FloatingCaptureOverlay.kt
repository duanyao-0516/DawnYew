package com.dawnyew.app.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.dawnyew.core.Category
import com.dawnyew.core.Direction
import com.dawnyew.core.Money
import com.dawnyew.core.validateOccurrence

/** Real accessibility windows, independent of any Activity or Compose view tree. */
internal class FloatingCaptureOverlay(
    serviceContext: Context,
    private val preferences: FloatingCapturePreferences,
    private val capture: () -> Unit,
) {
    private val context = ContextThemeWrapper(serviceContext, android.R.style.Theme_Material_Light_NoActionBar)
    private val windows = serviceContext.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val brand = Color.rgb(0, 191, 174)
    private val ink = Color.rgb(26, 49, 45)
    private val muted = Color.rgb(99, 117, 111)
    private var ball: TextView? = null
    private var ballLayout: WindowManager.LayoutParams? = null
    private var confirmation: View? = null
    private var confirmationLayout: WindowManager.LayoutParams? = null
    private var backCallback: OnBackInvokedCallback? = null
    private val fade = Runnable { ball?.animate()?.alpha(.48f)?.setDuration(250)?.start() }

    private data class Screen(val width: Int, val height: Int, val left: Int, val right: Int, val top: Int, val bottom: Int)
    private fun screen(): Screen {
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Screen(metrics.bounds.width(), metrics.bounds.height(), insets.left, insets.right, insets.top, insets.bottom)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun background(color: Int, radius: Int = 14, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), Color.rgb(221, 231, 225))
    }
    private fun wakeBall() { handler.removeCallbacks(fade); ball?.animate()?.cancel(); ball?.alpha = 1f }
    private fun idleBall() { handler.removeCallbacks(fade); handler.postDelayed(fade, 3_000) }

    fun showBall() {
        if (ball != null || confirmation != null) return
        val size = dp(52)
        val display = screen()
        val position = FloatingBallGeometry.position(display.width - display.left - display.right, display.height,
            size, display.top, display.bottom, preferences.onRight, preferences.verticalFraction)
        val layout = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = display.left + position.x; y = position.y
            title = "DawnYew 悬浮记账"
        }
        val view = TextView(context).apply {
            text = "记"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = background(brand, 26); elevation = dp(5).toFloat()
            contentDescription = "截屏记账，拖动调整位置"
            isClickable = true; isFocusable = true
            setOnClickListener { capture() }
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeBall(); downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        val bounds = screen()
                        layout.x = (startX + dx.toInt()).coerceIn(bounds.left, (bounds.width - bounds.right - size).coerceAtLeast(bounds.left))
                        layout.y = (startY + dy.toInt()).coerceIn(bounds.top.coerceAtMost((bounds.height - size).coerceAtLeast(0)),
                            (bounds.height - bounds.bottom - size).coerceAtLeast(bounds.top))
                        if (view.isAttachedToWindow) windows.updateViewLayout(view, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val bounds = screen()
                        preferences.onRight = layout.x + size / 2 >= bounds.width / 2
                        preferences.verticalFraction = FloatingBallGeometry.verticalFraction(layout.y, bounds.height, size, bounds.top, bounds.bottom)
                        layout.x = if (preferences.onRight) (bounds.width - bounds.right - size).coerceAtLeast(bounds.left) else bounds.left
                        if (view.isAttachedToWindow) windows.updateViewLayout(view, layout)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    idleBall(); true
                }
                else -> false
            }
        }
        windows.addView(view, layout)
        ball = view; ballLayout = layout; idleBall()
    }

    fun hideBall() {
        handler.removeCallbacks(fade)
        ball?.let { it.animate().cancel(); if (it.isAttachedToWindow) windows.removeViewImmediate(it) }
        ball = null; ballLayout = null
    }

    fun dismissConfirmation() {
        confirmation?.let {
            if (Build.VERSION.SDK_INT >= 33) backCallback?.let { callback -> it.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback) }
            if (it.isAttachedToWindow) windows.removeViewImmediate(it)
        }
        backCallback = null
        confirmation = null; confirmationLayout = null
    }

    fun hideAll() { hideBall(); dismissConfirmation() }

    fun configurationChanged() {
        if (ball != null) { hideBall(); showBall() }
        confirmation?.let { view -> confirmationLayout?.let { layout ->
            val bounds = screen(); layout.width = (bounds.width - bounds.left - bounds.right - dp(24)).coerceAtLeast(1)
            layout.height = (bounds.height - bounds.top - bounds.bottom - dp(32)).coerceIn(1, dp(640))
            windows.updateViewLayout(view, layout)
        } }
    }

    fun showConfirmation(review: FloatingCaptureReview, cancel: () -> Unit, save: (FloatingCaptureReview) -> Unit) {
        hideAll()
        val card = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { cancel(); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16))
            background = background(Color.rgb(248, 250, 247), 24)
            isFocusableInTouchMode = true
        }
        fun label(text: String, size: Float = 13f, color: Int = muted) = TextView(context).apply {
            this.text = text; textSize = size; setTextColor(color)
            setPadding(0, dp(10), 0, dp(6))
        }
        card.addView(label("确认这笔记账", 22f, ink).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(4)) })
        card.addView(label("修改后点一次确认，即可回到当前页面。", 12f).apply { setPadding(0, 0, 0, dp(10)) })
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { isFillViewport = false; addView(fields) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun input(title: String, value: String, type: Int): EditText {
            fields.addView(label(title))
            return EditText(context).apply {
                setText(value); textSize = 16f; setTextColor(ink); setHintTextColor(muted)
                inputType = type; setSingleLine(true); setPadding(dp(12), dp(9), dp(12), dp(9))
                background = background(Color.WHITE, 12, true)
                fields.addView(this, LinearLayout.LayoutParams(-1, dp(46)))
            }
        }
        fun spinner(title: String, values: List<String>): Spinner {
            fields.addView(label(title))
            return Spinner(context).apply {
                background = background(Color.WHITE, 12, true)
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, values).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                fields.addView(this, LinearLayout.LayoutParams(-1, dp(46)))
            }
        }
        val amount = input("金额（元）", review.draft.amount, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        amount.filters = arrayOf(InputFilter.LengthFilter(12))
        val direction = spinner("收支方向", listOf("请选择收支方向", "支出", "收入"))
        direction.setSelection(when (review.draft.direction) { Direction.EXPENSE -> 1; Direction.INCOME -> 2; null -> 0 })
        val ledgers = review.ledgers.filter { !it.archived }
        val ledger = spinner("账本", ledgers.map { it.name }.ifEmpty { listOf("没有可用账本") })
        ledger.setSelection(ledgers.indexOfFirst { it.id == review.ledgerId }.coerceAtLeast(0))
        val category = spinner("分类", listOf("请选择收支方向"))
        var visibleCategories = emptyList<Category>()
        fun selectedDirection(): Direction? = when (direction.selectedItemPosition) { 1 -> Direction.EXPENSE; 2 -> Direction.INCOME; else -> null }
        fun updateCategories() {
            val previousId = visibleCategories.getOrNull(category.selectedItemPosition)?.id ?: review.categoryId
            val ledgerId = ledgers.getOrNull(ledger.selectedItemPosition)?.id
            visibleCategories = review.categories.filter { it.enabled && it.ledgerId == ledgerId && it.direction == selectedDirection() }.sortedBy { it.sortOrder }
            category.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                visibleCategories.map { it.name }.ifEmpty { listOf(if (selectedDirection() == null) "请选择收支方向" else "自动创建截图记账分类") }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            category.setSelection(visibleCategories.indexOfFirst { it.id == previousId }.coerceAtLeast(0))
        }
        val changed = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateCategories()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        direction.onItemSelectedListener = changed; ledger.onItemSelectedListener = changed; updateCategories()
        val occurrence = input("时间（年-月-日 时:分:秒）", review.draft.occurrence.ifBlank { review.capturedAt }.replace('T', ' '),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val note = input("备注", review.draft.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        note.filters = arrayOf(InputFilter.LengthFilter(500))
        if (review.draft.warnings.isNotEmpty()) fields.addView(label(review.draft.warnings.joinToString("\n"), 12f))
        val validationMessage = label("", 12f, Color.rgb(160, 62, 48)).apply { visibility = View.GONE }
        card.addView(validationMessage)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        fun action(title: String, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
            text = title; textSize = 15f; isAllCaps = false; minHeight = 0; minimumHeight = 0
            setTextColor(if (primary) Color.WHITE else ink); background = background(if (primary) brand else Color.rgb(233, 239, 234), 14)
            setOnClickListener { onClick() }
        }
        actions.addView(action("取消", false, cancel), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
        var submitted = false
        actions.addView(action("确认记账", true) {
            if (!submitted) {
                try {
                    val amountText = amount.text.toString().trim(); Money.parse(amountText)
                    val chosenDirection = selectedDirection() ?: error("请选择收支方向")
                    val chosenLedger = ledgers.getOrNull(ledger.selectedItemPosition) ?: error("请先创建一个可用账本")
                    val chosenCategory = visibleCategories.getOrNull(category.selectedItemPosition)
                    val timeText = occurrence.text.toString().trim().replace(' ', 'T').let { if (it.length == 16) "$it:00" else it }
                    validateOccurrence(timeText)
                    submitted = true
                    save(review.copy(ledgerId = chosenLedger.id, categoryId = chosenCategory?.id,
                        draft = review.draft.copy(amount = amountText, direction = chosenDirection, occurrence = timeText, note = note.text.toString().trim())))
                } catch (failure: Exception) {
                    submitted = false; validationMessage.text = failure.message ?: "请检查填写的信息"; validationMessage.visibility = View.VISIBLE
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(actions)
        val bounds = screen()
        val layout = WindowManager.LayoutParams((bounds.width - bounds.left - bounds.right - dp(24)).coerceAtLeast(1),
            (bounds.height - bounds.top - bounds.bottom - dp(32)).coerceIn(1, dp(640)), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER; dimAmount = .24f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "DawnYew 记账确认"
        }
        windows.addView(card, layout); confirmation = card; confirmationLayout = layout; card.requestFocus()
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { cancel() }
            backCallback = callback
            card.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }
}
