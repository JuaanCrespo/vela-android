package com.vela.android.lab.core.marketdataset.domain

enum class RawMarketEventType { QUOTE, TRADE, BAR, BAR_UPDATE, STATUS, CONTROL, UNKNOWN }
enum class MarketEventIdentityProvenance { PROVIDER_ID, DETERMINISTIC_FINGERPRINT, LOCAL_INGEST_ID }

data class MarketEventIdentity(val provenance: MarketEventIdentityProvenance, val fingerprint: MarketFingerprint) {
    fun canonicalText(): String = record("event-id-v1", provenance.name, fingerprint.hex)
}

/** Small evidence shapes, not provider message parsers. No free-text body or arbitrary field map. */
sealed class RawMarketPayload {
    abstract val type: RawMarketEventType
    abstract fun canonicalText(): String

    data class Quote(val bid: RawMarketDecimal, val ask: RawMarketDecimal,
        val bidSize: RawMarketDecimal? = null, val askSize: RawMarketDecimal? = null) : RawMarketPayload() {
        override val type = RawMarketEventType.QUOTE
        init { positive(bid.exact); positive(ask.exact); bidSize?.let { nonnegative(it.exact) }; askSize?.let { nonnegative(it.exact) } }
        // Locked/crossed quotes are evidence; their quality interpretation belongs to a later policy.
        override fun canonicalText(): String = record("quote-v1", bid.text, ask.text, bidSize?.text, askSize?.text)
    }

    data class Trade(val price: RawMarketDecimal, val size: RawMarketDecimal) : RawMarketPayload() {
        override val type = RawMarketEventType.TRADE
        init { positive(price.exact); nonnegative(size.exact) }
        override fun canonicalText(): String = record("trade-v1", price.text, size.text)
    }

    data class Bar(val open: RawMarketDecimal, val high: RawMarketDecimal, val low: RawMarketDecimal,
        val close: RawMarketDecimal, val volume: RawMarketDecimal?, val tradeCount: Long? = null,
        val vwap: RawMarketDecimal? = null, override val type: RawMarketEventType = RawMarketEventType.BAR) : RawMarketPayload() {
        init {
            validate(type == RawMarketEventType.BAR || type == RawMarketEventType.BAR_UPDATE, DatasetValidationError.INVALID_PAYLOAD)
            validateOhlc(open.exact, high.exact, low.exact, close.exact)
            volume?.let { nonnegative(it.exact) }; vwap?.let { positive(it.exact) }
            validate(tradeCount == null || tradeCount >= 0, DatasetValidationError.INVALID_PAYLOAD)
        }
        override fun canonicalText(): String = record("raw-bar-v1", type.name, open.text, high.text, low.text, close.text,
            volume?.text, tradeCount?.toString(), vwap?.text)
    }

    /** Unknown/control evidence is opaque and cannot silently become a price or bar. */
    data class DigestOnly(override val type: RawMarketEventType, val safePayloadFingerprint: MarketFingerprint) : RawMarketPayload() {
        init { validate(type in setOf(RawMarketEventType.STATUS, RawMarketEventType.CONTROL, RawMarketEventType.UNKNOWN), DatasetValidationError.INVALID_PAYLOAD) }
        override fun canonicalText(): String = record("opaque-v1", type.name, safePayloadFingerprint.hex)
    }
}

data class RawMarketEventEnvelope(
    val instrument: MarketInstrumentIdentity,
    val source: MarketSourceIdentity,
    val eventTime: MarketTimestamp,
    val receivedAt: MarketTimestamp,
    val availableAt: MarketTimestamp,
    val ingestPosition: IngestPosition,
    val parserVersion: ParserVersion,
    val schemaVersion: DatasetSchemaVersion,
    val payload: RawMarketPayload,
    val providerEventId: ProviderEventId? = null,
) {
    init {
        compatible(instrument, source)
        validate(availableAt >= receivedAt, DatasetValidationError.INVALID_AVAILABILITY)
        // eventTime can exceed device receipt time: do not hide provider/device clock skew.
    }
    val eventType: RawMarketEventType get() = payload.type
    /** Provider evidence identity excludes receipt clock/sequence so repeated receipts remain recognisable. */
    fun canonicalEvidenceText(): String = record("raw-evidence-v1", instrument.canonicalText(), source.canonicalText(),
        eventType.name, eventTime.canonicalText(), parserVersion.value.toString(), schemaVersion.value.toString(),
        providerEventId?.value, payload.canonicalText())
    val contentFingerprint: MarketFingerprint = MarketFingerprint.ofCanonical(canonicalEvidenceText())
    val eventIdentity: MarketEventIdentity = if (providerEventId == null) {
        MarketEventIdentity(MarketEventIdentityProvenance.DETERMINISTIC_FINGERPRINT, contentFingerprint)
    } else MarketEventIdentity(MarketEventIdentityProvenance.PROVIDER_ID, MarketFingerprint.ofCanonical(record("provider-event-v1",
        instrument.canonicalText(), source.canonicalText(), eventType.name, providerEventId.value)))
    val receiptIdentity: MarketEventIdentity = MarketEventIdentity(MarketEventIdentityProvenance.LOCAL_INGEST_ID,
        MarketFingerprint.ofCanonical(record("receipt-v1", ingestPosition.canonicalText())))
    fun canonicalEnvelopeText(): String = record("raw-envelope-v1", canonicalEvidenceText(), receivedAt.canonicalText(),
        availableAt.canonicalText(), ingestPosition.canonicalText())
    fun reference(): RawEvidenceReference = RawEvidenceReference(instrument, source, eventIdentity, contentFingerprint,
        ingestPosition, receivedAt, availableAt, parserVersion, schemaVersion)
}

data class RawEvidenceReference(
    val instrument: MarketInstrumentIdentity, val source: MarketSourceIdentity,
    val eventIdentity: MarketEventIdentity, val contentFingerprint: MarketFingerprint,
    val ingestPosition: IngestPosition, val receivedAt: MarketTimestamp, val availableAt: MarketTimestamp,
    val parserVersion: ParserVersion, val schemaVersion: DatasetSchemaVersion,
) {
    init { compatible(instrument, source); validate(availableAt >= receivedAt, DatasetValidationError.INVALID_AVAILABILITY) }
    fun canonicalText(): String = record("evidence-ref-v1", instrument.canonicalText(), source.canonicalText(),
        eventIdentity.canonicalText(), contentFingerprint.hex, ingestPosition.canonicalText(), receivedAt.canonicalText(), availableAt.canonicalText(),
        parserVersion.value.toString(), schemaVersion.value.toString())
}
