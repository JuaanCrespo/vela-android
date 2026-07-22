package com.vela.android.lab.data.market.source.alpaca

/**
 * Persistent store for Alpaca **paper** credentials entered by the
 * user via the in-app settings card. Implementations must encrypt
 * the values at rest — for the Android lab this is
 * [EncryptedPrefsAlpacaCredentialsStore] backed by Android Keystore.
 *
 * The Phase 2.c.1 safety contract:
 *
 *  - Values written via [save] are encrypted before they reach disk.
 *  - [load] returns the decrypted value; callers must not log it.
 *  - [clear] removes every value the store holds for the lab's
 *    namespace.
 *  - [hasCredentials] never returns a partial state — true means
 *    both key id and secret are present.
 *
 * The store has **no** method that submits orders, mutates account
 * state, or performs any trading action.
 */
interface SecureAlpacaCredentialsStore {
    suspend fun save(credentials: AlpacaCredentials)
    suspend fun load(): AlpacaCredentials?
    suspend fun clear()
    suspend fun hasCredentials(): Boolean
}
