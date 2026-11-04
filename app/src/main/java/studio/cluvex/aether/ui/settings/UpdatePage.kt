package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ChangelogFetcher
import studio.cluvex.aether.core.UpdateChecker
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.ui.theme.AetherBlue
import studio.cluvex.aether.ui.theme.AetherMint

/**
 * "Check for updates": fetches the latest GitHub Release, downloads it into the
 * app's own cache (never through a browser) and, once [UpdateChecker] has
 * verified it, hands it to the system installer. See [UpdateChecker]'s doc for
 * exactly what is and is not guaranteed about a downloaded build.
 */
@Composable
internal fun UpdatePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    // null = not requested / loading; empty = loaded but nothing newer / failed.
    var changes by remember { mutableStateOf<ChangesUi>(ChangesUi.Hidden) }

    fun loadChanges() {
        changes = ChangesUi.Loading
        scope.launch {
            val persian = LanguagePrefs.isPersian(context)
            val all = withContext(Dispatchers.IO) { ChangelogFetcher.fetch(persian) }
            changes = if (all == null) {
                ChangesUi.Failed
            } else {
                ChangesUi.Loaded(ChangelogFetcher.newerThan(all, BuildConfig.VERSION_NAME))
            }
        }
    }

    fun startCheck() {
        state = UpdateUiState.Checking
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }) {
                is UpdateChecker.CheckResult.UpToDate -> {
                    changes = ChangesUi.Hidden
                    state = UpdateUiState.UpToDate(result.currentVersion)
                }
                is UpdateChecker.CheckResult.Available -> {
                    state = UpdateUiState.Available(result.release)
                    loadChanges()
                }
                is UpdateChecker.CheckResult.Error -> {
                    changes = ChangesUi.Hidden
                    state = UpdateUiState.Error(result.message)
                }
            }
        }
    }

    fun startDownload(release: UpdateChecker.ReleaseInfo) {
        state = UpdateUiState.Downloading(release, 0)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.download(context, release) { pct ->
                    // Progress callback runs on the IO thread; state is only read
                    // back on the main thread by Compose, so a plain write is fine.
                    state = UpdateUiState.Downloading(release, pct)
                }
            }
            when (result) {
                is UpdateChecker.DownloadResult.Error -> state = UpdateUiState.Error(result.message)
                is UpdateChecker.DownloadResult.Ok -> {
                    when (val verify = withContext(Dispatchers.IO) {
                        UpdateChecker.verifyBeforeInstall(context, result.file)
                    }) {
                        is UpdateChecker.VerifyResult.Rejected ->
                            state = UpdateUiState.Error(
                                context.getString(R.string.update_err_rejected, verify.reason),
                            )
                        UpdateChecker.VerifyResult.Ok -> {
                            val hashMatches = release.publishedSha256?.let { it == result.sha256 }
                            state = UpdateUiState.ReadyToInstall(result.file, release, hashMatches)
                        }
                    }
                }
            }
        }
    }

    fun install(file: File) {
        if (!UpdateChecker.canInstallUnknownApps(context)) {
            UpdateChecker.openUnknownSourcesSettings(context)
            return
        }
        UpdateChecker.install(context, file)
    }

    SettingsScaffold(
        stringResource(R.string.update_title),
        onBack,
        modifier,
        icon = Icons.Rounded.SystemUpdate,
        accent = AetherMint,
        subtitle = stringResource(R.string.update_nav_desc),
    ) {
        settingsSection {
            SettingsGroup {
                SettingsBlock {
                    Text(
                        stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))

                    when (val s = state) {
                        UpdateUiState.Idle -> Button(onClick = ::startCheck) {
                            Text(stringResource(R.string.update_check_now))
                        }

                        UpdateUiState.Checking -> StatusRow(stringResource(R.string.update_checking))

                        is UpdateUiState.UpToDate -> {
                            StatusRow(stringResource(R.string.update_up_to_date), Icons.Rounded.CheckCircle, AetherMint)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = ::startCheck) { Text(stringResource(R.string.update_check_again)) }
                        }

                        is UpdateUiState.Available -> {
                            Text(
                                stringResource(R.string.update_available, s.release.tag),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AetherBlue,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { startDownload(s.release) }) {
                                    Text(stringResource(R.string.update_download_install))
                                }
                                OutlinedButton(onClick = { uriHandler.openUri(s.release.htmlUrl) }) {
                                    Text(stringResource(R.string.update_view_release))
                                }
                            }
                        }

                        is UpdateUiState.Downloading -> {
                            Text(
                                stringResource(R.string.update_downloading, s.progress),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { s.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is UpdateUiState.ReadyToInstall -> {
                            StatusRow(stringResource(R.string.update_ready), Icons.Rounded.CheckCircle, AetherMint)
                            if (s.hashMatches == false) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.update_hash_mismatch),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { install(s.file) }) {
                                Text(stringResource(R.string.update_install_now))
                            }
                        }

                        is UpdateUiState.Error -> {
                            StatusRow(
                                stringResource(R.string.update_error, s.message),
                                Icons.Rounded.Error,
                                MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = ::startCheck) { Text(stringResource(R.string.update_check_again)) }
                        }
                    }
                }
            }
            if (state is UpdateUiState.Available ||
                state is UpdateUiState.Downloading ||
                state is UpdateUiState.ReadyToInstall
            ) {
                SettingsGroup {
                    SettingsBlock {
                        ChangesBlock(changes) { uriHandler.openUri(ChangelogFetcher.CHANGELOG_PAGE_URL) }
                    }
                }
            }
            GroupFooter(stringResource(R.string.update_footer))
        }
    }
}

private sealed interface ChangesUi {
    object Hidden : ChangesUi
    object Loading : ChangesUi
    object Failed : ChangesUi
    data class Loaded(val entries: List<ChangelogFetcher.Entry>) : ChangesUi
}

/** The changelog sections newer than the installed version, read from CHANGELOG.md on GitHub. */
@Composable
private fun ChangesBlock(changes: ChangesUi, onOpenFull: () -> Unit) {
    Text(
        stringResource(R.string.update_changes_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    when (changes) {
        ChangesUi.Hidden, ChangesUi.Loading ->
            Text(stringResource(R.string.update_changes_loading), style = MaterialTheme.typography.bodySmall)
        ChangesUi.Failed ->
            Text(
                stringResource(R.string.update_changes_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        is ChangesUi.Loaded -> {
            if (changes.entries.isEmpty()) {
                Text(stringResource(R.string.update_changes_unavailable), style = MaterialTheme.typography.bodySmall)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    changes.entries.forEach { e ->
                        Text(e.heading, style = MaterialTheme.typography.titleSmall, color = AetherBlue)
                        Text(ChangelogFetcher.plain(e.notes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onOpenFull) { Text(stringResource(R.string.update_changes_full)) }
}

private sealed interface UpdateUiState {
    object Idle : UpdateUiState
    object Checking : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class Available(val release: UpdateChecker.ReleaseInfo) : UpdateUiState
    data class Downloading(val release: UpdateChecker.ReleaseInfo, val progress: Int) : UpdateUiState
    data class ReadyToInstall(
        val file: File,
        val release: UpdateChecker.ReleaseInfo,
        /** null = the release notes published no hash to compare against. */
        val hashMatches: Boolean?,
    ) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@Composable
private fun StatusRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
