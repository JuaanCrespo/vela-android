package com.vela.android.lab.data.pipeline

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.repository.FeatureRepository
import com.vela.android.lab.data.repository.JournalRepository
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository

/**
 * Pure-Kotlin coordinator that drives the offline market pipeline and
 * persists each stage's output through the Room-backed repositories.
 *
 * Wiring (per call to [addUpdate]):
 *
 *   normalize symbol
 *     → journal "market_update_received"
 *     → barAggregator.addUpdate
 *     → marketDataRepository.persistBar
 *     → journal "bar_persisted"
 *     → featureEngine.addBar
 *     → featureRepository.persist
 *     → journal "features_persisted"
 *     → signalEngine.addFeatures
 *     → signalRepository.persist
 *     → journal "signal_persisted"
 *
 * No network. No Alpaca. No order submission. No Auto Paper. No
 * foreground service. Pure suspend coordination — callers choose the
 * dispatcher.
 */
class OfflineMarketPipelineCoordinator(
    private val barAggregator: OneMinuteBarAggregator,
    private val featureEngine: FeatureEngine,
    private val signalEngine: SignalEngine,
    private val marketDataRepository: MarketDataRepository,
    private val featureRepository: FeatureRepository,
    private val signalRepository: SignalRepository,
    private val journalRepository: JournalRepository,
) {

    suspend fun addUpdate(update: BootstrapMarketUpdate): PipelineStepResult {
        val normalizedSymbol = normalizeMarketSymbol(update.symbol)

        if (normalizedSymbol.isEmpty()) {
            journalRepository.record(
                eventType = PipelineEventTypes.INVALID_MARKET_UPDATE,
                timestamp = update.timestamp,
                symbol = null,
                payloadJson = """{"reason":"empty_symbol","sequence":${update.sequence}}""",
            )
            return PipelineStepResult(
                symbol = "",
                accepted = false,
                bar = null,
                features = null,
                signal = null,
                journalEventsRecorded = 1,
            )
        }

        var journalCount = 0

        journalRepository.record(
            eventType = PipelineEventTypes.MARKET_UPDATE_RECEIVED,
            timestamp = update.timestamp,
            symbol = normalizedSymbol,
            payloadJson = """{"sequence":${update.sequence},"price":${update.price}}""",
        )
        journalCount += 1

        barAggregator.addUpdate(update)
        val bar = barAggregator.currentBar(normalizedSymbol)
            ?: return PipelineStepResult(
                symbol = normalizedSymbol,
                accepted = true,
                bar = null,
                features = null,
                signal = null,
                journalEventsRecorded = journalCount,
            )

        marketDataRepository.persistBar(bar)
        journalRepository.record(
            eventType = PipelineEventTypes.BAR_PERSISTED,
            timestamp = bar.bucketStart,
            symbol = normalizedSymbol,
            payloadJson = """{"close":${bar.close},"updateCount":${bar.updateCount}}""",
        )
        journalCount += 1

        featureEngine.addBar(bar)
        val features = featureEngine.featuresFor(normalizedSymbol)
            ?: return PipelineStepResult(
                symbol = normalizedSymbol,
                accepted = true,
                bar = bar,
                features = null,
                signal = null,
                journalEventsRecorded = journalCount,
            )

        featureRepository.persist(features)
        journalRepository.record(
            eventType = PipelineEventTypes.FEATURES_PERSISTED,
            timestamp = features.bucketStart,
            symbol = normalizedSymbol,
            payloadJson = """{"direction":"${features.direction}","recentBarCount":${features.recentBarCount}}""",
        )
        journalCount += 1

        signalEngine.addFeatures(features)
        val signal = signalEngine.signalFor(normalizedSymbol)
            ?: return PipelineStepResult(
                symbol = normalizedSymbol,
                accepted = true,
                bar = bar,
                features = features,
                signal = null,
                journalEventsRecorded = journalCount,
            )

        signalRepository.persist(signal)
        journalRepository.record(
            eventType = PipelineEventTypes.SIGNAL_PERSISTED,
            timestamp = signal.bucketStart,
            symbol = normalizedSymbol,
            payloadJson = """{"state":"${signal.state.value}","score":${signal.score}}""",
        )
        journalCount += 1

        return PipelineStepResult(
            symbol = normalizedSymbol,
            accepted = true,
            bar = bar,
            features = features,
            signal = signal,
            journalEventsRecorded = journalCount,
        )
    }
}
