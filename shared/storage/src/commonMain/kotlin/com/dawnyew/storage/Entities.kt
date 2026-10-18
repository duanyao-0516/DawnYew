package com.dawnyew.storage

import androidx.room.*

@Entity(tableName = "metadata")
data class MetadataRow(@PrimaryKey val singleton: Int = 1, val datasetId: String, val contentVersion: Long)

@Entity(tableName = "ledgers", indices = [Index(value = ["normalizedName"], unique = true)])
data class LedgerRow(@PrimaryKey val id: String, val normalizedName: String, val currency: String, @ColumnInfo(defaultValue = "0") val archived: Boolean, val position: Long, val payload: String)

@Entity(tableName = "categories", foreignKeys = [ForeignKey(entity = LedgerRow::class, parentColumns = ["id"], childColumns = ["ledgerId"], deferred = true)],
    indices = [Index("ledgerId"), Index(value = ["id", "ledgerId", "direction"], unique = true), Index(value = ["ledgerId", "direction", "normalizedName"], unique = true)])
data class CategoryRow(@PrimaryKey val id: String, val ledgerId: String, val direction: String, val normalizedName: String, val position: Long, val payload: String)

@Entity(tableName = "deletion_groups")
data class DeletionGroupRow(@PrimaryKey val id: String, val position: Long, val payload: String)

@Entity(tableName = "entries", foreignKeys = [
    ForeignKey(entity = LedgerRow::class, parentColumns = ["id"], childColumns = ["ledgerId"], deferred = true),
    ForeignKey(entity = CategoryRow::class, parentColumns = ["id", "ledgerId", "direction"], childColumns = ["categoryId", "ledgerId", "direction"], deferred = true),
    ForeignKey(entity = EntryRow::class, parentColumns = ["id"], childColumns = ["parentExpenseId"], onDelete = ForeignKey.CASCADE, deferred = true),
    ForeignKey(entity = DeletionGroupRow::class, parentColumns = ["id"], childColumns = ["deletionGroupId"], deferred = true)
], indices = [Index("ledgerId"), Index(value = ["categoryId", "ledgerId", "direction"]), Index("parentExpenseId"), Index("deletionGroupId"), Index(value = ["creationOperationId"], unique = true), Index(value = ["ledgerId", "deletionGroupId", "occurrence"])])
data class EntryRow(@PrimaryKey val id: String, val ledgerId: String, val kind: String, val direction: String,
    val amountMinor: Long, val categoryId: String, val parentExpenseId: String?, val deletionGroupId: String?,
    @ColumnInfo(defaultValue = "''") val creationOperationId: String, val occurrence: String, @ColumnInfo(defaultValue = "''") val noteSearch: String, val position: Long, val payload: String)

@Entity(tableName = "budgets", primaryKeys = ["ledgerId", "yearMonth"], foreignKeys = [ForeignKey(entity = LedgerRow::class, parentColumns = ["id"], childColumns = ["ledgerId"], deferred = true)])
data class BudgetRow(val ledgerId: String, val yearMonth: String, val amountMinor: Long, val position: Long, val payload: String)

@Entity(tableName = "source_identities", foreignKeys = [ForeignKey(entity = EntryRow::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE, deferred = true)],
    indices = [Index("entryId"), Index(value = ["provider", "scope", "eventKind", "externalId"], unique = true)])
data class SourceIdentityRow(@PrimaryKey val id: String, val entryId: String, val provider: String, val scope: String, val eventKind: String, val externalId: String, val position: Long, val payload: String)

@Entity(tableName = "import_batches", indices = [Index(value = ["submissionId"], unique = true)])
data class ImportBatchRow(@PrimaryKey val id: String, val submissionId: String, val position: Long, val payload: String)

@Entity(tableName = "import_members", primaryKeys = ["batchId", "entryId"], foreignKeys = [
    ForeignKey(entity = ImportBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"], deferred = true),
    ForeignKey(entity = EntryRow::class, parentColumns = ["id"], childColumns = ["liveEntryId"], deferred = true)
], indices = [Index("liveEntryId")])
data class ImportMemberRow(val batchId: String, val entryId: String, val liveEntryId: String?, val memberState: String, val position: Long, val payload: String)

@Entity(tableName = "preferences")
data class PreferenceRow(@PrimaryKey val key: String, val position: Long, val payload: String)

@Entity(tableName = "operation_receipts")
data class ReceiptRow(@PrimaryKey val operationId: String, val position: Long, val payload: String)
