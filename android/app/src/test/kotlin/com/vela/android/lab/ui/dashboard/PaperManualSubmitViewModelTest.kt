@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.HttpResult
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PreviewQueueFakeDao
import com.vela.android.lab.data.paper.submit.PaperManualExecutionFeatureGate
import com.vela.android.lab.data.paper.submit.PaperManualOrderSubmitClient
import com.vela.android.lab.data.paper.submit.PaperManualSubmitExecutor
import com.vela.android.lab.data.paper.submit.PaperManualSubmitGate
import com.vela.android.lab.data.paper.submit.PaperManualSubmitTokenStore
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitError
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitStatus
import com.vela.android.lab.data.paper.submit.SubmitFakeAuditDao
import com.vela.android.lab.data.paper.submit.SubmitFakeHttpClient
import com.vela.android.lab.data.paper.submit.submitTestPreflight
import com.vela.android.lab.data.paper.submit.submitTestPreview
import com.vela.android.lab.data.paper.submit.submitTestReadiness
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.state.AppState
import java.time.Instant
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperManualSubmitViewModelTest {
    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `compile flag OFF keeps session and button blocked`() = runTest {
        val fixture = fixture(compileEnabled = false)
        fixture.vm.updateSource(
            submitTestPreflight(),
            submitTestPreview(),
            submitTestReadiness(),
        )
        fixture.vm.armSession()
        val state = fixture.vm.uiState.value
        assertFalse(state.sessionArmed)
        assertFalse(state.gateAllowed)
        assertTrue(state.gateReasons.contains(PaperOrderSubmitError.FEATURE_DISABLED))
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `valid manual flow requires exact text consumes token and shows result`() = runTest {
        val fixture = fixture(compileEnabled = true)
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())

        fixture.vm.armSession()
        assertTrue(fixture.vm.uiState.value.sessionArmed)
        assertEquals(500.0, fixture.vm.uiState.value.previewPriceUsd)
        assertEquals(500.0, fixture.vm.uiState.value.finalPriceUsd)
        assertEquals(1_000L, fixture.vm.uiState.value.finalPriceAgeMillis)
        assertEquals(0.0, fixture.vm.uiState.value.finalPriceDriftPercent)
        assertEquals(0.25, fixture.vm.uiState.value.allowedPriceDriftPercent)
        assertEquals("ALLOWED", fixture.vm.uiState.value.finalPriceGateResult)
        assertFalse(fixture.vm.uiState.value.gateAllowed)
        assertTrue(
            fixture.vm.uiState.value.gateReasons.contains(
                PaperOrderSubmitError.CONFIRMATION_MISSING,
            ),
        )

        fixture.vm.onConfirmationInputChange("wrong")
        assertFalse(fixture.vm.uiState.value.gateAllowed)
        fixture.vm.onConfirmationInputChange("SUBMIT PAPER SPY BUY 1")
        assertTrue(fixture.vm.uiState.value.gateAllowed)

        fixture.vm.submitOnce()
        val state = fixture.vm.uiState.value
        assertEquals(PaperOrderSubmitStatus.SUBMITTED, state.lastResult?.status)
        assertFalse(state.sessionArmed)
        assertFalse(state.gateAllowed)
        assertEquals(1, fixture.submitHttp.callCount)

        fixture.vm.submitOnce()
        assertEquals(1, fixture.submitHttp.callCount)
    }

    @Test
    fun `UI state never contains credential values`() = runTest {
        val fixture = fixture(compileEnabled = true)
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())
        fixture.vm.armSession()
        val serialized = fixture.vm.uiState.value.toString()
        assertFalse(serialized.contains("PK-SECRET-ID"))
        assertFalse(serialized.contains("super-secret-value"))
        assertNotNull(fixture.vm.uiState.value.requiredConfirmationText)
    }

    @Test
    fun `confirmation token is not issued while final price drift is blocked`() = runTest {
        val fixture = fixture(compileEnabled = true, finalPriceUsd = 502.0)
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())

        fixture.vm.armSession()
        assertEquals("PRICE_DRIFT_EXCEEDED", fixture.vm.uiState.value.finalPriceGateResult)
        fixture.vm.onConfirmationInputChange("SUBMIT PAPER SPY BUY 1")

        assertTrue(
            fixture.vm.uiState.value.gateReasons.contains(
                PaperOrderSubmitError.PRICE_DRIFT_EXCEEDED,
            ),
        )
        assertTrue(fixture.tokenStore.peek("token-vm") == null)
        assertEquals(0, fixture.submitHttp.callCount)
    }

    private fun fixture(
        compileEnabled: Boolean,
        finalPriceUsd: Double = 500.0,
    ): ViewModelFixture {
        val now = 10_000L
        val store = SubmitVmCredentialStore(
            AlpacaCredentials("PK-SECRET-ID", "super-secret-value"),
        )
        val readHttp = SubmitVmReadHttpClient()
        val readClient = AlpacaPaperReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = readHttp,
        )
        val marketDao = SubmitVmMarketBarDao().apply {
            runBlocking {
                insert(
                    MarketBar1mEntity(
                        symbol = "SPY",
                        bucketStartEpochMillis = 9_000L,
                        open = finalPriceUsd,
                        high = finalPriceUsd,
                        low = finalPriceUsd,
                        close = finalPriceUsd,
                        updateCount = 1,
                        syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
        }
        val priceProvider = MarketPriceSnapshotProvider(
            tickBuffer = MarketTickBuffer(),
            marketDataRepository = MarketDataRepository(marketDao),
            clock = { Instant.ofEpochMilli(now) },
        )
        val previewRepository = PaperOrderPayloadPreviewRepository(PreviewQueueFakeDao())
        val feature = PaperManualExecutionFeatureGate(compileEnabled)
        val gate = PaperManualSubmitGate(feature)
        val tokenStore = PaperManualSubmitTokenStore(
            clock = { Instant.ofEpochMilli(now) },
            tokenIdFactory = { "token-vm" },
        )
        val submitHttp = SubmitFakeHttpClient()
        val auditRepository = PaperOrderSubmitAuditRepository(SubmitFakeAuditDao())
        val executor = PaperManualSubmitExecutor(
            gate = gate,
            tokenStore = tokenStore,
            submitClient = PaperManualOrderSubmitClient(
                submitHttp,
                clock = { Instant.ofEpochMilli(now) },
            ),
            auditRepository = auditRepository,
            finalPriceSnapshotProvider = priceProvider::snapshotFor,
            clock = { Instant.ofEpochMilli(now) },
        )
        val vm = PaperManualSubmitViewModel(
            featureGate = feature,
            gate = gate,
            tokenStore = tokenStore,
            executor = executor,
            readOnlyClient = readClient,
            credentialsStore = store,
            priceSnapshotProvider = priceProvider,
            previewRepository = previewRepository,
            appState = AppState(),
            clock = { Instant.ofEpochMilli(now) },
            attemptIdFactory = { "attempt-vm" },
            clientOrderIdFactory = { "vela-client-vm" },
        )
        return ViewModelFixture(vm, previewRepository, submitHttp, tokenStore)
    }
}

private data class ViewModelFixture(
    val vm: PaperManualSubmitViewModel,
    val previewRepository: PaperOrderPayloadPreviewRepository,
    val submitHttp: SubmitFakeHttpClient,
    val tokenStore: PaperManualSubmitTokenStore,
)

private class SubmitVmCredentialStore(
    private var credentials: AlpacaCredentials?,
) : SecureAlpacaCredentialsStore {
    override suspend fun save(credentials: AlpacaCredentials) { this.credentials = credentials }
    override suspend fun load(): AlpacaCredentials? = credentials
    override suspend fun clear() { credentials = null }
    override suspend fun hasCredentials(): Boolean = credentials != null
}

private class SubmitVmReadHttpClient : AlpacaHttpClient {
    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        val body = when (url) {
            AlpacaPaperTradingEndpoint.ACCOUNT_URL ->
                """{"status":"ACTIVE","buying_power":"100000","cash":"50000","equity":"100000","portfolio_value":"100000","trading_blocked":false,"account_blocked":false}"""
            AlpacaPaperTradingEndpoint.CLOCK_URL -> """{"is_open":true}"""
            AlpacaPaperTradingEndpoint.POSITIONS_URL -> "[]"
            else -> return HttpResult.HttpError(404, "not found")
        }
        return HttpResult.Success(200, body)
    }
}

private class SubmitVmMarketBarDao : MarketBarDao {
    private val rows = mutableListOf<MarketBar1mEntity>()
    private var nextId = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}
