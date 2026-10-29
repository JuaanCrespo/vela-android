package com.vela.android.lab.core.marketdataset.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DatasetSnapshotSpecTest {
    @Test fun `event time before cutoff is insufficient if availability is later`() {
        assertFalse(spec().includes(event().copy(availableAt = time(181))))
        assertTrue(event().eventTime < spec().availabilityCutoff)
    }
    @Test fun `availability boundary is inclusive at nanosecond resolution`() {
        assertTrue(spec().includes(event().copy(availableAt = time(180))))
        assertFalse(spec().includes(event().copy(availableAt = time(180, 1))))
    }
    @Test fun `future provider event time is structural and cut bounds stay independent`() {
        val observed = event().copy(eventTime = time(240))
        val cut = spec()
        assertTrue(observed.eventTime > cut.availabilityCutoff)
        assertTrue(observed.availableAt <= cut.availabilityCutoff)
        assertTrue(cut.includes(observed))
        assertFalse(cut.copy(eventTimeRange = MarketTimeRange(time(0), cut.availabilityCutoff)).includes(observed))
        assertFalse(cut.includes(observed.copy(availableAt = time(181))))
    }
    @Test fun `10 01 original visible at 10 03 but 10 05 correction not visible`() {
        val start = time(36000)
        val firstEvent = event().copy(eventTime = start, receivedAt = time(36060), availableAt = time(36060))
        val original = bar().copy(identity = bar().identity.copy(startTime = start), endTime = time(36060),
            receivedAt = time(36060), availableAt = time(36060), evidence = BarEvidence(listOf(firstEvent.reference()), emptyList()))
        val correction = original.copy(receivedAt = time(36300), availableAt = time(36300),
            finality = BarFinality.REVISED, revision = BarRevision(1, original.contentFingerprint),
            evidence = BarEvidence(listOf(firstEvent.copy(receivedAt = time(36300), availableAt = time(36300), ingestPosition = position(8)).reference()), listOf(BarQualityFlag.LATE)))
        val cut = spec().copy(eventTimeRange = MarketTimeRange(start, time(36600)), availabilityCutoff = time(36180))
        assertTrue(cut.includes(original)); assertFalse(cut.includes(correction)); assertEquals(original.identity, correction.identity)
        assertTrue(cut.copy(availabilityCutoff = time(36300)).includes(correction))
    }
    @Test fun `causal highwater excludes later sequence and unknown session`() {
        assertTrue(spec().includes(event().copy(ingestPosition = position(8))))
        assertFalse(spec().includes(event().copy(ingestPosition = position(9))))
        assertFalse(spec().includes(event().copy(ingestPosition = position(0, "unknown-session"))))
        assertFalse(spec().includes(bar().copy(evidence = BarEvidence(listOf(event().copy(ingestPosition = position(9)).reference()), emptyList()))))
    }
    @Test fun `event range uses half open endpoints`() {
        assertTrue(spec().includes(event().copy(eventTime = time(0))))
        assertFalse(spec().includes(event().copy(eventTime = time(-1))))
        assertFalse(spec().includes(event().copy(eventTime = time(360))))
    }
    @Test fun `bar must be entirely inside event range`() {
        assertTrue(spec().includes(bar()))
        assertFalse(spec().copy(eventTimeRange = MarketTimeRange(time(0), time(59))).includes(bar()))
    }
    @Test fun `source and instrument and version scopes enforced`() {
        assertFalse(spec().includes(event().copy(source = iex.copy(sourceClass = MarketSourceClass.UNKNOWN))))
        assertFalse(spec().includes(event().copy(instrument = instrument().copy(providerSymbol = ProviderMarketSymbol("spy")))))
        assertFalse(spec().includes(event().copy(parserVersion = ParserVersion(2))))
        assertFalse(spec().includes(event().copy(schemaVersion = DatasetSchemaVersion(2))))
        assertFalse(spec().includes(bar().copy(canonicalizationVersion = CanonicalizationVersion(2))))
        assertFalse(spec().includes(bar().copy(schemaVersion = DatasetSchemaVersion(2))))
    }
    @Test fun `spec identity changes for each cut dimension`() {
        val a = spec()
        val variants = listOf(a.copy(datasetId = DatasetId("other")), a.copy(schemaVersion = DatasetSchemaVersion(2)),
            a.copy(parserVersion = ParserVersion(2)), a.copy(canonicalizationVersion = CanonicalizationVersion(2)),
            a.copy(qualityPolicyVersion = QualityPolicyVersion(2)), a.copy(availabilityCutoff = time(180, 1)),
            a.copy(eventTimeRange = MarketTimeRange(time(0), time(361))),
            a.copy(scope = DatasetSnapshotScope(listOf(iex), listOf(instrument()), listOf(position(9)))))
        variants.forEach { assertNotEquals(a.specFingerprint, it.specFingerprint) }
        assertEquals(variants.size, variants.map { it.specFingerprint }.distinct().size)
    }
    @Test fun `bar lineage cannot bypass raw parser or schema scope`() {
        val changedParser = event().copy(parserVersion = ParserVersion(2)).reference()
        val changedSchema = event().copy(schemaVersion = DatasetSchemaVersion(2)).reference()
        assertEquals(ParserVersion(2), changedParser.parserVersion)
        assertEquals(DatasetSchemaVersion(2), changedSchema.schemaVersion)
        assertFalse(spec().includes(bar().copy(evidence = BarEvidence(listOf(changedParser), emptyList()))))
        assertFalse(spec().includes(bar().copy(evidence = BarEvidence(listOf(changedSchema), emptyList()))))
        assertFalse(spec().includes(bar().copy(evidence = BarEvidence(listOf(event().reference(), changedParser), emptyList()))))
        assertNotEquals(event().reference().canonicalText(), event().reference().copy(parserVersion = ParserVersion(2)).canonicalText())
        assertNotEquals(event().reference().canonicalText(), event().reference().copy(schemaVersion = DatasetSchemaVersion(2)).canonicalText())
    }
    @Test fun `scope canonical order is stable and caller cannot mutate it`() {
        val sources = mutableListOf(iex, iex.copy(sourceClass = MarketSourceClass.UNKNOWN))
        val instruments = mutableListOf(instrument()); val hwm = mutableListOf(position(8, "B"), position(10, "A"))
        val a = DatasetSnapshotScope(sources, instruments, hwm)
        val b = DatasetSnapshotScope(sources.reversed(), instruments, hwm.reversed())
        sources.clear(); instruments.clear(); hwm.clear()
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode()); assertEquals(a.canonicalText(), b.canonicalText())
        assertEquals(spec().copy(scope = a).specFingerprint, spec().copy(scope = b).specFingerprint)
        assertThrows(UnsupportedOperationException::class.java) { (a.sources as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (a.instruments as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (a.highWater as MutableList).clear() }
    }
    @Test fun `scope cannot silently resolve conflicting session highwater`() {
        invalid(DatasetValidationError.INVALID_SCOPE) { DatasetSnapshotScope(listOf(iex), listOf(instrument()), listOf(position(7), position(8))) }
        invalid(DatasetValidationError.INVALID_SCOPE) { DatasetSnapshotScope(emptyList(), listOf(instrument()), listOf(position())) }
        invalid(DatasetValidationError.INVALID_SCOPE) { DatasetSnapshotScope(listOf(iex), emptyList(), listOf(position())) }
        invalid(DatasetValidationError.INVALID_SCOPE) { DatasetSnapshotScope(listOf(iex), listOf(instrument()), emptyList()) }
        invalid(DatasetValidationError.INVALID_SCOPE) { DatasetSnapshotScope(listOf(iex), listOf(instrument(MarketFeedId.TEST)), listOf(position())) }
    }
    @Test fun `all snapshot scope dimensions are independent of insertion order`() {
        val testSource = iex.copy(feed = MarketFeedId.TEST, sourceClass = MarketSourceClass.SYNTHETIC)
        val otherInstrument = instrument().copy(canonicalSymbol = CanonicalMarketSymbol("QQQ"), providerSymbol = ProviderMarketSymbol("QQQ"))
        val sources = listOf(iex, testSource)
        val instruments = listOf(instrument(), otherInstrument, instrument(MarketFeedId.TEST))
        val highWater = listOf(position(8, "A"), position(12, "B"), position(0, "C"))
        val first = spec().copy(scope = DatasetSnapshotScope(sources, instruments, highWater))
        for (sourceOrder in listOf(sources, sources.reversed())) {
            for (instrumentOrder in listOf(instruments, instruments.reversed(), instruments.drop(1) + instruments.first())) {
                for (waterOrder in listOf(highWater, highWater.reversed(), highWater.drop(1) + highWater.first())) {
                    val reordered = spec().copy(scope = DatasetSnapshotScope(sourceOrder, instrumentOrder, waterOrder))
                    assertEquals(first, reordered)
                    assertEquals(first.hashCode(), reordered.hashCode())
                    assertEquals(first.canonicalText(), reordered.canonicalText())
                    assertEquals(first.specFingerprint, reordered.specFingerprint)
                }
            }
        }
    }
    @Test fun `cut membership never grants training authority`() {
        assertTrue(spec().includes(bar().copy(finality = BarFinality.PROVISIONAL))) // Prerequisites, not quality/finality selection.
    }
    @Test fun `explicit scope cannot promote quarantined provenance`() {
        MarketSourceClass.entries.filter { it != MarketSourceClass.REAL_PROVIDER }.forEach { sourceClass ->
            val source = iex.copy(sourceClass = sourceClass)
            val raw = event().copy(source = source)
            val canonical = bar().copy(identity = bar().identity.copy(source = source), evidence = BarEvidence(listOf(raw.reference()), emptyList()))
            val cut = spec().copy(scope = DatasetSnapshotScope(listOf(source), listOf(instrument()), listOf(position(8))))
            assertFalse(DatasetSourcePolicy.isCandidate(instrument(), source))
            assertFalse(cut.includes(raw)); assertFalse(cut.includes(canonical))
        }
    }
    @Test fun `real provider label cannot promote TEST feed`() {
        val source = iex.copy(feed = MarketFeedId.TEST)
        val testInstrument = instrument(MarketFeedId.TEST)
        val raw = event().copy(source = source, instrument = testInstrument)
        val canonical = bar().copy(identity = bar().identity.copy(source = source, instrument = testInstrument), evidence = BarEvidence(listOf(raw.reference()), emptyList()))
        val cut = spec().copy(scope = DatasetSnapshotScope(listOf(source), listOf(testInstrument), listOf(position(8))))
        assertFalse(cut.includes(raw)); assertFalse(cut.includes(canonical))
    }
}
