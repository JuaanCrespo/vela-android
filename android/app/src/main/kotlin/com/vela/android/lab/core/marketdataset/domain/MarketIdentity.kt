package com.vela.android.lab.core.marketdataset.domain

import java.util.Locale

/** Extensible stable codes are identities, never claims that an adapter exists. */
data class MarketProviderId(val code: String) {
    init { validate(Regex("[A-Z][A-Z0-9_.-]{0,63}").matches(code), DatasetValidationError.INVALID_IDENTIFIER) }
    companion object {
        val ALPACA = MarketProviderId("ALPACA")
        val LOCAL = MarketProviderId("LOCAL")
        val UNKNOWN = MarketProviderId("UNKNOWN")
    }
}

data class MarketFeedId(val code: String) {
    init { validate(Regex("[A-Z][A-Z0-9_.-]{0,63}").matches(code), DatasetValidationError.INVALID_IDENTIFIER) }
    companion object {
        val IEX = MarketFeedId("IEX")
        val TEST = MarketFeedId("TEST")
        val DEMO = MarketFeedId("DEMO")
        val UNKNOWN = MarketFeedId("UNKNOWN")
    }
}

enum class MarketSourceClass { REAL_PROVIDER, SYNTHETIC, DEMO, LEGACY_UNKNOWN, UNKNOWN }
enum class DatasetAssetClass { EQUITY, SYNTHETIC, UNKNOWN }

data class MarketSourceIdentity(val provider: MarketProviderId, val feed: MarketFeedId, val sourceClass: MarketSourceClass) {
    fun canonicalText(): String = record("source-v1", provider.code, feed.code, sourceClass.name)
}

/** No URL decoding, pair inference, trimming or case folding in the constructor. */
data class CanonicalMarketSymbol(val value: String) {
    init { validate(validText(value), DatasetValidationError.INVALID_SYMBOL) }
    companion object {
        /** Explicit caller choice, not implicit device-locale normalization. */
        fun uppercase(value: String): CanonicalMarketSymbol {
            validate(validText(value), DatasetValidationError.INVALID_SYMBOL)
            return CanonicalMarketSymbol(value.uppercase(Locale.ROOT))
        }
    }
}

data class ProviderMarketSymbol(val value: String) {
    init { validate(validText(value), DatasetValidationError.INVALID_SYMBOL) }
}

data class MarketInstrumentIdentity(
    val assetClass: DatasetAssetClass,
    val provider: MarketProviderId,
    val feed: MarketFeedId,
    val canonicalSymbol: CanonicalMarketSymbol,
    val providerSymbol: ProviderMarketSymbol,
) {
    fun canonicalText(): String = record("instrument-v1", assetClass.name, provider.code, feed.code, canonicalSymbol.value, providerSymbol.value)
    val fingerprint: MarketFingerprint get() = MarketFingerprint.ofCanonical(canonicalText())
}

internal fun compatible(instrument: MarketInstrumentIdentity, source: MarketSourceIdentity) =
    validate(instrument.provider == source.provider && instrument.feed == source.feed, DatasetValidationError.SOURCE_MISMATCH)

/** Only a provenance prerequisite. A true result grants neither training nor execution authority. */
object DatasetSourcePolicy {
    fun isCandidate(instrument: MarketInstrumentIdentity, source: MarketSourceIdentity): Boolean =
        instrument.provider == source.provider && instrument.feed == source.feed &&
            instrument.assetClass == DatasetAssetClass.EQUITY && source.sourceClass == MarketSourceClass.REAL_PROVIDER &&
            source.provider == MarketProviderId.ALPACA && source.feed == MarketFeedId.IEX
}
