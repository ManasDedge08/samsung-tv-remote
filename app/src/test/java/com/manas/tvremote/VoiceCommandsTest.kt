package com.manas.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {

    private val apps = KNOWN_APPS

    private fun keys(phrase: String) = (VoiceCommands.parse(phrase, apps) as VoiceCommand.Keys).keys

    private fun opened(phrase: String) = (VoiceCommands.parse(phrase, apps) as VoiceCommand.Open).app.name

    @Test fun opensAppsByNameAndAlias() {
        assertEquals("Netflix", opened("Open Netflix"))
        assertEquals("YouTube", opened("youtube"))
        assertEquals("Prime Video", opened("launch Amazon Prime"))
        assertEquals("Disney+", opened("open disney plus"))
        assertEquals("Apple TV", opened("go to the Apple TV app"))
    }

    @Test fun volumeCounts() {
        assertEquals(List(5) { Keys.VOL_UP }, keys("Volume up 5."))
        assertEquals(List(3) { Keys.VOL_DOWN }, keys("turn the volume down by three"))
        assertEquals(List(2) { Keys.VOL_UP }, keys("turn it up"))
        assertEquals(List(4) { Keys.VOL_DOWN }, keys("volume down for"))
        assertEquals(List(20) { Keys.VOL_UP }, keys("volume up 99"))
        assertEquals(List(2) { Keys.VOL_UP }, keys("Louder"))
    }

    @Test fun simpleKeys() {
        assertEquals(listOf(Keys.MUTE), keys("Mute"))
        assertEquals(listOf(Keys.HOME), keys("go home please"))
        assertEquals(listOf(Keys.PAUSE), keys("Pause."))
        assertEquals(listOf(Keys.CH_UP), keys("channel next"))
    }

    @Test fun power() {
        assertEquals(VoiceCommand.Power(on = false), VoiceCommands.parse("Turn off the TV", apps))
        assertEquals(VoiceCommand.Power(on = true), VoiceCommands.parse("turn on", apps))
    }

    @Test fun searchTextIsNotACommand() {
        assertNull(VoiceCommands.parse("lofi music", apps))
        assertNull(VoiceCommands.parse("play stranger things", apps))
        assertNull(VoiceCommands.parse("how to cook biryani", apps))
    }

    @Test fun unknownAppIsNotOpened() {
        assertNull(VoiceCommands.parse("open hulu", apps))
    }
}
