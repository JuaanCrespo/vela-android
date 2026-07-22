package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2.n read-only dry-run audit ViewModel.
 *
 *  - Only reads from [PaperOrderDryRunAuditRepository]. No network.
 *    No credential dependency.
 *  - No order, trading, account mutation, or execution method.
 *  - No delete / clear method — the audit trail is append-only by
 *    design. The Phase 2.n reflection contract asserts the absence
 *    of any mutation-shape name on this class.
 *
 * The preflight VM signals back via `onAuditSaved` (passed at the
 * Application graph layer) which calls [refresh] here so the latest
 * row pops up without the user having to tap Refresh manually.
 */
class PaperOrderDryRunAuditViewModel(
    private val repository: PaperOrderDryRunAuditRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val recentLimit: Int = DEFAULT_RECENT_LIMIT,
) : ViewModel() {

    private val _uiState: MutableStateFlow<PaperOrderDryRunAuditUiState> =
        MutableStateFlow(PaperOrderDryRunAuditUiState.Initial)

    val uiState: StateFlow<PaperOrderDryRunAuditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refreshInternal() }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    /**
     * Public entry point usable by the preflight VM as a `suspend ()
     * -> Unit` callback. Re-reads the latest rows and total count.
     */
    suspend fun refreshNow() {
        refreshInternal()
    }

    private suspend fun refreshInternal() {
        _uiState.update { it.copy(isRefreshing = true, lastError = null) }
        try {
            val total = repository.countAll()
            val rows = repository.recent(recentLimit)
            _uiState.update {
                it.copy(
                    totalDryRuns = total,
                    recentRows = rows,
                    isRefreshing = false,
                    lastRefreshAtEpochMillis = clock().toEpochMilli(),
                    lastError = null,
                )
            }
        } catch (exc: Exception) {
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    lastError = exc.message ?: exc::class.simpleName ?: "Audit load failed",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_RECENT_LIMIT: Int = 20
    }
}
