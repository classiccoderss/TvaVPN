package studio.cluvex.aether.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.InetAddress

/**
 * What a Hysteria 2 config needs, read back from [CustomConfig.outbound], and the
 * client configuration built from it.
 *
 * [ConfigParser] stores a Hysteria config in the outbound shape Xray uses for it
 * (`protocol: hysteria`, `settings.address/port`, `streamSettings.hysteriaSettings.auth`,
 * `tlsSettings`, `finalmask.udp` for Salamander), so a full Xray JSON that already
 * has a Hysteria outbound is read the same way as a share link. The config is run by
 * Hysteria's own client ([HysteriaProcess]), not by Xray, because only that client can
 * skip certificate checks for the self-signed servers most shared links point at:
 * recent Xray releases removed `allowInsecure` and refuse the whole config that has it.
 */
internal data class HysteriaSettings(
    val host: String,
    val port: Int,
    /** The port list as written ("443,5000-6000", "20000-30000") when it is more than one port, else "". */
    val portSpec: String,
    val auth: String,
    val sni: String,
    val insecure: Boolean,
    val pinSha256: String,
    /** "salamander" or "". */
    val obfs: String,
    val obfsPassword: String,
) {
    /**
     * The client configuration, as JSON (the client reads it by its extension).
     *
     * @param serverIp the address to dial. The app resolves it, not the client: a Go
     *   program on Android has no usable system resolver of its own. Certificate
     *   checks still use [sni] (or the original host name), never this IP.
     * @param listenPort loopback port of the SOCKS5 listener.
     * @param user,pass optional SOCKS5 credentials, used by the delay test (its probe
     *   always offers user/password); the tunnel connects without them.
     */
    fun clientConfig(serverIp: String, listenPort: Int, user: String? = null, pass: String? = null): String {
        val ipPart = if (serverIp.contains(':')) "[$serverIp]" else serverIp
        val ports = portSpec.ifBlank { "$port" }
        val name = sni.ifBlank { if (isIpLiteral(host)) "" else host }
        return buildJsonObject {
            put("server", "$ipPart:$ports")
            put("auth", auth)
            put("tls", buildJsonObject {
                if (name.isNotBlank()) put("sni", name)
                if (insecure) put("insecure", true)
                if (pinSha256.isNotBlank()) put("pinSHA256", pinSha256)
            })
            if (obfs.isNotBlank()) {
                put("obfs", buildJsonObject {
                    put("type", obfs)
                    put(obfs, buildJsonObject { put("password", obfsPassword) })
                })
            }
            put("socks5", buildJsonObject {
                put("listen", "127.0.0.1:$listenPort")
                if (user != null && pass != null) {
                    put("username", user)
                    put("password", pass)
                }
            })
            put("fastOpen", true)
        }.toString()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reads a stored outbound, or null when it is not a Hysteria one or has no address. */
        fun of(outbound: String): HysteriaSettings? = runCatching {
            val o = json.parseToJsonElement(outbound).jsonObject
            val settings = o["settings"]!!.jsonObject
            val stream = o["streamSettings"]?.jsonObject
            val tls = stream?.get("tlsSettings")?.jsonObject
            val hysteria = stream?.get("hysteriaSettings")?.jsonObject
            val mask = (stream?.get("finalmask")?.jsonObject?.get("udp") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull { it.jsonObject.str("type").equals("salamander", ignoreCase = true) }
                ?.jsonObject
            HysteriaSettings(
                host = settings.str("address").trim('[', ']'),
                port = settings["port"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 443,
                portSpec = settings.str("ports"),
                auth = hysteria?.str("auth").orEmpty(),
                sni = tls?.str("serverName").orEmpty(),
                insecure = tls?.get("allowInsecure")?.jsonPrimitive?.booleanOrNull ?: false,
                pinSha256 = tls?.str("pinSHA256").orEmpty(),
                obfs = if (mask != null) "salamander" else "",
                obfsPassword = mask?.get("settings")?.jsonObject?.str("password").orEmpty(),
            ).takeIf { it.host.isNotBlank() }
        }.getOrNull()

        private fun JsonObject.str(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')

        /**
         * The address to dial for [host]: the host itself when it already is an IP,
         * otherwise a lookup through the phone's own network (this app is outside the
         * VPN, so the lookup does not go into the tunnel). IPv4 is preferred.
         * Returns null when the name does not resolve. Blocking: call off the main thread.
         */
        fun resolve(host: String): String? {
            if (isIpLiteral(host)) return host
            return runCatching {
                val all = InetAddress.getAllByName(host)
                (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
            }.getOrNull()
        }
    }
}
