package com.dawnyew.storage

import androidx.room.*

@Dao
interface LedgerDao {
    @Insert suspend fun insertLedgerRows(rows: List<LedgerRow>)
    @Insert suspend fun insertCategoryRows(rows: List<CategoryRow>)
    @Insert suspend fun insertEntryRows(rows: List<EntryRow>)
    @Insert suspend fun insertDeletionGroupRows(rows: List<DeletionGroupRow>)
    @Insert suspend fun insertBudgetRows(rows: List<BudgetRow>)
    @Insert suspend fun insertSourceIdentityRows(rows: List<SourceIdentityRow>)
    @Insert suspend fun insertImportBatchRows(rows: List<ImportBatchRow>)
    @Insert suspend fun insertImportMemberRows(rows: List<ImportMemberRow>)
    @Insert suspend fun insertPreferenceRows(rows: List<PreferenceRow>)

    @RawQuery
    suspend fun queryEntryRows(query: RoomRawQuery): List<EntryRow>

    @Query("UPDATE entries SET creationOperationId = :operationId WHERE id = :id")
    suspend fun stageCreationOperation(id: String, operationId: String)

    @Query("SELECT * FROM metadata WHERE singleton = 1")
    suspend fun metadata(): MetadataRow?
    @Upsert suspend fun upsertMetadata(row: MetadataRow)

    @Query("SELECT * FROM ledgers ORDER BY position")
    suspend fun ledgerRows(): List<LedgerRow>
    @Upsert suspend fun upsertLedgerRows(rows: List<LedgerRow>)
    @Query("DELETE FROM ledgers WHERE id IN (:keys)")
    suspend fun deleteLedgerRows(keys: List<String>)

    @Query("SELECT * FROM categories ORDER BY position")
    suspend fun categoryRows(): List<CategoryRow>
    @Upsert suspend fun upsertCategoryRows(rows: List<CategoryRow>)
    @Query("DELETE FROM categories WHERE id IN (:keys)")
    suspend fun deleteCategoryRows(keys: List<String>)

    @Query("SELECT * FROM deletion_groups ORDER BY position")
    suspend fun deletionGroupRows(): List<DeletionGroupRow>
    @Upsert suspend fun upsertDeletionGroupRows(rows: List<DeletionGroupRow>)
    @Query("DELETE FROM deletion_groups WHERE id IN (:keys)")
    suspend fun deleteDeletionGroupRows(keys: List<String>)

    @Query("SELECT * FROM entries ORDER BY position")
    suspend fun entryRows(): List<EntryRow>
    @Upsert suspend fun upsertEntryRows(rows: List<EntryRow>)
    @Query("DELETE FROM entries WHERE id IN (:keys)")
    suspend fun deleteEntryRows(keys: List<String>)

    @Query("SELECT * FROM budgets ORDER BY position")
    suspend fun budgetRows(): List<BudgetRow>
    @Upsert suspend fun upsertBudgetRows(rows: List<BudgetRow>)
    @Query("DELETE FROM budgets WHERE ledgerId = :first AND yearMonth = :second")
    suspend fun deleteBudgetRow(first: String, second: String)

    @Query("SELECT * FROM source_identities ORDER BY position")
    suspend fun sourceIdentityRows(): List<SourceIdentityRow>
    @Upsert suspend fun upsertSourceIdentityRows(rows: List<SourceIdentityRow>)
    @Query("DELETE FROM source_identities WHERE id IN (:keys)")
    suspend fun deleteSourceIdentityRows(keys: List<String>)

    @Query("SELECT * FROM import_batches ORDER BY position")
    suspend fun importBatchRows(): List<ImportBatchRow>
    @Upsert suspend fun upsertImportBatchRows(rows: List<ImportBatchRow>)
    @Query("DELETE FROM import_batches WHERE id IN (:keys)")
    suspend fun deleteImportBatchRows(keys: List<String>)

    @Query("SELECT * FROM import_members ORDER BY position")
    suspend fun importMemberRows(): List<ImportMemberRow>
    @Upsert suspend fun upsertImportMemberRows(rows: List<ImportMemberRow>)
    @Query("DELETE FROM import_members WHERE batchId = :first AND entryId = :second")
    suspend fun deleteImportMemberRow(first: String, second: String)

    @Query("SELECT * FROM preferences ORDER BY position")
    suspend fun preferenceRows(): List<PreferenceRow>
    @Upsert suspend fun upsertPreferenceRows(rows: List<PreferenceRow>)
    @Query("DELETE FROM preferences WHERE key IN (:keys)")
    suspend fun deletePreferenceRows(keys: List<String>)

    @Query("SELECT * FROM operation_receipts ORDER BY position")
    suspend fun receiptRows(): List<ReceiptRow>
    @Upsert suspend fun upsertReceiptRows(rows: List<ReceiptRow>)
    @Query("DELETE FROM operation_receipts WHERE operationId IN (:keys)")
    suspend fun deleteReceiptRows(keys: List<String>)

    @Query("UPDATE ledgers SET normalizedName = :temporaryName WHERE id = :id")
    suspend fun stageLedgerName(id: String, temporaryName: String)
    @Query("UPDATE categories SET normalizedName = :temporaryName WHERE id = :id")
    suspend fun stageCategoryName(id: String, temporaryName: String)
    @Query("UPDATE import_batches SET submissionId = :temporaryId WHERE id = :id")
    suspend fun stageSubmissionId(id: String, temporaryId: String)

    @Insert suspend fun insertAccounts(rows:List<AccountRow>)
    @Query("SELECT * FROM accounts ORDER BY position") suspend fun accountsRows():List<AccountRow>
    @Upsert suspend fun upsertAccounts(rows:List<AccountRow>)
    @Query("DELETE FROM accounts WHERE id IN (:keys)") suspend fun deleteAccounts(keys:List<String>)
    @Query("UPDATE accounts SET normalizedName=:temporaryName WHERE id=:id") suspend fun stageAccountsName(id:String,temporaryName:String)
    @Insert suspend fun insertEntryDetails(rows:List<EntryDetailsRow>)
    @Query("SELECT * FROM entry_details ORDER BY position") suspend fun entryDetailsRows():List<EntryDetailsRow>
    @Upsert suspend fun upsertEntryDetails(rows:List<EntryDetailsRow>)
    @Query("DELETE FROM entry_details WHERE entryId IN (:keys)") suspend fun deleteEntryDetails(keys:List<String>)
    @Insert suspend fun insertEntryTags(rows:List<EntryTagRow>)
    @Query("SELECT * FROM entry_tags ORDER BY position") suspend fun entryTagsRows():List<EntryTagRow>
    @Upsert suspend fun upsertEntryTags(rows:List<EntryTagRow>)
    @Query("DELETE FROM entry_tags WHERE entryId=:first AND tagId=:second") suspend fun deleteEntryTags(first:String,second:String)
    @Insert suspend fun insertTransfers(rows:List<TransferRow>)
    @Query("SELECT * FROM transfers ORDER BY position") suspend fun transfersRows():List<TransferRow>
    @Upsert suspend fun upsertTransfers(rows:List<TransferRow>)
    @Query("DELETE FROM transfers WHERE id IN (:keys)") suspend fun deleteTransfers(keys:List<String>)
    @Insert suspend fun insertDimensions(rows:List<DimensionRow>)
    @Query("SELECT * FROM dimensions ORDER BY position") suspend fun dimensionsRows():List<DimensionRow>
    @Upsert suspend fun upsertDimensions(rows:List<DimensionRow>)
    @Query("DELETE FROM dimensions WHERE id IN (:keys)") suspend fun deleteDimensions(keys:List<String>)
    @Query("UPDATE dimensions SET normalizedName=:temporaryName WHERE id=:id") suspend fun stageDimensionsName(id:String,temporaryName:String)
    @Insert suspend fun insertContacts(rows:List<ContactRow>)
    @Query("SELECT * FROM contacts ORDER BY position") suspend fun contactsRows():List<ContactRow>
    @Upsert suspend fun upsertContacts(rows:List<ContactRow>)
    @Query("DELETE FROM contacts WHERE id IN (:keys)") suspend fun deleteContacts(keys:List<String>)
    @Query("UPDATE contacts SET normalizedName=:temporaryName WHERE id=:id") suspend fun stageContactsName(id:String,temporaryName:String)
    @Insert suspend fun insertObligations(rows:List<ObligationRow>)
    @Query("SELECT * FROM obligations ORDER BY position") suspend fun obligationsRows():List<ObligationRow>
    @Upsert suspend fun upsertObligations(rows:List<ObligationRow>)
    @Query("DELETE FROM obligations WHERE id IN (:keys)") suspend fun deleteObligations(keys:List<String>)
    @Insert suspend fun insertRecurringPlans(rows:List<RecurringPlanRow>)
    @Query("SELECT * FROM recurring_plans ORDER BY position") suspend fun recurringPlansRows():List<RecurringPlanRow>
    @Upsert suspend fun upsertRecurringPlans(rows:List<RecurringPlanRow>)
    @Query("DELETE FROM recurring_plans WHERE id IN (:keys)") suspend fun deleteRecurringPlans(keys:List<String>)
    @Insert suspend fun insertRecurringOccurrences(rows:List<RecurringOccurrenceRow>)
    @Query("SELECT * FROM recurring_occurrences ORDER BY position") suspend fun recurringOccurrencesRows():List<RecurringOccurrenceRow>
    @Upsert suspend fun upsertRecurringOccurrences(rows:List<RecurringOccurrenceRow>)
    @Query("DELETE FROM recurring_occurrences WHERE id IN (:keys)") suspend fun deleteRecurringOccurrences(keys:List<String>)
    @Insert suspend fun insertSavingsPlans(rows:List<SavingsPlanRow>)
    @Query("SELECT * FROM savings_plans ORDER BY position") suspend fun savingsPlansRows():List<SavingsPlanRow>
    @Upsert suspend fun upsertSavingsPlans(rows:List<SavingsPlanRow>)
    @Query("DELETE FROM savings_plans WHERE id IN (:keys)") suspend fun deleteSavingsPlans(keys:List<String>)
    @Insert suspend fun insertSavingsMovements(rows:List<SavingsMovementRow>)
    @Query("SELECT * FROM savings_movements ORDER BY position") suspend fun savingsMovementsRows():List<SavingsMovementRow>
    @Upsert suspend fun upsertSavingsMovements(rows:List<SavingsMovementRow>)
    @Query("DELETE FROM savings_movements WHERE id IN (:keys)") suspend fun deleteSavingsMovements(keys:List<String>)
    @Insert suspend fun insertSavingsWishes(rows:List<SavingsWishRow>)
    @Query("SELECT * FROM savings_wishes ORDER BY position") suspend fun savingsWishesRows():List<SavingsWishRow>
    @Upsert suspend fun upsertSavingsWishes(rows:List<SavingsWishRow>)
    @Query("DELETE FROM savings_wishes WHERE id IN (:keys)") suspend fun deleteSavingsWishes(keys:List<String>)
    @Query("UPDATE savings_wishes SET fulfilledMovementId=NULL WHERE id=:id") suspend fun stageWishFulfillment(id:String)
    @Insert suspend fun insertBudgetAllocations(rows:List<BudgetAllocationRow>)
    @Query("SELECT * FROM budget_allocations ORDER BY position") suspend fun budgetAllocationsRows():List<BudgetAllocationRow>
    @Upsert suspend fun upsertBudgetAllocations(rows:List<BudgetAllocationRow>)
    @Query("DELETE FROM budget_allocations WHERE id IN (:keys)") suspend fun deleteBudgetAllocations(keys:List<String>)
    @Insert suspend fun insertBudgetReallocations(rows:List<BudgetReallocationRow>)
    @Query("SELECT * FROM budget_reallocations ORDER BY position") suspend fun budgetReallocationsRows():List<BudgetReallocationRow>
    @Upsert suspend fun upsertBudgetReallocations(rows:List<BudgetReallocationRow>)
    @Query("DELETE FROM budget_reallocations WHERE id IN (:keys)") suspend fun deleteBudgetReallocations(keys:List<String>)
    @Query("UPDATE budget_reallocations SET reversalOfId=NULL WHERE id=:id") suspend fun stageReversal(id:String)
}
