package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes, audits, and invokes at most one request for a consumed confirmation token. */
class PaperManualSubmitExecutor(
    private val gate: PaperManualSubmitGate,
    private val tokenStore: PaperManualSubmitTokenStore,
    private val submitClient: PaperManualOrderSubmitClient,
    private val auditRepository: PaperOrderSubmitAuditRepository,
    private val finalPriceSnapshotProvider: suspend (String) -> MarketPriceSnapshot,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val mutex = Mutex()

    suspend fun executeOnce(
        request: PaperOrderSubmitRequest,
        preview: PaperOrderPayloadPreview,
        gateInput: PaperManualSubmitGateInput,
    ): PaperOrderSubmitResult = mutex.withLock {
        val duplicatePreview = auditRepository.hasAttemptForPreview(request.previewId)
        val duplicateClientOrderId = auditRepository.hasClientOrderId(request.clientOrderId)
        val checkedInput = gateInput.copy(
            request = request,
            preview = preview,
            duplicatePreview = duplicatePreview,
            duplicateClientOrderId = duplicateClientOrderId,
            submitInFlight = false,
            nowEpochMillis = clock().toEpochMilli(),
        )
        when (val decision = gate.evaluate(checkedInput)) {
            is PaperManualSubmitGateDecision.Blocked -> {
                val error = decision.reasons.firstOrNull()
                    ?: PaperOrderSubmitError.FEATURE_DISABLED
                val result = blocked(request, error, decision.reasons.joinToString())
                recordTerminalBestEffort(request, preview, checkedInput.marketOpen == true, result)
                result
            }
            PaperManualSubmitGateDecision.Allowed -> {
                val consumed = tokenStore.consume(request.confirmationTokenId, preview)
                if (consumed == null) {
                    val result = blocked(
                        request,
                        PaperOrderSubmitError.CONFIRMATION_MISSING,
                        "Confirmation token is missing, expired, mismatched, or already consumed.",
                    )
                    recordTerminalBestEffort(request, preview, checkedInput.marketOpen == true, result)
                    return@withLock result
                }
                val startedAt = clock().toEpochMilli()
                try {
                    auditRepository.recordAttemptStarted(
                        request = request,
                        preview = preview,
                        marketOpen = checkedInput.marketOpen == true,
                        timestampEpochMillis = startedAt,
                    )
                } catch (_: Exception) {
                    return@withLock failed(
                        request,
                        PaperOrderSubmitError.AUDIT_WRITE_FAILED,
                        "Submit audit could not be written; no Paper request was sent.",
                    )
                }

                // Re-evaluate the complete fail-closed policy after the durable start
                // audit and immediately before the only network boundary. This closes
                // the suspension window in which the emergency kill switch can change.
                val latestPrice = try {
                    finalPriceSnapshotProvider(request.symbol)
                } catch (_: Exception) {
                    MarketPriceSnapshot.missing(
                        request.symbol,
                        "Final local price refresh failed.",
                    )
                }
                val finalInput = checkedInput.copy(
                    priceSnapshot = latestPrice,
                    nowEpochMillis = clock().toEpochMilli(),
                )
                when (val finalDecision = gate.evaluate(finalInput)) {
                    is PaperManualSubmitGateDecision.Blocked -> {
                        val error = finalDecision.reasons.firstOrNull()
                            ?: PaperOrderSubmitError.FEATURE_DISABLED
                        val result = blocked(
                            request,
                            error,
                            finalDecision.reasons.joinToString(),
                        )
                        recordTerminalBestEffort(
                            request,
                            preview,
                            finalInput.marketOpen == true,
                            result,
                        )
                        return@withLock result
                    }
                    PaperManualSubmitGateDecision.Allowed -> Unit
                }

                val result = submitClient.submitOnce(request)
                try {
                    auditRepository.recordResult(
                        request = request,
                        preview = preview,
                        marketOpen = checkedInput.marketOpen == true,
                        result = result,
                    )
                    result
                } catch (_: Exception) {
                    PaperOrderSubmitResult(
                        submitAttemptId = request.submitAttemptId,
                        previewId = request.previewId,
                        status = PaperOrderSubmitStatus.FAILED,
                        alpacaOrderId = result.alpacaOrderId,
                        clientOrderId = request.clientOrderId,
                        submittedAtEpochMillis = clock().toEpochMilli(),
                        errorCode = PaperOrderSubmitError.TERMINAL_AUDIT_FAILED,
                        safeErrorMessage =
                            "Paper response received but terminal audit failed; verify the Paper dashboard. No retry was attempted.",
                    )
                }
            }
        }
    }

    private suspend fun recordTerminalBestEffort(
        request: PaperOrderSubmitRequest,
        preview: PaperOrderPayloadPreview,
        marketOpen: Boolean,
        result: PaperOrderSubmitResult,
    ) {
        try {
            auditRepository.recordResult(request, preview, marketOpen, result)
        } catch (_: Exception) {
            // A local block remains a block. No network request is made from this branch.
        }
    }

    private fun blocked(
        request: PaperOrderSubmitRequest,
        error: PaperOrderSubmitError,
        message: String,
    ): PaperOrderSubmitResult = PaperOrderSubmitResult(
        submitAttemptId = request.submitAttemptId,
        previewId = request.previewId,
        status = PaperOrderSubmitStatus.BLOCKED,
        alpacaOrderId = null,
        clientOrderId = request.clientOrderId,
        submittedAtEpochMillis = clock().toEpochMilli(),
        errorCode = error,
        safeErrorMessage = PaperSubmitSanitizer.safeMessage(message, "Paper submit blocked."),
    )

    private fun failed(
        request: PaperOrderSubmitRequest,
        error: PaperOrderSubmitError,
        message: String,
    ): PaperOrderSubmitResult = PaperOrderSubmitResult(
        submitAttemptId = request.submitAttemptId,
        previewId = request.previewId,
        status = PaperOrderSubmitStatus.FAILED,
        alpacaOrderId = null,
        clientOrderId = request.clientOrderId,
        submittedAtEpochMillis = clock().toEpochMilli(),
        errorCode = error,
        safeErrorMessage = message,
    )
}
