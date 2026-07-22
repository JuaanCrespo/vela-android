package com.vela.android.lab.data.market.source.alpaca

import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Read-only parser for Alpaca Market Data WebSocket messages.
 *
 * The Alpaca stream wire format is always a top-level JSON array
 * of objects, each tagged with a `T` discriminator. This parser
 * never executes side effects, never performs I/O, and never throws
 * on malformed input — invalid JSON resolves to an empty list and
 * unrecognised tagged objects resolve to [AlpacaStreamMessage.Unknown].
 */
class AlpacaStreamMessageParser {

    fun parse(payload: String): List<AlpacaStreamMessage> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rootArray: JSONArray = try {
            JSONArray(trimmed)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<AlpacaStreamMessage>(rootArray.length())
        for (index in 0 until rootArray.length()) {
            val element = rootArray.opt(index) as? JSONObject ?: continue
            out += parseObject(element, raw = element.toString())
        }
        return out
    }

    private fun parseObject(obj: JSONObject, raw: String): AlpacaStreamMessage {
        return when (val tag = obj.optString("T", "")) {
            "success" -> when (obj.optString("msg", "")) {
                "connected" -> AlpacaStreamMessage.Connected
                "authenticated" -> AlpacaStreamMessage.Authenticated
                else -> AlpacaStreamMessage.Unknown(raw)
            }
            "subscription" -> AlpacaStreamMessage.Subscription(
                trades = stringList(obj.optJSONArray("trades")),
                quotes = stringList(obj.optJSONArray("quotes")),
                bars = stringList(obj.optJSONArray("bars")),
            )
            "q" -> parseQuote(obj, raw)
            "b" -> parseBar(obj, raw)
            "error" -> AlpacaStreamMessage.StreamError(
                code = obj.optInt("code", -1),
                message = obj.optString("msg", "(no message)"),
            )
            "" -> AlpacaStreamMessage.Unknown(raw)
            else -> AlpacaStreamMessage.Unknown(tag)
        }
    }

    private fun parseQuote(obj: JSONObject, raw: String): AlpacaStreamMessage {
        val symbol = obj.optString("S", "")
        if (symbol.isEmpty()) return AlpacaStreamMessage.Unknown(raw)
        val ts = parseTimestamp(obj.optString("t", "")) ?: return AlpacaStreamMessage.Unknown(raw)
        return AlpacaStreamMessage.Quote(
            symbol = symbol,
            bidPrice = obj.optDouble("bp", 0.0),
            askPrice = obj.optDouble("ap", 0.0),
            timestamp = ts,
        )
    }

    private fun parseBar(obj: JSONObject, raw: String): AlpacaStreamMessage {
        val symbol = obj.optString("S", "")
        if (symbol.isEmpty()) return AlpacaStreamMessage.Unknown(raw)
        val ts = parseTimestamp(obj.optString("t", "")) ?: return AlpacaStreamMessage.Unknown(raw)
        return AlpacaStreamMessage.Bar(
            symbol = symbol,
            open = obj.optDouble("o", 0.0),
            high = obj.optDouble("h", 0.0),
            low = obj.optDouble("l", 0.0),
            close = obj.optDouble("c", 0.0),
            volume = obj.optDouble("v", 0.0),
            timestamp = ts,
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optString(i, "")
            if (item.isNotEmpty()) out += item
        }
        return out
    }

    private fun parseTimestamp(value: String): Instant? {
        if (value.isEmpty()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
