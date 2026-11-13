package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the connection is just alive, or actually working well (1.12.0).
 *
 * Every watch loop in [studio.cluvex.aether.vpn.AetherVpnService] (the native
 * engine's own supervisor, and the shared custom-config loop both Xray and Hysteria
 * use) already asks one binary question on its own existing wake-up cycle: is the
 * process still running, does the pipeline still carry data. This adds a second,
 * independent signal from the SAME wake-up - no new timer, no extra wake-ups beyond
 * what each loop already does - by also reading [PingMonitor]'s live latency there.
 * [PingMonitor] does the actual measuring (warm-connection reuse, outlier
 * confirmation, all already in place); this object only turns a stream of those
 * numbers into one visible word: fine, or not.
 *
 * [record] expects to be called once per existing check, with that check's
 * [PingResult]. [DEGRADED_AFTER] consecutive bad readings (a failed probe, or a
 * reply slower than [SLOW_MS]) mark the connection [Quality.POOR]; a single good one
 * clears it immediately, because this is meant to describe the last few seconds, not
 * to linger once things recover.
 */
object ConnectionQuality {

    enum class Quality { GOOD, POOR, UNKNOWN }

    private val _quality = MutableStateFlow(Quality.UNKNOWN)
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    private const val SLOW_MS = 1_500L
    private const val DEGRADED_AFTER = 2

    private var consecutiveBad = 0

    fun record(result: PingResult) {
        if (result.running) return
        val bad = result.error || result.ms < 0 || result.ms > SLOW_MS
        if (bad) {
            consecutiveBad++
            if (consecutiveBad >= DEGRADED_AFTER) _quality.value = Quality.POOR
        } else {
            consecutiveBad = 0
            _quality.value = Quality.GOOD
        }
    }

    /** Called when a session ends, so a leftover POOR or GOOD never bleeds into the next one. */
    fun reset() {
        consecutiveBad = 0
        _quality.value = Quality.UNKNOWN
    }
}
