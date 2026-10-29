package com.vela.android.lab.core.marketdataset.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

internal fun decimal(value: String) = ExactMarketDecimal.parse(value)
internal fun time(second: Long, nano: Int = 0) = MarketTimestamp(second, nano)
internal val iex = MarketSourceIdentity(MarketProviderId.ALPACA, MarketFeedId.IEX, MarketSourceClass.REAL_PROVIDER)
internal fun instrument(feed: MarketFeedId = MarketFeedId.IEX) = MarketInstrumentIdentity(DatasetAssetClass.EQUITY,
    MarketProviderId.ALPACA, feed, CanonicalMarketSymbol("SPY"), ProviderMarketSymbol("SPY"))
internal fun position(value: Long = 7, session: String = "session-A") = IngestPosition(IngestSessionId(session), IngestSequence(value))
internal fun event() = RawMarketEventEnvelope(instrument(), iex, time(0), time(60), time(60), position(),
    ParserVersion(1), DatasetSchemaVersion(1), RawMarketPayload.Quote(RawMarketDecimal("100.0"), RawMarketDecimal("101.00")))
internal fun bar() = CanonicalMarketBar(LogicalBarIdentity(instrument(), iex, BarInterval.ONE_MINUTE, time(0)), time(60),
    time(60), time(60), decimal("100"), decimal("102"), decimal("99"), decimal("101"), decimal("5"), null, null,
    BarFinality.FINAL, BarRevision(0, null), DatasetSchemaVersion(1), CanonicalizationVersion(1), BarEvidence(listOf(event().reference()), emptyList()))
internal fun spec() = DatasetSnapshotSpec(DatasetId("dataset-A"), DatasetSchemaVersion(1), ParserVersion(1),
    CanonicalizationVersion(1), QualityPolicyVersion(1), DatasetSnapshotScope(listOf(iex), listOf(instrument()), listOf(position(8))),
    MarketTimeRange(time(0), time(360)), time(180))
internal fun invalid(expected: DatasetValidationError, action: () -> Unit) {
    val failure = assertThrows(DatasetValidationException::class.java, action)
    assertEquals(expected, failure.error)
    assertEquals(expected.name, failure.message)
}
