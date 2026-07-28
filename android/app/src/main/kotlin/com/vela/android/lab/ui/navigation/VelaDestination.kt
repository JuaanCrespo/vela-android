package com.vela.android.lab.ui.navigation

/**
 * Closed navigation allowlist for the UX-2 shell.
 *
 * Navigation is deliberately state-based: there is no URI/deep-link parser and
 * there is no destination for manual submit. Restored values must match one of
 * these routes exactly.
 */
enum class VelaDestination(
    val route: String,
    val label: String,
) {
    HOME(route = "inicio", label = "Inicio"),
    MARKET(route = "mercado", label = "Mercado"),
    CANDLES(route = "velas", label = "Velas"),
    PAPER(route = "paper", label = "Paper"),
    MORE(route = "mas", label = "Más"),
    RISK(route = "riesgo", label = "Riesgo"),
    HISTORY(route = "historial", label = "Historial y auditoría"),
    SETTINGS(route = "configuracion", label = "Configuración"),
    DIAGNOSTICS(route = "diagnostico", label = "Diagnóstico"),
    ;

    val isPrimary: Boolean
        get() = this in primaryDestinations

    /**
     * Secondary screens keep the Más item selected in the bottom navigation.
     */
    val isMoreSection: Boolean
        get() = this == MORE || this in secondaryDestinations

    val selectedPrimaryDestination: VelaDestination
        get() = if (isMoreSection) MORE else this

    companion object {
        /**
         * The phone bottom bar must remain exactly these five items and in this
         * order.
         */
        val primaryDestinations: List<VelaDestination> = listOf(
            HOME,
            MARKET,
            CANDLES,
            PAPER,
            MORE,
        )

        val secondaryDestinations: List<VelaDestination> = listOf(
            RISK,
            HISTORY,
            SETTINGS,
            DIAGNOSTICS,
        )

        private val destinationByRoute: Map<String, VelaDestination> =
            entries.associateBy(VelaDestination::route)

        /**
         * Exact-match restore only. Unknown, nested, execution, or submit-like
         * routes are rejected instead of being interpreted.
         */
        fun fromRoute(route: String?): VelaDestination? =
            route
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(destinationByRoute::get)
    }
}
