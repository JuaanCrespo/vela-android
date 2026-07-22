package com.vela.android.lab.ui.navigation

data class VelaNavigationState(
    val currentDestination: VelaDestination = VelaDestination.HOME,
)

sealed interface VelaNavigationAction {
    data class Select(val destination: VelaDestination) : VelaNavigationAction

    data class Restore(
        val lastDestinationRoute: String?,
        val rememberLastSection: Boolean,
    ) : VelaNavigationAction

    data object Reset : VelaNavigationAction
}

/**
 * Pure reducer used by the Activity/ViewModel integration.
 *
 * Restoring is fail-closed to Inicio. Because selection accepts the closed
 * [VelaDestination] enum rather than an arbitrary route, navigation cannot
 * manufacture an execution or submit destination.
 */
object VelaNavigationReducer {
    fun reduce(
        state: VelaNavigationState,
        action: VelaNavigationAction,
    ): VelaNavigationState = when (action) {
        is VelaNavigationAction.Select ->
            state.copy(currentDestination = action.destination)

        is VelaNavigationAction.Restore ->
            VelaNavigationState(
                currentDestination = restoreDestination(
                    lastDestinationRoute = action.lastDestinationRoute,
                    rememberLastSection = action.rememberLastSection,
                ),
            )

        VelaNavigationAction.Reset -> VelaNavigationState()
    }

    fun restoreDestination(
        lastDestinationRoute: String?,
        rememberLastSection: Boolean,
    ): VelaDestination {
        if (!rememberLastSection) return VelaDestination.HOME
        return VelaDestination.fromRoute(lastDestinationRoute) ?: VelaDestination.HOME
    }
}
