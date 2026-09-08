package com.vela.android.lab.data.paper.history

/**
 * Narrow read-only surface consumed by the Paper history viewer.
 *
 * Keeping this contract separate from submit/reconciliation services makes it impossible for
 * the viewer to mutate evidence or reach an Alpaca boundary.
 */
interface PaperOrderHistoryReader {
    suspend fun getByAttemptId(attemptId: String): CanonicalPaperOrderHistory?

    suspend fun getTerminalOrders(): List<CanonicalPaperOrderHistory>

    suspend fun getFilledOrders(): List<CanonicalPaperOrderHistory>

    suspend fun getBySymbol(symbol: String): List<CanonicalPaperOrderHistory>

    suspend fun getBySide(side: String): List<CanonicalPaperOrderHistory>

    suspend fun getLatestN(limit: Int): List<CanonicalPaperOrderHistory>
}
