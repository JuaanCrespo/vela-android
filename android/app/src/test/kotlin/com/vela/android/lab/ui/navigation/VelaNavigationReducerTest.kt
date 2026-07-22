package com.vela.android.lab.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VelaNavigationReducerTest {
    @Test
    fun `select changes only the current destination`() {
        val result = VelaNavigationReducer.reduce(
            state = VelaNavigationState(),
            action = VelaNavigationAction.Select(VelaDestination.CANDLES),
        )

        assertEquals(VelaDestination.CANDLES, result.currentDestination)
    }

    @Test
    fun `restore returns remembered allowlisted primary section`() {
        val result = VelaNavigationReducer.reduce(
            state = VelaNavigationState(VelaDestination.MARKET),
            action = VelaNavigationAction.Restore(
                lastDestinationRoute = VelaDestination.PAPER.route,
                rememberLastSection = true,
            ),
        )

        assertEquals(VelaDestination.PAPER, result.currentDestination)
    }

    @Test
    fun `restore supports a secondary section while selecting more`() {
        val destination = VelaNavigationReducer.restoreDestination(
            lastDestinationRoute = VelaDestination.HISTORY.route,
            rememberLastSection = true,
        )

        assertEquals(VelaDestination.HISTORY, destination)
        assertEquals(VelaDestination.MORE, destination.selectedPrimaryDestination)
    }

    @Test
    fun `restore disabled always returns home`() {
        assertEquals(
            VelaDestination.HOME,
            VelaNavigationReducer.restoreDestination(
                lastDestinationRoute = VelaDestination.PAPER.route,
                rememberLastSection = false,
            ),
        )
    }

    @Test
    fun `unknown or submit-like restored routes fail closed to home`() {
        listOf(
            null,
            "submit",
            "paper/submit",
            "manual-paper-submit",
            "https://paper-api.alpaca.markets/v2/orders",
        ).forEach { route ->
            assertEquals(
                VelaDestination.HOME,
                VelaNavigationReducer.restoreDestination(
                    lastDestinationRoute = route,
                    rememberLastSection = true,
                ),
            )
        }
    }

    @Test
    fun `reset returns the safe home destination`() {
        val result = VelaNavigationReducer.reduce(
            state = VelaNavigationState(VelaDestination.DIAGNOSTICS),
            action = VelaNavigationAction.Reset,
        )

        assertEquals(VelaNavigationState(), result)
    }
}
