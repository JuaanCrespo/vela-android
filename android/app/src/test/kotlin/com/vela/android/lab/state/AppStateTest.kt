package com.vela.android.lab.state

import com.vela.android.lab.core.OperationMode
import com.vela.android.lab.core.REAL_MODE_LOCK_REASON
import com.vela.android.lab.core.modeChangeSuccessMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port of `tests/test_app_state.py`.
 */
class AppStateTest {

    @Test
    fun `setMode rejects REAL when lock is active`() {
        val state = AppState(mode = OperationMode.READ_ONLY)

        val (ok, message) = state.setMode(OperationMode.REAL)

        assertFalse(ok)
        assertEquals(REAL_MODE_LOCK_REASON, message)
        assertEquals(OperationMode.READ_ONLY, state.mode)
        assertEquals(REAL_MODE_LOCK_REASON, state.lastError)
    }

    @Test
    fun `setMode preserves current safe mode on blocked REAL request`() {
        val state = AppState(mode = OperationMode.SIMULATED)

        val (ok, message) = state.setMode(OperationMode.REAL)

        assertFalse(ok)
        assertEquals(REAL_MODE_LOCK_REASON, message)
        assertEquals(OperationMode.SIMULATED, state.mode)
        assertEquals(REAL_MODE_LOCK_REASON, state.lastError)
    }

    @Test
    fun `setMode accepts READ_ONLY and SIMULATED transitions`() {
        val state = AppState(
            mode = OperationMode.READ_ONLY,
            lastError = "previous error",
        )

        val (firstOk, firstMessage) = state.setMode(OperationMode.SIMULATED)
        assertTrue(firstOk)
        assertEquals(modeChangeSuccessMessage(OperationMode.SIMULATED), firstMessage)
        assertEquals(OperationMode.SIMULATED, state.mode)
        assertNull(state.lastError)

        val (secondOk, secondMessage) = state.setMode(OperationMode.READ_ONLY)
        assertTrue(secondOk)
        assertEquals(modeChangeSuccessMessage(OperationMode.READ_ONLY), secondMessage)
        assertEquals(OperationMode.READ_ONLY, state.mode)
        assertNull(state.lastError)
    }

    @Test
    fun `realModeLocked defaults to true`() {
        val state = AppState()
        assertTrue(state.realModeLocked)
    }

    @Test
    fun `lockRealMode forces REAL back to READ_ONLY`() {
        val state = AppState(
            mode = OperationMode.READ_ONLY,
            realModeLocked = false,
        )
        // Walk to REAL through the unlocked path purely to validate the
        // re-lock fallback; downstream code never takes this path.
        val (ok, _) = state.setMode(OperationMode.REAL)
        assertTrue(ok)
        assertEquals(OperationMode.REAL, state.mode)

        state.lockRealMode()

        assertTrue(state.realModeLocked)
        assertEquals(OperationMode.READ_ONLY, state.mode)
    }
}
