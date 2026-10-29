package com.vela.android.lab.core.marketdataset.domain

import java.util.Locale
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarketIdentityTest {
    @Test fun `IEX and TEST cannot share instrument series`() {
        assertNotEquals(instrument(), instrument(MarketFeedId.TEST))
        assertNotEquals(instrument().fingerprint, instrument(MarketFeedId.TEST).fingerprint)
    }
    @Test fun `provider source class and exact provider spelling belong to identity`() {
        assertNotEquals(iex, iex.copy(sourceClass = MarketSourceClass.UNKNOWN))
        assertNotEquals(instrument().fingerprint, instrument().copy(providerSymbol = ProviderMarketSymbol("spy")).fingerprint)
        assertNotEquals(instrument().fingerprint, instrument().copy(provider = MarketProviderId("FUTURE_PROVIDER")).fingerprint)
        assertNotEquals(instrument().fingerprint, instrument().copy(assetClass = DatasetAssetClass.UNKNOWN).fingerprint)
    }
    @Test fun `normalization is explicit not constructor magic`() {
        assertEquals("spy", CanonicalMarketSymbol("spy").value)
        assertEquals("SPY", CanonicalMarketSymbol.uppercase("spy").value)
        assertEquals("btc%2Fusd", ProviderMarketSymbol("btc%2Fusd").value)
    }
    @Test fun `uppercase does not depend on Turkish locale`() {
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("I", CanonicalMarketSymbol.uppercase("i").value) }
        finally { Locale.setDefault(original) }
    }
    @ParameterizedTest @ValueSource(strings = ["", " ", " SPY", "SPY ", "SPY\n", "S\tPY", "S\u0000PY", "S\u007fPY"])
    fun `invalid symbols fail structurally`(text: String) {
        invalid(DatasetValidationError.INVALID_SYMBOL) { CanonicalMarketSymbol(text) }
        invalid(DatasetValidationError.INVALID_SYMBOL) { ProviderMarketSymbol(text) }
    }
    @Test fun `future legitimate symbols are not limited to stock regex`() {
        listOf("BRK.B", "BTC/USD", "A-B", "ABC:DEF", "銘柄", "OPT 2028", "X\uD83D\uDE00").forEach { assertEquals(it, ProviderMarketSymbol(it).value) }
    }
    @Test fun `unpaired surrogates cannot collapse through UTF8 replacement`() {
        invalid(DatasetValidationError.INVALID_SYMBOL) { ProviderMarketSymbol("X\uD800") }
        invalid(DatasetValidationError.INVALID_SYMBOL) { CanonicalMarketSymbol("\uDC00X") }
    }
    @Test fun `only explicitly known real combination is source candidate`() {
        assertTrue(DatasetSourcePolicy.isCandidate(instrument(), iex))
        MarketSourceClass.entries.filter { it != MarketSourceClass.REAL_PROVIDER }.forEach {
            assertFalse(DatasetSourcePolicy.isCandidate(instrument(), iex.copy(sourceClass = it)))
        }
    }
    @Test fun `TEST feed cannot become candidate by relabelling class real`() {
        assertFalse(DatasetSourcePolicy.isCandidate(instrument(MarketFeedId.TEST), iex.copy(feed = MarketFeedId.TEST)))
    }
    @Test fun `unknown asset feed provider and future feed fail conservatively`() {
        assertFalse(DatasetSourcePolicy.isCandidate(instrument().copy(assetClass = DatasetAssetClass.UNKNOWN), iex))
        for (feed in listOf(MarketFeedId.UNKNOWN, MarketFeedId("FUTURE_FEED"))) {
            assertFalse(DatasetSourcePolicy.isCandidate(instrument(feed), iex.copy(feed = feed)))
        }
        val provider = MarketProviderId("FUTURE_PROVIDER")
        assertFalse(DatasetSourcePolicy.isCandidate(instrument().copy(provider = provider), iex.copy(provider = provider)))
        assertFalse(DatasetSourcePolicy.isCandidate(instrument(MarketFeedId.TEST), iex))
    }
    @Test fun `legacy and demo never gain authority from plausible ticker or price`() {
        listOf(MarketSourceClass.LEGACY_UNKNOWN, MarketSourceClass.DEMO, MarketSourceClass.SYNTHETIC).forEach {
            assertFalse(DatasetSourcePolicy.isCandidate(instrument(), iex.copy(sourceClass = it)))
        }
    }
    @Test fun `invalid identity code and version types fail with stable codes`() {
        invalid(DatasetValidationError.INVALID_IDENTIFIER) { MarketProviderId("alpaca") }
        invalid(DatasetValidationError.INVALID_IDENTIFIER) { MarketFeedId("") }
        invalid(DatasetValidationError.INVALID_IDENTIFIER) { DatasetId("\n") }
        invalid(DatasetValidationError.INVALID_IDENTIFIER) { IngestSessionId(" ") }
        invalid(DatasetValidationError.INVALID_IDENTIFIER) { ProviderEventId("\uD800") }
        listOf<() -> Unit>({ DatasetSchemaVersion(0) }, { ParserVersion(-1) }, { CanonicalizationVersion(0) }, { QualityPolicyVersion(0) }).forEach {
            invalid(DatasetValidationError.INVALID_VERSION, it)
        }
    }
    @Test fun `version types are not interchangeable or application versions`() {
        assertNotEquals(DatasetSchemaVersion(1) as Any, ParserVersion(1) as Any)
        assertNotEquals(ParserVersion(1) as Any, CanonicalizationVersion(1) as Any)
        assertEquals(Int.MAX_VALUE, DatasetSchemaVersion(Int.MAX_VALUE).value)
    }
}
