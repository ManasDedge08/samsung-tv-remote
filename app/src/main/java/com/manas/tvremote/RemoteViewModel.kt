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
                    TvEvent.KeyboardOpened -> keyboardOpen = true
                    TvEvent.KeyboardClosed -> keyboardOpen = false
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

    override fun onCleared() {
        tv.stop()
    }
}
