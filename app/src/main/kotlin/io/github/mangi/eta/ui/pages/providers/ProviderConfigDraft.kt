package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.saveable.mapSaver
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.CustomHeaderFilter
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.RequestSendPolicy
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.util.UUID
import java.math.BigDecimal

internal data class ProviderHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val header: CustomHeader = CustomHeader("", ""),
)

internal data class ProviderConfigDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String,
    val isEnabled: Boolean,
    val endpointMode: String,
    val hostedWebSearchEnabled: Boolean,
    val anthropicVersion: String,
    val headers: List<ProviderHeaderDraft> = emptyList(),
    val requestByteBudgetKiB: String = "",
    val sendPolicyEnabled: Boolean = false,
    val observedRequestBytes: String = "",
    val hardRequestBytes: String = "",
    val authMode: String = ProviderAuthModes.API_KEY,
) {
    companion object {
        fun from(provider: ProviderSetting): ProviderConfigDraft = ProviderConfigDraft(
            headers = provider.customHeaders.map { ProviderHeaderDraft(header = it) },
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            requestByteBudgetKiB = requestByteBudgetKiBText(provider.requestByteBudget),
            sendPolicyEnabled = provider.requestSendPolicy.isValidFor(provider),
            observedRequestBytes = provider.requestSendPolicy.observedBytes?.toString().orEmpty(),
            hardRequestBytes = provider.requestSendPolicy.hardLimitBytes?.toString().orEmpty(),
            authMode = provider.authMode,
            systemPrompt = provider.systemPrompt.orEmpty(),
            isEnabled = provider.isEnabled,
            endpointMode = when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            },
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        )
    }
}

internal val ProviderConfigDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "headers" to ArrayList(draft.headers.flatMap { listOf(it.id, it.header.name, it.header.value) }),
            "name" to draft.name,
            "baseUrl" to draft.baseUrl,
            "apiKey" to draft.apiKey,
            "authMode" to draft.authMode,
            "systemPrompt" to draft.systemPrompt,
            "isEnabled" to draft.isEnabled,
            "endpointMode" to draft.endpointMode,
            "hostedWebSearchEnabled" to draft.hostedWebSearchEnabled,
            "anthropicVersion" to draft.anthropicVersion,
            "requestByteBudgetKiB" to draft.requestByteBudgetKiB,
            "sendPolicyEnabled" to draft.sendPolicyEnabled,
            "observedRequestBytes" to draft.observedRequestBytes,
            "hardRequestBytes" to draft.hardRequestBytes,
        )
    },
    restore = { state ->
        ProviderConfigDraft(
            headers = (state["headers"] as? List<*>)?.chunked(3)?.map {
                ProviderHeaderDraft(it[0] as String, CustomHeader(it[1] as String, it[2] as String))
            }.orEmpty(),
            name = state.getValue("name") as String,
            baseUrl = state.getValue("baseUrl") as String,
            apiKey = state.getValue("apiKey") as String,
            authMode = state["authMode"] as? String ?: ProviderAuthModes.API_KEY,
            systemPrompt = state.getValue("systemPrompt") as String,
            isEnabled = state.getValue("isEnabled") as Boolean,
            endpointMode = state.getValue("endpointMode") as String,
            hostedWebSearchEnabled = state.getValue("hostedWebSearchEnabled") as Boolean,
            anthropicVersion = state.getValue("anthropicVersion") as String,
            requestByteBudgetKiB = state["requestByteBudgetKiB"] as? String ?: "",
            sendPolicyEnabled = state["sendPolicyEnabled"] as? Boolean ?: false,
            observedRequestBytes = state["observedRequestBytes"] as? String ?: "",
            hardRequestBytes = state["hardRequestBytes"] as? String ?: "",
        )
    },
)

internal fun buildUpdatedProvider(
    source: ProviderSetting,
    name: String,
    baseUrl: String,
    apiKey: String,
    systemPrompt: String,
    isEnabled: Boolean,
    endpointMode: String,
    hostedWebSearchEnabled: Boolean,
    anthropicVersion: String,
    customHeaders: List<CustomHeader>,
    requestByteBudget: Int? = source.requestByteBudget,
    sendPolicyEnabled: Boolean? = null,
    requestObservedBytes: Long? = source.requestSendPolicy.observedBytes,
    requestHardLimitBytes: Long? = source.requestSendPolicy.hardLimitBytes,
    authMode: String = source.authMode,
): ProviderSetting {
    require(requestByteBudget == null || requestByteBudget >= 0) { "Invalid request byte budget" }
    val prompt = systemPrompt.trim().takeIf { it.isNotBlank() }
    val profile = ProviderConnectionProfile.forAuthMode(authMode)
    val updated = when (source) {
        is OpenAiCompatibleProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = profile?.baseUrl ?: baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = authMode,
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = profile?.endpointMode ?: endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
            requestByteBudget = requestByteBudget,
        )
        is CustomProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = profile?.baseUrl ?: baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = authMode,
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = profile?.endpointMode ?: endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
            requestByteBudget = requestByteBudget,
        )
        is AnthropicProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            anthropicVersion = anthropicVersion.trim().ifBlank { AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
            requestByteBudget = requestByteBudget,
        )
    }
    val old = source.requestSendPolicy
    val wanted = sendPolicyEnabled ?: (old.version == 4)
    val policy = when {
        !wanted -> RequestSendPolicy()
        old.version == 4 && !old.isValidFor(source) && sendPolicyEnabled != true -> RequestSendPolicy()
        old.version == 4 && old.isValidFor(source) && !old.isValidFor(updated) -> RequestSendPolicy() // route changes require a fresh opt-in
        old.isValidFor(updated) && old.observedBytes == requestObservedBytes &&
            old.hardLimitBytes == requestHardLimitBytes -> old // keep the frozen send limit
        else -> RequestSendPolicy.freeze(updated, requestObservedBytes, requestHardLimitBytes)
    }
    return when (updated) {
        is OpenAiCompatibleProviderSetting -> updated.copy(requestSendPolicy = policy)
        is CustomProviderSetting -> updated.copy(requestSendPolicy = policy)
        is AnthropicProviderSetting -> updated.copy(requestSendPolicy = policy)
    }
}

internal fun positiveRequestBytes(text: String): Long? = text.trim().takeIf { it.isNotEmpty() }?.let {
    require(it.length <= 18 && it.all(Char::isDigit))
    it.toLong().also { value -> require(value in 1..(Long.MAX_VALUE - 524_288)) }
}

internal fun sendPolicyInputError(draft: ProviderConfigDraft): Boolean = draft.sendPolicyEnabled && runCatching {
    positiveRequestBytes(draft.observedRequestBytes)
    positiveRequestBytes(draft.hardRequestBytes)
}.isFailure

internal fun ProviderConfigDraft.toProvider(source: ProviderSetting): ProviderSetting = buildUpdatedProvider(
    source = source,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    systemPrompt = systemPrompt,
    isEnabled = isEnabled,
    endpointMode = endpointMode,
    hostedWebSearchEnabled = hostedWebSearchEnabled,
    anthropicVersion = anthropicVersion,
    customHeaders = headers.map { it.header },
    authMode = authMode,
    requestByteBudget = requestByteBudgetFromKiB(requestByteBudgetKiB),
    sendPolicyEnabled = sendPolicyEnabled,
    requestObservedBytes = if (sendPolicyEnabled) positiveRequestBytes(observedRequestBytes) else null,
    requestHardLimitBytes = if (sendPolicyEnabled) positiveRequestBytes(hardRequestBytes) else null,
)

internal fun providerAccountAuthMode(provider: ProviderSetting): String? {
    if (provider is AnthropicProviderSetting) return null
    if (ProviderAuthModes.isOAuth(provider.authMode)) return provider.authMode
    return when (ProviderSourceRegistry.resolve(provider)) {
        ProviderSourceTypes.OPENAI_CODEX, ProviderSourceTypes.OPENAI -> ProviderAuthModes.OPENAI_CHATGPT
        ProviderSourceTypes.XAI -> ProviderAuthModes.XAI_GROK
        else -> null
    }
}

internal fun ProviderConfigDraft.withAuthMode(mode: String, provider: ProviderSetting): ProviderConfigDraft {
    val profile = ProviderConnectionProfile.forAuthMode(mode)
    if (profile != null) return copy(
        authMode = mode,
        baseUrl = profile.baseUrl,
        endpointMode = profile.endpointMode,
        hostedWebSearchEnabled = hostedWebSearchEnabled && profile.endpointMode == OpenAiEndpointMode.RESPONSES,
    )
    val original = ProviderConfigDraft.from(provider)
    if (provider.authMode == ProviderAuthModes.API_KEY) return copy(
        authMode = mode,
        baseUrl = original.baseUrl,
        endpointMode = original.endpointMode,
    )
    val apiProvider = checkNotNull(BuiltinProviders.providerById(
        if (authMode == ProviderAuthModes.XAI_GROK) BuiltinProviders.XAI_ID else BuiltinProviders.OPENAI_ID,
    ))
    return copy(
        authMode = mode,
        baseUrl = apiProvider.baseUrl,
        endpointMode = ProviderConfigDraft.from(apiProvider).endpointMode,
    )
}

internal fun validateProviderDraft(context: android.content.Context, draft: ProviderConfigDraft): String? {
    if (draft.name.isBlank()) return context.getString(R.string.page_name_cannot_be_empty_ca8984)
    if (requestByteBudgetInputError(draft.requestByteBudgetKiB)) {
        return context.getString(R.string.provider_request_byte_budget_error)
    }
    if (sendPolicyInputError(draft)) return "发送策略字节数必须为正整数，或留空。"
    val uri = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return context.getString(R.string.page_base_url_must_be_a_valid_http_s_address_0e7d58)
    }
    return CustomHeaderFilter.validationError(draft.headers.map { it.header })
}

/** Exact KiB display preserves byte-level values restored from older/custom profiles. */
internal fun requestByteBudgetKiBText(bytes: Int?): String = bytes?.let {
    BigDecimal(it).divide(BigDecimal(1024)).stripTrailingZeros().toPlainString()
}.orEmpty()

internal fun requestByteBudgetFromKiB(text: String): Int? {
    val value = text.trim()
    if (value.isEmpty()) return null
    require(value.length <= 32 && value.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")))
    return BigDecimal(value).multiply(BigDecimal(1024)).intValueExact().also { require(it >= 0) }
}

internal fun requestByteBudgetInputError(text: String): Boolean =
    runCatching { requestByteBudgetFromKiB(text) }.isFailure

/** Explicitly surface routes that do not inherit the provider's v4 L/H guard. */
internal fun requestSendPolicyExcludedModelNames(provider: ProviderSetting): List<String> =
    provider.models.filter { it.customHeaders.isNotEmpty() || it.customBody.isNotEmpty() }
        .map { it.displayName.ifBlank { it.modelId } }.distinct()

internal fun requestSendPolicyNeedsBootstrapReactivation(provider: ProviderSetting): Boolean =
    provider.requestSendPolicy.let { it.version == 4 && it.observedBytes == null && !it.isValidFor(provider) }
