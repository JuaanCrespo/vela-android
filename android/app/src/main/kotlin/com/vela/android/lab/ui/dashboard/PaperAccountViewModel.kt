package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2.k read-only Paper account ViewModel.
 *
 * - Reads `credentialsConfigured` from the existing
 *   [SecureAlpacaCredentialsStore]; never edits credentials.
 * - Calls `fetchAccount` + `fetchClock` + `fetchPositions` on
 *   manual Refresh — no polling, no background task.
 * - Surfaces typed Alpaca errors (HTTP code + body, auth-missing,
 *   parse error, network error) on `lastError`. Refresh never
 *   crashes the VM.
 * - No order / trading / account mutation method exists here;
 *   the underlying `AlpacaPaperReadOnlyClient` has no mutation
 *   surface, and the Phase 2.k reflection contract enforces it.
 */
class PaperAccountViewModel(
    private val client: AlpacaPaperReadOnlyClient,
    private val credentialsStore: SecureAlpacaCredentialsStore,
    private val clock: () -> Instant = { Instant.now() },
    private val maxTopPositions: Int = DEFAULT_MAX_TOP_POSITIONS,
) : ViewModel() {

    private val _uiState: MutableStateFlow<PaperAccountUiState> =
        MutableStateFlow(PaperAccountUiState.Initial)

    val uiState: StateFlow<PaperAccountUiState> = _uiState.asStateFlow()

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
                    lastError = "No Alpaca credentials configured. Save them in the Paper Credentials card first.",
                )
            }
            return
        }
        // Fetch all three independently and merge into UI state.
        val accountResult = client.fetchAccount()
        val clockResult = client.fetchClock()
        val positionsResult = client.fetchPositions()

        _uiState.update { prior ->
            val withAccount = applyAccount(prior, accountResult)
            val withClock = applyClock(withAccount, clockResult)
            val withPositions = applyPositions(withClock, positionsResult)
            withPositions.copy(
                isRefreshing = false,
                credentialsConfigured = true,
                lastRefreshAtEpochMillis = clock().toEpochMilli(),
                lastError = firstError(accountResult, clockResult, positionsResult),
            )
        }
    }

    private fun applyAccount(
        prior: PaperAccountUiState,
        result: AlpacaPaperReadOnlyClient.FetchResult<com.vela.android.lab.data.paper.PaperAccountSnapshot>,
    ): PaperAccountUiState = when (result) {
        is AlpacaPaperReadOnlyClient.FetchResult.Ok -> prior.copy(
            equityUsd = result.value.equityUsd,
            buyingPowerUsd = result.value.buyingPowerUsd,
            cashUsd = result.value.cashUsd,
            portfolioValueUsd = result.value.portfolioValueUsd,
            tradingBlocked = result.value.tradingBlocked,
            accountBlocked = result.value.accountBlocked,
            patternDayTrader = result.value.patternDayTrader,
            accountStatus = result.value.status,
        )
        else -> prior
    }

    private fun applyClock(
        prior: PaperAccountUiState,
        result: AlpacaPaperReadOnlyClient.FetchResult<com.vela.android.lab.data.paper.PaperClockSnapshot>,
    ): PaperAccountUiState = when (result) {
        is AlpacaPaperReadOnlyClient.FetchResult.Ok -> prior.copy(
            marketOpen = result.value.isOpen,
            nextOpenIso = result.value.nextOpenIso,
            nextCloseIso = result.value.nextCloseIso,
        )
        else -> prior
    }

    private fun applyPositions(
        prior: PaperAccountUiState,
        result: AlpacaPaperReadOnlyClient.FetchResult<List<com.vela.android.lab.data.paper.PaperPositionSnapshot>>,
    ): PaperAccountUiState = when (result) {
        is AlpacaPaperReadOnlyClient.FetchResult.Ok -> prior.copy(
            positionsCount = result.value.size,
            topPositions = result.value.take(maxTopPositions).map {
                PaperPositionRow(
                    symbol = it.symbol,
                    qty = it.qty,
                    marketValueUsd = it.marketValueUsd,
                    unrealizedPlUsd = it.unrealizedPlUsd,
                )
            },
        )
        else -> prior
    }

    private fun firstError(
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

    companion object {
        const val DEFAULT_MAX_TOP_POSITIONS: Int = 5
    }
}
