package com.vela.android.lab.core.marketdataset.domain

enum class BarFinality { PROVISIONAL, FINAL, REVISED }
enum class BarQualityFlag { LATE, PARTIAL, UNKNOWN }

internal fun validateOhlc(open: ExactMarketDecimal, high: ExactMarketDecimal, low: ExactMarketDecimal, close: ExactMarketDecimal) {
    validate(listOf(open, high, low, close).all { it > ExactMarketDecimal.ZERO } &&
        high >= open && high >= close && high >= low && low <= open && low <= close, DatasetValidationError.INVALID_BAR)
}

/** Defensive copies at the public boundary; a mutable caller collection cannot rewrite lineage. */
class BarEvidence(references: Collection<RawEvidenceReference>, qualityFlags: Collection<BarQualityFlag>) {
    val references: List<RawEvidenceReference> = frozenList(references.sortedBy { it.canonicalText() })
    val qualityFlags: List<BarQualityFlag> = frozenList(qualityFlags.distinct().sortedBy { it.name })
    init { validate(this.references.isNotEmpty() && this.references.distinct().size == this.references.size, DatasetValidationError.INVALID_LINEAGE) }
    fun canonicalText(): String = record("bar-evidence-v1", record("references", *references.map { it.canonicalText() }.toTypedArray()),
        record("quality", *qualityFlags.map { it.name }.toTypedArray()))
    override fun equals(other: Any?): Boolean = other is BarEvidence && references == other.references && qualityFlags == other.qualityFlags
    override fun hashCode(): Int = 31 * references.hashCode() + qualityFlags.hashCode()
}

data class BarRevision(val number: Long, val previousRevisionFingerprint: MarketFingerprint?) {
    init { validate(number >= 0 && ((number == 0L) == (previousRevisionFingerprint == null)), DatasetValidationError.INVALID_REVISION) }
    fun canonicalText(): String = record("revision-v1", number.toString(), previousRevisionFingerprint?.hex)
}

data class LogicalBarIdentity(val instrument: MarketInstrumentIdentity, val source: MarketSourceIdentity,
    val interval: BarInterval, val startTime: MarketTimestamp) {
    init { compatible(instrument, source); validate(interval.isBoundary(startTime), DatasetValidationError.INVALID_INTERVAL) }
    fun canonicalText(): String = record("logical-bar-v1", instrument.canonicalText(), source.canonicalText(), interval.seconds.toString(), startTime.canonicalText())
    val fingerprint: MarketFingerprint get() = MarketFingerprint.ofCanonical(canonicalText())
}

/** One immutable revision. Does not select finality, apply corrections, or replace another value. */
data class CanonicalMarketBar(
    val identity: LogicalBarIdentity,
    val endTime: MarketTimestamp,
    val receivedAt: MarketTimestamp,
    val availableAt: MarketTimestamp,
    val open: ExactMarketDecimal, val high: ExactMarketDecimal, val low: ExactMarketDecimal, val close: ExactMarketDecimal,
    val volume: ExactMarketDecimal?,
    val tradeCount: Long?,
    val vwap: ExactMarketDecimal?,
    val finality: BarFinality,
    val revision: BarRevision,
    val schemaVersion: DatasetSchemaVersion,
    val canonicalizationVersion: CanonicalizationVersion,
    val evidence: BarEvidence,
) {
    init {
        validate(endTime > identity.startTime && endTime == identity.startTime.plusSeconds(identity.interval.seconds), DatasetValidationError.INVALID_INTERVAL)
        validateOhlc(open, high, low, close)
        volume?.let { nonnegative(it) }; vwap?.let { positive(it) }
        validate(tradeCount == null || tradeCount >= 0, DatasetValidationError.INVALID_BAR)
        validate(finality != BarFinality.REVISED || revision.number > 0, DatasetValidationError.INVALID_REVISION)
        validate(availableAt >= receivedAt, DatasetValidationError.INVALID_AVAILABILITY)
        validate(evidence.references.all { it.instrument == identity.instrument && it.source == identity.source }, DatasetValidationError.SOURCE_MISMATCH)
        validate(evidence.references.all { it.availableAt <= availableAt && it.receivedAt <= receivedAt }, DatasetValidationError.INVALID_AVAILABILITY)
    }
    val revisionIdentity: MarketFingerprint = MarketFingerprint.ofCanonical(record("bar-revision-id-v1", identity.canonicalText(),
        revision.number.toString(), schemaVersion.value.toString(), canonicalizationVersion.value.toString()))
    fun canonicalText(): String = record("canonical-bar-v1", identity.canonicalText(), endTime.canonicalText(), receivedAt.canonicalText(),
        availableAt.canonicalText(), open.toString(), high.toString(), low.toString(), close.toString(), volume?.toString(),
        tradeCount?.toString(), vwap?.toString(), finality.name, revision.canonicalText(), schemaVersion.value.toString(),
        canonicalizationVersion.value.toString(), evidence.canonicalText())
    val contentFingerprint: MarketFingerprint = MarketFingerprint.ofCanonical(canonicalText())
}
