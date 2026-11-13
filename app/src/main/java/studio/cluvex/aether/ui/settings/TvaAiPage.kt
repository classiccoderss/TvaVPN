package studio.cluvex.aether.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiProposalValidation
import studio.cluvex.aether.ai.AiRedaction
import studio.cluvex.aether.ai.AiSettingsProposal
import studio.cluvex.aether.ai.GeminiApiClient
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.data.SecretStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.theme.AetherError
import studio.cluvex.aether.ui.theme.AetherMint
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.Navy850
import studio.cluvex.aether.ui.theme.Navy950
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkMuted

private data class TvaAiMessage(val fromUser: Boolean, val text: String)

/** Total attempts for a Gemini call: the first try plus short-delay retries on a flaky main tunnel. */
private const val MAX_ROUTE_ATTEMPTS = 3

/** Brief pause before retrying a Gemini request over the same main-tunnel SOCKS port. */
private const val ROUTE_RETRY_DELAY_MS = 1_200L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvaAiPage(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("tva_ai_settings", Context.MODE_PRIVATE) }
    val secretStore = remember(context) { SecretStore(context) }
    // The Gemini API key and model are configured on [TvaAiSettingsPage], not
    // here - this page is chat only. They are read once per visit (re-read
    // fresh every time this page is navigated into, since persistSettings()
    // used to live here but now lives there) rather than exposed as editable
    // fields.
    val apiKey = remember(context) { secretStore.read(SecretStore.GEMINI_API_KEY) }
    val model = remember(context) { GeminiApiClient.resolveStoredModel(prefs.getString("gemini_model", null)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var pendingProposal by remember { mutableStateOf<AiSettingsProposal?>(null) }
    var pendingUpdatedProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var showApplyDialog by remember { mutableStateOf(false) }
    var previousProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var reconnectOnUndo by remember { mutableStateOf(false) }
    var messageInput by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<TvaAiMessage>() }
    val history = remember { mutableStateListOf<GeminiApiClient.ChatTurn>() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    fun showError(message: String) {
        status = message
        statusIsError = true
    }

    /**
     * The local SOCKS port of the user's already-connected main VPN tunnel -
     * the same tunnel the rest of the app (and Gemini, since it is blocked the
     * same way the rest of the internet is) already uses once connected. TVA AI
     * never opens a route of its own; it only reuses this one, and only while
     * [state] reports the main tunnel as connected.
     */
    fun mainTunnelSocksPort(): Int? {
        val connected = state as? ConnectionState.Connected ?: return null
        val addr = connected.socksAddr
        val colon = addr.lastIndexOf(':')
        if (colon <= 0) return null
        return addr.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /**
     * Runs [action] against the main VPN tunnel's SOCKS port, and if the
     * connection turns out to be the kind of unstable that only shows up once
     * real traffic hits it (GeminiApiClient.RouteUnstableException - TLS reset
     * mid-response, refused connection, timeout), waits briefly and retries,
     * instead of giving up on the first transient hiccup. Any other exception
     * (bad key, model overloaded, etc.) is not retried here and propagates to
     * the caller immediately. The main tunnel itself is never touched: there is
     * nothing to rebuild here, only the request to try again.
     */
    suspend fun <T> withResilientGeminiRoute(action: suspend (Int) -> T): T {
        var lastError: GeminiApiClient.RouteUnstableException? = null
        repeat(MAX_ROUTE_ATTEMPTS) { attempt ->
            val port = mainTunnelSocksPort()
                ?: throw IllegalStateException(context.getString(R.string.tva_ai_vpn_not_connected))
            if (attempt > 0) delay(ROUTE_RETRY_DELAY_MS)
            try {
                return action(port)
            } catch (error: GeminiApiClient.RouteUnstableException) {
                lastError = error
                if (attempt < MAX_ROUTE_ATTEMPTS - 1) {
                    DiagnosticsLog.w(
                        "ai",
                        "route attempt ${attempt + 1}/$MAX_ROUTE_ATTEMPTS unstable (${error.message}), retrying in ${ROUTE_RETRY_DELAY_MS}ms",
                    )
                    status = context.getString(R.string.tva_ai_route_retrying)
                    statusIsError = false
                }
            }
        }
        throw requireNotNull(lastError)
    }

    fun sendPrompt(text: String, optimize: Boolean = false, logContext: String? = null) {
        val clean = text.trim()
        if (clean.isBlank() || busy) return
        if (apiKey.isBlank()) {
            showError(context.getString(R.string.tva_ai_missing_key))
            return
        }
        messages.add(TvaAiMessage(fromUser = true, text = clean))
        history.add(GeminiApiClient.ChatTurn(user = true, text = clean))
        messageInput = ""
        pendingProposal = null
        pendingUpdatedProfile = null
        busy = true
        status = context.getString(R.string.tva_ai_route_starting)
        statusIsError = false
        scope.launch {
            try {
                val reply = withResilientGeminiRoute { port ->
                    if (optimize) {
                        GeminiApiClient.optimize(
                            apiKey.trim(), model.trim(), port, profile,
                            connectionState = state,
                            diagnosticContext = logContext,
                        )
                    } else {
                        GeminiApiClient.chat(
                            apiKey.trim(), model.trim(), port, profile, history.toList(),
                            connectionState = state,
                            additionalContext = logContext,
                        )
                    }
                }
                messages.add(TvaAiMessage(fromUser = false, text = reply.text))
                history.add(GeminiApiClient.ChatTurn(user = false, text = reply.text))
                if (reply.proposal != null) {
                    when (val validation = studio.cluvex.aether.ai.AiProposalValidator.validateAndApply(profile, reply.proposal)) {
                        is AiProposalValidation.Accepted -> {
                            pendingProposal = reply.proposal
                            pendingUpdatedProfile = validation.updatedProfile
                        }
                        is AiProposalValidation.Rejected -> {
                            messages.add(
                                TvaAiMessage(
                                    fromUser = false,
                                    text = context.getString(R.string.tva_ai_proposal_rejected, validation.reasons.joinToString("؛ ")),
                                ),
                            )
                        }
                    }
                }
                status = context.getString(R.string.tva_ai_response_received)
                statusIsError = false
            } catch (error: Exception) {
                showError(error.message ?: context.getString(R.string.tva_ai_generic_error))
                messages.add(TvaAiMessage(fromUser = false, text = error.message ?: context.getString(R.string.tva_ai_generic_error)))
            } finally {
                busy = false
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    fun analyzeLogs() {
        val redacted = AiRedaction.digest(
            fullLog = DiagnosticsLog.exportText(),
            ownApiKey = apiKey,
            maxLines = 180,
        )
        sendPrompt(
            text = context.getString(R.string.tva_ai_analyze_logs_prompt),
            optimize = false,
            logContext = redacted,
        )
    }

    fun beginApply() {
        val proposal = pendingProposal ?: return
        val validation = studio.cluvex.aether.ai.AiProposalValidator.validateAndApply(profile, proposal)
        when (validation) {
            is AiProposalValidation.Accepted -> {
                pendingUpdatedProfile = validation.updatedProfile
                showApplyDialog = true
            }
            is AiProposalValidation.Rejected -> showError(validation.reasons.joinToString("؛ "))
        }
    }

    fun applyWithoutReconnect() {
        val updated = pendingUpdatedProfile ?: return
        previousProfile = profile
        reconnectOnUndo = false
        onProfileChange(updated)
        pendingProposal = null
        pendingUpdatedProfile = null
        showApplyDialog = false
        status = context.getString(R.string.tva_ai_applied_next_connect)
        statusIsError = false
    }

    fun applyAndReconnect() {
        val updated = pendingUpdatedProfile ?: return
        previousProfile = profile
        reconnectOnUndo = true
        onProfileChange(updated)
        pendingProposal = null
        pendingUpdatedProfile = null
        showApplyDialog = false
        busy = true
        status = context.getString(R.string.tva_ai_reconnecting)
        statusIsError = false
        scope.launch {
            try {
                AetherController.disconnect(context)
                val stopped = withTimeoutOrNull(45_000) {
                    AetherController.state.first { it is ConnectionState.Idle || it is ConnectionState.Error }
                    true
                } ?: false
                if (stopped) {
                    AetherController.connect(context, updated)
                    status = context.getString(R.string.tva_ai_reconnect_started)
                    statusIsError = false
                } else {
                    status = context.getString(R.string.tva_ai_reconnect_timeout)
                    statusIsError = true
                }
            } catch (error: Exception) {
                showError(error.message ?: context.getString(R.string.tva_ai_generic_error))
            } finally {
                busy = false
            }
        }
    }

    if (showApplyDialog) {
        AlertDialog(
            onDismissRequest = { showApplyDialog = false },
            title = { Text(stringResource(R.string.tva_ai_apply_title)) },
            text = {
                Text(
                    if (state is ConnectionState.Connected) stringResource(R.string.tva_ai_apply_connected_message)
                    else stringResource(R.string.tva_ai_apply_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = if (state is ConnectionState.Connected) ::applyAndReconnect else ::applyWithoutReconnect,
                ) {
                    Text(
                        if (state is ConnectionState.Connected) stringResource(R.string.tva_ai_apply_reconnect)
                        else stringResource(R.string.tva_ai_apply_later),
                    )
                }
            },
            dismissButton = {
                Row {
                    if (state is ConnectionState.Connected) {
                        TextButton(onClick = ::applyWithoutReconnect) {
                            Text(stringResource(R.string.tva_ai_apply_later))
                        }
                    }
                    TextButton(onClick = { showApplyDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Navy950,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.tva_ai_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.tva_ai_subtitle), style = MaterialTheme.typography.labelSmall, color = OnDarkMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tva_ai_open_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy950,
                    titleContentColor = OnDark,
                    navigationIconContentColor = OnDark,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Navy850)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.tva_ai_message_hint)) },
                        enabled = !busy,
                        maxLines = 4,
                    )
                    IconButton(
                        onClick = { sendPrompt(messageInput) },
                        enabled = !busy && messageInput.isNotBlank() && apiKey.isNotBlank(),
                        modifier = Modifier.size(52.dp).semantics {
                            contentDescription = context.getString(R.string.tva_ai_send)
                        },
                    ) {
                        Icon(Icons.Rounded.Send, contentDescription = null, tint = AetherViolet)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { sendPrompt(context.getString(R.string.tva_ai_optimize_prompt), optimize = true) },
                        enabled = !busy && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.tva_ai_optimize)) }
                    OutlinedButton(
                        onClick = ::analyzeLogs,
                        enabled = !busy && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.tva_ai_analyze_logs)) }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The Gemini key, model and connection test used to live in a
            // card right here - moved to TvaAiSettingsPage (opened via
            // onOpenSettings / the gear icon above) so this page is chat
            // only. All that is left of it here is the status line below,
            // which still reports what a send/apply/undo is doing.
            if (status.isNotBlank()) {
                item {
                    Text(
                        status,
                        color = if (statusIsError) AetherError else AetherMint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
                    )
                }
            }

            if (apiKey.isBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Navy850),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = AetherViolet)
                                Text(
                                    stringResource(R.string.tva_ai_settings_required),
                                    modifier = Modifier.padding(start = 8.dp),
                                    color = OnDark,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.tva_ai_go_to_settings))
                            }
                        }
                    }
                }
            } else if (messages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Navy800),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.tva_ai_welcome),
                            color = OnDark,
                            modifier = Modifier.padding(14.dp).semantics(mergeDescendants = true) {},
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            itemsIndexed(messages) { _, message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.fromUser) Color(0xFF1B2C52) else Navy850,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(0.92f).semantics(mergeDescendants = true) {},
                    ) {
                        Text(
                            message.text,
                            color = OnDark,
                            modifier = Modifier.padding(13.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            val proposal = pendingProposal
            if (proposal != null) {
                item {
                    ProposalCard(
                        proposal = proposal,
                        onApply = ::beginApply,
                        onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(proposal.changes.joinToString("\n") { "${it.setting}=${it.value}: ${it.reason}" })) },
                    )
                }
            }

            if (busy) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AetherViolet)
                        Text(stringResource(R.string.tva_ai_working), color = OnDarkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            previousProfile?.let { old ->
                item {
                    TextButton(onClick = {
                        onProfileChange(old)
                        previousProfile = null
                        if (reconnectOnUndo && state !is ConnectionState.Idle && state !is ConnectionState.Error) {
                            reconnectOnUndo = false
                            busy = true
                            status = context.getString(R.string.tva_ai_reconnecting)
                            statusIsError = false
                            scope.launch {
                                try {
                                    AetherController.disconnect(context)
                                    val stopped = withTimeoutOrNull(45_000) {
                                        AetherController.state.first { it is ConnectionState.Idle || it is ConnectionState.Error }
                                        true
                                    } ?: false
                                    if (stopped) {
                                        AetherController.connect(context, old)
                                        status = context.getString(R.string.tva_ai_undo_reconnect_started)
                                        statusIsError = false
                                    } else {
                                        status = context.getString(R.string.tva_ai_reconnect_timeout)
                                        statusIsError = true
                                    }
                                } catch (error: Exception) {
                                    showError(error.message ?: context.getString(R.string.tva_ai_generic_error))
                                } finally {
                                    busy = false
                                }
                            }
                        } else {
                            status = context.getString(R.string.tva_ai_undo_done)
                            statusIsError = false
                        }
                    }) { Text(stringResource(R.string.tva_ai_undo)) }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: AiSettingsProposal,
    onApply: () -> Unit,
    onCopy: () -> Unit,
) {
    val copyDescription = stringResource(R.string.tva_ai_copy_proposal)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF201D3F)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = AetherViolet)
                Text(proposal.title, modifier = Modifier.padding(start = 8.dp), color = OnDark, fontWeight = FontWeight.SemiBold)
            }
            Text(proposal.summary, color = OnDark)
            Text(stringResource(R.string.tva_ai_benefit_label, proposal.expectedBenefit), color = OnDarkMuted, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.tva_ai_tradeoff_label, proposal.tradeoff), color = OnDarkMuted, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.tva_ai_confidence_label, (proposal.confidence * 100).toInt()), color = OnDarkMuted, style = MaterialTheme.typography.bodySmall)
            proposal.changes.forEach { change ->
                val label = when (change.setting) {
                    "mtu" -> stringResource(R.string.tva_ai_setting_mtu)
                    "protocol" -> stringResource(R.string.tva_ai_setting_protocol)
                    "scanMode" -> stringResource(R.string.tva_ai_setting_scan_mode)
                    "ipVersion" -> stringResource(R.string.tva_ai_setting_ip_version)
                    "noize" -> stringResource(R.string.tva_ai_setting_noize)
                    "keepalive" -> stringResource(R.string.tva_ai_setting_keepalive)
                    "fragmentPreset" -> stringResource(R.string.tva_ai_setting_fragment_preset)
                    "quickReconnect" -> stringResource(R.string.tva_ai_setting_quick_reconnect)
                    "fastEndpointOnly" -> stringResource(R.string.tva_ai_setting_fast_endpoint_only)
                    else -> change.setting
                }
                Column(Modifier.fillMaxWidth().background(Navy800, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("$label ← ${change.value}", color = OnDark, fontWeight = FontWeight.Medium)
                    Text(change.reason, color = OnDarkMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onApply, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tva_ai_apply))
                }
                IconButton(onClick = onCopy, modifier = Modifier.semantics {
                    contentDescription = copyDescription
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = OnDark)
                }
            }
        }
    }
}
