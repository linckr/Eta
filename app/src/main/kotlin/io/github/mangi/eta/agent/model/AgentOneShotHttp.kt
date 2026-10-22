package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.utf8Size
import org.json.JSONArray

/** In-memory exact-body lease. It is never serialized, logged, persisted or silently renewed. */
internal class ModelOneShotAttempt private constructor(
    private val bytes: ByteArray,
    private val measuredBytes: Long,
    val bodyDigest: String,
    private val configDigest: String,
    private val sessionId: String,
    private val target: String,
    val estimatedTokens: Int,
    val inputLimit: Int?,
    val sendLimitBytes: Long?,
    val hardLimitBytes: Long?,
    val eligibleReason: String?,
    private val clockMillis: () -> Long,
    private val identityCurrent: (() -> Boolean)?,
) {
    val requestBytes: Long get() = measuredBytes
    internal val retainedBodyBytes: Int get() = bytes.size
    private val state = AtomicInteger(0) // prepared, authorized, consumed, invalidated
    @Volatile private var expiresAt = Long.MIN_VALUE
    val consumed: Boolean get() = state.get() == 2
    @Volatile var onConsumed: (() -> Unit)? = null
    @Volatile var onFinished: ((Boolean, String) -> Unit)? = null
    fun finished(success: Boolean, code: String = "") { runCatching { onFinished?.invoke(success, code) } }
    @Volatile var configurationCurrent: (() -> Boolean)? = null

    fun authorize() {
        if (eligibleReason != null) fail(eligibleReason)
        if (!state.compareAndSet(0, 1)) fail("MODEL_ONE_SHOT_ALREADY_USED")
        expiresAt = clockMillis() + LEASE_MILLIS
    }

    fun invalidate() { state.compareAndSet(0, 3); state.compareAndSet(1, 3) }

    /** Inspection does not consume or authorize the lease. */
    fun inspect(request: ProviderRequest) {
        eligibleReason?.let(::fail)
        if (configurationCurrent?.let { runCatching(it).getOrDefault(false) } == false)
            fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        if (identityCurrent != null && !runCatching(identityCurrent).getOrDefault(false)) fail("MODEL_ONE_SHOT_ACCOUNT_CHANGED")
        if (request.purpose != ProviderRequestPurpose.CHAT || request.sessionId != sessionId ||
            fingerprint(request) != configDigest || target(request) != target) fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        val current = AgentProviderRequestJson.build(request).toString()
        if (current.utf8Size() != measuredBytes || digestUtf8(current) != bodyDigest) fail("MODEL_ONE_SHOT_BINDING_CHANGED")
    }

    /** The single HTTP entry calls this immediately before creating the physical Call. */
    internal fun consume(request: ProviderRequest, outgoing: Request): Request {
        inspect(request)
        if (clockMillis() >= expiresAt || state.get() != 1) fail("MODEL_ONE_SHOT_EXPIRED_OR_USED")
        if (outgoing.url.toString() != target || outgoing.method != "POST") fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        val actual = outgoing.body ?: fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        if (actual.contentLength() != requestBytes) fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        // Stream into a hash, never retain a second full request-body buffer.
        val hash = HashingSink.sha256(blackholeSink())
        var writtenBytes = 0L
        val bounded = object : okio.ForwardingSink(hash) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                if (byteCount > measuredBytes - writtenBytes) fail("MODEL_ONE_SHOT_BINDING_CHANGED")
                writtenBytes += byteCount
                super.write(source, byteCount)
            }
        }
        bounded.buffer().use { actual.writeTo(it) }
        if (writtenBytes != measuredBytes || hash.hash.hex() != bodyDigest) fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        if (clockMillis() >= expiresAt) fail("MODEL_ONE_SHOT_EXPIRED_OR_USED")
        if (configurationCurrent?.let { runCatching(it).getOrDefault(false) } == false) fail("MODEL_ONE_SHOT_BINDING_CHANGED")
        if (identityCurrent != null && !runCatching(identityCurrent).getOrDefault(false)) fail("MODEL_ONE_SHOT_ACCOUNT_CHANGED")
        if (!state.compareAndSet(1, 2)) fail("MODEL_ONE_SHOT_EXPIRED_OR_USED")
        runCatching { onConsumed?.invoke() }
        return outgoing.newBuilder().method(outgoing.method, object : RequestBody() {
            private val written = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun contentType(): MediaType? = actual.contentType()
            override fun contentLength(): Long = requestBytes
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                if (!written.compareAndSet(false, true)) throw java.io.IOException("One-shot request cannot be replayed")
                sink.write(bytes)
            }
        }).header("X-Eta-One-Shot", "v1").build()
    }

    companion object {
        const val LEASE_MILLIS = 60_000L
        const val MAX_BINDING_BYTES = 16 * 1024 * 1024
        fun create(request: ProviderRequest,
            accountGuardFactory: (ProviderRequest) -> (() -> Boolean)? = { prepared ->
                val config = prepared.effectiveConfig
                if (ProviderConnectionProfile.forAuthMode(config.effectiveAuthMode) == null) null else {
                    val binding = ProviderCredentialRepository.binding(config.providerId, config.effectiveAuthMode)
                    val check: () -> Boolean = { ProviderCredentialRepository.isCurrent(binding) }
                    check
                }
            },
            clockMillis: () -> Long = { runCatching { android.os.SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000 } },
        ): ModelOneShotAttempt {
            val config = request.effectiveConfig
            val identity = runCatching { accountGuardFactory(request) }
            val body = AgentProviderRequestJson.build(request)
            val text = body.toString()
            val measuredBytes = text.utf8Size()
            val tokens = AgentContextBudget.estimate(JSONArray().put(body), JSONArray())
            val input = AgentContextBudget.inputBudget(config)
            val limit = config.requestSendLimitBytes
            val hard = config.requestHardLimitBytes
            val reason = when {
                identity.isFailure -> "MODEL_ONE_SHOT_ACCOUNT_UNAVAILABLE"
                request.purpose != ProviderRequestPurpose.CHAT -> "MODEL_ONE_SHOT_NOT_CHAT"
                config.compactionPolicyVersion < 4 -> "MODEL_ONE_SHOT_V4_DISABLED"
                limit == null || limit <= 0 || hard != null && (hard <= 0 || hard < limit) -> "MODEL_REQUEST_SEND_POLICY_INVALID"
                config.customHeaders.isNotEmpty() || config.customBody.isNotEmpty() || config.extraBodyJson.isNotBlank() -> "MODEL_ONE_SHOT_CUSTOM_OVERRIDES"
                AnthropicEphemeralState.hasPendingToolResponse(request.messages) -> "MODEL_ONE_SHOT_SIGNED_TOOL_ROUND"
                measuredBytes > MAX_BINDING_BYTES -> "MODEL_ONE_SHOT_BINDING_TOO_LARGE"
                hard != null && measuredBytes > hard -> "MODEL_REQUEST_HARD_LIMIT"
                measuredBytes <= limit -> "MODEL_ONE_SHOT_NOT_NEEDED"
                input != null && (input <= 0 || tokens.toLong() * 100 >= input.toLong() * 85) -> "MODEL_ONE_SHOT_TOKEN_PRESSURE"
                else -> AgentOneShotHttp.gatewayEligibility(request)
            }
            // Rejected requests retain no extra frozen body, including oversized Unicode payloads.
            val bytes = if (reason == null) text.toByteArray(Charsets.UTF_8) else ByteArray(0)
            return ModelOneShotAttempt(bytes, measuredBytes, if (reason == null) digest(bytes) else "", fingerprint(request), request.sessionId, target(request),
                tokens, input, limit, hard, reason, clockMillis, identity.getOrNull())
        }
        private fun fingerprint(request: ProviderRequest): String = digest(request.config.toString().toByteArray(Charsets.UTF_8))
        private fun target(request: ProviderRequest): String {
            val config = request.effectiveConfig
            return when {
                config.providerType == ProviderTypes.ANTHROPIC -> ProviderUrls.anthropicMessagesUrl(config.baseUrl)
                config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES -> ProviderUrls.openAiResponsesUrl(config.baseUrl)
                else -> ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
            }
        }
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun digestUtf8(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val discard = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            }
            java.io.OutputStreamWriter(java.security.DigestOutputStream(discard, digest), Charsets.UTF_8).use { it.write(text) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun fail(code: String): Nothing = throw AgentModelFailure(code, false,
            "本次发送许可不可用或已失效，请重新确认；原历史及工具结果已保留。", contextRecoveryAllowed = false)
    }
}

/** All model transports enter here. No redirects, authentication replay or connection recovery on leases. */
internal object AgentOneShotHttp {
    fun client(client: OkHttpClient, attempt: ModelOneShotAttempt?): OkHttpClient = if (attempt == null) client else client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).build()

    fun prepare(request: ProviderRequest, outgoing: Request): Request {
        val attempt = request.oneShotAttempt
        if (attempt == null) {
            AgentRequestSendPolicy.requireAllowed(request.effectiveConfig, outgoing.body?.contentLength() ?: -1)
            return outgoing
        }
        return attempt.consume(request, outgoing)
    }

    /** A client cannot enforce the replay policy of a separate gateway. No self-asserted capability header. */
    fun gatewayEligibility(request: ProviderRequest): String? {
        val config = request.effectiveConfig
        val route = config.baseUrl.lowercase()
        return if (config.providerName.contains("workbuddy", true) || config.providerSourceType.contains("workbuddy", true) ||
            route.contains("workbuddy") || route.contains(":8317")) "MODEL_ONE_SHOT_GATEWAY_UNVERIFIED" else null
    }
}
