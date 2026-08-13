package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint

/**
 * Exact read-only endpoint guard for one Alpaca Paper order lifecycle lookup.
 *
 * The only accepted shape is `GET /v2/orders/{canonical-lowercase-UUID}` on the
 * Paper host. The orders collection, LIVE host, query strings, fragments, extra
 * path segments, and non-canonical identifiers all fail closed.
 */
object AlpacaPaperOrderStatusEndpoint {

    const val METHOD: String = "GET"
    const val PAPER_BASE_URL: String = AlpacaPaperTradingEndpoint.PAPER_BASE_URL

    private const val ORDERS_PATH: String = "/orders"
    private const val ORDER_URL_PREFIX: String = "$PAPER_BASE_URL$ORDERS_PATH/"
    private val canonicalUuid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )

    fun requireCanonicalOrderId(orderId: String): String {
        require(canonicalUuid.matches(orderId)) {
            "Paper order id must be a canonical lowercase UUID."
        }
        return orderId
    }

    fun isCanonicalOrderId(orderId: String): Boolean = try {
        requireCanonicalOrderId(orderId)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    fun urlFor(orderId: String): String =
        "$ORDER_URL_PREFIX${requireCanonicalOrderId(orderId)}"

    fun requireSafeGet(url: String) {
        require(url.startsWith(ORDER_URL_PREFIX)) {
            "Paper order status GET must target the exact Paper orders path."
        }
        val orderId = url.removePrefix(ORDER_URL_PREFIX)
        requireCanonicalOrderId(orderId)
        require(url == urlFor(orderId)) {
            "Paper order status GET URL is not canonical."
        }
    }

    fun isSafeGet(url: String): Boolean = try {
        requireSafeGet(url)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}
