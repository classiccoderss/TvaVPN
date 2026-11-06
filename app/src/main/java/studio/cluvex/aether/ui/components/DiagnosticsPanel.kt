package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.collectAsState
import android.widget.Toast
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.ComponentCheck
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.LogLine

/**
 * A collapsible “pro” panel that shows the live status of every part of the
 * tunnel plus a scrollable, copyable technical log. This is what tells the user
 * exactly WHY no site loads even though the button says connected.
 */
@Composable
fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    // 1.3.2: the log console used to be drawn right here, under the checks and
    // the three action buttons - "copy" worked, but on a long check list it sat
    // below a screenful of other things, which is exactly the clutter this
    // callback fixes. Logs now open on their own page (see DiagnosticsLogPage);
    // the default is a no-op so a caller that has nowhere to navigate still
    // compiles.
    onOpenLogs: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val checks by DiagnosticsLog.checks.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val overall = overallState(checks)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header (tap to expand).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = overall.color, size = 12.dp)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.diag_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(overall.captionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(16.dp))

                    checks.forEach { CheckRow(it) }

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { Diagnostics.run() } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.diag_run))
                        }
                        TextButton(
                            onClick = {
                                // AUDIT F-4: the verbatim log carries exit IP,
                                // endpoints and the WARP enrolment handle. Flagged
                                // sensitive so it does not surface in the clipboard
                                // preview or history; the redacted export below
                                // stays an ordinary copy, since sharing it is the
                                // whole point of that button.
                                copySensitive(context, DiagnosticsLog.exportText(), "aether-log")
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.diag_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.diag_copy))
                        }
                        TextButton(onClick = { DiagnosticsLog.clear() }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.diag_clear))
                        }
                    }

                    // SAFE-TO-SHARE EXPORT (audit 1.2.9-r3, §6).
                    //
                    // "Copy logs" above hands over the log verbatim, which is right
                    // for a private bug report and wrong for the place these logs
                    // actually end up: a public issue, a Telegram group, a forum
                    // post. Until now the safe option did not exist, so every
                    // pasted log carried the exit IP, the WARP enrolment handle and
                    // the endpoints the scan settled on. This runs the same
                    // redaction the AI advisor uses, with no line cap.
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(DiagnosticsLog.exportRedactedText()))
                            Toast.makeText(
                                context,
                                context.getString(R.string.diag_copied_redacted),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.diag_copy_redacted))
                    }

                    if (DiagnosticsLog.persistedEncrypted) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.diag_log_sealed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 1.3.2: a one-line summary instead of the scrollable console
                    // that used to sit here. The full, copyable log lives on its
                    // own page now (DiagnosticsLogPage) - nothing else on that
                    // page to scroll past before you reach the copy button.
                    val lineCount = DiagnosticsLog.lines.collectAsState().value.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (lineCount == 0) {
                                androidx.compose.ui.res.stringResource(R.string.diag_empty_logs)
                            } else {
                                androidx.compose.ui.res.pluralStringResource(
                                    R.plurals.diag_log_lines_count,
                                    lineCount,
                                    lineCount,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenLogs) {
                            Text(androidx.compose.ui.res.stringResource(R.string.diag_view_logs))
                        }
                    }
                }
            }
        }
    }
}

private data class Overall(val color: Color, val captionRes: Int)

private fun overallState(checks: List<ComponentCheck>): Overall = when {
    checks.isEmpty() -> Overall(Color(0xFF8A93A6), R.string.diag_idle)
    checks.any { it.state == CheckState.FAIL } -> Overall(Color(0xFFFF5C7A), R.string.diag_problem)
    checks.all { it.state == CheckState.PASS } -> Overall(Color(0xFF32E0C4), R.string.diag_all_ok)
    else -> Overall(Color(0xFFF5C451), R.string.diag_idle)
}

@Composable
private fun CheckRow(check: ComponentCheck) {
    val color = when (check.state) {
        CheckState.PASS -> Color(0xFF32E0C4)
        CheckState.FAIL -> Color(0xFFFF5C7A)
        CheckState.RUNNING -> Color(0xFFF5C451)
        CheckState.PENDING -> Color(0xFF8A93A6)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = color, size = 10.dp)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = check.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (check.detail.isNotBlank()) {
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = check.state.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * The log console, full screen and on its own: nothing above it but a title
 * and the three actions that matter (copy, copy-redacted, clear), so they are
 * never more than one tap away no matter how long the log gets.
 *
 * 1.3.2: this used to be a 120-260dp scrollable box wedged under the checks
 * and buttons inside [DiagnosticsPanel] - the right content, the wrong amount
 * of room, and the copy buttons it needed were a full screen-height scroll
 * away on a long check list. [DiagnosticsPanel] now shows a line count and a
 * "view full log" button instead; this is where that button leads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsLogPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // AUDIT F-3: the open log is the most revealing surface in the app (exit
    // IP, bridge, endpoints, enrolment handle), so the window is blocked from
    // screenshots, screen recording and the recents thumbnail while it is
    // open. This page is only composed while it is on screen, so the flag
    // lifts the moment the user backs out of it.
    SecureSurface()

    val lines: List<LogLine> = DiagnosticsLog.lines.collectAsState().value
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scroll = rememberScrollState()

    // Follow the tail as new lines arrive, the way a terminal does, so the
    // newest line (usually the one the user came here to copy) is on screen
    // without them hunting for it.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFF0C0F16),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.diag_logs_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.a11y_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Same sensitive-copy path the old inline console used:
                        // the verbatim log carries exit IP, endpoints and the
                        // WARP enrolment handle, so it is flagged sensitive and
                        // kept out of the clipboard preview and history.
                        copySensitive(context, DiagnosticsLog.exportText(), "aether-log")
                        Toast.makeText(
                            context,
                            context.getString(R.string.diag_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.diag_copy),
                        )
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(DiagnosticsLog.exportRedactedText()))
                        Toast.makeText(
                            context,
                            context.getString(R.string.diag_copied_redacted),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.diag_copy_redacted),
                        )
                    }
                    IconButton(onClick = { DiagnosticsLog.clear() }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.diag_clear),
                        )
                    }
                },
            )
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .padding(insets)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.diag_empty_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8A93A6),
                )
            } else {
                // Selectable so a user who only wants one line (an error, an
                // IP to double-check) can long-press and grab just that,
                // instead of the "copy logs" button's all-or-nothing export.
                SelectionContainer {
                    Column {
                        lines.forEach { line ->
                            Text(
                                text = line.format(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = logColor(line.level),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun logColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Color(0xFFFF7A90)
    LogLevel.WARN -> Color(0xFFF5C451)
    LogLevel.INFO -> Color(0xFFB8C2D6)
    LogLevel.DEBUG -> Color(0xFF7C8698)
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape),
    )
}
