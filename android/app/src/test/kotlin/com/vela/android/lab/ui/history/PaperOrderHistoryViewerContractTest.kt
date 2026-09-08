package com.vela.android.lab.ui.history

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderHistoryViewerContractTest {

    private val viewerSource: String = source("PaperOrderHistoryViewer.kt")
    private val viewModelSource: String = source("PaperOrderHistoryViewModel.kt")

    @Test
    fun `viewer exposes list detail timeline filters legacy and local error states`() {
        listOf(
            "Historial Paper",
            "Mostrando hasta",
            "Estado",
            "Símbolo",
            "Lado",
            "Expected position delta",
            "Timeline lifecycle",
            "Payload repetido",
            LEGACY_NOT_RECORDED,
            "UNKNOWN LEGACY",
            "Same payload as previous",
            "Reset acknowledged",
            "ERROR DE BASE LOCAL",
            "ERROR CANÓNICO",
            "Todavía no hay órdenes Paper históricas",
        ).forEach { expected ->
            assertTrue(viewerSource.contains(expected), "Missing UI contract: $expected")
        }
    }

    @Test
    fun `viewer contains no trading or broker refresh control`() {
        listOf(
            "Submit Paper order once",
            "Armar sesión",
            "Preparar otra orden",
            "Consultar estado Alpaca",
            "Cancelar orden",
            "Reemplazar orden",
            "Cerrar posición",
        ).forEach { forbidden ->
            assertFalse(viewerSource.contains(forbidden), "Forbidden UI control: $forbidden")
        }
    }

    @Test
    fun `view model depends only on canonical history reader`() {
        assertTrue(viewModelSource.contains("PaperOrderHistoryReader"))
        listOf(
            "AlpacaPaper",
            "HttpClient",
            "Credentials",
            "PaperManualSubmitViewModel",
            "PaperOrderStatusTrackerRepository",
        ).forEach { forbidden ->
            assertFalse(viewModelSource.contains(forbidden), "Forbidden VM dependency: $forbidden")
        }
    }

    private fun source(name: String): String {
        val root = listOf(
            File("src/main/kotlin/com/vela/android/lab/ui/history"),
            File("app/src/main/kotlin/com/vela/android/lab/ui/history"),
        ).firstOrNull(File::isDirectory)
            ?: error("Cannot locate Paper history sources from ${File(".").absolutePath}")
        return File(root, name).readText()
    }
}
