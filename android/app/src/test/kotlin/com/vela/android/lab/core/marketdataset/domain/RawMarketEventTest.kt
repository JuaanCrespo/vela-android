package com.vela.android.lab.core.marketdataset.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RawMarketEventTest {
    @Test fun `complete raw evidence has an independently calculated golden fingerprint`() {
        val fixed = RawMarketEventEnvelope(
            instrument = MarketInstrumentIdentity(DatasetAssetClass.EQUITY, MarketProviderId.ALPACA, MarketFeedId.IEX,
                CanonicalMarketSymbol("SPY"), ProviderMarketSymbol("SPY")),
            source = MarketSourceIdentity(MarketProviderId.ALPACA, MarketFeedId.IEX, MarketSourceClass.REAL_PROVIDER),
            eventTime = MarketTimestamp(1700000000, 123456789),
            receivedAt = MarketTimestamp(1700000001, 987654321),
            availableAt = MarketTimestamp(1700000002, 111),
            ingestPosition = IngestPosition(IngestSessionId("golden-session"), IngestSequence(42)),
            parserVersion = ParserVersion(3), schemaVersion = DatasetSchemaVersion(2),
            payload = RawMarketPayload.Quote(RawMarketDecimal("0.100000000000000001"),
                RawMarketDecimal("0.100000000000000002"), RawMarketDecimal("12.5000"), RawMarketDecimal("0")),
            providerEventId = ProviderEventId("provider-42"),
        )
        // Literal UTF-8 framing and SHA-256 were calculated outside the production serializer.
        val expectedText = "15:raw-evidence-v1" +
            "47:13:instrument-v16:EQUITY6:ALPACA3:IEX3:SPY3:SPY" +
            "40:9:source-v16:ALPACA3:IEX13:REAL_PROVIDER" +
            "5:QUOTE32:6:utc-v110:17000000009:123456789" +
            "1:31:211:provider-42" +
            "68:8:quote-v120:0.10000000000000000120:0.1000000000000000027:12.50001:0"
        assertEquals(expectedText, fixed.canonicalEvidenceText())
        assertEquals("6bf0f962f0b39d47a99da1e5f7fd6bdac9c142e0b4ec604b83caed05e5bda6cc", fixed.contentFingerprint.hex)
    }
    @Test fun `fingerprint uses standard SHA256 and framing has a golden vector`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MarketFingerprint.ofCanonical("abc").hex)
        assertEquals("1:x1:a0:-1:2:é", record("x", "a", "", null, "é"))
        assertNotEquals(record("x", "ab", "c"), record("x", "a", "bc"))
        assertNotEquals(record("x", "-1:"), record("x", null))
    }
    @Test fun `fingerprint input validates lowercase fixed length`() {
        for (text in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            invalid(DatasetValidationError.INVALID_FINGERPRINT) { MarketFingerprint(text) }
        }
    }
    @Test fun `exact duplicate evidence stable across allocations and repeated calculations`() {
        val first = event()
        repeat(100) { assertEquals(first, event()); assertEquals(first.contentFingerprint, event().contentFingerprint) }
    }
    @Test fun `authoritative field mutations change fingerprint`() {
        val a = event()
        val variants = listOf(a.copy(eventTime = time(0, 1)), a.copy(parserVersion = ParserVersion(2)), a.copy(schemaVersion = DatasetSchemaVersion(2)),
            a.copy(providerEventId = ProviderEventId("event-1")), a.copy(source = iex.copy(sourceClass = MarketSourceClass.UNKNOWN)),
            a.copy(payload = RawMarketPayload.Quote(RawMarketDecimal("100.00"), RawMarketDecimal("101.00"))),
            a.copy(payload = RawMarketPayload.Quote(RawMarketDecimal("100.0"), RawMarketDecimal("101.000000000000001"))),
            a.copy(payload = RawMarketPayload.Quote(RawMarketDecimal("100.0"), RawMarketDecimal("101.00"), RawMarketDecimal("0"))),
            a.copy(instrument = instrument().copy(providerSymbol = ProviderMarketSymbol("spy"))))
        variants.forEach { assertNotEquals(a.contentFingerprint, it.contentFingerprint) }
        assertEquals(variants.size, variants.map { it.contentFingerprint }.distinct().size)
    }
    @Test fun `same ticker time and values from TEST is distinct evidence`() {
        val a = event(); val b = a.copy(instrument = instrument(MarketFeedId.TEST), source = iex.copy(feed = MarketFeedId.TEST, sourceClass = MarketSourceClass.SYNTHETIC))
        assertNotEquals(a.contentFingerprint, b.contentFingerprint); assertNotEquals(a.eventIdentity, b.eventIdentity)
    }
    @Test fun `receipt order and availability do not replace content identity`() {
        val a = event(); val b = a.copy(ingestPosition = position(8), receivedAt = time(70), availableAt = time(80))
        assertEquals(a.contentFingerprint, b.contentFingerprint); assertEquals(a.eventIdentity, b.eventIdentity)
        assertNotEquals(a.receiptIdentity, b.receiptIdentity); assertNotEquals(a, b); assertNotEquals(a.canonicalEnvelopeText(), b.canonicalEnvelopeText())
        assertEquals(MarketEventIdentityProvenance.LOCAL_INGEST_ID, a.receiptIdentity.provenance)
    }
    @Test fun `provider identity is scoped while content revisions remain detectable`() {
        val a = event().copy(providerEventId = ProviderEventId("native-1"))
        val b = a.copy(payload = RawMarketPayload.Quote(RawMarketDecimal("102"), RawMarketDecimal("103")))
        assertEquals(MarketEventIdentityProvenance.PROVIDER_ID, a.eventIdentity.provenance)
        assertEquals(a.eventIdentity, b.eventIdentity); assertNotEquals(a.contentFingerprint, b.contentFingerprint)
        assertNotEquals(a.eventIdentity, a.copy(instrument = instrument(MarketFeedId.TEST), source = iex.copy(feed = MarketFeedId.TEST)).eventIdentity)
        assertEquals(MarketEventIdentityProvenance.DETERMINISTIC_FINGERPRINT, event().eventIdentity.provenance)
    }
    @Test fun `receive and available are explicit and cannot be reversed`() {
        assertEquals(time(60), event().receivedAt)
        invalid(DatasetValidationError.INVALID_AVAILABILITY) { event().copy(availableAt = time(59)) }
        assertEquals(time(120), event().copy(eventTime = time(120)).eventTime)
    }
    @Test fun `mismatched source and instrument rejected`() {
        invalid(DatasetValidationError.SOURCE_MISMATCH) { event().copy(source = iex.copy(feed = MarketFeedId.TEST)) }
        invalid(DatasetValidationError.SOURCE_MISMATCH) { event().reference().copy(source = iex.copy(provider = MarketProviderId.LOCAL)) }
    }
    @Test fun `payload field semantics reject invalid prices sizes and counts`() {
        invalid(DatasetValidationError.INVALID_PAYLOAD) { RawMarketPayload.Quote(RawMarketDecimal("0"), RawMarketDecimal("1")) }
        invalid(DatasetValidationError.INVALID_PAYLOAD) { RawMarketPayload.Trade(RawMarketDecimal("1"), RawMarketDecimal("-1")) }
        invalid(DatasetValidationError.INVALID_PAYLOAD) { RawMarketPayload.Quote(RawMarketDecimal("1"), RawMarketDecimal("2"), askSize = RawMarketDecimal("-1")) }
        val p = RawMarketPayload.Bar(RawMarketDecimal("1"), RawMarketDecimal("1"), RawMarketDecimal("1"), RawMarketDecimal("1"), null)
        invalid(DatasetValidationError.INVALID_PAYLOAD) { p.copy(tradeCount = -1) }
        invalid(DatasetValidationError.INVALID_PAYLOAD) { p.copy(vwap = RawMarketDecimal("0")) }
        invalid(DatasetValidationError.INVALID_PAYLOAD) { p.copy(type = RawMarketEventType.QUOTE) }
        assertNotEquals(p.canonicalText(), p.copy(volume = RawMarketDecimal("0")).canonicalText())
        assertNotEquals(p.canonicalText(), p.copy(type = RawMarketEventType.BAR_UPDATE).canonicalText())
    }
    @Test fun `unknown control and status only carry safe fingerprint`() {
        for (type in listOf(RawMarketEventType.UNKNOWN, RawMarketEventType.STATUS, RawMarketEventType.CONTROL)) {
            assertEquals(type, event().copy(payload = RawMarketPayload.DigestOnly(type, event().contentFingerprint)).eventType)
        }
        invalid(DatasetValidationError.INVALID_PAYLOAD) { RawMarketPayload.DigestOnly(RawMarketEventType.QUOTE, event().contentFingerprint) }
    }
    @Test fun `locked and crossed quotes retained for later quality policy`() {
        assertNotNull(RawMarketPayload.Quote(RawMarketDecimal("2"), RawMarketDecimal("1")))
        assertNotNull(RawMarketPayload.Quote(RawMarketDecimal("1"), RawMarketDecimal("1")))
    }
}
