package com.dawnyew.core

/** Bounded transfer chunk for backup readers/writers; no dataset-wide materialization is required. */
data class LedgerEntityBatch(
    val ledgers:List<Ledger> = emptyList(), val categories:List<Category> = emptyList(),
    val entries:List<Entry> = emptyList(), val deletionGroups:List<DeletionGroup> = emptyList(),
    val budgets:List<MonthlyBudget> = emptyList(), val sourceIdentities:List<SourceIdentity> = emptyList(),
    val importBatches:List<ImportBatch> = emptyList(), val importMembers:List<ImportMember> = emptyList(),
    val preferences:List<UserPreference> = emptyList(),
    val accounts:List<Account> = emptyList(), val entryDetails:List<EntryDetails> = emptyList(), val entryTags:List<EntryTag> = emptyList(),
    val transfers:List<AccountTransfer> = emptyList(), val dimensions:List<Dimension> = emptyList(), val contacts:List<Contact> = emptyList(),
    val obligations:List<Obligation> = emptyList(), val recurringPlans:List<RecurringPlan> = emptyList(), val recurringOccurrences:List<RecurringOccurrence> = emptyList(),
    val savingsPlans:List<SavingsPlan> = emptyList(), val savingsMovements:List<SavingsMovement> = emptyList(), val savingsWishes:List<SavingsWish> = emptyList(),
    val budgetAllocations:List<BudgetAllocation> = emptyList(), val budgetReallocations:List<BudgetReallocation> = emptyList(),
)
val ledgerEntityTypes=listOf("ledgers","categories","entries","deletionGroups","budgets","sourceIdentities","importBatches","importMembers","preferences","accounts","entryDetails","entryTags","transfers","dimensions","contacts","obligations","recurringPlans","recurringOccurrences","savingsPlans","savingsMovements","savingsWishes","budgetAllocations","budgetReallocations")

/** Local fields only. References and aggregate constraints are checked after the candidate is complete. */
fun validateEntityBatch(batch:LedgerEntityBatch,normalize:(String)->String=::normalizeTextNfc) {
    fun ensure(valid:Boolean,message:String) { if(!valid) invalid(message) }
    fun name(value:String,max:Int) { ensure(value==normalize(value.trim()) && codePointCount(value) in 1..max && value.none { it.code<32 },"名称格式无效") }
    batch.ledgers.forEach { validateId(it.id);name(it.name,30);ensure(it.revision>0 && it.currency=="CNY","账本字段无效") }
    batch.categories.forEach { validateId(it.id);validateId(it.ledgerId);name(it.name,20);ensure(it.revision>0,"分类版本无效") }
    batch.entries.forEach { e ->
        listOfNotNull(e.id,e.ledgerId,e.categoryId,e.parentExpenseId,e.deletionGroupId,e.creationOperationId).forEach(::validateId)
        Money.requireValid(e.amountMinor);validateOccurrence(e.occurrence);validateUtc(e.createdAt);validateUtc(e.updatedAt)
        ensure(e.revision>0 && codePointCount(e.note)<=500 && !e.note.contains('\u0000'),"账单字段无效")
        ensure((e.kind==EntryKind.REFUND)==(e.parentExpenseId!=null),"退款父引用无效")
        e.captureFingerprint?.let(::validateDigest);ensure(e.captureEngineVersion==null || e.captureEngineVersion.isNotBlank(),"识别引擎版本无效")
    }
    batch.deletionGroups.forEach { validateId(it.id);validateId(it.rootEntryId);validateUtc(it.deletedAt);ensure(it.reason=="USER","删除原因无效") }
    batch.budgets.forEach { validateId(it.ledgerId);validateOccurrence(it.yearMonth+"-01T00:00:00");Money.requireValid(it.amountMinor);ensure(it.revision>0,"预算版本无效") }
    batch.sourceIdentities.forEach(::validateSource)
    batch.importBatches.forEach { b ->
        validateId(b.id);validateId(b.submissionId);validateUtc(b.createdAt);listOf(b.fileDigest,b.previewDigest,b.mappingDigest).forEach(::validateDigest)
        ensure(b.status in setOf("COMMITTED","UNDONE") && b.count>=0 && b.adapterId.isNotBlank() && b.adapterVersion.isNotBlank(),"导入批次状态无效")
    }
    batch.importMembers.forEach { validateId(it.batchId);validateId(it.entryId);validateDigest(it.committedContentDigest);ensure(it.committedRevision>0 && it.state in setOf("PRESENT","REMOVED","UNDONE"),"导入成员字段无效") }
    batch.preferences.forEach(::validatePreference)
    validateFinanceEntityBatch(batch,normalize)
}
