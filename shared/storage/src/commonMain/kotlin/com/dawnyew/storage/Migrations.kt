package com.dawnyew.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.dawnyew.core.validateId
import kotlinx.serialization.json.*

/** Preserve all v1 business rows; invalid or duplicate durable command IDs abort the transaction. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.prepare("SELECT payload FROM entries").use { statement ->
            while (statement.step()) {
                val value = Json.parseToJsonElement(statement.getText(0)).jsonObject.getValue("creationOperationId").jsonPrimitive
                require(value.isString) { "Invalid creation operation ID in v1 database" }
                validateId(value.content)
            }
        }
        connection.execSQL("ALTER TABLE entries ADD COLUMN creationOperationId TEXT NOT NULL DEFAULT ''")
        connection.execSQL("UPDATE entries SET creationOperationId = json_extract(payload, '$.creationOperationId')")
        connection.execSQL("CREATE UNIQUE INDEX index_entries_creationOperationId ON entries (creationOperationId)")
    }
}


internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE ledgers ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE ledgers SET archived = json_extract(payload, '$.archived')")
        connection.execSQL("ALTER TABLE entries ADD COLUMN noteSearch TEXT NOT NULL DEFAULT ''")
        connection.prepare("SELECT id, payload FROM entries").use { rows ->
            connection.prepare("UPDATE entries SET noteSearch = ? WHERE id = ?").use { update ->
                while (rows.step()) {
                    val note = Json.parseToJsonElement(rows.getText(1)).jsonObject.getValue("note").jsonPrimitive
                    require(note.isString)
                    update.bindText(1, com.dawnyew.core.literalSearchKey(note.content)); update.bindText(2, rows.getText(0)); update.step(); update.reset()
                }
            }
        }
    }
}
