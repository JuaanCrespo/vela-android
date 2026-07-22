@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.HttpResult
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.DisabledExecutionStatus
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftStatus
import com.vela.android.lab.data.paper.preflight.PreviewQueueFakeDao
import com.vela.android.lab.data.paper.preflight.PreflightBlockReason
import com.vela.android.lab.data.paper.preflight.PreflightStatus
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.InMemoryWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import com.vela.android.lab.state.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperOrderPreflightViewModelTest {

    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val testCreds = AlpacaCredentials("PKABCDEF1234", "topsecretvalue")

    private fun newVm(
        responses: Map<String, String>,
        store: PreflightInMemoryStore = PreflightInMemoryStore().apply { runBlockingSave(testCreds) },
        watchlist: Set<String> = setOf("SPY"),
        marketDao: MarketBarDao = PreflightFakeMarketBarDao().also { dao ->
            runBlocking {
                dao.insert(
                    MarketBar1mEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 1L,
                        open = 1.0, high = 1.0, low = 1.0, close = 520.0,
                        updateCount = 1, syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
        },
        signalDao: SignalDao = PreflightFakeSignalDao().also { dao ->
            runBlocking {
                dao.insert(
                    SymbolSignalEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 1L,
                        state = "BULLISH", score = 2,
                        shortReturn = 0.0, percentChange = 0.0, barRange = 0.0,
                        direction = "up",
                    ),
                )
            }
        },
        previewDao: PreviewQueueFakeDao? = null,
        onPayloadPreviewSaved: suspend () -> Unit = {},
    ): PaperOrderPreflightViewModel {
        val httpClient = PreflightTrackingHttpClient(responses)
        val client = AlpacaPaperReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = httpClient,
        )
        return PaperOrderPreflightViewModel(
            engine = PaperOrderPreflightEngine(),
            client = client,
            credentialsStore = store,
            watchlistRepository = WatchlistRepository(InMemoryWatchlistStore(watchlist)),
            marketDataRepository = MarketDataRepository(marketDao),
            signalRepository = SignalRepository(signalDao),
            appState = AppState(),
            payloadPreviewRepository = previewDao?.let(::PaperOrderPayloadPreviewRepository),
            onPayloadPreviewSaved = onPayloadPreviewSaved,
        )
    }

    private fun okResponses(
        equity: Double = 100_000.0,
        buyingPower: Double = 200_000.0,
        portfolioValue: Double = 100_000.0,
        marketOpen: Boolean = true,
        positionsJson: String = "[]",
    ): Map<String, String> = mapOf(
        AlpacaPaperTradingEndpoint.ACCOUNT_URL to
            """{"status":"ACTIVE","equity":"$equity","cash":"50000","buying_power":"$buyingPower","portfolio_value":"$portfolioValue"}""",
        AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":$marketOpen}""",
        AlpacaPaperTradingEndpoint.POSITIONS_URL to positionsJson,
    )

    @Test
    fun `runDryRunPreflight populates lastResult with ALLOWED_DRY_RUN`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onSideChange(OrderSide.BUY)
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()

            val result = vm.uiState.value.lastResult
            assertNotNull(result)
            assertEquals(PreflightStatus.ALLOWED_DRY_RUN, result!!.status)
            assertEquals(520.0, result.estimatedNotionalUsd)
            assertEquals(1.0, result.positionImpactQty)
            // No order submission code path; VM only called readonly GETs.
        }

    @Test
    fun `invalid quantity input shows lastInputError without running engine`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("not a number")
            vm.runDryRunPreflight()
            val s = vm.uiState.value
            assertEquals("Quantity must be a number.", s.lastInputError)
            assertNull(s.lastResult)
        }

    @Test
    fun `empty symbol input shows lastInputError`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            val s = vm.uiState.value
            assertEquals("Symbol is required.", s.lastInputError)
            assertNull(s.lastResult)
        }

    @Test
    fun `insufficient buying power produces BLOCKED via engine`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses(buyingPower = 100.0))
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("10")  // notional = 5200 > 100
            vm.runDryRunPreflight()
            val result = vm.uiState.value.lastResult!!
            assertEquals(PreflightStatus.BLOCKED, result.status)
            assertTrue(result.blockReasons.any { it is PreflightBlockReason.InsufficientBuyingPower })
        }

    @Test
    fun `UI state never carries credential value after dry-run`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            val serialised = vm.uiState.value.toString()
            assertFalse(serialised.contains("topsecretvalue"))
            assertFalse(serialised.contains("PKABCDEF1234"))
        }

    @Test
    fun `approved preflight builds execution-disabled local draft`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()

            vm.buildLocalDraft()

            val state = vm.uiState.value
            assertNull(state.lastDraftError)
            assertNotNull(state.lastDraft)
            assertEquals(PaperOrderRequestDraftStatus.READY_LOCAL, state.lastDraft!!.status)
            assertEquals("SPY", state.lastDraft!!.symbol)
            assertEquals(520.0, state.lastDraft!!.estimatedNotionalUsd)
            assertFalse(state.lastDraft!!.executionEnabled)
        }

    @Test
    fun `blocked preflight cannot build local draft`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses(buyingPower = 100.0))
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("10")
            vm.runDryRunPreflight()

            vm.buildLocalDraft()

            val state = vm.uiState.value
            assertNull(state.lastDraft)
            assertNotNull(state.lastDraftError)
            assertTrue(state.lastDraftError!!.contains("rejected", ignoreCase = true))

            vm.buildPayloadPreview()
            assertNull(vm.uiState.value.lastPayloadPreview)
            assertNotNull(vm.uiState.value.lastPayloadPreviewError)
        }

    @Test
    fun `local draft builds immutable disabled payload preview`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            vm.buildLocalDraft()

            vm.buildPayloadPreview()

            val preview = vm.uiState.value.lastPayloadPreview
            assertNotNull(preview)
            assertEquals(PaperOrderPayloadPreviewStatus.READY_PREVIEW, preview!!.status)
            assertEquals("SPY", preview.symbol)
            assertEquals(520.0, preview.estimatedNotionalUsd)
            assertFalse(preview.executionEnabled)
            assertEquals("DISABLED", preview.endpointPreview)
            assertEquals("POST_DISABLED", preview.httpMethodPreview)
            assertNull(vm.uiState.value.lastPayloadPreviewError)
        }

    @Test
    fun `payload preview appends exactly one queue row and refresh callback fires`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            var refreshCount = 0
            val vm = newVm(
                responses = okResponses(),
                previewDao = dao,
                onPayloadPreviewSaved = { refreshCount += 1 },
            )
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            vm.buildLocalDraft()

            vm.buildPayloadPreview()

            assertEquals(1, dao.rows.size)
            assertEquals(1, refreshCount)
            assertEquals(vm.uiState.value.lastPayloadPreview!!.previewId, dao.rows.single().previewId)
            assertFalse(dao.rows.single().executionEnabled)
        }

    @Test
    fun `payload preview readiness is locally ready but execution disabled`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            vm.buildLocalDraft()
            vm.buildPayloadPreview()

            vm.checkExecutionReadiness()

            val readiness = vm.uiState.value.lastExecutionReadiness
            assertNotNull(readiness)
            assertEquals(
                PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED,
                readiness!!.status,
            )
            assertFalse(readiness.executionEnabled)
            assertTrue(readiness.realLocked)
            assertFalse(readiness.paperPostOrdersAllowed)
            assertFalse(readiness.liveEndpointAllowed)
            assertFalse(readiness.autoPaperEnabled)
            assertFalse(readiness.foregroundServiceEnabled)
            assertTrue(readiness.credentialsConfigured)
        }

    @Test
    fun `disabled execution attempt produces only local rejection`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            vm.buildLocalDraft()
            vm.buildPayloadPreview()
            vm.checkExecutionReadiness()

            vm.attemptDisabledExecution()

            val result = vm.uiState.value.lastDisabledExecutionResult
            assertNotNull(result)
            assertEquals(DisabledExecutionStatus.EXECUTION_DISABLED, result!!.result)
            assertEquals(vm.uiState.value.lastPayloadPreview!!.previewId, result.previewId)
            assertFalse(vm.uiState.value.toString().contains("topsecretvalue"))
            assertFalse(vm.uiState.value.toString().contains("PKABCDEF1234"))
        }

    @Test
    fun `readiness cannot run before a payload preview exists`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())

            vm.checkExecutionReadiness()

            assertNull(vm.uiState.value.lastExecutionReadiness)
            assertNotNull(vm.uiState.value.lastExecutionReadinessError)
            assertNull(vm.uiState.value.lastDisabledExecutionResult)
        }

    @Test
    fun `form edit invalidates prior preflight and draft`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.runDryRunPreflight()
            vm.buildLocalDraft()
            vm.buildPayloadPreview()
            vm.checkExecutionReadiness()
            vm.attemptDisabledExecution()
            assertNotNull(vm.uiState.value.lastDraft)
            assertNotNull(vm.uiState.value.lastPayloadPreview)
            assertNotNull(vm.uiState.value.lastExecutionReadiness)
            assertNotNull(vm.uiState.value.lastDisabledExecutionResult)

            vm.onQuantityInputChange("2")

            assertNull(vm.uiState.value.lastResult)
            assertNull(vm.uiState.value.lastDraft)
            assertNull(vm.uiState.value.lastDraftError)
            assertNull(vm.uiState.value.lastPayloadPreview)
            assertNull(vm.uiState.value.lastPayloadPreviewError)
            assertNull(vm.uiState.value.lastExecutionReadiness)
            assertNull(vm.uiState.value.lastExecutionReadinessError)
            assertNull(vm.uiState.value.lastDisabledExecutionResult)
        }

    @Test
    fun `no method on PaperOrderPreflightViewModel has an execution-shape name`() {
        // HTTP-verb substrings (`put`, `post`) are intentionally
        // excluded here because Compose UI methods legitimately
        // contain "input" / "out" / etc. The HTTP-verb prohibition
        // is enforced at the `AlpacaHttpClient` interface surface
        // (which still exposes only `executeGet`) — covered by
        // `AlpacaPaperReadOnlyClientTest`.
        val forbidden = listOf(
            "submitorder", "placeorder", "executeorder", "cancelorder",
            "replaceorder", "openposition", "closeposition", "trading",
        )
        val methods = PaperOrderPreflightViewModel::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "VM method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}

// --- Test doubles -----------------------------------------------------

private class PreflightInMemoryStore : SecureAlpacaCredentialsStore {
    @Volatile private var creds: AlpacaCredentials? = null
    fun runBlockingSave(c: AlpacaCredentials) { creds = c }
    override suspend fun save(credentials: AlpacaCredentials) { creds = credentials }
    override suspend fun load(): AlpacaCredentials? = creds
    override suspend fun clear() { creds = null }
    override suspend fun hasCredentials(): Boolean = creds != null
}

private class PreflightTrackingHttpClient(
    private val responses: Map<String, String>,
) : AlpacaHttpClient {
    val urls: MutableList<String> = mutableListOf()
    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        urls += url
        val body = responses[url] ?: return HttpResult.HttpError(404, "no stub")
        return HttpResult.Success(200, body)
    }
}

private class PreflightFakeMarketBarDao : MarketBarDao {
    private val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}

private class PreflightFakeSignalDao : SignalDao {
    private val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(signal: SymbolSignalEntity): Long {
        val stored = if (signal.id == 0L) signal.copy(id = nextId++) else signal
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long> = signals.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolSignalEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun latestFor(symbol: String): SymbolSignalEntity? = rows.lastOrNull { it.symbol == symbol }
    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.state == state }.takeLast(limit)
    override suspend fun clear() { rows.clear() }
}
