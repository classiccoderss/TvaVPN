# 1.2.7 implementation map

- `model/TransportBackend.kt`: backend enum - `AETHER` and the chained
  `AETHER_PSIPHON` - plus the `usesAetherEngine`, `usesExternal`, `isChained` and
  `pipelineLabel` helpers. `ExternalKind` splits "which external stack" from "is
  it chained". `AETHER_TOR` / `ExternalKind.TOR` were removed; `fromStoredName`
  maps the retired Tor names onto plain `AETHER` and `PSIPHON` onto
  `AETHER_PSIPHON`.
- `model/Profile.kt`: persisted backend and ISO exit region.
- `data/ProfileStore.kt`: DataStore migration-safe persistence (stores enum names).
- `core/AetherController.kt`: backward-compatible Intent codec fields.
- `core/TunnelConfig.kt`: `CHAIN_SOCKS_PORT` (1825) and `PSIPHON_SOCKS_PORT`
  (1827). Tor's `TOR_SOCKS_PORT` / `TOR_DNS_PORT` (1822 / 1823) are gone.
- `core/Diagnostics.kt`: `graceMs` parameter, `EXTERNAL_GRACE_MS` (150 s) for
  chained Psiphon sessions, and `runProxyStage()` - the chained stage-1 gate
  (port + handshake + real outbound TCP, no geo lookup, no self-test circles).
- `core/ShareBridge.kt`: relays into a configurable `upstreamPort` instead of a
  hardcoded 1819, so proxy mode and LAN sharing follow the chain's exit.
- `transport/PsiphonTransport.kt`: Psiphon lifecycle, `UpstreamProxyUrl` for the
  chain, configurable local SOCKS port, real bound port reported to the caller,
  automatic-exit + datastore-reset retry, own datastore directory.
- `transport/TorTransport.kt`, `transport/TorSocksFront.kt`: **deleted**
  with the rest of the Tor runtime (see docs/TRANSPORT_BACKENDS.md). Both paths are
  listed in `.github/removed-sources.txt` so a build over an older checkout purges
  the orphans.
- `transport/ExternalTransport.kt`: factory that derives the upstream URL and the
  listen port from the profile's backend.
- `transport/ExitRegions.kt`: expanded country list with flag-emoji labels
  (`NetProbe.flagEmoji`), plus a flag-free `name()` for logs.
- `vpn/AetherVpnService.kt`: common TUN/health/teardown orchestration for all
  backends; `connectExternal` now drives the two-stage chain, `connectAetherStage`
  runs stage 1 through the existing ladders with `stageOnly = true`, and
  `startTun2Socks` / `writeHevConfig` take the SOCKS port as a parameter.
- `ui/AdvancedPanel.kt`: backend and exit-country selectors, per-backend helper
  text, capability-based enablement.
- `scripts/build-tor.sh` and its release-workflow step: **deleted**.


## 1.3.0 additions (engine core 2.0.0)

- `transport/TorSocksWire.kt`: the byte-level SOCKS5 UDP and DNS-over-TCP framing,
  pure and total, with `app/src/test/.../TorSocksWireTest.kt` behind it. Split out of
  the front so the one part of the Tor path that fails invisibly can be tested at
  all; `TorSocksFront` keeps the sockets, threads and counters.
- `transport/TorCountries.kt`: the short bridge-country list for bridgedb, separate
  from `ExitRegions` because it answers the opposite question.
- `model/Profile.kt`: `torCountry`, `torDirectSecs` and `torCheck` with strict
  sanitizers, emitted into `toEnv()` only when Tor runs and only when they differ
  from the engine's defaults.
- `model/TransportBackend.kt`: six modes and `TorMode` (CHAIN/ONLY/REVERSE); every port and pipeline
  decision is a computed property of the backend, so no caller can pair a mode with
  the wrong port.
- `transport/TorSocksFront.kt` (new): the UDP-capable SOCKS front Tor needs on
  Android. See the file header for why a Tor listener cannot be handed to
  hev-socks5-tunnel directly.
- `core/TunnelConfig.kt`: `TOR_SOCKS_PORT` 1820, `TOR_FRONT_PORT` 1821.
- `model/Profile.kt`: `Protocol.MIM`, `TorBridges`, `torBridgeLines` +
  `sanitizedBridges()`; `toArgs()` emits the Tor flags first and returns early in
  `--tor-only`, where the WARP flags describe work nothing would read.
- `core/AetherProcess.kt`: Tor directory cache and pluggable-transport paths.
- `vpn/AetherVpnService.kt`: `connectTor()`, the Tor bootstrap budget, the stage
  backend fix, and the front's teardown ordering.
- `transport/ExternalTransport.kt`: Psiphon's upstream port follows the backend.
- `ai/AiPatch.kt`, `ai/AiTopic.kt`, `ai/AiGate.kt`: Tor as read-only AI context.
- `scripts/build-natives.sh`, `.github/workflows/build.yml`: `--features tor`,
  binary verification, and the lyrebird target.


## UI map (home screen and connection card)

- `ui/components/GlowCycle.kt` (new): the shared travelling-light engine. One
  `animateFloat` from 0 to the number of palette colours drives both the lap phase
  and the lap colour, and `drawGlowCycle()` draws the equaliser bands along any
  path. Used by the card edge and the connect button, so they cannot drift apart.
- `ui/components/ConnectionCard.kt`: the three-column protocol strip became three
  full-width slides (`MetaSlides` / `MetaSlide`) inside the same card, the new
  `PingStrength` waveform lives on the latency slide, the card holds the last good
  ping reading, and `glassEdge` now delegates its band drawing to `GlowCycle`
  (the duplicate local `TWO_PI` and band table were removed with it).
- `ui/components/ConnectButton.kt`: one 96 dp bolt with an additive core when
  connected, plus `cycleRing` - the card's light show drawn around the disc while
  connected.
- `ui/HomeScreen.kt`: screen padding 32 -> 18 dp, the gap under the title
  28 -> 10 dp and the gap under the button 28 -> 6 dp, which pays for the card's
  extra slides without pushing the block off the fold.
- `res/values*/strings.xml`: `meta_ping_strength` and `ping_quality_*` in English
  and Persian.

## 1.4.0
- Engine rebased onto core 2.1.0. New patch in `native/aether/aether/src/lib.rs`:
  `tor-only-psiphon-chain` (lets `--tor-only` carry the engine's Psiphon).
- The Psiphon AAR was removed; Psiphon is the engine's (psiphon-tunnel-core
  console client, built from source by `native/aether/psiphon-build.sh`).


## 1.5.0 additions (engine core 2.3.0)

- Engine rebased onto core 2.3.0; details and hand resolutions (lib.rs, psiphon.rs, socks.rs, Cargo.lock) in `native/aether/.upstream-baseline/README.txt`.
- `app/build.gradle.kts`: versionName 1.5.0, versionCode 18; `PATCHLEVEL` 1.5.0.
- Tor over an upstream proxy: `native/aether/vendor/tor-chanmgr` (relay TLS without SNI when `AETHER_TOR_LINK_SNI=off`, set by `Profile.toEnv` whenever Tor has an upstream) and `native/aether/vendor/tor-dirclient` (20 s BEGIN_DIR / 20 s read-idle / 180 s total directory timeouts), both arti 0.46.0, wired in by the `tor-slow-chain` block in `native/aether/aether/Cargo.toml`. If a core upgrade moves arti off 0.46, cargo reports the patch as unused and the vendored copies must be refreshed.
- `native/aether/aether/src/tor.rs` (`tor-cache-seed`): bootstrap attempts share a snapshot of the freshest directory cache.
- `custom/XrayConfigBuilder.kt`: chain-level `uplinkOnly` 10 s / `downlinkOnly` 30 s.
- Clipboard import: `ui/components/LtrInput.kt` (`replaceKey`), `ui/settings/CustomConfigsPage.kt`, `custom/ConfigParser.kt` (`cleanInput`, `extractLinks`), `custom/ConfigImporter.kt`.
- `vpn/AetherVpnService.kt`: Psiphon-only start rethrows `CancellationException`.
- Real delay test: new `custom/RealDelay.kt` (one Xray process per batch, password-protected loopback SOCKS inbound per config, HTTP `generate_204` through it); `custom/ConfigPing.kt` (TLS probe and trust-all `TrustManager` removed, `diagnose`, `NO_REPLY` / `INVALID`); `custom/ConfigPingStore.kt` (serialised runs, logged results); `custom/XrayConfigBuilder.kt` (`directOutbound`, `directDns`); `ui/settings/CustomConfigsPage.kt` and `res/values*/strings.xml` (`cc_ping_noreply`, `cc_ping_invalid`).
- Security fixes from `docs/SECURITY_AUDIT_1.5.0.md`: `custom/XrayProcess.kt` (`redact` masks UUIDs, host names, IPv6, IPv4 /16), `custom/ConfigImporter.kt` + `vpn/AetherVpnService.kt` (`activeForwardPort`: subscription downloads through the session), *Copy link* via `copySensitive`.
- Tests: `app/src/test/.../custom/RealDelayTest.kt` (incl. `XrayLogRedactionTest`).

## tvaVPN edits (latest)
- Removed the "Freedom, in one tap" tagline from the home screen and the drawer menu.
- Settings: removed the hero subtitle; About: removed the signing-certificate/fingerprint lines, APK hash and verify hint.
- Tamper block: a build whose signature differs from the pinned release key shows an error screen (TamperScreen) and the VPN service refuses to connect. Release builds pin themselves automatically from the private keystore (app/build.gradle.kts).

## 1.6.0 additions
- Custom configs: Hysteria 2. `custom/ConfigParser.kt` (`hysteria2()`, schemes `hysteria2://`, `hy2://`; `hysteria://`, `hy://` refused; JSON `hysteria2` normalised to `hysteria`), `custom/XrayProcess.kt` (`binaryFor`: `libxray_hy.so` only for Hysteria outbounds), `custom/XrayConfigBuilder.kt` (`hasServer` accepts the flat `settings.address`), `scripts/build-natives.sh` (`build_xray_hy`, `XRAY_HY_VERSION`, SHA-256 check).
- Tests: `app/src/test/.../custom/HysteriaLinkTest.kt`.

## Smart Connection Guardian (2026-11-11)
- Added a dedicated, localized Guardian settings page using the existing settings row and switch components so TalkBack receives the same role/state semantics as other settings.
- Added persistent controls for tunnel quality monitoring and automatic recovery from sustained critical latency.
- Kept the Guardian silent: no live-region announcements, speech prompts, or repeated notifications were added.
- Existing dead-tunnel watchdog recovery remains active as a core safety behaviour; the new recovery switch controls only critical-latency route recovery.
- Quality checks remain conservative: they run through the tunnel, ignore active data transfers, and require three consecutive critical samples before restarting the engine.


## Automatic connection quality improvement (2026-11-11)
- Smart AUTO / Smart Plus attempts now enable the engine's existing cached-endpoint quality gate even when the user has not enabled the optional manual setting. A cached WireGuard endpoint that answers but measures very slow (for example, the 5.84 s data-plane RTT in the supplied log) is no longer accepted blindly.
- The engine performs its short TURBO scan and uses the existing RTT floor: it only replaces the cached endpoint if a genuinely faster candidate is found; otherwise it restores the just-verified cached endpoint. This avoids turning a working connection into a failed connection merely because a scan found nothing better.
- Hand-picked protocols preserve the user's existing `fastEndpointOnly` preference. No UI elements, TalkBack announcements, or background notification behavior were added.


## TVA AI integration (2026-11-12)
- Added a first-class `TVA AI` destination to the home navigation drawer and the settings route stack.
- Added Persian and English UI strings for per-user Gemini API credentials, model selection, connection testing, chat, settings review, proposals, apply/reconnect, undo, and TalkBack labels. Saving an empty key clears the stored credential; save verifies that the Android Keystore-backed secret can be read back.
- Added runtime connection-state context to Gemini prompts without sending local SOCKS addresses or private exception details.
- The Gemini REST client sends requests only through a local SOCKS5 port and uses remote-domain CONNECT for `generativelanguage.googleapis.com:443`; it does not silently fall back to the direct network.
- AI proposals remain limited to the local nine-setting allow-list and require explicit confirmation. Undo restores the previous profile and re-connects only when the settings had previously been applied with a live reconnect.
- Build/runtime verification remains pending; the Gradle 8.9 distribution is not present in the offline environment.

## TVA AI: reuse the main tunnel instead of a separate route (2026-11-12)
- Removed `AiPsiphonRouteManager` and the isolated Psiphon-only Aether process it owned (own loopback port, work directory, Psiphon state directory, scoped cleanup). AI traffic no longer starts any process or tunnel of its own.
- `GeminiApiClient` now sends every request through the local SOCKS5 port of the user's already-connected main VPN tunnel (the same exit the rest of the app's traffic, and Gemini access itself, already relies on). `TvaAiPage.mainTunnelSocksPort()` reads that port from `ConnectionState.Connected.socksAddr`.
- If the main VPN is not connected, TVA AI now reports a clear "connect to the VPN first" error (`tva_ai_vpn_not_connected`) instead of trying to bring up a route by itself.
- `GeminiApiClient.RouteUnstableException` retry logic in `TvaAiPage` no longer rebuilds anything; it waits briefly and retries the same main-tunnel port, since there is nothing isolated left to tear down and recreate.
- Updated the TVA AI system-prompt context (`TvaAiKnowledge`) and the `tva_ai_route_*` / added `tva_ai_vpn_not_connected` strings (English and Persian) to describe the main-tunnel route instead of a separate Psiphon side-route.

## Quick-reconnect RTT-budget: make a disabled/passed check visible in the log (2026-11-26)
- A field log (ping complaint) showed a cached WireGuard endpoint accepted at `rtt 680ms`
  with "skipping scan" immediately followed by that same peer being put into service — on a
  network whose own fingerprint, taken seconds earlier in the same connect, measured
  41-117ms edges. That is the exact symptom the "Automatic connection quality improvement
  (2026-11-11)" entry above was written to prevent, but nothing in that log said *why* the
  gate did not fire here: a passing budget check and a budget check that is simply not
  configured for this process (older/unpatched build, or `fastEndpointOnly` off) produced
  byte-for-byte identical log output on the success path, because `within_budget()` in
  `native/aether/aether/src/lib.rs` only ever logged on rejection.
- `within_budget()` now logs (debug level) on both the `None` (no budget env var reached this
  process at all) and `Some(limit)` (measured value, within budget) arms, each saying plainly
  which case it is. This covers both call sites (`cached_peer_fast_enough` for the WireGuard
  RTT budget and `cached_handshake_fast_enough` for the MASQUE handshake budget), since both
  go through the same helper.
- No behavior changed — the function still always allows reuse when `budget` is `None` or the
  measurement is within it, exactly as before. This is a diagnostics-only change: a future log
  from this build can no longer be mistaken for one from a build that predates the 2026-11-11
  gate.
- **Correction, same day.** The two new lines were first written as `log::debug!`. A second
  field log from the same build (`code 31003`), captured right after this change was supposed
  to be in place, showed the same silent accept at `rtt 587ms` with neither new line present —
  and critically, *no debug-level engine line of any kind* appears anywhere in either field log,
  meaning this process's own log filter does not pass `debug` through at all. A `debug!` here is
  therefore invisible in exactly the report this change exists to read, regardless of whether
  the running binary includes the patch. Changed both lines to `log::info!`, matching every
  neighbouring line they sit beside (`verifying cached...`, `using cloudflare edge...`), which is
  the level actually visible in these logs.
- Still open: whether a phone producing a silent-accept log is running an APK built from a
  source tree that includes the 2026-11-11 gate. `PATCHLEVEL`/`versionName` were not bumped when
  that gate landed, so version alone cannot answer this — a fresh log taken after rebuilding from
  this source will now show one of the two new `info!` lines either way, which settles it.
