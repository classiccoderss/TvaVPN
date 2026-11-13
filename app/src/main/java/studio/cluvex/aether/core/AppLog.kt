package studio.cluvex.aether.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-flow event log (tvaVPN) - deliberately SEPARATE from [DiagnosticsLog] /
 * [studio.cluvex.aether.transport.ConnectionLog], which exist for the
 * tunnel/engine/connection side of the app (exit IP, endpoints, the 1.1.1.1
 * self-test, etc) and are shown on the DIAGNOSTICS/LOGS pages.
 *
 * This is for everything else: UI flows that are not about the tunnel at all
 * but still fail in ways a user can see without a developer attached. The
 * first and still biggest source is external-import on the backup/restore
 * page (a file opened or shared in from outside the app), step by step:
 * payload received, whether the page was editable, what the file parse
 * returned, whether the confirm dialog was shown, and what the user did with
 * it - but [event] takes a `tag` precisely so ANY screen in the app can log
 * to this same trail, not just that one. It is a general, app-wide event log,
 * not a backup/import-specific one: see it on its own top-level Settings row
 * (AppLogPage, "App logs" / R.string.applog_nav_title in SettingsHomePage),
 * reached the same way regardless of which screen actually logged to it.
 *
 * In-memory only, capped, session-scoped: this is meant to catch "it didn't
 * work and I don't know why" while it is still fresh, not to survive a crash
 * the way the encrypted connection log does. The event log page's own "copy"
 * button is how a report gets out of the app.
 */
object AppLog {
    private const val MAX_LINES = 500

    data class Line(val timeMs: Long, val tag: String, val message: String) {
        fun format(): String {
            val ts = TS_FORMAT.get()?.format(Date(timeMs)) ?: timeMs.toString()
            return "$ts [$tag] $message"
        }

        private companion object {
            val TS_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            }
        }
    }

    private val buffer = ArrayDeque<Line>(MAX_LINES)
    private val lock = Any()

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    /**
     * App/build identification for [exportText]'s header - set once from
     * [init], read-only afterwards. Without this a copied report was just a
     * bare list of "HH:mm:ss [tag] message" lines with no app version,
     * no date and no device info, which made two reports from two users (or
     * two reports from the same user a week apart) impossible to tell apart
     * once pasted into a chat. `null` fields print as "unknown" rather than
     * being dropped, so a header line is never silently missing.
     */
    @Volatile
    private var appId: String? = null

    /**
     * Call once, as early as possible (e.g. [studio.cluvex.aether.AetherApp.onCreate]),
     * so every exported report is self-identifying. Safe to call more than
     * once (e.g. in a test); the latest call wins. Never throws - a failure
     * to read package/device info must not block startup or logging itself.
     */
    fun init(context: Context) {
        appId = runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            "${info.versionName} ($versionCode) - ${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        }.getOrNull()
    }

    /** Records one step. [tag] is the screen/flow ("backup-import", "custom-configs", ...). */
    fun event(tag: String, message: String) {
        val line = Line(System.currentTimeMillis(), tag, message)
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    /**
     * The text the "copy" button on [AppLogPage][studio.cluvex.aether.ui.settings.AppLogPage]
     * puts on the clipboard. Starts with a short header (app version/build,
     * device, Android version, export time) so a report is legible on its own
     * once it has been pasted somewhere else, then one formatted line per
     * event, oldest first. Empty buffer still exports the header, so "I
     * copied it and got nothing" is distinguishable from "copy failed".
     */
    fun exportText(): String {
        // A fresh formatter per call: this runs once per user tap (the copy
        // button), never in a hot loop, so there is no reason to share a
        // mutable SimpleDateFormat across threads just to save an allocation.
        val headerTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = "tvaVPN event log - ${appId ?: "app/device info unavailable"}\nexported $headerTs"
        val body = synchronized(lock) { buffer.toList() }
        return if (body.isEmpty()) {
            "$header\n(no events recorded this session)"
        } else {
            header + "\n\n" + body.joinToString("\n") { it.format() }
        }
    }
}
