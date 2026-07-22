package com.vela.android.lab.state

import com.vela.android.lab.core.OperationMode
import com.vela.android.lab.core.REAL_MODE_LOCK_REASON
import com.vela.android.lab.core.modeChangeSuccessMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Port of `tests/test_mode_guard.py`. Each Python `subTest` becomes a
 * JUnit 5 `DynamicTest` to preserve the same per-case failure granularity.
 */
class ModeGuardTest {

    @Test
    fun `rejects REAL mode when lock is active`() {
        val result = validateModeTransition(
            currentMode = OperationMode.READ_ONLY,
            requestedMode = OperationMode.REAL,
            realModeLocked = true,
        )

        assertFalse(result.allowed)
        assertEquals(REAL_MODE_LOCK_REASON, result.message)
    }

    @TestFactory
    fun `accepts READ_ONLY and SIMULATED transitions`(): List<DynamicTest> {
        val scenarios = listOf(
            OperationMode.READ_ONLY to OperationMode.READ_ONLY,
            OperationMode.READ_ONLY to OperationMode.SIMULATED,
            OperationMode.SIMULATED to OperationMode.READ_ONLY,
            OperationMode.SIMULATED to OperationMode.SIMULATED,
        )
        return scenarios.map { (current, requested) ->
            DynamicTest.dynamicTest("$current -> $requested") {
                val result = validateModeTransition(
                    currentMode = current,
                    requestedMode = requested,
                    realModeLocked = true,
                )
                assertTrue(result.allowed)
                assertEquals(modeChangeSuccessMessage(requested), result.message)
            }
        }
    }

    @Test
    fun `returns explicit status and message data`() {
        val result = validateModeTransition(
            currentMode = OperationMode.SIMULATED,
            requestedMode = OperationMode.SIMULATED,
            realModeLocked = false,
        )

        assertEquals(modeChangeSuccessMessage(OperationMode.SIMULATED), result.message)
    }
}
