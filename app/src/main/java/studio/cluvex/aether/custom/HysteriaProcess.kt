package studio.cluvex.aether.custom

import android.util.Log
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.core.DiagnosticsLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the Hysteria 2 client as a child process (`libhysteria.so`), the same way
 * [XrayProcess] runs Xray.
 *
 * The bundled Xray build has no Hysteria outbound, so a `hysteria2://` config
 * cannot go through it. Hysteria's own client is shipped next to it instead. It
 * listens as a SOCKS5 proxy on a loopback port, which is exactly what the rest of
 * the pipeline (hev-socks5-tunnel, the liveness probe) already expects from a core,
 * so nothing after this class needs to know the difference.
 *
 * The file is named `lib....so` only so the package manager unpacks it into the
 * native library directory, the one place an app may execute a binary from; it is
 * an executable, not a shared library.
 */
class HysteriaProcess(
    private val nativeLibDir: String,
    private val workDir: File,
) {
    @Volatile
    private var process: Process? = null
    private var configFile: File? = null

    fun available(): Boolean = File(nativeLibDir, BINARY).exists()

    /** Starts the client with [configJson]. Throws if the binary is missing. */
    fun start(configJson: String, tag: String) {
        val bin = File(nativeLibDir, BINARY)
        if (!bin.exists()) throw IllegalStateException(MISSING)
        stop()
        val dir = File(workDir, "hysteria").apply { mkdirs() }
        val file = File(dir, "client-$tag.json")
        file.writeText(configJson)
        // The config holds the server password: owner-only.
        runCatching {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
        configFile = file
        // --disable-update-check: the client would otherwise call Hysteria's update
        // server on every start, from outside the tunnel.
        val builder = ProcessBuilder(
            bin.absolutePath, "client",
            "-c", file.absolutePath,
            "--disable-update-check",
            "--log-level", "warn",
        )
            .directory(dir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", dir.absolutePath)
            put("TMPDIR", dir.absolutePath)
        }
        val proc = builder.start()
        process = proc
        DiagnosticsLog.i(LOG_TAG, "Spawned ${bin.name} ($tag)")
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        if (BuildConfig.DEBUG) Log.i("aether-hysteria", it)
                        DiagnosticsLog.d(LOG_TAG, XrayProcess.redact(it))
                    }
                }
            } catch (_: Exception) {
                // The stream closes when the process exits.
            } finally {
                DiagnosticsLog.w(LOG_TAG, "Hysteria output stream closed ($tag).")
            }
        }, "hysteria-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun stop() {
        val proc = process
        process = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(GRACEFUL_EXIT_MS, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            }
        }
        configFile?.let { runCatching { it.delete() } }
        configFile = null
    }

    companion object {
        const val BINARY = "libhysteria.so"
        const val LOG_TAG = "hysteria"
        const val MISSING = "This APK does not contain the Hysteria 2 core (libhysteria.so)."
        private const val GRACEFUL_EXIT_MS = 400L
    }
}
