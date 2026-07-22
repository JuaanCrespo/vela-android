package com.vela.android.lab.ui.settings

import com.vela.android.lab.ui.navigation.VelaDestination
import java.util.Locale

enum class VelaUiDensity(val storageValue: String) {
    COMPACT("compact"),
    COMFORTABLE("comfortable"),
    ;

    companion object {
        fun fromStorage(value: String?): VelaUiDensity =
            entries.firstOrNull { it.storageValue == value } ?: COMFORTABLE
    }
}

enum class VelaCandleCount(val count: Int) {
    THIRTY(30),
    FIFTY(50),
    ONE_HUNDRED(100),
    ;

    companion object {
        val allowedCounts: Set<Int> = entries.mapTo(linkedSetOf()) { it.count }

        fun fromCount(value: Int?): VelaCandleCount =
            entries.firstOrNull { it.count == value } ?: FIFTY
    }
}

enum class VelaTimeFormat(val storageValue: String) {
    LOCAL("local"),
    UTC("utc"),
    ;

    companion object {
        fun fromStorage(value: String?): VelaTimeFormat =
            entries.firstOrNull { it.storageValue == value } ?: LOCAL
    }
}

data class VelaVisualPreferences(
    val density: VelaUiDensity = VelaUiDensity.COMFORTABLE,
    val candleCount: VelaCandleCount = VelaCandleCount.FIFTY,
    val defaultSymbol: String = DEFAULT_VISUAL_SYMBOL,
    val advancedDiagnostics: Boolean = false,
    val rememberLastSection: Boolean = true,
    val lastDestination: VelaDestination = VelaDestination.HOME,
    val timeFormat: VelaTimeFormat = VelaTimeFormat.LOCAL,
) {
    fun defaultSymbolFor(availableSymbols: Collection<String>): String =
        VelaPreferencePolicy.resolveDefaultSymbol(
            requestedSymbol = defaultSymbol,
            availableSymbols = availableSymbols,
        )
}

data class VelaPreferencesState(
    val preferences: VelaVisualPreferences = VelaVisualPreferences(),
    val isLoaded: Boolean = false,
)

const val DEFAULT_VISUAL_SYMBOL: String = "SPY"

/**
 * Pure validation shared by persistence and the UI.
 *
 * Symbols may only resolve to an item already present in the supplied
 * watchlist. No market-data request is triggered here.
 */
object VelaPreferencePolicy {
    private const val MAX_SYMBOL_LENGTH = 16

    fun normalizedAvailableSymbols(symbols: Collection<String>): List<String> =
        symbols
            .asSequence()
            .map(::normalizeSymbol)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

    fun resolveDefaultSymbol(
        requestedSymbol: String,
        availableSymbols: Collection<String>,
    ): String {
        val allowed = normalizedAvailableSymbols(availableSymbols)
        val requested = normalizeSymbol(requestedSymbol)
        return when {
            requested in allowed -> requested
            DEFAULT_VISUAL_SYMBOL in allowed -> DEFAULT_VISUAL_SYMBOL
            allowed.isNotEmpty() -> allowed.first()
            else -> DEFAULT_VISUAL_SYMBOL
        }
    }

    fun normalizeStoredSymbol(symbol: String?): String =
        normalizeSymbol(symbol.orEmpty()).ifEmpty { DEFAULT_VISUAL_SYMBOL }

    fun restoreDestination(
        storedRoute: String?,
        rememberLastSection: Boolean,
    ): VelaDestination {
        if (!rememberLastSection) return VelaDestination.HOME
        return VelaDestination.fromRoute(storedRoute) ?: VelaDestination.HOME
    }

    private fun normalizeSymbol(symbol: String): String =
        symbol
            .trim()
            .uppercase(Locale.ROOT)
            .filter { character ->
                character.isLetterOrDigit() || character == '.' || character == '-'
            }
            .take(MAX_SYMBOL_LENGTH)
}
