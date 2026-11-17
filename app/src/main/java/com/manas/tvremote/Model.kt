package com.manas.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address

data class TvApp(val id: String, val name: String)

/**
 * App shortcuts. IDs are Samsung app-store IDs; only the ones installed on the
 * paired TV are shown, so an unknown or wrong ID simply never appears.
 */
val KNOWN_APPS = listOf(
    TvApp("3201907018807", "Netflix"),
    TvApp("111299001912", "YouTube"),
    TvApp("3201910019365", "Prime Video"),
    TvApp("3201901017640", "Disney+"),
    TvApp("3201807016597", "Apple TV"),
    TvApp("3201606009684", "Spotify"),
    TvApp("3201908019041", "Apple Music"),
    TvApp("3201512006963", "Plex"),
    TvApp("3202010022079", "Browser"),
)

/** Persisted pairing. One TV per phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("tv", Context.MODE_PRIVATE)

    var ip: String?
        get() = p.getString("ip", null)
        set(v) = p.edit().putString("ip", v).apply()
    var name: String?
        get() = p.getString("name", null)
        set(v) = p.edit().putString("name", v).apply()
    var mac: String?
        get() = p.getString("mac", null)
        set(v) = p.edit().putString("mac", v).apply()
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    /** SHA-256 of the TV's TLS certificate, pinned at pairing. */
    var certPin: String?
        get() = p.getString("certPin", null)
        set(v) = p.edit().putString("certPin", v).apply()
    var useDpad: Boolean
        get() = p.getBoolean("dpad", false)
        set(v) = p.edit().putBoolean("dpad", v).apply()

    /** Set once the touch surface has been used enough that its hint can go. */
    var padLearned: Boolean
        get() = p.getBoolean("padLearned", false)
        set(v) = p.edit().putBoolean("padLearned", v).apply()

    fun forget() = p.edit().remove("ip").remove("name").remove("mac").remove("token").remove("certPin").apply()
}

/**
 * Finds Samsung TVs by probing port 8001 on every address of the phone's Wi-Fi subnet
 * (a /22 at most: a bigger subnet is cut to the /22 around the phone).
 */
object Discovery {
    /** The phone's IPv4 address on Wi-Fi (not cellular or VPN), with its prefix length. */
    private fun wifiAddress(context: Context): LinkAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val wifi = (listOfNotNull(cm.activeNetwork) + cm.allNetworks).firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(wifi)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
    }

    suspend fun scan(context: Context): List<TvInfo> {
        val link = wifiAddress(context) ?: return emptyList()
        val own = link.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val prefix = link.prefixLength.coerceIn(22, 30)
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = own and mask
        val hosts = (1 until (1L shl (32 - prefix)) - 1).map { network + it }.filter { it != own }
        val gate = Semaphore(64)
        return coroutineScope {
            hosts.map { n ->
                val ip = "${n shr 24 and 0xFF}.${n shr 16 and 0xFF}.${n shr 8 and 0xFF}.${n and 0xFF}"
                async(Dispatchers.IO) { gate.withPermit { SamsungTv.info(ip, fast = true) } }
            }.awaitAll().filterNotNull()
        }
    }
}

/** Samsung key codes used by the remote. */
object Keys {
    const val UP = "KEY_UP"
    const val DOWN = "KEY_DOWN"
    const val LEFT = "KEY_LEFT"
    const val RIGHT = "KEY_RIGHT"
    const val OK = "KEY_ENTER"
    const val BACK = "KEY_RETURN"
    const val HOME = "KEY_HOME"
    const val VOL_UP = "KEY_VOLUP"
    const val VOL_DOWN = "KEY_VOLDOWN"
    const val MUTE = "KEY_MUTE"
    const val CH_UP = "KEY_CHUP"
    const val CH_DOWN = "KEY_CHDOWN"
    const val PLAY = "KEY_PLAY"
    const val PAUSE = "KEY_PAUSE"
    const val SOURCE = "KEY_SOURCE"
    const val MENU = "KEY_MENU"
}
