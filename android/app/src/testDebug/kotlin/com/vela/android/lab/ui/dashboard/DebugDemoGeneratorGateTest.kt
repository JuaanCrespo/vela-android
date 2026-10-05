package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Debug configuration only. This source set compiles into testDebugUnitTest, where BuildConfig.DEBUG is true.
 *
 * DEBUG_DEMO_GENERATOR_AVAILABLE: the developer demo capability stays. The rows it writes keep the legacy-table
 * semantics documented in DemoGeneratorGate.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugDemoGeneratorGateTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `DEBUG_DEMO_GENERATOR_AVAILABLE the debug build flag is open`() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(demoGeneratorsEnabledForThisBuild())
    }

    @Test
    fun `DEBUG_DEMO_CARD_AVAILABLE the build-configured state shows the Diagnostics demo card`() {
        val harness = OfflineDemoHarness(demoGeneratorsEnabled = demoGeneratorsEnabledForThisBuild())
        assertTrue(harness.viewModel.uiState.value.demoGeneratorsAvailable)
    }

    @Test
    fun `DEBUG_DEMO_GENERATOR_AVAILABLE the build-configured generators run and write both bars`() {
        val harness = OfflineDemoHarness(demoGeneratorsEnabled = demoGeneratorsEnabledForThisBuild())

        harness.viewModel.generateBtcUpdate()
        harness.viewModel.generateSpyUpdate()

        assertEquals(2, harness.marketDao.rows.size)
    }
}
