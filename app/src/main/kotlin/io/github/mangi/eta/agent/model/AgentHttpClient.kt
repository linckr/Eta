package io.github.mangi.eta.agent.model

import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .eventListenerFactory(ModelHttpTrace.factory)
            .build()
    }

    private val compactionClient: OkHttpClient by lazy {
        modelClient.newBuilder()
            .callTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun modelClient(purpose: ProviderRequestPurpose, freshConnection: Boolean = false): OkHttpClient {
        val shared = if (purpose == ProviderRequestPurpose.COMPACTION) compactionClient else modelClient
        return if (freshConnection) shared.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build() else shared
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
