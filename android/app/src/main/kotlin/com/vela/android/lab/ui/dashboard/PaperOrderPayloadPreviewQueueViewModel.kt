package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only Phase 2.q review-queue ViewModel. It can refresh local
 * rows but exposes no update/delete/clear or execution operation.
 */
class PaperOrderPayloadPreviewQueueViewModel(
    private val repository: PaperOrderPayloadPreviewRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val recentLimit: Int = DEFAULT_RECENT_LIMIT,
) : ViewModel() {

    private val _uiState: MutableStateFlow<PaperOrderPayloadPreviewQueueUiState> =
        MutableStateFlow(PaperOrderPayloadPreviewQueueUiState.Initial)

    val uiState: StateFlow<PaperOrderPayloadPreviewQueueUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refreshInternal() }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

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
                    totalPreviews = total,
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
                    lastError = exc.message ?: exc::class.simpleName ?: "Review queue load failed",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_RECENT_LIMIT: Int = 20
    }
}
