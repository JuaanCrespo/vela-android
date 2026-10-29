package com.vela.android.lab.core.marketdataset.domain

import java.util.Locale
import java.util.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ExactMarketDecimalTest {
    @Test fun `permanent sub-double precision regression`() {
        val a = decimal("0.100000000000000001"); val b = decimal("0.100000000000000002")
        assertNotEquals(a, b); assertTrue(a < b)
        assertEquals("0.100000000000000001", a.toString()); assertEquals(2, setOf(a, b).size)
    }
    @Test fun `scale independent canonical equality has matching hash`() {
        val a = decimal("1.0"); val b = decimal("1.00")
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode()); assertEquals(0, a.compareTo(b)); assertEquals("1", a.toString())
    }
    @Test fun `raw decimal retains scale and spelling without changing exact value`() {
        val a = RawMarketDecimal("+001.00"); val b = RawMarketDecimal("1.0")
        assertNotEquals(a, b); assertEquals(a.exact, b.exact); assertEquals("+001.00", a.text)
    }
    @Test fun `signed zero canonicalizes to zero`() {
        listOf("0", "-0", "+00.000").forEach { assertEquals(ExactMarketDecimal.ZERO, decimal(it)); assertEquals("0", decimal(it).toString()) }
    }
    @Test fun `negative values and deterministic order`() {
        assertTrue(decimal("-2.001") < decimal("-2")); assertTrue(decimal("-2") < ExactMarketDecimal.ZERO)
        assertEquals("-1000", decimal("-1000.000").toString())
    }
    @ParameterizedTest @ValueSource(strings = ["NaN", "Infinity", "-Infinity", "1,2", "1_000", "", " ", "1 ", " 1", ".1", "1.", "1e2", "1E+2", "1e", "--1", "0x10", "１２"])
    fun `invalid decimal grammar has stable error`(text: String) { invalid(DatasetValidationError.INVALID_DECIMAL) { decimal(text) } }
    @Test fun `resource limit rejects rather than rounds`() {
        assertEquals("1".repeat(512), decimal("1".repeat(512)).toString())
        invalid(DatasetValidationError.INVALID_DECIMAL) { decimal("1".repeat(513)) }
    }
    @Test fun `normalization and round trip fixed-seed property`() {
        val random = Random(302)
        repeat(256) {
            val integer = random.nextInt(100000) + 1
            val fraction = random.nextInt(999) + 1
            val a = decimal("$integer.$fraction")
            assertEquals(a, decimal("+00$integer.${fraction}00")); assertEquals(a, decimal(a.toString()))
            assertEquals(a.hashCode(), decimal(a.toString()).hashCode())
        }
    }
    @Test fun `decimal behavior independent from locale`() {
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("ar-EG")); assertEquals("1234.56", decimal("1234.560").toString()) }
        finally { Locale.setDefault(original) }
    }
}
