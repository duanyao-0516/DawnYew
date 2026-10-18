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
}
