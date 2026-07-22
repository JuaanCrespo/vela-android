package com.vela.android.lab.ui.settings

import com.vela.android.lab.ui.navigation.VelaDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VelaPreferencesTest {
    @Test
    fun `defaults are comfortable fifty SPY local and safe`() {
        val preferences = VelaVisualPreferences()

        assertEquals(VelaUiDensity.COMFORTABLE, preferences.density)
        assertEquals(VelaCandleCount.FIFTY, preferences.candleCount)
        assertEquals(50, preferences.candleCount.count)
        assertEquals("SPY", preferences.defaultSymbol)
        assertFalse(preferences.advancedDiagnostics)
        assertTrue(preferences.rememberLastSection)
        assertEquals(VelaDestination.HOME, preferences.lastDestination)
        assertEquals(VelaTimeFormat.LOCAL, preferences.timeFormat)
    }

    @Test
    fun `candle count accepts only thirty fifty or one hundred`() {
        assertEquals(setOf(30, 50, 100), VelaCandleCount.allowedCounts)
        assertEquals(VelaCandleCount.THIRTY, VelaCandleCount.fromCount(30))
        assertEquals(VelaCandleCount.FIFTY, VelaCandleCount.fromCount(999))
        assertEquals(VelaCandleCount.FIFTY, VelaCandleCount.fromCount(null))
        assertEquals(VelaCandleCount.ONE_HUNDRED, VelaCandleCount.fromCount(100))
    }

    @Test
    fun `unknown enum storage values fall back to documented defaults`() {
        assertEquals(VelaUiDensity.COMFORTABLE, VelaUiDensity.fromStorage("dense"))
        assertEquals(VelaTimeFormat.LOCAL, VelaTimeFormat.fromStorage("exchange"))
    }

    @Test
    fun `default symbol resolves only against the supplied watchlist`() {
        val watchlist = listOf(" spy ", "qqq", "AAPL")

        assertEquals(
            "QQQ",
            VelaPreferencePolicy.resolveDefaultSymbol(" qqq ", watchlist),
        )
        assertEquals(
            "SPY",
            VelaPreferencePolicy.resolveDefaultSymbol("not-listed", watchlist),
        )
        assertEquals(
            "AAPL",
            VelaPreferencePolicy.resolveDefaultSymbol("missing", listOf("AAPL")),
        )
    }

    @Test
    fun `stored symbol normalization never produces an empty value`() {
        assertEquals("BRK.B", VelaPreferencePolicy.normalizeStoredSymbol(" brk.b "))
        assertEquals("SPY", VelaPreferencePolicy.normalizeStoredSymbol("!!!"))
        assertEquals("SPY", VelaPreferencePolicy.normalizeStoredSymbol(null))
    }

    @Test
    fun `last destination restore rejects submit and obeys remember setting`() {
        assertEquals(
            VelaDestination.CANDLES,
            VelaPreferencePolicy.restoreDestination("velas", rememberLastSection = true),
        )
        assertEquals(
            VelaDestination.HOME,
            VelaPreferencePolicy.restoreDestination("submit", rememberLastSection = true),
        )
        assertEquals(
            VelaDestination.HOME,
            VelaPreferencePolicy.restoreDestination("paper", rememberLastSection = false),
        )
    }

    @Test
    fun `DataStore schema contains visual keys only`() {
        assertEquals(
            setOf(
                "ui_density",
                "candle_count",
                "default_symbol",
                "advanced_diagnostics",
                "remember_last_section",
                "last_destination",
                "time_format",
            ),
            VelaVisualPreferenceSchema.keyNames,
        )
        val forbiddenFragments = listOf(
            "key",
            "secret",
            "credential",
            "account",
            "token",
            "confirmation",
            "endpoint",
            "order",
            "submit",
            "gate",
        )
        assertTrue(
            VelaVisualPreferenceSchema.keyNames.none { key ->
                forbiddenFragments.any { fragment -> key.contains(fragment, ignoreCase = true) }
            },
        )
    }

    @Test
    fun `settings contract exposes no mutable trading or safety control`() {
        assertEquals(
            setOf(
                "density",
                "candle_count",
                "default_symbol",
                "advanced_diagnostics",
                "remember_last_section",
                "time_format",
            ),
            VelaSettingsContract.editablePreferenceIds,
        )
        assertTrue(
            VelaSettingsContract.editablePreferenceIds.intersect(
                VelaSettingsContract.readOnlySafetyIds,
            ).isEmpty(),
        )
        assertTrue(
            setOf(
                "real_locked",
                "live_forbidden",
                "auto_paper_disabled",
                "manual_submit_compiled",
            ).all(VelaSettingsContract.readOnlySafetyIds::contains),
        )
    }
}
