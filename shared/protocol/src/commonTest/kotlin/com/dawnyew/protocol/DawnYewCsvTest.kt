package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlin.test.*

class DawnYewCsvTest {
    private var next=0
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++next).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private val engine=DomainEngine(env)
    private val state=engine.initialState()
    private val ledger=state.ledgers.single().id
    private val sourceDataset=env.id()
    private val sourceEntry=env.id()
    private fun record(amount:String="12.34",category:String="餐饮",note:String="午餐")=CsvPhysicalRecord(listOf(sourceDataset,sourceEntry,"EXPENSE",amount,"2026-10-01T10:00:00","'日常","'$category","'$note"),1,3,4)
    private fun doc(record:CsvPhysicalRecord=record())=DawnYewCsvDocument(DocumentFacts("text/csv",DocumentContainer.CSV,4,Sha256.hex(record.cells.joinToString().encodeToByteArray()),1),listOf(record))
    private fun context()=PreviewContext(env.id(),env.id(),0,DAWNYEW_CSV_ADAPTER_ID,"1")
    private fun preview(d:DawnYewCsvDocument,s:LedgerState=state):ImportPreview {
        val prepared=DawnYewCsv.prepareImport(d,ledger,s,env)
        return ImportPreviewer.preview(prepared.facts,context(),prepared.parsed,prepared.mapping,ImportSelection(prepared.defaultSelectedRows),s,env)
    }

    @Test fun logicalRecordsAreIndependentOfPhysicalLinesAndReuseExistingCategories() {
        val prepared=DawnYewCsv.prepareImport(doc(),ledger,state,env)
        assertEquals(4,prepared.facts.physicalRowCount);assertEquals(1,prepared.facts.previewRecordCount)
        assertTrue(prepared.mapping.newCategories.isEmpty())
        val p=ImportPreviewer.preview(prepared.facts,context(),prepared.parsed,prepared.mapping,ImportSelection(setOf(1)),state,env)
        assertTrue(p.canCommit,p.problems.joinToString());assertEquals(1234L,p.impact.expense)
        assertEquals(1,p.plan!!.rows.single().rowNumber)
    }
    @Test fun csvSourceIdentityDeduplicatesAndChangedContentConflicts() {
        val first=preview(doc());assertTrue(first.canCommit,first.problems.joinToString())
        val saved=engine.apply(state,Command.CommitImport(first.plan!!),env.id()).state
        val source=saved.sourceIdentities.single()
        assertEquals(listOf("DAWNYEW_CSV",sourceDataset,"EXPENSE",sourceEntry),source.identityKey())
        val duplicate=preview(doc(),saved);assertTrue(duplicate.canCommit,duplicate.problems.joinToString());assertEquals(0L,duplicate.impact.addedCount)
        assertEquals(1L,duplicate.counts.getValue(PreviewDisposition.DUPLICATE))
        val changed=preview(doc(record("12.35")),saved);assertFalse(changed.canCommit);assertEquals(1L,changed.counts.getValue(PreviewDisposition.CONFLICT))
        assertTrue(engine.apply(saved,Command.UndoImport(first.plan!!.batchId),env.id()).state.entries.isEmpty())
    }
    @Test fun sameDatasetLocalIdentityNeedsEqualBusinessAndPreservesOriginalSources() {
        val e=Entry(env.id(),ledger,EntryKind.EXPENSE,1234,state.categories.first().id,"2026-10-01T10:00:00","午餐",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id(),captureFingerprint="0".repeat(64),captureEngineVersion="1")
        val originalSource=SourceIdentity(env.id(),e.id,"fixture","original","payment","one",canonicalSource="prior",sourceDigest=Sha256.hex("prior".encodeToByteArray()))
        val own=state.copy(entries=listOf(e),sourceIdentities=listOf(originalSource))
        val originalRecord=record().copy(cells=record().cells.toMutableList().also { it[0]=state.datasetId;it[1]=e.id })
        val duplicate=preview(doc(originalRecord),own)
        assertTrue(duplicate.canCommit,duplicate.problems.joinToString());assertEquals(0L,duplicate.impact.addedCount)
        val saved=engine.apply(own,Command.CommitImport(duplicate.plan!!),env.id()).state
        assertEquals(own.entries,saved.entries);assertEquals(own.sourceIdentities,saved.sourceIdentities)
        val changed=preview(doc(originalRecord.copy(cells=originalRecord.cells.toMutableList().also { it[3]="12.35" })),own)
        assertFalse(changed.canCommit)
    }
    @Test fun missingCategoryIsExplicitlyIncludedInCommitPreview() {
        val prepared=DawnYewCsv.prepareImport(doc(record(category="新分类")),ledger,state,env)
        assertEquals("新分类",prepared.mapping.newCategories.single().name)
        assertEquals(state.categories.size,engine.initialState().categories.size)
        val p=ImportPreviewer.preview(prepared.facts,context(),prepared.parsed,prepared.mapping,ImportSelection(setOf(1)),state,env)
        assertTrue(p.canCommit,p.problems.joinToString())
        assertTrue(engine.apply(state,Command.CommitImport(p.plan!!),env.id()).state.categories.any { it.name=="新分类" })
    }
    @Test fun textMarkerAndExactMoneyAreMandatoryAndOnlyCsvIsRegistered() {
        listOf(record().copy(cells=record().cells.toMutableList().also { it[7]="=FORMULA()" }),record("1.234"),record("1"),record("0.00")).forEach { bad ->
            assertIs<ParsedImportRow.Invalid>(DawnYewCsv.prepareImport(doc(bad),ledger,state,env).parsed.single())
        }
        assertEquals(setOf(DAWNYEW_CSV_ADAPTER_ID),TemplateRegistry.templates.filter { it.availability==TemplateAvailability.AVAILABLE }.map { it.id }.toSet())
    }
    @Test fun exportCountsExcludeRefundsTransfersAndTrashButDiscloseLostDetails() {
        var s=engine.apply(state,Command.SaveEntry(ledgerId=ledger,kind=EntryKind.EXPENSE,amountMinor=1234,categoryId=state.categories.first().id,occurrence="2026-10-01T10:00:00",note="=SUM(1,2)",details=EntryDetails("",recorded=false)),env.id()).state
        val e=s.entries.single();s=engine.apply(s,Command.AddRefund(e.id,100,"2026-10-02T00:00:00",""),env.id()).state
        val output=DawnYewCsv.prepareExport(s,QueryScope())
        assertEquals(1,output.summary.includedCount);assertEquals(1,output.summary.excludedRefundCount);assertEquals(1,output.summary.omittedDetailsCount)
        assertEquals("'=SUM(1,2)",output.records.single()[7]);assertEquals("12.34",output.records.single()[3])
    }
    @Test fun exportAcceptsTenThousandAndRejectsTenThousandAndOneBeforeEncoding() {
        fun stateWith(count:Int)=state.copy(entries=List(count) { Entry(env.id(),ledger,EntryKind.EXPENSE,1,state.categories.first().id,"2026-10-01T00:00:00",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id()) })
        assertEquals(9999,DawnYewCsv.prepareExport(stateWith(9999),QueryScope()).summary.includedCount)
        assertEquals(10000,DawnYewCsv.prepareExport(stateWith(10000),QueryScope()).summary.includedCount)
        assertFailsWith<DomainException> { DawnYewCsv.prepareExport(stateWith(10001),QueryScope()) }
    }

    @Test fun unselectedCsvRowsDoNotCreateUnreferencedCategories() {
        val first=record(category="仅预览")
        val second=record("2.34").copy(cells=record("2.34").cells.toMutableList().also { it[1]=env.id() },logicalRecordNumber=2,startLine=5,endLine=5)
        val document=DawnYewCsvDocument(DocumentFacts("text/csv",DocumentContainer.CSV,5,Sha256.hex("two rows".encodeToByteArray()),2),listOf(first,second))
        val prepared=DawnYewCsv.prepareImport(document,ledger,state,env)
        assertEquals(1,prepared.mapping.newCategories.size)
        val preview=ImportPreviewer.preview(prepared.facts,context(),prepared.parsed,prepared.mapping,ImportSelection(setOf(2)),state,env)
        assertTrue(preview.canCommit,preview.problems.joinToString())
        assertTrue(preview.plan!!.newCategories.isEmpty())
        assertEquals(state.categories,engine.apply(state,Command.CommitImport(preview.plan!!),env.id()).state.categories)
    }

    @Test fun duplicateCsvSourceDoesNotRecreateItsOldCategoryAfterRename() {
        val first=preview(doc())
        var saved=engine.apply(state,Command.CommitImport(first.plan!!),env.id()).state
        val category=saved.categories.first { it.id==saved.entries.single().categoryId }
        saved=engine.apply(saved,Command.SaveCategory(ledger,category.direction,"已重命名",category.id),env.id()).state
        val prepared=DawnYewCsv.prepareImport(doc(),ledger,saved,env)
        assertEquals("餐饮",prepared.mapping.newCategories.single().name)
        val duplicate=ImportPreviewer.preview(prepared.facts,context(),prepared.parsed,prepared.mapping,ImportSelection(setOf(1)),saved,env)
        assertTrue(duplicate.canCommit,duplicate.problems.joinToString())
        assertEquals(1L,duplicate.counts.getValue(PreviewDisposition.DUPLICATE))
        assertTrue(duplicate.plan!!.newCategories.isEmpty())
        assertEquals(saved.categories,engine.apply(saved,Command.CommitImport(duplicate.plan!!),env.id()).state.categories)
    }
}
