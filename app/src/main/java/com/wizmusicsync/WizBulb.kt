package com.wizmusicsync

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** WiZ local UDP API (port 38899): command payloads, one-off sends and bulb discovery. */
object WizBulb {
    const val PORT = 38899

    data class Found(val ip: String, val mac: String, val model: String?)

    fun pilot(color: LightColor): String = setPilot(
        JSONObject()
            .put("r", color.r)
            .put("g", color.g)
            .put("b", color.b)
            .put("c", 0)
            .put("w", 0)
    , color.dimming)

    /** Clean natural white using the bulb's dedicated white diodes. */
    fun white(light: WhiteLight): String = setPilot(JSONObject().put("temp", light.temp), light.dimming)

    private fun setPilot(params: JSONObject, dimming: Int): String {
        params.put("state", true)
            .put("dimming", dimming.coerceIn(10, 100))
            .put("speed", 100)
        return JSONObject().put("id", 1).put("method", "setPilot").put("params", params).toString()
    }

    /** Fire-and-forget send. Blocking network call: never call on the main thread. */
    fun sendOnce(ip: String, payload: String) {
        try {
            DatagramSocket().use { socket ->
                val bytes = payload.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), PORT))
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Broadcasts WiZ discovery messages on the current network and collects the bulbs that answer.
     * Blocking for about [listenMs]: never call on the main thread.
     */
    fun discover(context: Context, listenMs: Long = 3000): List<Found> {
        val found = LinkedHashMap<String, String>() // ip -> mac
        val probes = listOf(
            """{"method":"registration","params":{"phoneMac":"AAAAAAAAAAAA","register":false,"phoneIp":"1.2.3.4","id":"1"}}""",
            """{"method":"getPilot","params":{}}""",
        ).map { it.toByteArray() }
        val targets = broadcastAddresses(context) + InetAddress.getByName("255.255.255.255")

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val buffer = ByteArray(2048)
            val end = System.currentTimeMillis() + listenMs
            var nextProbe = 0L
            var probesSent = 0
            while (System.currentTimeMillis() < end) {
                // Repeat the probe a few times: UDP broadcasts are easily dropped on busy Wi-Fi
                if (probesSent < 3 && System.currentTimeMillis() >= nextProbe) {
                    for (target in targets) for (probe in probes) {
                        try {
                            socket.send(DatagramPacket(probe, probe.size, target, PORT))
                        } catch (_: Exception) {
                        }
                    }
                    probesSent++
                    nextProbe = System.currentTimeMillis() + 800
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val mac = JSONObject(String(packet.data, 0, packet.length))
                        .optJSONObject("result")?.optString("mac").orEmpty()
                    if (mac.isNotEmpty()) found[packet.address.hostAddress ?: continue] = mac
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }
            }
        }

        return found.map { (ip, mac) -> Found(ip, formatMac(mac), modelOf(ip)) }
    }

    /** Asks one bulb for its module name (e.g. ESP25_SHRGB_01); null if it doesn't answer. */
    private fun modelOf(ip: String): String? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 600
            val probe = """{"method":"getSystemConfig","params":{}}""".toByteArray()
            socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName(ip), PORT))
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            JSONObject(String(packet.data, 0, packet.length))
                .optJSONObject("result")?.optString("moduleName")?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    /** Directed broadcast address of each IPv4 subnet on the active network (e.g. 192.168.0.255). */
    private fun broadcastAddresses(context: Context): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val links = cm.getLinkProperties(cm.activeNetwork ?: return emptyList()) ?: return emptyList()
        return links.linkAddresses.mapNotNull { link ->
            val address = link.address as? Inet4Address ?: return@mapNotNull null
            val ip = address.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
            val mask = if (link.prefixLength == 0) 0 else -1 shl (32 - link.prefixLength)
            val broadcast = ip or mask.inv()
            InetAddress.getByAddress(ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() })
        }
    }

    private fun formatMac(mac: String) =
        if (mac.length == 12) mac.chunked(2).joinToString(":").uppercase() else mac
}

/** Persists the user's [LightTheme]; [current] is read live by the sync engine. */
object ThemeStore {
    @Volatile var current: LightTheme = LightTheme.DEFAULT
        private set

    private const val KEY = "theme"

    fun load(context: Context) {
        val json = prefs(context).getString(KEY, null) ?: return
        current = try {
            val o = JSONObject(json)
            val tiers = o.getJSONArray("tiers")
            LightTheme(
                tiers = List(4) { color(tiers.getJSONObject(it)) },
                drop = color(o.getJSONObject("drop")),
                idle = o.getJSONObject("idle").let { WhiteLight(it.getInt("temp"), it.getInt("dimming")) },
            )
        } catch (_: Exception) {
            LightTheme.DEFAULT
        }
    }

    fun save(context: Context, theme: LightTheme) {
        current = theme
        val o = JSONObject()
            .put("tiers", org.json.JSONArray(theme.tiers.map { json(it) }))
            .put("drop", json(theme.drop))
            .put("idle", JSONObject().put("temp", theme.idle.temp).put("dimming", theme.idle.dimming))
        prefs(context).edit().putString(KEY, o.toString()).apply()
    }

    fun reset(context: Context) {
        current = LightTheme.DEFAULT
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun color(o: JSONObject) = LightColor(o.getInt("r"), o.getInt("g"), o.getInt("b"), o.getInt("dimming"))

    private fun json(c: LightColor) =
        JSONObject().put("r", c.r).put("g", c.g).put("b", c.b).put("dimming", c.dimming)
}
