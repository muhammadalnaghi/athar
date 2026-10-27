package com.athar.core.data.ledger.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds archival/diagnostics without inventing payloads for existing schema9 rows. */
internal object CanonicalEvidenceSchema {
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canonical_ledger_observation_snapshot` (
                    `sourceNamespace` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `sourcePayloadDigest` TEXT NOT NULL,
                    `snapshotDigest` TEXT NOT NULL,
                    `sourcePayload` BLOB NOT NULL,
                    `snapshotJson` TEXT NOT NULL,
                    PRIMARY KEY(`sourceNamespace`, `sourceId`, `sourcePayloadDigest`, `snapshotDigest`),
                    FOREIGN KEY(`sourceNamespace`, `sourceId`)
                        REFERENCES `canonical_ledger_source_observation`(`sourceNamespace`, `sourceId`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canonical_ledger_conflict` (
                    `conflictId` TEXT NOT NULL,
                    `sourceNamespace` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `sourcePayloadDigest` TEXT NOT NULL,
                    `snapshotDigest` TEXT NOT NULL,
                    `existingEventId` TEXT,
                    `reasonCode` TEXT NOT NULL,
                    `detailJson` TEXT,
                    PRIMARY KEY(`conflictId`),
                    FOREIGN KEY(`sourceNamespace`, `sourceId`, `sourcePayloadDigest`, `snapshotDigest`)
                        REFERENCES `canonical_ledger_observation_snapshot`(`sourceNamespace`, `sourceId`, `sourcePayloadDigest`, `snapshotDigest`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`existingEventId`)
                        REFERENCES `canonical_ledger_event`(`eventId`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_canonical_ledger_conflict_sourceNamespace_sourceId_sourcePayloadDigest_snapshotDigest`
                ON `canonical_ledger_conflict` (`sourceNamespace`, `sourceId`, `sourcePayloadDigest`, `snapshotDigest`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_canonical_ledger_conflict_existingEventId`
                ON `canonical_ledger_conflict` (`existingEventId`)
                """.trimIndent(),
            )
        }
    }
}
