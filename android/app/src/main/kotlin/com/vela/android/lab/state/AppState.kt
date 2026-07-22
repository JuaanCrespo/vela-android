package com.vela.android.lab.state

import com.vela.android.lab.core.OperationMode

/**
 * Port of `app/services/app_state.py::AppState`.
 *
 * Phase 1 keeps the class simple and synchronous to match the Python
 * dataclass surface. The migration map (§2.1) wraps this in a coroutine
 * `StateFlow` later; that wrapper is **not** part of Phase 1.
 *
 * Safety invariant: `realModeLocked` defaults to `true`. Any future
 * code that flips it must go through `unlockRealMode()` explicitly.
 */
class AppState(
    mode: OperationMode = OperationMode.READ_ONLY,
    streamRunning: Boolean = false,
    realModeLocked: Boolean = true,
    lastError: String? = null,
    selectedSymbols: List<String> = emptyList(),
) {
    var mode: OperationMode = mode
        private set

    var streamRunning: Boolean = streamRunning
        private set

    var realModeLocked: Boolean = realModeLocked
        private set

    var lastError: String? = lastError
        private set

    var selectedSymbols: List<String> = selectedSymbols.toList()
        private set

    fun setMode(mode: OperationMode): Pair<Boolean, String> {
        val validation = validateModeTransition(
            currentMode = this.mode,
            requestedMode = mode,
            realModeLocked = realModeLocked,
        )
        if (!validation.allowed) {
            lastError = validation.message
            return false to validation.message
        }
        this.mode = mode
        lastError = null
        return true to validation.message
    }

    fun setSelectedSymbols(symbols: Iterable<String>) {
        selectedSymbols = symbols.toList()
    }

    fun setStreamState(running: Boolean, symbols: Iterable<String>) {
        streamRunning = running
        selectedSymbols = symbols.toList()
    }

    fun startStream() {
        setStreamState(running = true, symbols = selectedSymbols)
    }

    fun stopStream() {
        setStreamState(running = false, symbols = emptyList())
    }

    fun unlockRealMode() {
        realModeLocked = false
    }

    fun lockRealMode() {
        realModeLocked = true
        if (mode == OperationMode.REAL) {
            mode = OperationMode.READ_ONLY
        }
    }
}
