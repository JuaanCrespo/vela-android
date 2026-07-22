package com.vela.android.lab.data.market.source.alpaca

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.source.MarketDataClient
import com.vela.android.lab.data.market.source.MarketDataConnectionStatus
import com.vela.android.lab.data.market.source.MarketDataError
import com.vela.android.lab.data.market.source.MarketDataSource
import com.vela.android.lab.data.market.source.StreamHealth
import com.vela.android.lab.data.market.source.StreamHealthTracker
import com.vela.android.lab.data.market.tick.MarketTick
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only [MarketDataClient] for the Alpaca Market Data **IEX
 * stream** at `wss://stream.data.alpaca.markets/v2/iex`.
 *
 * Phase 2.e safety contract:
 *
 *  - The constructor calls
 *    [AlpacaStreamEndpoint.requireSafeMarketDataEndpoint] on the
 *    endpoint. Only the test stream and the IEX stream pass; the
 *    SIP variants, the trading hosts, and every
 *    `/orders|/positions|/account|/trading|/portfolio` fragment
 *    are rejected at construction time.
 *  - This class **has no method that submits an order**, mutates
 *    account state, or performs any trading action. The Phase 2.a
 *    reflection-based contract tests cover this class for the same
 *    forbidden-method substrings as the Phase 2.b client and the
 *    Phase 2.d bridge.
 *  - When the [credentialsProvider] returns `null`, the client
 *    transitions immediately to
 *    [MarketDataConnectionStatus.State.ERROR] with
 *    [MarketDataError.AuthenticationFailed]. No WebSocket is
 *    opened and no network call is made.
 *  - Subscriptions are limited to `bars` and `quotes` only — the
 *    auth/subscribe messages this client emits never reference
 *    trade-execution channels.
 *
 * The default symbol set is empty until the caller subscribes. The
 * Phase 2.e dashboard subscribes to [AlpacaStreamEndpoint.STOCK_PRIMARY_SYMBOL]
 * (= `SPY`) before opening the socket.
 */
class AlpacaStockMarketDataClient(
    private val credentialsProvider: AlpacaCredentialsProvider,
    private val webSocketFactory: AlpacaWebSocketFactory,
    private val parser: AlpacaStreamMessageParser = AlpacaStreamMessageParser(),
    private val clock: () -> Instant = { Instant.now() },
    private val endpoint: String = AlpacaStreamEndpoint.IEX_STREAM_URL,
) : MarketDataClient {

    init {
        AlpacaStreamEndpoint.requireSafeMarketDataEndpoint(endpoint)
    }

    override val source: MarketDataSource = MarketDataSource.ALPACA_STOCK_IEX

    private val _connectionStatus: MutableStateFlow<MarketDataConnectionStatus> =
        MutableStateFlow(MarketDataConnectionStatus.disconnected(source, clock()))

    override val connectionStatus: StateFlow<MarketDataConnectionStatus> =
        _connectionStatus.asStateFlow()

    private val _updates: MutableSharedFlow<BootstrapMarketUpdate> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

    override val updates: SharedFlow<BootstrapMarketUpdate> = _updates.asSharedFlow()

    /**
     * Phase 2.i quote stream. Hot, replay=0. Each emission is a
     * [MarketTick] produced from an Alpaca `T="q"` frame. No
     * trading-shape data crosses this flow.
     */
    private val _quotes: MutableSharedFlow<MarketTick> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 256)

    val quotes: SharedFlow<MarketTick> = _quotes.asSharedFlow()

    private val healthTracker: StreamHealthTracker = StreamHealthTracker(
        endpoint = endpoint,
        feedLabel = "Alpaca stock (IEX)",
        clock = clock,
    )

    /** Phase 2.f read-only diagnostic snapshot. Hot StateFlow. */
    val health: StateFlow<StreamHealth> = healthTracker.health

    private val subscribedLock = Any()
    private val subscribed: MutableSet<String> = linkedSetOf()

    @Volatile
    private var handle: AlpacaWebSocketHandle? = null

    @Volatile
    private var currentCredentials: AlpacaCredentials? = null

    private val sequenceCounter: AtomicInteger = AtomicInteger(0)

    /** Phase 2.f lifecycle mutex — prevents concurrent connect/disconnect races. */
    private val lifecycleMutex: Mutex = Mutex()

    /**
     * Phase 2.h session counter. See [AlpacaTestStreamMarketDataClient]
     * for the design — same protection here so a late `onFailure`
     * from a stopped session cannot demote a fresh session to ERROR.
     */
    private val sessionGen: AtomicInteger = AtomicInteger(0)

    @Volatile
    private var activeSessionId: Int = -1

    @Volatile
    private var intentionalCloseSessionId: Int = -1

    /** Read-only view of the resolved feed URL. Used by the dashboard. */
    val feedUrl: String get() = endpoint

    override suspend fun connect() {
        lifecycleMutex.withLock {
            when (_connectionStatus.value.state) {
                MarketDataConnectionStatus.State.CONNECTING,
                MarketDataConnectionStatus.State.CONNECTED -> return
                else -> Unit
            }
            val credentials = credentialsProvider.read()
            if (credentials == null) {
                healthTracker.onConnectRequested()
                healthTracker.onError(
                    type = "AuthenticationFailed",
                    message = "No Alpaca credentials configured for the stock IEX stream.",
                )
                _connectionStatus.value = MarketDataConnectionStatus.error(
                    source = source,
                    error = MarketDataError.AuthenticationFailed(
                        "No Alpaca credentials configured for the stock IEX stream.",
                    ),
                    now = clock(),
                )
                return
            }
            val sessionId = sessionGen.incrementAndGet()
            activeSessionId = sessionId
            currentCredentials = credentials
            healthTracker.onConnectRequested()
            _connectionStatus.value = MarketDataConnectionStatus.connecting(source, clock())
            handle = webSocketFactory.open(endpoint, ListenerBridge(sessionId))
        }
    }

    override suspend fun disconnect() {
        lifecycleMutex.withLock {
            intentionalCloseSessionId = activeSessionId
            handle?.close()
            handle = null
            currentCredentials = null
            _connectionStatus.value = MarketDataConnectionStatus.disconnected(source, clock())
            healthTracker.onUserStop()
        }
    }

    override suspend fun subscribe(symbols: Set<String>) {
        val normalized = symbols
            .map { normalizeMarketSymbol(it) }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return
        synchronized(subscribedLock) { subscribed += normalized }
        if (_connectionStatus.value.state == MarketDataConnectionStatus.State.CONNECTED) {
            handle?.send(buildSubscribeMessage(normalized))
        }
    }

    override suspend fun unsubscribe(symbols: Set<String>) {
        val normalized = symbols
            .map { normalizeMarketSymbol(it) }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return
        synchronized(subscribedLock) { subscribed -= normalized.toSet() }
        if (_connectionStatus.value.state == MarketDataConnectionStatus.State.CONNECTED) {
            handle?.send(buildUnsubscribeMessage(normalized))
        }
    }

    override fun subscribedSymbols(): Set<String> =
        synchronized(subscribedLock) { subscribed.toSet() }

    // --- Wire-format helpers -----------------------------------------

    private fun buildAuthMessage(credentials: AlpacaCredentials): String =
        JSONObject().apply {
            put("action", "auth")
            put("key", credentials.keyId)
            put("secret", credentials.secret)
        }.toString()

    private fun buildSubscribeMessage(symbols: Collection<String>): String {
        val asArray = JSONArray()
        for (sym in symbols) asArray.put(sym)
        return JSONObject().apply {
            put("action", "subscribe")
            put("bars", asArray)
            put("quotes", asArray)
        }.toString()
    }

    private fun buildUnsubscribeMessage(symbols: Collection<String>): String {
        val asArray = JSONArray()
        for (sym in symbols) asArray.put(sym)
        return JSONObject().apply {
            put("action", "unsubscribe")
            put("bars", asArray)
            put("quotes", asArray)
        }.toString()
    }

    private fun barToUpdate(bar: AlpacaStreamMessage.Bar): BootstrapMarketUpdate =
        BootstrapMarketUpdate(
            symbol = normalizeMarketSymbol(bar.symbol),
            sequence = sequenceCounter.incrementAndGet(),
            price = bar.close,
            change = bar.close - bar.open,
            timestamp = bar.timestamp,
            source = "alpaca-iex-stream",
            open = bar.open,
            high = bar.high,
            low = bar.low,
            close = bar.close,
            volume = bar.volume,
        )

    // --- Listener-side message handling ------------------------------

    internal fun handleStreamMessage(message: AlpacaStreamMessage) {
        healthTracker.onMessage()
        when (message) {
            AlpacaStreamMessage.Connected -> sendAuthMessage()
            AlpacaStreamMessage.Authenticated -> onAuthenticated()
            is AlpacaStreamMessage.Subscription -> onSubscriptionConfirmed(message)
            is AlpacaStreamMessage.Quote -> emitQuoteTick(message)
            is AlpacaStreamMessage.Bar -> _updates.tryEmit(barToUpdate(message))
            is AlpacaStreamMessage.StreamError -> onStreamError(message)
            is AlpacaStreamMessage.Unknown -> Unit
        }
    }

    private fun emitQuoteTick(quote: AlpacaStreamMessage.Quote) {
        val tick = MarketTick(
            symbol = quote.symbol,
            bidPrice = quote.bidPrice,
            askPrice = quote.askPrice,
            marketTimestampMillis = quote.timestamp.toEpochMilli(),
            receivedAtMillis = clock().toEpochMilli(),
            source = "alpaca-iex-stream",
        )
        _quotes.tryEmit(tick)
    }

    private fun onSubscriptionConfirmed(message: AlpacaStreamMessage.Subscription) {
        val confirmed = (message.bars + message.quotes).toSet()
        if (confirmed.isNotEmpty()) {
            healthTracker.onSubscribed(confirmed)
        }
    }

    private fun sendAuthMessage() {
        val credentials = currentCredentials
        if (credentials == null) {
            _connectionStatus.value = MarketDataConnectionStatus.error(
                source = source,
                error = MarketDataError.AuthenticationFailed(
                    "No credentials available at auth time.",
                ),
                now = clock(),
            )
            handle?.close()
            handle = null
            return
        }
        handle?.send(buildAuthMessage(credentials))
    }

    private fun onAuthenticated() {
        _connectionStatus.value = MarketDataConnectionStatus.connected(source, clock())
        healthTracker.onAuthenticated()
        val pending = synchronized(subscribedLock) { subscribed.toList() }
        if (pending.isNotEmpty()) {
            handle?.send(buildSubscribeMessage(pending))
        }
    }

    private fun onStreamError(error: AlpacaStreamMessage.StreamError) {
        val mapped = when (error.code) {
            401, 402, 403 -> MarketDataError.AuthenticationFailed(error.message)
            405, 406, 409, 410 -> MarketDataError.SubscriptionRejected(error.message, symbol = "")
            else -> MarketDataError.Unknown("[${error.code}] ${error.message}")
        }
        _connectionStatus.value = MarketDataConnectionStatus.error(source, mapped, clock())
        healthTracker.onError(
            type = mapped::class.simpleName ?: "Unknown",
            message = mapped.message,
        )
    }

    private inner class ListenerBridge(
        private val sessionId: Int,
    ) : AlpacaWebSocketListener {

        private fun isStale(): Boolean = sessionId != activeSessionId
        private fun isIntentionalClose(): Boolean = sessionId == intentionalCloseSessionId

        override fun onOpen() {
            // Wait for the server "connected" message before sending auth.
        }

        override fun onMessage(text: String) {
            if (isStale()) return
            val parsed = parser.parse(text)
            for (message in parsed) {
                handleStreamMessage(message)
            }
        }

        override fun onClosed(code: Int, reason: String) {
            if (isStale()) return
            handle = null
            currentCredentials = null
            _connectionStatus.value = MarketDataConnectionStatus.disconnected(source, clock())
            if (isIntentionalClose()) {
                healthTracker.onUserStop()
            } else {
                healthTracker.onDisconnected()
            }
        }

        override fun onFailure(throwable: Throwable?, response: String?) {
            if (isStale()) return
            if (isIntentionalClose()) {
                handle = null
                currentCredentials = null
                _connectionStatus.value = MarketDataConnectionStatus.disconnected(source, clock())
                healthTracker.onUserStop()
                return
            }
            handle = null
            currentCredentials = null
            val message = throwable?.message ?: response ?: "Stream failure"
            _connectionStatus.value = MarketDataConnectionStatus.error(
                source = source,
                error = MarketDataError.StreamLost(message),
                now = clock(),
            )
            healthTracker.onError(type = "StreamLost", message = message)
        }
    }
}
