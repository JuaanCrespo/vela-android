package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.BrokerPositionSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrictPaperCaptureParserTest {

    private val parser = StrictPaperCaptureParser()
    private val uuidA = "11111111-1111-1111-1111-111111111111"
    private val uuidB = "22222222-2222-2222-2222-222222222222"

    private fun position(symbol: String = "SPY", side: String = "long", qty: String = "1"): String =
        """{"symbol":"$symbol","side":"$side","qty":"$qty"}"""

    // §9 empty body vs empty array

    @Test
    fun `empty JSON array is COMPLETE zero-row portfolio`() {
        val result = parser.positions("[]")
        assertTrue(result.valid)
        assertEquals(CaptureDiagnostic.SUCCESS_COMPLETE, result.diagnostic)
        assertEquals(emptyList<CapturedPosition>(), result.value)
        assertEquals(0, result.receivedCount)
        assertEquals(0, result.validatedCount)
    }

    @Test
    fun `empty body is invalid not empty portfolio`() {
        val result = parser.positions("")
        assertEquals(CaptureDiagnostic.EMPTY_BODY_INVALID, result.diagnostic)
        assertNull(result.value)
    }

    @Test
    fun `whitespace-only body is invalid`() {
        val result = parser.positions("   \n\t ")
        assertEquals(CaptureDiagnostic.EMPTY_BODY_INVALID, result.diagnostic)
        assertNull(result.value)
    }

    @Test
    fun `null body is invalid`() {
        val result = parser.positions(null)
        assertEquals(CaptureDiagnostic.EMPTY_BODY_INVALID, result.diagnostic)
        assertNull(result.value)
    }

    @Test
    fun `malformed JSON is diagnosed`() {
        val result = parser.positions("[{broken")
        assertEquals(CaptureDiagnostic.MALFORMED_JSON, result.diagnostic)
        assertNull(result.value)
    }

    @Test
    fun `root object instead of array is UNEXPECTED_TYPE`() {
        val result = parser.positions("""{"symbol":"SPY"}""")
        assertEquals(CaptureDiagnostic.UNEXPECTED_TYPE, result.diagnostic)
    }

    // §10 invalid rows

    @Test
    fun `non-object row fails whole snapshot even when other rows are valid`() {
        val result = parser.positions("""[${position("SPY")},"not-object",${position("AAPL")}]""")
        assertFalse(result.valid)
        assertEquals(CaptureDiagnostic.INVALID_ROW, result.diagnostic)
        assertNull(result.value)
    }

    @Test
    fun `missing symbol fails`() {
        val result = parser.positions("""[{"side":"long","qty":"1"}]""")
        assertEquals(CaptureDiagnostic.INVALID_SYMBOL, result.diagnostic)
    }

    @Test
    fun `blank symbol fails`() {
        val result = parser.positions("""[{"symbol":"","side":"long","qty":"1"}]""")
        assertEquals(CaptureDiagnostic.INVALID_SYMBOL, result.diagnostic)
    }

    @Test
    fun `symbol with whitespace fails`() {
        val result = parser.positions("""[{"symbol":"SP Y","side":"long","qty":"1"}]""")
        assertEquals(CaptureDiagnostic.INVALID_SYMBOL, result.diagnostic)
    }

    // §11 duplicate symbol

    @Test
    fun `duplicate symbol case-insensitive is rejected`() {
        val result = parser.positions("""[${position("SPY")},${position("spy")}]""")
        assertEquals(CaptureDiagnostic.DUPLICATE_SYMBOL, result.diagnostic)
    }

    // §13 qty validation

    @Test
    fun `missing qty is INVALID_QTY`() {
        val result = parser.positions("""[{"symbol":"SPY","side":"long"}]""")
        assertEquals(CaptureDiagnostic.INVALID_QTY, result.diagnostic)
    }

    @Test
    fun `numeric qty instead of string is UNEXPECTED_TYPE`() {
        val result = parser.positions("""[{"symbol":"SPY","side":"long","qty":1}]""")
        assertEquals(CaptureDiagnostic.UNEXPECTED_TYPE, result.diagnostic)
    }

    @Test
    fun `NaN qty is nonfinite`() {
        val result = parser.positions("""[{"symbol":"SPY","side":"long","qty":"NaN"}]""")
        assertEquals(CaptureDiagnostic.NONFINITE_NUMBER, result.diagnostic)
    }

    @Test
    fun `Infinity qty is nonfinite`() {
        val result = parser.positions("""[{"symbol":"SPY","side":"long","qty":"Infinity"}]""")
        assertEquals(CaptureDiagnostic.NONFINITE_NUMBER, result.diagnostic)
    }

    @Test
    fun `invalid qty text is INVALID_QTY`() {
        val result = parser.positions("""[{"symbol":"SPY","side":"long","qty":"abc"}]""")
        assertEquals(CaptureDiagnostic.INVALID_QTY, result.diagnostic)
    }

    // Decimal exact round-trip: raw text preserved, canonical stripped

    @Test
    fun `decimal 1 canonical is 1`() {
        val row = parser.positions("""[${position(qty = "1")}]""").value!!.single()
        assertEquals("1", row.qtyRawDecimal)
        assertEquals("1", row.qtyCanonicalDecimal)
    }

    @Test
    fun `decimal 1_00 raw preserved canonical stripped`() {
        val row = parser.positions("""[${position(qty = "1.00")}]""").value!!.single()
        assertEquals("1.00", row.qtyRawDecimal)
        assertEquals("1", row.qtyCanonicalDecimal)
    }

    @Test
    fun `decimal 0_1 canonical is 0_1`() {
        val row = parser.positions("""[${position(qty = "0.1")}]""").value!!.single()
        assertEquals("0.1", row.qtyRawDecimal)
        assertEquals("0.1", row.qtyCanonicalDecimal)
    }

    @Test
    fun `very small decimal parses exactly`() {
        val row = parser.positions("""[${position(qty = "0.000001")}]""").value!!.single()
        assertEquals("0.000001", row.qtyRawDecimal)
        assertEquals("0.000001", row.qtyCanonicalDecimal)
    }

    // Side semantics: signed quantity, never reconstructed

    @Test
    fun `long side with positive qty is LONG`() {
        val row = parser.positions("""[${position(qty = "5")}]""").value!!.single()
        assertEquals(BrokerPositionSide.LONG, row.side)
    }

    @Test
    fun `short side with negative qty is SHORT`() {
        val row = parser.positions("""[${position(side = "short", qty = "-5")}]""").value!!.single()
        assertEquals(BrokerPositionSide.SHORT, row.side)
    }

    @Test
    fun `long side with negative qty is SIDE_CONTRADICTION`() {
        val result = parser.positions("""[${position(side = "long", qty = "-5")}]""")
        assertEquals(CaptureDiagnostic.SIDE_CONTRADICTION, result.diagnostic)
    }

    @Test
    fun `short side with positive qty is SIDE_CONTRADICTION`() {
        val result = parser.positions("""[${position(side = "short", qty = "5")}]""")
        assertEquals(CaptureDiagnostic.SIDE_CONTRADICTION, result.diagnostic)
    }

    @Test
    fun `long side with zero qty is SIDE_CONTRADICTION`() {
        val result = parser.positions("""[${position(side = "long", qty = "0")}]""")
        assertEquals(CaptureDiagnostic.SIDE_CONTRADICTION, result.diagnostic)
    }

    @Test
    fun `unknown side is SIDE_CONTRADICTION`() {
        val result = parser.positions("""[${position(side = "flat", qty = "1")}]""")
        assertEquals(CaptureDiagnostic.SIDE_CONTRADICTION, result.diagnostic)
    }

    // Extra unknown fields do not affect valid parse

    @Test
    fun `unknown extra fields are ignored on positions row`() {
        val body = """[{"symbol":"SPY","side":"long","qty":"1","market_value":"999","exchange":"NYSE"}]"""
        val row = parser.positions(body).value!!.single()
        assertEquals("SPY", row.symbol)
        assertEquals("1", row.qtyCanonicalDecimal)
    }

    @Test
    fun `multiple valid positions parse in order retaining raw decimals`() {
        val body = """[${position("SPY", qty = "1.000")},${position("AAPL", qty = "0.5")}]"""
        val rows = parser.positions(body).value!!
        assertEquals(2, rows.size)
        assertEquals("SPY", rows[0].symbol)
        assertEquals("1.000", rows[0].qtyRawDecimal)
        assertEquals("1", rows[0].qtyCanonicalDecimal)
        assertEquals("AAPL", rows[1].symbol)
        assertEquals("0.5", rows[1].qtyRawDecimal)
        assertEquals("0.5", rows[1].qtyCanonicalDecimal)
    }

    // §14 §15 §16 account parser + accountRef derivation

    @Test
    fun `account with stable id derives paper-v1 sha256 accountRef`() {
        val result = parser.account("""{"id":"$uuidA","cash":"1000.00","equity":"1000","buying_power":"2000","portfolio_value":"1000"}""")
        assertTrue(result.valid)
        val ref = result.value!!.accountRef!!
        assertTrue(ref.startsWith("paper-v1:"), "accountRef must be domain-tagged: $ref")
        assertTrue(ref.matches(Regex("paper-v1:[0-9a-f]{64}")), "accountRef must be hash: $ref")
        assertFalse(ref.contains(uuidA), "raw id must not appear in accountRef")
    }

    @Test
    fun `same broker id yields same accountRef`() {
        val a = parser.account("""{"id":"$uuidA"}""").value!!.accountRef
        val b = parser.account("""{"id":"$uuidA"}""").value!!.accountRef
        assertEquals(a, b)
    }

    @Test
    fun `same broker id in different case yields same accountRef`() {
        val lower = parser.account("""{"id":"$uuidA"}""").value!!.accountRef
        val upper = parser.account("""{"id":"${uuidA.uppercase()}"}""").value!!.accountRef
        assertEquals(lower, upper)
    }

    @Test
    fun `different broker id yields different accountRef`() {
        val a = parser.account("""{"id":"$uuidA"}""").value!!.accountRef
        val b = parser.account("""{"id":"$uuidB"}""").value!!.accountRef
        assertNotEquals(a, b)
    }

    @Test
    fun `missing id yields ACCOUNT_REF_UNKNOWN with null ref`() {
        val result = parser.account("""{"cash":"1000"}""")
        assertEquals(CaptureDiagnostic.ACCOUNT_REF_UNKNOWN, result.diagnostic)
        assertNull(result.value!!.accountRef)
    }

    @Test
    fun `null id yields ACCOUNT_REF_UNKNOWN`() {
        val result = parser.account("""{"id":null}""")
        assertEquals(CaptureDiagnostic.ACCOUNT_REF_UNKNOWN, result.diagnostic)
        assertNull(result.value!!.accountRef)
    }

    @Test
    fun `non-uuid id is INVALID_ACCOUNT`() {
        val result = parser.account("""{"id":"not-a-uuid"}""")
        assertEquals(CaptureDiagnostic.INVALID_ACCOUNT, result.diagnostic)
    }

    @Test
    fun `account non-object root is UNEXPECTED_TYPE`() {
        val result = parser.account("""["array"]""")
        assertEquals(CaptureDiagnostic.UNEXPECTED_TYPE, result.diagnostic)
    }

    @Test
    fun `missing observational fields yield null not zero`() {
        val result = parser.account("""{"id":"$uuidA"}""")
        val account = result.value!!
        assertNull(account.cash)
        assertNull(account.equity)
        assertNull(account.buyingPower)
        assertNull(account.portfolioValue)
    }

    @Test
    fun `observational fields normalize decimals`() {
        val result = parser.account("""{"id":"$uuidA","cash":"1000.00","equity":"1000.5"}""")
        val account = result.value!!
        assertEquals("1000", account.cash)
        assertEquals("1000.5", account.equity)
    }

    @Test
    fun `account empty body invalid`() {
        assertEquals(CaptureDiagnostic.EMPTY_BODY_INVALID, parser.account("").diagnostic)
    }

    @Test
    fun `account malformed json`() {
        assertEquals(CaptureDiagnostic.MALFORMED_JSON, parser.account("{not-json").diagnostic)
    }

    // Row order preserved deterministically, and dropping a valid row cannot happen silently

    @Test
    fun `invalid row does not leak preceding valid rows into COMPLETE snapshot`() {
        val body = """[${position("SPY")},{"symbol":"AAPL"}]"""
        val result = parser.positions(body)
        assertFalse(result.valid)
        assertEquals(CaptureDiagnostic.INVALID_QTY, result.diagnostic)
        assertNull(result.value)
    }
}
