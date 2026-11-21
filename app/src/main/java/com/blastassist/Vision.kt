package com.blastassist

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SlotPiece(val slot: Int, val piece: Piece, val bounds: RectF)

class Snapshot(val board: Long, val slots: List<SlotPiece>) {
    val signature: String = board.toString() + "|" + slots.joinToString(";") { s ->
        "${s.slot}:${s.piece.w}x${s.piece.h}:" + s.piece.cells.joinToString("") { "${it[0]}${it[1]}" }
    }
}

object Vision {
    // RGB uzaklık eşikleri: KALİBRE EDİLMEMİŞ, gerçek ekranda ayarlanmalı.
    const val BOARD_THR = 45f
    const val TRAY_THR = 45f
    private const val GRID = 8

    fun avgColor(bmp: Bitmap, cx: Float, cy: Float, half: Int): Int {
        val x0 = (cx - half).roundToInt().coerceIn(0, bmp.width - 1)
        val x1 = (cx + half).roundToInt().coerceIn(0, bmp.width - 1)
        val y0 = (cy - half).roundToInt().coerceIn(0, bmp.height - 1)
        val y1 = (cy + half).roundToInt().coerceIn(0, bmp.height - 1)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            val p = bmp.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0) return 0
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    fun dist(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun mean(vararg cols: Int): Int {
        var r = 0; var g = 0; var b = 0
        for (c in cols) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        return Color.rgb(r / cols.size, g / cols.size, b / cols.size)
    }

    fun analyze(bmp: Bitmap, cal: Calib): Snapshot {
        // Tahta
        val cw = (cal.boardR - cal.boardL) / GRID
        val ch = (cal.boardB - cal.boardT) / GRID
        val half = maxOf(1, (minOf(cw, ch) * 0.2f).toInt())
        var board = 0L
        for (r in 0 until GRID) for (c in 0 until GRID) {
            val cx = cal.boardL + (c + 0.5f) * cw
            val cy = cal.boardT + (r + 0.5f) * ch
            if (dist(avgColor(bmp, cx, cy, half), cal.emptyRgb) > BOARD_THR) {
                board = board or (1L shl (r * GRID + c))
            }
        }

        // Blok alanı: 3 eşit slot varsayımı
        val slotW = (cal.trayR - cal.trayL) / 3f
        val pitch = maxOf(4f, cal.trayCellPx)
        val out = ArrayList<SlotPiece>()
        for (i in 0 until 3) {
            val x0 = (cal.trayL + i * slotW).roundToInt().coerceIn(0, bmp.width - 1)
            val x1 = (cal.trayL + (i + 1) * slotW).roundToInt().coerceIn(x0 + 1, bmp.width)
            val y0 = cal.trayT.roundToInt().coerceIn(0, bmp.height - 1)
            val y1 = cal.trayB.roundToInt().coerceIn(y0 + 1, bmp.height)
            val w = x1 - x0
            val h = y1 - y0
            if (w < 8 || h < 8) continue
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, x0, y0, w, h)
            val bg = mean(px[2 * w + 2], px[2 * w + (w - 3)], px[(h - 3) * w + 2], px[(h - 3) * w + (w - 3)])

            val colCnt = IntArray(w)
            val rowCnt = IntArray(h)
            for (yy in 0 until h) for (xx in 0 until w) {
                if (dist(px[yy * w + xx], bg) > TRAY_THR) { colCnt[xx]++; rowCnt[yy]++ }
            }
            val minRun = maxOf(2, (pitch * 0.3f).toInt())
            val fc = colCnt.indexOfFirst { it >= minRun }
            val lc = colCnt.indexOfLast { it >= minRun }
            val fr = rowCnt.indexOfFirst { it >= minRun }
            val lr = rowCnt.indexOfLast { it >= minRun }
            if (fc < 0 || fr < 0 || lc < fc || lr < fr) continue

            val bw = lc - fc + 1
            val bh = lr - fr + 1
            val cols = (bw / pitch).roundToInt().coerceIn(1, 5)
            val rows = (bh / pitch).roundToInt().coerceIn(1, 5)
            val tcw = bw.toFloat() / cols
            val tch = bh.toFloat() / rows
            val tHalf = maxOf(1, (minOf(tcw, tch) * 0.2f).toInt())
            val cells = ArrayList<IntArray>()
            for (rr in 0 until rows) for (cc in 0 until cols) {
                val sx = x0 + fc + (cc + 0.5f) * tcw
                val sy = y0 + fr + (rr + 0.5f) * tch
                if (dist(avgColor(bmp, sx, sy, tHalf), bg) > TRAY_THR) cells.add(intArrayOf(rr, cc))
            }
            if (cells.isEmpty()) continue
            out.add(
                SlotPiece(
                    i, Piece(cols, rows, cells),
                    RectF((x0 + fc).toFloat(), (y0 + fr).toFloat(), (x0 + lc + 1).toFloat(), (y0 + lr + 1).toFloat())
                )
            )
        }
        return Snapshot(board, out)
    }
}
