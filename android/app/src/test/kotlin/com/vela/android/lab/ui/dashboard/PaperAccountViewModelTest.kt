@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.HttpResult
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

class PaperAccountViewModelTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-13T15:00:00Z") }

    @BeforeEach
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial state reflects credentials configured from store`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = PaperVmInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "topsecretvalue"))
            }
            val vm = PaperAccountViewModel(
                client = newClient(store),
                credentialsStore = store,
                clock = fixedClock,
            )
            assertTrue(vm.uiState.value.credentialsConfigured)
            assertFalse(vm.uiState.value.isRefreshing)
        }

    @Test
    fun `refresh with no credentials surfaces error and does not call HTTP`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = PaperVmInMemoryStore()
            val tracker = TrackingHttpClient()
            val vm = PaperAccountViewModel(
                client = AlpacaPaperReadOnlyClient(
                    credentialsProvider = AlpacaCredentialsProvider { store.load() },
                    httpClient = tracker,
                ),
                credentialsStore = store,
                clock = fixedClock,
            )
            vm.refresh()
            val s = vm.uiState.value
            assertFalse(s.credentialsConfigured)
            assertNotNull(s.lastError)
            assertEquals(0, tracker.callCount)
        }

    @Test
    fun `refresh with credentials hits 3 GET URLs only`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = PaperVmInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "topsecretvalue"))
            }
            val tracker = TrackingHttpClient(
                responses = mapOf(
                    AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE","equity":"100","cash":"50","buying_power":"200","portfolio_value":"100"}""",
                    AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":true,"next_close":"2026-06-13T20:00:00Z"}""",
                    AlpacaPaperTradingEndpoint.POSITIONS_URL to """[{"symbol":"SPY","qty":"5","market_value":"3650","unrealized_pl":"-12","side":"long"}]""",
                ),
            )
            val vm = PaperAccountViewModel(
                client = AlpacaPaperReadOnlyClient(
                    credentialsProvider = AlpacaCredentialsProvider { store.load() },
                    httpClient = tracker,
                ),
                credentialsStore = store,
                clock = fixedClock,
            )
            vm.refresh()
            val s = vm.uiState.value

            assertEquals(setOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL,
                AlpacaPaperTradingEndpoint.CLOCK_URL,
                AlpacaPaperTradingEndpoint.POSITIONS_URL,
            ), tracker.urls.toSet())

            assertEquals(true, s.marketOpen)
            assertEquals(100.0, s.equityUsd)
            assertEquals(50.0, s.cashUsd)
            assertEquals(200.0, s.buyingPowerUsd)
            assertEquals(false, s.tradingBlocked)
            assertEquals("ACTIVE", s.accountStatus)
            assertEquals(1, s.positionsCount)
            assertEquals("SPY", s.topPositions.single().symbol)
            assertNotNull(s.lastRefreshAtEpochMillis)
            assertNull(s.lastError)
            assertFalse(s.isRefreshing)
        }

    @Test
    fun `HTTP 403 surfaces on lastError without breaking other fetches`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = PaperVmInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "topsecretvalue"))
            }
            val tracker = TrackingHttpClient(
                responses = mapOf(
                    AlpacaPaperTradingEndpoint.ACCOUNT_URL to ERROR_403,
                    AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":false}""",
                    AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
                ),
                forceErrorOnAccount = true,
            )
            val vm = PaperAccountViewModel(
                client = AlpacaPaperReadOnlyClient(
                    credentialsProvider = AlpacaCredentialsProvider { store.load() },
                    httpClient = tracker,
                ),
                credentialsStore = store,
                clock = fixedClock,
            )
            vm.refresh()
            val s = vm.uiState.value
            assertNotNull(s.lastError)
            assertTrue(s.lastError!!.contains("403"))
            // Clock still came through.
            assertEquals(false, s.marketOpen)
            assertEquals(0, s.positionsCount)
        }

    @Test
    fun `UI state never carries the saved secret after refresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = PaperVmInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "topsecretvalue"))
            }
            val vm = PaperAccountViewModel(
                client = newClient(store),
                credentialsStore = store,
                clock = fixedClock,
            )
            vm.refresh()
            val serialised = vm.uiState.value.toString()
            assertFalse(serialised.contains("topsecretvalue"))
            assertFalse(serialised.contains("PKABCDEF1234"))
        }

    @Test
    fun `no method on PaperAccountViewModel has a trading-shape name`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition", "getaccount",
        )
        val methods = PaperAccountViewModel::class.java.declaredMethods.map { it.name }
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

    private fun newClient(store: PaperVmInMemoryStore): AlpacaPaperReadOnlyClient {
        val tracker = TrackingHttpClient(
            responses = mapOf(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE"}""",
                AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":false}""",
                AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
            ),
        )
        return AlpacaPaperReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = tracker,
        )
    }
}

private const val ERROR_403 = """{"message":"forbidden"}"""

private class PaperVmInMemoryStore : SecureAlpacaCredentialsStore {
    private var creds: AlpacaCredentials? = null
    override suspend fun save(credentials: AlpacaCredentials) { creds = credentials }
    override suspend fun load(): AlpacaCredentials? = creds
    override suspend fun clear() { creds = null }
    override suspend fun hasCredentials(): Boolean = creds != null
}

private class TrackingHttpClient(
    private val responses: Map<String, String> = emptyMap(),
    private val forceErrorOnAccount: Boolean = false,
) : AlpacaHttpClient {

    val urls: MutableList<String> = mutableListOf()
    var callCount: Int = 0; private set

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        urls += url
        callCount += 1
        if (forceErrorOnAccount && url == AlpacaPaperTradingEndpoint.ACCOUNT_URL) {
            return HttpResult.HttpError(403, ERROR_403)
        }
        val body = responses[url] ?: return HttpResult.HttpError(404, "no stub")
        return HttpResult.Success(200, body)
    }
}
