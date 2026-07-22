@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadFields
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PreviewQueueFakeDao
import com.vela.android.lab.data.paper.preflight.TimeInForce
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperOrderPayloadPreviewQueueViewModelTest {

    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun preview(id: String, createdAt: Long): PaperOrderPayloadPreview =
        PaperOrderPayloadPreview(
            previewId = id,
            linkedClientDryRunId = "dry-$id",
            symbol = "SPY",
            side = OrderSide.BUY,
            type = OrderType.MARKET,
            timeInForce = TimeInForce.DAY,
            quantity = 1.0,
            limitPriceUsd = null,
            estimatedNotionalUsd = 400.25,
            priceSource = "ROOM_BAR_CLOSE",
            priceFreshness = "FRESH",
            relatedSignalState = "NEUTRAL",
            generatedAtEpochMillis = createdAt,
            status = PaperOrderPayloadPreviewStatus.READY_PREVIEW,
            warningMessages = emptyList(),
            payloadFields = PaperOrderPayloadFields(
                symbol = "SPY",
                side = "buy",
                type = "market",
                timeInForce = "day",
                quantity = 1.0,
                limitPriceUsd = null,
            ),
        )

    @Test
    fun `initial refresh exposes empty queue and timestamp`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = PaperOrderPayloadPreviewQueueViewModel(
                repository = PaperOrderPayloadPreviewRepository(PreviewQueueFakeDao()),
                clock = { Instant.ofEpochMilli(500L) },
            )
            val state = vm.uiState.value
            assertEquals(0, state.totalPreviews)
            assertTrue(state.recentRows.isEmpty())
            assertEquals(500L, state.lastRefreshAtEpochMillis)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun `refreshNow reads durable rows newest first`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val repository = PaperOrderPayloadPreviewRepository(dao)
            val vm = PaperOrderPayloadPreviewQueueViewModel(repository)
            repository.savePreview(preview("old", 100L))
            repository.savePreview(preview("new", 200L))

            vm.refreshNow()

            assertEquals(2, vm.uiState.value.totalPreviews)
            assertEquals(listOf("new", "old"), vm.uiState.value.recentRows.map { it.previewId })
            assertNotNull(vm.uiState.value.lastRefreshAtEpochMillis)
        }

    @Test
    fun `queue ViewModel has no mutation or execution method`() {
        val forbidden = listOf(
            "update", "delete", "clear", "submit", "execute", "cancel", "replace",
            "post", "patch", "closeposition", "account",
        )
        val methods = PaperOrderPayloadPreviewQueueViewModel::class.java.declaredMethods
            .map { it.name }.filterNot { it.contains('$') }
        for (method in methods) {
            for (bad in forbidden) {
                assertFalse(method.lowercase().contains(bad), "Queue VM method '$method' contains '$bad'")
            }
        }
    }

    @Test
    fun `queue UI state contains no credential or account value`() {
        val rendered = PaperOrderPayloadPreviewQueueUiState.Initial.toString()
        assertFalse(rendered.contains("topsecretvalue"))
        assertFalse(rendered.contains("PKABCDEF1234"))
        assertFalse(rendered.contains("accountId", ignoreCase = true))
    }
}
