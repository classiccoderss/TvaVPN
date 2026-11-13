package studio.cluvex.aether.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.GeminiApiClient
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.data.SecretStore
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.theme.AetherError
import studio.cluvex.aether.ui.theme.AetherMint
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * One-way fingerprint of a (key, model) pair, used only to remember that
 * *this exact* API key + model combination already passed a connection
 * test - never to recover the key itself. Kept in a plain (unencrypted)
 * pref since a hash leaks nothing about the underlying secret.
 *
 * This used to live in [TvaAiPage] alongside the rest of the Gemini
 * configuration UI; it moved here with that UI (see [TvaAiSettingsPage])
 * so [TvaAiPage] can be chat only.
 */
private fun testFingerprint(apiKey: String, model: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$apiKey\u0000$model".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** Total attempts for a Gemini call: the first try plus short-delay retries on a flaky main tunnel. */
private const val MAX_ROUTE_ATTEMPTS = 3

/** Brief pause before retrying a Gemini request over the same main-tunnel SOCKS port. */
private const val ROUTE_RETRY_DELAY_MS = 1_200L

/**
 * The Gemini API key, model ID, and connection test - everything needed to
 * set TVA AI up, split out of [TvaAiPage] so that page can be chat only.
 * This is the only place the key and model are edited; [TvaAiPage] just
 * reads whatever is saved here.
 *
 * Reachable from the Settings home list (its own row, next to the "TVA AI"
 * chat row) and from a gear icon on the chat page itself.
 */
@Composable
fun TvaAiSettingsPage(
    state: ConnectionState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("tva_ai_settings", Context.MODE_PRIVATE) }
    val secretStore = remember(context) { SecretStore(context) }
    var apiKey by remember { mutableStateOf(secretStore.read(SecretStore.GEMINI_API_KEY)) }
    // resolveStoredModel, not the raw pref: a device that saved
    // "gemini-3.8-flash" back when that was still the default would
    // otherwise keep reading it from prefs forever and showing it here as
    // if it were still a live option - see GeminiApiClient.RETIRED_STORED_MODELS.
    var model by remember {
        mutableStateOf(GeminiApiClient.resolveStoredModel(prefs.getString("gemini_model", null))).also {
            // Write the corrected value straight back so it sticks even if
            // the user never touches this field or taps Save.
            prefs.edit().putString("gemini_model", it.value).apply()
        }
    }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var verifiedFingerprint by remember { mutableStateOf(prefs.getString("gemini_tested_fingerprint", null)) }
    val isVerified = apiKey.isNotBlank() && verifiedFingerprint == testFingerprint(apiKey.trim(), model.trim())
    var status by remember { mutableStateOf(context.getString(R.string.tva_ai_ready_hint)) }
    var statusIsError by remember { mutableStateOf(false) }

    fun showError(message: String) {
        status = message
        statusIsError = true
    }

    fun persistSettings(): Boolean {
        val normalizedKey = apiKey.trim()
        val normalizedModel = model.trim()
        if (!normalizedModel.matches(Regex("^[A-Za-z0-9._-]{1,80}$"))) {
            showError(context.getString(R.string.tva_ai_model_invalid))
            return false
        }
        if (normalizedKey.any { it.code !in 33..126 }) {
            showError(context.getString(R.string.tva_ai_key_invalid))
            return false
        }
        secretStore.write(SecretStore.GEMINI_API_KEY, normalizedKey)
        if (secretStore.read(SecretStore.GEMINI_API_KEY) != normalizedKey) {
            showError(context.getString(R.string.tva_ai_key_save_failed))
            return false
        }
        prefs.edit().putString("gemini_model", normalizedModel).apply()
        return true
    }

    /**
     * The local SOCKS port of the user's already-connected main VPN tunnel -
     * same reasoning as the identical helper in [TvaAiPage]: the connection
     * test is sent through the main tunnel, never a route of its own.
     */
    fun mainTunnelSocksPort(): Int? {
        val connected = state as? ConnectionState.Connected ?: return null
        val addr = connected.socksAddr
        val colon = addr.lastIndexOf(':')
        if (colon <= 0) return null
        return addr.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    }

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

    fun testApi() {
        if (apiKey.isBlank()) {
            showError(context.getString(R.string.tva_ai_missing_key))
            return
        }
        if (!persistSettings()) return
        busy = true
        status = context.getString(R.string.tva_ai_route_starting)
        statusIsError = false
        scope.launch {
            try {
                status = withResilientGeminiRoute { port ->
                    GeminiApiClient.testConnection(apiKey.trim(), model.trim(), port)
                }
                statusIsError = false
                val fingerprint = testFingerprint(apiKey.trim(), model.trim())
                verifiedFingerprint = fingerprint
                prefs.edit().putString("gemini_tested_fingerprint", fingerprint).apply()
            } catch (error: Exception) {
                showError(error.message ?: context.getString(R.string.tva_ai_generic_error))
            } finally {
                busy = false
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.tva_ai_settings_title),
        onBack = onBack,
        modifier = modifier,
        icon = Icons.Rounded.AutoAwesome,
        accent = AetherViolet,
        subtitle = stringResource(R.string.tva_ai_ready_hint),
    ) {
        settingsSection {
            GroupCaption(stringResource(R.string.tva_ai_configuration))
            SettingsGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.tva_ai_api_key)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showKey) stringResource(R.string.tva_ai_hide_key) else stringResource(R.string.tva_ai_show_key),
                                )
                            }
                        },
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.tva_ai_model)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (persistSettings()) {
                                    status = if (apiKey.isBlank()) context.getString(R.string.tva_ai_key_cleared) else context.getString(R.string.tva_ai_saved)
                                    statusIsError = false
                                }
                            },
                            enabled = !busy && model.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.tva_ai_save)) }
                        // Once this exact key+model pair has passed a
                        // connection test, keep that confirmation instead of
                        // asking the user to re-run it on every visit; the
                        // button comes back only if they change either
                        // field, since that then becomes a fresh, untested
                        // pair.
                        if (isVerified) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = AetherMint,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    stringResource(R.string.tva_ai_verified),
                                    color = AetherMint,
                                    modifier = Modifier.padding(start = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            Button(
                                onClick = ::testApi,
                                enabled = !busy && apiKey.isNotBlank() && model.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.tva_ai_test), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    Text(
                        status,
                        color = if (statusIsError) AetherError else AetherMint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    )
                }
            }
        }
        settingsSection {
            SettingsGroup {
                Text(
                    stringResource(R.string.tva_ai_route_notice),
                    color = OnDarkMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }
        }
    }
}
