package com.vela.android.lab.core.marketdataset.domain

/** Bounds only, not a query, materialized dataset, row digest, quality gate or training permission. */
class DatasetSnapshotScope(sources: Collection<MarketSourceIdentity>, instruments: Collection<MarketInstrumentIdentity>,
    highWater: Collection<IngestPosition>) {
    val sources: List<MarketSourceIdentity> = frozenList(sources.distinct().sortedBy { it.canonicalText() })
    val instruments: List<MarketInstrumentIdentity> = frozenList(instruments.distinct().sortedBy { it.canonicalText() })
    val highWater: List<IngestPosition> = frozenList(highWater.sortedBy { it.session.value })
    init {
        validate(this.sources.isNotEmpty() && this.instruments.isNotEmpty() && this.highWater.isNotEmpty(), DatasetValidationError.INVALID_SCOPE)
        validate(this.highWater.map { it.session }.distinct().size == this.highWater.size, DatasetValidationError.INVALID_SCOPE)
        validate(this.instruments.all { instrument -> this.sources.any { it.provider == instrument.provider && it.feed == instrument.feed } }, DatasetValidationError.INVALID_SCOPE)
    }
    fun contains(instrument: MarketInstrumentIdentity, source: MarketSourceIdentity, position: IngestPosition): Boolean =
        instrument in instruments && source in sources && instrument.provider == source.provider && instrument.feed == source.feed &&
            highWater.any { it.session == position.session && position.sequence <= it.sequence }
    fun canonicalText(): String = record("scope-v1", record("sources", *sources.map { it.canonicalText() }.toTypedArray()),
        record("instruments", *instruments.map { it.canonicalText() }.toTypedArray()), record("high-water", *highWater.map { it.canonicalText() }.toTypedArray()))
    override fun equals(other: Any?): Boolean = other is DatasetSnapshotScope && sources == other.sources && instruments == other.instruments && highWater == other.highWater
    override fun hashCode(): Int = (31 * sources.hashCode() + instruments.hashCode()) * 31 + highWater.hashCode()
}

data class DatasetSnapshotSpec(
    val datasetId: DatasetId,
    val schemaVersion: DatasetSchemaVersion,
    val parserVersion: ParserVersion,
    val canonicalizationVersion: CanonicalizationVersion,
    val qualityPolicyVersion: QualityPolicyVersion,
    val scope: DatasetSnapshotScope,
    val eventTimeRange: MarketTimeRange,
    val availabilityCutoff: MarketTimestamp,
) {
    fun includes(event: RawMarketEventEnvelope): Boolean = event.schemaVersion == schemaVersion && event.parserVersion == parserVersion &&
        DatasetSourcePolicy.isCandidate(event.instrument, event.source) &&
        scope.contains(event.instrument, event.source, event.ingestPosition) && event.eventTime in eventTimeRange &&
        MarketAvailability.isVisible(event.availableAt, availabilityCutoff)

    /** Full interval containment and every raw input's causal high-water are necessary, not training-sufficient. */
    fun includes(bar: CanonicalMarketBar): Boolean = bar.schemaVersion == schemaVersion && bar.canonicalizationVersion == canonicalizationVersion &&
        DatasetSourcePolicy.isCandidate(bar.identity.instrument, bar.identity.source) &&
        bar.identity.startTime in eventTimeRange && bar.endTime <= eventTimeRange.endExclusive &&
        MarketAvailability.isVisible(bar.availableAt, availabilityCutoff) &&
        bar.evidence.references.all { it.parserVersion == parserVersion && it.schemaVersion == schemaVersion &&
            scope.contains(it.instrument, it.source, it.ingestPosition) }

    fun canonicalText(): String = record("dataset-cut-spec-v1", datasetId.value, schemaVersion.value.toString(), parserVersion.value.toString(),
        canonicalizationVersion.value.toString(), qualityPolicyVersion.value.toString(), scope.canonicalText(),
        eventTimeRange.canonicalText(), availabilityCutoff.canonicalText())
    /** Identity of the specification, deliberately NOT a digest of selected/materialized rows. */
    val specFingerprint: MarketFingerprint = MarketFingerprint.ofCanonical(canonicalText())
}
