package com.vela.android.lab.data.market.source.alpaca

/**
 * Reads credentials from a [SecureAlpacaCredentialsStore]. Returns
 * `null` when the store has not been populated. **Never logs or
 * otherwise exposes the credential values.**
 */
class SecureAlpacaCredentialsProvider(
    private val store: SecureAlpacaCredentialsStore,
) : AlpacaCredentialsProvider {

    override suspend fun read(): AlpacaCredentials? = store.load()
}
