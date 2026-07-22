package com.vela.android.lab.data.paper.submit

import java.util.concurrent.atomic.AtomicBoolean

data class PaperManualExecutionFeatureSnapshot(
    val compileTimeEnabled: Boolean,
    val sessionArmed: Boolean,
    val emergencyDisabled: Boolean,
) {
    val enabled: Boolean
        get() = compileTimeEnabled && sessionArmed && !emergencyDisabled
}

/** Default-off, session-only gate with an irreversible process-local emergency stop. */
class PaperManualExecutionFeatureGate(
    val compileTimeEnabled: Boolean,
) {
    private val emergencyDisabled = AtomicBoolean(false)

    fun evaluate(sessionArmed: Boolean): PaperManualExecutionFeatureSnapshot =
        PaperManualExecutionFeatureSnapshot(
            compileTimeEnabled = compileTimeEnabled,
            sessionArmed = sessionArmed,
            emergencyDisabled = emergencyDisabled.get(),
        )

    fun activateEmergencyDisable() {
        emergencyDisabled.set(true)
    }
}

/** Recorded human approval required by the Phase 2.u package. */
object PaperManualSubmitApproval {
    const val APPROVED_PHASE: String =
        "Phase 2.v - Manual Paper submit implementation, Paper-only, one-shot, user-confirmed"
    const val RECORDED: Boolean = true
}
