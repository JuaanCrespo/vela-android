package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PaperManualSubmitConfirmation(
    val tokenId: String,
    val previewId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val priceSource: String,
    val priceFreshness: String,
    val previewGeneratedAtEpochMillis: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

sealed interface PaperManualSubmitTokenIssue {
    data class Issued(val confirmation: PaperManualSubmitConfirmation) :
        PaperManualSubmitTokenIssue
    data class Rejected(val requiredText: String) : PaperManualSubmitTokenIssue
}

/** In-memory only. An issued token is removed on the first consume attempt. */
class PaperManualSubmitTokenStore(
    private val clock: () -> Instant = { Instant.now() },
    private val tokenIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private var active: PaperManualSubmitConfirmation? = null

    init {
        require(ttlMillis in 1_000L..120_000L) { "Confirmation TTL must be short-lived." }
    }

    @Synchronized
    fun issue(
        preview: PaperOrderPayloadPreview,
        exactUserText: String,
    ): PaperManualSubmitTokenIssue {
        val required = requiredText(preview)
        if (exactUserText != required ||
            preview.priceSource.isNullOrBlank() ||
            preview.priceFreshness.isNullOrBlank()
        ) {
            active = null
            return PaperManualSubmitTokenIssue.Rejected(required)
        }
        val now = clock().toEpochMilli()
        val confirmation = PaperManualSubmitConfirmation(
            tokenId = tokenIdFactory(),
            previewId = preview.previewId,
            symbol = preview.symbol,
            side = preview.side.name,
            quantity = preview.quantity,
            priceSource = preview.priceSource,
            priceFreshness = preview.priceFreshness,
            previewGeneratedAtEpochMillis = preview.generatedAtEpochMillis,
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + ttlMillis,
        )
        active = confirmation
        return PaperManualSubmitTokenIssue.Issued(confirmation)
    }

    @Synchronized
    fun peek(tokenId: String): PaperManualSubmitConfirmation? =
        active?.takeIf { it.tokenId == tokenId }

    @Synchronized
    fun consume(
        tokenId: String,
        preview: PaperOrderPayloadPreview,
    ): PaperManualSubmitConfirmation? {
        val candidate = active
        active = null
        val now = clock().toEpochMilli()
        return candidate?.takeIf {
            it.tokenId == tokenId &&
                now <= it.expiresAtEpochMillis &&
                it.previewId == preview.previewId &&
                it.symbol == preview.symbol &&
                it.side == preview.side.name &&
                it.quantity == preview.quantity &&
                it.priceSource == preview.priceSource &&
                it.priceFreshness == preview.priceFreshness &&
                it.previewGeneratedAtEpochMillis == preview.generatedAtEpochMillis
        }
    }

    @Synchronized
    fun invalidate() {
        active = null
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30_000L

        fun requiredText(preview: PaperOrderPayloadPreview): String =
            "SUBMIT PAPER ${preview.symbol} ${preview.side.name} ${formatQuantity(preview.quantity)}"

        private fun formatQuantity(quantity: Double): String =
            BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()
    }
}
