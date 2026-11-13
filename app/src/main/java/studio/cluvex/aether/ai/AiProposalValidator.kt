package studio.cluvex.aether.ai

import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.FragmentPreset
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TorMode

/** A single proposed change emitted by Gemini. Values are kept as text until validated. */
data class AiProposedChange(
    val setting: String,
    val value: String,
    val reason: String,
)

data class AiSettingsProposal(
    val title: String,
    val summary: String,
    val expectedBenefit: String,
    val tradeoff: String,
    val confidence: Double,
    val requiresReconnect: Boolean,
    val changes: List<AiProposedChange>,
)

sealed class AiProposalValidation {
    data class Accepted(
        val updatedProfile: ConnectionProfile,
        val changedSettings: List<String>,
    ) : AiProposalValidation()

    data class Rejected(val reasons: List<String>) : AiProposalValidation()
}

/**
 * Local trust boundary for AI-suggested settings.
 *
 * Gemini never receives a method that can mutate the profile. The UI must first
 * parse the model response into [AiSettingsProposal], show it to the user, and
 * call this validator only after the user taps Apply. Every proposed field is
 * checked against the current backend and an explicit allow-list. Any invalid
 * field rejects the whole patch.
 */
object AiProposalValidator {
    private const val MAX_CHANGES = 3
    private const val MIN_AI_MTU = 1280
    private const val MAX_AI_MTU_WITHOUT_PROBE = 1500
    private val allowedKeepalive = setOf(0, 10, 25, 45)

    fun validateAndApply(
        current: ConnectionProfile,
        proposal: AiSettingsProposal,
    ): AiProposalValidation {
        val errors = mutableListOf<String>()
        val changes = proposal.changes

        if (changes.isEmpty()) errors += "هیچ تغییری برای اعمال پیشنهاد نشده است."
        if (changes.size > MAX_CHANGES) errors += "حداکثر سه تغییر در هر پیشنهاد مجاز است."
        if (proposal.confidence !in 0.0..1.0) errors += "میزان اطمینان باید بین صفر و یک باشد."
        if (changes.map { it.setting }.distinct().size != changes.size) {
            errors += "یک تنظیم بیش از یک بار پیشنهاد شده است."
        }

        // Resolve cross-dependent values before validation so response order cannot
        // change whether a combined protocol + keepalive/preset proposal is accepted.
        val proposedProtocol = changes.firstOrNull { it.setting == "protocol" }
            ?.value?.let { enumValueOrNull<Protocol>(it) }
        val effectiveProtocolForProposal = when {
            proposedProtocol != null && current.backend.torMode == TorMode.REVERSE -> Protocol.MASQUE
            proposedProtocol != null -> proposedProtocol
            else -> current.effectiveProtocol
        }
        val selectedProtocolForProposal = proposedProtocol ?: current.protocol

        var mtu: Int? = null
        var protocol: Protocol? = null
        var scanMode: ScanMode? = null
        var ipVersion: IpVersion? = null
        var noize: Noize? = null
        var keepalive: Int? = null
        var fragmentPreset: FragmentPreset? = null
        var quickReconnect: Boolean? = null
        var fastEndpointOnly: Boolean? = null

        changes.forEach { change ->
            when (change.setting) {
                "mtu" -> {
                    mtu = change.value.toIntOrNull()?.takeIf {
                        it in MIN_AI_MTU..MAX_AI_MTU_WITHOUT_PROBE
                    }
                    if (mtu == null) errors += "MTU باید عددی بین 1280 و 1500 باشد."
                    if (!current.backend.usesWarp) errors += "MTU در این مسیر بدون WARP قابل پیشنهاد نیست."
                }
                "protocol" -> {
                    protocol = enumValueOrNull<Protocol>(change.value)
                    if (protocol == null) errors += "پروتکل پیشنهادی معتبر نیست."
                    if (!current.backend.usesWarp) errors += "این مسیر از تنظیم پروتکل WARP استفاده نمی‌کند."
                    if (current.backend.torMode == TorMode.REVERSE && protocol != null && protocol != Protocol.MASQUE) {
                        errors += "در مسیر TOR_AETHER فقط MASQUE مجاز است."
                    }
                }
                "scanMode" -> {
                    scanMode = enumValueOrNull<ScanMode>(change.value)
                    if (scanMode == null) errors += "حالت اسکن پیشنهادی معتبر نیست."
                    if (!current.backend.usesWarp) errors += "حالت اسکن در این مسیر بدون WARP کاربرد ندارد."
                    if (current.endpointMode.name != "AUTO") errors += "حالت اسکن فقط در انتخاب خودکار Endpoint کاربرد دارد."
                }
                "ipVersion" -> {
                    ipVersion = enumValueOrNull<IpVersion>(change.value)
                    if (ipVersion == null) errors += "نسخه IP پیشنهادی معتبر نیست."
                    if (!current.backend.usesWarp) errors += "انتخاب نسخه IP در این مسیر بدون WARP قابل اعمال نیست."
                }
                "noize" -> {
                    noize = enumValueOrNull<Noize>(change.value)
                    if (noize == null) errors += "پروفایل Noize پیشنهادی معتبر نیست."
                    if (!current.backend.usesWarp) errors += "Noize در این مسیر بدون WARP کاربرد ندارد."
                }
                "keepalive" -> {
                    keepalive = change.value.toIntOrNull()?.takeIf { it in allowedKeepalive }
                    if (keepalive == null) errors += "Keepalive باید یکی از 0، 10، 25 یا 45 باشد."
                    if (!current.backend.usesWarp || selectedProtocolForProposal !in setOf(Protocol.WIREGUARD, Protocol.GOOL)) {
                        errors += "Keepalive فقط برای پروتکل صریح WireGuard یا GOOL قابل پیشنهاد است."
                    }
                }
                "fragmentPreset" -> {
                    fragmentPreset = enumValueOrNull<FragmentPreset>(change.value)
                    if (fragmentPreset == null || fragmentPreset == FragmentPreset.CUSTOM) {
                        errors += "Preset تکه‌بندی باید DEFAULT، LIGHT، BALANCED یا AGGRESSIVE باشد."
                    }
                    if (!current.backend.usesWarp || effectiveProtocolForProposal !in setOf(Protocol.MASQUE, Protocol.MIM)) {
                        errors += "Preset تکه‌بندی فقط برای مسیر MASQUE یا MIM قابل پیشنهاد است."
                    }
                }
                "quickReconnect" -> {
                    quickReconnect = parseBoolean(change.value)
                    if (quickReconnect == null) errors += "quickReconnect باید true یا false باشد."
                    if (!current.backend.usesWarp) errors += "اتصال مجدد سریع در این مسیر بدون WARP کاربرد ندارد."
                }
                "fastEndpointOnly" -> {
                    fastEndpointOnly = parseBoolean(change.value)
                    if (fastEndpointOnly == null) errors += "fastEndpointOnly باید true یا false باشد."
                    if (!current.backend.usesWarp) errors += "این گزینه در مسیر بدون WARP کاربرد ندارد."
                }
                else -> errors += "تنظیم '${change.setting}' در فهرست مجاز نیست."
            }
        }

        val resultingQuickReconnect = quickReconnect ?: current.quickReconnect
        val resultingFastOnly = fastEndpointOnly ?: current.fastEndpointOnly
        if (resultingFastOnly && !resultingQuickReconnect) {
            errors += "fastEndpointOnly بدون quickReconnect قابل فعال‌سازی نیست."
        }

        if (errors.isNotEmpty()) return AiProposalValidation.Rejected(errors.distinct())

        val fragmentPair = fragmentPreset?.let(::fragmentPair)
        val updated = current.copy(
            mtu = mtu ?: current.mtu,
            protocol = protocol ?: current.protocol,
            scanMode = scanMode ?: current.scanMode,
            ipVersion = ipVersion ?: current.ipVersion,
            noize = noize ?: current.noize,
            keepalive = keepalive ?: current.keepalive,
            fragmentSize = fragmentPair?.first ?: current.fragmentSize,
            fragmentDelay = fragmentPair?.second ?: current.fragmentDelay,
            quickReconnect = resultingQuickReconnect,
            fastEndpointOnly = resultingFastOnly,
        )

        val changed = buildList {
            if (updated.mtu != current.mtu) add("mtu")
            if (updated.protocol != current.protocol) add("protocol")
            if (updated.scanMode != current.scanMode) add("scanMode")
            if (updated.ipVersion != current.ipVersion) add("ipVersion")
            if (updated.noize != current.noize) add("noize")
            if (updated.keepalive != current.keepalive) add("keepalive")
            if (updated.fragmentSize != current.fragmentSize || updated.fragmentDelay != current.fragmentDelay) add("fragmentPreset")
            if (updated.quickReconnect != current.quickReconnect) add("quickReconnect")
            if (updated.fastEndpointOnly != current.fastEndpointOnly) add("fastEndpointOnly")
        }

        if (changed.isEmpty()) return AiProposalValidation.Rejected(listOf("تنظیمات پیشنهادی با وضعیت فعلی تفاوتی ندارد."))
        return AiProposalValidation.Accepted(updated, changed)
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(raw: String): T? =
        runCatching { enumValueOf<T>(raw.trim().uppercase()) }.getOrNull()

    private fun parseBoolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun fragmentPair(preset: FragmentPreset): Pair<String, String> = when (preset) {
        FragmentPreset.DEFAULT -> "" to ""
        FragmentPreset.LIGHT -> "8-16" to "1-4"
        FragmentPreset.BALANCED -> "16-32" to "2-10"
        FragmentPreset.AGGRESSIVE -> "24-48" to "5-20"
        FragmentPreset.CUSTOM -> error("CUSTOM is not AI-applicable")
    }
}
