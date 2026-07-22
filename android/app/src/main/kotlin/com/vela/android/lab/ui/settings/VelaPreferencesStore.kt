package com.vela.android.lab.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vela.android.lab.ui.navigation.VelaDestination
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private const val VELA_VISUAL_PREFERENCES_STORE = "vela_visual_preferences"

private val Context.velaVisualPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = VELA_VISUAL_PREFERENCES_STORE,
)

/**
 * Dedicated DataStore for UX-only preferences.
 *
 * This schema intentionally has no credential, account, endpoint, gate, token,
 * order, confirmation, or execution keys.
 */
internal object VelaVisualPreferenceSchema {
    const val UI_DENSITY = "ui_density"
    const val CANDLE_COUNT = "candle_count"
    const val DEFAULT_SYMBOL = "default_symbol"
    const val ADVANCED_DIAGNOSTICS = "advanced_diagnostics"
    const val REMEMBER_LAST_SECTION = "remember_last_section"
    const val LAST_DESTINATION = "last_destination"
    const val TIME_FORMAT = "time_format"

    val keyNames: Set<String> = setOf(
        UI_DENSITY,
        CANDLE_COUNT,
        DEFAULT_SYMBOL,
        ADVANCED_DIAGNOSTICS,
        REMEMBER_LAST_SECTION,
        LAST_DESTINATION,
        TIME_FORMAT,
    )
}

private object Keys {
    val uiDensity = stringPreferencesKey(VelaVisualPreferenceSchema.UI_DENSITY)
    val candleCount = intPreferencesKey(VelaVisualPreferenceSchema.CANDLE_COUNT)
    val defaultSymbol = stringPreferencesKey(VelaVisualPreferenceSchema.DEFAULT_SYMBOL)
    val advancedDiagnostics = booleanPreferencesKey(
        VelaVisualPreferenceSchema.ADVANCED_DIAGNOSTICS,
    )
    val rememberLastSection = booleanPreferencesKey(
        VelaVisualPreferenceSchema.REMEMBER_LAST_SECTION,
    )
    val lastDestination = stringPreferencesKey(VelaVisualPreferenceSchema.LAST_DESTINATION)
    val timeFormat = stringPreferencesKey(VelaVisualPreferenceSchema.TIME_FORMAT)
}

class VelaPreferencesStore private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.velaVisualPreferencesDataStore)

    val state: Flow<VelaPreferencesState> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { values ->
                VelaPreferencesState(
                    preferences = values.toVisualPreferences(),
                    isLoaded = true,
                )
            }
            .onStart { emit(VelaPreferencesState()) }

    suspend fun setDensity(density: VelaUiDensity) {
        dataStore.edit { it[Keys.uiDensity] = density.storageValue }
    }

    suspend fun setCandleCount(candleCount: VelaCandleCount) {
        dataStore.edit { it[Keys.candleCount] = candleCount.count }
    }

    suspend fun setDefaultSymbol(
        symbol: String,
        availableSymbols: Collection<String>,
    ) {
        val resolved = VelaPreferencePolicy.resolveDefaultSymbol(
            requestedSymbol = symbol,
            availableSymbols = availableSymbols,
        )
        dataStore.edit { it[Keys.defaultSymbol] = resolved }
    }

    suspend fun setAdvancedDiagnostics(visible: Boolean) {
        dataStore.edit { it[Keys.advancedDiagnostics] = visible }
    }

    suspend fun setRememberLastSection(remember: Boolean) {
        dataStore.edit { values ->
            values[Keys.rememberLastSection] = remember
            if (!remember) {
                values[Keys.lastDestination] = VelaDestination.HOME.route
            }
        }
    }

    suspend fun setLastDestination(destination: VelaDestination) {
        dataStore.edit { it[Keys.lastDestination] = destination.route }
    }

    suspend fun setTimeFormat(timeFormat: VelaTimeFormat) {
        dataStore.edit { it[Keys.timeFormat] = timeFormat.storageValue }
    }
}

private fun Preferences.toVisualPreferences(): VelaVisualPreferences {
    val rememberLastSection = this[Keys.rememberLastSection] ?: true
    return VelaVisualPreferences(
        density = VelaUiDensity.fromStorage(this[Keys.uiDensity]),
        candleCount = VelaCandleCount.fromCount(this[Keys.candleCount]),
        defaultSymbol = VelaPreferencePolicy.normalizeStoredSymbol(this[Keys.defaultSymbol]),
        advancedDiagnostics = this[Keys.advancedDiagnostics] ?: false,
        rememberLastSection = rememberLastSection,
        lastDestination = VelaPreferencePolicy.restoreDestination(
            storedRoute = this[Keys.lastDestination],
            rememberLastSection = rememberLastSection,
        ),
        timeFormat = VelaTimeFormat.fromStorage(this[Keys.timeFormat]),
    )
}
