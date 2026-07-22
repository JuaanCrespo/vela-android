@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.HttpResult
import com.vela.android.lab.data.paper.RiskFlag
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.InMemoryWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import java.time.Instant
import kotlinx.coroutines.Dispatchers
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

class PaperPortfolioRiskViewModelTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-13T15:00:00Z") }

    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val testCreds = AlpacaCredentials("PKABCDEF1234", "topsecretvalue")

    private fun store(initial: AlpacaCredentials? = testCreds): PortInMemoryStore =
        PortInMemoryStore().apply { initial?.let { runBlockingSave(it) } }

    private fun newVm(
        store: PortInMemoryStore,
        responses: Map<String, String>,
        watchlist: Set<String> = emptySet(),
        marketDao: MarketBarDao = PortFakeMarketBarDao(),
        signalDao: SignalDao = PortFakeSignalDao(),
    ): PaperPortfolioRiskViewModel = PaperPortfolioRiskViewModel(
        client = AlpacaPaperReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = PortTrackingHttpClient(responses),
        ),
        credentialsStore = store,
        watchlistRepository = WatchlistRepository(InMemoryWatchlistStore(watchlist)),
        marketDataRepository = MarketDataRepository(marketDao),
        signalRepository = SignalRepository(signalDao),
        clock = fixedClock,
    )

    @Test
    fun `no credentials produces NO_CREDENTIALS warn flag and empty portfolio`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newVm(store(initial = null), emptyMap())
            vm.refresh()
            val s = vm.uiState.value
            assertFalse(s.credentialsConfigured)
            assertEquals(1, s.flags.size)
            assertEquals(RiskFlag.Code.NO_CREDENTIALS, s.flags.single().code)
            assertEquals(RiskFlag.Severity.WARN, s.flags.single().severity)
            assertEquals(0, s.exposures.size)
            assertNotNull(s.lastError)
        }

    @Test
    fun `empty positions produce safe empty exposure state`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100000","cash":"100000","buying_power":"400000","portfolio_value":"100000"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
            )
            val vm = newVm(store(), responses, watchlist = setOf("SPY"))
            vm.refresh()
            val s = vm.uiState.value
            assertEquals(0, s.exposures.size)
            assertEquals(0, s.portfolio.positionsCount)
            assertEquals(0.0, s.portfolio.grossMarketValueUsd)
            assertEquals(100000.0, s.portfolio.equityUsd)
            // market open is true → no MARKET_CLOSED flag.
            assertTrue(s.flags.none { it.code == RiskFlag.Code.MARKET_CLOSED })
            assertTrue(s.flags.none { it.code == RiskFlag.Code.NO_CREDENTIALS })
            assertNull(s.lastError)
        }

    @Test
    fun `positions produce per-symbol exposures with correct allocation percentages`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100000","cash":"40000","buying_power":"400000","portfolio_value":"100000"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to """[
                    {"symbol":"SPY","qty":"10","market_value":"30000","unrealized_pl":"500","side":"long"},
                    {"symbol":"AAPL","qty":"50","market_value":"10000","unrealized_pl":"-50","side":"long"}
                ]""",
            )
            val vm = newVm(store(), responses, watchlist = setOf("SPY", "AAPL"))
            vm.refresh()
            val s = vm.uiState.value
            assertEquals(2, s.exposures.size)
            assertEquals(40000.0, s.portfolio.grossMarketValueUsd)
            // SPY first because of sortedByDescending |mv|
            assertEquals("SPY", s.exposures[0].symbol)
            assertEquals(30.0, s.exposures[0].allocationPercent, 1e-9)
            assertEquals("AAPL", s.exposures[1].symbol)
            assertEquals(10.0, s.exposures[1].allocationPercent, 1e-9)
        }

    @Test
    fun `position not in watchlist produces informational flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100000","cash":"0","buying_power":"100000","portfolio_value":"100000"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to """[
                    {"symbol":"TSLA","qty":"5","market_value":"1500","unrealized_pl":"20","side":"long"}
                ]""",
            )
            val vm = newVm(store(), responses, watchlist = setOf("SPY", "QQQ"))
            vm.refresh()
            val s = vm.uiState.value
            val flag = s.flags.firstOrNull { it.code == RiskFlag.Code.POSITION_NOT_IN_WATCHLIST }
            assertNotNull(flag)
            assertEquals(RiskFlag.Severity.INFO, flag!!.severity)
            assertEquals("TSLA", flag.symbol)
        }

    @Test
    fun `position without local bars produces NO_LOCAL_MARKET_DATA informational flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100000","cash":"0","buying_power":"100000","portfolio_value":"100000"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to """[
                    {"symbol":"SPY","qty":"5","market_value":"1500","unrealized_pl":"20","side":"long"}
                ]""",
            )
            val vm = newVm(store(), responses, watchlist = setOf("SPY"))  // no bars in fake DAO
            vm.refresh()
            val s = vm.uiState.value
            val flag = s.flags.firstOrNull { it.code == RiskFlag.Code.NO_LOCAL_MARKET_DATA }
            assertNotNull(flag)
            assertEquals(RiskFlag.Severity.INFO, flag!!.severity)
            assertEquals("SPY", flag.symbol)
        }

    @Test
    fun `high allocation above 25 percent produces HIGH_ALLOCATION WARN flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100000","cash":"0","buying_power":"100000","portfolio_value":"100000"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to """[
                    {"symbol":"SPY","qty":"100","market_value":"40000","unrealized_pl":"100","side":"long"}
                ]""",
            )
            val vm = newVm(store(), responses, watchlist = setOf("SPY"))
            vm.refresh()
            val s = vm.uiState.value
            val flag = s.flags.firstOrNull { it.code == RiskFlag.Code.HIGH_ALLOCATION }
            assertNotNull(flag)
            assertEquals(RiskFlag.Severity.WARN, flag!!.severity)
            assertEquals("SPY", flag.symbol)
            assertTrue(s.exposures.single().allocationPercent > 25.0)
        }

    @Test
    fun `blocked account and trading flags surface as WARN flags`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100","cash":"100","buying_power":"100","portfolio_value":"100","trading_blocked":true,"account_blocked":true}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
            )
            val vm = newVm(store(), responses)
            vm.refresh()
            val s = vm.uiState.value
            assertTrue(s.flags.any {
                it.code == RiskFlag.Code.ACCOUNT_BLOCKED && it.severity == RiskFlag.Severity.WARN
            })
            assertTrue(s.flags.any {
                it.code == RiskFlag.Code.TRADING_BLOCKED && it.severity == RiskFlag.Severity.WARN
            })
        }

    @Test
    fun `market closed produces MARKET_CLOSED informational flag only`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100","cash":"100","buying_power":"100","portfolio_value":"100"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":false}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
            )
            val vm = newVm(store(), responses)
            vm.refresh()
            val flag = vm.uiState.value.flags.single { it.code == RiskFlag.Code.MARKET_CLOSED }
            assertEquals(RiskFlag.Severity.INFO, flag.severity)
        }

    @Test
    fun `latest local signal and close are joined per symbol`() =
        runTest(UnconfinedTestDispatcher()) {
            val marketDao = PortFakeMarketBarDao().apply {
                insert(
                    MarketBar1mEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 1L,
                        open = 1.0, high = 1.0, low = 1.0, close = 520.95,
                        updateCount = 1, syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
            val signalDao = PortFakeSignalDao().apply {
                insert(
                    SymbolSignalEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 1L,
                        state = "BULLISH", score = 2,
                        shortReturn = 0.0, percentChange = 0.0, barRange = 0.0,
                        direction = "up",
                    ),
                )
            }
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100","cash":"100","buying_power":"100","portfolio_value":"100"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to """[
                    {"symbol":"SPY","qty":"1","market_value":"520.95","unrealized_pl":"0","side":"long"}
                ]""",
            )
            val vm = newVm(
                store(),
                responses,
                watchlist = setOf("SPY"),
                marketDao = marketDao,
                signalDao = signalDao,
            )
            vm.refresh()
            val row = vm.uiState.value.exposures.single()
            assertEquals("BULLISH", row.latestSignalState)
            assertEquals(520.95, row.latestLocalClose)
        }

    @Test
    fun `UI state never carries credential value after refresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":false}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
            )
            val vm = newVm(store(), responses)
            vm.refresh()
            val serialised = vm.uiState.value.toString()
            assertFalse(serialised.contains("topsecretvalue"))
            assertFalse(serialised.contains("PKABCDEF1234"))
        }

    @Test
    fun `HTTP 403 surfaces as lastError without breaking other fetches`() =
        runTest(UnconfinedTestDispatcher()) {
            val responses = mapOf(
                // account 403; clock + positions OK
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":false}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
            )
            val vm = newVm(store(), responses)
            vm.refresh()
            val s = vm.uiState.value
            assertNotNull(s.lastError)
            // Clock came through — MARKET_CLOSED flag still raised
            assertTrue(s.flags.any { it.code == RiskFlag.Code.MARKET_CLOSED })
        }

    @Test
    fun `no method on PaperPortfolioRiskViewModel has a trading-shape name`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "replaceorder", "openposition", "closeposition",
            "post", "put", "patch", "delete",
        )
        val methods = PaperPortfolioRiskViewModel::class.java.declaredMethods
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

// --- Test doubles ----------------------------------------------------

private class PortInMemoryStore : SecureAlpacaCredentialsStore {
    @Volatile private var creds: AlpacaCredentials? = null
    fun runBlockingSave(c: AlpacaCredentials) { creds = c }
    override suspend fun save(credentials: AlpacaCredentials) { creds = credentials }
    override suspend fun load(): AlpacaCredentials? = creds
    override suspend fun clear() { creds = null }
    override suspend fun hasCredentials(): Boolean = creds != null
}

private class PortTrackingHttpClient(
    private val responses: Map<String, String>,
) : AlpacaHttpClient {
    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        val body = responses[url] ?: return HttpResult.HttpError(403, "no stub")
        return HttpResult.Success(200, body)
    }
}

private class PortFakeMarketBarDao : MarketBarDao {
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

private class PortFakeSignalDao : SignalDao {
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
