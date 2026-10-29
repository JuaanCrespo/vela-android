package com.vela.android.lab.core.marketdataset.domain

import java.time.Instant

/** UTC components over Instant's structural range, without consulting any clock. */
data class MarketTimestamp(val epochSecond: Long, val nanoAdjustment: Int) : Comparable<MarketTimestamp> {
    init {
        validate(epochSecond in Instant.MIN.epochSecond..Instant.MAX.epochSecond && nanoAdjustment in 0..999_999_999,
            DatasetValidationError.INVALID_TIMESTAMP)
    }
    override fun compareTo(other: MarketTimestamp): Int =
        epochSecond.compareTo(other.epochSecond).takeIf { it != 0 } ?: nanoAdjustment.compareTo(other.nanoAdjustment)
    fun canonicalText(): String = record("utc-v1", epochSecond.toString(), nanoAdjustment.toString())
    fun plusSeconds(seconds: Long): MarketTimestamp {
        val next = try { Math.addExact(epochSecond, seconds) } catch (_: ArithmeticException) {
            throw DatasetValidationException(DatasetValidationError.TIME_OVERFLOW)
        }
        validate(next in Instant.MIN.epochSecond..Instant.MAX.epochSecond, DatasetValidationError.TIME_OVERFLOW)
        return MarketTimestamp(next, nanoAdjustment)
    }
}

/** Local monotonic receipt order within a session; zero is the first valid sequence. No generator. */
data class IngestSequence(val value: Long) : Comparable<IngestSequence> {
    init { validate(value >= 0, DatasetValidationError.INVALID_SEQUENCE) }
    override fun compareTo(other: IngestSequence): Int = value.compareTo(other.value)
}

data class IngestPosition(val session: IngestSessionId, val sequence: IngestSequence) {
    fun canonicalText(): String = record("ingest-v1", session.value, sequence.value.toString())
}

data class MarketTimeRange(val startInclusive: MarketTimestamp, val endExclusive: MarketTimestamp) {
    init { validate(endExclusive > startInclusive, DatasetValidationError.INVALID_INTERVAL) }
    operator fun contains(time: MarketTimestamp): Boolean = time >= startInclusive && time < endExclusive
    fun canonicalText(): String = record("range-v1", startInclusive.canonicalText(), endExclusive.canonicalText())
}

/** Fixed UTC seconds, epoch-aligned. A day means 86400 seconds, not a market session/calendar day. */
data class BarInterval(val seconds: Long) {
    init { validate(seconds > 0, DatasetValidationError.INVALID_INTERVAL) }
    fun isBoundary(time: MarketTimestamp): Boolean = time.nanoAdjustment == 0 && Math.floorMod(time.epochSecond, seconds) == 0L
    companion object { val ONE_MINUTE = BarInterval(60) }
}

object MarketAvailability {
    fun isVisible(availableAt: MarketTimestamp, cutoff: MarketTimestamp): Boolean = availableAt <= cutoff
}
