package com.vela.android.lab.data.market.source.alpaca

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlpacaStreamMessageParserTest {

    private val parser = AlpacaStreamMessageParser()

    @Test
    fun `parses success connected message`() {
        val msgs = parser.parse("""[{"T":"success","msg":"connected"}]""")
        assertEquals(listOf(AlpacaStreamMessage.Connected), msgs)
    }

    @Test
    fun `parses success authenticated message`() {
        val msgs = parser.parse("""[{"T":"success","msg":"authenticated"}]""")
        assertEquals(listOf(AlpacaStreamMessage.Authenticated), msgs)
    }

    @Test
    fun `parses subscription confirmation`() {
        val msgs = parser.parse(
            """[{"T":"subscription","trades":[],"quotes":["FAKEPACA"],"bars":["FAKEPACA"]}]""",
        )
        assertEquals(1, msgs.size)
        val sub = msgs.single() as AlpacaStreamMessage.Subscription
        assertEquals(emptyList<String>(), sub.trades)
        assertEquals(listOf("FAKEPACA"), sub.quotes)
        assertEquals(listOf("FAKEPACA"), sub.bars)
    }

    @Test
    fun `parses quote message for FAKEPACA`() {
        val msgs = parser.parse(
            """[{"T":"q","S":"FAKEPACA","bp":0.99,"ap":1.01,"t":"2026-01-01T14:30:00Z"}]""",
        )
        val quote = msgs.single() as AlpacaStreamMessage.Quote
        assertEquals("FAKEPACA", quote.symbol)
        assertEquals(0.99, quote.bidPrice)
        assertEquals(1.01, quote.askPrice)
        assertEquals(Instant.parse("2026-01-01T14:30:00Z"), quote.timestamp)
    }

    @Test
    fun `parses bar message for FAKEPACA`() {
        val msgs = parser.parse(
            """[{"T":"b","S":"FAKEPACA","o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":100,"t":"2026-01-01T14:30:00Z"}]""",
        )
        val bar = msgs.single() as AlpacaStreamMessage.Bar
        assertEquals("FAKEPACA", bar.symbol)
        assertEquals(1.0, bar.open)
        assertEquals(1.2, bar.high)
        assertEquals(0.9, bar.low)
        assertEquals(1.1, bar.close)
        assertEquals(100.0, bar.volume)
        assertEquals(Instant.parse("2026-01-01T14:30:00Z"), bar.timestamp)
    }

    @Test
    fun `parses real stock bar for SPY`() {
        val msgs = parser.parse(
            """[{"T":"b","S":"SPY","o":520.10,"h":521.40,"l":519.80,"c":520.95,"v":12500,"t":"2026-06-03T14:31:00Z"}]""",
        )
        val bar = msgs.single() as AlpacaStreamMessage.Bar
        assertEquals("SPY", bar.symbol)
        assertEquals(520.10, bar.open)
        assertEquals(521.40, bar.high)
        assertEquals(519.80, bar.low)
        assertEquals(520.95, bar.close)
        assertEquals(12500.0, bar.volume)
        assertEquals(Instant.parse("2026-06-03T14:31:00Z"), bar.timestamp)
    }

    @Test
    fun `parses SPY quote message`() {
        val msgs = parser.parse(
            """[{"T":"q","S":"SPY","bp":520.95,"ap":521.05,"t":"2026-06-03T14:31:00Z"}]""",
        )
        val quote = msgs.single() as AlpacaStreamMessage.Quote
        assertEquals("SPY", quote.symbol)
        assertEquals(520.95, quote.bidPrice)
        assertEquals(521.05, quote.askPrice)
        assertEquals(Instant.parse("2026-06-03T14:31:00Z"), quote.timestamp)
    }

    @Test
    fun `parses error message`() {
        val msgs = parser.parse(
            """[{"T":"error","code":401,"msg":"auth failed"}]""",
        )
        val err = msgs.single() as AlpacaStreamMessage.StreamError
        assertEquals(401, err.code)
        assertEquals("auth failed", err.message)
    }

    @Test
    fun `parses multiple messages in one envelope`() {
        val msgs = parser.parse(
            """[
            |  {"T":"success","msg":"connected"},
            |  {"T":"b","S":"FAKEPACA","o":1.0,"h":1.0,"l":1.0,"c":1.0,"v":1,"t":"2026-01-01T14:30:00Z"}
            |]""".trimMargin(),
        )
        assertEquals(2, msgs.size)
        assertTrue(msgs[0] is AlpacaStreamMessage.Connected)
        assertTrue(msgs[1] is AlpacaStreamMessage.Bar)
    }

    @Test
    fun `invalid JSON resolves to empty list, not exception`() {
        val msgs = parser.parse("this is not json")
        assertEquals(emptyList<AlpacaStreamMessage>(), msgs)
    }

    @Test
    fun `empty payload resolves to empty list`() {
        assertEquals(emptyList<AlpacaStreamMessage>(), parser.parse(""))
        assertEquals(emptyList<AlpacaStreamMessage>(), parser.parse("   "))
    }

    @Test
    fun `unknown tag becomes Unknown variant`() {
        val msgs = parser.parse("""[{"T":"weather","msg":"sunny"}]""")
        assertTrue(msgs.single() is AlpacaStreamMessage.Unknown)
    }

    @Test
    fun `bar missing timestamp is downgraded to Unknown`() {
        val msgs = parser.parse(
            """[{"T":"b","S":"FAKEPACA","o":1.0,"h":1.0,"l":1.0,"c":1.0,"v":1}]""",
        )
        assertTrue(msgs.single() is AlpacaStreamMessage.Unknown)
    }

    @Test
    fun `bar missing symbol is downgraded to Unknown`() {
        val msgs = parser.parse(
            """[{"T":"b","o":1.0,"h":1.0,"l":1.0,"c":1.0,"v":1,"t":"2026-01-01T14:30:00Z"}]""",
        )
        assertTrue(msgs.single() is AlpacaStreamMessage.Unknown)
    }
}
