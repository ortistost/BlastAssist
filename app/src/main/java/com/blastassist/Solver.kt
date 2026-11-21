package com.blastassist

const val N = 8

/** cells: her eleman [satır, sütun], parçanın sol-üst köşesine göre. */
class Piece(val w: Int, val h: Int, val cells: List<IntArray>)

data class Move(val slot: Int, val row: Int, val col: Int, val linesCleared: Int)
data class Plan(val moves: List<Move>, val score: Double)

object Solver {
    // Değerlendirme ağırlıkları: KALİBRE EDİLMEMİŞ, oyun deneyiminle ayarla.
    private const val W_EMPTY = 0.5
    private const val W_ISOLATED = 12.0
    private const val W_LINE = 20.0
    private const val W_UNPLACED = 1000.0

    private const val COL0 = 0x0101010101010101L
    private const val NOT_COL7 = 0x7F7F7F7F7F7F7F7FL
    private val NOT_COL0 = 0xFEFEFEFEFEFEFEFEuL.toLong()

    fun placementMask(p: Piece, r: Int, c: Int): Long {
        var m = 0L
        for (cell in p.cells) m = m or (1L shl ((r + cell[0]) * N + (c + cell[1])))
        return m
    }

    /** Dönüş: temizlenmiş tahta ve temizlenen satır+sütun sayısı. */
    fun clearLines(b: Long): Pair<Long, Int> {
        var clearMask = 0L
        var lines = 0
        for (r in 0 until N) {
            if (((b ushr (r * N)) and 0xFFL) == 0xFFL) { clearMask = clearMask or (0xFFL shl (r * N)); lines++ }
        }
        for (c in 0 until N) {
            val cm = COL0 shl c
            if ((b and cm) == cm) { clearMask = clearMask or cm; lines++ }
        }
        return (b and clearMask.inv()) to lines
    }

    fun evaluate(b: Long): Double {
        val e = b.inv()
        val left = (e shl 1) and NOT_COL0
        val right = (e ushr 1) and NOT_COL7
        val up = e shl 8
        val down = e ushr 8
        val isolated = e and (left or right or up or down).inv()
        return java.lang.Long.bitCount(e) * W_EMPTY - java.lang.Long.bitCount(isolated) * W_ISOLATED
    }

    /** items: (slot, parça). strict=true ise parçalar slot sırasıyla yerleştirilir. */
    fun best(board: Long, items: List<Pair<Int, Piece>>, strict: Boolean): Plan? {
        if (items.isEmpty()) return null
        val full = (1 shl items.size) - 1
        var bestScore = -1e18
        var bestMoves: List<Move>? = null
        val cur = ArrayList<Move>()

        fun dfs(b: Long, used: Int, acc: Double) {
            if (used == full) {
                val s = acc + evaluate(b)
                if (s > bestScore) { bestScore = s; bestMoves = ArrayList(cur) }
                return
            }
            var any = false
            val firstUnused = Integer.numberOfTrailingZeros(used.inv())
            for (i in items.indices) {
                if ((used and (1 shl i)) != 0) continue
                if (strict && i != firstUnused) continue
                val (slot, piece) = items[i]
                for (r in 0..(N - piece.h)) for (c in 0..(N - piece.w)) {
                    val m = placementMask(piece, r, c)
                    if ((b and m) != 0L) continue
                    any = true
                    val (nb, lines) = clearLines(b or m)
                    cur.add(Move(slot, r, c, lines))
                    dfs(nb, used or (1 shl i), acc + lines * W_LINE + lines * lines * 5.0)
                    cur.removeAt(cur.size - 1)
                }
            }
            if (!any && cur.isNotEmpty()) {
                val remaining = items.size - Integer.bitCount(used)
                val s = acc + evaluate(b) - remaining * W_UNPLACED
                if (s > bestScore) { bestScore = s; bestMoves = ArrayList(cur) }
            }
        }

        dfs(board, 0, 0.0)
        val mv = bestMoves ?: return null
        return Plan(mv, bestScore)
    }
}
