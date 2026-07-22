package com.vela.android.lab.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Port of `normalize_market_symbol` and related helpers from
 * `app/data/alpaca_client.py`. The crypto base/quote sets are kept
 * verbatim so the canonical "BASE/QUOTE" form matches the Windows
 * project for every symbol VELA accepts today.
 *
 * No network. No Alpaca dependency. Pure string transformation.
 */

private val CRYPTO_QUOTES: List<String> = listOf("USD", "USDT", "USDC")

private val CRYPTO_BASES: Set<String> = setOf(
    "AAVE",
    "AVAX",
    "BCH",
    "BTC",
    "DOGE",
    "ETH",
    "LINK",
    "LTC",
    "MKR",
    "SHIB",
    "SOL",
    "UNI",
    "YFI",
)

private val WHITESPACE_REGEX = Regex("\\s+")

fun normalizeMarketSymbol(symbol: String?): String {
    val raw = symbol ?: return ""
    if (raw.isEmpty()) return ""

    val decoded = try {
        URLDecoder.decode(raw, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        raw
    }

    val normalized = decoded.trim().uppercase().replace(WHITESPACE_REGEX, "")
    if (normalized.isEmpty()) return ""

    if ('/' in normalized) {
        val slashIndex = normalized.indexOf('/')
        val base = normalized.substring(0, slashIndex)
        val quote = normalized.substring(slashIndex + 1)
        if (base.isNotEmpty() && quote.isNotEmpty() && '/' !in quote) {
            return "$base/$quote"
        }
        return normalized
    }

    for (quote in CRYPTO_QUOTES) {
        if (!normalized.endsWith(quote)) continue
        val base = normalized.substring(0, normalized.length - quote.length)
        if (base in CRYPTO_BASES) {
            return "$base/$quote"
        }
    }

    return normalized
}

fun isCryptoSymbol(symbol: String?): Boolean {
    val normalized = normalizeMarketSymbol(symbol)
    val slashIndex = normalized.indexOf('/')
    if (slashIndex <= 0 || slashIndex == normalized.lastIndex) return false
    val quote = normalized.substring(slashIndex + 1)
    return '/' !in quote
}

fun compactMarketSymbol(symbol: String?): String =
    normalizeMarketSymbol(symbol).replace("/", "")
