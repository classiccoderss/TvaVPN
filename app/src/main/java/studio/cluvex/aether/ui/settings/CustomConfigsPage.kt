package studio.cluvex.aether.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.custom.ChainMode
import studio.cluvex.aether.custom.ChainPlacement
import studio.cluvex.aether.custom.ConfigFormat
import studio.cluvex.aether.custom.ConfigProtocol
import studio.cluvex.aether.custom.ConfigImporter
import studio.cluvex.aether.custom.ImportIntentBus
import studio.cluvex.aether.custom.ConfigParser
import studio.cluvex.aether.custom.ConfigPing
import studio.cluvex.aether.custom.ConfigPingStore
import studio.cluvex.aether.custom.ConfigSource
import studio.cluvex.aether.custom.CustomConfig
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.custom.Subscription
import studio.cluvex.aether.custom.SubscriptionStore
import studio.cluvex.aether.custom.NpvtPasswordException
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.theme.OnDarkDim
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** An import that stopped at a NapsternetV password prompt, and how to retry it. */
private class PendingImport(
    val wrong: Boolean,
    val retry: suspend (String?) -> List<CustomConfig>,
)

/**
 * 1.5.0: Settings -> Custom configs.
 *
 * Import (link / text / subscription, file, camera QR, QR in an image), the
 * saved list (select, edit, rename, delete), the master switch and the chain the
 * selected config runs in. Everything here only takes effect on the next
 * connect, which is why - like every engine setting - it is read-only while a
 * session is up.
 */
@Composable
internal fun CustomConfigsPage(
    editable: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CustomConfigStore.load(context) } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SubscriptionStore.load(context) } }
    val st by CustomConfigStore.state.collectAsState()
    // A mode saved before this fix (or imported from an older config) could be a
    // LAST-placement chain on a Hysteria config, which can never connect - see
    // AetherVpnService.connectHysteria. Settle it back to SOLO here, once, instead
    // of letting the user hit "Connect" and get an error for a state the UI itself
    // no longer offers.
    LaunchedEffect(st.active?.id, st.mode) {
        val a = st.active
        if (a?.protocol == ConfigProtocol.HYSTERIA && st.mode.placement == ChainPlacement.LAST) {
            CustomConfigStore.setMode(context, ChainMode.SOLO)
        }
    }
    val pings by ConfigPingStore.results.collectAsState()
    val testingAll by ConfigPingStore.testingAll.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var importMenuOpen by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var actionsFor by remember { mutableStateOf<CustomConfig?>(null) }
    var editing by remember { mutableStateOf<CustomConfig?>(null) }
    var renaming by remember { mutableStateOf<CustomConfig?>(null) }
    var deleting by remember { mutableStateOf<CustomConfig?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val subs by SubscriptionStore.subscriptions.collectAsState()
    var refreshingSubs by remember { mutableStateOf(false) }
    var removingSub by remember { mutableStateOf<Subscription?>(null) }

    val importedMsg = stringResource(R.string.cc_imported)
    val failedMsg = stringResource(R.string.cc_import_failed)
    val noQrMsg = stringResource(R.string.cc_qr_none)
    val clipboardEmptyMsg = stringResource(R.string.cc_paste_empty)

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun runImport(password: String? = null, task: suspend (String?) -> List<CustomConfig>) {
        busy = true
        scope.launch {
            try {
                val list = task(password)
                withContext(Dispatchers.IO) { CustomConfigStore.add(context, list) }
                toast(String.format(importedMsg, list.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NpvtPasswordException) {
                pending = PendingImport(e.wrong, task)
            } catch (e: Exception) {
                toast(String.format(failedMsg, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    // A bare link - nothing else pasted alongside it - is treated as a
    // subscription instead of a one-off import: same rule ConfigImporter
    // already uses to decide whether to download pasted text at all, just
    // acted on here instead of being thrown away after one fetch. This is
    // what replaced the separate "Add a subscription" dialog: pasting the
    // link IS adding the subscription now, auto-detected, nothing else to fill in.
    fun addSubscriptionFromLink(url: String) {
        busy = true
        refreshingSubs = true
        val sub = SubscriptionStore.add(context, "", url)
        scope.launch {
            try {
                SubscriptionStore.refresh(context, sub)
            } finally {
                busy = false
                refreshingSubs = false
            }
        }
    }

    // EXTERNAL IMPORT (tvaVPN): a config link/file the user opened or shared in
    // from outside the app (see MainActivity + ImportIntentBus). This page is
    // the only thing that ever consumes a pending payload, so whichever
    // instance of it is composed when one shows up runs it, through the exact
    // same runImport() / addSubscriptionFromLink() as a manual paste or file
    // pick does above.
    val pendingExternalImport by ImportIntentBus.pending.collectAsState()
    LaunchedEffect(pendingExternalImport) {
        val payload = pendingExternalImport ?: return@LaunchedEffect
        ImportIntentBus.consume()
        // Same restriction as every other action on this page (see the
        // `enabled = editable` rows above) - a locked profile does not get to
        // add configs this way either.
        if (!editable) return@LaunchedEffect
        when (payload) {
            is ImportIntentBus.Payload.Text -> {
                val input = ConfigParser.cleanInput(payload.value)
                // Same single-line-URL rule the paste dialog below uses: a bare
                // https:// link shared in is a subscription, not a one-off config.
                val isLink = input.lines().size == 1 &&
                    (input.startsWith("https://", ignoreCase = true) ||
                        input.startsWith("http://", ignoreCase = true))
                if (isLink) {
                    addSubscriptionFromLink(input)
                } else {
                    runImport { p -> ConfigImporter.fromText(context, input, ConfigSource.LINK, p) }
                }
            }
            is ImportIntentBus.Payload.FileUri ->
                runImport { p -> ConfigImporter.fromFile(context, payload.uri, p) }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport { p -> ConfigImporter.fromFile(context, uri, p) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runImport { p -> ConfigImporter.fromImage(context, uri, p) }
    }
    val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) {
            if (result.originalIntent != null) toast(noQrMsg)
        } else {
            runImport { p -> ConfigImporter.fromText(context, text, ConfigSource.QR, p) }
        }
    }
    val scanPrompt = stringResource(R.string.cc_scan_prompt)

    val active = st.active
    val chainName = active?.chainLabel ?: stringResource(R.string.cc_config_word)

    SettingsScaffold(
        stringResource(R.string.cc_title), onBack, modifier,
        icon = androidx.compose.material.icons.Icons.Rounded.Route,
        accent = studio.cluvex.aether.ui.theme.AetherViolet,
        subtitle = stringResource(R.string.cc_nav_desc),
    ) {
        if (!editable) {
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(
                        text = stringResource(R.string.settings_locked_hint),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.cc_enable),
                    summary = if (active == null) {
                        stringResource(R.string.cc_enable_need)
                    } else {
                        stringResource(R.string.cc_enable_desc, active.name)
                    },
                    icon = Icons.Rounded.VpnKey,
                    checked = st.enabled && active != null,
                    enabled = editable && (active != null || st.enabled),
                    onCheckedChange = { on -> CustomConfigStore.setEnabled(context, on) },
                )
                RowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.cc_auto_select),
                    summary = stringResource(R.string.cc_auto_select_desc),
                    icon = Icons.Rounded.AutoAwesome,
                    checked = st.autoSelect,
                    enabled = editable,
                    onCheckedChange = { on -> CustomConfigStore.setAutoSelect(context, on) },
                )
                RowDivider()
                // Hysteria's own client has no upstream-proxy field, so it can never be
                // the LAST hop (engine -> config) - only alone or FIRST (config -> engine).
                // Offering those options here would just defer the failure to connect
                // time (see AetherVpnService.connectHysteria).
                val chainOptions = if (active?.protocol == ConfigProtocol.HYSTERIA) {
                    ChainMode.entries.filter { it.placement != ChainPlacement.LAST }
                } else {
                    ChainMode.entries.toList()
                }
                SettingsChoiceRow(
                    title = stringResource(R.string.cc_chain_title),
                    summary = stringResource(R.string.cc_chain_desc),
                    icon = Icons.Rounded.Route,
                    options = chainOptions,
                    selected = st.mode,
                    label = { it.label(chainName) },
                    optionDescription = { hopsLabel(it) },
                    enabled = editable,
                    onSelect = { CustomConfigStore.setMode(context, it) },
                )
            }
            GroupFooter(stringResource(R.string.cc_chain_footer))
        }

        settingsSection {
            GroupCaption(stringResource(R.string.cc_group_import))
            SettingsGroup {
                // One entry point instead of a row per method: tapping it opens
                // the method picker below (link/text, file, camera, image). A
                // bare link pasted there is auto-detected and added as a
                // subscription - see addSubscriptionFromLink - so there is no
                // separate "add a subscription" step anymore either.
                SettingsActionRow(
                    title = stringResource(R.string.cc_import_title),
                    summary = stringResource(R.string.cc_import_desc),
                    icon = Icons.Rounded.Add,
                    enabled = !busy,
                    onClick = { importMenuOpen = true },
                )
            }
        }

        settingsSection {
            GroupCaption(stringResource(R.string.cc_group_subscriptions))
            SettingsGroup {
                if (subs.isEmpty()) {
                    SettingsNoticeRow(
                        text = stringResource(R.string.cc_sub_empty),
                        icon = Icons.Rounded.CloudSync,
                    )
                } else {
                    subs.forEachIndexed { index, sub ->
                        if (index > 0) RowDivider()
                        SubscriptionRow(
                            subscription = sub,
                            enabled = editable && !refreshingSubs,
                            onRefresh = {
                                refreshingSubs = true
                                scope.launch {
                                    try {
                                        SubscriptionStore.refresh(context, sub)
                                    } finally {
                                        refreshingSubs = false
                                    }
                                }
                            },
                            onRemove = { removingSub = sub },
                        )
                    }
                }
                if (subs.isNotEmpty()) {
                    RowDivider()
                    SettingsActionRow(
                        title = stringResource(R.string.cc_sub_refresh_all),
                        summary = stringResource(
                            if (refreshingSubs) R.string.cc_ping_running else R.string.cc_sub_refresh_all_desc,
                        ),
                        icon = Icons.Rounded.Refresh,
                        enabled = editable && !refreshingSubs,
                        onClick = {
                            refreshingSubs = true
                            scope.launch {
                                try {
                                    SubscriptionStore.refreshAll(context)
                                } finally {
                                    refreshingSubs = false
                                }
                            }
                        },
                    )
                }
            }
            GroupFooter(stringResource(R.string.cc_sub_footer))
        }

        settingsSection {
            GroupCaption(stringResource(R.string.cc_group_saved, st.configs.size))
            SettingsGroup {
                if (st.configs.isEmpty()) {
                    SettingsNoticeRow(
                        text = stringResource(R.string.cc_empty),
                        icon = Icons.Rounded.Info,
                    )
                } else {
                    st.configs.forEachIndexed { index, cfg ->
                        if (index > 0) RowDivider()
                        ConfigRow(
                            config = cfg,
                            selected = cfg.id == st.activeId,
                            enabled = editable,
                            ping = pings[cfg.id] ?: ConfigPing.Report(),
                            onSelect = { CustomConfigStore.select(context, cfg.id) },
                            onMore = { actionsFor = cfg },
                        )
                    }
                }
            }
            GroupFooter(stringResource(R.string.cc_saved_footer))
        }

        // Real delay for the whole list. Its own section so the saved-configs
        // card stays a list of configs and nothing else.
        if (st.configs.isNotEmpty()) {
            settingsSection {
                SettingsGroup {
                    SettingsActionRow(
                        title = stringResource(R.string.cc_ping_all),
                        summary = stringResource(
                            if (testingAll) R.string.cc_ping_running else R.string.cc_ping_all_desc,
                        ),
                        icon = Icons.Rounded.Speed,
                        enabled = !testingAll,
                        onClick = {
                            val all = st.configs
                            scope.launch { ConfigPingStore.testAll(context, all) }
                        },
                    )
                }
                GroupFooter(stringResource(R.string.cc_ping_footer))
            }
        }

        // Its own section, so it gets the same gap every other group has instead
        // of sitting glued to the bottom of the saved-configs card.
        if (st.configs.isNotEmpty()) {
            settingsSection {
                SettingsGroup {
                    SettingsActionRow(
                        title = stringResource(R.string.cc_delete_all),
                        icon = Icons.Rounded.DeleteSweep,
                        destructive = true,
                        enabled = editable,
                        onClick = { confirmDeleteAll = true },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ dialogs

    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        var pasteCount by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text(stringResource(R.string.cc_import_paste)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LtrOutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = false,
                        placeholder = { Text(stringResource(R.string.cc_paste_hint)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        // The field is focused while this dialog is open; without
                        // the key a pasted value would never reach it.
                        replaceKey = pasteCount,
                    )
                    TextButton(onClick = {
                        val clip = clipboardText(context)
                        if (clip.isNullOrBlank()) {
                            toast(clipboardEmptyMsg)
                        } else {
                            text = clip
                            pasteCount++
                        }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                        Text(stringResource(R.string.cc_paste_clipboard), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        pasteOpen = false
                        val input = ConfigParser.cleanInput(text)
                        // Same single-line-URL rule ConfigImporter.fromText already
                        // uses to decide whether to download the paste at all - a
                        // bare link gets treated as a subscription (added, then
                        // refreshed) instead of a one-off import.
                        val isLink = input.lines().size == 1 &&
                            (input.startsWith("https://", ignoreCase = true) ||
                                input.startsWith("http://", ignoreCase = true))
                        if (isLink) {
                            addSubscriptionFromLink(input)
                        } else {
                            runImport { p -> ConfigImporter.fromText(context, input, ConfigSource.LINK, p) }
                        }
                    },
                ) { Text(stringResource(R.string.cc_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pasteOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    pending?.let { p ->
        var password by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.cc_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (p.wrong) R.string.cc_password_wrong else R.string.cc_password_desc),
                        color = if (p.wrong) MaterialTheme.colorScheme.error else OnDarkDim,
                    )
                    LtrOutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = {
                        pending = null
                        runImport(password, p.retry)
                    },
                ) { Text(stringResource(R.string.cc_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    actionsFor?.let { cfg ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(cfg.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (cfg.locked) {
                        Text(
                            stringResource(R.string.cc_locked_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkDim,
                        )
                    }
                    if (cfg.note.isNotBlank()) {
                        Text(
                            cfg.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkDim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    TextButton(
                        enabled = editable && cfg.id != st.activeId,
                        onClick = { CustomConfigStore.select(context, cfg.id); actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_use)) }
                    TextButton(
                        onClick = {
                            actionsFor = null
                            scope.launch { ConfigPingStore.test(context, cfg) }
                        },
                    ) { Text(stringResource(R.string.cc_ping_one)) }
                    TextButton(
                        enabled = editable && !cfg.locked && cfg.raw.isNotBlank(),
                        onClick = { editing = cfg; actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_edit)) }
                    TextButton(
                        enabled = editable,
                        onClick = { renaming = cfg; actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_rename)) }
                    if (!cfg.locked && cfg.raw.isNotBlank()) {
                        TextButton(onClick = {
                            // Holds the server's credentials: flagged sensitive so the
                            // system clipboard preview and keyboards do not show it.
                            studio.cluvex.aether.ui.components.copySensitive(context, cfg.raw, "config")
                            actionsFor = null
                        }) { Text(stringResource(R.string.cc_action_copy)) }
                    }
                    TextButton(
                        enabled = editable,
                        onClick = { deleting = cfg; actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsFor = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    editing?.let { cfg ->
        var raw by remember(cfg.id) { mutableStateOf(cfg.raw) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.cc_edit_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LtrOutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = raw.isNotBlank() && raw != cfg.raw,
                    onClick = {
                        val edited = raw
                        editing = null
                        scope.launch {
                            try {
                                val next = ConfigImporter.reparse(context, cfg, edited)
                                withContext(Dispatchers.IO) { CustomConfigStore.replace(context, next) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toast(String.format(failedMsg, e.message ?: e.javaClass.simpleName))
                            }
                        }
                    },
                ) { Text(stringResource(R.string.cc_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    renaming?.let { cfg ->
        var name by remember(cfg.id) { mutableStateOf(cfg.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.cc_action_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cc_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        CustomConfigStore.replace(context, cfg.copy(name = name.trim()))
                        renaming = null
                    },
                ) { Text(stringResource(R.string.cc_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    deleting?.let { cfg ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.cc_delete_title)) },
            text = { Text(stringResource(R.string.cc_delete_body, cfg.name)) },
            confirmButton = {
                TextButton(onClick = {
                    CustomConfigStore.delete(context, cfg.id)
                    deleting = null
                }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.cc_delete_all)) },
            text = { Text(stringResource(R.string.cc_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    CustomConfigStore.deleteAll(context)
                    confirmDeleteAll = false
                }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    if (importMenuOpen) {
        AlertDialog(
            onDismissRequest = { importMenuOpen = false },
            title = { Text(stringResource(R.string.cc_import_title)) },
            text = {
                Column {
                    ImportMethodRow(
                        icon = Icons.Rounded.Link,
                        title = stringResource(R.string.cc_import_paste),
                        summary = stringResource(R.string.cc_import_paste_desc),
                        onClick = { importMenuOpen = false; pasteOpen = true },
                    )
                    ImportMethodRow(
                        icon = Icons.Rounded.FileOpen,
                        title = stringResource(R.string.cc_import_file),
                        summary = stringResource(R.string.cc_import_file_desc),
                        onClick = {
                            importMenuOpen = false
                            runCatching { pickFile.launch(arrayOf("*/*")) }
                        },
                    )
                    ImportMethodRow(
                        icon = Icons.Rounded.QrCodeScanner,
                        title = stringResource(R.string.cc_import_camera),
                        summary = stringResource(R.string.cc_import_camera_desc),
                        onClick = {
                            importMenuOpen = false
                            runCatching {
                                scanQr.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt(scanPrompt)
                                        .setBeepEnabled(false)
                                        // Portrait only: the stock capture screen is
                                        // landscape and rotated with the sensor.
                                        .setOrientationLocked(true)
                                        .setCaptureActivity(PortraitCaptureActivity::class.java),
                                )
                            }
                        },
                    )
                    ImportMethodRow(
                        icon = Icons.Rounded.Image,
                        title = stringResource(R.string.cc_import_image),
                        summary = stringResource(R.string.cc_import_image_desc),
                        onClick = {
                            importMenuOpen = false
                            runCatching { pickImage.launch("image/*") }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { importMenuOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    removingSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { removingSub = null },
            title = { Text(stringResource(R.string.cc_sub_remove_title)) },
            text = { Text(stringResource(R.string.cc_sub_remove_body, sub.name)) },
            confirmButton = {
                TextButton(onClick = {
                    SubscriptionStore.remove(context, sub.id)
                    removingSub = null
                }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removingSub = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun hopsLabel(mode: ChainMode): String = when (mode.hops) {
    1 -> stringResource(R.string.cc_hops_single)
    2 -> stringResource(R.string.cc_hops_double)
    else -> stringResource(R.string.cc_hops_triple)
}

/** The label for the settings home row: the chain in use, or "Off". */
@Composable
internal fun customConfigsValue(): String {
    val st by CustomConfigStore.state.collectAsState()
    val pings by ConfigPingStore.results.collectAsState()
    val testingAll by ConfigPingStore.testingAll.collectAsState()
    val sel = st.selection
    return sel?.mode?.label(sel.config.chainLabel) ?: stringResource(R.string.cc_off)
}

/**
 * Reads the clipboard the way a user means it.
 *
 * ROOT CAUSE this fixes (1.5.0-r2, "Paste from clipboard does nothing"): the
 * button used Compose's `ClipboardManager.getText()`, which returns the FIRST
 * clip item and only when that item already carries plain text. A config link
 * copied from a browser's address bar, from Telegram, or from a QR-reader app
 * arrives as a `text/uri-list` item, or as styled HTML, or as a second item - and
 * for every one of those `getText()` returns null. The button then silently did
 * nothing at all, with no message, which is exactly what was reported.
 *
 * `ClipData.Item.coerceToText` is the platform's own answer: it returns the text
 * of a text item, the URI of a URI item, and the stripped text of an HTML item.
 * Every item is read, not just the first, so a multi-link clip (a whole
 * subscription pasted in one go) imports as the several configs it is.
 */
/**
 * Every text item on the clipboard, cleaned for the importer.
 *
 * Read with the item's own text first and `coerceToText` only as a fallback
 * (that one may open a content URI and can throw on some OEM clipboards), and
 * never allowed to throw: a SecurityException from a clipboard read in the
 * background, or a provider that has gone away, means "nothing to paste", not
 * a crash. Invisible marks that messengers put around links (RLM/LRM, BOM,
 * zero-width spaces) are removed here so the link is recognised as one.
 */
private fun clipboardText(context: android.content.Context): String? {
    val manager = context.getSystemService(android.content.ClipboardManager::class.java) ?: return null
    val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null
    val text = (0 until clip.itemCount)
        .mapNotNull { index ->
            runCatching {
                val item = clip.getItemAt(index)
                item.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: item.htmlText?.let { html ->
                        androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                            .toString()
                    }?.takeIf { it.isNotBlank() }
                    ?: item.coerceToText(context)?.toString()
            }.getOrNull()
        }
        .map { ConfigParser.cleanInput(it) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    return text.ifBlank { null }
}

/** The ms badge / verdict shown on a config's card. */
@Composable
private fun PingBadge(report: ConfigPing.Report) {
    when (report.status) {
        ConfigPing.PingStatus.UNTESTED -> Unit
        ConfigPing.PingStatus.TESTING -> CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.padding(end = 4.dp).heightIn(max = 16.dp).widthIn(max = 16.dp),
        )
        else -> {
            val (label, tint) = when (report.status) {
                ConfigPing.PingStatus.OK -> stringResource(R.string.cc_ping_ms, report.ms) to pingColor(report.ms)
                ConfigPing.PingStatus.OFFLINE ->
                    stringResource(R.string.cc_ping_offline) to MaterialTheme.colorScheme.error
                ConfigPing.PingStatus.BLOCKED ->
                    stringResource(R.string.cc_ping_blocked) to Color(0xFFF59E0B)
                ConfigPing.PingStatus.NO_REPLY ->
                    stringResource(R.string.cc_ping_noreply) to Color(0xFFF59E0B)
                ConfigPing.PingStatus.INVALID ->
                    stringResource(R.string.cc_ping_invalid) to MaterialTheme.colorScheme.error
                else -> stringResource(R.string.cc_ping_nonet) to OnDarkDim
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                if (!report.ok && report.status != ConfigPing.PingStatus.NO_NETWORK) {
                    // The number every other client prints for a dead config, so a
                    // screenshot from here means the same thing as one from v2rayNG.
                    Text(
                        stringResource(R.string.cc_ping_ms, ConfigPing.FAILED_MS),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = OnDarkDim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Green / amber / red for a latency, on the same thresholds the rest of the app uses. */
private fun pingColor(ms: Long): Color = when {
    ms in 1..299 -> Color(0xFF22C55E)
    ms in 300..799 -> Color(0xFFF59E0B)
    else -> Color(0xFFEF4444)
}

@Composable
private fun ConfigRow(
    config: CustomConfig,
    selected: Boolean,
    enabled: Boolean,
    ping: ConfigPing.Report,
    onSelect: () -> Unit,
    onMore: () -> Unit,
) {
    val detail = buildList {
        add(if (config.format == ConfigFormat.NPVT) "${config.protocol.short} \u00b7 npvt" else config.protocol.short)
        if (config.locked) add(stringResource(R.string.cc_locked)) else if (config.server.isNotBlank()) add(config.server)
        if (config.isExpired()) add(stringResource(R.string.cc_badge_expired))
        if (config.mobileOnly) add(stringResource(R.string.cc_badge_mobile))
    }.joinToString(" \u00b7 ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (config.locked) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = OnDarkDim,
                        modifier = Modifier.padding(end = 4.dp).heightIn(max = 16.dp),
                    )
                }
                Text(
                    config.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PingBadge(ping)
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cc_more))
        }
    }
}
/** One row in the import-method picker dialog: icon, title, one-line summary. */
@Composable
private fun ImportMethodRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = OnDarkDim)
        }
    }
}

/** One row in the Subscriptions list: its name, last-refresh summary, refresh and remove buttons. */
@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val detail = when {
        subscription.lastRefreshAt == 0L -> stringResource(R.string.cc_sub_never_refreshed)
        subscription.lastError.isNotBlank() -> stringResource(R.string.cc_sub_last_error, subscription.lastError)
        else -> {
            val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(
                subscription.lastRefreshAt,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
            )
            stringResource(R.string.cc_sub_last_ok, subscription.lastCount, whenText)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                subscription.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (subscription.lastError.isNotBlank()) MaterialTheme.colorScheme.error else OnDarkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = enabled) {
            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cc_sub_refresh))
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.cc_action_delete))
        }
    }
}
