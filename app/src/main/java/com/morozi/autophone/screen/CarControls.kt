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
import android.os.Handler
import android.os.Looper
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
 * Back, Home and Recents. It slides away after [IDLE_MS] without a car touch,
 * so it does not sit over a video, and any car touch brings it back.
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
    /** True while a car shows the mirrored screen; the button may then come and go. */
    private var active = false
    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { slideAway() }
    private val buttonParams = overlayParams().apply {
        width = buttonSize
        height = buttonSize
        x = screen.width() - buttonSize - gap * 2
        y = screen.height() / 2 - buttonSize / 2
    }

    fun show() {
        active = true
        touched()
    }

    fun hide() {
        active = false
        main.removeCallbacks(idle)
        closeMenu()
        closeTabs()
        button?.let { windows.removeView(it) }
        button = null
    }

    /** A car touch: bring the button back and restart the idle timer. */
    fun touched() {
        if (!active) return
        if (button == null) {
            button = ButtonView(service).also { view ->
                view.alpha = 0f
                view.translationY = -buttonSize / 2f
                windows.addView(view, buttonParams)
                view.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).start()
            }
        }
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    private fun slideAway() {
        closeMenu()
        val view = button ?: return
        button = null
        view.animate().alpha(0f).translationY(-buttonSize.toFloat()).setDuration(ANIM_MS)
            .withEndAction { runCatching { windows.removeView(view) } }.start()
    }

    /** True if screen pixel ([x], [y]) is on the button or its open menu. */
    fun contains(x: Int, y: Int): Boolean =
        listOfNotNull(button, menu, tabs).any { view ->
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
            addView(item("←", "Voltar") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) })
            addView(item("⌂", "Início") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) })
            // Android TV has no recents screen (GLOBAL_ACTION_RECENTS does
            // nothing there), so Abas is our own panel of recent apps.
            addView(item("❐", "Abas") { openTabs() })
            // V4: volume of the sound sent to the car; the menu stays open.
            val level = text("${CarVolume.percent}%", buttonSize * 0.17f)
            addView(volumeItem("−", text("Vol", buttonSize * 0.17f), level, -CarVolume.STEP))
            addView(volumeItem("+", level, level, CarVolume.STEP))
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

    private fun item(icon: String, label: String, action: () -> Unit) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(gap, 0, gap, 0)
        addView(text(icon, buttonSize * 0.42f))
        addView(text(label, buttonSize * 0.17f))
        setOnClickListener {
            closeMenu()
            action()
        }
    }

    /** A volume step: [caption] goes under [icon]; [level] is refreshed with the new value. */
    private fun volumeItem(icon: String, caption: TextView, level: TextView, delta: Int) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(gap, 0, gap, 0)
        addView(text(icon, buttonSize * 0.42f))
        addView(caption)
        setOnClickListener { level.text = "${CarVolume.adjust(service, delta)}%" }
    }

    private var tabs: View? = null

    private fun closeTabs() {
        tabs?.let { windows.removeView(it) }
        tabs = null
    }

    /** A panel in the middle of the screen with the most recently used apps; tap one to open it. */
    private fun openTabs() {
        closeTabs()
        val apps = RecentApps.list(service, TAB_COUNT)
        val iconSize = buttonSize * 7 / 10
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(gap * 2, gap, gap * 2, gap)
            background = GradientDrawable().apply {
                cornerRadius = gap * 2f
                setColor(PANEL)
                setStroke(3, OUTLINE)
            }
            val header = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Abas recentes", buttonSize * 0.22f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("✕", buttonSize * 0.3f).apply {
                    setPadding(gap, 0, 0, 0)
                    setOnClickListener { closeTabs() }
                })
            }
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (apps.isEmpty()) addView(text("Nenhum app recente", buttonSize * 0.2f).apply { setPadding(0, gap, 0, gap) })
            apps.chunked(TAB_COLUMNS).forEach { rowApps ->
                addView(LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowApps.forEach { app ->
                        addView(LinearLayout(service).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(gap, gap, gap, 0)
                            addView(android.widget.ImageView(service).apply { setImageDrawable(app.icon) },
                                LinearLayout.LayoutParams(iconSize, iconSize))
                            addView(text(app.label, buttonSize * 0.15f).apply {
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                width = iconSize * 3 / 2
                            })
                            setOnClickListener {
                                closeTabs()
                                runCatching { service.startActivity(app.intent) }
                                    .onFailure { android.util.Log.w("TapAssist", "could not open ${app.label}", it) }
                            }
                        })
                    }
                })
            }
        }
        windows.addView(panel, overlayParams().apply { gravity = Gravity.CENTER })
        tabs = panel
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
        const val IDLE_MS = 5_000L
        const val TAB_COUNT = 8
        const val TAB_COLUMNS = 4
        const val ANIM_MS = 250L
        val PANEL = Color.argb(215, 27, 35, 43)
        val OUTLINE = Color.argb(200, 61, 220, 132)
    }
}
