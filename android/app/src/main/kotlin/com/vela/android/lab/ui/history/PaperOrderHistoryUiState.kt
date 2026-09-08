package com.vela.android.lab.ui.history

import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory

enum class PaperHistoryStatusFilter(val label: String) {
    ALL("Todos"),
    FILLED("FILLED"),
    TERMINAL("Terminales"),
    UNRESOLVED("No resueltas"),
    WARNINGS("Con advertencias"),
    INCONSISTENT("Inconsistentes"),
}

enum class PaperHistorySideFilter(val label: String) {
    ALL("Todos"),
    BUY("BUY"),
    SELL("SELL"),
}

enum class PaperHistoryErrorKind {
    LOCAL_DATABASE,
    CANONICAL_RECONSTRUCTION,
}

data class PaperHistoryError(
    val kind: PaperHistoryErrorKind,
    val userMessage: String,
)

data class PaperOrderHistoryUiState(
    val isLoading: Boolean = true,
    val isDetailLoading: Boolean = false,
    val orders: List<CanonicalPaperOrderHistory> = emptyList(),
    val selectedOrder: CanonicalPaperOrderHistory? = null,
    val statusFilter: PaperHistoryStatusFilter = PaperHistoryStatusFilter.ALL,
    val symbolFilter: String? = null,
    val sideFilter: PaperHistorySideFilter = PaperHistorySideFilter.ALL,
    val availableSymbols: List<String> = emptyList(),
    val error: PaperHistoryError? = null,
    val initialLimit: Int = PaperOrderHistoryViewModel.DEFAULT_INITIAL_LIMIT,
)
