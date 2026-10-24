package com.dawnyew.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

const val CURRENT_DATABASE_VERSION = 4

// SQLite's stable sqlite3_open_v2 flags. Intentionally omit SQLITE_OPEN_CREATE (0x00000004).
private const val OPEN_READONLY = 0x00000001
private const val OPEN_READWRITE = 0x00000002

/** Probe without Room, migration, repair, or creation of a missing database. */
fun probeDatabaseVersion(path: String): Int = BundledSQLiteDriver().open(path, OPEN_READONLY).use(::readSchemaVersion)

private fun readSchemaVersion(connection: SQLiteConnection): Int = connection.prepare("PRAGMA user_version").use { statement ->
    check(statement.step()) { "数据库版本无法读取" }
    statement.getLong(0).toInt()
}

/** Caller has stopped every writer and will copy only after this function closes its connection. */
fun checkpointDatabaseForCopy(path: String, expectedVersion: Int) {
    require(expectedVersion in 1..CURRENT_DATABASE_VERSION) { "不支持的数据库版本" }
    BundledSQLiteDriver().open(path, OPEN_READWRITE).use { connection ->
        check(readSchemaVersion(connection) == expectedVersion) { "数据库版本发生变化，未开始复制" }
        connection.execSQL("PRAGMA busy_timeout=5000")
        connection.execSQL("PRAGMA synchronous=FULL")
        connection.execSQL("PRAGMA foreign_keys=ON")
        connection.prepare("PRAGMA integrity_check").use { statement ->
            check(statement.step() && statement.getText(0) == "ok" && !statement.step()) { "数据库完整性检查失败" }
        }
        connection.prepare("PRAGMA foreign_key_check").use { statement ->
            check(!statement.step()) { "数据库存在无效关联" }
        }
        connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { statement ->
            check(statement.step()) { "数据库检查点未返回结果" }
            val busy = statement.getLong(0)
            val frames = statement.getLong(1)
            val copied = statement.getLong(2)
            check(busy == 0L && frames == copied && (frames == 0L || frames == -1L)) { "数据库仍有活动连接，未完成检查点" }
        }
        check(readSchemaVersion(connection) == expectedVersion) { "数据库版本发生变化，复制已停止" }
    }
}
