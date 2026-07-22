package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.HIGH_ALLOCATION_PERCENT_THRESHOLD
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.data.paper.PaperPortfolioSnapshot
import com.vela.android.lab.data.paper.PaperPositionSnapshot
import com.vela.android.lab.data.paper.PerSymbolPaperExposure
import com.vela.android.lab.data.paper.RiskFlag
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.WatchlistRepository
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2.l read-only Paper portfolio + risk ViewModel.
 *
 * Refresh joins:
 *  - existing [AlpacaPaperReadOnlyClient.fetchAccount] / `fetchClock`
 *    / `fetchPositions` (the three GET URLs already in scope)
 *  - existing [WatchlistRepository] (current watchlist set)
 *  - existing [MarketDataRepository] (latest persisted close per symbol)
 *  - existing [SignalRepository] (latest persisted signal state per symbol)
 *
 * No new network endpoint. No method on this class submits an
 * order, cancels an order, or mutates account state. Risk flags
 * are **informational only**.
 */
class PaperPortfolioRiskViewModel(
    private val client: AlpacaPaperReadOnlyClient,
    private val credentialsStore: SecureAlpacaCredentialsStore,
    private val watchlistRepository: WatchlistRepository,
    private val marketDataRepository: MarketDataRepository,
    private val signalRepository: SignalRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val highAllocationPercent: Double = HIGH_ALLOCATION_PERCENT_THRESHOLD,
) : ViewModel() {

    private val _uiState: MutableStateFlow<PaperPortfolioRiskUiState> =
        MutableStateFlow(PaperPortfolioRiskUiState.Initial)

    val uiState: StateFlow<PaperPortfolioRiskUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val configured = credentialsStore.hasCredentials()
            _uiState.update { it.copy(credentialsConfigured = configured) }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        _uiState.update { it.copy(isRefreshing = true, lastError = null) }
        val configured = credentialsStore.hasCredentials()
        if (!configured) {
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    credentialsConfigured = false,
                    flags = listOf(
                        RiskFlag(
                            code = RiskFlag.Code.NO_CREDENTIALS,
                            severity = RiskFlag.Severity.WARN,
                            message = "No Alpaca credentials configured.",
                        ),
                    ),
                    lastError = "Save credentials in the Paper Credentials card first.",
                )
            }
            return
        }

        val accountResult = client.fetchAccount()
        val clockResult = client.fetchClock()
        val positionsResult = client.fetchPositions()

        val account = (accountResult as? AlpacaPaperReadOnlyClient.FetchResult.Ok)?.value
        val clockSnap = (clockResult as? AlpacaPaperReadOnlyClient.FetchResult.Ok)?.value
        val positions = (positionsResult as? AlpacaPaperReadOnlyClient.FetchResult.Ok)?.value
            ?: emptyList()

        val watchlist = watchlistRepository.load().toSet()

        val exposures = positions.map { position ->
            buildExposure(position, account, watchlist)
        }
        val portfolio = buildPortfolio(account, clockSnap, exposures)
        val flags = buildRiskFlags(account, clockSnap, exposures, watchlist)

        _uiState.update { prior ->
            prior.copy(
                isRefreshing = false,
                credentialsConfigured = true,
                portfolio = portfolio,
                exposures = exposures.sortedByDescending { abs(it.marketValueUsd) },
                flags = flags,
                lastRefreshAtEpochMillis = clock().toEpochMilli(),
                lastError = firstErrorMessage(accountResult, clockResult, positionsResult),
            )
        }
    }

    private suspend fun buildExposure(
        position: PaperPositionSnapshot,
        account: PaperAccountSnapshot?,
        watchlist: Set<String>,
    ): PerSymbolPaperExposure {
        val portfolioValue = account?.portfolioValueUsd ?: 0.0
        val allocationPercent = if (portfolioValue > 0.0) {
            abs(position.marketValueUsd) / portfolioValue * 100.0
        } else {
            0.0
        }
        val latestBar = marketDataRepository.recentBars(position.symbol, 1).lastOrNull()
        val latestSignal = signalRepository.latestFor(position.symbol)
        return PerSymbolPaperExposure(
            symbol = position.symbol,
            qty = position.qty,
            marketValueUsd = position.marketValueUsd,
            unrealizedPlUsd = position.unrealizedPlUsd,
            side = position.side,
            allocationPercent = allocationPercent,
            inWatchlist = position.symbol in watchlist,
            latestSignalState = latestSignal?.state?.value,
            latestLocalClose = latestBar?.close,
        )
    }

    private fun buildPortfolio(
        account: PaperAccountSnapshot?,
        clockSnap: PaperClockSnapshot?,
        exposures: List<PerSymbolPaperExposure>,
    ): PaperPortfolioSnapshot {
        if (account == null) return PaperPortfolioSnapshot.Empty
        val grossMv = exposures.sumOf { abs(it.marketValueUsd) }
        return PaperPortfolioSnapshot(
            equityUsd = account.equityUsd,
            cashUsd = account.cashUsd,
            buyingPowerUsd = account.buyingPowerUsd,
            portfolioValueUsd = account.portfolioValueUsd,
            grossMarketValueUsd = grossMv,
            positionsCount = exposures.size,
            marketOpen = clockSnap?.isOpen,
            tradingBlocked = account.tradingBlocked,
            accountBlocked = account.accountBlocked,
            patternDayTrader = account.patternDayTrader,
            accountStatus = account.status,
        )
    }

    private fun buildRiskFlags(
        account: PaperAccountSnapshot?,
        clockSnap: PaperClockSnapshot?,
        exposures: List<PerSymbolPaperExposure>,
        watchlist: Set<String>,
    ): List<RiskFlag> {
        val flags = mutableListOf<RiskFlag>()
        if (account?.accountBlocked == true) {
            flags += RiskFlag(
                code = RiskFlag.Code.ACCOUNT_BLOCKED,
                severity = RiskFlag.Severity.WARN,
                message = "Alpaca reports the account as BLOCKED.",
            )
        }
        if (account?.tradingBlocked == true) {
            flags += RiskFlag(
                code = RiskFlag.Code.TRADING_BLOCKED,
                severity = RiskFlag.Severity.WARN,
                message = "Alpaca reports TRADING_BLOCKED on the account.",
            )
        }
        if (account?.patternDayTrader == true) {
            flags += RiskFlag(
                code = RiskFlag.Code.PATTERN_DAY_TRADER,
                severity = RiskFlag.Severity.INFO,
                message = "Account is flagged as Pattern Day Trader.",
            )
        }
        if (clockSnap?.isOpen == false) {
            flags += RiskFlag(
                code = RiskFlag.Code.MARKET_CLOSED,
                severity = RiskFlag.Severity.INFO,
                message = "US market is closed.",
            )
        }
        for (exposure in exposures) {
            if (!exposure.inWatchlist) {
                flags += RiskFlag(
                    code = RiskFlag.Code.POSITION_NOT_IN_WATCHLIST,
                    severity = RiskFlag.Severity.INFO,
                    message = "${exposure.symbol} position is not on the watchlist.",
                    symbol = exposure.symbol,
                )
            }
            if (exposure.latestLocalClose == null) {
                flags += RiskFlag(
                    code = RiskFlag.Code.NO_LOCAL_MARKET_DATA,
                    severity = RiskFlag.Severity.INFO,
                    message = "No locally persisted bars for ${exposure.symbol}.",
                    symbol = exposure.symbol,
                )
            }
            if (exposure.allocationPercent > highAllocationPercent) {
                flags += RiskFlag(
                    code = RiskFlag.Code.HIGH_ALLOCATION,
                    severity = RiskFlag.Severity.WARN,
                    message = "${exposure.symbol} allocation is " +
                        "%.1f%% (> %.0f%% threshold).".format(
                            exposure.allocationPercent, highAllocationPercent,
                        ),
                    symbol = exposure.symbol,
                )
            }
        }
        return flags
    }

    private fun firstErrorMessage(
        vararg results: AlpacaPaperReadOnlyClient.FetchResult<*>,
    ): String? {
        for (r in results) {
            when (r) {
                is AlpacaPaperReadOnlyClient.FetchResult.Ok<*> -> Unit
                AlpacaPaperReadOnlyClient.FetchResult.AuthMissing ->
                    return "Auth missing — re-enter credentials."
                is AlpacaPaperReadOnlyClient.FetchResult.HttpError ->
                    return "HTTP ${r.code}: ${r.message}"
                is AlpacaPaperReadOnlyClient.FetchResult.NetworkError ->
                    return "Network: ${r.message}"
                is AlpacaPaperReadOnlyClient.FetchResult.ParseError ->
                    return "Parse: ${r.message}"
            }
        }
        return null
    }
}
