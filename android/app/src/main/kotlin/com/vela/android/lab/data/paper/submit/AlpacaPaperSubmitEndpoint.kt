package com.vela.android.lab.data.paper.submit

/** Exact Phase 2.v allowlist for the single manual Paper mutation. */
object AlpacaPaperSubmitEndpoint {
    const val METHOD: String = "POST"
    const val ORDERS_URL: String = "https://paper-api.alpaca.markets/v2/orders"

    fun requireSafeManualPaperOrder(method: String, url: String) {
        val normalizedMethod = method.trim().uppercase()
        val lowerUrl = url.lowercase()
        require(!lowerUrl.startsWith("https://api.alpaca.markets")) {
            "LIVE Trading host is forbidden."
        }
        require(!lowerUrl.contains("live")) { "LIVE endpoints are forbidden." }
        require(normalizedMethod == METHOD) { "Manual Paper submit permits POST only." }
        require(url == ORDERS_URL) {
            "Manual Paper submit is locked to the Paper orders collection."
        }
    }

    fun isSafeManualPaperOrder(method: String, url: String): Boolean = try {
        requireSafeManualPaperOrder(method, url)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}
