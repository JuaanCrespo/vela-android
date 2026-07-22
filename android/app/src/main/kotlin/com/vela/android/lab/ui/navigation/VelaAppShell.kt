package com.vela.android.lab.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.android.lab.ui.settings.VelaUiDensity
import com.vela.android.lab.ui.theme.LocalVelaColors
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSafetyBanner
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill

internal object VelaBottomNavigationTokens {
    val HorizontalInset = 12.dp
    val BottomInset = 8.dp
    val CornerRadius = 28.dp
    val ShadowElevation = 8.dp
    val BorderWidth = 1.dp
}

/**
 * Persistent phone shell for UX-2.
 *
 * All navigation events leave through [onDestinationSelected]. This composable
 * never starts a stream, refreshes data, mutates Paper state, or performs I/O.
 */
@Composable
fun VelaAppShell(
    currentDestination: VelaDestination,
    onDestinationSelected: (VelaDestination) -> Unit,
    modeLabel: String,
    realLocked: Boolean,
    manualSubmitCompiled: Boolean,
    density: VelaUiDensity = VelaUiDensity.COMFORTABLE,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val horizontalPadding = when (density) {
        VelaUiDensity.COMPACT -> 8.dp
        VelaUiDensity.COMFORTABLE -> 12.dp
    }
    val bannerBottomPadding = when (density) {
        VelaUiDensity.COMPACT -> 6.dp
        VelaUiDensity.COMFORTABLE -> 8.dp
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                VelaTopBar(
                    currentDestination = currentDestination,
                    density = density,
                )
                VelaSafetyBanner(
                    modeLabel = modeLabel,
                    realLocked = realLocked,
                    manualSubmitCompiled = manualSubmitCompiled,
                    modifier = Modifier.padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = bannerBottomPadding,
                    ),
                )
            }
        },
        bottomBar = {
            VelaBottomNavigation(
                currentDestination = currentDestination,
                onDestinationSelected = onDestinationSelected,
            )
        },
    ) { contentPadding ->
        VelaNavHost(
            currentDestination = currentDestination,
            modifier = Modifier.fillMaxSize(),
        ) {
            content(contentPadding)
        }
    }
}

@Composable
fun VelaNavHost(
    currentDestination: VelaDestination,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    Box(modifier = modifier) {
        stateHolder.SaveableStateProvider(currentDestination.route) {
            content()
        }
    }
}

@Composable
fun VelaTopBar(
    currentDestination: VelaDestination,
    density: VelaUiDensity = VelaUiDensity.COMFORTABLE,
    modifier: Modifier = Modifier,
) {
    val verticalPadding = when (density) {
        VelaUiDensity.COMPACT -> 7.dp
        VelaUiDensity.COMFORTABLE -> 10.dp
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "VELA",
                    color = LocalVelaColors.current.safe,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = currentDestination.label,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            VelaStatusPill(
                label = if (currentDestination.isPrimary) {
                    "Sección principal"
                } else {
                    "Más"
                },
                tone = VelaPillTone.Neutral,
            )
        }
    }
}

@Composable
fun VelaBottomNavigation(
    currentDestination: VelaDestination,
    onDestinationSelected: (VelaDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedPrimary = currentDestination.selectedPrimaryDestination
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = VelaBottomNavigationTokens.HorizontalInset,
                end = VelaBottomNavigationTokens.HorizontalInset,
                bottom = VelaBottomNavigationTokens.BottomInset,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(VelaBottomNavigationTokens.CornerRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = VelaBottomNavigationTokens.ShadowElevation,
            border = BorderStroke(
                width = VelaBottomNavigationTokens.BorderWidth,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            ),
        ) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                VelaDestination.primaryDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedPrimary == destination,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Text(
                                text = destination.navigationGlyph,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        alwaysShowLabel = true,
                        modifier = Modifier.semantics {
                            contentDescription = "Abrir ${destination.label}"
                        },
                    )
                }
            }
        }
    }
}

/**
 * Content for the Más destination. Secondary selections use the same callback
 * as the bottom bar and therefore remain state-only.
 */
@Composable
fun VelaMoreMenu(
    currentDestination: VelaDestination,
    onDestinationSelected: (VelaDestination) -> Unit,
    contentPadding: PaddingValues,
    density: VelaUiDensity = VelaUiDensity.COMFORTABLE,
    modifier: Modifier = Modifier,
) {
    val spacing = when (density) {
        VelaUiDensity.COMPACT -> 8.dp
        VelaUiDensity.COMFORTABLE -> 12.dp
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        VelaSectionHeader(
            title = "Más",
            subtitle = "Herramientas read-only y preferencias locales",
        )
        VelaDestination.secondaryDestinations.forEach { destination ->
            OutlinedButton(
                onClick = { onDestinationSelected(destination) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Abrir ${destination.label}"
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(destination.label)
                    if (currentDestination == destination) {
                        VelaStatusPill(
                            label = "Actual",
                            tone = VelaPillTone.Safe,
                        )
                    }
                }
            }
        }
    }
}
