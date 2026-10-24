package com.dawnyew.storage

import com.dawnyew.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal fun accountsRow(v:Account,position:Long)=AccountRow(v.id,v.ledgerId,v.name,v.type.name,v.openingBalanceMinor,v.openingAt,v.archived,v.hidden,v.includedInAssets,v.note,v.sortOrder,v.revision,normalizeTextNfc(v.name.trim()),position,streamJson.encodeToString(v))

internal fun entryDetailsRow(v:EntryDetails,position:Long)=EntryDetailsRow(v.entryId,v.accountId,v.recorded,v.excludedFromBudget,v.reconciled,v.projectId,v.merchantId,position,streamJson.encodeToString(v))

internal fun entryTagsRow(v:EntryTag,position:Long)=EntryTagRow(v.entryId,v.tagId,position,streamJson.encodeToString(v))

internal fun transfersRow(v:AccountTransfer,position:Long)=TransferRow(v.id,v.ledgerId,v.fromAccountId,v.toAccountId,v.amountMinor,v.occurrence,v.note,v.recorded,v.reconciled,v.deleted,v.revision,v.createdAt,v.updatedAt,v.businessKind.name,v.businessId,literalSearchKey(v.note),position,streamJson.encodeToString(v))

internal fun dimensionsRow(v:Dimension,position:Long)=DimensionRow(v.id,v.ledgerId,v.kind.name,v.name,v.archived,v.sortOrder,v.revision,normalizeTextNfc(v.name.trim()),position,streamJson.encodeToString(v))

internal fun contactsRow(v:Contact,position:Long)=ContactRow(v.id,v.ledgerId,v.name,v.note,v.archived,v.revision,normalizeTextNfc(v.name.trim()),position,streamJson.encodeToString(v))

internal fun obligationsRow(v:Obligation,position:Long)=ObligationRow(v.id,v.ledgerId,v.kind.name,v.title,v.virtualAccountId,v.openingAt,v.contactId,v.dueDate,v.note,v.archived,v.revision,position,streamJson.encodeToString(v))

internal fun recurringPlansRow(v:RecurringPlan,position:Long)=RecurringPlanRow(v.id,v.ledgerId,v.name,v.kind.name,v.amountMinor,v.anchor,v.frequency.name,v.totalOccurrences,v.nextIndex,v.paused,v.archived,v.categoryId,v.accountId,v.fromAccountId,v.toAccountId,v.note,v.recorded,v.excludedFromBudget,v.reconciled,v.projectId,v.merchantId,v.revision,position,streamJson.encodeToString(v))

internal fun recurringOccurrencesRow(v:RecurringOccurrence,position:Long)=RecurringOccurrenceRow(v.id,v.planId,v.index,v.scheduledAt,v.status.name,v.entryId,v.transferId,position,streamJson.encodeToString(v))

internal fun savingsPlansRow(v:SavingsPlan,position:Long)=SavingsPlanRow(v.id,v.ledgerId,v.name,v.scenario.name,v.targetMinor,v.note,v.archived,v.revision,position,streamJson.encodeToString(v))

internal fun savingsMovementsRow(v:SavingsMovement,position:Long)=SavingsMovementRow(v.id,v.planId,v.kind.name,v.amountMinor,v.occurrence,v.note,v.transferId,v.deleted,v.revision,position,streamJson.encodeToString(v))

internal fun savingsWishesRow(v:SavingsWish,position:Long)=SavingsWishRow(v.id,v.planId,v.name,v.amountMinor,v.fulfilledMovementId,v.revision,position,streamJson.encodeToString(v))

internal fun budgetAllocationsRow(v:BudgetAllocation,position:Long)=BudgetAllocationRow(v.id,v.ledgerId,v.yearMonth,v.categoryId,v.amountMinor,v.revision,position,streamJson.encodeToString(v))

internal fun budgetReallocationsRow(v:BudgetReallocation,position:Long)=BudgetReallocationRow(v.id,v.ledgerId,v.yearMonth,v.fromAllocationId,v.toAllocationId,v.amountMinor,v.createdAt,v.reversalOfId,position,streamJson.encodeToString(v))

internal fun financialRecordColumns(type:String,payload:String):Map<String,Any?> {
    val value=when(type) {
        "accounts" -> streamJson.decodeFromString<Account>(payload).also { validateEntityBatch(LedgerEntityBatch(accounts=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "entryDetails" -> streamJson.decodeFromString<EntryDetails>(payload).also { validateEntityBatch(LedgerEntityBatch(entryDetails=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "entryTags" -> streamJson.decodeFromString<EntryTag>(payload).also { validateEntityBatch(LedgerEntityBatch(entryTags=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "transfers" -> streamJson.decodeFromString<AccountTransfer>(payload).also { validateEntityBatch(LedgerEntityBatch(transfers=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "dimensions" -> streamJson.decodeFromString<Dimension>(payload).also { validateEntityBatch(LedgerEntityBatch(dimensions=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "contacts" -> streamJson.decodeFromString<Contact>(payload).also { validateEntityBatch(LedgerEntityBatch(contacts=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "obligations" -> streamJson.decodeFromString<Obligation>(payload).also { validateEntityBatch(LedgerEntityBatch(obligations=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "recurringPlans" -> streamJson.decodeFromString<RecurringPlan>(payload).also { validateEntityBatch(LedgerEntityBatch(recurringPlans=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "recurringOccurrences" -> streamJson.decodeFromString<RecurringOccurrence>(payload).also { validateEntityBatch(LedgerEntityBatch(recurringOccurrences=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "savingsPlans" -> streamJson.decodeFromString<SavingsPlan>(payload).also { validateEntityBatch(LedgerEntityBatch(savingsPlans=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "savingsMovements" -> streamJson.decodeFromString<SavingsMovement>(payload).also { validateEntityBatch(LedgerEntityBatch(savingsMovements=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "savingsWishes" -> streamJson.decodeFromString<SavingsWish>(payload).also { validateEntityBatch(LedgerEntityBatch(savingsWishes=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "budgetAllocations" -> streamJson.decodeFromString<BudgetAllocation>(payload).also { validateEntityBatch(LedgerEntityBatch(budgetAllocations=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        "budgetReallocations" -> streamJson.decodeFromString<BudgetReallocation>(payload).also { validateEntityBatch(LedgerEntityBatch(budgetReallocations=listOf(it)));if(streamJson.encodeToString(it)!=payload) invalid("候选记录字段不完整或编码已变化") }.let { streamJson.encodeToJsonElement(it).jsonObject }
        else -> invalid("未知财务实体")
    }
    val columns=value.mapValues { (_,v)-> when {v==JsonNull->null;v.jsonPrimitive.isString->v.jsonPrimitive.content;v.jsonPrimitive.booleanOrNull!=null->if(v.jsonPrimitive.boolean) 1L else 0L;else->v.jsonPrimitive.long} }.toMutableMap()
    if(type in setOf("accounts","dimensions","contacts")) columns["normalizedName"]=normalizeTextNfc(value.getValue("name").jsonPrimitive.content.trim())
    if(type=="transfers") columns["noteSearch"]=literalSearchKey(value.getValue("note").jsonPrimitive.content)
    return columns
}