package com.vela.android.lab.data.paper.reconciliation.domain

import java.math.BigDecimal

/** Value equality, exact arithmetic and canonical text; never a floating-point calculation. */
class DecimalQuantity private constructor(private val value: BigDecimal) : Comparable<DecimalQuantity> {
    operator fun plus(other: DecimalQuantity): DecimalQuantity = normalized(value.add(other.value))
    operator fun minus(other: DecimalQuantity): DecimalQuantity = normalized(value.subtract(other.value))
    operator fun unaryMinus(): DecimalQuantity = normalized(value.negate())
    override fun compareTo(other: DecimalQuantity): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is DecimalQuantity && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value.toPlainString()

    companion object {
        val ZERO: DecimalQuantity = DecimalQuantity(BigDecimal.ZERO)
        // Resource limits reject oversized inputs; they never round or change accepted values.
        private val syntax = Regex("^[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,4})?$")
        fun parse(text: String): DecimalQuantity {
            require(text.length in 1..512 && syntax.matches(text)) { "Invalid decimal quantity." }
            val decimal = BigDecimal(text)
            require(decimal.scale() in -1024..1024) { "Decimal scale exceeds domain limits." }
            return normalized(decimal)
        }

        private fun normalized(value: BigDecimal): DecimalQuantity =
            if (value.signum() == 0) ZERO else DecimalQuantity(value.stripTrailingZeros())
    }
}

enum class DecimalProvenance { EXACT_DECIMAL, LEGACY_DOUBLE_DERIVED, UNKNOWN, INVALID }

data class QuantityEvidence(val quantity: DecimalQuantity?, val provenance: DecimalProvenance) {
    init {
        require((quantity != null) == (provenance in setOf(
            DecimalProvenance.EXACT_DECIMAL, DecimalProvenance.LEGACY_DOUBLE_DERIVED,
        ))) { "Quantity and provenance must agree." }
    }

    val exact: Boolean get() = provenance == DecimalProvenance.EXACT_DECIMAL

    companion object {
        val UNKNOWN = QuantityEvidence(null, DecimalProvenance.UNKNOWN)
        val INVALID = QuantityEvidence(null, DecimalProvenance.INVALID)
        val ZERO = QuantityEvidence(DecimalQuantity.ZERO, DecimalProvenance.EXACT_DECIMAL)
        fun decimal(text: String?): QuantityEvidence = if (text == null) UNKNOWN else try {
            QuantityEvidence(DecimalQuantity.parse(text), DecimalProvenance.EXACT_DECIMAL)
        } catch (_: IllegalArgumentException) {
            INVALID
        }

        /** Explicit decimal rendering of legacy storage, NOT recovered source precision. */
        fun legacy(value: Double?): QuantityEvidence = when {
            value == null -> UNKNOWN
            !value.isFinite() -> INVALID
            else -> QuantityEvidence(DecimalQuantity.parse(value.toString()), DecimalProvenance.LEGACY_DOUBLE_DERIVED)
        }
    }
}

internal fun sumEvidence(values: List<QuantityEvidence>): QuantityEvidence {
    if (values.any { it.provenance == DecimalProvenance.INVALID }) return QuantityEvidence.INVALID
    if (values.any { it.quantity == null }) return QuantityEvidence.UNKNOWN
    return QuantityEvidence(
        values.fold(DecimalQuantity.ZERO) { sum, item -> sum + requireNotNull(item.quantity) },
        if (values.all { it.exact }) DecimalProvenance.EXACT_DECIMAL else DecimalProvenance.LEGACY_DOUBLE_DERIVED,
    )
}
