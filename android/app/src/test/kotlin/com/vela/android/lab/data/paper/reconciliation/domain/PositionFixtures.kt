package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.CanonicalPaperLifecycleObservation
import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus

internal const val ACCOUNT = "local-paper-scope"
internal data class PositionFixture(val history: CanonicalPaperOrderHistory, val decimals: OrderDecimalEvidence)
internal fun fixture(
    attempt: String = "a",
    symbol: String = "SPY",
    side: String = "BUY",
    requested: String = "1",
    observations: List<Pair<String, String?>> = listOf("filled" to requested),
    orderSequence: Long = 1L,
    firstObservation: Long = 10L,
): PositionFixture {
    val rows = observations.mapIndexed { index, (raw, quantity) ->
        CanonicalPaperLifecycleObservation(
            databaseId = firstObservation + index, observedAtEpochMillis = 1_000L - index,
            status = raw.uppercase(), rawStatus = raw,
            terminal = raw in setOf("filled", "canceled", "expired", "rejected"),
            filledQuantity = quantity?.toDouble(),
            filledAveragePriceUsd = if (raw == "filled") 100.0 else null,
            filledAtIso = if (raw == "filled") "2026-09-17T12:00:00Z" else null,
            source = if (raw == "submitted") "LOCAL_SUBMIT_AUDIT" else "ALPACA_PAPER_ORDER_GET",
            httpStatusCode = if (raw == "submitted") null else 200, submitAuditEntryId = orderSequence + 1L,
            payloadFingerprint = "$attempt:$raw:$quantity", samePayloadAsPrevious = false,
        )
    }
    val history = CanonicalPaperOrderHistory(
        submitAttemptId = attempt, orderSequenceId = orderSequence, linkedClientDryRunId = "dry-$attempt",
        previewId = "preview-$attempt", auditStartRowId = orderSequence, auditResultRowId = orderSequence + 1L,
        alpacaOrderId = "broker-$attempt", clientOrderId = "client-$attempt", symbol = symbol, side = side,
        quantity = requested.toDouble(), orderType = "MARKET", timeInForce = "DAY", limitPriceUsd = null,
        dryRunAuditRowId = 1L, decisionCreatedAtEpochMillis = 1L, localSubmitResult = "SUBMITTED",
        localSubmitResultAtEpochMillis = 2L, submitHttpStatusCode = 201, initialAlpacaStatus = "new",
        alpacaSubmittedAtIso = "2026-09-17T11:00:00Z", lifecycleObservations = rows, currentLifecycle = rows.lastOrNull(),
        mappingState = "EXACT", resolved = rows.lastOrNull()?.terminal == true,
        unresolved = rows.lastOrNull()?.terminal != true, ambiguous = false,
        resetAcknowledgedAtEpochMillis = null, integrityStatus = PaperHistoryIntegrityStatus.VALID, integrityDiagnostics = emptyList(),
    )
    return PositionFixture(history, OrderDecimalEvidence(
        history.positionIdentity(), QuantityEvidence.decimal(requested), rows.mapIndexedNotNull { index, row ->
            if (row.source == "LOCAL_SUBMIT_AUDIT") null else row.databaseId to
                DecimalFillObservation(row.payloadFingerprint, QuantityEvidence.decimal(observations[index].second))
        }.toMap(),
    ))
}

internal fun input(vararg fixtures: PositionFixture, exact: Boolean = true) = PositionHistoryInput(
    ACCOUNT, fixtures.map { it.history }, HistoryCompleteness.COMPLETE,
    if (exact) fixtures.associate { it.history.submitAttemptId to it.decimals } else emptyMap(),
)
internal fun anchor(
    baseline: String = "3", symbol: String = "SPY",
    cut: AnchorCoverageCut = AnchorCoverageCut(0L, 0L, CutAssurance.CONFIRMED),
    cursors: List<AnchorOrderCursor> = emptyList(),
) = PositionAnchor("anchor-$symbol", symbol, ACCOUNT, QuantityEvidence.decimal(baseline), cut, cursors, AnchorStatus.ACTIVE, 100L)

internal fun cursor(fixture: PositionFixture, observationIndex: Int = 0): AnchorOrderCursor {
    val row = fixture.history.lifecycleObservations[observationIndex]
    return AnchorOrderCursor(
        fixture.history.positionIdentity(), fixture.decimals.observations.getValue(row.databaseId).filledQuantity,
        row.databaseId, row.payloadFingerprint,
    )
}

internal fun snapshot(vararg positions: Pair<String, String>) = BrokerPositionSnapshot(
    "snapshot", ACCOUNT, 100L, 101L, BrokerSnapshotCompleteness.COMPLETE,
    positions.map { (symbol, text) ->
        val quantity = QuantityEvidence.decimal(text)
        BrokerPositionQuantity(symbol, quantity, when {
            requireNotNull(quantity.quantity) > DecimalQuantity.ZERO -> BrokerPositionSide.LONG
            quantity.quantity < DecimalQuantity.ZERO -> BrokerPositionSide.SHORT
            else -> BrokerPositionSide.FLAT
        })
    },
    localEvidenceAlignment = CutAssurance.CONFIRMED,
)
