package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AgentOneShotHttpTest {
    private fun request(base: String = "http://127.0.0.1:1", endpoint: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        type: String = ProviderTypes.OPENAI_COMPATIBLE, chars: Int = 2000): ProviderRequest = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl = base, apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
            openAiEndpointMode = endpoint, providerType = type, contextWindow = 1_000_000,
            compactionPolicyVersion = 4, requestSendLimitBytes = 100),
        JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(chars))), JSONArray(), sessionId = "fixed")

    private fun outgoing(request: ProviderRequest): Request {
        val config = request.effectiveConfig
        val url = when {
            config.providerType == ProviderTypes.ANTHROPIC -> ProviderUrls.anthropicMessagesUrl(config.baseUrl)
            config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES -> ProviderUrls.openAiResponsesUrl(config.baseUrl)
            else -> ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        }
        return Request.Builder().url(url).post(AgentProviderRequestJson.build(request).toString()
            .toRequestBody("application/json".toMediaType())).build()
    }

    @Test fun inspectionNeverConsumesAndBodyCannotBeWrittenTwice() {
        val request = request()
        val lease = ModelOneShotAttempt.create(request)
        assertNull(lease.eligibleReason)
        repeat(2) { lease.inspect(request) }
        assertFalse(lease.consumed)
        lease.authorize()
        val guarded = AgentOneShotHttp.prepare(request.copy(oneShotAttempt = lease), outgoing(request))
        assertTrue(guarded.body!!.isOneShot())
        assertTrue(lease.consumed)
        guarded.body!!.writeTo(okio.Buffer())
        assertThrows(java.io.IOException::class.java) { guarded.body!!.writeTo(okio.Buffer()) }
        assertThrows(AgentModelFailure::class.java) { AgentOneShotHttp.prepare(request.copy(oneShotAttempt = lease), outgoing(request)) }
    }

    @Test fun exactBodyConfigAndSessionChangesFailBeforeConsumption() {
        val request = request()
        for (changed in listOf(
            request.copy(messages = JSONArray().put(AgentConversationCodec.userTextMessage("y".repeat(2000)))),
            request.copy(sessionId = "different"), request.copy(config = request.config.copy(apiKey = "other")),
            request.copy(config = request.config.copy(requestSendLimitBytes = 101)))) {
            val lease = ModelOneShotAttempt.create(request); lease.authorize()
            assertThrows(AgentModelFailure::class.java) { AgentOneShotHttp.prepare(changed.copy(oneShotAttempt = lease), outgoing(changed)) }
            assertFalse(lease.consumed)
        }
    }

    @Test fun expirationAndDuplicateAuthorizationDoNotRenewLease() {
        var now = 1L
        val request = request(); val lease = ModelOneShotAttempt.create(request) { now }
        lease.authorize()
        now += 59_000
        assertThrows(AgentModelFailure::class.java) { lease.authorize() }
        now += 1_000
        assertThrows(AgentModelFailure::class.java) { AgentOneShotHttp.prepare(request.copy(oneShotAttempt = lease), outgoing(request)) }
        assertFalse(lease.consumed)
    }

    @Test fun disabledInvalidHardTokenAndGatewayAreIneligible() {
        val r = request()
        val cases = listOf(
            r.copy(config = r.config.copy(compactionPolicyVersion = 0)) to "MODEL_ONE_SHOT_V4_DISABLED",
            r.copy(config = r.config.copy(requestSendLimitBytes = null)) to "MODEL_REQUEST_SEND_POLICY_INVALID",
            r.copy(config = r.config.copy(requestHardLimitBytes = 101)) to "MODEL_REQUEST_HARD_LIMIT",
            r.copy(config = r.config.copy(contextWindow = 100)) to "MODEL_ONE_SHOT_TOKEN_PRESSURE",
            r.copy(config = r.config.copy(providerName = "WorkBuddy")) to "MODEL_ONE_SHOT_GATEWAY_UNVERIFIED",
            r.copy(config = r.config.copy(extraBodyJson = "{}")) to "MODEL_ONE_SHOT_CUSTOM_OVERRIDES",
        )
        for ((request, code) in cases) {
            val lease = ModelOneShotAttempt.create(request)
            assertEquals(code, lease.eligibleReason)
            assertThrows(AgentModelFailure::class.java) { lease.authorize() }
        }
    }

    @Test fun allEndpointsLoopbackRedirect408503And401OnlyUploadOnce() {
        for ((endpoint, type) in listOf(OpenAiEndpointMode.CHAT_COMPLETIONS to ProviderTypes.OPENAI_COMPATIBLE,
            OpenAiEndpointMode.RESPONSES to ProviderTypes.OPENAI_COMPATIBLE,
            OpenAiEndpointMode.CHAT_COMPLETIONS to ProviderTypes.ANTHROPIC)) {
            for (status in listOf(307, 308, 401, 408, 503)) {
                val calls = AtomicInteger()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    calls.incrementAndGet()
                    exchange.requestBody.use { it.readBytes() }
                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/redirected")
                    exchange.responseHeaders.add("Retry-After", "0")
                    exchange.sendResponseHeaders(status, -1); exchange.close()
                }
                server.start()
                try {
                    val request = request("http://127.0.0.1:${server.address.port}", endpoint, type)
                    val lease = ModelOneShotAttempt.create(request); lease.authorize()
                    val guardedRequest = request.copy(oneShotAttempt = lease)
                    val client = AgentOneShotHttp.client(OkHttpClient(), lease)
                    val guarded = AgentOneShotHttp.prepare(guardedRequest, outgoing(request))
                    client.newCall(guarded).execute().use { assertEquals(status, it.code) }
                    assertEquals("$endpoint HTTP $status", 1, calls.get())
                    assertTrue(lease.consumed)
                } finally { server.stop(0) }
            }
        }
    }

    @Test fun connectionFailureConsumesEvenWhenNoBodyWasWritten() {
        val server = java.net.ServerSocket(0)
        val port = server.localPort; server.close()
        val request = request("http://127.0.0.1:$port")
        val lease = ModelOneShotAttempt.create(request); lease.authorize()
        val client = AgentOneShotHttp.client(OkHttpClient(), lease)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertThrows(java.io.IOException::class.java) {
            client.newCall(AgentOneShotHttp.prepare(request.copy(oneShotAttempt = lease), outgoing(request))).execute().close()
        }
        assertTrue(lease.consumed)
    }

    @Test fun twoMiBBindingUsesStreamingHashAndBoundedPreparationTime() {
        val request = request(chars = 2 * 1024 * 1024).let { it.copy(config = it.config.copy(contextWindow = 10_000_000)) }
        val start = System.nanoTime()
        val lease = ModelOneShotAttempt.create(request)
        assertTrue(lease.requestBytes > 2 * 1024 * 1024)
        assertNull(lease.eligibleReason)
        lease.authorize()
        AgentOneShotHttp.prepare(request.copy(oneShotAttempt = lease), outgoing(request))
        assertTrue("2 MiB bind/inspect must finish within 10 seconds on test host", (System.nanoTime() - start) < 10_000_000_000L)
    }
    @Test fun rejectedOversizedUnicodeBodyRetainsNoFrozenCopy() {
        val r = request().copy(messages = JSONArray().put(AgentConversationCodec.userTextMessage("中".repeat(6 * 1024 * 1024))))
        val lease = ModelOneShotAttempt.create(r)
        assertEquals("MODEL_ONE_SHOT_BINDING_TOO_LARGE", lease.eligibleReason)
        assertTrue(lease.requestBytes > ModelOneShotAttempt.MAX_BINDING_BYTES)
        assertEquals(0, lease.retainedBodyBytes)
    }

    @Test fun expiryDuringFinalBodyHashCannotConsumeLease() {
        var now = 1L
        val r = request()
        val lease = ModelOneShotAttempt.create(r, clockMillis = { now }); lease.authorize()
        val original = outgoing(r)
        val delayed = original.newBuilder().post(object : okhttp3.RequestBody() {
            override fun contentType() = original.body!!.contentType()
            override fun contentLength() = original.body!!.contentLength()
            override fun writeTo(sink: okio.BufferedSink) {
                original.body!!.writeTo(sink)
                now += ModelOneShotAttempt.LEASE_MILLIS
            }
        }).build()
        assertThrows(AgentModelFailure::class.java) { AgentOneShotHttp.prepare(r.copy(oneShotAttempt = lease), delayed) }
        assertFalse(lease.consumed)
    }

    @Test fun accountChangeBeforeConsumptionRejectsAndPreservesUnspentState() {
        val request = request()
        var generation = 1
        val lease = ModelOneShotAttempt.create(request, accountGuardFactory = {
            val captured = generation
            val check: () -> Boolean = { generation == captured }
            check
        })
        lease.authorize(); generation++
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentOneShotHttp.prepare(request.copy(oneShotAttempt = lease), outgoing(request))
        }
        assertEquals("MODEL_ONE_SHOT_ACCOUNT_CHANGED", failure.code)
        assertFalse(lease.consumed)
    }

    @Test fun realProviderEntriesAllConsumeLeaseAndReturn503WithoutFollowing() {
        for ((endpoint, type) in listOf(OpenAiEndpointMode.CHAT_COMPLETIONS to ProviderTypes.OPENAI_COMPATIBLE,
            OpenAiEndpointMode.RESPONSES to ProviderTypes.OPENAI_COMPATIBLE,
            OpenAiEndpointMode.CHAT_COMPLETIONS to ProviderTypes.ANTHROPIC)) {
            val calls = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                calls.incrementAndGet(); exchange.requestBody.use { it.readBytes() }
                assertEquals("v1", exchange.requestHeaders.getFirst("X-Eta-One-Shot"))
                exchange.responseHeaders.add("Retry-After", "0")
                val error = "{\"error\":{\"message\":\"fixture\"}}".toByteArray()
                exchange.sendResponseHeaders(503, error.size.toLong())
                exchange.responseBody.use { it.write(error) }; exchange.close()
            }
            server.start()
            try {
                val request = request("http://127.0.0.1:${server.address.port}", endpoint, type)
                val lease = ModelOneShotAttempt.create(request); lease.authorize()
                val failure = assertThrows(AgentModelFailure::class.java) {
                    ProviderClientFactory.getClient(request.config).complete(request.copy(oneShotAttempt = lease), AgentRunController())
                }
                assertTrue(failure.code.contains("503"))
                assertEquals(1, calls.get()); assertTrue(lease.consumed)
            } finally { server.stop(0) }
        }
    }

    @Test fun fakeFailuresEmptyDsmlAndGatewayReceiveNeverTriggerSecondProviderAttempt() {
        for (code in listOf("MODEL_EMPTY_RESPONSE", "MODEL_TOOL_PROTOCOL_LEAK", "HTTP_408", "HTTP_503", "HTTP_401", "MODEL_CONNECTION_FAILED")) {
            val request = request()
            val lease = ModelOneShotAttempt.create(request); lease.authorize()
            var calls = 0
            val provider = object : AgentProviderClient {
                override val id = "fake-one-shot"
                override val capabilities = OpenAiChatCompletionsProvider.capabilities
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    calls++
                    AgentOneShotHttp.prepare(request, outgoing(request))
                    if (code == "MODEL_EMPTY_RESPONSE" || code == "MODEL_TOOL_PROTOCOL_LEAK") return ProviderResponse(org.json.JSONObject().put("finish_reason", "stop"))
                    throw AgentModelFailure(code, true, "fixture", receiveRetryAllowed = true)
                }
            }
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, _ -> fail("Lease cannot retry") }.complete(1, request.copy(oneShotAttempt = lease), provider,
                    AgentRunController(), {}, { _, _ -> }, {}, validateResponse = { throw AgentModelFailure(code, true, "fixture") })
            }
            assertEquals(code, failure.code)
            assertEquals(1, calls); assertTrue(lease.consumed)
            assertFalse(failure.retryable); assertFalse(failure.receiveRetryAllowed); assertFalse(failure.contextRecoveryAllowed)
        }
    }

    @Test fun oauth401DoesNotRefreshOrMakeSecondPhysicalCall() {
        val request = request().let { it.copy(config = it.config.copy(
            baseUrl = io.github.mangi.eta.data.provider.ProviderConnectionProfile.OPENAI_CODEX.baseUrl,
            authMode = ProviderAuthModes.OPENAI_CHATGPT, openAiEndpointMode = OpenAiEndpointMode.RESPONSES)) }
        val lease = ModelOneShotAttempt.create(request, accountGuardFactory = { { true } }); lease.authorize()
        var calls = 0
        var tokenReads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("unauthorized".toResponseBody()).build()
        }.build()
        val code = AuthorizedProviderHttp.execute(client, request.config.providerId, request.config.authMode,
            outgoing(request), AgentRunController(), accessToken = { _, _, _, rejected ->
                assertNull(rejected); tokenReads++; io.github.mangi.eta.agent.auth.CodexOAuthFixture.accessToken()
            }, modelRequest = request.copy(oneShotAttempt = lease)) { it.code }
        assertEquals(401, code); assertEquals(1, calls); assertEquals(1, tokenReads); assertTrue(lease.consumed)
    }

}
