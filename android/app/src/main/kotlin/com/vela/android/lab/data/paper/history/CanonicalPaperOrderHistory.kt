package com.vela.android.lab.data.paper.history

/** Overall trust result for one read-only canonical history aggregate. */
enum class PaperHistoryIntegrityStatus {
    VALID,
    VALID_WITH_WARNINGS,
    INCONSISTENT,
}

/** Diagnostics never mutate, discard, or repair the underlying append-only evidence. */
enum class PaperHistoryIntegrityDiagnostic(val warningOnly: Boolean = false) {
    LEGACY_SUBMIT_METADATA_UNKNOWN(warningOnly = true),
    INITIAL_ALPACA_STATUS_UNKNOWN(warningOnly = true),
    ALPACA_SUBMITTED_AT_UNKNOWN(warningOnly = true),
    MISSING_DRY_RUN_EVIDENCE(warningOnly = true),
    REPEATED_LIFECYCLE_PAYLOAD(warningOnly = true),
    PARTIALLY_FILLED_REGRESSION(warningOnly = true),
    MISSING_AUDIT_LINKAGE,
    INCOMPLETE_SUBMIT_AUDIT,
    MULTIPLE_SUBMIT_AUDIT_EVENTS,
    RECONCILIATION_MISSING,
    SOURCE_IDENTITY_MISMATCH,
    AMBIGUOUS_RECONCILIATION,
    DUPLICATE_IDENTITY,
    LIFECYCLE_IDENTITY_MISMATCH,
    INVALID_LIFECYCLE_EVIDENCE,
    LIFECYCLE_CONTRADICTION,
    PROJECTION_MISMATCH,
    NON_MONOTONIC_LIFECYCLE_SEQUENCE,
}

/** One local observation. Equal fingerprints mean equal remote payload, not equal observation. */
data class CanonicalPaperLifecycleObservation(
    val databaseId: Long,
    val observedAtEpochMillis: Long,
    val status: String,
    val rawStatus: String,
    val terminal: Boolean,
    val filledQuantity: Double?,
    val filledAveragePriceUsd: Double?,
    val filledAtIso: String?,
    val source: String,
    val httpStatusCode: Int?,
    val submitAuditEntryId: Long?,
    val payloadFingerprint: String,
    val samePayloadAsPrevious: Boolean,
)

/**
 * Canonical, read-only Paper history aggregate.
 *
 * Audit rows own submit identity and semantics; lifecycle observations own current status/fill;
 * reconciliation is only a checked projection plus the reset acknowledgement authority.
 */
data class CanonicalPaperOrderHistory(
    val submitAttemptId: String,
    /** Stable order-list sequence: earliest durable audit row id, never wall-clock time. */
    val orderSequenceId: Long?,
    val linkedClientDryRunId: String?,
    val previewId: String?,
    val auditStartRowId: Long?,
    val auditResultRowId: Long?,
    val alpacaOrderId: String?,
    val clientOrderId: String?,
    val symbol: String?,
    val side: String?,
    val quantity: Double?,
    val orderType: String?,
    val timeInForce: String?,
    val limitPriceUsd: Double?,
    val dryRunAuditRowId: Long?,
    val decisionCreatedAtEpochMillis: Long?,
    val localSubmitResult: String?,
    val localSubmitResultAtEpochMillis: Long?,
    val submitHttpStatusCode: Int?,
    val initialAlpacaStatus: String?,
    val alpacaSubmittedAtIso: String?,
    /** Always ordered by lifecycle database id ASC (ingestion sequence). */
    val lifecycleObservations: List<CanonicalPaperLifecycleObservation>,
    /** Derived from [lifecycleObservations], never from the reconciliation cache. */
    val currentLifecycle: CanonicalPaperLifecycleObservation?,
    val mappingState: String?,
    val resolved: Boolean,
    val unresolved: Boolean,
    val ambiguous: Boolean,
    val resetAcknowledgedAtEpochMillis: Long?,
    val integrityStatus: PaperHistoryIntegrityStatus,
    val integrityDiagnostics: List<PaperHistoryIntegrityDiagnostic>,
) {
    /** Input for future position reconciliation; no broker position is queried here. */
    val expectedSignedPositionDelta: Double?
        get() = if (currentLifecycle?.status == "FILLED" && quantity != null) {
            when (side) {
                "BUY" -> quantity
                "SELL" -> -quantity
                else -> null
            }
        } else {
            null
        }
}
