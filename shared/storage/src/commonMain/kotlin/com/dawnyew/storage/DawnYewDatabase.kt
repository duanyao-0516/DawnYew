@file:OptIn(kotlin.ExperimentalStdlibApi::class)

package com.dawnyew.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(entities = [MetadataRow::class, LedgerRow::class, CategoryRow::class,
    DeletionGroupRow::class, EntryRow::class, BudgetRow::class, SourceIdentityRow::class,
    ImportBatchRow::class, ImportMemberRow::class, PreferenceRow::class, ReceiptRow::class], version = CURRENT_DATABASE_VERSION, exportSchema = true)
@ConstructedBy(DawnYewDatabaseConstructor::class)
abstract class DawnYewDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}

@Suppress("KotlinNoActualForExpect")
expect object DawnYewDatabaseConstructor : RoomDatabaseConstructor<DawnYewDatabase> {
    override fun initialize(): DawnYewDatabase
}

/** Every pooled connection gets these settings, including connections created after onOpen. */
internal class DurableSQLiteDriver : SQLiteDriver {
    private val bundled = BundledSQLiteDriver()
    override fun open(fileName: String): SQLiteConnection = bundled.open(fileName).also {
        try {
            it.execSQL("PRAGMA foreign_keys=ON")
            it.execSQL("PRAGMA synchronous=FULL")
            it.execSQL("PRAGMA secure_delete=ON")
            it.execSQL("PRAGMA busy_timeout=5000")
        } catch (failure: Throwable) {
            it.close()
            throw failure
        }
    }.let(::DurableConnection)
}

/** Room 2.8 configures WAL connections with NORMAL after driver.open; retain FULL instead. */
private class DurableConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    private val synchronousAssignment = Regex("^\\s*PRAGMA\\s+(?:main\\.)?synchronous\\s*=", RegexOption.IGNORE_CASE)
    override fun prepare(sql: String): SQLiteStatement = delegate.prepare(
        if (synchronousAssignment.containsMatchIn(sql)) "PRAGMA synchronous = FULL" else sql
    )
}

internal fun database(builder: RoomDatabase.Builder<DawnYewDatabase>): DawnYewDatabase = builder
    .setDriver(DurableSQLiteDriver())
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .setQueryCoroutineContext(Dispatchers.IO)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .addCallback(object : RoomDatabase.Callback() {
        override fun onCreate(connection: SQLiteConnection) { installConstraints(connection) }
        override fun onOpen(connection: SQLiteConnection) { verifyConstraints(connection) }
    })
    .build()

private val assertions = mapOf(
    "metadata" to "NEW.singleton != 1 OR NEW.contentVersion < 1 OR length(NEW.datasetId) = 0",
    "ledgers" to "length(NEW.id) = 0 OR length(NEW.normalizedName) = 0 OR NEW.currency != 'CNY'",
    "categories" to "length(NEW.id) = 0 OR length(NEW.normalizedName) = 0 OR NEW.direction NOT IN ('EXPENSE','INCOME')",
    "deletion_groups" to "length(NEW.id) = 0",
    "entries" to "typeof(NEW.amountMinor) != 'integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999 " +
        "OR NEW.kind NOT IN ('EXPENSE','INCOME','REFUND') " +
        "OR NEW.direction != CASE WHEN NEW.kind = 'INCOME' THEN 'INCOME' ELSE 'EXPENSE' END " +
        "OR (NEW.kind = 'REFUND' AND (NEW.parentExpenseId IS NULL OR NEW.parentExpenseId = NEW.id)) " +
        "OR (NEW.kind != 'REFUND' AND NEW.parentExpenseId IS NOT NULL) OR length(NEW.occurrence) != 19",
    "budgets" to "typeof(NEW.amountMinor) != 'integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999 OR length(NEW.yearMonth) != 7",
    "source_identities" to "length(NEW.provider) = 0 OR length(NEW.scope) = 0 OR length(NEW.eventKind) = 0 OR length(NEW.externalId) = 0",
    "import_batches" to "length(NEW.id) = 0 OR length(NEW.submissionId) = 0",
    "import_members" to "NEW.memberState NOT IN ('PRESENT','REMOVED','UNDONE') " +
        "OR (NEW.memberState = 'PRESENT' AND NEW.liveEntryId IS NOT NEW.entryId) " +
        "OR (NEW.memberState != 'PRESENT' AND NEW.liveEntryId IS NOT NULL)",
    "preferences" to "length(NEW.key) = 0",
    "operation_receipts" to "length(NEW.operationId) = 0",
)

internal fun installConstraints(connection: SQLiteConnection) {
    for ((table, assertion) in assertions) {
        for (event in listOf("INSERT", "UPDATE")) {
            val payloadGuard = if (table == "metadata") "" else " OR json_valid(NEW.payload) != 1"
            connection.execSQL("CREATE TRIGGER ${table}_validate_${event.lowercase()} BEFORE $event ON $table " +
                "WHEN $assertion$payloadGuard BEGIN SELECT RAISE(ABORT, 'DawnYew row constraint'); END")
        }
    }
}

/** Missing guards indicate a damaged/unsupported database. Do not silently repair or clear it. */
private fun verifyConstraints(connection: SQLiteConnection) {
    val expected = assertions.keys.flatMap { listOf("${it}_validate_insert", "${it}_validate_update") }.toSet()
    val actual = mutableSetOf<String>()
    connection.prepare("SELECT name FROM sqlite_master WHERE type='trigger'").use { statement ->
        while (statement.step()) actual += statement.getText(0)
    }
    check(actual.containsAll(expected)) { "DawnYew database is missing integrity triggers" }
}
