package studio.cluvex.aether.custom

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.PortProbe
import java.security.SecureRandom

/**
 * Real-delay test for one Hysteria 2 config.
 *
 * [RealDelay] measures many configs at once by putting every outbound into a single
 * Xray instance. A Hysteria config cannot do that (Xray has no Hysteria outbound), so
 * each one gets its own short-lived client process: start it on a free loopback port,
 * make the same timed request through it that every other config gets
 * ([RealDelay.measurePort]), stop it. The number measured at the same time is capped
 * by the caller.
 */
internal object HysteriaDelay {

    private const val START_TIMEOUT_MS = 6_000L

    /** Only the start-up (pick a port, spawn, wait for it to listen) is serialised, so two tests never pick the same port. */
    private val startLock = Mutex()
    private val random = SecureRandom()

    suspend fun measure(context: Context, cfg: CustomConfig): RealDelay.Result = withContext(Dispatchers.IO) {
        val settings = HysteriaSettings.of(cfg.outbound)
            ?: return@withContext RealDelay.Result.Invalid("not a Hysteria 2 config")
        val process = HysteriaProcess(context.applicationInfo.nativeLibraryDir, context.filesDir)
        if (!process.available()) return@withContext RealDelay.Result.Invalid(HysteriaProcess.MISSING)
        val ip = HysteriaSettings.resolve(settings.host)
            ?: return@withContext RealDelay.Result.NoReply("the server name did not resolve")

        // The probe always offers user/password, so give the client some.
        val user = "t" + token()
        val pass = token()
        try {
            val port = startLock.withLock {
                val picked = RealDelay.freeLoopbackPorts(1).first()
                process.start(settings.clientConfig(ip, picked, user, pass), "ping-${cfg.id.take(8)}")
                val listening = PortProbe.awaitOpen("127.0.0.1", picked, START_TIMEOUT_MS) { process.isAlive() }
                if (listening) picked else -1
            }
            if (port < 0) {
                return@withContext RealDelay.Result.Invalid("the Hysteria client did not start")
            }
            RealDelay.measurePort(port, user = user, pass = pass)
        } finally {
            process.stop()
        }
    }

    private fun token(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
