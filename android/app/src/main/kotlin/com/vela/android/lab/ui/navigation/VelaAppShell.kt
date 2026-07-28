package com.vela.android.lab.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.android.lab.R
import com.vela.android.lab.ui.settings.VelaUiDensity
import com.vela.android.lab.ui.theme.LocalVelaColors
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSafetyBanner
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill

internal object VelaBottomNavigationTokens {
    val HorizontalInset = 20.dp
    val BottomInset = 8.dp
    val CornerRadius = 20.dp
    val ShadowElevation = 8.dp
    val BorderWidth = 1.dp
    val MaxWidth = 320.dp
    val IconSize = 18.dp
    val NavigationBarHeight = 56.dp
    val IndicatorAlpha = 0.24f
    val PillWidth = 48.dp
    val PillHeight = 28.dp
    val PillCornerRadius = 14.dp
    val ItemLabelSpacing = 2.dp
}

internal fun VelaDestination.bottomNavigationIconRes(): Int = when (this) {
    VelaDestination.HOME -> R.drawable.ic_nav_home
    VelaDestination.MARKET -> R.drawable.ic_nav_market
    VelaDestination.CANDLES -> R.drawable.ic_nav_candles
    VelaDestination.PAPER -> R.drawable.ic_nav_paper
    VelaDestination.MORE -> R.drawable.ic_nav_more
    else -> error("Only primary destinations have bottom navigation icons: $this")
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
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = VelaBottomNavigationTokens.MaxWidth)
                .fillMaxWidth(),
            shape = RoundedCornerShape(VelaBottomNavigationTokens.CornerRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = VelaBottomNavigationTokens.ShadowElevation,
            border = BorderStroke(
                width = VelaBottomNavigationTokens.BorderWidth,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VelaBottomNavigationTokens.NavigationBarHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VelaDestination.primaryDestinations.forEach { destination ->
                    VelaBottomNavigationItem(
                        selected = selectedPrimary == destination,
                        label = destination.label,
                        iconRes = destination.bottomNavigationIconRes(),
                        onClick = { onDestinationSelected(destination) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = "Abrir ${destination.label}"
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun VelaBottomNavigationItem(
    selected: Boolean,
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val velaColors = LocalVelaColors.current
    val contentColor = if (selected) velaColors.safe else velaColors.muted
    val pillColor = if (selected) {
        velaColors.safe.copy(alpha = VelaBottomNavigationTokens.IndicatorAlpha)
    } else {
        Color.Transparent
    }
    Column(
        modifier = modifier
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = VelaBottomNavigationTokens.PillWidth,
                    height = VelaBottomNavigationTokens.PillHeight,
                )
                .clip(RoundedCornerShape(VelaBottomNavigationTokens.PillCornerRadius))
                .background(pillColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(VelaBottomNavigationTokens.IconSize),
            )
        }
        Spacer(modifier = Modifier.height(VelaBottomNavigationTokens.ItemLabelSpacing))
        Text(
            text = label,
            color = contentColor,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
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
