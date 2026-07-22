package com.vela.android.lab.data.market.source.alpaca

/**
 * Tries each delegate in order and returns the first non-null
 * result. Used by the lab to prefer in-app saved credentials
 * (Phase 2.c.1) and fall back to BuildConfig-from-`local.properties`
 * for headless developer scenarios (Phase 2.c).
 *
 * Construction enforces a non-empty list of providers — empty
 * lists are a programming error, not "no credentials".
 */
class CompositeAlpacaCredentialsProvider(
    private val providers: List<AlpacaCredentialsProvider>,
) : AlpacaCredentialsProvider {

    init {
        require(providers.isNotEmpty()) {
            "CompositeAlpacaCredentialsProvider requires at least one delegate."
        }
    }

    constructor(vararg providers: AlpacaCredentialsProvider) : this(providers.toList())

    override suspend fun read(): AlpacaCredentials? {
        for (provider in providers) {
            val credentials = provider.read()
            if (credentials != null) return credentials
        }
        return null
    }
}
