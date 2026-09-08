package com.vela.android.lab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import com.vela.android.lab.data.paper.history.PaperOrderHistoryReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Local-only viewer state. It has no submit VM, credentials, stream, or HTTP dependency. */
class PaperOrderHistoryViewModel(
    private val repository: PaperOrderHistoryReader,
    private val initialLimit: Int = DEFAULT_INITIAL_LIMIT,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PaperOrderHistoryUiState(initialLimit = initialLimit),
    )
    val uiState: StateFlow<PaperOrderHistoryUiState> = _uiState

    private var loadJob: Job? = null
    private var detailJob: Job? = null

    init {
        require(initialLimit in 1..MAX_INITIAL_LIMIT)
        loadOrders()
    }

    fun onStatusFilterSelected(filter: PaperHistoryStatusFilter) {
        if (_uiState.value.statusFilter == filter) return
        _uiState.value = _uiState.value.copy(
            statusFilter = filter,
            selectedOrder = null,
        )
        loadOrders()
    }

    fun onSymbolFilterSelected(symbol: String?) {
        val normalized = symbol?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        if (_uiState.value.symbolFilter == normalized) return
        _uiState.value = _uiState.value.copy(
            symbolFilter = normalized,
            selectedOrder = null,
        )
        loadOrders()
    }

    fun onSideFilterSelected(filter: PaperHistorySideFilter) {
        if (_uiState.value.sideFilter == filter) return
        _uiState.value = _uiState.value.copy(
            sideFilter = filter,
            selectedOrder = null,
        )
        loadOrders()
    }

    fun openDetails(attemptId: String) {
        val normalized = attemptId.trim().takeIf(String::isNotEmpty) ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDetailLoading = true, error = null)
            runCatching { repository.getByAttemptId(normalized) }
                .onSuccess { record ->
                    _uiState.value = _uiState.value.copy(
                        isDetailLoading = false,
                        selectedOrder = record,
                        error = if (record == null) canonicalError() else null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isDetailLoading = false,
                        selectedOrder = null,
                        error = classifyError(error),
                    )
                }
        }
    }

    fun closeDetails() {
        detailJob?.cancel()
        _uiState.value = _uiState.value.copy(selectedOrder = null, error = null)
    }

    private fun loadOrders() {
        detailJob?.cancel()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isDetailLoading = false,
                error = null,
            )
            val filters = _uiState.value
            runCatching { queryFor(filters) }
                .onSuccess { records ->
                    val symbols = (_uiState.value.availableSymbols + records.mapNotNull { it.symbol })
                        .map(String::uppercase)
                        .distinct()
                        .sorted()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        orders = records,
                        availableSymbols = symbols,
                        error = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        orders = emptyList(),
                        error = classifyError(error),
                    )
                }
        }
    }

    private suspend fun queryFor(
        filters: PaperOrderHistoryUiState,
    ): List<CanonicalPaperOrderHistory> {
        val candidates = when {
            filters.statusFilter == PaperHistoryStatusFilter.FILLED ->
                repository.getFilledOrders()
            filters.statusFilter == PaperHistoryStatusFilter.TERMINAL ->
                repository.getTerminalOrders()
            filters.symbolFilter != null -> repository.getBySymbol(filters.symbolFilter)
            filters.sideFilter == PaperHistorySideFilter.BUY -> repository.getBySide("BUY")
            filters.sideFilter == PaperHistorySideFilter.SELL -> repository.getBySide("SELL")
            else -> repository.getLatestN(initialLimit)
        }
        return candidates.asSequence()
            .filter { record -> filters.statusFilter.matches(record) }
            .filter { record ->
                filters.symbolFilter == null || record.symbol == filters.symbolFilter
            }
            .filter { record ->
                filters.sideFilter == PaperHistorySideFilter.ALL ||
                    record.side == filters.sideFilter.name
            }
            .take(initialLimit)
            .toList()
    }

    private fun PaperHistoryStatusFilter.matches(record: CanonicalPaperOrderHistory): Boolean =
        when (this) {
            PaperHistoryStatusFilter.ALL -> true
            PaperHistoryStatusFilter.FILLED -> record.currentLifecycle?.status == "FILLED"
            PaperHistoryStatusFilter.TERMINAL -> record.currentLifecycle?.terminal == true
            PaperHistoryStatusFilter.UNRESOLVED -> record.unresolved
            PaperHistoryStatusFilter.WARNINGS ->
                record.integrityStatus == PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS
            PaperHistoryStatusFilter.INCONSISTENT ->
                record.integrityStatus == PaperHistoryIntegrityStatus.INCONSISTENT
        }

    private fun classifyError(error: Throwable): PaperHistoryError {
        val signature = listOfNotNull(error::class.qualifiedName, error.message)
            .joinToString(" ")
            .lowercase()
        return if (listOf("sqlite", "room", "database").any(signature::contains)) {
            PaperHistoryError(
                kind = PaperHistoryErrorKind.LOCAL_DATABASE,
                userMessage = "No se pudo leer la base local de historial.",
            )
        } else {
            canonicalError()
        }
    }

    private fun canonicalError(): PaperHistoryError = PaperHistoryError(
        kind = PaperHistoryErrorKind.CANONICAL_RECONSTRUCTION,
        userMessage = "No se pudo reconstruir el historial Paper canónico.",
    )

    companion object {
        const val DEFAULT_INITIAL_LIMIT: Int = 100
        const val MAX_INITIAL_LIMIT: Int = 500
    }
}
