package com.manas.tvremote

import java.text.Normalizer

/**
 * Types into an app's own on-screen keyboard by moving focus key by key and pressing OK, the way
 * you would with the remote. Needed for apps like YouTube that ignore text sent to the TV.
 *
 * The planner only uses moves that were checked on a real TV, and avoids the edges where focus
 * leaves the keyboard: left of the first column (the search history), below the bottom row (the
 * results) and above the top row (the search bar).
 */
object YouTubeKeyboard {

    /** Rows 0–3 of the letter page. Column 7 holds Delete, &123 and the language key. */
    private val letters = listOf("ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ-'")

    /** The same rows on the &123 page. */
    private val symbols = listOf("123&#()", "456@!?:", "789.-_\"", "0/$%+[]")

    /** The page toggle (&123 / ABC) sits in row 1, column 7 on both pages. */
    private const val TOGGLE_ROW = 1
    private const val EDGE = 7

    /** Under row 3: Space covers columns 0–1, Clear 2–3, Search 4–6. */
    private const val SPACE_COL = 0
    private const val CLEAR_COL = 2
    private const val SEARCH_COL = 5

    /**
     * Key presses that clear the search box, type [text] and run the search. Assumes focus is on
     * A, where YouTube puts it when the search screen opens. Characters the keyboard doesn't have
     * are skipped; returns null if nothing in [text] can be typed.
     */
    fun plan(text: String): List<String>? {
        val wanted = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim().uppercase().replace(Regex("\\s+"), " ")
        if (wanted.none { it != ' ' && find(it) != null }) return null

        val keys = mutableListOf<String>()
        var r = 0
        var c = 0
        var page = letters

        fun move(tr: Int, tc: Int) {
            // Column 7 is ragged (row 3 has none), so step off it before moving between rows.
            if (c == EDGE && tr != r) { keys += Keys.LEFT; c = EDGE - 1 }
            while (r < tr) { keys += Keys.DOWN; r++ }
            while (r > tr) { keys += Keys.UP; r-- }
            while (c < tc) { keys += Keys.RIGHT; c++ }
            while (c > tc) { keys += Keys.LEFT; c-- }
        }

        /** Press a key on the bottom row, come back up, and pin focus at the right end of row 3. */
        fun bottom(col: Int, back: Boolean = true) {
            move(3, col)
            keys += Keys.DOWN
            keys += Keys.OK
            if (back) {
                keys += Keys.UP
                // Where Up lands depends on the bottom key's width; Right stops at the edge, so
                // seven presses always end on the last key of row 3.
                repeat(EDGE) { keys += Keys.RIGHT }
                r = 3
                c = EDGE - 1
            }
        }

        bottom(CLEAR_COL)
        var lastWasSpace = true
        for (ch in wanted) {
            if (ch == ' ') {
                if (!lastWasSpace) bottom(SPACE_COL)
                lastWasSpace = true
                continue
            }
            val (p, row, col) = find(ch) ?: continue
            if (p !== page) {
                move(TOGGLE_ROW, EDGE)
                keys += Keys.OK
                page = p
            }
            move(row, col)
            keys += Keys.OK
            lastWasSpace = false
        }
        bottom(SEARCH_COL, back = false)
        return keys
    }

    private fun find(ch: Char): Triple<List<String>, Int, Int>? {
        for (p in listOf(letters, symbols)) {
            p.forEachIndexed { row, keys ->
                val col = keys.indexOf(ch)
                if (col >= 0) return Triple(p, row, col)
            }
        }
        return null
    }
}
