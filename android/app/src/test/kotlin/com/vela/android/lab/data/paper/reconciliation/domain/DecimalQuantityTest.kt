package com.vela.android.lab.data.paper.reconciliation.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DecimalQuantityTest {
    @ParameterizedTest
    @ValueSource(strings = ["1", "1.0", "1.000", "1e0", "+01.000"])
    fun valueEqualityAndHash(text: String) {
        val value = DecimalQuantity.parse(text)
        assertEquals(DecimalQuantity.parse("1"), value)
        assertEquals(DecimalQuantity.parse("1").hashCode(), value.hashCode())
        assertEquals("1", value.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0.000", "-0.0", "+0", "0e-9"])
    fun normalizedZero(text: String) {
        assertEquals(DecimalQuantity.ZERO, DecimalQuantity.parse(text))
        assertEquals("0", DecimalQuantity.parse(text).toString())
    }

    @Test fun arithmeticIsExactAndSigned() {
        assertEquals(DecimalQuantity.parse("0.3"), DecimalQuantity.parse("0.1") + DecimalQuantity.parse("0.2"))
        assertEquals("0.0000000000000000003", (DecimalQuantity.parse("1e-19") + DecimalQuantity.parse("2e-19")).toString())
        assertEquals(DecimalQuantity.parse("-1"), DecimalQuantity.parse("1") - DecimalQuantity.parse("2"))
        assertEquals(DecimalQuantity.parse("1"), -DecimalQuantity.parse("-1.000"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " 1", "1 ", "NaN", "Infinity", "-Infinity", "abc", "1,2", "1_0", "1e9999", ".5", "1."])
    fun rejectsInvalidText(text: String) {
        assertThrows(IllegalArgumentException::class.java) { DecimalQuantity.parse(text) }
        assertEquals(QuantityEvidence.INVALID, QuantityEvidence.decimal(text))
    }

    @Test fun provenanceDoesNotRecoverLostPrecision() {
        val legacy = QuantityEvidence.legacy(0.1)
        assertEquals(DecimalProvenance.LEGACY_DOUBLE_DERIVED, legacy.provenance)
        assertEquals(DecimalQuantity.parse("0.1"), legacy.quantity)
        assertFalse(legacy.exact)
        assertEquals(QuantityEvidence.UNKNOWN, QuantityEvidence.legacy(null))
        assertEquals(QuantityEvidence.UNKNOWN, QuantityEvidence.decimal(null))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertEquals(QuantityEvidence.INVALID, QuantityEvidence.legacy(it))
        }
        assertEquals(DecimalProvenance.LEGACY_DOUBLE_DERIVED, sumEvidence(listOf(legacy, QuantityEvidence.decimal("0.2"))).provenance)
        assertThrows(IllegalArgumentException::class.java) { QuantityEvidence(null, DecimalProvenance.EXACT_DECIMAL) }
    }
}
