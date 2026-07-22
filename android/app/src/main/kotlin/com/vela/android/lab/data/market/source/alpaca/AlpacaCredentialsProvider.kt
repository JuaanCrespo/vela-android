package com.vela.android.lab.data.market.source.alpaca

/**
 * Abstract source of [AlpacaCredentials]. Returns `null` when no
 * credentials are configured — callers must treat that as a hard
 * authentication failure rather than a reason to skip authentication.
 *
 * Implementations live outside the source tree (Keystore-backed,
 * test-only fakes, etc.). Phase 2.b ships only [NoAlpacaCredentialsProvider]
 * as the production default, plus a fake in the test source set.
 */
fun interface AlpacaCredentialsProvider {
    suspend fun read(): AlpacaCredentials?
}
