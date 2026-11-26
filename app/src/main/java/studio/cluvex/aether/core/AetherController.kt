package studio.cluvex.aether.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.GoolMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.vpn.AetherVpnService

/**
 * App-wide singleton that (a) publishes the live [ConnectionState] to the UI and
 * (b) sends connect/disconnect intents to [AetherVpnService].
 */
object AetherController {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Epoch millis of when the current session became Connected, or null. */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /** IP + country shown in the UI (exit server when connected, operator when not). */
    private val _ipInfo = MutableStateFlow<IpEndpoint?>(null)
    val ipInfo: StateFlow<IpEndpoint?> = _ipInfo.asStateFlow()

    /** True while an IP lookup is in flight (drives the “…” placeholder). */
    private val _ipLoading = MutableStateFlow(false)
    val ipLoading: StateFlow<Boolean> = _ipLoading.asStateFlow()

    /** Called by the service to broadcast state changes. */
    fun setState(newState: ConnectionState) {
        _state.value = newState
        when (newState) {
            is ConnectionState.Connected ->
                if (_connectedSince.value == null) _connectedSince.value = System.currentTimeMillis()
            is ConnectionState.Reconnecting -> {
                // Keep the running timer during a transient reconnect.
            }
            else -> _connectedSince.value = null
        }
        // The IP on screen must always belong to the state it is shown in. This is
        // done here, not in the UI, because the UI may not be on screen when the
        // state changes (disconnecting from the notification, the tile or the
        // widget) and would otherwise keep showing the old tunnel IP.
        when (newState) {
            is ConnectionState.Connected ->
                if (_ipInfo.value?.viaTunnel == false) _ipInfo.value = null
            is ConnectionState.Idle,
            is ConnectionState.Disconnecting,
            is ConnectionState.Error,
            is ConnectionState.Launching,
            ->
                if (_ipInfo.value?.viaTunnel == true) _ipInfo.value = null
            else -> Unit
        }
    }

    fun setIpInfo(info: IpEndpoint?) {
        _ipInfo.value = info
    }

    /**
     * Sets the tunnel exit IP only when the badge doesn't already show a
     * tunnel IP for this session.
     */
    fun offerTunnelIpInfo(info: IpEndpoint) {
        if (_ipInfo.value?.viaTunnel == true) return
        // A self-test that finishes after the session has already gone down must
        // not put the old tunnel IP back on screen.
        val now = _state.value
        if (now is ConnectionState.Idle ||
            now is ConnectionState.Disconnecting ||
            now is ConnectionState.Error
        ) {
            return
        }
        _ipInfo.value = info
    }

    fun setIpLoading(loading: Boolean) {
        _ipLoading.value = loading
    }

    /**
     * Returns a consent Intent if the user must still grant VPN permission,
     * or null if permission was already granted.
     */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun connect(context: Context, profile: ConnectionProfile) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_CONNECT
            putExtra(AetherVpnService.EXTRA_PROFILE, ProfileCodec.encode(profile))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Asks [AetherVpnService] to reconcile the screen with reality.
     *
     * ROOT CAUSE of "the notification says connected but the app says it is
     * not": [state] lives in this object, i.e. in ONE process, while the
     * tunnel does not have to. On MIUI/HyperOS the launcher task can be removed
     * (or the process frozen and recreated) while the engine and its TUN stay
     * up, and the next Activity comes back with this object newly constructed -
     * its initial value, [ConnectionState.Idle]. The screen then says "not
     * connected" over a tunnel that is up, and the user's next tap dials it
     * again. Neither side can be trusted to know on its own, so the service -
     * the only thing that can prove whether a session exists - is asked to
     * publish what it sees and, when there is nothing left but the user's
     * saved "stay connected" wish, to restore the session instead.
     *
     * Safe to send while a session is live: the service only re-publishes the
     * state in that case, it never re-dials.
     */
    fun syncState(context: Context) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_SYNC_STATE
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * [profile] is the CURRENT settings snapshot (e.g. the UI's in-memory
     * profile), not necessarily the one the active session was started
     * with.
     *
     * STRICT-KILL-SWITCH STALE-FLAG FIX: without this, the service decided
     * whether to lock down purely from the [ConnectionProfile] it captured
     * at connect time ([AetherVpnService.lastProfile]). Turning Strict Kill
     * Switch ON (or OFF) in Settings WHILE ALREADY CONNECTED never reached
     * that in-memory copy - only ProfileStore on disk changed - so
     * Disconnect kept honoring whatever the switch was set to when the
     * tunnel was first connected. That is why toggling it on, then
     * disconnecting, looked like a no-op kill switch: a brief drop (the
     * normal teardown) and then the internet simply came back, instead of
     * staying locked down as the switch now promised. Carrying the live
     * profile here lets the service re-check the CURRENT flag at the moment
     * Disconnect is actually pressed.
     */
    fun disconnect(context: Context, profile: ConnectionProfile? = null) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_DISCONNECT
            if (profile != null) {
                putExtra(AetherVpnService.EXTRA_PROFILE, ProfileCodec.encode(profile))
            }
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

/**
 * Serialises a [ConnectionProfile] into a compact `key=value` list (one pair per
 * line) for Intent transport. This format is forward/backward tolerant: unknown
 * keys are ignored and missing keys fall back to the model defaults, so old and
 * new builds can decode each other's payloads without crashing.
 */
object ProfileCodec {
    fun encode(p: ConnectionProfile): String = buildList {
        add("backend=${p.backend.name}")
        add("exitRegion=${p.exitRegion}")
        add("protocol=${p.protocol.name}")
        add("scan=${p.scanMode.name}")
        add("ip=${p.ipVersion.name}")
        add("quick=${p.quickReconnect}")
        add("h2=${p.masqueHttp2}")
        add("share=${p.lanShare}")
        add("noize=${p.noize.name}")
        add("endpoint=${p.endpointMode.name}")
        add("peer=${p.manualPeer}")
        add("range=${p.manualRange}")
        add("keepalive=${p.keepalive}")
        add("fragment=${p.fragment}")
        add("ech=${p.ech}")
        add("echConfig=${p.echConfig}")
        add("mtu=${p.mtu}")
        add("proxy=${p.proxyMode}")
        add("split=${p.splitMode.name}")
        add("splitApps=${p.splitApps.joinToString(",")}")
        // Added in 1.2.4 (feature parity)
        add("kill=${p.killSwitch}")
        add("strictKill=${p.strictKillSwitch}")
        add("v6leak=${p.ipv6LeakProtection}")
        add("smartRe=${p.smartReconnect}")
        add("reLimit=${p.reconnectRetryLimit}")
        add("fSize=${p.fragmentSize}")
        add("fDelay=${p.fragmentDelay}")
        add("noDataCheck=${p.noDataCheck}")
        add("tlsGroups=${p.tlsGroups}")
        // Added in 1.5.0 (engine core 2.3.0)
        add("tlsCiphers=${p.tlsCiphers}")
        add("noGrease=${p.disableGrease}")
        add("tlsVerify=${p.tlsVerify}")
        add("goolMode=${p.goolMode.name}")
        add("valSecs=${p.validateSecs}")
        add("recSecs=${p.reconnectSecs}")
        add("noProfRetry=${p.noProfileRetry}")
        add("coreLog=${p.coreLogLevel.name}")
        add("blockedApps=${p.blockedApps.joinToString(",")}")
        // ---------------------------------------------------------------
        // 1.2.7 BUG FIX: everything below was ALREADY in the settings screen,
        // already persisted, already turned into engine flags by
        // ConnectionProfile.toArgs()/toEnv() - and never reached the engine,
        // because the service reconstructs its profile from THIS codec and the
        // codec did not carry any of it. So the in-tunnel DNS servers, both
        // routing-rule lists, domain sniffing, the upstream proxy, identity
        // replacement and the whole Zero Trust section were silently ignored at
        // runtime: the user set them, the UI kept them, and the engine was
        // launched without a single one of the corresponding flags.
        //
        // The two Zero Trust SECRETS are deliberately still absent. They live in
        // the hardware-backed SecretStore and the service reads them from there
        // itself (see AetherVpnService.hydrateSecrets), so a credential never
        // travels inside an Intent.
        // ---------------------------------------------------------------
        add("dns=${p.dnsServers}")
        add("routeBlock=${p.routeBlock}")
        add("routeDirect=${p.routeDirect}")
        add("bypassIran=${p.bypassIran}")
        add("blockAds=${p.blockAds}")
        add("sniff=${p.routeSniff}")
        add("sniffMs=${p.routeSniffMs}")
        add("upstream=${p.upstreamProxy}")
        add("reprovision=${p.autoReprovision}")
        add("fastEndpoint=${p.fastEndpointOnly}")
        add("teamAuth=${p.teamAuth.name}")
        add("team=${p.team}")
        add("accessId=${p.accessClientId}")
        add("accessEmail=${p.accessEmail}")
        add("gateway=${p.gateway}")
    }.joinToString("\n")

    fun decode(raw: String?): ConnectionProfile {
        if (raw.isNullOrBlank()) return ConnectionProfile()
        // Backward compatibility: the 1.0/1.1 codec used a single pipe-delimited
        // line with no keys. Detect and decode that legacy shape.
        if (!raw.contains('=') && raw.contains('|')) return decodeLegacy(raw)

        val map = raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        val d = ConnectionProfile()
        return runCatching {
            ConnectionProfile(
                // fromStoredName, NOT enumOr: it migrates the retired backend
                // names. The single-hop PSIPHON becomes the chained mode so an
                // existing profile keeps the exit the user chose; the Tor names
                // removed with the backend resolve to plain Aether.
                backend = TransportBackend.fromStoredName(map["backend"]) ?: d.backend,
                exitRegion = map["exitRegion"] ?: d.exitRegion,
                protocol = map["protocol"]?.let { enumOr<Protocol>(it) } ?: d.protocol,
                scanMode = map["scan"]?.let { enumOr<ScanMode>(it) } ?: d.scanMode,
                ipVersion = map["ip"]?.let { enumOr<IpVersion>(it) } ?: d.ipVersion,
                quickReconnect = map["quick"]?.toBooleanStrictOrNull() ?: d.quickReconnect,
                masqueHttp2 = map["h2"]?.toBooleanStrictOrNull() ?: d.masqueHttp2,
                lanShare = map["share"]?.toBooleanStrictOrNull() ?: d.lanShare,
                noize = map["noize"]?.let { enumOr<Noize>(it) } ?: d.noize,
                endpointMode = map["endpoint"]?.let { enumOr<EndpointMode>(it) } ?: d.endpointMode,
                manualPeer = map["peer"] ?: d.manualPeer,
                manualRange = map["range"] ?: d.manualRange,
                keepalive = map["keepalive"]?.toIntOrNull() ?: d.keepalive,
                fragment = map["fragment"]?.toBooleanStrictOrNull() ?: d.fragment,
                ech = map["ech"]?.toBooleanStrictOrNull() ?: d.ech,
                echConfig = map["echConfig"] ?: d.echConfig,
                mtu = map["mtu"]?.toIntOrNull()
                    ?.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX)
                    ?: d.mtu,
                proxyMode = map["proxy"]?.toBooleanStrictOrNull() ?: d.proxyMode,
                splitMode = map["split"]?.let { enumOr<SplitMode>(it) } ?: d.splitMode,
                splitApps = map["splitApps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.splitApps,
                killSwitch = map["kill"]?.toBooleanStrictOrNull() ?: d.killSwitch,
                strictKillSwitch = map["strictKill"]?.toBooleanStrictOrNull() ?: d.strictKillSwitch,
                ipv6LeakProtection = map["v6leak"]?.toBooleanStrictOrNull() ?: d.ipv6LeakProtection,
                smartReconnect = map["smartRe"]?.toBooleanStrictOrNull() ?: d.smartReconnect,
                reconnectRetryLimit = map["reLimit"]?.toIntOrNull() ?: d.reconnectRetryLimit,
                fragmentSize = map["fSize"] ?: d.fragmentSize,
                fragmentDelay = map["fDelay"] ?: d.fragmentDelay,
                noDataCheck = map["noDataCheck"]?.toBooleanStrictOrNull() ?: d.noDataCheck,
                tlsGroups = map["tlsGroups"] ?: d.tlsGroups,
                tlsCiphers = map["tlsCiphers"] ?: d.tlsCiphers,
                disableGrease = map["noGrease"]?.toBooleanStrictOrNull() ?: d.disableGrease,
                tlsVerify = map["tlsVerify"]?.toBooleanStrictOrNull() ?: d.tlsVerify,
                goolMode = map["goolMode"]?.let { enumOr<GoolMode>(it) } ?: d.goolMode,
                validateSecs = map["valSecs"]?.toIntOrNull() ?: d.validateSecs,
                reconnectSecs = map["recSecs"]?.toIntOrNull() ?: d.reconnectSecs,
                noProfileRetry = map["noProfRetry"]?.toBooleanStrictOrNull() ?: d.noProfileRetry,
                coreLogLevel = map["coreLog"]?.let { enumOr<CoreLogLevel>(it) } ?: d.coreLogLevel,
                blockedApps = map["blockedApps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.blockedApps,
                // See the note in encode(): these used to be dropped here.
                dnsServers = map["dns"] ?: d.dnsServers,
                routeBlock = map["routeBlock"] ?: d.routeBlock,
                routeDirect = map["routeDirect"] ?: d.routeDirect,
                bypassIran = map["bypassIran"]?.toBooleanStrictOrNull() ?: d.bypassIran,
                blockAds = map["blockAds"]?.toBooleanStrictOrNull() ?: d.blockAds,
                routeSniff = map["sniff"]?.toBooleanStrictOrNull() ?: d.routeSniff,
                routeSniffMs = map["sniffMs"]?.toIntOrNull() ?: d.routeSniffMs,
                upstreamProxy = map["upstream"] ?: d.upstreamProxy,
                autoReprovision = map["reprovision"]?.toBooleanStrictOrNull() ?: d.autoReprovision,
                fastEndpointOnly = map["fastEndpoint"]?.toBooleanStrictOrNull() ?: d.fastEndpointOnly,
                teamAuth = map["teamAuth"]?.let { enumOr<TeamAuth>(it) } ?: d.teamAuth,
                team = map["team"] ?: d.team,
                accessClientId = map["accessId"] ?: d.accessClientId,
                accessEmail = map["accessEmail"] ?: d.accessEmail,
                gateway = map["gateway"]?.toBooleanStrictOrNull() ?: d.gateway,
            )
        }.getOrDefault(d)
    }

    private fun decodeLegacy(raw: String): ConnectionProfile {
        val parts = raw.split("|")
        if (parts.size < 5) return ConnectionProfile()
        return runCatching {
            ConnectionProfile(
                protocol = Protocol.valueOf(parts[0]),
                scanMode = ScanMode.valueOf(parts[1]),
                ipVersion = IpVersion.valueOf(parts[2]),
                quickReconnect = parts[3].toBoolean(),
                masqueHttp2 = parts[4].toBoolean(),
                lanShare = parts.getOrNull(5)?.toBoolean() ?: false,
            )
        }.getOrDefault(ConnectionProfile())
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()
}
