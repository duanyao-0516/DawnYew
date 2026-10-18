package com.dawnyew.core

import kotlin.test.*

class ImportCommandTest {
    private var n = 100
    private var localNow = "2026-10-18T12:00:00"
    private val env = object : DomainEnvironment {
        override fun id() = "00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow() = localNow
        override fun utcNow() = "2026-10-18T04:00:00Z"
        override fun normalize(value:String) = value.trim()
    }
    private val engine = DomainEngine(env)
    private fun entry(s:LedgerState, kind:EntryKind = EntryKind.EXPENSE, parent:String? = null) = Entry(env.id(), s.ledgers.first().id, kind, 100,
        s.categories.first().id, "2026-10-17T10:00:00", parentExpenseId = parent, createdAt = env.utcNow(), updatedAt = env.utcNow(), creationOperationId = env.id())
    private fun source(e:Entry, external:String = "payment-1") = SourceIdentity(env.id(), e.id, "fixture", "account-owned-stable-scope", "payment", external,
        canonicalSource = "amount=100&date=2026-10-17", sourceDigest = Sha256.hex("amount=100&date=2026-10-17".encodeToByteArray()))
    private fun plan(s:LedgerState, rows:List<ImportRow>) = ImportPlan(env.id(), env.id(), s.datasetId, s.contentVersion, 0, "fixture", "1",
        Sha256.hex("file".encodeToByteArray()), Sha256.hex("mapping".encodeToByteArray()), rows = rows).sealed()
    private fun run(s:LedgerState,c:Command) = engine.apply(s,c,env.id()).state

    @Test fun commitsExpenseRefundAndSourceThenUndoesWholeBatch() {
        val s = engine.initialState(); val e = entry(s); val r = entry(s,EntryKind.REFUND,e.id)
        val p = plan(s,listOf(ImportRow(1,e,source(e)), ImportRow(2,r)))
        val imported = run(s,Command.CommitImport(p))
        assertEquals(2, imported.entries.size); assertEquals(2,imported.importMembers.size)
        assertTrue(imported.entries.all { it.originKind == OriginKind.IMPORT })
        val undone = run(imported,Command.UndoImport(p.batchId))
        assertTrue(undone.entries.isEmpty()); assertTrue(undone.sourceIdentities.isEmpty())
        assertEquals("UNDONE",undone.importBatches.single().status)
        assertTrue(undone.importMembers.all { it.state == "UNDONE" })
        assertEquals(undone,run(undone,Command.UndoImport(p.batchId)))
    }
    @Test fun submissionRetryAfterReceiptLossReturnsOriginalWithoutDuplicate() {
        val s = engine.initialState(); val p = plan(s,listOf(ImportRow(1,entry(s))))
        val imported = run(s,Command.CommitImport(p)).copy(receipts=emptyList())
        assertEquals(imported,run(imported,Command.CommitImport(p)))
        assertFailsWith<DomainException> { run(imported,Command.CommitImport(p.copy(fileDigest=Sha256.hex("changed".encodeToByteArray())).sealed())) }
    }
    @Test fun staleDatasetVersionAndModifiedSelectionAreRejected() {
        val s=engine.initialState(); val p=plan(s,listOf(ImportRow(1,entry(s))))
        assertFailsWith<DomainException> { run(s.copy(contentVersion=2),Command.CommitImport(p)) }
        assertFailsWith<DomainException> { run(s.copy(datasetId=env.id()),Command.CommitImport(p)) }
        assertFailsWith<DomainException> { run(s,Command.CommitImport(p.copy(rows=p.rows.map { it.copy(entry=it.entry.copy(amountMinor=101)) }))) }
    }
    @Test fun sourceDuplicatePreservesLocalEditsAndDeletedRecords() {
        val s=engine.initialState(); val e=entry(s); val src=source(e)
        val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,e,src)))))
        val old=imported.entries.single()
        val edited=run(imported,Command.SaveEntry(old.id,old.ledgerId,old.kind,200,old.categoryId,old.occurrence,"local correction",old.revision))
        val candidate=e.copy(id=env.id(),creationOperationId=env.id())
        val repeated=run(edited,Command.CommitImport(plan(edited,listOf(ImportRow(1,candidate,src.copy(id=env.id(),entryId=candidate.id))))))
        assertEquals(edited.entries,repeated.entries); assertEquals(0,repeated.importBatches.last().count)
        val deleted=run(repeated,Command.SoftDelete(e.id))
        val again=run(deleted,Command.CommitImport(plan(deleted,listOf(ImportRow(1,candidate,src.copy(id=env.id(),entryId=candidate.id))))))
        assertEquals(deleted.entries,again.entries)
    }
    @Test fun identityContradictionAndChangedCanonicalSourceAreConflicts() {
        val s=engine.initialState();val a=entry(s);val b=entry(s).copy(amountMinor=200)
        val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,a,source(a,"a")),ImportRow(2,b,source(b,"b"))))))
        val bad=source(a,"b")
        assertFailsWith<DomainException> { run(imported,Command.CommitImport(plan(imported,listOf(ImportRow(1,a,bad))))) }
        val changed=source(a).copy(canonicalSource="changed",sourceDigest=Sha256.hex("changed".encodeToByteArray()),externalId="a")
        assertFailsWith<DomainException> { run(imported,Command.CommitImport(plan(imported,listOf(ImportRow(1,a.copy(id=env.id()),changed.copy(entryId=env.id())))))) }
    }
    @Test fun sameBatchDuplicatesCollapseAndRefundUsesSurvivingParent() {
        val s=engine.initialState();val a=entry(s);val duplicate=a.copy(id=env.id(),creationOperationId=env.id());val r=entry(s,EntryKind.REFUND,duplicate.id)
        val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,a,source(a)),ImportRow(2,duplicate,source(duplicate)),ImportRow(3,r)))))
        assertEquals(2,imported.entries.size)
        assertEquals(a.id,imported.entries.single { it.kind==EntryKind.REFUND }.parentExpenseId)
    }
    @Test fun suspectNeedsExplicitDecisionAndMissingSelectedParentRejects() {
        val s=engine.initialState();val a=entry(s)
        val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,a)))))
        val b=entry(imported)
        assertFailsWith<DomainException> { run(imported,Command.CommitImport(plan(imported,listOf(ImportRow(1,b))))) }
        assertEquals(2,run(imported,Command.CommitImport(plan(imported,listOf(ImportRow(1,b,keepSuspectedDuplicate=true))))).entries.size)
        assertFailsWith<DomainException> { run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,entry(s,EntryKind.REFUND,env.id())))))) }
    }
    @Test fun editThenRevertAndOutsideRefundEachBlockUndo() {
        val s=engine.initialState();val p=plan(s,listOf(ImportRow(1,entry(s))))
        val imported=run(s,Command.CommitImport(p));val e=imported.entries.single()
        var edited=run(imported,Command.SaveEntry(e.id,e.ledgerId,e.kind,e.amountMinor,e.categoryId,e.occurrence,"change",e.revision))
        edited=run(edited,Command.SaveEntry(e.id,e.ledgerId,e.kind,e.amountMinor,e.categoryId,e.occurrence,e.note,edited.entries.single().revision))
        assertFailsWith<DomainException> { run(edited,Command.UndoImport(p.batchId)) }
        val refund=run(imported,Command.AddRefund(e.id,1,e.occurrence,""))
        assertFailsWith<DomainException> { run(refund,Command.UndoImport(p.batchId)) }
    }
    @Test fun explicitContainersSurviveUndoAndBadRowLeavesNoContainers() {
        val s=engine.initialState();val l=Ledger(env.id(),"New");val c=Category(env.id(),l.id,Direction.EXPENSE,"new")
        val e=entry(s).copy(ledgerId=l.id,categoryId=c.id)
        val p=plan(s,listOf(ImportRow(1,e))).copy(newLedgers=listOf(l),newCategories=listOf(c)).sealed()
        val undone=run(run(s,Command.CommitImport(p)),Command.UndoImport(p.batchId))
        assertTrue(undone.ledgers.any { it.id==l.id });assertTrue(undone.categories.any { it.id==c.id })
        assertFailsWith<DomainException> { run(s,Command.CommitImport(p.copy(rows=listOf(ImportRow(1,e.copy(amountMinor=0)))).sealed())) }
        assertEquals(1,s.ledgers.size)
    }
    @Test fun strictIdsSourcesAndPreferencesAreValidated() {
        val s=engine.initialState();val e=entry(s)
        assertFailsWith<DomainException> { validateState(s.copy(datasetId="not-a-uuid")) }
        assertFailsWith<DomainException> { run(s,Command.SetPreference("theme","unknown")) }
        assertFailsWith<DomainException> { run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,e,source(e).copy(scope="")))))) }
        assertFailsWith<DomainException> { run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,e,source(e).copy(sourceDigest="0".repeat(64))))))) }
    }
    @Test fun historicalFutureDateCanRemainUnchangedAfterClockChange() {
        val s=engine.initialState();val e=entry(s);val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,e)))))
        localNow="2026-10-16T00:00:00"
        val edited=run(imported,Command.SaveEntry(e.id,e.ledgerId,e.kind,e.amountMinor,e.categoryId,e.occurrence,"note only",1))
        assertEquals("note only",edited.entries.single().note)
        assertFailsWith<DomainException> { run(edited,Command.SaveEntry(e.id,e.ledgerId,e.kind,e.amountMinor,e.categoryId,"2026-10-18T00:00:00","",2)) }
    }
    @Test fun repeatedCandidateIdCannotResolveToTwoDifferentExistingSources() {
        val s=engine.initialState();val a=entry(s);val b=entry(s).copy(amountMinor=200)
        val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,a,source(a,"a")),ImportRow(2,b,source(b,"b"))))))
        val id=env.id();val first=a.copy(id=id);val second=b.copy(id=id)
        val p=plan(imported,listOf(ImportRow(1,first,source(first,"a")),ImportRow(2,second,source(second,"b"))))
        assertFailsWith<DomainException> { run(imported,Command.CommitImport(p)) }
    }
    @Test fun duplicateSourceCannotHideMalformedCandidateFields() {
        val s=engine.initialState();val a=entry(s)
        val imported=run(s,Command.CommitImport(plan(s,listOf(ImportRow(1,a,source(a))))))
        val invalid=a.copy(id=env.id(),amountMinor=0)
        assertFailsWith<DomainException> { run(imported,Command.CommitImport(plan(imported,listOf(ImportRow(1,invalid,source(invalid)))))) }
    }

}
