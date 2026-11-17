package com.manas.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Replays a plan on a model of YouTube's keyboard and reads back what got typed, so a wrong move
 * shows up as wrong text rather than a wrong key list.
 */
class YouTubeKeyboardTest {

    private val letters = listOf("ABCDEFG<", "HIJKLMN#", "OPQRSTU@", "VWXYZ-'")
    private val symbols = listOf("123&#()<", "456@!?:#", "789.-_\"", "0/$%+[]")

    private fun replay(keys: List<String>): String {
        var r = 0; var c = 0; var page = letters; val typed = StringBuilder()
        var searched = false
        for (k in keys) {
            check(!searched) { "keys after Search" }
            when (k) {
                Keys.UP -> { check(r > 0) { "Up off the top" }; r--; if (r < 4) c = c.coerceAtMost(page[r].length - 1) }
                Keys.DOWN -> { check(r < 4) { "Down off the bottom" }; r++; if (r < 4) check(c < page[r].length) { "Down into a gap" } }
                Keys.LEFT -> { check(c > 0 && r < 4) { "Left off the keyboard" }; c-- }
                Keys.RIGHT -> if (r < 4 && c < page[r].length - 1) c++
                Keys.OK -> if (r == 4) {
                    when {
                        c <= 1 -> typed.append(' ')
                        c <= 3 -> typed.clear()
                        else -> searched = true
                    }
                } else {
                    when (val ch = page[r][c]) {
                        '#' -> page = if (page === letters) symbols else letters
                        '<', '@' -> error("pressed $ch")
                        else -> typed.append(ch)
                    }
                }
            }
        }
        check(searched) { "never pressed Search" }
        return typed.toString()
    }

    private fun typed(text: String) = replay(YouTubeKeyboard.plan(text)!!)

    @Test fun typesWords() {
        assertEquals("LOFI MUSIC", typed("lofi music"))
        assertEquals("STRANGER THINGS", typed("  Stranger   Things "))
    }

    @Test fun switchesPagesForDigitsAndSymbols() {
        assertEquals("TOP 10 SONGS 2026", typed("top 10 songs 2026"))
        assertEquals("AC/DC", typed("AC/DC"))
        assertEquals("DON'T STOP", typed("don't stop"))
    }

    @Test fun skipsWhatItCannotType() {
        assertEquals("CAFE", typed("café"))
        assertNull(YouTubeKeyboard.plan("नमस्ते"))
    }
}
