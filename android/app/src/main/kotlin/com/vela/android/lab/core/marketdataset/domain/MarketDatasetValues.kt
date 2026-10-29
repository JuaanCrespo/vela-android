package com.vela.android.lab.core.marketdataset.domain

import java.security.MessageDigest
import java.util.Collections

enum class DatasetValidationError {
    INVALID_IDENTIFIER, INVALID_SYMBOL, INVALID_DECIMAL, INVALID_TIMESTAMP, TIME_OVERFLOW,
    INVALID_SEQUENCE, INVALID_VERSION, INVALID_FINGERPRINT, INVALID_INTERVAL, INVALID_BAR,
    INVALID_PAYLOAD, SOURCE_MISMATCH, INVALID_AVAILABILITY, INVALID_REVISION, INVALID_LINEAGE, INVALID_SCOPE,
}

/** Stable error code; never includes input text, provider payloads or incidental parser messages. */
class DatasetValidationException(val error: DatasetValidationError) : IllegalArgumentException(error.name)

internal fun validate(condition: Boolean, error: DatasetValidationError) {
    if (!condition) throw DatasetValidationException(error)
}

// Reject unpaired UTF-16 surrogates: UTF-8 replacement would otherwise collapse distinct inputs.
internal fun validText(value: String): Boolean {
    if (value.isBlank() || value != value.trim() || value.any { it.isISOControl() }) return false
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        if (ch.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (ch.isLowSurrogate()) return false
    }
    return true
}

internal fun identifier(value: String) = validate(validText(value), DatasetValidationError.INVALID_IDENTIFIER)
internal fun version(value: Int) = validate(value > 0, DatasetValidationError.INVALID_VERSION)

data class DatasetId(val value: String) { init { identifier(value) } }
data class IngestSessionId(val value: String) { init { identifier(value) } }
data class ProviderEventId(val value: String) { init { identifier(value) } }
data class DatasetSchemaVersion(val value: Int) { init { version(value) } }
data class ParserVersion(val value: Int) { init { version(value) } }
data class CanonicalizationVersion(val value: Int) { init { version(value) } }
data class QualityPolicyVersion(val value: Int) { init { version(value) } }

/** Content identity only, not a signature or an assertion that the content is trustworthy. */
data class MarketFingerprint(val hex: String) : Comparable<MarketFingerprint> {
    init { validate(Regex("[0-9a-f]{64}").matches(hex), DatasetValidationError.INVALID_FINGERPRINT) }
    override fun compareTo(other: MarketFingerprint): Int = hex.compareTo(other.hex)

    companion object {
        internal fun ofCanonical(text: String): MarketFingerprint {
            val digits = "0123456789abcdef"
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return MarketFingerprint(buildString(64) {
                bytes.forEach { byte -> val n = byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
            })
        }
    }
}

/** V1 framing: UTF-8 byte length ':' value; null is '-1:'. Nested records are framed fields. */
internal fun record(tag: String, vararg fields: String?): String = buildString {
    for (field in listOf(tag) + fields) {
        if (field == null) append("-1:")
        else { append(field.toByteArray(Charsets.UTF_8).size); append(':'); append(field) }
    }
}

internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
