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
        assertEquals(20.dp, VelaBottomNavigationTokens.HorizontalInset)
        assertEquals(8.dp, VelaBottomNavigationTokens.BottomInset)
        assertEquals(20.dp, VelaBottomNavigationTokens.CornerRadius)
        assertEquals(8.dp, VelaBottomNavigationTokens.ShadowElevation)
        assertEquals(1.dp, VelaBottomNavigationTokens.BorderWidth)
        assertEquals(320.dp, VelaBottomNavigationTokens.MaxWidth)
        assertEquals(18.dp, VelaBottomNavigationTokens.IconSize)
        assertEquals(56.dp, VelaBottomNavigationTokens.NavigationBarHeight)
        assertEquals(0.24f, VelaBottomNavigationTokens.IndicatorAlpha)
        assertEquals(48.dp, VelaBottomNavigationTokens.PillWidth)
        assertEquals(28.dp, VelaBottomNavigationTokens.PillHeight)
        assertEquals(14.dp, VelaBottomNavigationTokens.PillCornerRadius)
        assertEquals(2.dp, VelaBottomNavigationTokens.ItemLabelSpacing)
    }

    @Test
    fun `primary destinations have five distinct vector icons`() {
        val iconResources = VelaDestination.primaryDestinations.map(
            VelaDestination::bottomNavigationIconRes,
        )

        assertEquals(5, iconResources.size)
        assertEquals(5, iconResources.distinct().size)
        assertTrue(iconResources.all { resourceId -> resourceId != 0 })
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
        assertTrue(bottomBar.contains(".align(Alignment.Center)"))
        assertTrue(bottomBar.contains(".widthIn(max = VelaBottomNavigationTokens.MaxWidth)"))
        assertTrue(bottomBar.contains("Arrangement.SpaceEvenly"))
        assertTrue(bottomBar.contains(".height(VelaBottomNavigationTokens.NavigationBarHeight)"))
        assertTrue(bottomBar.contains("VelaBottomNavigationItem("))
        assertTrue(bottomBar.contains("iconRes = destination.bottomNavigationIconRes()"))
        assertTrue(bottomBar.contains("label = destination.label"))
        assertFalse(bottomBar.contains("navigationGlyph"))
        assertFalse(source.contains("import androidx.compose.material3.NavigationBar"))

        val itemComposable = source
            .substringAfter("private fun VelaBottomNavigationItem(")
            .substringBefore("/**\n * Content for the")

        assertTrue(itemComposable.contains("VelaBottomNavigationTokens.PillWidth"))
        assertTrue(itemComposable.contains("VelaBottomNavigationTokens.PillHeight"))
        assertTrue(itemComposable.contains("VelaBottomNavigationTokens.PillCornerRadius"))
        assertTrue(itemComposable.contains("VelaBottomNavigationTokens.IconSize"))
        assertTrue(itemComposable.contains("VelaBottomNavigationTokens.ItemLabelSpacing"))
        assertTrue(itemComposable.contains("VelaBottomNavigationTokens.IndicatorAlpha"))
        assertTrue(itemComposable.contains("velaColors.safe"))
        assertTrue(itemComposable.contains("velaColors.muted"))
        assertTrue(itemComposable.contains("painterResource(iconRes)"))
        assertTrue(itemComposable.contains("Icon("))

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
            assertFalse(itemComposable.contains(forbidden), "Item composable gained forbidden behavior: $forbidden")
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
