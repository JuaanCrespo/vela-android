package com.vela.android.lab.ui.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.paper.reconciliation.domain.AnchorStatus
import com.vela.android.lab.data.paper.reconciliation.domain.SnapshotFreshness
import com.vela.android.lab.data.paper.reconciliation.integration.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Human actions only. Construction/re-entry reads durable state, with no capture or report creation. */
class PositionReconciliationViewModel(
    private val store: PositionReconciliationStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val policy: PositionObservationPolicy = PositionObservationPolicy(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(PositionReconciliationUiState())
    val uiState = mutableState.asStateFlow()

    init { loadOffline() }

    fun loadOffline() {
        if (mutableState.value.busy || mutableState.value.dialog != null) return
        mutableState.update { it.copy(loadingLocal = true, selectedBaseline = null) }
        viewModelScope.launch {
            try { reloadDurable() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = PositionUiError.LOCAL_READ_FAILED) } }
            finally { mutableState.update { it.copy(loadingLocal = false) } }
        }
    }

    fun refreshPositions() {
        if (!mutableState.value.canRefresh) return
        mutableState.update { it.copy(refreshState = PositionRefreshState.REFRESHING, error = null, selectedBaseline = null) }
        viewModelScope.launch {
            try {
                val result = store.refreshManually()
                reloadDurable()
                mutableState.update { it.copy(
                    refreshState = when (result) {
                        PositionRefreshResult.SUCCESS -> PositionRefreshState.SUCCESS
                        PositionRefreshResult.BUSY -> PositionRefreshState.BLOCKED
                        else -> PositionRefreshState.FAILED
                    },
                    error = when (result) {
                        PositionRefreshResult.SUCCESS -> null
                        PositionRefreshResult.BUSY -> PositionUiError.BUSY
                        PositionRefreshResult.BROKER_READ_FAILED -> PositionUiError.BROKER_READ_FAILED
                        PositionRefreshResult.CAPTURE_PERSISTENCE_FAILED -> PositionUiError.CAPTURE_PERSISTENCE_FAILED
                        PositionRefreshResult.RECONCILIATION_PERSISTENCE_FAILED -> PositionUiError.RECONCILIATION_PERSISTENCE_FAILED
                    },
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(refreshState = PositionRefreshState.BLOCKED, error = PositionUiError.REFRESH_BLOCKED) } }
            finally { mutableState.update { if (it.refreshState == PositionRefreshState.REFRESHING) it.copy(refreshState = PositionRefreshState.IDLE) else it } }
        }
    }

    fun selectBaselineSymbol(symbol: String) = localAction(PositionUiError.LOCAL_READ_FAILED) {
        mutableState.update { it.copy(selectedBaseline = null) }
        val selection = store.selectBaselineSymbol(symbol)
        mutableState.update { it.copy(selectedBaseline = selection) }
    }

    fun requestEstablishBaseline() {
        val state = mutableState.value
        if (state.canEstablish) mutableState.update { it.copy(dialog = PositionBaselineDialog.Establish(requireNotNull(state.selectedBaseline))) }
    }

    fun requestInvalidateBaseline(anchorId: String) {
        val state = mutableState.value
        if (state.busy || state.dialog != null) return
        val anchor = state.durable.anchors.singleOrNull { it.metadata.anchorId == anchorId && it.metadata.status == AnchorStatus.ACTIVE.name } ?: return
        mutableState.update { it.copy(dialog = PositionBaselineDialog.Invalidate(anchor)) }
    }

    fun dismissBaselineDialog() { if (!mutableState.value.busy) mutableState.update { it.copy(dialog = null) } }

    fun confirmEstablishBaseline() {
        val dialog = mutableState.value.dialog as? PositionBaselineDialog.Establish ?: return
        mutableState.update { it.copy(dialog = null) }
        localAction(PositionUiError.ANCHOR_CREATE_FAILED) {
            store.establishBaseline(dialog.selection)
            reloadDurable()
        }
    }

    fun confirmInvalidateBaseline() {
        val dialog = mutableState.value.dialog as? PositionBaselineDialog.Invalidate ?: return
        mutableState.update { it.copy(dialog = null) }
        localAction(PositionUiError.ANCHOR_INVALIDATION_FAILED) {
            store.invalidateBaseline(dialog.anchor.metadata.anchorId)
            reloadDurable()
        }
    }

    fun toggleDiagnostics() { mutableState.update { it.copy(diagnosticsExpanded = !it.diagnosticsExpanded) } }

    private fun localAction(failure: PositionUiError, action: suspend () -> Unit) {
        if (mutableState.value.busy || mutableState.value.dialog != null) return
        mutableState.update { it.copy(workingLocal = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = failure, selectedBaseline = null) } }
            finally { mutableState.update { it.copy(workingLocal = false) } }
        }
    }

    private suspend fun reloadDurable() {
        val durable = store.loadOffline()
        val at = now()
        val rows = positionRows(durable, at, policy)
        val freshness = durable.latestComplete?.let { policy.freshness(it.metadata.completedAtEpochMillis, at) } ?: SnapshotFreshness.UNKNOWN
        mutableState.update { it.copy(durable = durable, rows = rows, freshness = freshness, selectedBaseline = null, error = null) }
    }
}
