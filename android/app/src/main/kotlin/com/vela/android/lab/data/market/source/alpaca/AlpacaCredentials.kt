package com.vela.android.lab.data.market.source.alpaca

/**
 * Value type for Alpaca API credentials. **Never logged in plain
 * text** — `toString()` redacts the secret and exposes only a short
 * prefix of the key id. Equality intentionally is not derived from
 * the secret to keep redaction-safe equality semantics.
 *
 * The fields here are **read-only credentials** consumed by the
 * Market Data WebSocket auth message. They are never used for
 * order submission, account mutation, or any trading action.
 */
class AlpacaCredentials(
    val keyId: String,
    val secret: String,
) {
    init {
        require(keyId.isNotBlank()) { "AlpacaCredentials.keyId must not be blank" }
        require(secret.isNotBlank()) { "AlpacaCredentials.secret must not be blank" }
    }

    override fun toString(): String {
        val keyHint = if (keyId.length <= 4) "***" else "${keyId.take(4)}…"
        return "AlpacaCredentials(keyId=$keyHint, secret=***)"
    }

    override fun equals(other: Any?): Boolean =
        other is AlpacaCredentials && other.keyId == keyId && other.secret == secret

    override fun hashCode(): Int = keyId.hashCode() * 31 + secret.hashCode()
}
