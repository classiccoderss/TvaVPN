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
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PingResult
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

// internal, not private, 1.5.7: TvaAiChatSession.messages (below) is now
// internal too, and a member cannot be more visible than its own type.
internal data class TvaAiMessage(val fromUser: Boolean, val text: String)

/**
 * TVA AI conversation state, held in a plain singleton instead of this
 * composable's own `remember` (1.5.3). `remember` is scoped to this
 * composable staying in the navigation graph: leaving this screen (the gear
 * icon to [TvaAiSettingsPage] and back, a rotation, backgrounding the app)
 * tore it down, so every return to this page started from an empty chat even
 * though nothing else about the app's state had reset.
 *
 * A singleton's members live exactly as long as the app's process does -
 * nothing more, nothing less. That is exactly "cleared only when the app is
 * actually closed": navigating within the app, or Android recreating this
 * screen for any reason, never touches the process; only the app being fully
 * closed (swiped from recents with no VPN tunnel left keeping the process
 * alive - see [studio.cluvex.aether.vpn.AetherVpnService.onTaskRemoved] -
 * force-stopped, or killed by the system to reclaim memory) does, and at
 * that point every one of these fields is gone along with it, with nothing
 * further to clean up here.
 */
// internal (not private), 1.5.7: SettingsScreen.kt's "ask AI" row button
// prefills messageInput with a ready question from wherever a settings row
// is, before navigating here - it needs this same instance, not a copy.
internal object TvaAiChatSession {
    val messages = mutableStateListOf<TvaAiMessage>()
    val history = mutableStateListOf<GeminiApiClient.ChatTurn>()
    var pendingProposal by mutableStateOf<AiSettingsProposal?>(null)
    var pendingUpdatedProfile by mutableStateOf<ConnectionProfile?>(null)
    var previousProfile by mutableStateOf<ConnectionProfile?>(null)
    var reconnectOnUndo by mutableStateOf(false)
    var messageInput by mutableStateOf("")
}

/** Total attempts for a Gemini call: the first try plus short-delay retries on a flaky main tunnel. */
private const val MAX_ROUTE_ATTEMPTS = 3

/** Brief pause before retrying a Gemini request over the same main-tunnel SOCKS port. */
private const val ROUTE_RETRY_DELAY_MS = 1_200L

/**
 * SharedPreferences key for "apply suggestions automatically", toggled on
 * [TvaAiSettingsPage]. Shared with this page as a plain string literal, the
 * same way "gemini_model" already is - both files read the one
 * "tva_ai_settings" prefs file.
 */
private const val KEY_AUTO_APPLY = "auto_apply"

/**
 * Markers folded into Gemini's OWN stored turn in [TvaAiChatSession.history]
 * (1.5.4), never into the bubble the user sees in [TvaAiChatSession.messages]
 * - these are conversation memory for the model, not a user-facing message.
 * Without them, Gemini only had the fresh profile snapshot at the top of
 * every request to infer what already happened from; that is enough once a
 * change is genuinely applied (the snapshot just shows the new value), but
 * it is NOT enough for a REJECTED proposal - Gemini never learned a given
 * combination was invalid, so it could, and did, propose the exact same
 * rejected combination again on the next turn, which read as a repeated/
 * useless suggestion. Writing the outcome directly into the turn Gemini
 * itself produced (and that gets sent straight back as its own prior turn on
 * every later request) is the one place this information is guaranteed to
 * still be in context, bounded history trimming aside. See
 * [TvaAiKnowledge.systemInstruction] for the matching instruction telling
 * Gemini how to read these.
 */
private const val PROPOSAL_ACCEPTED_MARKER = "[TVA_PROPOSAL_ACCEPTED]"
private const val PROPOSAL_APPLIED_MARKER = "[TVA_PROPOSAL_APPLIED]"
private const val PROPOSAL_REJECTED_MARKER = "[TVA_PROPOSAL_REJECTED]"

/**
 * Closes the loop [PROPOSAL_APPLIED_MARKER] on its own could not: that marker
 * says a change was applied, not whether it actually helped. This one
 * carries a measured before/after latency reading, folded onto the same
 * history turn once [applyAndReconnect] finishes reconnecting - see
 * [TvaAiKnowledge.systemInstruction] for how Gemini is told to read it.
 */
private const val PROPOSAL_RESULT_MARKER = "[TVA_PROPOSAL_RESULT]"

/** Below this, a latency difference is just measurement noise, not a real before/after change. */
private const val LATENCY_NOISE_FLOOR_MS = 15L

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
    // showApplyDialog stays local, on purpose: it is "is the confirmation
    // dialog open right now", not conversation content. Re-showing it on its
    // own the moment someone comes back to this screen would be surprising;
    // the pending proposal card below the chat (which DOES come from
    // TvaAiChatSession) is still there either way for them to apply by hand.
    var showApplyDialog by remember { mutableStateOf(false) }
    val messages = TvaAiChatSession.messages
    val history = TvaAiChatSession.history
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

    /**
     * Upgrades the [PROPOSAL_ACCEPTED_MARKER] [sendPrompt] left on Gemini's own
     * last turn to [PROPOSAL_APPLIED_MARKER], so the very next request already
     * tells Gemini - in its own remembered words, not just inferred from a
     * fresh profile snapshot - that this specific change is done, not merely
     * pending. Only touches the LAST history turn, and only if it is still
     * carrying the "accepted" marker; if something else happened in between
     * (unlikely, since apply/auto-apply runs right after the proposal that
     * produced it), this is a harmless no-op - the next profile snapshot
     * reflects the applied value regardless.
     */
    fun markLastProposalApplied() {
        val lastIndex = history.lastIndex
        if (lastIndex < 0) return
        val last = history[lastIndex]
        if (!last.user && last.text.contains(PROPOSAL_ACCEPTED_MARKER)) {
            history[lastIndex] = last.copy(text = last.text.replace(PROPOSAL_ACCEPTED_MARKER, PROPOSAL_APPLIED_MARKER))
        }
    }

    fun applyWithoutReconnect() {
        val updated = TvaAiChatSession.pendingUpdatedProfile ?: return
        TvaAiChatSession.previousProfile = profile
        TvaAiChatSession.reconnectOnUndo = false
        onProfileChange(updated)
        markLastProposalApplied()
        TvaAiChatSession.pendingProposal = null
        TvaAiChatSession.pendingUpdatedProfile = null
        showApplyDialog = false
        status = context.getString(R.string.tva_ai_applied_next_connect)
        statusIsError = false
    }

    /**
     * Appends a measured before/after latency reading to Gemini's own last
     * turn in [TvaAiChatSession.history] (1.5.5), right after
     * [markLastProposalApplied] already upgraded it to
     * [PROPOSAL_APPLIED_MARKER] there - closing the loop [sendPrompt]'s own
     * `expectedBenefit` text never did on its own: Gemini wrote what it
     * EXPECTED, but nothing ever told it whether that actually happened. A
     * later "بازم بهینه کن" in this same conversation now starts from what was
     * actually measured last time, not just the profile snapshot and
     * Gemini's own optimism about it.
     */
    fun appendLatencyResultToHistory(beforeMs: Long, afterMs: Long, delta: Long) {
        val lastIndex = history.lastIndex
        if (lastIndex < 0) return
        val last = history[lastIndex]
        if (last.user) return
        val verdict = when {
            delta <= -LATENCY_NOISE_FLOOR_MS -> "improved"
            delta >= LATENCY_NOISE_FLOOR_MS -> "got worse"
            else -> "about the same (within measurement noise)"
        }
        history[lastIndex] = last.copy(
            text = last.text + "\n\n$PROPOSAL_RESULT_MARKER measured latency before=${beforeMs}ms after=${afterMs}ms ($verdict).",
        )
    }

    /**
     * Waits for the reconnect [applyAndReconnect] just started to actually
     * reach [ConnectionState.Connected], takes one fresh latency reading on
     * the new settings, and reports it against [beforePing] - the reading
     * taken right before the old settings were torn down, so there is
     * something honest to compare the new one with. Runs as its own
     * independent coroutine (launched, never awaited, by [applyAndReconnect])
     * and never touches [busy]: a connection can take a while to settle, and
     * nothing else on this screen should wait on it.
     *
     * Silently does nothing - no message, no history note - whenever the
     * comparison would not be meaningful: no baseline reading yet, the
     * reconnect did not reach Connected within the wait, or the fresh probe
     * itself failed. A missing result is simply not interesting enough to
     * report; it is not treated as a proposal failure.
     */
    suspend fun measureAndRecordProposalResult(beforePing: PingResult) {
        val before = beforePing.takeIf { !it.running && !it.error && it.ms >= 0 } ?: return
        val connected = withTimeoutOrNull(30_000) {
            AetherController.state.first { it is ConnectionState.Connected || it is ConnectionState.Error }
        }
        if (connected !is ConnectionState.Connected) return
        // Short settle delay: Connected fires the moment the tunnel is up,
        // slightly ahead of a probe through it being representative of
        // steady-state rather than the handshake's own tail.
        delay(1_500L)
        if (!PingMonitor.pingOnce(viaTunnel = true)) return
        val after = PingMonitor.state.value
        if (after.error || after.ms < 0) return
        val delta = after.ms - before.ms
        val resultText = when {
            delta <= -LATENCY_NOISE_FLOOR_MS -> context.getString(R.string.tva_ai_latency_improved, before.ms, after.ms)
            delta >= LATENCY_NOISE_FLOOR_MS -> context.getString(R.string.tva_ai_latency_worse, before.ms, after.ms)
            else -> context.getString(R.string.tva_ai_latency_unchanged, before.ms, after.ms)
        }
        messages.add(TvaAiMessage(fromUser = false, text = resultText))
        appendLatencyResultToHistory(before.ms, after.ms, delta)
    }

    fun applyAndReconnect() {
        val updated = TvaAiChatSession.pendingUpdatedProfile ?: return
        TvaAiChatSession.previousProfile = profile
        TvaAiChatSession.reconnectOnUndo = true
        onProfileChange(updated)
        markLastProposalApplied()
        TvaAiChatSession.pendingProposal = null
        TvaAiChatSession.pendingUpdatedProfile = null
        showApplyDialog = false
        busy = true
        status = context.getString(R.string.tva_ai_reconnecting)
        statusIsError = false
        // Read BEFORE the disconnect below changes anything: this is the old
        // settings' own latency, the one honest baseline to compare the new
        // settings' reading against once reconnected.
        val beforePing = PingMonitor.state.value
        scope.launch {
            try {
                // Carry the proposal's own profile along so the service's
                // strict-kill-switch check (see AetherController.disconnect)
                // sees what this disconnect is ACTUALLY supposed to apply,
                // not whatever was true when the current session connected.
                AetherController.disconnect(context, updated)
                val stopped = withTimeoutOrNull(45_000) {
                    AetherController.state.first { it is ConnectionState.Idle || it is ConnectionState.Error }
                    true
                } ?: false
                if (stopped) {
                    AetherController.connect(context, updated)
                    status = context.getString(R.string.tva_ai_reconnect_started)
                    statusIsError = false
                    scope.launch { measureAndRecordProposalResult(beforePing) }
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

    /**
     * Applies the proposal [sendPrompt] just received immediately, skipping the
     * confirmation dialog entirely - only when the person turned on "apply
     * suggestions automatically" on [TvaAiSettingsPage]. Takes the same
     * reconnect-or-not branch the dialog's own confirm button takes, so the
     * dialog is simply never shown for an auto-applied proposal; a no-op
     * (including when the last response carried no proposal, or the toggle is
     * off) just leaves [pendingProposal] on screen for the person to apply by
     * hand, exactly as before this setting existed.
     */
    fun autoApplyIfEnabled() {
        if (!prefs.getBoolean(KEY_AUTO_APPLY, false)) return
        if (TvaAiChatSession.pendingUpdatedProfile == null) return
        if (state is ConnectionState.Connected) applyAndReconnect() else applyWithoutReconnect()
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
        TvaAiChatSession.messageInput = ""
        TvaAiChatSession.pendingProposal = null
        TvaAiChatSession.pendingUpdatedProfile = null
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
                // historyText is what actually gets stored as Gemini's OWN turn
                // below - starts as the plain reply, then gets a marker
                // appended once the proposal (if any) is validated, so this
                // one model turn carries both the natural-language reply the
                // user saw and the outcome Gemini itself needs to remember.
                var historyText = reply.text
                if (reply.proposal != null) {
                    when (val validation = studio.cluvex.aether.ai.AiProposalValidator.validateAndApply(profile, reply.proposal)) {
                        is AiProposalValidation.Accepted -> {
                            TvaAiChatSession.pendingProposal = reply.proposal
                            TvaAiChatSession.pendingUpdatedProfile = validation.updatedProfile
                            historyText += "\n\n$PROPOSAL_ACCEPTED_MARKER pending the user's confirmation to apply: " +
                                validation.changedSettings.joinToString(", ") + "."
                        }
                        is AiProposalValidation.Rejected -> {
                            // The one rejection reason that is not a mistake worth
                            // surfacing: the proposal, once resolved against the
                            // current profile, was not actually a change (most
                            // often because it, or something equivalent, was
                            // already applied earlier in this same chat). Showing
                            // that as an error every time Gemini repeats it is
                            // exactly the "تکراری" annoyance this fix targets - the
                            // history marker below is what actually stops the
                            // repeat; no need to also show a message for it.
                            val isNoOp = validation.reasons.singleOrNull() ==
                                studio.cluvex.aether.ai.AiProposalValidator.NO_OP_REASON
                            if (!isNoOp) {
                                messages.add(
                                    TvaAiMessage(
                                        fromUser = false,
                                        text = context.getString(R.string.tva_ai_proposal_rejected, validation.reasons.joinToString("؛ ")),
                                    ),
                                )
                            }
                            historyText += "\n\n$PROPOSAL_REJECTED_MARKER ${validation.reasons.joinToString("; ")} " +
                                "— do not propose this exact same change again unless the profile snapshot or new evidence has actually changed."
                        }
                    }
                }
                history.add(GeminiApiClient.ChatTurn(user = false, text = historyText))
                status = context.getString(R.string.tva_ai_response_received)
                statusIsError = false
            } catch (error: Exception) {
                showError(error.message ?: context.getString(R.string.tva_ai_generic_error))
                messages.add(TvaAiMessage(fromUser = false, text = error.message ?: context.getString(R.string.tva_ai_generic_error)))
            } finally {
                busy = false
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
            // Outside the try/finally above, not inside it (1.5.2): this call may
            // itself set busy=true and launch a second, separate coroutine for the
            // apply/reconnect (see applyAndReconnect). Calling it before the
            // finally block ran would have let that second busy=true be
            // immediately stomped back to false by this coroutine's own
            // `finally { busy = false }`, and the "response received" status line
            // above would have overwritten whatever reconnecting status it set.
            // Doing it after lets the automatic apply behave exactly like the
            // manual button: its own busy/status, uninterrupted by this call.
            autoApplyIfEnabled()
        }
    }

    fun analyzeLogs() {
        // 1.5.6: the full buffered log (up to ~800 lines), redacted the same
        // way, sent as a genuine attachment - not AiRedaction.digest's
        // 180-line/12,000-char excerpt pasted into the chat text. See
        // AiRedaction.fullLogAttachment and the matching GeminiApiClient
        // comments for why this reaches Gemini whole now instead of in a
        // fraction that used to get cut down twice more after this point.
        val redacted = AiRedaction.fullLogAttachment(
            fullLog = DiagnosticsLog.exportText(),
            ownApiKey = apiKey,
        )
        sendPrompt(
            text = context.getString(R.string.tva_ai_analyze_logs_prompt),
            optimize = false,
            logContext = redacted,
        )
    }

    fun beginApply() {
        val proposal = TvaAiChatSession.pendingProposal ?: return
        val validation = studio.cluvex.aether.ai.AiProposalValidator.validateAndApply(profile, proposal)
        when (validation) {
            is AiProposalValidation.Accepted -> {
                TvaAiChatSession.pendingUpdatedProfile = validation.updatedProfile
                showApplyDialog = true
            }
            is AiProposalValidation.Rejected -> showError(validation.reasons.joinToString("؛ "))
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
                        value = TvaAiChatSession.messageInput,
                        onValueChange = { TvaAiChatSession.messageInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.tva_ai_message_hint)) },
                        enabled = !busy,
                        maxLines = 4,
                    )
                    IconButton(
                        onClick = { sendPrompt(TvaAiChatSession.messageInput) },
                        enabled = !busy && TvaAiChatSession.messageInput.isNotBlank() && apiKey.isNotBlank(),
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

            val proposal = TvaAiChatSession.pendingProposal
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
            TvaAiChatSession.previousProfile?.let { old ->
                item {
                    TextButton(onClick = {
                        onProfileChange(old)
                        TvaAiChatSession.previousProfile = null
                        if (TvaAiChatSession.reconnectOnUndo && state !is ConnectionState.Idle && state !is ConnectionState.Error) {
                            TvaAiChatSession.reconnectOnUndo = false
                            busy = true
                            status = context.getString(R.string.tva_ai_reconnecting)
                            statusIsError = false
                            scope.launch {
                                try {
                                    // Same reasoning as applyAndReconnect(): ride the
                                    // profile being restored so the strict-kill-switch
                                    // check reflects it, not the session's stale flag.
                                    AetherController.disconnect(context, old)
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
