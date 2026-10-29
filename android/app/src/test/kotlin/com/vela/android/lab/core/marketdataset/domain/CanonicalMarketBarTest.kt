package com.vela.android.lab.core.marketdataset.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CanonicalMarketBarTest {
    @Test fun `flat positive zero-volume bar valid`() {
        val b = bar().copy(open = decimal("1"), high = decimal("1"), low = decimal("1"), close = decimal("1"), volume = decimal("0"), tradeCount = 0)
        assertEquals("0", b.volume.toString())
    }
    @Test fun `all OHLC inequalities enforced`() {
        for (bad in listOf<() -> Unit>({ bar().copy(high = decimal("98")) }, { bar().copy(high = decimal("99.5")) },
            { bar().copy(high = decimal("100")) }, { bar().copy(low = decimal("101.5")) }, { bar().copy(low = decimal("100.5")) })) {
            invalid(DatasetValidationError.INVALID_BAR, bad)
        }
    }
    @Test fun `every OHLC field must be strictly positive`() {
        for (text in listOf("0", "-1")) {
            val d = decimal(text)
            listOf<() -> Unit>({ bar().copy(open = d) }, { bar().copy(high = d) }, { bar().copy(low = d) }, { bar().copy(close = d) }).forEach {
                invalid(DatasetValidationError.INVALID_BAR, it)
            }
        }
    }
    @Test fun `negative volume count and invalid VWAP rejected`() {
        invalid(DatasetValidationError.INVALID_PAYLOAD) { bar().copy(volume = decimal("-1")) }
        invalid(DatasetValidationError.INVALID_BAR) { bar().copy(tradeCount = -1) }
        invalid(DatasetValidationError.INVALID_PAYLOAD) { bar().copy(vwap = decimal("0")) }
    }
    @Test fun `missing provider values remain missing and distinct from zero`() {
        val absent = bar().copy(volume = null)
        assertNull(absent.volume); assertNull(absent.tradeCount); assertNull(absent.vwap)
        assertNotEquals(absent.contentFingerprint, absent.copy(volume = decimal("0")).contentFingerprint)
        assertNotEquals(absent.contentFingerprint, absent.copy(tradeCount = 0).contentFingerprint)
    }
    @Test fun `interval duration boundaries and nanos are enforced`() {
        invalid(DatasetValidationError.INVALID_INTERVAL) { bar().copy(endTime = time(0)) }
        invalid(DatasetValidationError.INVALID_INTERVAL) { bar().copy(endTime = time(61)) }
        invalid(DatasetValidationError.INVALID_INTERVAL) { bar().copy(endTime = time(60, 1)) }
        invalid(DatasetValidationError.INVALID_INTERVAL) { bar().identity.copy(startTime = time(1)) }
    }
    @Test fun `revision zero and one share logical identity not revision identity`() {
        val original = bar()
        val revised = original.copy(revision = BarRevision(1, original.contentFingerprint), finality = BarFinality.REVISED)
        assertEquals(original.identity, revised.identity); assertNotEquals(original.revisionIdentity, revised.revisionIdentity)
        assertNotEquals(original.contentFingerprint, revised.contentFingerprint); assertEquals(0L, original.revision.number)
    }
    @Test fun `revision parent constraints reject invented first revised version`() {
        invalid(DatasetValidationError.INVALID_REVISION) { BarRevision(-1, null) }
        invalid(DatasetValidationError.INVALID_REVISION) { BarRevision(0, bar().contentFingerprint) }
        invalid(DatasetValidationError.INVALID_REVISION) { BarRevision(1, null) }
        invalid(DatasetValidationError.INVALID_REVISION) { bar().copy(finality = BarFinality.REVISED) }
    }
    @Test fun `conflicting content at same revision key is detectable`() {
        val a = bar(); val b = a.copy(close = decimal("100"))
        assertEquals(a.revisionIdentity, b.revisionIdentity); assertNotEquals(a.contentFingerprint, b.contentFingerprint)
        assertNotEquals(a, b)
    }
    @Test fun `canonical scale equality does not alter bar fingerprint`() {
        val a = bar(); val b = a.copy(open = decimal("100.000"))
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode()); assertEquals(a.contentFingerprint, b.contentFingerprint)
    }
    @Test fun `schema and canonicalization versions change revision and content identity`() {
        val original = bar()
        val variants = listOf(original.copy(schemaVersion = DatasetSchemaVersion(2)),
            original.copy(canonicalizationVersion = CanonicalizationVersion(2)))
        for (changed in variants) {
            assertEquals(original.identity, changed.identity)
            assertNotEquals(original.revisionIdentity, changed.revisionIdentity)
            assertNotEquals(original.contentFingerprint, changed.contentFingerprint)
        }
        assertNotEquals(variants[0].revisionIdentity, variants[1].revisionIdentity)
        assertNotEquals(variants[0].contentFingerprint, variants[1].contentFingerprint)
    }
    @Test fun `source lineage must match and cannot be available later than result`() {
        val alien = event().copy(instrument = instrument(MarketFeedId.TEST), source = iex.copy(feed = MarketFeedId.TEST)).reference()
        invalid(DatasetValidationError.SOURCE_MISMATCH) { bar().copy(evidence = BarEvidence(listOf(alien), emptyList())) }
        val late = event().copy(receivedAt = time(70), availableAt = time(80)).reference()
        invalid(DatasetValidationError.INVALID_AVAILABILITY) { bar().copy(evidence = BarEvidence(listOf(late), emptyList())) }
    }
    @Test fun `lineage is nonempty unique defensively copied and unmodifiable`() {
        invalid(DatasetValidationError.INVALID_LINEAGE) { BarEvidence(emptyList(), emptyList()) }
        invalid(DatasetValidationError.INVALID_LINEAGE) { BarEvidence(listOf(event().reference(), event().reference()), emptyList()) }
        val refs = mutableListOf(event().reference()); val flags = mutableListOf(BarQualityFlag.UNKNOWN)
        val evidence = BarEvidence(refs, flags); refs.clear(); flags.clear()
        assertEquals(1, evidence.references.size); assertEquals(listOf(BarQualityFlag.UNKNOWN), evidence.qualityFlags)
        assertThrows(UnsupportedOperationException::class.java) { (evidence.references as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (evidence.qualityFlags as MutableList).clear() }
    }
    @Test fun `lineage and flags serialization independent of input order`() {
        val refs = listOf(event().reference(), event().copy(ingestPosition = position(8)).reference())
        val flags = listOf(BarQualityFlag.PARTIAL, BarQualityFlag.LATE)
        val a = BarEvidence(refs, flags); val b = BarEvidence(refs.reversed(), flags.reversed())
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode()); assertEquals(a.canonicalText(), b.canonicalText())
        assertEquals(bar().copy(evidence = a).contentFingerprint, bar().copy(evidence = b).contentFingerprint)
    }
    @Test fun `valid invariant boundaries property`() {
        repeat(100) { n ->
            val p = decimal((n + 1).toString())
            assertNotNull(bar().copy(open = p, high = p, low = p, close = p))
        }
    }
}
