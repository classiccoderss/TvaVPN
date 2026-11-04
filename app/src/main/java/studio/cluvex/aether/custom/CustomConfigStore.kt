package studio.cluvex.aether.custom

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.KeyVault

/**
 * The saved custom configs and the chain the user picked.
 *
 * One sealed blob in its own preferences file: the configs hold server
 * credentials, so they are encrypted with the app's Keystore-backed vault key
 * ([KeyVault], AES-256-GCM, non-exportable) exactly like the other secrets, and
 * the backup rules already exclude every preferences file from any backup.
 * Deliberately NOT inside the connection profile: "Reset settings" resets the
 * tunnel's settings, it does not delete a user's imported servers.
 */
object CustomConfigStore {

    data class State(
        val configs: List<CustomConfig> = emptyList(),
        val activeId: String? = null,
        val mode: ChainMode = ChainMode.SOLO,
        /** Master switch: off = every session runs exactly as before this feature. */
        val enabled: Boolean = false,
        /**
         * Auto-select (1.8.0): after each speed test, switch to the fastest config
         * that answered; if the active one later fails to connect, try the
         * next-fastest instead of just reporting an error. See [promoteBest].
         */
        val autoSelect: Boolean = false,
    ) {
        val active: CustomConfig? get() = configs.firstOrNull { it.id == activeId }

        /** What the next session runs, or null when custom configs are not in use. */
        val selection: CustomSelection?
            get() = if (enabled) active?.let { CustomSelection(it, mode) } else null
    }

    private const val PREFS = "aether_custom_configs"
    private const val KEY = "state"
    private const val MAX_CONFIGS = 500
    private const val TAG = "custom-select"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context): State {
        if (loaded) return _state.value
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        val decoded = stored?.let { s ->
            runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()
                ?.let { KeyVault.open(it) }
                ?.let { decode(String(it, Charsets.UTF_8)) }
        }
        _state.value = decoded ?: State()
        loaded = true
        return _state.value
    }

    /** The selection a session should run, read fresh from storage. */
    fun selection(context: Context): CustomSelection? = load(context).selection

    fun add(context: Context, configs: List<CustomConfig>): State = update(context) { s ->
        val merged = (s.configs + configs).distinctBy { it.id }.takeLast(MAX_CONFIGS)
        // The first import of an empty list selects it, so the user sees it in use.
        s.copy(configs = merged, activeId = s.activeId ?: configs.firstOrNull()?.id)
    }

    fun replace(context: Context, config: CustomConfig): State = update(context) { s ->
        s.copy(configs = s.configs.map { if (it.id == config.id) config else it })
    }

    fun delete(context: Context, id: String): State = update(context) { s ->
        val rest = s.configs.filterNot { it.id == id }
        val active = if (s.activeId == id) null else s.activeId
        s.copy(configs = rest, activeId = active, enabled = s.enabled && active != null)
    }

    fun deleteAll(context: Context): State = update(context) { State(mode = it.mode) }

    fun select(context: Context, id: String): State = update(context) { it.copy(activeId = id) }

    fun setMode(context: Context, mode: ChainMode): State = update(context) { it.copy(mode = mode) }

    fun setEnabled(context: Context, enabled: Boolean): State =
        update(context) { it.copy(enabled = enabled && it.active != null) }

    fun setAutoSelect(context: Context, value: Boolean): State =
        update(context) { it.copy(autoSelect = value) }

    @Synchronized
    private fun update(context: Context, change: (State) -> State): State {
        load(context)
        val next = change(_state.value)
        _state.value = next
        val sealed = KeyVault.seal(encode(next).toByteArray(Charsets.UTF_8))
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sealed != null) {
            prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }
        // No vault key (a broken keystore): the state lives for this process only,
        // rather than writing server credentials to disk in the clear.
        return next
    }

    // ------------------------------------------------------------- codec

    internal fun encode(s: State): String = buildJsonObject {
        put("v", 1)
        s.activeId?.let { put("active", it) }
        put("mode", s.mode.name)
        put("enabled", s.enabled)
        put("autoSelect", s.autoSelect)
        put("configs", buildJsonArray {
            s.configs.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("protocol", c.protocol.name)
                    put("format", c.format.name)
                    put("source", c.source.name)
                    put("outbound", c.outbound)
                    put("raw", c.raw)
                    put("server", c.server)
                    put("locked", c.locked)
                    put("note", c.note)
                    put("expiresAt", c.expiresAt)
                    put("mobileOnly", c.mobileOnly)
                    put("addedAt", c.addedAt)
                    put("subscriptionId", c.subscriptionId)
                })
            }
        })
    }.toString()

    internal fun decode(text: String): State? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
        fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
        val configs = (o["configs"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            CustomConfig(
                id = c.s("id") ?: return@mapNotNull null,
                name = c.s("name").orEmpty(),
                protocol = ConfigProtocol.entries.firstOrNull { it.name == c.s("protocol") } ?: ConfigProtocol.OTHER,
                format = ConfigFormat.entries.firstOrNull { it.name == c.s("format") } ?: ConfigFormat.URI,
                source = ConfigSource.entries.firstOrNull { it.name == c.s("source") } ?: ConfigSource.LINK,
                outbound = c.s("outbound") ?: return@mapNotNull null,
                raw = c.s("raw").orEmpty(),
                server = c.s("server").orEmpty(),
                locked = c.b("locked"),
                note = c.s("note").orEmpty(),
                expiresAt = c.l("expiresAt"),
                mobileOnly = c.b("mobileOnly"),
                addedAt = c.l("addedAt"),
                subscriptionId = c.s("subscriptionId").orEmpty(),
            )
        }
        val active = o.s("active")?.takeIf { id -> configs.any { it.id == id } }
        return State(
            configs = configs,
            activeId = active,
            mode = ChainMode.fromStoredName(o.s("mode")),
            enabled = o.b("enabled") && active != null,
            autoSelect = o.b("autoSelect"),
        )
    }

    // ------------------------------------------------------------ auto-select

    /**
     * Short per-config timeout after a failed connect attempt (1.8.0): [promoteBest]
     * will not pick a config that just failed, even if its last speed-test result was
     * good, until this passes. Kept in memory only (not persisted): it describes the
     * current run, not a lasting fact about the config, and clears itself on its own
     * by simply expiring - there is nothing to clean up when the process restarts.
     */
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FAILOVER_COOLDOWN_MS = 5 * 60_000L

    fun markFailed(configId: String) {
        cooldownUntil[configId] = System.currentTimeMillis() + FAILOVER_COOLDOWN_MS
    }

    /**
     * Switches to the fastest config that answered its last speed test, if auto-select
     * is on and that config is not the one already active. A no-op otherwise: when
     * auto-select is off, when custom configs are off, when nothing has an OK result
     * yet, or when the best one IS the active one already.
     *
     * Called after every full speed-test sweep ([ConfigPingStore.testAll]) and after a
     * subscription refresh changes the config list ([SubscriptionStore.refresh]), so a
     * fresh pick is always based on fresh numbers - this function itself never runs a
     * test.
     */
    fun promoteBest(context: Context) {
        val s = load(context)
        if (!s.autoSelect || !s.enabled) return
        val now = System.currentTimeMillis()
        val results = ConfigPingStore.results.value
        val best = s.configs
            .asSequence()
            .filter { !it.isExpired(now) }
            .filter { (cooldownUntil[it.id] ?: 0L) <= now }
            .mapNotNull { cfg -> results[cfg.id]?.takeIf { it.ok }?.let { cfg to it.ms } }
            .minByOrNull { it.second }
            ?.first
        if (best != null && best.id != s.activeId) {
            DiagnosticsLog.i(TAG, "Auto-select: switching to \"${best.name}\" (${results[best.id]?.ms} ms).")
            select(context, best.id)
        }
    }

    // ------------------------------------------------------------ subscriptions

    /**
     * Merges a freshly-fetched subscription body into the stored configs (1.8.0).
     *
     * Configs are matched to their PREVIOUS copy by their raw link text (trimmed): a
     * subscription link is reparsed into a brand new random [CustomConfig.id] every
     * time, so matching by id would treat every refresh as "every config is new",
     * losing [CustomConfig.addedAt] and - more importantly - silently dropping
     * [CustomConfigStore.State.activeId] out from under a connected session. A config
     * no longer present in the fetched body is removed; one manually added by the
     * user (an empty [CustomConfig.subscriptionId]) is never touched by this.
     *
     * If the active config is the one that got removed, it is cleared rather than
     * guessed at: [promoteBest] (when auto-select is on) or the user picks what runs
     * next, this function only ever reflects what the subscription currently serves.
     */
    fun syncSubscription(context: Context, subscriptionId: String, parsed: List<CustomConfig>): State =
        update(context) { s ->
            val tagged = parsed.map { it.copy(subscriptionId = subscriptionId) }
            val oldByRaw = s.configs
                .filter { it.subscriptionId == subscriptionId }
                .associateBy { it.raw.trim() }
            val merged = tagged.map { new ->
                oldByRaw[new.raw.trim()]?.copy(
                    name = new.name,
                    protocol = new.protocol,
                    format = new.format,
                    outbound = new.outbound,
                    server = new.server,
                    locked = new.locked,
                    note = new.note,
                    expiresAt = new.expiresAt,
                    mobileOnly = new.mobileOnly,
                ) ?: new
            }
            val others = s.configs.filterNot { it.subscriptionId == subscriptionId }
            val finalConfigs = (others + merged).distinctBy { it.id }.takeLast(MAX_CONFIGS)
            val activeStillThere = finalConfigs.any { it.id == s.activeId }
            s.copy(
                configs = finalConfigs,
                activeId = if (activeStillThere) s.activeId else null,
                enabled = s.enabled && activeStillThere,
            )
        }
}

/**
 * What the running custom session looks like, for the home screen's pipeline row.
 * Published by the VPN service, cleared when the session ends.
 */
object CustomSession {
    data class Info(val configLabel: String, val mode: ChainMode)

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    fun set(info: Info?) {
        _info.value = info
    }
}