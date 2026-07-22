package com.vela.android.lab.data.market.source.alpaca

/**
 * Production-safe default: **no credentials configured**. The lab
 * does not ship credentials; the Phase 2.b client surface returns
 * an [com.vela.android.lab.data.market.source.MarketDataError.AuthenticationFailed]
 * the moment it attempts to authenticate without them.
 */
object NoAlpacaCredentialsProvider : AlpacaCredentialsProvider {
    override suspend fun read(): AlpacaCredentials? = null
}
