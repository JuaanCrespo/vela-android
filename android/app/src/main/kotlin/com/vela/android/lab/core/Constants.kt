package com.vela.android.lab.core

/**
 * Direct port of constants from `app/constants.py`. Values are preserved
 * verbatim because they appear in user-visible messages and existing
 * tests assert on them.
 */
const val APP_TITLE: String = "VELA"
const val WINDOW_TITLE_SUFFIX: String = "Android Lab"
const val REAL_MODE_LOCK_REASON: String = "REAL mode is locked by default for safety."
const val MODE_CHANGE_SUCCESS_TEMPLATE: String = "Mode changed to %s"

fun modeChangeSuccessMessage(mode: OperationMode): String =
    MODE_CHANGE_SUCCESS_TEMPLATE.format(mode.value)

val MODE_LABELS: Map<OperationMode, String> = mapOf(
    OperationMode.READ_ONLY to "Read Only",
    OperationMode.SIMULATED to "Simulated",
    OperationMode.REAL to "Real",
)
