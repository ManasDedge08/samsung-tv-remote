package com.manas.tvremote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val tv = SamsungTv(
        viewModelScope,
        onToken = { token -> prefs.token = token },
        pinnedCert = { prefs.certPin },
        onCertPinned = { pin -> prefs.certPin = pin },
    )

    var paired by mutableStateOf(prefs.ip != null && prefs.token != null)
        private set
    var tvName by mutableStateOf(prefs.name ?: "TV")
        private set
    var useDpad by mutableStateOf(prefs.useDpad)
        private set
    var padLearned by mutableStateOf(prefs.padLearned)
        private set
    private var padSteps = 0

    /** "on", "standby", "off" (unreachable) or "" before the first check. */
    var power by mutableStateOf("")
        private set
    var powerBusy by mutableStateOf(false)
        private set
    var apps by mutableStateOf<List<TvApp>>(emptyList())
        private set
    var keyboardOpen by mutableStateOf(false)

    /** True while the TV's own on-screen keyboard is up (it accepts typed text then). */
    private var tvKeyboard = false

    // Voice
    private val voice = VoiceInput(app)
    var voiceOpen by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set
    var heard by mutableStateOf("")
        private set
    var voiceLevel by mutableStateOf(0f)
        private set
    /** What the remote did with the last phrase, shown briefly before the sheet closes. */
    var voiceDone by mutableStateOf<String?>(null)
        private set
    var voiceError by mutableStateOf<String?>(null)
        private set
    /** Set while the remote is busy carrying out a phrase, such as typing on YouTube's keyboard. */
    var voiceWorking by mutableStateOf<String?>(null)
        private set
    /** Microphone permission was refused; only Settings can turn it back on. */
    var micDenied by mutableStateOf(false)
        private set
    private var voiceJob: Job? = null

    // Setup screen
    var scanning by mutableStateOf(false)
        private set
    var found by mutableStateOf<List<TvInfo>>(emptyList())
        private set
    var setupError by mutableStateOf<String?>(null)
        private set
    var pairingWith by mutableStateOf<TvInfo?>(null)
        private set

    private var paused = true
    private var pollJob: Job? = null
    private var lingerJob: Job? = null
    private var lastWasPlay = true

    init {
        prefs.ip?.let { tv.configure(it, prefs.token) }
        viewModelScope.launch {
            tv.events.collect {
                when (it) {
                    TvEvent.KeyboardOpened -> {
                        tvKeyboard = true
                        if (!voiceOpen) keyboardOpen = true
                    }
                    TvEvent.KeyboardClosed -> {
                        tvKeyboard = false
                        keyboardOpen = false
                    }
                }
            }
        }
        viewModelScope.launch {
            tv.conn.collect { c ->
                val pending = pairingWith
                if (c == Conn.Connected && pending != null) {
                    paired = true
                    pairingWith = null
                    loadApps()
                    if (!paused) startPolling()
                }
                if ((c == Conn.Denied || c == Conn.TimedOut) && pending != null) {
                    pairingWith = null
                    tv.stop()
                    setupError = if (c == Conn.TimedOut) {
                        "You have 30 seconds to select Allow on the TV. Try again."
                    } else {
                        "You selected Deny on the TV. Try again and select Allow."
                    }
                }
                if ((c == Conn.Denied || c == Conn.TimedOut || c == Conn.Untrusted) && pending == null && paired) {
                    tv.stop()
                    prefs.token = null
                    prefs.certPin = null
                    paired = false
                    apps = emptyList()
                    power = ""
                    setupError = if (c == Conn.Untrusted) {
                        "Your TV's security certificate changed. Connect again and select Allow."
                    } else {
                        "Your TV removed this phone. Connect again and select Allow."
                    }
                }
                if (c == Conn.Connected) power = "on"
            }
        }
    }

    fun onForeground() {
        paused = false
        lingerJob?.cancel()
        if (paired) {
            tv.start()
            startPolling()
            if (apps.isEmpty()) loadApps()
        } else if (found.isEmpty() && !scanning) {
            scan()
        }
    }

    /** Keeps the socket open for a minute so a quick app switch doesn't need a reconnect. */
    fun onBackground() {
        paused = true
        closeVoice()
        pollJob?.cancel()
        lingerJob?.cancel()
        lingerJob = viewModelScope.launch {
            delay(60_000)
            if (paused && pairingWith == null) tv.stop()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (!powerBusy) {
                    val state = tv.powerState()
                    if (!powerBusy) power = state
                }
                delay(if (power == "on") 6000 else 3000)
            }
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val list = tv.installed(KNOWN_APPS)
            if (list.isNotEmpty()) apps = list
        }
    }

    // ---- Commands ----

    fun key(code: String) = tv.key(code)

    fun playPause() {
        // Samsung has separate play and pause keys; alternate between them.
        tv.key(if (lastWasPlay) Keys.PAUSE else Keys.PLAY)
        lastWasPlay = !lastWasPlay
    }

    fun sendText(text: String) {
        if (text.isNotEmpty()) tv.text(text)
        keyboardOpen = false
    }

    fun launch(app: TvApp) {
        viewModelScope.launch { tv.launch(app.id) }
    }

    // ---- Voice ----

    /** True while the mic key is held down past a tap, like holding the remote's mic button. */
    var holdingMic by mutableStateOf(false)
        private set
    private var micDownAt = 0L
    private var holdJob: Job? = null

    fun openVoice() {
        micDownAt = System.currentTimeMillis()
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            delay(HOLD_MS)
            if (listening) holdingMic = true
        }
        micDenied = false
        voiceOpen = true
        keyboardOpen = false
        listen()
    }

    fun listen() {
        voiceJob?.cancel()
        heard = ""
        voiceDone = null
        voiceError = null
        voiceWorking = null
        voiceLevel = 0f
        if (!voice.available) {
            listening = false
            voiceError = "This phone has no speech recognition. Install or turn on Google's speech services to use voice."
            return
        }
        listening = true
        voice.start(object : VoiceInput.Listener {
            override fun onPartial(text: String) { heard = text }
            override fun onLevel(rms: Float) { voiceLevel = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onError(message: String) {
                listening = false
                voiceError = message
            }
            override fun onFinal(text: String) {
                listening = false
                heard = text
                act(text)
            }
        })
    }

    /** Stop listening early and act on what was heard so far. */
    fun finishVoice() = voice.finish()

    /** Letting go of a held mic key ends the phrase; letting go of a quick tap keeps listening. */
    fun releaseMic() {
        holdJob?.cancel()
        val held = System.currentTimeMillis() - micDownAt >= HOLD_MS
        holdingMic = false
        if (held && listening) finishVoice()
    }

    fun closeVoice() {
        voice.stop()
        voiceJob?.cancel()
        voiceWorking = null
        voiceOpen = false
        listening = false
    }

    fun voicePermissionDenied() {
        voiceOpen = true
        listening = false
        micDenied = true
        voiceDone = null
        heard = ""
        voiceError = "To use voice, allow Remote to use the microphone in Settings."
    }

    private fun act(text: String) {
        when (val cmd = VoiceCommands.parse(text, apps)) {
            is VoiceCommand.Keys -> {
                voiceJob = viewModelScope.launch {
                    cmd.keys.forEachIndexed { i, k ->
                        if (i > 0) delay(140)
                        tv.key(k)
                    }
                }
                done(cmd.label)
            }
            is VoiceCommand.Open -> {
                launch(cmd.app)
                done("Opening ${cmd.app.name}")
            }
            is VoiceCommand.Power -> {
                if (cmd.on == (power == "on")) {
                    done(if (cmd.on) "Your TV Is Already On" else "Your TV Is Already Off")
                } else {
                    togglePower()
                    done(if (cmd.on) "Turning On" else "Turning Off")
                }
            }
            null -> if (tvKeyboard) {
                tv.text(text)
                done("Typed on Your TV")
            } else {
                voiceJob = viewModelScope.launch {
                    voiceWorking = "Checking your TV…"
                    if (tv.visible(YOUTUBE_ID)) {
                        searchYouTube(text)
                    } else {
                        voiceWorking = null
                        voiceError = "To search, open Search in YouTube first, or open a search box on the TV. " +
                            "You can also say a command like \"Open Netflix\" or \"Volume Up 5\"."
                    }
                }
            }
        }
    }

    /**
     * YouTube ignores text sent to the TV, so type on its own keyboard key by key. Focus has to be
     * on A, which is where YouTube puts it when you open Search.
     */
    private suspend fun searchYouTube(text: String) {
        val keys = YouTubeKeyboard.plan(text)
        if (keys == null) {
            voiceWorking = null
            voiceError = "YouTube's keyboard can't type that. Try saying it in English."
            return
        }
        voiceWorking = "Typing on YouTube…"
        for (k in keys) {
            tv.key(k)
            delay(if (k == Keys.OK) KEY_OK_MS else KEY_MOVE_MS)
        }
        voiceWorking = null
        done("Searching YouTube")
    }

    private fun done(label: String) {
        voiceDone = label
        voiceJob = viewModelScope.launch {
            delay(1200)
            if (voiceDone == label) closeVoice()
        }
    }

    fun togglePower() {
        if (powerBusy) return
        powerBusy = true
        viewModelScope.launch {
            try {
                power = if (power == "on") "standby" else "waking"
                power = tv.togglePower(prefs.mac)
                if (power == "on" && !paused) tv.start()
            } finally {
                powerBusy = false
            }
        }
    }

    fun onPadStep() {
        if (padLearned) return
        if (++padSteps >= 8) {
            padLearned = true
            prefs.padLearned = true
        }
    }

    fun toggleDpad() {
        useDpad = !useDpad
        prefs.useDpad = useDpad
    }

    fun forget() {
        tv.stop()
        prefs.forget()
        paired = false
        apps = emptyList()
        power = ""
        found = emptyList()
        scan()
    }

    // ---- Setup ----

    fun scan() {
        if (scanning) return
        scanning = true
        setupError = null
        viewModelScope.launch {
            found = Discovery.scan(getApplication())
            scanning = false
        }
    }

    var checkingIp by mutableStateOf(false)
        private set

    fun pairManual(ip: String) {
        val clean = ip.trim()
        if (checkingIp || pairingWith != null || clean.isEmpty()) return
        checkingIp = true
        setupError = null
        viewModelScope.launch {
            val info = try { SamsungTv.info(clean) } finally { checkingIp = false }
            if (info == null) {
                setupError = "No TV responded at $clean."
            } else {
                pair(info)
            }
        }
    }

    fun pair(info: TvInfo) {
        if (pairingWith != null) return
        setupError = null
        prefs.ip = info.ip
        prefs.name = info.name
        prefs.mac = info.mac
        prefs.token = null
        prefs.certPin = null
        tvName = info.name
        pairingWith = info
        tv.stop()
        tv.configure(info.ip, null)
        tv.start()
    }

    fun cancelPairing() {
        pairingWith = null
        tv.stop()
    }

    private companion object {
        const val HOLD_MS = 500L
        /** Gaps between key presses when typing on an app's keyboard; tested on a 2022 Q60B. */
        const val KEY_MOVE_MS = 200L
        const val KEY_OK_MS = 400L
    }

    override fun onCleared() {
        voice.stop()
        tv.stop()
    }
}
