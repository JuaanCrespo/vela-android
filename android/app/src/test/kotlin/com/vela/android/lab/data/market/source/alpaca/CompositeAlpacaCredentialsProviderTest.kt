@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompositeAlpacaCredentialsProviderTest {

    @Test
    fun `empty providers list is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompositeAlpacaCredentialsProvider(emptyList())
        }
    }

    @Test
    fun `returns first non-null result`() = runTest(UnconfinedTestDispatcher()) {
        val first = AlpacaCredentialsProvider { AlpacaCredentials("PKFIRST", "secret-1") }
        val second = AlpacaCredentialsProvider { AlpacaCredentials("PKSECOND", "secret-2") }
        val composite = CompositeAlpacaCredentialsProvider(first, second)
        val result = composite.read()
        assertNotNull(result)
        assertEquals("PKFIRST", result!!.keyId)
    }

    @Test
    fun `falls back when primary returns null`() = runTest(UnconfinedTestDispatcher()) {
        val primary = AlpacaCredentialsProvider { null }
        val fallback = AlpacaCredentialsProvider { AlpacaCredentials("PKFALLBACK", "secret") }
        val composite = CompositeAlpacaCredentialsProvider(primary, fallback)
        val result = composite.read()
        assertNotNull(result)
        assertEquals("PKFALLBACK", result!!.keyId)
    }

    @Test
    fun `returns null when all providers return null`() = runTest(UnconfinedTestDispatcher()) {
        val composite = CompositeAlpacaCredentialsProvider(
            AlpacaCredentialsProvider { null },
            AlpacaCredentialsProvider { null },
            AlpacaCredentialsProvider { null },
        )
        assertNull(composite.read())
    }

    @Test
    fun `stops querying once a value is found`() = runTest(UnconfinedTestDispatcher()) {
        var thirdQueried = false
        val composite = CompositeAlpacaCredentialsProvider(
            AlpacaCredentialsProvider { null },
            AlpacaCredentialsProvider { AlpacaCredentials("PKMIDDLE", "secret") },
            AlpacaCredentialsProvider {
                thirdQueried = true
                AlpacaCredentials("PKTHIRD", "secret")
            },
        )
        composite.read()
        assertEquals(false, thirdQueried)
    }

    @Test
    fun `single-provider composite delegates directly`() = runTest(UnconfinedTestDispatcher()) {
        val only = AlpacaCredentialsProvider { AlpacaCredentials("PKONLY", "secret") }
        val composite = CompositeAlpacaCredentialsProvider(only)
        val result = composite.read()
        assertEquals("PKONLY", result?.keyId)
    }
}
