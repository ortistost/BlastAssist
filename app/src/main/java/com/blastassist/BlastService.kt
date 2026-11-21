package com.blastassist

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class BlastService : AccessibilityService() {

    companion object {
        /** true: parçalar slot sırasıyla (1,2,3) yerleştirilir. false: en iyi sıra aranır. */
        const val STRICT_SLOT_ORDER = false
        private const val LOOP_DELAY_MS = 450L // sistem screenshot aralığı ~333 ms altına inmemeli
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var enabled = false
    @Volatile private var calib: Calib? = null

    private var hintView: HintView? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var toggle: TextView? = null
    private var toggleLp: WindowManager.LayoutParams? = null
    private var calView: CalView? = null

    // yalnızca worker thread'inde kullanılır
    private var lastSig: String? = null
    private var stable = 0
    private var solvedSig: String? = null

    private val tick = Runnable { takeShot() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        calib = CalibStore.load(this)
        buildHint()
        buildPanel()
        buildToggle()
        setStatus(if (calib == null) "Önce Kalibre et" else "Hazır, ON'a bas")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        enabled = false
        ui.removeCallbacks(tick)
        listOf<View?>(hintView, panel, toggle, calView).forEach {
            if (it != null) try { wm.removeView(it) } catch (_: IllegalArgumentException) {}
        }
        hintView = null; panel = null; toggle = null; calView = null
        worker.shutdownNow()
    }

    // ---------- Pencereler ----------

    private fun overlayLp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val fullFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun buildHint() {
        val v = HintView(this)
        val lp = overlayLp(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            fullFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        wm.addView(v, lp)
        hintView = v
    }

    private fun buildPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xDD202020.toInt()) }
        }
        val st = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; maxWidth = dp(230)
            text = "BlastAssist"
        }
        val btn = Button(this).apply {
            text = "Kalibre"; textSize = 12f; minHeight = 0; minimumHeight = dp(36)
            setOnClickListener { startCalibration() }
        }
        box.addView(st)
        box.addView(btn)
        val lp = overlayLp(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply { x = dp(8); y = dp(24) }
        makeDraggable(box, lp, null)
        wm.addView(box, lp)
        panel = box; panelLp = lp; statusText = st
    }

    private fun buildToggle() {
        val t = TextView(this).apply {
            text = "OFF"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            gravity = Gravity.CENTER
            width = dp(56); height = dp(56)
        }
        paintToggle(t, false)
        val lp = overlayLp(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply { x = dp(8); y = dp(150) }
        makeDraggable(t, lp) { setEnabled(!enabled) }
        wm.addView(t, lp)
        toggle = t; toggleLp = lp
    }

    private fun paintToggle(t: TextView, on: Boolean) {
        t.text = if (on) "ON" else "OFF"
        t.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xCC2E7D32.toInt() else 0xCCD32F2F.toInt())
        }
    }

    private fun makeDraggable(view: View, lp: WindowManager.LayoutParams, onClick: (() -> Unit)?) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onClick?.invoke(); true }
                else -> false
            }
        }
    }

    private fun setStatus(s: String) {
        ui.post { statusText?.text = s }
    }

    // ---------- ON / OFF ----------

    private fun setEnabled(on: Boolean) {
        if (on && calib == null) { setStatus("Önce Kalibre et"); return }
        enabled = on
        toggle?.let { paintToggle(it, on) }
        ui.removeCallbacks(tick)
        worker.execute { lastSig = null; stable = 0; solvedSig = null }
        if (on) {
            setStatus("Taranıyor…")
            ui.post(tick)
        } else {
            hintView?.show(null, emptyList())
            setStatus("Kapalı")
        }
    }

    // ---------- Ekran görüntüsü ----------

    private fun capture(cb: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                var soft: Bitmap? = null
                try {
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    if (hw != null) { soft = hw.copy(Bitmap.Config.ARGB_8888, false); hw.recycle() }
                } finally { hb.close() }
                cb(soft)
            }
            override fun onFailure(errorCode: Int) {
                setStatus("Screenshot hatası: $errorCode")
                cb(null)
            }
        })
    }

    private fun takeShot() {
        if (!enabled) return
        capture { bmp ->
            if (bmp == null) { if (enabled) ui.postDelayed(tick, 800); return@capture }
            try { handleBitmap(bmp) } finally {
                bmp.recycle()
                if (enabled) ui.postDelayed(tick, LOOP_DELAY_MS)
            }
        }
    }

    private fun handleBitmap(bmp: Bitmap) {
        val cal = calib ?: run { setStatus("Kalibrasyon yok"); return }
        if (bmp.width != cal.refW || bmp.height != cal.refH) {
            setStatus("Ekran boyutu değişti, tekrar kalibre et"); return
        }
        val snap = Vision.analyze(bmp, cal)
        val sig = snap.signature
        if (sig != lastSig) {
            lastSig = sig; stable = 0; solvedSig = null
            ui.post { hintView?.show(null, emptyList()) }
            return
        }
        stable++
        if (stable < 1 || sig == solvedSig) return
        solvedSig = sig

        if (snap.slots.isEmpty()) { setStatus("Blok görünmüyor"); return }
        val items = snap.slots.map { it.slot to it.piece }
        val plan = Solver.best(snap.board, items, STRICT_SLOT_ORDER)
        if (plan == null || plan.moves.isEmpty()) { setStatus("Yerleştirilecek hamle yok"); return }

        val first = plan.moves[0]
        val sp = snap.slots.first { it.slot == first.slot }
        val cw = (cal.boardR - cal.boardL) / 8f
        val ch = (cal.boardB - cal.boardT) / 8f
        val targets = sp.piece.cells.map { cell ->
            val r = first.row + cell[0]; val c = first.col + cell[1]
            RectF(cal.boardL + c * cw, cal.boardT + r * ch, cal.boardL + (c + 1) * cw, cal.boardT + (r + 1) * ch)
        }
        val seq = plan.moves.joinToString(" → ") { "S${it.slot + 1}(${it.row + 1},${it.col + 1})" }
        setStatus("İlk: Slot ${first.slot + 1} → satır ${first.row + 1}, sütun ${first.col + 1}\nSıra: $seq")
        ui.post { if (enabled) hintView?.show(sp.bounds, targets) }
    }

    // ---------- Kalibrasyon ----------

    private fun startCalibration() {
        if (calView != null) return
        if (enabled) setEnabled(false)
        val v = CalView(this,
            onDone = { pts -> finishCalibration(pts) },
            onCancel = { removeCalView(); setStatus("Kalibrasyon iptal") })
        val lp = overlayLp(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, fullFlags
        )
        wm.addView(v, lp)
        calView = v
    }

    private fun removeCalView() {
        calView?.let { try { wm.removeView(it) } catch (_: IllegalArgumentException) {} }
        calView = null
    }

    private fun finishCalibration(p: List<android.graphics.PointF>) {
        removeCalView()
        setStatus("Kalibrasyon: boş hücre rengi okunuyor…")
        ui.postDelayed({
            capture { bmp ->
                if (bmp == null) { setStatus("Kalibrasyon başarısız (screenshot)"); return@capture }
                try {
                    val boardL = min(p[0].x, p[1].x); val boardR = maxOf(p[0].x, p[1].x)
                    val boardT = min(p[0].y, p[1].y); val boardB = maxOf(p[0].y, p[1].y)
                    val trayL = min(p[2].x, p[3].x); val trayR = maxOf(p[2].x, p[3].x)
                    val trayT = min(p[2].y, p[3].y); val trayB = maxOf(p[2].y, p[3].y)
                    val pitch = abs(p[6].x - p[5].x)
                    if (boardR - boardL < 80f || boardB - boardT < 80f || pitch < 6f) {
                        setStatus("Kalibrasyon geçersiz, tekrar dene"); return@capture
                    }
                    val empty = Vision.avgColor(bmp, p[4].x, p[4].y, 3)
                    val cal = Calib(
                        bmp.width, bmp.height, boardL, boardT, boardR, boardB,
                        trayL, trayT, trayR, trayB, empty, pitch
                    )
                    CalibStore.save(this@BlastService, cal)
                    calib = cal
                    setStatus("Kalibrasyon tamam. ON'a bas")
                } finally { bmp.recycle() }
            }
        }, 350)
    }
}
