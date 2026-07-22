package com.vela.android.lab.ui.navigation

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VelaBottomNavigationStyleTest {

    @Test
    fun `bottom bar keeps the floating rounded geometry`() {
        assertEquals(12.dp, VelaBottomNavigationTokens.HorizontalInset)
        assertEquals(8.dp, VelaBottomNavigationTokens.BottomInset)
        assertEquals(28.dp, VelaBottomNavigationTokens.CornerRadius)
        assertEquals(8.dp, VelaBottomNavigationTokens.ShadowElevation)
        assertEquals(1.dp, VelaBottomNavigationTokens.BorderWidth)
    }

    @Test
    fun `floating treatment remains navigation only`() {
        val source = shellSource()
        val bottomBar = source
            .substringAfter("fun VelaBottomNavigation(")
            .substringBefore("/**\n * Content for the")

        assertTrue(bottomBar.contains("VelaDestination.primaryDestinations.forEach"))
        assertTrue(bottomBar.contains(".navigationBarsPadding()"))
        assertTrue(bottomBar.contains("RoundedCornerShape(VelaBottomNavigationTokens.CornerRadius)"))
        assertTrue(bottomBar.contains("windowInsets = WindowInsets(0, 0, 0, 0)"))

        listOf(
            "submitOnce",
            "preflight",
            "arm(",
            "token",
            "LaunchedEffect",
            "http://",
            "https://",
            "wss://",
        ).forEach { forbidden ->
            assertFalse(bottomBar.contains(forbidden), "Bottom bar gained forbidden behavior: $forbidden")
        }
    }

    private fun shellSource(): String {
        val root = listOf(
            File("src/main/kotlin/com/vela/android/lab"),
            File("app/src/main/kotlin/com/vela/android/lab"),
        ).firstOrNull(File::isDirectory)
            ?: error("Cannot locate app main sources from ${File(".").absolutePath}")
        return File(root, "ui/navigation/VelaAppShell.kt").readText()
    }
}
