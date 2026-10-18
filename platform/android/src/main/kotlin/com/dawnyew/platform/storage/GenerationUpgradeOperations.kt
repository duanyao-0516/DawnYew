package com.dawnyew.platform.storage

import com.dawnyew.storage.CURRENT_DATABASE_VERSION
import com.dawnyew.storage.checkpointDatabaseForCopy
import com.dawnyew.storage.probeDatabaseVersion
import java.io.File

/** Raw SQLite access deliberately precedes Room, which would otherwise migrate the source on open. */
internal open class GenerationUpgradeOperations {
    open val currentVersion: Int get() = CURRENT_DATABASE_VERSION
    open fun version(database: File): Int = probeDatabaseVersion(database.absolutePath)
    open fun checkpoint(database: File, expectedVersion: Int) = checkpointDatabaseForCopy(database.absolutePath, expectedVersion)
}
