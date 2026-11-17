package com.manas.tvremote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens with the phone's own speech recogniser. The TV can't take audio from this app (its voice
 * assistant only hears the Bluetooth remote's mic), so the phone turns speech into text and the
 * remote acts on the text.
 */
class VoiceInput(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onLevel(rms: Float) {}
    }

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                if (text.isNullOrBlank()) listener.onError(NOTHING_HEARD) else listener.onFinal(text)
                release()
            }

            override fun onError(error: Int) {
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING_HEARD
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to use voice."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition needs a connection right now. Try again."
                        else -> "Voice isn't available right now. Try again."
                    }
                )
                release()
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
    }

    /** Stop listening and use what was heard so far. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(b: Bundle?) =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val NOTHING_HEARD = "Didn't catch that."
    }
}

/** Something the remote can do in response to a phrase. */
sealed interface VoiceCommand {
    data class Keys(val keys: List<String>, val label: String) : VoiceCommand
    data class Open(val app: TvApp) : VoiceCommand
    data class Power(val on: Boolean) : VoiceCommand
}

/**
 * Turns phrases like "open Netflix", "volume up 5", "mute" or "go home" into actions. Anything it
 * doesn't recognise is treated as text to type on the TV.
 */
object VoiceCommands {

    private val numbers = mapOf(
        "one" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3, "four" to 4, "for" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )

    private val aliases = mapOf(
        "prime" to "Prime Video", "amazon" to "Prime Video", "amazon prime" to "Prime Video",
        "apple tv" to "Apple TV", "apple music" to "Apple Music", "music" to "Apple Music",
        "internet" to "Browser", "web" to "Browser", "disney" to "Disney+", "hotstar" to "Disney+",
    )

    fun parse(raw: String, apps: List<TvApp>): VoiceCommand? {
        val t = raw.lowercase().replace(Regex("[^a-z0-9+ ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("please ").removeSuffix(" please")

        Regex("^(?:open|launch|start|go to|switch to|play) (.+)$").find(t)?.let { m ->
            findApp(m.groupValues[1], apps)?.let { return VoiceCommand.Open(it) }
        }
        findApp(t, apps)?.takeIf { t.split(' ').size <= 2 }?.let { return VoiceCommand.Open(it) }

        Regex("^(?:turn (?:it |the volume )?(up|down)|(?:turn |set )?(?:the )?volume (up|down)(?: by)?(?: (\\w+))?(?: times| steps)?)$").find(t)?.let { m ->
            val dir = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val n = count(m.groupValues[3]) ?: 2
            val up = dir == "up"
            return VoiceCommand.Keys(List(n) { if (up) Keys.VOL_UP else Keys.VOL_DOWN }, "Volume ${if (up) "Up" else "Down"} $n")
        }
        Regex("^(louder|quieter|softer)$").find(t)?.let { m ->
            val up = m.groupValues[1] == "louder"
            return VoiceCommand.Keys(List(2) { if (up) Keys.VOL_UP else Keys.VOL_DOWN }, if (up) "Louder" else "Quieter")
        }
        Regex("^channel (up|down|next|previous)$").find(t)?.let { m ->
            val up = m.groupValues[1] in setOf("up", "next")
            return VoiceCommand.Keys(listOf(if (up) Keys.CH_UP else Keys.CH_DOWN), "Channel ${if (up) "Up" else "Down"}")
        }

        return when (t) {
            "mute", "unmute", "mute the tv", "unmute the tv" -> VoiceCommand.Keys(listOf(Keys.MUTE), "Mute")
            "home", "go home", "go to home", "home screen" -> VoiceCommand.Keys(listOf(Keys.HOME), "Home")
            "back", "go back" -> VoiceCommand.Keys(listOf(Keys.BACK), "Back")
            "pause", "stop" -> VoiceCommand.Keys(listOf(Keys.PAUSE), "Pause")
            "play", "resume", "continue" -> VoiceCommand.Keys(listOf(Keys.PLAY), "Play")
            "select", "ok", "okay", "enter" -> VoiceCommand.Keys(listOf(Keys.OK), "Select")
            "turn off", "turn off the tv", "turn the tv off", "switch off", "switch off the tv", "power off", "tv off" ->
                VoiceCommand.Power(on = false)
            "turn on", "turn on the tv", "turn the tv on", "switch on", "power on", "tv on" -> VoiceCommand.Power(on = true)
            "input", "source", "change input", "change source" -> VoiceCommand.Keys(listOf(Keys.SOURCE), "Input Source")
            "settings", "tv settings", "open settings" -> VoiceCommand.Keys(listOf(Keys.MENU), "TV Settings")
            else -> null
        }
    }

    private fun count(word: String): Int? =
        (word.toIntOrNull() ?: numbers[word])?.coerceIn(1, 20)

    private fun findApp(phrase: String, apps: List<TvApp>): TvApp? {
        val p = phrase.removeSuffix(" app").removePrefix("the ").trim()
        val target = aliases[p] ?: p
        return apps.firstOrNull { it.name.equals(target, ignoreCase = true) }
            ?: apps.firstOrNull { it.name.lowercase().replace("+", " plus") == target }
    }
}
