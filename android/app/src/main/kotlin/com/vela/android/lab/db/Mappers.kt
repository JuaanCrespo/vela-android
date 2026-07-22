package com.vela.android.lab.db

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.OneMinuteBar
import com.vela.android.lab.data.market.SignalState
import com.vela.android.lab.data.market.SymbolFeatures
import com.vela.android.lab.data.market.SymbolSignal
import com.vela.android.lab.db.room.entities.JournalEventEntity
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.SymbolFeaturesEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import java.time.Instant

/**
 * Domain ↔ Room entity mappers.
 *
 * Symbols are normalized on the way IN (toEntity) as a defense in
 * depth: even if a caller bypasses the aggregator and constructs a
 * domain object with a raw symbol, the persisted row uses the
 * canonical "BASE/QUOTE" form. On the way OUT (toDomain) we trust the
 * stored value because every write went through normalization.
 *
 * Phase 1.c does not need a JournalEvent domain type yet — the
 * journal repository accepts already-shaped entities.
 */

// --- OneMinuteBar -----------------------------------------------------

fun OneMinuteBar.toEntity(id: Long = 0L): MarketBar1mEntity = MarketBar1mEntity(
    id = id,
    symbol = normalizeMarketSymbol(symbol),
    bucketStartEpochMillis = bucketStart.toEpochMilli(),
    open = open,
    high = high,
    low = low,
    close = close,
    updateCount = updateCount,
    syntheticVolume = syntheticVolume,
    lastUpdateTimeEpochMillis = lastUpdateTime?.toEpochMilli(),
)

fun MarketBar1mEntity.toDomain(): OneMinuteBar = OneMinuteBar(
    symbol = symbol,
    bucketStart = Instant.ofEpochMilli(bucketStartEpochMillis),
    open = open,
    high = high,
    low = low,
    close = close,
    updateCount = updateCount,
    syntheticVolume = syntheticVolume,
    lastUpdateTime = lastUpdateTimeEpochMillis?.let(Instant::ofEpochMilli),
)

// --- SymbolFeatures ---------------------------------------------------

fun SymbolFeatures.toEntity(id: Long = 0L): SymbolFeaturesEntity = SymbolFeaturesEntity(
    id = id,
    symbol = normalizeMarketSymbol(symbol),
    bucketStartEpochMillis = bucketStart.toEpochMilli(),
    shortReturn = shortReturn,
    percentChange = percentChange,
    barRange = barRange,
    direction = direction,
    recentBarCount = recentBarCount,
)

fun SymbolFeaturesEntity.toDomain(): SymbolFeatures = SymbolFeatures(
    symbol = symbol,
    bucketStart = Instant.ofEpochMilli(bucketStartEpochMillis),
    shortReturn = shortReturn,
    percentChange = percentChange,
    barRange = barRange,
    direction = direction,
    recentBarCount = recentBarCount,
)

// --- SymbolSignal -----------------------------------------------------

fun SymbolSignal.toEntity(id: Long = 0L): SymbolSignalEntity = SymbolSignalEntity(
    id = id,
    symbol = normalizeMarketSymbol(symbol),
    bucketStartEpochMillis = bucketStart.toEpochMilli(),
    state = state.value,
    score = score,
    shortReturn = shortReturn,
    percentChange = percentChange,
    barRange = barRange,
    direction = direction,
)

fun SymbolSignalEntity.toDomain(): SymbolSignal = SymbolSignal(
    symbol = symbol,
    bucketStart = Instant.ofEpochMilli(bucketStartEpochMillis),
    state = SignalState.valueOf(state),
    score = score,
    shortReturn = shortReturn,
    percentChange = percentChange,
    barRange = barRange,
    direction = direction,
)

// --- Helpers for journal events --------------------------------------

fun journalEvent(
    eventType: String,
    timestamp: Instant,
    symbol: String? = null,
    payloadJson: String? = null,
): JournalEventEntity = JournalEventEntity(
    symbol = symbol?.let(::normalizeMarketSymbol)?.ifEmpty { null },
    eventType = eventType,
    timestampEpochMillis = timestamp.toEpochMilli(),
    payloadJson = payloadJson,
)
