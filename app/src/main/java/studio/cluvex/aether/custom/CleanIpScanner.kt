package studio.cluvex.aether.custom

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.DiagnosticsLog

/**
 * Clean-IP scan (1.11.0): for a Cloudflare-fronted config (BPB-panel-style VLESS,
 * Trojan, etc. - anything whose TLS `serverName` is a domain Cloudflare answers for),
 * finds a nearby Cloudflare edge address that is fast and actually reachable right
 * now, and swaps the config's SERVER ADDRESS to it while leaving the SNI untouched.
 * This is what fixes a config whose default IP has been throttled or blocked without
 * needing a separate IP-scanner app or a new config link.
 *
 * ## What a "candidate" test actually checks
 *
 * The app already excludes itself from its own VPN tunnel (every outbound probe in
 * this codebase relies on that - see [studio.cluvex.aether.core.SpeedTest]'s own
 * note), so a scan measures the real path from this phone's current network to each
 * candidate, whether or not the tunnel happens to be up; there is no need to turn
 * the VPN off first the way a scan in a plain IP-scanner app would.
 *
 * A candidate is tested with a raw TLS handshake straight to `ip:port`, presenting
 * [sni] as the SNI name - exactly what the real proxy connection will do - and timing
 * how long that handshake takes. This is deliberately NOT a full VLESS/Trojan/etc.
 * round trip through Xray: spinning up a real proxy session per candidate would make
 * testing dozens of addresses far too slow to be useful. A successful TLS handshake
 * at a Cloudflare edge address is what every established clean-IP scanner treats as
 * "this address is alive and fronts this hostname", and is a strong proxy for "a real
 * connection through it will work", without being the real thing. Certificate
 * validation is intentionally skipped for this: a clean IP is still Cloudflare's own
 * anycast network, and the connection carries no payload, only a timed handshake.
 */
object CleanIpScanner {

    /**
     * The SNI a config's TLS layer presents, or null when scanning does not apply:
     * no `security: tls` (a REALITY config needs the real server's own certificate, a
     * plaintext one has no SNI to front at all), or no `serverName` set.
     */
    fun targetSniOf(config: CustomConfig): String? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(config.outbound) as? JsonObject ?: return null
        val stream = root["streamSettings"] as? JsonObject ?: return null
        if ((stream["security"] as? JsonPrimitive)?.contentOrNull != "tls") return null
        val tls = stream["tlsSettings"] as? JsonObject ?: return null
        (tls["serverName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The port scanning should test candidates on - the config's own current server port. */
    fun targetPortOf(config: CustomConfig): Int? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(config.outbound) as? JsonObject ?: return null
        val settings = root["settings"] as? JsonObject ?: return null
        val server = (settings["servers"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            ?: return null
        (server["port"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }.getOrNull()

    data class Candidate(val ip: String, val ms: Long)

    /** No candidate is pursued if even this baseline timeout is not enough for a bare handshake. */
    private const val HANDSHAKE_TIMEOUT_MS = 2_500
    private const val PARALLEL = 24
    private const val TAG = "clean-ip"

    /**
     * The default pool, sampled from when no custom range is set: Cloudflare's own
     * published anycast ranges (cloudflare.com/ips-v4), trimmed to the blocks most
     * commonly seen serving proxy-friendly ports.
     */
    val DEFAULT_RANGES = listOf(
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "188.114.96.0/20",
        "198.41.128.0/17",
    )

    /**
     * Scans up to [depth] random candidates from [ranges] (or [DEFAULT_RANGES] when
     * [ranges] is blank) against `[sni]:[port]`, [PARALLEL] at a time, and returns the
     * fastest one that answered - or null when none did. [onProgress] is called after
     * every attempt (tested count, total), from a background thread.
     */
    suspend fun scan(
        sni: String,
        port: Int,
        ranges: String,
        depth: Int,
        onProgress: (tested: Int, total: Int) -> Unit,
    ): Candidate? = withContext(Dispatchers.IO) {
        val pool = candidatesFrom(ranges.ifBlank { DEFAULT_RANGES.joinToString(",") })
        if (pool.isEmpty()) return@withContext null
        val picks = pool.shuffled(Random(System.nanoTime())).take(depth.coerceIn(1, pool.size))
        val gate = Semaphore(PARALLEL)
        val tested = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            picks.map { ip ->
                async {
                    val ms = gate.withPermit { handshake(ip, port, sni) }
                    val done = tested.incrementAndGet()
                    onProgress(done, picks.size)
                    ms?.let { Candidate(ip, it) }
                }
            }.mapNotNull { it.await() }
        }
        val best = results.minByOrNull { it.ms }
        DiagnosticsLog.i(TAG, "Scanned ${picks.size} candidate(s) for $sni:$port - ${results.size} answered" +
            (best?.let { ", best ${it.ip} (${it.ms} ms)" } ?: ", none qualified") + ".")
        best
    }

    /** Null on any failure or timeout; otherwise the handshake's wall-clock time in ms. */
    private fun handshake(ip: String, port: Int, sni: String): Long? {
        val started = System.currentTimeMillis()
        var socket: Socket? = null
        return try {
            val raw = Socket()
            socket = raw
            raw.connect(InetSocketAddress(ip, port), HANDSHAKE_TIMEOUT_MS)
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(LENIENT_TRUST_MANAGER), SecureRandom())
            val ssl = context.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
            socket = ssl
            val params = SSLParameters().apply { serverNames = listOf(SNIHostName(sni)) }
            ssl.sslParameters = params
            ssl.startHandshake()
            System.currentTimeMillis() - started
        } catch (e: Exception) {
            DiagnosticsLog.d(TAG, "$ip:$port rejected: ${e.message}")
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * A candidate's own identity is never checked here (see the class doc on why);
     * only Cloudflare's edge as a whole is being timed, nothing is sent to it.
     */
    private val LENIENT_TRUST_MANAGER = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Hard ceiling so a typo like a /8 cannot blow up memory expanding every address in it. */
    private const val MAX_POOL = 20_000

    /** Parses a comma/newline/space-separated list of CIDR blocks and/or bare IPv4 addresses. */
    internal fun candidatesFrom(ranges: String): List<String> {
        val out = ArrayList<String>()
        for (token in ranges.split(',', '\n', ' ', '\t')) {
            val entry = token.trim()
            if (entry.isEmpty()) continue
            if ('/' in entry) {
                out += expandCidr(entry)
            } else if (isIpv4(entry)) {
                out += entry
            }
            if (out.size > MAX_POOL) return out.take(MAX_POOL)
        }
        return out
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    private fun expandCidr(cidr: String): List<String> {
        val (base, bitsText) = cidr.split('/', limit = 2)
        if (!isIpv4(base)) return emptyList()
        val bits = bitsText.toIntOrNull() ?: return emptyList()
        if (bits !in 0..32) return emptyList()
        val hostBits = 32 - bits
        if (hostBits > 20) return emptyList() // a block bigger than /12 is refused outright - MAX_POOL would just truncate it unevenly
        val octets = base.split('.').map { it.toInt() }
        var addr = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        val mask = if (bits == 0) 0 else (-1 shl hostBits)
        addr = addr and mask
        val count = 1L shl hostBits
        val out = ArrayList<String>(minOf(count, MAX_POOL.toLong()).toInt())
        var i = 0L
        while (i < count && out.size < MAX_POOL) {
            val a = addr + i.toInt()
            out += "${(a ushr 24) and 0xFF}.${(a ushr 16) and 0xFF}.${(a ushr 8) and 0xFF}.${a and 0xFF}"
            i++
        }
        return out
    }
}
