package studio.cluvex.aether.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import studio.cluvex.aether.custom.CustomSession
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.sin
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.ui.theme.AetherGlowCyan
import studio.cluvex.aether.ui.theme.AetherMint
import studio.cluvex.aether.ui.theme.CardSubSurface
import studio.cluvex.aether.ui.theme.CardSurfaceBottom
import studio.cluvex.aether.ui.theme.CardSurfaceTop
import studio.cluvex.aether.ui.theme.CardTextDim
import studio.cluvex.aether.ui.theme.CardTextMuted
import studio.cluvex.aether.ui.theme.CardTextPrimary

/**
 * The connection screen's two content blocks (rewritten for 1.6.0).
 *
 * The old bottom block was ONE glass card that stacked status, timer, IP pill,
 * speed strip and two more slides, all inside a travelling-light border. It was
 * busy: six surfaces, three font sizes in mint, and a light show competing with
 * the power button for attention.
 *
 * It is now two quiet pieces with one job each:
 *
 *  1. [ConnectionStatus] - plain text under the button: what state the tunnel is
 *     in, one line of caption, and the session timer. No surface, no border.
 *  2. [ConnectionDetails] - ONE flat panel of rows divided by hairlines: speeds,
 *     address, route, latency. Label on one side, value on the other, nothing
 *     animated except the numbers themselves.
 *
 * Colours stay pinned to the brand palette (not MaterialTheme), so Material You
 * wallpaper colours cannot repaint the screen.
 */
@Composable
fun ConnectionStatus(
    connected: Boolean,
    statusTitle: String,
    statusCaption: String,
    connectedSince: Long?,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        error -> ERROR_ACCENT
        connected -> AetherMint
        else -> IDLE_ACCENT
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        if (connectedSince == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsed = if (connectedSince == null) 0L else (now - connectedSince).coerceAtLeast(0L) / 1000L
    val timer = String.format(
        Locale.US,
        "%02d:%02d:%02d",
        elapsed / 3600,
        (elapsed % 3600) / 60,
        elapsed % 60,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = statusTitle,
            transitionSpec = { (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false)) },
            modifier = Modifier.fillMaxWidth(),
            label = "status",
        ) { value ->
            // One fixed-height line: a long state name shrinks instead of wrapping,
            // so the screen never changes height between states.
            FitLineText(
                text = value,
                style = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                minLineHeight = 32.sp,
                color = accent,
                minFontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedContent(
            targetState = statusCaption,
            transitionSpec = { (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false)) },
            modifier = Modifier.fillMaxWidth(),
            label = "caption",
        ) { value ->
            // An error message can be arbitrarily long: it shrinks, then ellipsizes.
            FitLineText(
                text = value,
                style = TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center),
                minLineHeight = 20.sp,
                color = CardTextMuted,
                minFontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = timer,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 34.sp,
            letterSpacing = 1.sp,
            color = if (connected) CardTextPrimary else CardTextDim,
        )
    }
}

@Composable
fun ConnectionDetails(
    connected: Boolean,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    /** The session's mode; decides how the route row names the whole path. */
    backend: TransportBackend,
    modifier: Modifier = Modifier,
) {
    val stats = rememberTrafficStats(connectedSince = connectedSince, connected = connected)
    val meta by EngineMeta.state.collectAsState()
    val ping by PingMonitor.state.collectAsState()

    // Live latency: one cheap handshake through the tunnel every few seconds.
    LaunchedEffect(connected) {
        while (connected) {
            PingMonitor.pingOnce(viaTunnel = true)
            delay(LATENCY_REFRESH_MS)
        }
    }

    // PingMonitor publishes ms = -1 while a probe is in flight; keep the last good
    // reading so the number does not blink every few seconds.
    var lastMs by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(connected, ping.ms) {
        lastMs = when {
            !connected -> -1L
            ping.ms >= 0L -> ping.ms
            else -> lastMs
        }
    }

    val dash = "\u2014"
    val customInfo by CustomSession.info.collectAsState()
    val routeForms = customInfo?.takeIf { connected }
        ?.let { it.mode.compactLabels(it.configLabel, meta.protocol) }
        ?: listOf(if (connected) backend.pipelineLabel(meta.protocol) else dash)

    // Only an address that belongs to the current state is shown: the exit IP while
    // connected, the phone's own IP while not.
    val shownIp = ipInfo?.takeIf { it.viaTunnel == connected }
    val ipText = when {
        shownIp != null -> shownIp.ip
        ipLoading -> stringResource(R.string.ip_checking)
        else -> stringResource(R.string.ip_unavailable)
    }

    val grade = pingGrade(connected, lastMs)
    val latencyText = when {
        !connected -> dash
        lastMs >= 0L -> "$lastMs ms"
        ping.running -> "\u2026"
        else -> dash
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = DETAILS_SHAPE)
            .border(width = 1.dp, color = SUB_BORDER, shape = DETAILS_SHAPE)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedCell(
                icon = Icons.Rounded.ArrowDownward,
                tint = AetherMint,
                label = stringResource(R.string.traffic_download),
                rate = stats.downRate,
                total = stats.downTotal,
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            SpeedCell(
                icon = Icons.Rounded.ArrowUpward,
                tint = AetherGlowCyan,
                label = stringResource(R.string.traffic_upload),
                rate = stats.upRate,
                total = stats.upTotal,
                modifier = Modifier.weight(1f),
            )
        }
        RowLine()
        DetailRow(
            label = stringResource(if (connected) R.string.ip_server_label else R.string.ip_your_label),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shownIp != null) {
                    Text(text = NetProbe.flagEmoji(shownIp.countryCode), fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                }
                AnimatedContent(
                    targetState = ipText,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ip",
                ) { value ->
                    Text(
                        // An address is LTR technical text even in the Persian UI.
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = CardTextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        RowLine()
        DetailRow(label = stringResource(R.string.meta_pipeline)) {
            RouteValue(forms = routeForms)
        }
        RowLine()
        DetailRow(label = stringResource(R.string.meta_latency)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = pingTint(grade), shape = CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = latencyText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = CardTextPrimary,
                    maxLines = 1,
                )
                if (connected && lastMs >= 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(pingQualityRes(grade)),
                        fontSize = 12.sp,
                        color = CardTextMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One row of the details panel: a quiet label, and the value on the far side. */
@Composable
private fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 12.sp, color = CardTextMuted, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun RowLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(DIVIDER),
    )
}

/**
 * The route, e.g. `Aether(WireGuard) \u2192 Tor`. Of the alternative spellings the first
 * that fits the row at a comfortable size is used; the last one shrinks if even it
 * is too wide, so the row never wraps.
 */
@Composable
private fun RouteValue(forms: List<String>) {
    val style = MaterialTheme.typography.bodySmall.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        textDirection = TextDirection.Ltr,
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val chosen = remember(forms, maxPx, style) {
            if (forms.size < 2) {
                forms.first()
            } else {
                val comfortable = style.copy(fontSize = COMFORTABLE_VALUE_SP.sp)
                forms.firstOrNull { form ->
                    measurer.measure(
                        text = form,
                        style = comfortable,
                        maxLines = 1,
                        softWrap = false,
                    ).size.width <= maxPx
                } ?: forms.last()
            }
        }
        AnimatedContent(
            targetState = chosen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "route",
            modifier = Modifier.fillMaxWidth(),
        ) { shown ->
            FitLineText(
                text = shown,
                style = style,
                minLineHeight = 16.sp,
                color = CardTextPrimary,
                minFontSize = 9.sp,
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SpeedCell(
    icon: ImageVector,
    tint: Color,
    label: String,
    rate: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.background(color = tint.copy(alpha = 0.14f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .padding(5.dp)
                    .size(15.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 9.dp)) {
            Text(
                text = formatRate(rate),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = CardTextPrimary,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.traffic_total, formatBytes(total)),
                fontSize = 10.sp,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The ONE grade a latency reading has. Colour and label both come from here.
 *
 * ## ROOT CAUSE this fixes (reported directly, screenshot a2)
 *
 * There were TWO independent threshold tables and they disagreed:
 *
 *  * the colour came from a continuous `strength` ramp between [PING_BEST_MS]
 *    (80 ms) and [PING_WORST_MS] (400 ms), and went mint at `strength >= 0.66`,
 *    i.e. at any RTT up to **189 ms**;
 *  * the label came from a separate ladder that called anything over **160 ms**
 *    "Fair".
 *
 * So every reading between 161 and 189 ms rendered as green bars over the word
 * "Fair". 173 ms - the exact number in a2 - sits in that window: strength 0.709
 * (mint) against a label table that had already given up at 160. Neither number
 * was wrong; there were simply two of them.
 *
 * `strength` keeps its own smooth ramp for the bar HEIGHT, because a continuous
 * value is what an animation wants, but it no longer decides anything a word also
 * decides. One enum, one set of cut-offs, and the comment in [PingStrength] that
 * promised the three "can never disagree" is now actually true.
 */
private enum class PingGrade { OFFLINE, MEASURING, EXCELLENT, GOOD, FAIR, POOR }

private fun pingGrade(connected: Boolean, ms: Long): PingGrade = when {
    !connected -> PingGrade.OFFLINE
    ms < 0L -> PingGrade.MEASURING
    ms <= PING_EXCELLENT_MS -> PingGrade.EXCELLENT
    ms <= PING_GOOD_MS -> PingGrade.GOOD
    ms <= PING_FAIR_MS -> PingGrade.FAIR
    else -> PingGrade.POOR
}

private fun pingTint(grade: PingGrade): Color = when (grade) {
    PingGrade.EXCELLENT, PingGrade.GOOD -> AetherMint
    PingGrade.FAIR -> PING_FAIR
    PingGrade.POOR -> PING_POOR
    PingGrade.OFFLINE, PingGrade.MEASURING -> CardTextDim
}

private fun pingQualityRes(grade: PingGrade): Int = when (grade) {
    PingGrade.OFFLINE -> R.string.ping_quality_offline
    PingGrade.MEASURING -> R.string.ping_quality_measuring
    PingGrade.EXCELLENT -> R.string.ping_quality_excellent
    PingGrade.GOOD -> R.string.ping_quality_good
    PingGrade.FAIR -> R.string.ping_quality_fair
    PingGrade.POOR -> R.string.ping_quality_poor
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(DIVIDER),
    )
}

// ------------------------------------------------------------ traffic feed

/** Instantaneous rates + session totals, polled once per second. */
private data class TrafficStats(
    val downRate: Long = 0L,
    val upRate: Long = 0L,
    val downTotal: Long = 0L,
    val upTotal: Long = 0L,
)

/**
 * Sums BOTH possible traffic paths so the meter works in every mode:
 *  - hev-socks5-tunnel's direction-corrected counters (system-VPN mode; null in
 *    proxy mode, where there is no TUN),
 *  - ShareBridge: bytes relayed through the local SOCKS5/HTTP listeners (the
 *    only source in proxy mode, plus LAN clients in system-VPN mode).
 *
 * Rates come from deltas against a monotonic clock, so a wall-clock jump cannot
 * invent a spike. A negative delta (core restart during auto-reconnect, or a
 * fresh sharing session resetting the bridge counters) is clamped to zero and
 * the baseline rebases itself.
 */
@Composable
private fun rememberTrafficStats(connectedSince: Long?, connected: Boolean): TrafficStats {
    var stats by remember(connectedSince) { mutableStateOf(TrafficStats()) }

    LaunchedEffect(connectedSince, connected) {
        if (!connected) return@LaunchedEffect
        var lastDown = -1L
        var lastUp = -1L
        var lastAt = 0L
        while (true) {
            val hev = HevTunnel.traffic()
            val share = ShareBridge.traffic()
            if (hev != null || ShareBridge.active.value) {
                val down = (hev?.downloadBytes ?: 0L) + share.downloadBytes
                val up = (hev?.uploadBytes ?: 0L) + share.uploadBytes
                val at = SystemClock.elapsedRealtime()
                var downRate = stats.downRate
                var upRate = stats.upRate
                if (lastAt > 0L && at > lastAt) {
                    val dt = at - lastAt
                    downRate = ((down - lastDown).coerceAtLeast(0L) * 1000L) / dt
                    upRate = ((up - lastUp).coerceAtLeast(0L) * 1000L) / dt
                }
                stats = TrafficStats(downRate, upRate, down, up)
                lastDown = down
                lastUp = up
                lastAt = at
            }
            delay(1_000L)
        }
    }

    return stats
}

// ---------------------------------------------------------------- helpers

private fun formatBytes(v: Long): String {
    if (v < 1024L) return "$v B"
    val kb = v / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatRate(v: Long): String = formatBytes(v) + "/s"

private val SUB_BORDER = Color(0x1F3EDBB0)
private val DIVIDER = Color(0x1FFFFFFF)
private val IDLE_ACCENT = Color(0xFF4C8DFF)
private val ERROR_ACCENT = Color(0xFFFF5C7A)
private const val LATENCY_REFRESH_MS = 4_000L

/** 1.5.0: the smallest size a chain label is shown at before a shorter form of it is used. */
private const val COMFORTABLE_VALUE_SP = 11

// ---- ping meter tuning ----
// 1.2.8-r4: the grade cut-offs and the bar-height ramp are declared together so
// the next reader can see they are meant to line up. EXCELLENT is also the top of
// the height ramp, and FAIR is its bottom, so the mint/amber boundary in the
// colour and the Good/Fair boundary in the label are the same number by
// construction rather than by coincidence.
private const val PING_EXCELLENT_MS = 80L
private const val PING_GOOD_MS = 190L
private const val PING_FAIR_MS = 320L
private val PING_FAIR = Color(0xFFFFB43C)
private val PING_POOR = Color(0xFFFF5C7A)

private val DETAILS_SHAPE = RoundedCornerShape(22.dp)
