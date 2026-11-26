package studio.cluvex.aether.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLog

/**
 * App-wide event log, on its own page - deliberately separate from
 * [DiagnosticsLogPage][studio.cluvex.aether.ui.components.DiagnosticsLogPage],
 * which is the tunnel/connection log. This page shows [AppLog]: step-by-step
 * events from UI flows across the whole app (not the tunnel), starting with
 * (but not limited to) external import on the backup/restore page.
 *
 * Reached from a top-level row on the Settings home page ("App logs"), same
 * as Diagnostics - not nested under any one feature's settings, since what it
 * shows is not limited to any one feature either.
 *
 * PAGE_SIZE: [AppLog] can hold up to several hundred lines, and rendering all
 * of them into one plain Column (the old approach) meant building that many
 * Text composables up front even when the person only ever looks at the last
 * few. Only the newest [PAGE_SIZE] lines render at first; a "show more" row
 * reveals the next-oldest batch, same size, until none are left - the same
 * one-tap-for-more shape as [DiagnosticsLogPage]'s own
 * preview-then-full-log, just inside a single page instead of across two.
 */
private const val PAGE_SIZE = 80

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lines by AppLog.lines.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // How many of the newest lines are showing, oldest-first within that
    // window so it still reads top-to-bottom like a normal log. Grows by
    // PAGE_SIZE each time "show more" is tapped; reset if the log is cleared
    // out from under this page (lines.size can only shrink via clear(), never
    // shrink otherwise, so that is the one case visibleCount needs to follow).
    var visibleCount by remember { mutableIntStateOf(minOf(PAGE_SIZE, lines.size)) }
    LaunchedEffect(lines.size) {
        if (lines.size < visibleCount) visibleCount = lines.size
    }
    val hiddenCount = (lines.size - visibleCount).coerceAtLeast(0)
    val visible = remember(lines, visibleCount) { lines.takeLast(visibleCount) }

    // Follow the tail as new lines arrive, same as DiagnosticsLogPage, but
    // only while already caught up to the newest line - otherwise a line
    // landing while the person has scrolled up to read "show more" history
    // would yank them back down to the bottom mid-read.
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && hiddenCount == 0) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFF0C0F16),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.applog_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(AppLog.exportText()))
                        Toast.makeText(context, context.getString(R.string.applog_copied), Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.applog_copy),
                        )
                    }
                    IconButton(onClick = {
                        AppLog.clear()
                        visibleCount = 0
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.diag_clear),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C0F16)),
            )
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .padding(insets)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.applog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8A93A6),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (hiddenCount > 0) {
                            item {
                                TextButton(onClick = {
                                    val target = visibleCount
                                    visibleCount = minOf(visibleCount + PAGE_SIZE, lines.size)
                                    // Keep the list from jumping to the very
                                    // top of the newly-revealed batch - land
                                    // the user right where the old content
                                    // used to start, so reading continues
                                    // from where "show more" was tapped.
                                    scope.launch {
                                        listState.scrollToItem((visibleCount - target).coerceAtLeast(0))
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                    Text(String.format(stringResource(R.string.applog_show_more), hiddenCount))
                                }
                            }
                        }
                        items(visible) { line ->
                            Text(
                                text = line.format(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFFB8C2D6),
                            )
                        }
                    }
                }
            }
        }
    }
}

