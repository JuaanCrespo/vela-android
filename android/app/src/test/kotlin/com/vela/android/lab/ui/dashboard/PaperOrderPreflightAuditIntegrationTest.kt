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
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.InMemoryWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
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

class PaperOrderPreflightAuditIntegrationTest {

    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val testCreds = AlpacaCredentials("PKABCDEF1234", "topsecretvalue")

    private fun okResponses(): Map<String, String> = mapOf(
        AlpacaPaperTradingEndpoint.ACCOUNT_URL to
            """{"status":"ACTIVE","equity":"100000","cash":"50000","buying_power":"200000","portfolio_value":"100000"}""",
        AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
        AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
    )

    private fun newPreflightVm(
        auditDao: PaperOrderDryRunAuditDao,
        store: IntegInMemoryStore = IntegInMemoryStore().apply { runBlockingSave(testCreds) },
    ): PaperOrderPreflightViewModel {
        val marketDao = IntegFakeMarketBarDao().also { dao ->
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
        }
        val signalDao = IntegFakeSignalDao().also { dao ->
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
        }
        return PaperOrderPreflightViewModel(
            engine = PaperOrderPreflightEngine(),
            client = AlpacaPaperReadOnlyClient(
                credentialsProvider = AlpacaCredentialsProvider { store.load() },
                httpClient = IntegStubHttpClient(okResponses()),
            ),
            credentialsStore = store,
            watchlistRepository = WatchlistRepository(InMemoryWatchlistStore(setOf("SPY"))),
            marketDataRepository = MarketDataRepository(marketDao),
            signalRepository = SignalRepository(signalDao),
            appState = AppState(),
            auditRepository = PaperOrderDryRunAuditRepository(auditDao),
            onAuditSaved = null,
        )
    }

    @Test
    fun `valid preflight inserts exactly one audit row`() = runTest(UnconfinedTestDispatcher()) {
        val auditDao = IntegFakeAuditDao()
        val vm = newPreflightVm(auditDao)
        vm.onSymbolInputChange("SPY")
        vm.onSideChange(OrderSide.BUY)
        vm.onQuantityInputChange("1")
        vm.runDryRunPreflight()

        assertEquals(1, auditDao.rows.size)
        val row = auditDao.rows.single()
        assertEquals("SPY", row.symbol)
        assertEquals("BUY", row.side)
        assertEquals(1.0, row.quantity)
        // Persisted status reflects the engine's verdict (might be
        // ALLOWED_DRY_RUN or WARNING_ONLY depending on signal /
        // market-open). Either way it is *not* a network call.
        assertNotNull(row.status)
        // No credential value ended up in the persisted row.
        val rowStr = row.toString()
        assertFalse(rowStr.contains("topsecretvalue"))
        assertFalse(rowStr.contains("PKABCDEF1234"))
        // VM did not set lastAuditError.
        assertNull(vm.uiState.value.lastAuditError)
    }

    @Test
    fun `two distinct dry-runs produce two rows with distinct clientDryRunIds`() =
        runTest(UnconfinedTestDispatcher()) {
            val auditDao = IntegFakeAuditDao()
            val vm = newPreflightVm(auditDao)
            vm.onSymbolInputChange("SPY"); vm.onQuantityInputChange("1"); vm.runDryRunPreflight()
            vm.onSymbolInputChange("SPY"); vm.onQuantityInputChange("2"); vm.runDryRunPreflight()
            assertEquals(2, auditDao.rows.size)
            assertEquals(2, auditDao.rows.map { it.clientDryRunId }.toSet().size)
        }

    @Test
    fun `invalid form input does NOT insert an audit row`() = runTest(UnconfinedTestDispatcher()) {
        val auditDao = IntegFakeAuditDao()
        val vm = newPreflightVm(auditDao)
        // Empty symbol → lastInputError, no engine call, no audit.
        vm.onQuantityInputChange("1")
        vm.runDryRunPreflight()
        assertEquals(0, auditDao.rows.size)
        assertNotNull(vm.uiState.value.lastInputError)
    }

    @Test
    fun `audit save failure surfaces lastAuditError but keeps preflight result visible`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = IntegInMemoryStore().apply { runBlockingSave(testCreds) }
            val throwingDao = object : PaperOrderDryRunAuditDao {
                override suspend fun insert(audit: PaperOrderDryRunAuditEntity): Long =
                    throw RuntimeException("simulated DB failure")
                override suspend fun countAll(): Int = 0
                override suspend fun recent(limit: Int): List<PaperOrderDryRunAuditEntity> = emptyList()
                override suspend fun recentBySymbol(symbol: String, limit: Int): List<PaperOrderDryRunAuditEntity> = emptyList()
            }
            val vm = newPreflightVm(throwingDao, store)
            vm.onSymbolInputChange("SPY"); vm.onQuantityInputChange("1"); vm.runDryRunPreflight()
            val s = vm.uiState.value
            assertNotNull(s.lastResult, "preflight result must still be present")
            assertNotNull(s.lastAuditError, "audit error must surface")
            assertTrue(s.lastAuditError!!.contains("DB failure"))
        }
}

// --- Test doubles --------------------------------------------------

private class IntegInMemoryStore : SecureAlpacaCredentialsStore {
    @Volatile private var creds: AlpacaCredentials? = null
    fun runBlockingSave(c: AlpacaCredentials) { creds = c }
    override suspend fun save(credentials: AlpacaCredentials) { creds = credentials }
    override suspend fun load(): AlpacaCredentials? = creds
    override suspend fun clear() { creds = null }
    override suspend fun hasCredentials(): Boolean = creds != null
}

private class IntegStubHttpClient(private val responses: Map<String, String>) : AlpacaHttpClient {
    override suspend fun executeGet(url: String, keyId: String, secret: String): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        val body = responses[url] ?: return HttpResult.HttpError(404, "no stub")
        return HttpResult.Success(200, body)
    }
}

private class IntegFakeAuditDao : PaperOrderDryRunAuditDao {
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
    override suspend fun recentBySymbol(symbol: String, limit: Int): List<PaperOrderDryRunAuditEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.createdAtEpochMillis }.take(limit)
}

private class IntegFakeMarketBarDao : MarketBarDao {
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

private class IntegFakeSignalDao : SignalDao {
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
