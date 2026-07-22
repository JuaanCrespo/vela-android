package com.vela.android.lab.core

/**
 * Direct port of `app/constants.py::OperationMode` from the Windows VELA
 * project. The string values are kept identical so journal entries, logs,
 * and any future shared serialization remain comparable across platforms.
 */
enum class OperationMode(val value: String) {
    READ_ONLY("READ_ONLY"),
    SIMULATED("SIMULATED"),
    REAL("REAL"),
}
