package studio.cluvex.aether.core

import android.os.SystemClock

/**
 * Connection-success telemetry taken from the flows the user's own apps open
 * (1.8.3, "Handshake Sentinel").
 *
 * ## The failure this closes
 *
 * Both watchdogs in [studio.cluvex.aether.vpn.AetherVpnService] share one rule,
 * and both say it in their own comments: while the data path is carrying bytes,
 * a failed probe is NOT evidence of a wedge ("A PROBE IS AN OPINION, THE METER
 * IS A FACT"). That rule is right about restarts - rebuilding the session under
 * a live stream is the "it reconnects over and over" symptom.
 *
 * It also leaves one very common failure completely invisible. On a filtered
 * path the tunnel is up, the exit still answers on the connections that already
 * exist (a video keeps streaming, Telegram's long-lived socket stays warm), and
 * every NEW connection is dropped: the exit is being blackholed per-flow, the
 * server has stopped accepting, or the operator is eating fresh handshakes. The
 * meter keeps moving, so `probeFailures` is reset to zero on every wake-up, and
 * the latency probe cannot see it either because [PingMonitor] reuses its warm
 * session - the one connection that still works. The user's screen reads
 * "connected", the badge reads a fine ping, and no site opens until they toggle
 * the switch by hand.
 *
 * ## Why this data and not another probe
 *
 * Every new TCP flow already passes through [SocksTunBridge]'s dial, and that
 * dial's outcome is the only honest answer to "can this path still open a
 * connection right now?" - measured on real destinations, no extra packets, no
 * extra timers. [HandshakeSentinel] only keeps a short rolling window of those
 * outcomes; the policy lives in [verdict].
 *
 * ## The anti-false-positive rules
 *
 * A single app retrying one dead host must never be able to rebuild a healthy
 * session, so a verdict needs ALL of:
 *  * [MIN_ATTEMPTS] dials in the window (a quiet phone is never judged),
 *  * [MIN_FAILURES] failures out of those,
 *  * at least [MIN_FAILURE_RATIO] of attempts failed,
 *  * failures spread over at least [MIN_DISTINCT_DESTINATIONS] different
 *    `host:port` destinations (one unreachable server is not a broken path),
 *  * the failures spanning at least [MIN_SPAN_MS], so a burst of tabs opening
 *    in the same instant - including the ones an app fires off while a fresh
 *    session is still settling - is not mistaken for a sustained outage.
 *
 * Everything is best-effort and never throws: a diagnostic that can break the
 * data path is worse than no diagnostic.
 */
object HandshakeSentinel {

    private const val WINDOW_MS = 45_000L
    private const val MAX_ENTRIES = 512

    private const val MIN_ATTEMPTS = 8
    private const val MIN_FAILURES = 6
    private const val MIN_DISTINCT_DESTINATIONS = 3
    private const val MIN_SPAN_MS = 8_000L
    private const val MIN_FAILURE_RATIO = 0.75

    /** One upstream dial, as the data path actually experienced it. */
    private class Outcome(
        val atMs: Long,
        val ok: Boolean,
        val dest: String,
        val kind: String,
        val tookMs: Long,
    )

    /** What the window says, when it says anything worth acting on. */
    data class Verdict(
        val attempts: Int,
        val failures: Int,
        val destinations: Int,
        val spanMs: Long,
        val kinds: String,
    ) {
        fun summary(): String =
            "$failures/$attempts new connections failed across $destinations destinations " +
                "in ${spanMs / 1000}s ($kinds)"
    }

    private val lock = Any()
    private val entries = ArrayDeque<Outcome>()

    /**
     * Records one dial through the tunnel.
     *
     * @param dest stable identity of the destination (`domain:port` when the
     *   name is known, otherwise `ip:port`).
     * @param kind short reason, used only to make the log line diagnosable.
     */
    fun record(ok: Boolean, dest: String, tookMs: Long, kind: String) {
        runCatching {
            synchronized(lock) {
                val now = SystemClock.elapsedRealtime()
                entries.addLast(Outcome(now, ok, dest, kind, tookMs))
                trim(now)
            }
        }
    }

    /**
     * The current verdict, or null while the window cannot support one.
     *
     * Pure read: the window is NOT cleared here, so a caller that decides not to
     * act (recovery switched off) still has the evidence in front of it, and the
     * same verdict cannot quietly disappear between two checks in the same
     * watchdog cycle.
     */
    fun verdict(): Verdict? = runCatching {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            trim(now)
            if (entries.size < MIN_ATTEMPTS) return null
            val failed = entries.filter { !it.ok }
            if (failed.size < MIN_FAILURES) return null
            if (failed.size.toDouble() / entries.size < MIN_FAILURE_RATIO) return null
            val destinations = failed.mapTo(HashSet()) { it.dest }
            if (destinations.size < MIN_DISTINCT_DESTINATIONS) return null
            val spanMs = failed.last().atMs - failed.first().atMs
            if (spanMs < MIN_SPAN_MS) return null
            val kinds = failed.groupingBy { it.kind }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} x${it.value}" }
            Verdict(
                attempts = entries.size,
                failures = failed.size,
                destinations = destinations.size,
                spanMs = spanMs,
                kinds = kinds,
            )
        }
    }.getOrNull()

    /** Drops the window once a verdict has been acted on, so it cannot fire twice for one outage. */
    fun consume() {
        runCatching { synchronized(lock) { entries.clear() } }
    }

    /** Called when a session ends or a new one comes up: a window belongs to one path. */
    fun reset() {
        runCatching { synchronized(lock) { entries.clear() } }
    }

    private fun trim(now: Long) {
        while (entries.isNotEmpty() && now - entries.first().atMs > WINDOW_MS) {
            entries.removeFirst()
        }
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }
}
