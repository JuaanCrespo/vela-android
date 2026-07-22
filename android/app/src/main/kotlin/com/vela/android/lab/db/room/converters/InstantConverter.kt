package com.vela.android.lab.db.room.converters

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room type converter that stores [Instant] values as epoch
 * milliseconds. Millisecond precision is sufficient for VELA's
 * one-minute bar pipeline; if sub-millisecond precision becomes
 * load-bearing later, this can be replaced with a
 * `(epochSeconds: Long, nanos: Int)` pair stored across two columns.
 */
class InstantConverter {

    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
