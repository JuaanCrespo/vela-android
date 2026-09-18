package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.BrokerPositionSide
import com.vela.android.lab.data.paper.reconciliation.domain.DecimalQuantity
import java.util.Locale

/** Canonical route only. Never uses the permissive dashboard parser. */
class StrictPaperCaptureParser {
    fun account(body: String?): EvidenceParseResult<ObservedPaperAccount> = parse(body) { root ->
        val fields = (root as? StrictEvidenceJson.Obj)?.fields ?: fail(CaptureDiagnostic.UNEXPECTED_TYPE)
        val rawId = fields["id"]
        val identifier = when (rawId) {
            null, StrictEvidenceJson.Null -> null
            is StrictEvidenceJson.Str -> rawId.text.takeIf {
                it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            } ?: fail(CaptureDiagnostic.INVALID_ACCOUNT)
            else -> fail(CaptureDiagnostic.UNEXPECTED_TYPE)
        }
        val ref = identifier?.let { "paper-v1:" + evidenceDigest("vela-paper-account-ref-v1|" + it.lowercase(Locale.ROOT)) }
        val value = ObservedPaperAccount(ref, optionalDecimal(fields, "cash"), optionalDecimal(fields, "equity"),
            optionalDecimal(fields, "buying_power"), optionalDecimal(fields, "portfolio_value"))
        EvidenceParseResult(value, if (ref == null) CaptureDiagnostic.ACCOUNT_REF_UNKNOWN else CaptureDiagnostic.SUCCESS_COMPLETE)
    }

    fun positions(body: String?): EvidenceParseResult<List<CapturedPosition>> = parse(body) { root ->
        val rows = (root as? StrictEvidenceJson.Arr)?.values ?: fail(CaptureDiagnostic.UNEXPECTED_TYPE)
        val output = arrayListOf<CapturedPosition>()
        val symbols = hashSetOf<String>()
        for (row in rows) {
            try {
                val fields = (row as? StrictEvidenceJson.Obj)?.fields ?: fail(CaptureDiagnostic.INVALID_ROW)
                val rawSymbol = (fields["symbol"] as? StrictEvidenceJson.Str)?.text ?: fail(CaptureDiagnostic.INVALID_SYMBOL)
                val symbol = rawSymbol.uppercase(Locale.ROOT)
                if (!rawSymbol.matches(Regex("^[A-Za-z][A-Za-z0-9.-]{0,31}$")) || !validEvidenceSymbol(symbol)) fail(CaptureDiagnostic.INVALID_SYMBOL)
                if (!symbols.add(symbol)) fail(CaptureDiagnostic.DUPLICATE_SYMBOL)
                val raw = decimalText(fields["qty"])
                val qty = exact(raw)
                val rawSide = (fields["side"] as? StrictEvidenceJson.Str)?.text ?: fail(CaptureDiagnostic.UNEXPECTED_TYPE)
                // REST signed quantity: never infer/repair the sign using side or abs().
                val side = when (rawSide) {
                    "long" -> if (qty > DecimalQuantity.ZERO) BrokerPositionSide.LONG else fail(CaptureDiagnostic.SIDE_CONTRADICTION)
                    "short" -> if (qty < DecimalQuantity.ZERO) BrokerPositionSide.SHORT else fail(CaptureDiagnostic.SIDE_CONTRADICTION)
                    else -> fail(CaptureDiagnostic.SIDE_CONTRADICTION)
                }
                output += CapturedPosition(symbol, side, raw, qty.toString())
            } catch (failure: ParseFailure) {
                return@parse EvidenceParseResult(null, failure.diagnostic, rows.size, output.size)
            }
        }
        EvidenceParseResult(output.toList(), CaptureDiagnostic.SUCCESS_COMPLETE, rows.size, output.size)
    }

    private fun optionalDecimal(fields: Map<String, StrictEvidenceJson.Value>, name: String): String? =
        when (val value = fields[name]) {
            null, StrictEvidenceJson.Null -> null
            else -> exact(decimalText(value)).toString()
        }

    internal fun decimalText(value: StrictEvidenceJson.Value?): String = when (value) {
        null, StrictEvidenceJson.Null -> fail(CaptureDiagnostic.INVALID_QTY)
        is StrictEvidenceJson.Str -> value.text
        else -> fail(CaptureDiagnostic.UNEXPECTED_TYPE)
    }

    private fun exact(text: String): DecimalQuantity {
        if (text in setOf("NaN", "Infinity", "-Infinity", "+Infinity")) fail(CaptureDiagnostic.NONFINITE_NUMBER)
        return try { DecimalQuantity.parse(text) } catch (_: IllegalArgumentException) { fail(CaptureDiagnostic.INVALID_QTY) }
    }

    private fun <T> parse(body: String?, block: (StrictEvidenceJson.Value) -> EvidenceParseResult<T>): EvidenceParseResult<T> {
        if (body.isNullOrBlank()) return EvidenceParseResult(null, CaptureDiagnostic.EMPTY_BODY_INVALID)
        val root = try { StrictEvidenceJson.parse(body) } catch (_: IllegalArgumentException) {
            return EvidenceParseResult(null, CaptureDiagnostic.MALFORMED_JSON)
        }
        return try { block(root) } catch (failure: ParseFailure) { EvidenceParseResult(null, failure.diagnostic) }
    }

    private class ParseFailure(val diagnostic: CaptureDiagnostic) : RuntimeException()
    private fun fail(diagnostic: CaptureDiagnostic): Nothing = throw ParseFailure(diagnostic)
}
