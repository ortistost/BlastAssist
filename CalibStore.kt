package com.blastassist

import android.content.Context

/** Tüm koordinatlar ekran pikseli. refW/refH = kalibrasyon anındaki ekran görüntüsü boyutu. */
data class Calib(
    val refW: Int, val refH: Int,
    val boardL: Float, val boardT: Float, val boardR: Float, val boardB: Float,
    val trayL: Float, val trayT: Float, val trayR: Float, val trayB: Float,
    val emptyRgb: Int,
    val trayCellPx: Float
)

object CalibStore {
    private const val PREFS = "blast_calib"
    private const val KEY = "v1"

    fun save(ctx: Context, c: Calib) {
        val s = listOf(
            c.refW, c.refH, c.boardL, c.boardT, c.boardR, c.boardB,
            c.trayL, c.trayT, c.trayR, c.trayB, c.emptyRgb, c.trayCellPx
        ).joinToString(",")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s).apply()
    }

    fun load(ctx: Context): Calib? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val p = s.split(",")
            if (p.size != 12) return null
            Calib(
                p[0].toInt(), p[1].toInt(),
                p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(),
                p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat(),
                p[10].toInt(), p[11].toFloat()
            )
        } catch (e: NumberFormatException) { null }
    }
}
