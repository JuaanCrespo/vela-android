@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.tick.MarketTick
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.HttpResult
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.DisabledExecutionStatus
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftStatus
import com.vela.android.lab.data.paper.preflight.PreviewQueueFakeDao
import com.vela.android.lab.data.paper.preflight.PreflightBlockReason
import com.vela.android.lab.data.paper.preflight.PreflightStatus
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitError
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.InMemoryWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.db.room.dao.PaperOrderPayloadPreviewDao
import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import com.vela.android.lab.state.AppState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        httpClient: AlpacaHttpClient = PreflightTrackingHttpClient(responses),
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
        previewDao: PaperOrderPayloadPreviewDao? = null,
        auditDao: PaperOrderDryRunAuditDao? = PreflightFakeAuditDao(),
        includeLiveQuote: Boolean = true,
        liveQuoteAgeMillis: Long = 500L,
        onPayloadPreviewSaved: suspend () -> Unit = {},
    ): PaperOrderPreflightViewModel {
        val nowMillis = 100_000L
        val client = AlpacaPaperReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = httpClient,
        )
        val marketDataRepository = MarketDataRepository(marketDao)
        val tickBuffer = MarketTickBuffer()
        if (includeLiveQuote) {
            val receivedAt = nowMillis - liveQuoteAgeMillis
            tickBuffer.pushQuote(
                MarketTick(
                    symbol = "SPY",
                    bidPrice = 519.90,
                    askPrice = 520.10,
                    marketTimestampMillis = receivedAt - 100L,
                    receivedAtMillis = receivedAt,
                    source = "alpaca-iex-stream",
                ),
            )
        }
        return PaperOrderPreflightViewModel(
            engine = PaperOrderPreflightEngine(),
            client = client,
            credentialsStore = store,
            watchlistRepository = WatchlistRepository(InMemoryWatchlistStore(watchlist)),
            marketDataRepository = marketDataRepository,
            signalRepository = SignalRepository(signalDao),
            appState = AppState(),
            auditRepository = auditDao?.let(::PaperOrderDryRunAuditRepository),
            priceSnapshotProvider = MarketPriceSnapshotProvider(
                tickBuffer = tickBuffer,
                marketDataRepository = marketDataRepository,
                clock = { Instant.ofEpochMilli(nowMillis) },
            ),
            payloadPreviewRepository = previewDao?.let(::PaperOrderPayloadPreviewRepository),
            onPayloadPreviewSaved = onPayloadPreviewSaved,
            clock = { Instant.ofEpochMilli(nowMillis) },
        )
    }

    private fun okResponses(
        equity: Double = 100_000.0,
        buyingPower: Double = 200_000.0,
        portfolioValue: Double = 100_000.0,
        marketOpen: Boolean = true,
        accountStatus: String = "ACTIVE",
        tradingBlocked: Boolean = false,
        accountBlocked: Boolean = false,
        positionsJson: String = "[]",
    ): Map<String, String> = mapOf(
        AlpacaPaperTradingEndpoint.ACCOUNT_URL to
            """{"status":"$accountStatus","equity":"$equity","cash":"50000","buying_power":"$buyingPower","portfolio_value":"$portfolioValue","trading_blocked":$tradingBlocked,"account_blocked":$accountBlocked}""",
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
    fun `guided preparation reaches safe disabled readiness in one action`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val auditDao = PreflightFakeAuditDao()
            val vm = newVm(okResponses(), previewDao = dao, auditDao = auditDao)
            vm.onSymbolInputChange("SPY")
            vm.onSideChange(OrderSide.BUY)
            vm.onQuantityInputChange("1")

            vm.prepareGuidedLocalChain()

            val state = vm.uiState.value
            assertEquals(PaperGuidedPreparationStage.READY, state.guidedPreparationStage)
            assertFalse(state.isGuidedPreparationRunning)
            assertNull(state.guidedPreparationError)
            assertEquals(PreflightStatus.ALLOWED_DRY_RUN, state.lastResult?.status)
            assertEquals(PaperOrderRequestDraftStatus.READY_LOCAL, state.lastDraft?.status)
            assertEquals(PaperOrderPayloadPreviewStatus.READY_PREVIEW, state.lastPayloadPreview?.status)
            assertEquals(
                PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED,
                state.lastExecutionReadiness?.status,
            )
            assertFalse(state.lastExecutionReadiness!!.executionEnabled)
            assertEquals(1, dao.rows.size)
            assertEquals(1, auditDao.rows.size)
        }

    @Test
    fun `reset for new preparation preserves form and clears derived state without GET`() =
        runTest(UnconfinedTestDispatcher()) {
            val previewDao = PreviewQueueFakeDao()
            val auditDao = PreflightFakeAuditDao()
            val httpClient = PreflightTrackingHttpClient(okResponses())
            val vm = newVm(
                responses = okResponses(),
                httpClient = httpClient,
                previewDao = previewDao,
                auditDao = auditDao,
            )
            vm.onSymbolInputChange("SPY")
            vm.onSideChange(OrderSide.BUY)
            vm.onQuantityInputChange("1")
            vm.prepareGuidedLocalChain()
            assertEquals(PaperGuidedPreparationStage.READY, vm.uiState.value.guidedPreparationStage)
            assertNotNull(vm.uiState.value.lastResult)
            assertNotNull(vm.uiState.value.lastDraft)
            assertNotNull(vm.uiState.value.lastPayloadPreview)
            assertNotNull(vm.uiState.value.lastExecutionReadiness)
            val getCountBeforeReset = httpClient.urls.size
            val previewRowsBeforeReset = previewDao.rows.toList()
            val auditRowsBeforeReset = auditDao.rows.toList()

            vm.resetForNewPreparation()

            val state = vm.uiState.value
            assertEquals("SPY", state.symbolInput)
            assertEquals(OrderSide.BUY, state.side)
            assertEquals("1", state.quantityInput)
            assertEquals(PaperGuidedPreparationStage.IDLE, state.guidedPreparationStage)
            assertFalse(state.isGuidedPreparationRunning)
            assertFalse(state.isRunning)
            assertFalse(state.isBuildingPayloadPreview)
            assertFalse(state.isCheckingExecutionReadiness)
            assertNull(state.lastResult)
            assertNull(state.lastInputError)
            assertNull(state.lastAuditError)
            assertNull(state.lastDraft)
            assertNull(state.lastDraftError)
            assertNull(state.lastPayloadPreview)
            assertNull(state.lastPayloadPreviewError)
            assertNull(state.lastExecutionReadiness)
            assertNull(state.lastExecutionReadinessError)
            assertNull(state.lastDisabledExecutionResult)
            assertNull(state.guidedPreparationError)
            assertEquals(getCountBeforeReset, httpClient.urls.size)
            assertEquals(previewRowsBeforeReset, previewDao.rows)
            assertEquals(auditRowsBeforeReset, auditDao.rows)
        }

    @Test
    fun `reset for new preparation is a no-op while guided work is pending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = newVm(okResponses(), previewDao = PreviewQueueFakeDao())
        vm.onSymbolInputChange("SPY")
        vm.onSideChange(OrderSide.BUY)
        vm.onQuantityInputChange("1")

        vm.prepareGuidedLocalChain()
        val pending = vm.uiState.value
        assertTrue(pending.isGuidedPreparationRunning)
        assertEquals(PaperGuidedPreparationStage.PREFLIGHT, pending.guidedPreparationStage)

        vm.resetForNewPreparation()

        assertEquals(pending, vm.uiState.value)
        advanceUntilIdle()
        assertEquals(PaperGuidedPreparationStage.READY, vm.uiState.value.guidedPreparationStage)
    }

    @Test
    fun `guided preparation stops before draft when preflight is blocked`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val vm = newVm(okResponses(buyingPower = 100.0), previewDao = dao)
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("10")

            vm.prepareGuidedLocalChain()

            val state = vm.uiState.value
            assertEquals(PaperGuidedPreparationStage.BLOCKED, state.guidedPreparationStage)
            assertNotNull(state.guidedPreparationError)
            assertEquals(PreflightStatus.BLOCKED, state.lastResult?.status)
            assertNull(state.lastDraft)
            assertNull(state.lastPayloadPreview)
            assertNull(state.lastExecutionReadiness)
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `guided preparation requires every Paper GET to succeed`() =
        runTest(UnconfinedTestDispatcher()) {
            val requiredUrls = listOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL,
                AlpacaPaperTradingEndpoint.CLOCK_URL,
                AlpacaPaperTradingEndpoint.POSITIONS_URL,
            )
            requiredUrls.forEach { missingUrl ->
                val dao = PreviewQueueFakeDao()
                val vm = newVm(okResponses() - missingUrl, previewDao = dao)
                vm.onSymbolInputChange("SPY")
                vm.onQuantityInputChange("1")

                vm.prepareGuidedLocalChain()

                assertEquals(
                    PaperGuidedPreparationStage.BLOCKED,
                    vm.uiState.value.guidedPreparationStage,
                    "Missing GET must block: $missingUrl",
                )
                assertNull(vm.uiState.value.lastDraft)
                assertNull(vm.uiState.value.lastPayloadPreview)
                assertNull(vm.uiState.value.lastExecutionReadiness)
                assertTrue(dao.rows.isEmpty())
            }
        }

    @Test
    fun `guided preparation requires market open`() = runTest(UnconfinedTestDispatcher()) {
        val dao = PreviewQueueFakeDao()
        val vm = newVm(okResponses(marketOpen = false), previewDao = dao)
        vm.onSymbolInputChange("SPY")
        vm.onQuantityInputChange("1")

        vm.prepareGuidedLocalChain()

        assertEquals(PaperGuidedPreparationStage.BLOCKED, vm.uiState.value.guidedPreparationStage)
        assertNull(vm.uiState.value.lastDraft)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `guided preparation requires an active unblocked account`() =
        runTest(UnconfinedTestDispatcher()) {
            val unsafeAccounts = listOf(
                okResponses(accountStatus = "INACTIVE"),
                okResponses(tradingBlocked = true),
                okResponses(accountBlocked = true),
            )
            unsafeAccounts.forEach { responses ->
                val dao = PreviewQueueFakeDao()
                val vm = newVm(responses, previewDao = dao)
                vm.onSymbolInputChange("SPY")
                vm.onQuantityInputChange("1")

                vm.prepareGuidedLocalChain()

                assertEquals(
                    PaperGuidedPreparationStage.BLOCKED,
                    vm.uiState.value.guidedPreparationStage,
                )
                assertNull(vm.uiState.value.lastDraft)
                assertTrue(dao.rows.isEmpty())
            }
        }

    @Test
    fun `guided preparation requires a fresh live quote midpoint`() =
        runTest(UnconfinedTestDispatcher()) {
            val unsafePriceVms = listOf(
                newVm(
                    okResponses(),
                    previewDao = PreviewQueueFakeDao(),
                    includeLiveQuote = false,
                ),
                newVm(
                    okResponses(),
                    previewDao = PreviewQueueFakeDao(),
                    liveQuoteAgeMillis = 11_000L,
                ),
            )
            unsafePriceVms.forEach { vm ->
                vm.onSymbolInputChange("SPY")
                vm.onQuantityInputChange("1")

                vm.prepareGuidedLocalChain()

                assertEquals(
                    PaperGuidedPreparationStage.BLOCKED,
                    vm.uiState.value.guidedPreparationStage,
                )
                assertNull(vm.uiState.value.lastDraft)
                assertNull(vm.uiState.value.lastPayloadPreview)
            }
        }

    @Test
    fun `guided preparation requires the dry run audit to be persisted`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val vm = newVm(okResponses(), previewDao = dao, auditDao = null)
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")

            vm.prepareGuidedLocalChain()

            assertEquals(PaperGuidedPreparationStage.BLOCKED, vm.uiState.value.guidedPreparationStage)
            assertNull(vm.uiState.value.lastDraft)
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `guided preparation stops before readiness when preview persistence fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(okResponses(), previewDao = FailingPreviewQueueDao())
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")

            vm.prepareGuidedLocalChain()

            val state = vm.uiState.value
            assertEquals(PaperGuidedPreparationStage.BLOCKED, state.guidedPreparationStage)
            assertEquals(PaperOrderPayloadPreviewStatus.READY_PREVIEW, state.lastPayloadPreview?.status)
            assertNotNull(state.lastPayloadPreviewError)
            assertNull(state.lastExecutionReadiness)
        }

    @Test
    fun `guided preparation ignores a double tap and persists one preview`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val dao = PreviewQueueFakeDao()
        val vm = newVm(okResponses(), previewDao = dao)
        vm.onSymbolInputChange("SPY")
        vm.onQuantityInputChange("1")

        vm.prepareGuidedLocalChain()
        vm.prepareGuidedLocalChain()
        advanceUntilIdle()

        assertEquals(PaperGuidedPreparationStage.READY, vm.uiState.value.guidedPreparationStage)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `prepared preview synchronization rejects stale manual selection`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val vm = newVm(okResponses(), previewDao = dao)
            vm.onSymbolInputChange("SPY")
            vm.onQuantityInputChange("1")
            vm.prepareGuidedLocalChain()
            val preflight = vm.uiState.value
            val preparedId = preflight.lastPayloadPreview!!.previewId
            val synchronizedManual = PaperManualSubmitUiState.initial(true).copy(
                previewId = preparedId,
                preflightStatus = PreflightStatus.ALLOWED_DRY_RUN.name,
                readinessStatus =
                    PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED.name,
                orderTrackingRestoreComplete = true,
            )

            assertTrue(preparedPreviewIsSynchronized(preflight, synchronizedManual))
            assertFalse(
                preparedPreviewIsSynchronized(
                    preflight,
                    synchronizedManual.copy(previewId = "stale-preview"),
                ),
            )
            assertFalse(
                preparedPreviewIsSynchronized(
                    preflight,
                    synchronizedManual.copy(readinessStatus = "NOT_CHECKED"),
                ),
            )
        }

    @Test
    fun `manual confirmation UI allows typing only for the exact expected blockers`() {
        val required = "SUBMIT PAPER SPY BUY 1"
        val awaiting = PaperManualSubmitUiState.initial(true).copy(
            sessionArmed = true,
            finalPriceGateResult = "ALLOWED",
            finalPriceRawAgeMillis = -1_499L,
            requiredConfirmationText = required,
            gateReasons = listOf(
                PaperOrderSubmitError.PREVIEW_MISMATCH,
                PaperOrderSubmitError.CONFIRMATION_MISSING,
            ),
        )

        assertTrue(paperManualConfirmationUiGate(awaiting).mayType)
        assertFalse(paperManualConfirmationUiGate(awaiting).maySubmit)

        val prudentAbortBoundary = awaiting.copy(finalPriceRawAgeMillis = -1_500L)
        assertFalse(paperManualConfirmationUiGate(prudentAbortBoundary).mayType)
        assertFalse(paperManualConfirmationUiGate(prudentAbortBoundary).maySubmit)

        val unexpected = awaiting.copy(
            gateReasons = listOf(PaperOrderSubmitError.MARKET_CLOSED),
        )
        assertFalse(paperManualConfirmationUiGate(unexpected).mayType)
        assertFalse(paperManualConfirmationUiGate(unexpected).maySubmit)

        val ready = awaiting.copy(
            confirmationInput = required,
            confirmationExpiresAtEpochMillis = 100_000L,
            gateAllowed = true,
            gateReasons = emptyList(),
        )
        assertFalse(paperManualConfirmationUiGate(ready, nowEpochMillis = 99_999L).mayType)
        assertTrue(paperManualConfirmationUiGate(ready, nowEpochMillis = 100_000L).maySubmit)
        assertFalse(paperManualConfirmationUiGate(ready, nowEpochMillis = 100_001L).maySubmit)
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

private class PreflightFakeAuditDao : PaperOrderDryRunAuditDao {
    val rows: MutableList<PaperOrderDryRunAuditEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(audit: PaperOrderDryRunAuditEntity): Long {
        val stored = if (audit.id == 0L) audit.copy(id = nextId++) else audit
        rows += stored
        return stored.id
    }

    override suspend fun countAll(): Int = rows.size

    override suspend fun recent(limit: Int): List<PaperOrderDryRunAuditEntity> =
        rows.sortedByDescending { it.createdAtEpochMillis }.take(limit)

    override suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderDryRunAuditEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.createdAtEpochMillis }
            .take(limit)
}

private class FailingPreviewQueueDao : PaperOrderPayloadPreviewDao {
    override suspend fun insert(preview: PaperOrderPayloadPreviewEntity): Long =
        error("simulated local persistence failure")

    override suspend fun countAll(): Int = 0

    override suspend fun recent(limit: Int): List<PaperOrderPayloadPreviewEntity> = emptyList()

    override suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderPayloadPreviewEntity> = emptyList()

    override suspend fun byPreviewId(previewId: String): PaperOrderPayloadPreviewEntity? = null
}

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
