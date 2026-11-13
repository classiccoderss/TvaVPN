package studio.cluvex.aether.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.TransportBackend

class AiProposalValidatorTest {

    @Test
    fun acceptsSmallSafeMtuAndNoizePatch() {
        val current = ConnectionProfile()
        val result = AiProposalValidator.validateAndApply(
            current,
            proposal(
                AiProposedChange("mtu", "1360", "A measured path supports a slightly larger MTU."),
                AiProposedChange("noize", "LIGHT", "Test a less intensive obfuscation preset."),
            ),
        )

        assertTrue(result is AiProposalValidation.Accepted)
        val updated = (result as AiProposalValidation.Accepted).updatedProfile
        assertEquals(1360, updated.mtu)
        assertEquals("LIGHT", updated.noize.name)
        assertTrue(current.mtu != updated.mtu)
    }

    @Test
    fun rejectsOutOfRangeMtu() {
        val result = AiProposalValidator.validateAndApply(
            ConnectionProfile(),
            proposal(AiProposedChange("mtu", "9000", "Higher is always faster.")),
        )
        assertTrue(result is AiProposalValidation.Rejected)
    }

    @Test
    fun rejectsUnknownSettingsInsteadOfIgnoringThem() {
        val result = AiProposalValidator.validateAndApply(
            ConnectionProfile(),
            proposal(AiProposedChange("killSwitch", "false", "Improve speed.")),
        )
        assertTrue(result is AiProposalValidation.Rejected)
    }

    @Test
    fun preservesKillSwitchAndLeakProtection() {
        val current = ConnectionProfile(killSwitch = true, ipv6LeakProtection = true)
        val result = AiProposalValidator.validateAndApply(
            current,
            proposal(AiProposedChange("mtu", "1400", "Use a conservative MTU test.")),
        ) as AiProposalValidation.Accepted

        assertTrue(result.updatedProfile.killSwitch)
        assertTrue(result.updatedProfile.ipv6LeakProtection)
    }

    @Test
    fun reverseTorBackendRejectsNonMasqueProtocol() {
        val current = ConnectionProfile(backend = TransportBackend.TOR_AETHER)
        val result = AiProposalValidator.validateAndApply(
            current,
            proposal(AiProposedChange("protocol", "WIREGUARD", "Try WireGuard.")),
        )
        assertTrue(result is AiProposalValidation.Rejected)
    }

    @Test
    fun reverseTorBackendAcceptsMasqueOnlyProtocolChange() {
        val current = ConnectionProfile(backend = TransportBackend.TOR_AETHER, protocol = Protocol.AUTO)
        val result = AiProposalValidator.validateAndApply(
            current,
            proposal(AiProposedChange("protocol", "MASQUE", "This backend requires MASQUE.")),
        )
        assertTrue(result is AiProposalValidation.Accepted)
        assertEquals(Protocol.MASQUE, (result as AiProposalValidation.Accepted).updatedProfile.protocol)
    }

    @Test
    fun rejectsWarpOnlyNetworkTuningOnTorOnlyBackend() {
        val current = ConnectionProfile(backend = TransportBackend.TOR)
        val ipVersion = AiProposalValidator.validateAndApply(
            current,
            proposal(AiProposedChange("ipVersion", "V4", "Use IPv4.")),
        )
        val quickReconnect = AiProposalValidator.validateAndApply(
            current,
            proposal(AiProposedChange("quickReconnect", "true", "Reconnect quickly.")),
        )
        assertTrue(ipVersion is AiProposalValidation.Rejected)
        assertTrue(quickReconnect is AiProposalValidation.Rejected)
    }

    @Test
    fun rejectsMoreThanThreeChanges() {
        val result = AiProposalValidator.validateAndApply(
            ConnectionProfile(),
            proposal(
                AiProposedChange("mtu", "1360", "reason"),
                AiProposedChange("noize", "LIGHT", "reason"),
                AiProposedChange("ipVersion", "V4", "reason"),
                AiProposedChange("scanMode", "BALANCED", "reason"),
            ),
        )
        assertTrue(result is AiProposalValidation.Rejected)
    }

    @Test
    fun runtimeContextDescribesStateWithoutForwardingPrivateErrorDetails() {
        val context = TvaAiKnowledge.runtimeContext(ConnectionState.Error("api_key=DO_NOT_SEND_ME"))
        assertTrue(context.contains("connectionState=ERROR"))
        assertTrue(context.contains("aiTrafficRoute=main-tunnel"))
        assertFalse(context.contains("DO_NOT_SEND_ME"))
        assertFalse(context.contains("api_key"))
    }

    @Test
    fun profileContextDoesNotExposeSecretsOrManualEndpoints() {
        val profile = ConnectionProfile(
            accessClientSecret = "DO_NOT_SEND_SECRET",
            accessToken = "DO_NOT_SEND_TOKEN",
            manualPeer = "198.51.100.42:2408",
            upstreamProxy = "socks5://user:password@example.com:1080",
            dnsServers = "9.9.9.9",
        )
        val context = TvaAiKnowledge.profileContext(profile)

        assertFalse(context.contains("DO_NOT_SEND_SECRET"))
        assertFalse(context.contains("DO_NOT_SEND_TOKEN"))
        assertFalse(context.contains("198.51.100.42"))
        assertFalse(context.contains("password"))
        assertFalse(context.contains("9.9.9.9"))
        assertTrue(context.contains("customDnsConfigured=true"))
    }

    private fun proposal(vararg changes: AiProposedChange) = AiSettingsProposal(
        title = "Safe test",
        summary = "Test a limited change",
        expectedBenefit = "A measurable improvement may be observed",
        tradeoff = "The result can vary by network",
        confidence = 0.7,
        requiresReconnect = true,
        changes = changes.toList(),
    )
}
