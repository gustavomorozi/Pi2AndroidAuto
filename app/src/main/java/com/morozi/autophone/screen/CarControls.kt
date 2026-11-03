package com.morozi.autophone.screen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating button shown over the Android TV screen while a car is
 * connected, like the CarlinKit one: drag it anywhere; tap it to open
 * Back, Home and Recents.
 *
 * It is an accessibility overlay of [TapAssistService], so it needs no extra
 * permission and is part of the mirrored picture the car shows. Call from the
 * main thread.
 */
class CarControls(private val service: AccessibilityService) {

    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val screen = windows.maximumWindowMetrics.bounds
    /** Sized from the screen height so it stays finger-sized in the car (1080p TV → 800x450 video). */
    private val buttonSize = screen.height() * 13 / 100
    private val gap = buttonSize / 6

    private var button: View? = null
    private var menu: View? = null
    private val buttonParams = overlayParams().apply {
        width = buttonSize
        height = buttonSize
        x = screen.width() - buttonSize - gap * 2
        y = screen.height() / 2 - buttonSize / 2
    }

    val isShown get() = button != null

    fun show() {
        if (button != null) return
        button = ButtonView(service).also { windows.addView(it, buttonParams) }
    }

    fun hide() {
        closeMenu()
        button?.let { windows.removeView(it) }
        button = null
    }

    /** True if screen pixel ([x], [y]) is on the button or its open menu. */
    fun contains(x: Int, y: Int): Boolean =
        listOfNotNull(button, menu).any { view ->
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            Rect(at[0], at[1], at[0] + view.width, at[1] + view.height).contains(x, y)
        }

    private fun toggleMenu() = if (menu != null) closeMenu() else openMenu()

    private fun closeMenu() {
        menu?.let { windows.removeView(it) }
        menu = null
    }

    private fun openMenu() {
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(gap, gap / 2, gap, gap / 2)
            background = GradientDrawable().apply {
                cornerRadius = buttonSize / 2f
                setColor(PANEL)
                setStroke(3, OUTLINE)
            }
            addView(item("←", "Voltar", AccessibilityService.GLOBAL_ACTION_BACK))
            addView(item("⌂", "Início", AccessibilityService.GLOBAL_ACTION_HOME))
            addView(item("❐", "Abas", AccessibilityService.GLOBAL_ACTION_RECENTS))
        }
        // Open toward the middle of the screen: right of the button on the left
        // half, left of it on the right half (END gravity counts from the right).
        val onLeft = buttonParams.x + buttonSize / 2 < screen.width() / 2
        val params = overlayParams().apply {
            height = buttonSize
            y = buttonParams.y
            if (onLeft) {
                x = buttonParams.x + buttonSize + gap
            } else {
                gravity = Gravity.TOP or Gravity.END
                x = screen.width() - buttonParams.x + gap
            }
        }
        windows.addView(bar, params)
        menu = bar
    }

    private fun item(icon: String, label: String, action: Int) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(gap, 0, gap, 0)
        addView(text(icon, buttonSize * 0.42f))
        addView(text(label, buttonSize * 0.17f))
        setOnClickListener {
            closeMenu()
            service.performGlobalAction(action)
        }
    }

    private fun text(value: String, sizePx: Float) = TextView(service).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Round button with three dots; a press that moves drags it, a tap opens the menu. */
    @SuppressLint("ViewConstructor")
    private inner class ButtonView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OUTLINE
            style = Paint.Style.STROKE
            strokeWidth = buttonSize / 22f
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
            canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
            for (i in -1..1) canvas.drawCircle(r + i * r * 0.36f, r, r * 0.11f, dot)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = buttonParams.x; startY = buttonParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > gap || abs(dy) > gap)) {
                        dragging = true
                        closeMenu()
                    }
                    if (dragging) {
                        buttonParams.x = (startX + dx.toInt()).coerceIn(0, screen.width() - buttonSize)
                        buttonParams.y = (startY + dy.toInt()).coerceIn(0, screen.height() - buttonSize)
                        windows.updateViewLayout(this, buttonParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) toggleMenu()
            }
            return true
        }
    }

    private companion object {
        val PANEL = Color.argb(215, 27, 35, 43)
        val OUTLINE = Color.argb(200, 61, 220, 132)
    }
}
