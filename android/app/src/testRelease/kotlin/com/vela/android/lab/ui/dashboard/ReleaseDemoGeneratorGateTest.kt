package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Release configuration only. This source set compiles into testReleaseUnitTest, where BuildConfig.DEBUG is false.
 *
 * RELEASE_DEMO_GENERATOR_REACHABILITY_ZERO and RELEASE_DEMO_MARKET_WRITE_PATHS_ZERO are asserted against the real
 * release flag, not a constructed value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseDemoGeneratorGateTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `RELEASE_DEMO_GENERATOR_REACHABILITY_ZERO the release build flag is closed`() {
        assertFalse(BuildConfig.DEBUG)
        assertFalse(demoGeneratorsEnabledForThisBuild())
    }

    @Test
    fun `RELEASE_DEMO_CARD_HIDDEN the build-configured state hides the Diagnostics demo card`() {
        val harness = OfflineDemoHarness(demoGeneratorsEnabled = demoGeneratorsEnabledForThisBuild())
        assertFalse(harness.viewModel.uiState.value.demoGeneratorsAvailable)
    }

    @Test
    fun `RELEASE_DEMO_MARKET_WRITE_PATHS_ZERO release generator calls persist no market bars`() {
        val harness = OfflineDemoHarness(demoGeneratorsEnabled = demoGeneratorsEnabledForThisBuild())

        harness.viewModel.generateBtcUpdate()
        harness.viewModel.generateSpyUpdate()

        assertEquals(0, harness.marketDao.insertCalls)
        assertEquals(0, harness.marketDao.rows.size)
        assertEquals(0, harness.featureDao.insertCalls)
        assertEquals(0, harness.signalDao.insertCalls)
        assertEquals(0, harness.journalDao.insertCalls)
    }
}
