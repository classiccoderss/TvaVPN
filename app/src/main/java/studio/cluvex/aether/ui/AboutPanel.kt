package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.FilledTonalButton
import android.net.Uri
import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.ChangelogFetcher
import studio.cluvex.aether.data.LanguagePrefs
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R

private const val URL_OWN_GITHUB = "https://github.com/tohidbareza"
private const val TELEGRAM_APP_URI = "tg://resolve?domain=ClassicDevs"
private const val TELEGRAM_WEB_URL = "https://t.me/ClassicDevs"

/**
 * Collapsible "About" card: this edition's GitHub link and a button that opens its
 * Telegram channel.
 */
@Composable
fun AboutPanel(modifier: Modifier = Modifier, startExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "aboutArrow")
    val context = LocalContext.current
    var showChangelog by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.1.0"
    }

    if (showChangelog) ChangelogDialog { showChangelog = false }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.about_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Engine (core) version — same idea as the Windows edition's
                    // About page, which shows app version AND core version so a
                    // user can verify the bundled engine is current.
                    // BuildConfig.CORE_VERSION is stamped at build time from
                    // native/aether/CORE_VERSION, i.e. from whatever
                    // scripts/sync-core.sh actually vendored for THIS build.
                    Text(
                        text = stringResource(R.string.about_core_version, BuildConfig.CORE_VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 1.2.8-r5 BUILD IDENTITY, visible without opening the log.
                    //
                    // versionName is "1.2.8" for r2, r3, r4 and r5 alike and the
                    // core version is 1.8.0 in all of them, so neither row above
                    // can tell two revisions apart. That is not a cosmetic gap:
                    // the r4 field test was carried out on the r3 build and
                    // nobody could see it. This row is the one that answers
                    // "which build am I actually running", and it turns red when
                    // the engine inside the APK disagrees with the APK.
                    Text(
                        text = stringResource(
                            R.string.about_patch_level,
                            studio.cluvex.aether.core.BuildProvenance.summary(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (studio.cluvex.aether.core.BuildProvenance.enginePatchLevel != null &&
                            !studio.cluvex.aether.core.BuildProvenance.consistent
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                    ChangelogRow { showChangelog = true }
                    Spacer(Modifier.height(8.dp))

                    // ---- This edition (tohidbareza) ----
                    SectionHeader(
                        title = stringResource(R.string.about_own_title),
                        note = stringResource(R.string.about_own_note),
                    )
                    LinkRow(R.drawable.ic_github, "github.com/tohidbareza", URL_OWN_GITHUB)
                    Spacer(Modifier.height(10.dp))
                    TelegramButton()
                }
            }
        }
    }
}

/** About > Changelog: opens the list of changed files (paths only). */
@Composable
private fun ChangelogRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.about_changelog),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.about_changelog_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Changed files per version, read from CHANGELOG.md on GitHub. Paths only, no release notes. */
@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // null = loading, Failed = fetch failed.
    var entries by remember { mutableStateOf<List<ChangelogFetcher.Entry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val persian = LanguagePrefs.isPersian(context)
        val all = withContext(Dispatchers.IO) { ChangelogFetcher.fetch(persian) }
        if (all == null) failed = true else entries = all.filter { it.files.isNotEmpty() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.changelog_dialog_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val list = entries
                when {
                    failed -> Text(stringResource(R.string.update_changes_unavailable))
                    list == null -> Text(stringResource(R.string.update_changes_loading))
                    list.isEmpty() -> Text(stringResource(R.string.changelog_no_files))
                    else -> list.forEach { e ->
                        Text(
                            e.heading,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // File paths are always left-to-right, even in the Persian UI.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                e.files.forEach { path ->
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.changelog_close)) }
        },
        dismissButton = {
            TextButton(onClick = { runCatching { uriHandler.openUri(ChangelogFetcher.CHANGELOG_PAGE_URL) } }) {
                Text(stringResource(R.string.update_changes_full))
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, note: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

/** A button that opens the Telegram channel straight in the Telegram app. */
@Composable
private fun TelegramButton() {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = { openTelegramChannel(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_telegram),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.about_telegram_button))
    }
}

/**
 * Opens the channel in the Telegram app through its own `tg://` link. If no app
 * handles that (Telegram is not installed), the same channel opens on the web.
 */
private fun openTelegramChannel(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_APP_URI))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_WEB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun LinkRow(iconRes: Int, label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FeatureList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
