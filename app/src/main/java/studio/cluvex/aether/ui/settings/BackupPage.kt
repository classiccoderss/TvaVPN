package studio.cluvex.aether.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLog
import studio.cluvex.aether.custom.ImportIntentBus
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile

/** Tag this page's events are recorded under in [AppLog] - see [AppLogPage]. */
private const val LOG_TAG = "backup-import"

/**
 * Export and import of the tunnel settings as one small JSON file.
 *
 * ```json
 * { "format": "aether-settings", "version": 1,
 *   "settings": { "protocol": "MASQUE", "mtu": 1280, "bypassIran": true, ... } }
 * ```
 *
 * ## Why it copies the stored preferences instead of listing fields
 *
 * [ProfileStore] already knows every setting and how to turn the stored values
 * back into a [ConnectionProfile] with defaults and clamping. A second list of
 * fields here would be a second place to forget a new setting, so the export
 * copies whatever the store holds and the import runs the file through
 * [ProfileStore.fromPrefs], the same validation as a normal load. A setting added
 * later is exported and imported without touching this file.
 *
 * ## What is deliberately not in the file
 *
 * The Zero Trust sign-in (team, auth mode, client id, e-mail) and the upstream
 * proxy are identity and credentials, not tuning. They are never written, an
 * imported file is ignored for those keys, and an import keeps the values the
 * phone already has. The Zero Trust secrets, custom configs and the AI key live
 * in other stores and are never read here.
 *
 * ## How the screen works
 *
 * It uses the system file picker (`CreateDocument` / `OpenDocument`), so no
 * storage permission is needed. A file can still change DNS servers, endpoints and
 * routes, so the import asks before it replaces anything. It replaces the settings
 * through [onProfileChange], the path every other settings row uses, and is
 * disabled while connected like the rest of the settings.
 */
@Composable
internal fun BackupPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProfileStore(context.applicationContext) }
    // A parsed file waiting for the user's confirmation.
    var pending by remember { mutableStateOf<ConnectionProfile?>(null) }

    // The outcome of the last export / import. Shown on the page AS A BANNER
    // (not a Toast - a Toast times out on its own and is too easy to miss,
    // especially right after an external-import intent switches screens on
    // its own) and spoken for a screen-reader user. It stays until the user
    // dismisses it or a new outcome replaces it - it does NOT disappear by
    // itself. Every outcome is also written to [AppLog] (separate from the
    // tunnel/connection log), so it is still visible on the App logs page
    // even if it was missed live.
    var status by remember { mutableStateOf<Int?>(null) }
    val view = LocalView.current
    fun report(@StringRes message: Int) {
        status = message
        view.announceForAccessibility(context.getString(message))
        AppLog.event(LOG_TAG, context.getString(message))
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            AppLog.event(LOG_TAG, "export: user cancelled the save dialog")
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { writeExport(context, store, profile, uri) }
                }
                AppLog.event(LOG_TAG, "export result: ${if (saved.isSuccess) "ok" else saved.exceptionOrNull()?.javaClass?.simpleName}")
                report(if (saved.isSuccess) R.string.backup_exported else R.string.backup_export_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            AppLog.event(LOG_TAG, "manual import: user cancelled the file picker")
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) { readImport(context, store, uri) }
                AppLog.event(LOG_TAG, "manual import result: ${result::class.simpleName}")
                when (result) {
                    is ImportResult.Ok -> pending = keepPrivate(result.profile, profile)
                    ImportResult.Newer -> report(R.string.backup_err_newer)
                    ImportResult.Invalid -> report(R.string.backup_err_invalid)
                }
            }
        }
    }

    // EXTERNAL IMPORT (tvaVPN): the user tapped or shared in their own exported
    // settings file from outside the app. MainActivity already peeked it (see
    // isSettingsBackupFile below) and routed it here instead of to
    // CustomConfigsPage, so it is run through the exact same readImport() the
    // in-app "Import" button above uses. Any other payload type (a config link
    // or a custom-config file) is not this page's job and is left alone for
    // CustomConfigsPage to pick up.
    //
    // BUG FIX (tvaVPN): this used to be `val pendingExternalImport by
    // ImportIntentBus.pending.collectAsState()` followed by
    // `LaunchedEffect(pendingExternalImport) { ... ImportIntentBus.consume() ... }`.
    // That keyed the effect on the very StateFlow value the effect itself
    // resets to null via consume(). The moment consume() ran, the collected
    // state flipped to null, Compose saw the LaunchedEffect's key change, and
    // CANCELLED the still-running coroutine before it reached readImport() -
    // every single time, for every external-intent import, with the log
    // always stopping right after "handling settings file, editable=...".
    // Importing from the in-app "Import" button (importLauncher above) never
    // goes through ImportIntentBus, so it was never affected.
    //
    // Fix: collect the flow inside a LaunchedEffect keyed on Unit (so the
    // effect itself is never restarted) and consume one emission at a time.
    // consume()'s null value then just arrives as the next element of the
    // same collect, instead of cancelling the coroutine that is mid-import.
    val currentEditable by rememberUpdatedState(editable)
    LaunchedEffect(Unit) {
        ImportIntentBus.pending.collect { raw ->
            // Logged unconditionally (not just when a SettingsFileUri shows up) so
            // the app log proves whether BackupPage ever saw the flow emit at all -
            // a FileUri/Text payload here means MainActivity classified the file as
            // a custom config, not a settings backup, and this page was never going
            // to handle it.
            if (raw != null) {
                AppLog.event(LOG_TAG, "ImportIntentBus emitted: ${raw::class.simpleName}")
            }
            val payload = raw as? ImportIntentBus.Payload.SettingsFileUri ?: return@collect
            ImportIntentBus.consume()
            AppLog.event(LOG_TAG, "handling settings file, editable=$currentEditable")
            if (!currentEditable) {
                AppLog.event(LOG_TAG, "blocked: page is locked (tunnel is connected) - import not attempted")
                report(R.string.backup_locked)
                return@collect
            }
            val result = withContext(Dispatchers.IO) { readImport(context, store, payload.uri) }
            AppLog.event(LOG_TAG, "file read result: ${result::class.simpleName}")
            when (result) {
                is ImportResult.Ok -> {
                    pending = keepPrivate(result.profile, profile)
                    AppLog.event(LOG_TAG, "parsed OK, confirm dialog is now showing")
                }
                ImportResult.Newer -> report(R.string.backup_err_newer)
                ImportResult.Invalid -> report(R.string.backup_err_invalid)
            }
        }
    }

    SettingsScaffold(
        stringResource(R.string.backup_title), onBack, modifier,
        icon = androidx.compose.material.icons.Icons.Rounded.Save,
        accent = studio.cluvex.aether.ui.theme.AetherMint,
        subtitle = stringResource(R.string.backup_nav_desc),
    ) {
        // First on the page, so it is the first thing a screen reader reaches.
        status?.let { message ->
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(text = stringResource(message), icon = Icons.Rounded.Info)
                }
            }
        }
        if (!editable) {
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(
                        text = stringResource(R.string.backup_locked),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }
        settingsSection {
            SettingsGroup {
                ButtonSemantics(enabled = true) {
                    SettingsActionRow(
                        title = stringResource(R.string.backup_export),
                        summary = stringResource(R.string.backup_export_desc),
                        icon = Icons.Rounded.Save,
                        onClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
                    )
                }
                RowDivider()
                ButtonSemantics(enabled = editable) {
                    SettingsActionRow(
                        title = stringResource(R.string.backup_import),
                        summary = stringResource(R.string.backup_import_desc),
                        icon = Icons.Rounded.FolderOpen,
                        enabled = editable,
                        onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
                    )
                }
            }
            GroupFooter(stringResource(R.string.backup_footer))
        }
        // The event log used to have its own row here ("App log"), reachable
        // only from this page - but it records every UI flow in the app, not
        // just import/export (see AppLog's header comment), so burying its
        // only entry point inside Backup made it look like an import/export
        // debugging tool instead of what it actually is. It is now a
        // top-level row on the Settings home page (SettingsHomePage, "App
        // activity log" / R.string.applog_nav_title) next to Diagnostics,
        // reached the same way for every flow that logs to it, backup
        // included.
    }

    pending?.let { incoming ->
        AlertDialog(
            onDismissRequest = {
                pending = null
                AppLog.event(LOG_TAG, "confirm dialog dismissed (outside tap / back)")
            },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onProfileChange(incoming)
                    AppLog.event(LOG_TAG, "confirm dialog: user accepted - settings applied")
                    report(R.string.backup_imported)
                }) { Text(stringResource(R.string.backup_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    AppLog.event(LOG_TAG, "confirm dialog: user cancelled")
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/**
 * Gives a settings row the semantics a screen reader needs for a button.
 *
 * The shared action row is a plain `clickable` with no role, so TalkBack reads its
 * text and "double tap to activate" without saying it is a button, and a row that
 * is switched off is not clickable at all, so it is read as ordinary text with no
 * hint that it is unavailable. The wrapper merges the row into one focus stop,
 * marks it as a button, and marks it disabled when it is.
 */
@Composable
private fun ButtonSemantics(enabled: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
        },
    ) {
        content()
    }
}

// ---------------------------------------------------------------- the file

// >>> AETHER-APP-PATCH export-filename-cover
// Suggested filename in the system save dialog. Only this label changes; the
// JSON content's internal "format" marker is never shown to the user and is
// left as-is so existing exported files still import correctly.
private const val EXPORT_FILE_NAME = "tvaVPN-settings.json"
// <<< AETHER-APP-PATCH export-filename-cover

/** Some file managers label a .json file as plain text or as an unknown binary, so accept anything. */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")

private const val FORMAT = "aether-settings"
private const val VERSION = 1

/** Larger than any real settings file (a long app list is about 10 KB). */
private const val MAX_BYTES = 256 * 1024

/** Stored keys that identify the user or carry credentials. Never exported, never imported. */
private val PRIVATE_KEYS = setOf("team", "teamAuth", "accessId", "accessEmail", "upstreamProxy")

private sealed interface ImportResult {
    class Ok(val profile: ConnectionProfile) : ImportResult
    object Newer : ImportResult
    object Invalid : ImportResult
}

/**
 * Writes the settings file to [uri].
 *
 * The in-memory profile is saved first: the settings screen keeps its own copy and
 * persists it a moment after each change, so exporting straight after a change
 * would otherwise miss it.
 */
private suspend fun writeExport(
    context: Context,
    store: ProfileStore,
    profile: ConnectionProfile,
    uri: Uri,
) {
    store.save(profile)
    val settings = JSONObject()
    store.snapshot().asMap().entries
        .map { it.key.name to it.value }
        .filter { (name, _) -> name !in PRIVATE_KEYS }
        .sortedBy { (name, _) -> name }
        .forEach { (name, value) ->
            // The profile only stores strings, booleans and ints.
            if (value is String || value is Boolean || value is Int) settings.put(name, value)
        }
    val json = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("settings", settings)
        .toString(2)
    val stream = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw java.io.IOException("no output stream")
    stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
}

/**
 * Peeks at [uri] to tell a settings backup file (written by [writeExport]) apart
 * from a custom proxy-config JSON file, without importing either one. Used by
 * MainActivity before it decides whether an externally opened/shared `.json`
 * file should be routed to this page or to Custom configs - both are plain
 * JSON, so without this check a settings file opened from outside the app
 * (tapped in a file manager, shared in) would reach [studio.cluvex.aether.custom.ConfigImporter]
 * instead, which rejects it as "the JSON has no proxy outbound" even though it
 * is a perfectly valid file, just the other app's JSON format.
 *
 * Reads the same [MAX_BYTES] ceiling as a real import and never throws; any
 * read or parse failure just means "not a settings file", and the file falls
 * back to the normal custom-config import path, same as before this existed.
 */
internal fun isSettingsBackupFile(context: Context, uri: Uri): Boolean = try {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return false
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    bytes != null && JSONObject(String(bytes, Charsets.UTF_8)).optString("format") == FORMAT
} catch (_: Exception) {
    false
}

/** Reads at most [MAX_BYTES] from [uri] and turns it into a profile. Never throws. */
private fun readImport(context: Context, store: ProfileStore, uri: Uri): ImportResult {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return ImportResult.Invalid
        val bytes = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > MAX_BYTES) return ImportResult.Invalid
                bytes.write(buffer, 0, n)
            }
        }
        val root = JSONObject(String(bytes.toByteArray(), Charsets.UTF_8))
        if (root.optString("format") != FORMAT) return ImportResult.Invalid
        val version = root.optInt("version", 0)
        if (version < 1) return ImportResult.Invalid
        if (version > VERSION) return ImportResult.Newer
        val settings = root.optJSONObject("settings") ?: return ImportResult.Invalid

        val prefs = mutablePreferencesOf()
        for (name in settings.keys()) {
            if (name in PRIVATE_KEYS) continue
            when (val value = settings.opt(name)) {
                is String -> prefs[stringPreferencesKey(name)] = value
                is Boolean -> prefs[booleanPreferencesKey(name)] = value
                is Int -> prefs[intPreferencesKey(name)] = value
                else -> Unit // not part of the format
            }
        }
        // A value of the wrong type for its key (a string where a number belongs)
        // makes the mapper throw, which is "not a usable file", not a crash.
        ImportResult.Ok(store.fromPrefs(prefs))
    } catch (_: Exception) {
        ImportResult.Invalid
    }
}

/** [imported] with this phone's own sign-in and upstream proxy put back. */
private fun keepPrivate(imported: ConnectionProfile, current: ConnectionProfile): ConnectionProfile =
    imported.copy(
        team = current.team,
        teamAuth = current.teamAuth,
        accessClientId = current.accessClientId,
        accessClientSecret = current.accessClientSecret,
        accessEmail = current.accessEmail,
        accessToken = current.accessToken,
        accessSignInToken = current.accessSignInToken,
        upstreamProxy = current.upstreamProxy,
    )
