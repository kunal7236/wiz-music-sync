package com.wizmusicsync

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.pow

/** WiZ local UDP API (port 38899): command payloads, one-off sends and bulb discovery. */
object WizBulb {
    const val PORT = 38899

    data class Found(val ip: String, val mac: String, val model: String?)

    fun payload(light: BulbLight): String {
        val params = when (light) {
            is BulbLight.Rgb -> JSONObject()
                .put("r", light.r)
                .put("g", light.g)
                .put("b", light.b)
                .put("c", 0)
                .put("w", 0)
            // Clean natural white using the bulb's dedicated white diodes
            is BulbLight.White -> JSONObject().put("temp", light.temp)
        }
        params.put("state", true)
            .put("dimming", light.dimming.coerceIn(10, 100))
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

    /** Asks the bulb whether it is on; null if it doesn't answer within [timeoutMs]. Blocking. */
    fun isOn(ip: String, timeoutMs: Int = 700): Boolean? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val probe = """{"method":"getPilot","params":{}}""".toByteArray()
            socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName(ip), PORT))
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            JSONObject(String(packet.data, 0, packet.length)).getJSONObject("result").getBoolean("state")
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Switches the bulb on (back to whatever it last showed) or off, retrying until the bulb
     * confirms or [timeoutMs] runs out — Wi-Fi can take a few seconds to wake after the phone sleeps.
     * Blocking: never call on the main thread.
     * @return true once the bulb reports the requested state
     */
    fun setPower(ip: String, on: Boolean, timeoutMs: Long = 8000): Boolean {
        val payload = JSONObject().put("id", 1).put("method", "setState")
            .put("params", JSONObject().put("state", on)).toString()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            sendOnce(ip, payload)
            if (isOn(ip) == on) return true
            Thread.sleep(300)
        }
        return false
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

/**
 * Every user setting, persisted in SharedPreferences. The @Volatile fields are read live by the
 * sync service, so edits apply while syncing.
 */
object Settings {
    const val DEFAULT_BULB_IP = "192.168.0.106"
    const val DEFAULT_SENSITIVITY = 50
    val DEFAULT_FIXED_LIGHT: BulbLight = BulbLight.White(4200, 100)

    @Volatile var theme: LightTheme = LightTheme.DEFAULT
        private set
    /** Holds every bulb command back by this much so the light lines up with a lagging speaker. */
    @Volatile var delayMs = 0
        private set
    /** 0-100; 50 is the original script. */
    @Volatile var sensitivity = DEFAULT_SENSITIVITY
        private set
    var bulbIp = DEFAULT_BULB_IP
        private set
    var fixedMode = false
        private set
    var fixedLight: BulbLight = DEFAULT_FIXED_LIGHT
        private set

    /** Level multiplier for the engine: 0.5x at 0, 1x at 50, 2x at 100. */
    val gain: Double get() = 2.0.pow((sensitivity - 50) / 50.0)

    fun load(context: Context) {
        val p = prefs(context)
        bulbIp = p.getString("bulbIp", DEFAULT_BULB_IP) ?: DEFAULT_BULB_IP
        delayMs = p.getInt("delayMs", 0)
        sensitivity = p.getInt("sensitivity", DEFAULT_SENSITIVITY)
        fixedMode = p.getBoolean("fixedMode", false)
        fixedLight = p.getString("fixedLight", null)?.let { json -> parseOr(DEFAULT_FIXED_LIGHT) { light(JSONObject(json)) } }
            ?: DEFAULT_FIXED_LIGHT
        theme = p.getString("theme", null)?.let { json ->
            parseOr(LightTheme.DEFAULT) {
                val o = JSONObject(json)
                val tiers = o.getJSONArray("tiers")
                LightTheme(
                    tiers = List(4) { light(tiers.getJSONObject(it)) },
                    drop = light(o.getJSONObject("drop")),
                    idle = light(o.getJSONObject("idle")),
                )
            }
        } ?: LightTheme.DEFAULT
    }

    fun setBulbIp(context: Context, ip: String) {
        bulbIp = ip
        prefs(context).edit().putString("bulbIp", ip).apply()
    }

    fun setDelayMs(context: Context, ms: Int) {
        delayMs = ms
        prefs(context).edit().putInt("delayMs", ms).apply()
    }

    fun setSensitivity(context: Context, value: Int) {
        sensitivity = value
        prefs(context).edit().putInt("sensitivity", value).apply()
    }

    fun setFixedMode(context: Context, fixed: Boolean) {
        fixedMode = fixed
        prefs(context).edit().putBoolean("fixedMode", fixed).apply()
    }

    fun setFixedLight(context: Context, light: BulbLight) {
        fixedLight = light
        prefs(context).edit().putString("fixedLight", json(light).toString()).apply()
    }

    fun setTheme(context: Context, value: LightTheme) {
        theme = value
        val o = JSONObject()
            .put("tiers", JSONArray(value.tiers.map { json(it) }))
            .put("drop", json(value.drop))
            .put("idle", json(value.idle))
        prefs(context).edit().putString("theme", o.toString()).apply()
    }

    fun resetTheme(context: Context) {
        theme = LightTheme.DEFAULT
        prefs(context).edit().remove("theme").apply()
    }

    /** Back to exactly how the app was on first install, schedules included. */
    fun resetAll(context: Context) {
        Scheduler.clearAll(context)
        prefs(context).edit().clear().apply()
        load(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <T> parseOr(fallback: T, parse: () -> T): T =
        try {
            parse()
        } catch (_: Exception) {
            fallback
        }

    /** Reads {"temp","dimming"} as white, {"r","g","b","dimming"} as colour (also the v1.1 format). */
    private fun light(o: JSONObject): BulbLight =
        if (o.has("temp")) BulbLight.White(o.getInt("temp"), o.getInt("dimming"))
        else BulbLight.Rgb(o.getInt("r"), o.getInt("g"), o.getInt("b"), o.getInt("dimming"))

    private fun json(light: BulbLight): JSONObject = when (light) {
        is BulbLight.Rgb -> JSONObject().put("r", light.r).put("g", light.g).put("b", light.b)
        is BulbLight.White -> JSONObject().put("temp", light.temp)
    }.put("dimming", light.dimming)
}
