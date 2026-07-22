package com.vela.android.lab.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VelaDestinationTest {
    @Test
    fun `phone navigation has exactly five ordered primary destinations`() {
        assertEquals(
            listOf("Inicio", "Mercado", "Velas", "Paper", "Más"),
            VelaDestination.primaryDestinations.map(VelaDestination::label),
        )
        assertEquals(5, VelaDestination.primaryDestinations.size)
        assertTrue(VelaDestination.primaryDestinations.all(VelaDestination::isPrimary))
    }

    @Test
    fun `more contains the four required secondary destinations`() {
        assertEquals(
            listOf("Riesgo", "Historial y auditoría", "Configuración", "Diagnóstico"),
            VelaDestination.secondaryDestinations.map(VelaDestination::label),
        )
        assertTrue(VelaDestination.secondaryDestinations.all(VelaDestination::isMoreSection))
        assertTrue(VelaDestination.MORE.isMoreSection)
        assertEquals(
            VelaDestination.MORE,
            VelaDestination.RISK.selectedPrimaryDestination,
        )
    }

    @Test
    fun `route lookup is an exact allowlist`() {
        assertEquals(VelaDestination.HOME, VelaDestination.fromRoute("inicio"))
        assertEquals(VelaDestination.SETTINGS, VelaDestination.fromRoute(" configuracion "))

        assertNull(VelaDestination.fromRoute(null))
        assertNull(VelaDestination.fromRoute(""))
        assertNull(VelaDestination.fromRoute("Inicio"))
        assertNull(VelaDestination.fromRoute("paper/submit"))
        assertNull(VelaDestination.fromRoute("submit"))
        assertNull(VelaDestination.fromRoute("manual-paper-submit"))
    }

    @Test
    fun `destination catalog contains no submit route`() {
        assertFalse(
            VelaDestination.entries.any { destination ->
                destination.route.contains("submit", ignoreCase = true)
            },
        )
    }
}
