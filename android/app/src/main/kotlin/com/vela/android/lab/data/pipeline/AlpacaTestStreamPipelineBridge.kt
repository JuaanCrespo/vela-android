package com.vela.android.lab.data.pipeline

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.source.MarketDataClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only bridge: drains a [MarketDataClient.updates] flow and
 * forwards every [BootstrapMarketUpdate] into the existing
 * [OfflineMarketPipelineCoordinator] so FAKEPACA bars from the
 * Alpaca test stream traverse the same `aggregator → features →
 * signals → repositories → journal` path that the demo BTC/SPY
 * buttons use.
 *
 * Phase 2.d safety contract:
 *
 *  - The bridge calls **only** `coordinator.addUpdate(...)`, which
 *    is a read-only orchestrator with no order/account/trading
 *    surface. Reflection contract tests (Phase 2.b style) cover
 *    the bridge itself for the same forbidden-method patterns.
 *  - Errors from `coordinator.addUpdate(...)` are caught and folded
 *    into the bridge state — the upstream flow never observes
 *    them — so a single bad update cannot tear down the collector.
 *  - [stop] cancels the collector coroutine via [Job.cancel]; the
 *    flow itself is not affected and can be re-attached by a
 *    subsequent [start].
 *  - No credential value flows through this class. The bridge
 *    operates on already-emitted [BootstrapMarketUpdate]s that the
 *    Phase 2.b client produced from authenticated frames; the
 *    credentials live only inside the client.
 */
class AlpacaTestStreamPipelineBridge(
    private val client: MarketDataClient,
    private val coordinator: OfflineMarketPipelineCoordinator,
) {

    private val _state: MutableStateFlow<AlpacaTestStreamBridgeState> =
        MutableStateFlow(AlpacaTestStreamBridgeState.Initial)

    val state: StateFlow<AlpacaTestStreamBridgeState> = _state.asStateFlow()

    @Volatile
    private var collectorJob: Job? = null

    val isCollecting: Boolean
        get() = collectorJob?.isActive == true

    /** Attach to [client]'s updates flow on [scope]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (isCollecting) return
        collectorJob = scope.launch {
            client.updates.collect { update -> handleUpdate(update) }
        }
    }

    /** Cancel the collector. Safe to call multiple times. */
    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    /** Reset counters and last-* fields to their initial values. */
    fun resetCounters() {
        _state.value = AlpacaTestStreamBridgeState.Initial
    }

    private suspend fun handleUpdate(update: BootstrapMarketUpdate) {
        try {
            val result = coordinator.addUpdate(update)
            _state.update { current ->
                val sym = if (result.symbol.isNotEmpty()) result.symbol else update.symbol
                val priorSym = current.perSymbol[sym] ?: SymbolBridgeStats.Initial
                val updatedSym = priorSym.copy(
                    received = priorSym.received + 1,
                    persisted = if (result.accepted) priorSym.persisted + 1
                        else priorSym.persisted,
                    lastClose = result.bar?.close ?: priorSym.lastClose,
                    lastSignalState = result.signal?.state?.value ?: priorSym.lastSignalState,
                    lastBarBucketStartEpochMillis = result.bar?.bucketStart?.toEpochMilli()
                        ?: priorSym.lastBarBucketStartEpochMillis,
                )
                current.copy(
                    receivedUpdates = current.receivedUpdates + 1,
                    persistedUpdates = if (result.accepted) current.persistedUpdates + 1
                        else current.persistedUpdates,
                    lastSymbol = sym,
                    lastPrice = update.price,
                    lastBarClose = result.bar?.close ?: current.lastBarClose,
                    lastSignalState = result.signal?.state?.value ?: current.lastSignalState,
                    lastJournalEventsForUpdate = result.journalEventsRecorded,
                    lastError = null,
                    perSymbol = current.perSymbol + (sym to updatedSym),
                )
            }
        } catch (exc: Exception) {
            // One symbol failing must not break the whole stream
            // (Phase 2.g routing contract). We capture the error on
            // the aggregate `lastError` field but do NOT advance
            // perSymbol[sym] because we have no result to attribute.
            _state.update { current ->
                current.copy(
                    receivedUpdates = current.receivedUpdates + 1,
                    lastError = exc.message ?: exc::class.simpleName ?: "Unknown bridge error",
                )
            }
        }
    }
}
