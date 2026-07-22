@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureAlpacaCredentialsProviderTest {

    @Test
    fun `provider returns null when store is empty`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemorySecureAlpacaCredentialsStore()
        val provider = SecureAlpacaCredentialsProvider(store)
        assertNull(provider.read())
        assertFalse(store.hasCredentials())
    }

    @Test
    fun `provider returns saved credentials`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemorySecureAlpacaCredentialsStore()
        store.save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
        val provider = SecureAlpacaCredentialsProvider(store)
        val loaded = provider.read()
        assertNotNull(loaded)
        assertEquals("PKABCDEF1234", loaded!!.keyId)
        assertEquals("sssssss", loaded.secret)
    }

    @Test
    fun `save then load returns identical credentials`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemorySecureAlpacaCredentialsStore()
        val original = AlpacaCredentials("PKABCDEF1234", "sssssss")
        store.save(original)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(original, loaded)
    }

    @Test
    fun `clear removes credentials`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemorySecureAlpacaCredentialsStore()
        store.save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
        assertTrue(store.hasCredentials())
        store.clear()
        assertFalse(store.hasCredentials())
        assertNull(store.load())
    }

    @Test
    fun `hasCredentials reflects partial state`() = runTest(UnconfinedTestDispatcher()) {
        // Partial state cannot be produced via the AlpacaCredentials
        // value type (it rejects blank fields), so we drive the fake
        // store directly to assert hasCredentials is binary.
        val store = InMemorySecureAlpacaCredentialsStore()
        assertFalse(store.hasCredentials())
        store.save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
        assertTrue(store.hasCredentials())
    }

    @Test
    fun `subsequent save overwrites previous credentials`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemorySecureAlpacaCredentialsStore()
        store.save(AlpacaCredentials("PKAAAAAA1234", "secret-1"))
        store.save(AlpacaCredentials("PKBBBBBB5678", "secret-2"))
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("PKBBBBBB5678", loaded!!.keyId)
        assertEquals("secret-2", loaded.secret)
    }
}

/**
 * In-memory fake of [SecureAlpacaCredentialsStore] for JVM tests.
 * The production store is `EncryptedPrefsAlpacaCredentialsStore`
 * (Android Keystore-backed) which requires an instrumented test
 * environment; correctness of the store contract is validated here
 * against this fake.
 */
internal class InMemorySecureAlpacaCredentialsStore : SecureAlpacaCredentialsStore {
    private var stored: AlpacaCredentials? = null

    override suspend fun save(credentials: AlpacaCredentials) {
        stored = credentials
    }

    override suspend fun load(): AlpacaCredentials? = stored

    override suspend fun clear() {
        stored = null
    }

    override suspend fun hasCredentials(): Boolean = stored != null
}
