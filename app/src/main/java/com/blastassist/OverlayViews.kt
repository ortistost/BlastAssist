package com.blastassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Tam ekran, dokunmaz. Sadece çerçeve çizer (hücre merkezlerini boyamaz, taramayı bozmasın diye). */
class HintView(ctx: Context) : View(ctx) {
    private var pieceRect: RectF? = null
    private var targets: List<RectF> = emptyList()
    private val loc = IntArray(2)
    private val d = ctx.resources.displayMetrics.density
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFD600.toInt(); strokeWidth = 3f * d
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF00E676.toInt(); strokeWidth = 3f * d
    }

    fun show(piece: RectF?, t: List<RectF>) {
        pieceRect = piece; targets = t; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        pieceRect?.let {
            val r = RectF(it); r.inset(-6f * d, -6f * d)
            canvas.drawRoundRect(r, 8f * d, 8f * d, piecePaint)
        }
        for (t in targets) {
            val r = RectF(t); r.inset(2f * d, 2f * d)
            canvas.drawRoundRect(r, 4f * d, 4f * d, targetPaint)
        }
    }
}

/** Tam ekran dokunmatik kalibrasyon. 7 dokunuş toplar. */
class CalView(
    ctx: Context,
    private val onDone: (List<PointF>) -> Unit,
    private val onCancel: () -> Unit
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val steps = listOf(
        "1/7  Tahtanın SOL-ÜST köşesine dokun",
        "2/7  Tahtanın SAĞ-ALT köşesine dokun",
        "3/7  Blok alanının SOL-ÜST köşesine dokun\n(3 bloğun hepsini kapsayan alan)",
        "4/7  Blok alanının SAĞ-ALT köşesine dokun",
        "5/7  Tahtada BOŞ bir hücrenin ortasına dokun",
        "6/7  Alttaki bir bloğun tek hücresinin\nSOL kenarına dokun",
        "7/7  Aynı hücrenin SAĞ kenarına dokun"
    )
    private val points = ArrayList<PointF>()
    private val loc = IntArray(2)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 17f * d }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt() }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF424242.toInt() }

    private fun cancelRect() = RectF(width / 2f - 70 * d, height - 120 * d, width / 2f + 70 * d, height - 70 * d)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x66000000)
        var y = 110f * d
        for (line in steps[points.size.coerceAtMost(steps.size - 1)].split("\n")) {
            canvas.drawText(line, 16f * d, y, textPaint); y += 24f * d
        }
        val cr = cancelRect()
        canvas.drawRoundRect(cr, 12f * d, 12f * d, btnPaint)
        canvas.drawText("İptal", cr.centerX() - 20f * d, cr.centerY() + 6f * d, textPaint)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (p in points) canvas.drawCircle(p.x, p.y, 6f * d, dotPaint)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (cancelRect().contains(e.x, e.y)) { onCancel(); return true }
            points.add(PointF(e.rawX, e.rawY))
            if (points.size == steps.size) onDone(ArrayList(points)) else invalidate()
        }
        return true
    }
}
