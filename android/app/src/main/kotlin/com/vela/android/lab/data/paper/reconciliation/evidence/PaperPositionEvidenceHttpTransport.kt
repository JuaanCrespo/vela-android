package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

enum class PaperCaptureEndpoint(val url: String) {
    ACCOUNT(AlpacaPaperTradingEndpoint.ACCOUNT_URL), POSITIONS(AlpacaPaperTradingEndpoint.POSITIONS_URL),
}

/** Never include raw response content in toString, diagnostics or persistence. */
class CaptureHttpResponse(val code: Int?, val body: String?, val failure: CaptureDiagnostic? = null) {
    override fun toString(): String = "CaptureHttpResponse(code=$code, failure=$failure, body=REDACTED)"
}

class PaperCaptureSession internal constructor(internal val credentials: AlpacaCredentials?, val configRef: String, val sessionRef: String) {
    override fun toString(): String = "PaperCaptureSession(configRef=$configRef)"
}

/** Changes observed at each boundary advance a counter, never a secret-derived hash. */
class PaperCaptureConfiguration(private val provider: AlpacaCredentialsProvider) {
    private val mutex = Mutex()
    private val sessionRef = UUID.randomUUID().toString()
    private var generation = 0L
    private var previous: AlpacaCredentials? = null
    suspend fun read(): PaperCaptureSession = mutex.withLock {
        val current = provider.read()
        if (current != previous) { generation++; previous = current }
        PaperCaptureSession(current, "$sessionRef:$generation", sessionRef)
    }
    suspend fun isCurrent(session: PaperCaptureSession): Boolean = read().configRef == session.configRef
}

fun interface PaperCaptureTransport {
    suspend fun get(endpoint: PaperCaptureEndpoint, session: PaperCaptureSession): CaptureHttpResponse
}

/** Narrower than the existing GET client. No URL/verb is supplied by a caller. */
class PaperPositionEvidenceHttpTransport internal constructor(private val testCalls: Call.Factory?) : PaperCaptureTransport {
    constructor() : this(null)

    override suspend fun get(endpoint: PaperCaptureEndpoint, session: PaperCaptureSession): CaptureHttpResponse {
        val credentials = session.credentials ?: return CaptureHttpResponse(null, null, CaptureDiagnostic.AUTH_FAILURE)
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(endpoint.url)
        val request = Request.Builder().url(endpoint.url).get()
            .header("APCA-API-KEY-ID", credentials.keyId).header("APCA-API-SECRET-KEY", credentials.secret)
            .header("Accept", "application/json").build()
        return withContext(Dispatchers.IO) {
            try {
                val exchanges = AtomicInteger()
                val calls = testCalls ?: strictClient().newBuilder().addNetworkInterceptor { chain ->
                    // Defense against any HTTP follow-up (including Retry-After) inside OkHttp.
                    if (exchanges.incrementAndGet() != 1) throw IOException("Additional exchange refused")
                    chain.proceed(chain.request())
                }.build()
                calls.newCall(request).execute().use { response ->
                    when {
                        response.code in setOf(401, 403) -> CaptureHttpResponse(response.code, null, CaptureDiagnostic.AUTH_FAILURE)
                        response.code != 200 -> CaptureHttpResponse(response.code, null, CaptureDiagnostic.HTTP_FAILURE)
                        response.body == null -> CaptureHttpResponse(response.code, null)
                        response.body!!.source().request(2_097_153) -> CaptureHttpResponse(response.code, null, CaptureDiagnostic.INCOMPLETE_RESPONSE)
                        else -> CaptureHttpResponse(response.code, response.body!!.string())
                    }
                }
            } catch (_: IOException) { CaptureHttpResponse(null, null, CaptureDiagnostic.NETWORK_FAILURE) }
        }
    }

    companion object {
        internal fun strictClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
}
