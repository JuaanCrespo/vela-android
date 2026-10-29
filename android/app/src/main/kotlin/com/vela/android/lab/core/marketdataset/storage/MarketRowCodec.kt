package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*

/** Explicit V1 columns, not JSON, reflection or a second set of market-domain models. */
internal object MarketRowCodec {
    private fun identity(instrument: MarketInstrumentIdentity, source: MarketSourceIdentity) = linkedMapOf<String, String?>(
        "provider" to source.provider.code, "feed" to source.feed.code, "source_class" to source.sourceClass.name,
        "asset_class" to instrument.assetClass.name, "canonical_symbol" to instrument.canonicalSymbol.value,
        "provider_symbol" to instrument.providerSymbol.value, "instrument_fp" to instrument.fingerprint.hex,
    )
    private fun MutableMap<String, String?>.time(prefix: String, value: MarketTimestamp) {
        put("${prefix}_sec", value.epochSecond.toString()); put("${prefix}_nano", value.nanoAdjustment.toString())
    }
    fun source(row: Map<String, String?>) = MarketSourceIdentity(MarketProviderId(row.text("provider")),
        MarketFeedId(row.text("feed")), MarketSourceClass.valueOf(row.text("source_class")))
    private fun instrument(row: Map<String, String?>) = MarketInstrumentIdentity(DatasetAssetClass.valueOf(row.text("asset_class")),
        MarketProviderId(row.text("provider")), MarketFeedId(row.text("feed")), CanonicalMarketSymbol(row.text("canonical_symbol")), ProviderMarketSymbol(row.text("provider_symbol")))
    fun raw(event: RawMarketEventEnvelope): Map<String, String?> = identity(event.instrument, event.source).apply {
        put("session_id", event.ingestPosition.session.value); put("sequence", event.ingestPosition.sequence.value.toString())
        time("event", event.eventTime); time("received", event.receivedAt); time("available", event.availableAt)
        put("parser_version", event.parserVersion.value.toString()); put("dataset_schema_version", event.schemaVersion.value.toString())
        put("event_type", event.eventType.name); put("provider_event_id", event.providerEventId?.value)
        put("event_identity_kind", event.eventIdentity.provenance.name); put("event_identity_fp", event.eventIdentity.fingerprint.hex)
        put("content_fp", event.contentFingerprint.hex); put("receipt_fp", event.receiptIdentity.fingerprint.hex)
        for (column in listOf("bid", "ask", "bid_size", "ask_size", "trade_price", "trade_size", "open_value", "high_value", "low_value", "close_value", "volume", "trade_count", "vwap", "digest_fp")) put(column, null)
        when (val payload = event.payload) {
            is RawMarketPayload.Quote -> {
                put("bid", payload.bid.text); put("ask", payload.ask.text); put("bid_size", payload.bidSize?.text); put("ask_size", payload.askSize?.text)
            }
            is RawMarketPayload.Trade -> { put("trade_price", payload.price.text); put("trade_size", payload.size.text) }
            is RawMarketPayload.Bar -> {
                put("open_value", payload.open.text); put("high_value", payload.high.text); put("low_value", payload.low.text); put("close_value", payload.close.text)
                put("volume", payload.volume?.text); put("trade_count", payload.tradeCount?.toString()); put("vwap", payload.vwap?.text)
            }
            is RawMarketPayload.DigestOnly -> put("digest_fp", payload.safePayloadFingerprint.hex)
        }
    }
    fun raw(row: Map<String, String?>): RawMarketEventEnvelope {
        fun value(name: String) = RawMarketDecimal(row.text(name))
        fun optional(name: String) = row[name]?.let(::RawMarketDecimal)
        val type = RawMarketEventType.valueOf(row.text("event_type"))
        val payload = when (type) {
            RawMarketEventType.QUOTE -> RawMarketPayload.Quote(value("bid"), value("ask"), optional("bid_size"), optional("ask_size"))
            RawMarketEventType.TRADE -> RawMarketPayload.Trade(value("trade_price"), value("trade_size"))
            RawMarketEventType.BAR, RawMarketEventType.BAR_UPDATE -> RawMarketPayload.Bar(value("open_value"), value("high_value"), value("low_value"), value("close_value"),
                optional("volume"), row["trade_count"]?.let { row.number("trade_count") }, optional("vwap"), type)
            RawMarketEventType.STATUS, RawMarketEventType.CONTROL, RawMarketEventType.UNKNOWN -> RawMarketPayload.DigestOnly(type, MarketFingerprint(row.text("digest_fp")))
        }
        val result = RawMarketEventEnvelope(instrument(row), source(row), row.timestamp("event"), row.timestamp("received"), row.timestamp("available"),
            IngestPosition(IngestSessionId(row.text("session_id")), IngestSequence(row.number("sequence"))),
            ParserVersion(row.version("parser_version")), DatasetSchemaVersion(row.version("dataset_schema_version")), payload, row["provider_event_id"]?.let(::ProviderEventId))
        demand(row == raw(result), MarketStorageError.DATA_INTEGRITY_ERROR, "RAW_CONTENT_OR_IDENTITY")
        return result
    }
    fun bar(bar: CanonicalMarketBar): Map<String, String?> = identity(bar.identity.instrument, bar.identity.source).apply {
        put("revision_id", bar.revisionIdentity.hex); put("logical_id", bar.identity.fingerprint.hex); put("interval_seconds", bar.identity.interval.seconds.toString())
        time("start", bar.identity.startTime); time("end", bar.endTime); time("received", bar.receivedAt); time("available", bar.availableAt)
        put("open_value", bar.open.toString()); put("high_value", bar.high.toString()); put("low_value", bar.low.toString()); put("close_value", bar.close.toString())
        put("volume", bar.volume?.toString()); put("trade_count", bar.tradeCount?.toString()); put("vwap", bar.vwap?.toString()); put("finality", bar.finality.name)
        put("revision_number", bar.revision.number.toString()); put("previous_content_fp", bar.revision.previousRevisionFingerprint?.hex)
        put("dataset_schema_version", bar.schemaVersion.value.toString()); put("canonical_version", bar.canonicalizationVersion.value.toString()); put("content_fp", bar.contentFingerprint.hex)
    }
    fun lineage(revisionId: String, reference: RawEvidenceReference): Map<String, String?> = linkedMapOf(
        "revision_id" to revisionId, "session_id" to reference.ingestPosition.session.value, "sequence" to reference.ingestPosition.sequence.value.toString(),
        "content_fp" to reference.contentFingerprint.hex, "parser_version" to reference.parserVersion.value.toString(), "dataset_schema_version" to reference.schemaVersion.value.toString(),
    )
    fun bar(row: Map<String, String?>, evidence: BarEvidence): CanonicalMarketBar {
        fun value(name: String) = ExactMarketDecimal.parse(row.text(name))
        val result = CanonicalMarketBar(LogicalBarIdentity(instrument(row), source(row), BarInterval(row.number("interval_seconds")), row.timestamp("start")),
            row.timestamp("end"), row.timestamp("received"), row.timestamp("available"), value("open_value"), value("high_value"), value("low_value"), value("close_value"),
            row["volume"]?.let(ExactMarketDecimal::parse), row["trade_count"]?.let { row.number("trade_count") }, row["vwap"]?.let(ExactMarketDecimal::parse),
            BarFinality.valueOf(row.text("finality")), BarRevision(row.number("revision_number"), row["previous_content_fp"]?.let(::MarketFingerprint)),
            DatasetSchemaVersion(row.version("dataset_schema_version")), CanonicalizationVersion(row.version("canonical_version")), evidence)
        demand(row == bar(result), MarketStorageError.DATA_INTEGRITY_ERROR, "BAR_CONTENT_OR_IDENTITY")
        return result
    }
}
