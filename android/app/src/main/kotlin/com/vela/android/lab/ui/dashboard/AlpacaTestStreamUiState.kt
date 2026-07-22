package com.vela.android.lab.ui.dashboard

/**
 * Debug-only UI state for the "Alpaca Paper Credentials" card on
 * the offline dashboard. Pure data; no Android imports.
 *
 * Phase 2.c.1 contract:
 *  - [keyIdInput] and [secretInput] are the user's *current*
 *    text-field contents. They are cleared by the ViewModel on
 *    save and on clear so the secret never lingers in state once
 *    the user finishes interacting.
 *  - [credentialsConfigured] reflects whether the secure store
 *    holds credentials (NOT whether the BuildConfig developer
 *    fallback has them). Clearing the in-app credentials always
 *    flips this to `false`.
 *  - [connectionState] / `lastBar*` / [barsReceived] are the
 *    last test-stream telemetry. Read-only.
 *  - [lastSaveStatus] / [lastClearStatus] are short transient
 *    messages ("Saved", "Cleared", or an error string with no
 *    credential content) to give the user UI feedback.
 *
 * Phase 2.d additions (read-only pipeline telemetry):
 *  - [pipelineReceived] / [pipelinePersisted] track the bridge's
 *    count of upstream updates received vs. coordinator-accepted.
 *  - [lastPipelineSignalState] is the `state.value` of the last
 *    `SymbolSignal` the coordinator emitted for a FAKEPACA bar
 *    (e.g. `NEUTRAL`).
 *  - [lastPipelineError] is the bridge's last forwarding error,
 *    if any. Contains no credential.
 *
 *  - **No field on this state carries a credential value after
 *    save/clear completes.**
 */
data class AlpacaTestStreamUiState(
    val keyIdInput: String,
    val secretInput: String,
    val credentialsConfigured: Boolean,
    val connectionState: String,
    val lastBarSymbol: String?,
    val lastBarClose: Double?,
    val lastBarTimestamp: String?,
    val barsReceived: Int,
    val errorMessage: String?,
    val lastSaveStatus: String?,
    val lastClearStatus: String?,
    val pipelineReceived: Int,
    val pipelinePersisted: Int,
    val lastPipelineSignalState: String?,
    val lastPipelineError: String?,
    // Phase 2.f read-only diagnostics
    val healthPhase: String,
    val lastConnectedAtEpochMillis: Long?,
    val lastDisconnectedAtEpochMillis: Long?,
    val lastMessageAtEpochMillis: Long?,
    val reconnectAttempts: Int,
    val lastErrorType: String?,
) {
    companion object {
        val Initial: AlpacaTestStreamUiState = AlpacaTestStreamUiState(
            keyIdInput = "",
            secretInput = "",
            credentialsConfigured = false,
            connectionState = "DISCONNECTED",
            lastBarSymbol = null,
            lastBarClose = null,
            lastBarTimestamp = null,
            barsReceived = 0,
            errorMessage = null,
            lastSaveStatus = null,
            lastClearStatus = null,
            pipelineReceived = 0,
            pipelinePersisted = 0,
            lastPipelineSignalState = null,
            lastPipelineError = null,
            healthPhase = "DISCONNECTED",
            lastConnectedAtEpochMillis = null,
            lastDisconnectedAtEpochMillis = null,
            lastMessageAtEpochMillis = null,
            reconnectAttempts = 0,
            lastErrorType = null,
        )
    }
}
