package studio.cluvex.aether.custom

import android.content.Context
import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.KeyVault

/**
 * One subscription URL the user added: a link that, when fetched, returns a list of
 * configs (the same body format [ConfigImporter.fromText] already accepts for a
 * pasted subscription link - this is that same mechanism, just remembered and
 * refreshed on its own instead of a one-off paste).
 */
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val addedAt: Long = 0L,
    val lastRefreshAt: Long = 0L,
    /** How many configs the last successful refresh found. */
    val lastCount: Int = 0,
    /** The last refresh's failure, or "" when it succeeded (or never ran yet). */
    val lastError: String = "",
)

/**
 * Stored subscriptions and their refresh (1.8.0).
 *
 * ## What "automatic" means here
 *
 * There is no background job: this project has no WorkManager (and adding one just
 * for this would be a new dependency the rest of the app does not need). A
 * subscription refreshes when [refreshDue] is called - [MainActivity] does this once
 * per app open - and whenever the user taps Refresh. That is enough to keep a
 * subscription current for anyone who opens the app at least as often as
 * [REFRESH_INTERVAL_MS], which is the normal way a VPN app gets used, but it will NOT
 * refresh while the app is closed the whole time, the way a true background job would.
 *
 * ## Storage
 *
 * Same shape as [CustomConfigStore]: a JSON array, sealed with [KeyVault] and kept in
 * its own SharedPreferences file, loaded once per process and cached in [subscriptions].
 */
object SubscriptionStore {
    private const val PREFS = "aether_subscriptions"
    private const val KEY = "state"
    private const val MAX_SUBSCRIPTIONS = 20
    private const val TAG = "subscription"

    /** Default refresh interval. Not user-configurable, kept simple - see the class doc. */
    const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context): List<Subscription> {
        if (loaded) return _subscriptions.value
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        val decoded = stored?.let { text ->
            runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()
                ?.let { KeyVault.open(it) }
                ?.let { decode(String(it, Charsets.UTF_8)) }
        }
        _subscriptions.value = decoded.orEmpty()
        loaded = true
        return _subscriptions.value
    }

    fun add(context: Context, name: String, url: String): Subscription {
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { url },
            url = url,
            addedAt = System.currentTimeMillis(),
        )
        update(context) { (it + sub).takeLast(MAX_SUBSCRIPTIONS) }
        return sub
    }

    fun remove(context: Context, id: String) {
        update(context) { it.filterNot { sub -> sub.id == id } }
        // The configs it brought in are not useful without the subscription that
        // explains where they came from - keeping them would make removing a
        // subscription look like it silently did nothing.
        CustomConfigStore.syncSubscription(context, id, emptyList())
    }

    /**
     * Fetches [subscription]'s URL and merges the result into [CustomConfigStore].
     * Records the outcome either way and never throws - a subscription that is
     * temporarily unreachable should not crash whatever triggered the refresh.
     */
    suspend fun refresh(context: Context, subscription: Subscription) {
        val app = context.applicationContext
        try {
            val parsed = ConfigImporter.fromText(app, subscription.url, ConfigSource.LINK)
            CustomConfigStore.syncSubscription(app, subscription.id, parsed)
            recordResult(app, subscription.id, parsed.size, "")
            val mine = CustomConfigStore.load(app).configs.filter { it.subscriptionId == subscription.id }
            if (mine.isNotEmpty()) {
                // Fresh numbers before promoteBest acts on the new list - testAll
                // itself calls promoteBest once it finishes, see ConfigPingStore.
                ConfigPingStore.testAll(app, mine)
            } else {
                CustomConfigStore.promoteBest(app)
            }
        } catch (e: Exception) {
            DiagnosticsLog.w(TAG, "Subscription refresh failed (${subscription.name}): ${e.message}")
            recordResult(app, subscription.id, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Refreshes every stored subscription now, regardless of [REFRESH_INTERVAL_MS]. */
    suspend fun refreshAll(context: Context) {
        load(context).forEach { refresh(context, it) }
    }

    /** Refreshes only the subscriptions whose interval has elapsed. */
    suspend fun refreshDue(context: Context) {
        val now = System.currentTimeMillis()
        load(context)
            .filter { now - it.lastRefreshAt >= REFRESH_INTERVAL_MS }
            .forEach { refresh(context, it) }
    }

    private fun recordResult(context: Context, id: String, count: Int, error: String) {
        update(context) { list ->
            list.map {
                if (it.id == id) {
                    it.copy(lastRefreshAt = System.currentTimeMillis(), lastCount = count, lastError = error)
                } else {
                    it
                }
            }
        }
    }

    @Synchronized
    private fun update(context: Context, change: (List<Subscription>) -> List<Subscription>) {
        load(context)
        val next = change(_subscriptions.value)
        _subscriptions.value = next
        val sealed = KeyVault.seal(encode(next).toByteArray(Charsets.UTF_8))
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sealed != null) {
            prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }
    }

    private fun encode(list: List<Subscription>): String = buildJsonArray {
        list.forEach { sub ->
            add(
                buildJsonObject {
                    put("id", sub.id)
                    put("name", sub.name)
                    put("url", sub.url)
                    put("addedAt", sub.addedAt)
                    put("lastRefreshAt", sub.lastRefreshAt)
                    put("lastCount", sub.lastCount)
                    put("lastError", sub.lastError)
                },
            )
        }
    }.toString()

    private fun decode(text: String): List<Subscription>? {
        val arr = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            Subscription(
                id = s("id") ?: return@mapNotNull null,
                name = s("name").orEmpty(),
                url = s("url") ?: return@mapNotNull null,
                addedAt = l("addedAt"),
                lastRefreshAt = l("lastRefreshAt"),
                lastCount = i("lastCount"),
                lastError = s("lastError").orEmpty(),
            )
        }
    }
}
