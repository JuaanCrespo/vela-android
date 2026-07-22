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

class BuildConfigAlpacaCredentialsProviderTest {

    @Test
    fun `blank key returns null`() = runTest(UnconfinedTestDispatcher()) {
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { "" },
            secretSource = { "secret" },
        )
        assertNull(provider.read())
    }

    @Test
    fun `blank secret returns null`() = runTest(UnconfinedTestDispatcher()) {
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { "ABCDE" },
            secretSource = { "" },
        )
        assertNull(provider.read())
    }

    @Test
    fun `both blank returns null`() = runTest(UnconfinedTestDispatcher()) {
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { "" },
            secretSource = { "" },
        )
        assertNull(provider.read())
    }

    @Test
    fun `whitespace-only values are treated as blank`() = runTest(UnconfinedTestDispatcher()) {
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { "   " },
            secretSource = { "\t\n " },
        )
        assertNull(provider.read())
    }

    @Test
    fun `populated values produce credentials`() = runTest(UnconfinedTestDispatcher()) {
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { "PKABCDEF1234" },
            secretSource = { "sssssss" },
        )
        val creds = provider.read()
        assertNotNull(creds)
        assertEquals("PKABCDEF1234", creds!!.keyId)
        assertEquals("sssssss", creds.secret)
    }

    @Test
    fun `values are trimmed before use`() = runTest(UnconfinedTestDispatcher()) {
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { "  PKABCDEF1234  " },
            secretSource = { "\tsssssss\n" },
        )
        val creds = provider.read()
        assertNotNull(creds)
        assertEquals("PKABCDEF1234", creds!!.keyId)
        assertEquals("sssssss", creds.secret)
    }

    @Test
    fun `toString redacts the secret entirely and truncates the key`() {
        val creds = AlpacaCredentials("PKABCDEF1234", "topsecretvalue")
        val text = creds.toString()
        // Secret value never appears in text form.
        assertFalse(text.contains("topsecretvalue"))
        assertFalse(text.contains("secretvalue"))
        // Only a short prefix of the key id survives.
        assertTrue(text.contains("PKAB"))
        assertFalse(text.contains("PKABCDEF1234"))
        // The redaction marker is present.
        assertTrue(text.contains("***"))
    }

    @Test
    fun `read is repeatable - sources are called every time`() = runTest(UnconfinedTestDispatcher()) {
        var keyCalls = 0
        var secretCalls = 0
        val provider = BuildConfigAlpacaCredentialsProvider(
            keyIdSource = { keyCalls += 1; "PKABCDEF1234" },
            secretSource = { secretCalls += 1; "sssssss" },
        )

        provider.read()
        provider.read()
        provider.read()

        // Each read consults the sources fresh — important so a future
        // Keystore-backed implementation can pick up rotated credentials
        // without restarting the process.
        assertEquals(3, keyCalls)
        assertEquals(3, secretCalls)
    }
}
