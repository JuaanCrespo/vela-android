package com.vela.android.lab.state

import com.vela.android.lab.core.OperationMode
import com.vela.android.lab.core.REAL_MODE_LOCK_REASON
import com.vela.android.lab.core.modeChangeSuccessMessage

/**
 * Port of `app/services/mode_guard.py`. Pure function. No side effects.
 *
 * The `REAL` lock is an architectural invariant for the Android lab and
 * cannot be bypassed by a code-only change. Even with `realModeLocked`
 * set to false at compile time, the live trading code paths in
 * downstream modules are unreachable.
 */
data class ModeTransitionValidation(
    val allowed: Boolean,
    val message: String,
)

fun validateModeTransition(
    currentMode: OperationMode,
    requestedMode: OperationMode,
    realModeLocked: Boolean,
): ModeTransitionValidation {
    if (requestedMode == OperationMode.REAL && realModeLocked) {
        return ModeTransitionValidation(
            allowed = false,
            message = REAL_MODE_LOCK_REASON,
        )
    }
    return ModeTransitionValidation(
        allowed = true,
        message = modeChangeSuccessMessage(requestedMode),
    )
}
