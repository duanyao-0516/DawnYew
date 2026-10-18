package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlin.test.*

class ImportPreviewTest {
    private var n=0
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private val state=DomainEngine(env).initialState()
    private fun candidate(row:Int,kind:EntryKind=EntryKind.EXPENSE,parent:String?=null)=ImportCandidate(row,Entry(env.id(),state.ledgers.first().id,kind,100,state.categories.first().id,"2026-10-17T00:00:00",parentExpenseId=parent,createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id()),null,emptyList())
    private fun facts(rows:Int)=DocumentFacts("text/csv",DocumentContainer.CSV,rows,Sha256.hex("file".encodeToByteArray()))
    private fun context()=PreviewContext(env.id(),env.id(),0,"synthetic","1")
    /** This fixture adapter is deliberately restricted to test sources and is never registered in production. */
    private inner class SyntheticAdapter(private val adapterId:String="synthetic"):BillTemplateAdapter {
        override val descriptor=TemplateDescriptor(adapterId,"Fixture",TemplateAvailability.AVAILABLE,"")
        override val version="1"
        override val allowedMimeTypes=setOf("text/csv")
        override val allowedContainers=setOf(DocumentContainer.CSV)
        override val sourceScopeVersion=1
        override fun inspect(facts:DocumentFacts,header:List<String>):TemplateProbe=if(header==listOf("fixture-v1")) TemplateProbe.ExactMatch(adapterId,version) else TemplateProbe.Unsupported("Not fixture")
        override fun parseRow(cells:List<String>,rowNumber:Int):ParsedImportRow=when(cells.firstOrNull()) {
            "entry"->ParsedImportRow.Valid(candidate(rowNumber))
            "decision"->ParsedImportRow.NeedsDecision(rowNumber,"日期精度未确认")
            "bad"->ParsedImportRow.Invalid(rowNumber,"金额无效")
            else->ParsedImportRow.Excluded(rowNumber,"说明行")
        }
    }
    @Test fun detectionRequiresOneExactAdapterWithoutBestGuess() {
        val a=SyntheticAdapter();val b=SyntheticAdapter("second")
        assertIs<TemplateProbe.ExactMatch>(TemplateDetection.inspect(listOf(a),facts(1),listOf("fixture-v1")))
        assertIs<TemplateProbe.Unsupported>(TemplateDetection.inspect(listOf(a),facts(1),listOf("almost fixture-v1")))
        assertIs<TemplateProbe.Unsupported>(TemplateDetection.inspect(listOf(a),facts(1).copy(container=DocumentContainer.XLSX),listOf("fixture-v1")))
        assertEquals(setOf("synthetic","second"),assertIs<TemplateProbe.Ambiguous>(TemplateDetection.inspect(listOf(a,b),facts(1),listOf("fixture-v1"))).adapterIds)
        assertTrue(TemplateRegistry.templates.all { it.availability==TemplateAvailability.AWAITING_SPECIFICATION })
    }
    @Test fun everyPhysicalRowIsCountedAndUnresolvedSelectedRowsBlockCommit() {
        val adapter=SyntheticAdapter();val rows=listOf("header","entry","decision","bad","footer").mapIndexed { i,value->adapter.parseRow(listOf(value),i+1) }
        val preview=ImportPreviewer.preview(facts(5),context(),rows,ImportMappingPlan(),ImportSelection(setOf(2)),state,env)
        assertEquals(5L,preview.counts.values.sum());assertEquals(2L,preview.counts.getValue(PreviewDisposition.EXCLUDED));assertEquals(1L,preview.impact.addedCount);assertEquals(100L,preview.impact.expense);assertTrue(preview.canCommit)
        val unresolved=ImportPreviewer.preview(facts(5),context(),rows,ImportMappingPlan(),ImportSelection(setOf(2,3)),state,env)
        assertFalse(unresolved.canCommit);assertTrue(unresolved.problems.isNotEmpty())
        assertFailsWith<DomainException> { ImportPreviewer.preview(facts(6),context(),rows,ImportMappingPlan(),ImportSelection(setOf(2)),state,env) }
    }
    @Test fun selectedRefundDependsOnSelectedNewParentAndPlanMatchesCommittedImpact() {
        val expense=candidate(1);val refund=candidate(2,EntryKind.REFUND,expense.entry.id).let { it.copy(entry=it.entry.copy(amountMinor=20)) }
        val rows=listOf(ParsedImportRow.Valid(expense),ParsedImportRow.Valid(refund))
        val missing=ImportPreviewer.preview(facts(2),context(),rows,ImportMappingPlan(),ImportSelection(setOf(2)),state,env)
        assertFalse(missing.canCommit);assertEquals(1,missing.dependencies.single().parentRowNumber)
        val preview=ImportPreviewer.preview(facts(2),context(),rows,ImportMappingPlan(),ImportSelection(setOf(1,2)),state,env)
        assertTrue(preview.canCommit);assertEquals(80L,preview.impact.netExpense)
        val committed=DomainEngine(env).apply(state,Command.CommitImport(preview.plan!!),env.id()).state
        assertEquals(preview.impact.addedCount,committed.entries.size.toLong())
    }
    @Test fun explicitMappingCreatesContainersOnlyOnCommit() {
        val l=Ledger(env.id(),"Mapped");val c=Category(env.id(),l.id,Direction.EXPENSE,"Mapped")
        val original=candidate(1);val mapping=ImportMappingPlan(listOf(RowMapping(1,l.id,c.id)),listOf(l),listOf(c))
        val preview=ImportPreviewer.preview(facts(1),context(),listOf(ParsedImportRow.Valid(original)),mapping,ImportSelection(setOf(1)),state,env)
        assertTrue(preview.canCommit);assertEquals(mapping.digest(),preview.plan!!.mappingDigest);assertEquals(l.id,preview.plan!!.rows.single().entry.ledgerId)
        assertEquals(1,state.ledgers.size)
        assertEquals(2,DomainEngine(env).apply(state,Command.CommitImport(preview.plan!!),env.id()).state.ledgers.size)
    }
    @Test fun sameBatchStrongDuplicatesCollapseButSuspectedDuplicatesNeedChoice() {
        val first=candidate(1);val source=SourceIdentity(env.id(),first.entry.id,"fixture","stable-account","payment","1",canonicalSource="source",sourceDigest=Sha256.hex("source".encodeToByteArray()))
        val a=first.copy(source=source);val b=candidate(2).let { it.copy(source=source.copy(id=env.id(),entryId=it.entry.id)) }
        val preview=ImportPreviewer.preview(facts(2),context(),listOf(ParsedImportRow.Valid(a),ParsedImportRow.Valid(b)),ImportMappingPlan(),ImportSelection(setOf(1,2)),state,env)
        assertTrue(preview.canCommit);assertEquals(1L,preview.impact.addedCount);assertEquals(1L,preview.counts.getValue(PreviewDisposition.DUPLICATE))
        val rows=listOf(ParsedImportRow.Valid(first),ParsedImportRow.Valid(candidate(2)))
        assertFalse(ImportPreviewer.preview(facts(2),context(),rows,ImportMappingPlan(),ImportSelection(setOf(1,2)),state,env).canCommit)
        assertTrue(ImportPreviewer.preview(facts(2),context(),rows,ImportMappingPlan(),ImportSelection(setOf(1,2),setOf(2)),state,env).canCommit)
    }
}
