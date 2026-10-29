package com.vela.android.lab.core.marketdataset.domain

import java.math.BigDecimal

/** Plain finite decimals only. Numerical equality ignores scale; accepted values are never rounded. */
class ExactMarketDecimal private constructor(private val value: BigDecimal) : Comparable<ExactMarketDecimal> {
    override fun compareTo(other: ExactMarketDecimal): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is ExactMarketDecimal && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value.toPlainString()

    companion object {
        val ZERO = ExactMarketDecimal(BigDecimal.ZERO)
        private val syntax = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")
        fun parse(text: String): ExactMarketDecimal {
            validate(text.length in 1..512 && syntax.matches(text), DatasetValidationError.INVALID_DECIMAL)
            // Grammar and length make NumberFormatException/scale overflow unreachable.
            val parsed = BigDecimal(text)
            return if (parsed.signum() == 0) ZERO else ExactMarketDecimal(parsed.stripTrailingZeros())
        }
    }
}

/** Lexical evidence keeps trailing zeros/sign/leading zeros. Equality is deliberately lexical. */
data class RawMarketDecimal(val text: String) {
    val exact: ExactMarketDecimal = ExactMarketDecimal.parse(text)
}

internal fun positive(value: ExactMarketDecimal) = validate(value > ExactMarketDecimal.ZERO, DatasetValidationError.INVALID_PAYLOAD)
internal fun nonnegative(value: ExactMarketDecimal) = validate(value >= ExactMarketDecimal.ZERO, DatasetValidationError.INVALID_PAYLOAD)
