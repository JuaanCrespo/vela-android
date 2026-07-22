package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.paper.preflight.IntentSource
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessReason
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessSnapshot
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderIntent
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadFields
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightResult
import com.vela.android.lab.data.paper.preflight.PreflightStatus
import com.vela.android.lab.data.paper.preflight.TimeInForce
import com.vela.android.lab.db.room.dao.PaperOrderSubmitAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity

internal const val SUBMIT_TEST_NOW: Long = 10_000L

internal fun submitTestPreview(
    previewPriceUsd: Double = 500.0,
    priceSource: MarketPriceSource = MarketPriceSource.ROOM_BAR_CLOSE,
    priceFreshness: PriceFreshness = PriceFreshness.FRESH,
): PaperOrderPayloadPreview = PaperOrderPayloadPreview(
    previewId = "preview-submit-1",
    linkedClientDryRunId = "dry-run-submit-1",
    symbol = "SPY",
    side = OrderSide.BUY,
    type = OrderType.MARKET,
    timeInForce = TimeInForce.DAY,
    quantity = 1.0,
    limitPriceUsd = null,
    estimatedNotionalUsd = previewPriceUsd,
    priceSource = priceSource.name,
    priceFreshness = priceFreshness.name,
    relatedSignalState = "NEUTRAL",
    generatedAtEpochMillis = 9_000L,
    status = PaperOrderPayloadPreviewStatus.READY_PREVIEW,
    warningMessages = emptyList(),
    payloadFields = PaperOrderPayloadFields(
        symbol = "SPY",
        side = "buy",
        type = "market",
        timeInForce = "day",
        quantity = 1.0,
        limitPriceUsd = null,
    ),
)

internal fun submitTestPreflight(
    status: PreflightStatus = PreflightStatus.ALLOWED_DRY_RUN,
): PaperOrderPreflightResult = PaperOrderPreflightResult(
    intent = PaperOrderIntent(
        symbol = "SPY",
        side = OrderSide.BUY,
        quantity = 1.0,
        createdAtEpochMillis = 9_000L,
        clientDryRunId = "dry-run-submit-1",
        source = IntentSource.MANUAL_DRY_RUN,
    ),
    status = status,
    estimatedNotionalUsd = 500.0,
    estimatedBuyingPowerAfterUsd = 99_500.0,
    allocationPercentAfter = 0.5,
    positionImpactQty = 1.0,
    relatedSignalState = "NEUTRAL",
    marketOpen = true,
    blockReasons = emptyList(),
    warnings = emptyList(),
    priceSource = MarketPriceSource.ROOM_BAR_CLOSE.name,
    priceFreshness = PriceFreshness.FRESH.name,
    priceAgeMillis = 1_000L,
)

internal fun submitTestPrice(
    freshness: PriceFreshness = PriceFreshness.FRESH,
    price: Double = 500.0,
    symbol: String = "SPY",
    source: MarketPriceSource = MarketPriceSource.ROOM_BAR_CLOSE,
    ageMillis: Long = 1_000L,
): MarketPriceSnapshot = MarketPriceSnapshot(
    symbol = symbol,
    price = price,
    bid = null,
    ask = null,
    marketTimestampMillis = SUBMIT_TEST_NOW - ageMillis,
    deviceReceivedAtMillis = null,
    ageMillis = ageMillis,
    source = source,
    freshness = freshness,
    reason = null,
)

internal fun submitTestConfirmation(): PaperManualSubmitConfirmation =
    PaperManualSubmitConfirmation(
        tokenId = "token-submit-1",
        previewId = "preview-submit-1",
        symbol = "SPY",
        side = "BUY",
        quantity = 1.0,
        priceSource = MarketPriceSource.ROOM_BAR_CLOSE.name,
        priceFreshness = PriceFreshness.FRESH.name,
        previewGeneratedAtEpochMillis = 9_000L,
        issuedAtEpochMillis = 9_500L,
        expiresAtEpochMillis = 69_500L,
    )

internal fun submitTestRequest(
    confirmationTokenId: String = "token-submit-1",
): PaperOrderSubmitRequest = PaperOrderSubmitRequest(
    submitAttemptId = "attempt-submit-1",
    previewId = "preview-submit-1",
    linkedClientDryRunId = "dry-run-submit-1",
    symbol = "SPY",
    side = OrderSide.BUY,
    type = OrderType.MARKET,
    timeInForce = TimeInForce.DAY,
    quantity = 1.0,
    limitPrice = null,
    clientOrderId = "vela-client-submit-1",
    generatedAtEpochMillis = 9_500L,
    confirmationTokenId = confirmationTokenId,
)

internal fun submitTestReadiness(): PaperExecutionReadinessSnapshot =
    PaperExecutionReadinessSnapshot(
        previewId = "preview-submit-1",
        linkedClientDryRunId = "dry-run-submit-1",
        hasValidPreview = true,
        realLocked = true,
        credentialsConfigured = true,
        status = PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED,
        blockingReasons = listOf(
            PaperExecutionReadinessReason.EXECUTION_DISABLED,
            PaperExecutionReadinessReason.PAPER_POST_ORDERS_DISABLED,
        ),
        warnings = emptyList(),
        checkedAtEpochMillis = 9_500L,
    )

internal fun submitTestGateInput(
    sessionArmed: Boolean = true,
    confirmation: PaperManualSubmitConfirmation? = submitTestConfirmation(),
    request: PaperOrderSubmitRequest? = submitTestRequest(),
): PaperManualSubmitGateInput = PaperManualSubmitGateInput(
    humanApprovalRecorded = true,
    sessionArmed = sessionArmed,
    realLocked = true,
    liveEnabled = false,
    autoPaperEnabled = false,
    credentialsConfigured = true,
    accountBlocked = false,
    tradingBlocked = false,
    accountRefreshedAtEpochMillis = 9_500L,
    clockRefreshedAtEpochMillis = 9_500L,
    marketOpen = true,
    priceSnapshot = submitTestPrice(),
    preflight = submitTestPreflight(),
    warningAccepted = true,
    disabledReadinessStatus = PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED,
    preview = submitTestPreview(),
    reviewQueueMatch = true,
    request = request,
    confirmation = confirmation,
    duplicatePreview = false,
    duplicateClientOrderId = false,
    submitInFlight = false,
    nowEpochMillis = SUBMIT_TEST_NOW,
)

internal class SubmitFakeAuditDao(
    var failInsert: Boolean = false,
) : PaperOrderSubmitAuditDao {
    val rows: MutableList<PaperOrderSubmitAuditEntity> = mutableListOf()
    var afterInsert: ((PaperOrderSubmitAuditEntity) -> Unit)? = null
    private var nextId = 1L

    override suspend fun insert(event: PaperOrderSubmitAuditEntity): Long {
        if (failInsert) error("audit unavailable")
        require(rows.none { it.eventKey == event.eventKey }) { "duplicate event key" }
        val stored = if (event.id == 0L) event.copy(id = nextId++) else event
        rows += stored
        afterInsert?.invoke(stored)
        return stored.id
    }

    override suspend fun countAll(): Int = rows.size
    override suspend fun recent(limit: Int): List<PaperOrderSubmitAuditEntity> =
        rows.sortedByDescending { it.submittedAtEpochMillis }.take(limit)
    override suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderSubmitAuditEntity> = rows.filter { it.symbol == symbol }
        .sortedByDescending { it.submittedAtEpochMillis }.take(limit)
    override suspend fun byAttemptId(attemptId: String): List<PaperOrderSubmitAuditEntity> =
        rows.filter { it.submitAttemptId == attemptId }
    override suspend fun countByPreviewId(previewId: String): Int =
        rows.count { it.previewId == previewId }
    override suspend fun countByClientOrderId(clientOrderId: String): Int =
        rows.count { it.clientOrderId == clientOrderId }
}

internal class SubmitFakeHttpClient(
    var response: PaperSubmitHttpResult =
        PaperSubmitHttpResult.Success(200, """{"id":"paper-order-1"}"""),
) : AlpacaPaperOrderSubmitHttpClient {
    var callCount: Int = 0
    var lastUrl: String? = null
    var lastBody: String? = null

    override suspend fun executePostOrder(url: String, bodyJson: String): PaperSubmitHttpResult {
        AlpacaPaperSubmitEndpoint.requireSafeManualPaperOrder("POST", url)
        callCount += 1
        lastUrl = url
        lastBody = bodyJson
        return response
    }
}
