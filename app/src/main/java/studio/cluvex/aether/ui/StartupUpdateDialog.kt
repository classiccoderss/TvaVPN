package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.UpdateChecker
import studio.cluvex.aether.ui.theme.AetherMint
import studio.cluvex.aether.ui.theme.Navy850

/**
 * Asks about an available update once per app launch.
 *
 * Checks GitHub in the background the first time this composable enters the
 * composition (gated by [hasCheckedThisProcess], so leaving and coming back to a
 * still-running process does not re-check or re-prompt). Nothing is shown while
 * the app is up to date or the check fails — this is a quiet background check,
 * not something worth interrupting the user over when there is nothing to offer.
 *
 * Exactly two buttons once a release IS found, per the feature request: "Update"
 * starts the same download -> verify -> install pipeline [UpdatePage] uses, but
 * — unlike that manual screen — drives it straight through to the system
 * installer itself once verified, with no extra tap. "Not now" just dismisses;
 * like [LoginCodeDialog], tapping outside does nothing; `onDismissRequest` is a
 * no-op so the user explicitly picks one of the two buttons.
 *
 * The one safety exception that does NOT auto-install: a downloaded file whose
 * hash does not match the one the release notes published (see
 * [UpdateChecker]'s own doc on what that check can and cannot prove). That case
 * stops and asks for one more explicit confirmation instead of silently
 * installing something that already failed a check.
 */
@Composable
fun StartupUpdateDialog(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<StartupUpdateState>(StartupUpdateState.Hidden) }

    LaunchedEffect(Unit) {
        if (hasCheckedThisProcess) return@LaunchedEffect
        hasCheckedThisProcess = true
        val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
        if (result is UpdateChecker.CheckResult.Available) {
            state = StartupUpdateState.Available(result.release)
        }
    }

    val current = state
    if (current is StartupUpdateState.Hidden) return

    AlertDialog(
        modifier = modifier,
        // Deliberate: the two buttons are the only way out, same as LoginCodeDialog.
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = AetherMint) },
        title = { Text(stringResource(R.string.update_startup_title)) },
        text = { StartupUpdateBody(current) },
        confirmButton = {
            when (current) {
                is StartupUpdateState.Available -> TextButton(
                    onClick = { startDownload(context, scope, current.release) { state = it } },
                ) { Text(stringResource(R.string.update_startup_button)) }

                is StartupUpdateState.Error -> TextButton(
                    onClick = { startDownload(context, scope, current.release) { state = it } },
                ) { Text(stringResource(R.string.update_startup_retry)) }

                is StartupUpdateState.HashMismatch -> TextButton(
                    onClick = { installNow(context, current.file); state = StartupUpdateState.Hidden },
                ) { Text(stringResource(R.string.update_startup_install_anyway)) }

                else -> {}
            }
        },
        dismissButton = {
            // Downloading has no dismiss button — see StartupUpdateBody's comment.
            if (current !is StartupUpdateState.Downloading) {
                TextButton(onClick = { state = StartupUpdateState.Hidden }) {
                    Text(stringResource(R.string.update_startup_not_now))
                }
            }
        },
        containerColor = Navy850,
    )
}

@Composable
private fun StartupUpdateBody(state: StartupUpdateState) {
    Column {
        when (state) {
            is StartupUpdateState.Available ->
                Text(
                    stringResource(R.string.update_startup_body, state.release.tag),
                    style = MaterialTheme.typography.bodyMedium,
                )

            is StartupUpdateState.Downloading -> {
                Text(
                    stringResource(R.string.update_startup_downloading, state.progress),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            StartupUpdateState.Installing -> {
                Text(
                    stringResource(R.string.update_startup_installing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator()
            }

            is StartupUpdateState.HashMismatch ->
                Text(
                    stringResource(R.string.update_startup_hash_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            is StartupUpdateState.Error ->
                Text(
                    stringResource(R.string.update_startup_error, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            StartupUpdateState.Hidden -> {}
        }
    }
}

private sealed interface StartupUpdateState {
    object Hidden : StartupUpdateState
    data class Available(val release: UpdateChecker.ReleaseInfo) : StartupUpdateState
    data class Downloading(val release: UpdateChecker.ReleaseInfo, val progress: Int) : StartupUpdateState
    object Installing : StartupUpdateState
    data class HashMismatch(val file: File, val release: UpdateChecker.ReleaseInfo) : StartupUpdateState
    data class Error(val message: String, val release: UpdateChecker.ReleaseInfo) : StartupUpdateState
}

/** Only checked once per process — see the class doc. */
private var hasCheckedThisProcess = false

/**
 * Drives download -> verify -> install for [release], reporting every step back
 * through [onState]. Mirrors [studio.cluvex.aether.ui.settings.UpdatePage]'s
 * pipeline but, once verified, calls [UpdateChecker.install] itself instead of
 * waiting for a second tap — the one exception being a hash mismatch, which
 * stops for an explicit confirmation (see the class doc on [StartupUpdateDialog]).
 */
private fun startDownload(
    context: android.content.Context,
    scope: CoroutineScope,
    release: UpdateChecker.ReleaseInfo,
    onState: (StartupUpdateState) -> Unit,
) {
    onState(StartupUpdateState.Downloading(release, 0))
    scope.launch {
        val downloadResult = withContext(Dispatchers.IO) {
            UpdateChecker.download(context, release) { pct ->
                onState(StartupUpdateState.Downloading(release, pct))
            }
        }
        when (downloadResult) {
            is UpdateChecker.DownloadResult.Error ->
                onState(StartupUpdateState.Error(downloadResult.message, release))

            is UpdateChecker.DownloadResult.Ok -> {
                val file = downloadResult.file
                when (val verify = withContext(Dispatchers.IO) {
                    UpdateChecker.verifyBeforeInstall(context, file)
                }) {
                    is UpdateChecker.VerifyResult.Rejected ->
                        onState(StartupUpdateState.Error(verify.reason, release))

                    UpdateChecker.VerifyResult.Ok -> {
                        val hashMatches = release.publishedSha256?.let { it == downloadResult.sha256 }
                        if (hashMatches == false) {
                            onState(StartupUpdateState.HashMismatch(file, release))
                        } else {
                            onState(StartupUpdateState.Installing)
                            installNow(context, file)
                            onState(StartupUpdateState.Hidden)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hands the verified file to the system installer. If "install unknown apps"
 * is not yet allowed for tvaVPN, this opens that one system settings screen
 * instead — [UpdateChecker.install] can't skip it, by platform design — and
 * the user can re-open the app afterwards to pick the update up again.
 */
private fun installNow(context: android.content.Context, file: File) {
    if (!UpdateChecker.canInstallUnknownApps(context)) {
        UpdateChecker.openUnknownSourcesSettings(context)
        return
    }
    UpdateChecker.install(context, file)
}
