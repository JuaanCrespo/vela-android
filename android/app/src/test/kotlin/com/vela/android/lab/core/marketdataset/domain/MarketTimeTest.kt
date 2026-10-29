package com.vela.android.lab.core.marketdataset.domain

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarketTimeTest {
    @Test fun `one nanosecond never collapses`() {
        val a = time(10, 123456789); val b = time(10, 123456790)
        assertNotEquals(a, b); assertTrue(a < b); assertNotEquals(a.canonicalText(), b.canonicalText())
    }
    @Test fun `ordering at second rollover and before epoch`() {
        assertTrue(time(-1, 999999999) < time(0)); assertTrue(time(0, 999999999) < time(1))
    }
    @Test fun `timestamp boundaries invalid nanos and second overflow`() {
        invalid(DatasetValidationError.INVALID_TIMESTAMP) { time(1, -1) }
        invalid(DatasetValidationError.INVALID_TIMESTAMP) { time(1, 1000000000) }
        invalid(DatasetValidationError.INVALID_TIMESTAMP) { time(Long.MAX_VALUE) }
        invalid(DatasetValidationError.INVALID_TIMESTAMP) { time(Long.MIN_VALUE) }
        invalid(DatasetValidationError.TIME_OVERFLOW) { time(Instant.MAX.epochSecond).plusSeconds(1) }
        invalid(DatasetValidationError.TIME_OVERFLOW) { time(Instant.MIN.epochSecond).plusSeconds(-1) }
        invalid(DatasetValidationError.TIME_OVERFLOW) { time(1).plusSeconds(Long.MAX_VALUE) }
    }
    @Test fun `historical and distant future times are structural values`() {
        assertEquals(-31557014167219200L, time(Instant.MIN.epochSecond).epochSecond)
        assertEquals(Instant.MAX.epochSecond, time(Instant.MAX.epochSecond, 999999999).epochSecond)
        assertEquals(5, time(100000000000L, 5).plusSeconds(60).nanoAdjustment)
    }
    @Test fun `ordering property across nanos and seconds`() {
        val values = (0..100).map { time((it / 7 - 8).toLong(), (it % 7) * 100000001) }
        assertEquals(values, values.reversed().sorted())
        values.zipWithNext().forEach { (a, b) -> assertTrue(a < b); assertNotEquals(a, b) }
    }
    @Test fun `timezone and locale cannot change canonical evidence`() {
        val timezone = TimeZone.getDefault(); val locale = Locale.getDefault()
        val expected = event().canonicalEnvelopeText(); val digest = bar().contentFingerprint
        try {
            for (zone in listOf("America/Argentina/Buenos_Aires", "Asia/Tokyo", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone)); Locale.setDefault(Locale.forLanguageTag("tr-TR"))
                assertEquals(expected, event().canonicalEnvelopeText()); assertEquals(digest, bar().contentFingerprint)
            }
        } finally { TimeZone.setDefault(timezone); Locale.setDefault(locale) }
    }
    @Test fun `sequence is nonnegative and independent from provider time`() {
        assertTrue(IngestSequence(0) < IngestSequence(Long.MAX_VALUE))
        invalid(DatasetValidationError.INVALID_SEQUENCE) { IngestSequence(-1) }
        assertNotEquals(position(0, "A"), position(0, "B"))
    }
    @Test fun `range is half open and rejects empty or reversed ranges`() {
        val range = MarketTimeRange(time(0), time(60))
        assertTrue(time(0) in range); assertFalse(time(60) in range); assertFalse(time(-1) in range)
        invalid(DatasetValidationError.INVALID_INTERVAL) { MarketTimeRange(time(1), time(1)) }
        invalid(DatasetValidationError.INVALID_INTERVAL) { MarketTimeRange(time(2), time(1)) }
    }
    @Test fun `interval boundaries are epoch aligned including negative time`() {
        for (seconds in listOf(1L, 60L, 300L, 900L, 3600L, 86400L)) {
            val interval = BarInterval(seconds)
            assertTrue(interval.isBoundary(time(-seconds))); assertTrue(interval.isBoundary(time(seconds)))
            assertFalse(interval.isBoundary(time(seconds, 1)))
        }
        invalid(DatasetValidationError.INVALID_INTERVAL) { BarInterval(0) }
        invalid(DatasetValidationError.INVALID_INTERVAL) { BarInterval(-60) }
    }
}
