package io.github.mangi.eta.data.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local sending policy, never a claim about the server's accepted request size. */
@Serializable
data class RequestSendPolicy(
    val version: Int = 0,
    val observedBytes: Long? = null,
    val sendLimitBytes: Long? = null,
    val growthAllowanceBytes: Long? = null,
    val hardLimitBytes: Long? = null,
    val scopeDigest: String = "",
    val evidence: String = "bootstrap_local_policy",
    // Missing in early v4 builds whose bootstrap incorrectly inherited the soft budget B.
    val bootstrapRuleVersion: Int = 0,
) {
    fun isValidFor(provider: ProviderSetting): Boolean = version == 4 &&
        sendLimitBytes != null && sendLimitBytes > 0 &&
        growthAllowanceBytes != null && growthAllowanceBytes >= 0 &&
        (hardLimitBytes == null || hardLimitBytes > 0 && sendLimitBytes <= hardLimitBytes) &&
        scopeDigest == requestSendPolicyScope(provider) &&
        (observedBytes != null || bootstrapRuleVersion == BOOTSTRAP_RULE_VERSION &&
            growthAllowanceBytes == 262_144L &&
            sendLimitBytes == minOf(786_432L, hardLimitBytes ?: Long.MAX_VALUE))

    companion object {
        const val BOOTSTRAP_ANCHOR_BYTES = 524_288L
        const val BOOTSTRAP_RULE_VERSION = 1
        /** Freeze once on explicit activation/edit, not after each successful request. */
        fun freeze(provider: ProviderSetting, observedBytes: Long?, hardLimitBytes: Long?): RequestSendPolicy {
            require(observedBytes == null || observedBytes > 0)
            require(hardLimitBytes == null || hardLimitBytes > 0)
            // B is an independent soft trigger, including temporary sampling overrides.
            val anchor = observedBytes ?: BOOTSTRAP_ANCHOR_BYTES
            require(anchor <= Long.MAX_VALUE - 524_288L)
            val growth = (anchor / 4 + if (anchor % 4 == 0L) 0 else 1).coerceIn(262_144L, 524_288L)
            return RequestSendPolicy(
                version = 4,
                observedBytes = observedBytes,
                sendLimitBytes = (anchor + growth).let { if (hardLimitBytes == null) it else minOf(it, hardLimitBytes) },
                growthAllowanceBytes = growth,
                hardLimitBytes = hardLimitBytes,
                scopeDigest = requestSendPolicyScope(provider),
                evidence = if (observedBytes == null) "bootstrap_local_policy" else "user_confirmed_observed_success",
                bootstrapRuleVersion = if (observedBytes == null) BOOTSTRAP_RULE_VERSION else 0,
            )
        }
    }
}

/** Provider-route scope includes auth and request overrides. Only the digest is stored. */
internal fun requestSendPolicyScope(provider: ProviderSetting): String {
    val endpoint = when (provider) {
        is OpenAiCompatibleProviderSetting -> provider.endpointMode
        is CustomProviderSetting -> provider.endpointMode
        is AnthropicProviderSetting -> provider.anthropicVersion
    }
    val parts = listOf(provider.runtimeProviderType, provider.sourceType, provider.baseUrl.trim(),
        endpoint, provider.apiKey.trim(), Json.encodeToString(provider.customHeaders),
        Json.encodeToString(provider.customBody), provider.hostedWebSearchEnabled.toString())
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
