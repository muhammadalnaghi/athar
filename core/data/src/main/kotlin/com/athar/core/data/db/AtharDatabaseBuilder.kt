package com.athar.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.athar.core.data.ledger.db.CanonicalLedgerSchema

/**
 * The single production Room configuration for AtharDatabase.
 *
 * WM028-13B0/B1/B2a: the builder registers every migration through11 and
 * deliberately enables NO `fallbackToDestructiveMigration*` behavior — an
 * unsupported open (for example a downgrade from a future schema version) must
 * fail closed and leave every database artifact in place for explicit recovery.
 *
 * Production (DataModule, with the SQLCipher helper) and the instrumentation
 * downgrade regression both build through this function, so the tested migration
 * configuration is exactly the shipped one. The SQLCipher open-helper factory
 * remains production-only, as today.
 *
 * The `onCreate` callback performs the only metadata initialization: it seeds
 * the canonical-ledger revision singleton for freshly created databases with
 * the same statement `MIGRATION_8_9` uses for migrated databases
 * ([CanonicalLedgerSchema.SEED_REVISION_SINGLETON_SQL]). It never rewrites an
 * existing ledger and runs on creation only — upgrades keep their migration path.
 */
internal fun atharDatabaseBuilder(
    context: Context,
    databaseName: String,
): RoomDatabase.Builder<AtharDatabase> = Room.databaseBuilder(context, AtharDatabase::class.java, databaseName)
    .addMigrations(
        AtharDatabase.MIGRATION_1_2,
        AtharDatabase.MIGRATION_2_3,
        AtharDatabase.MIGRATION_3_4,
        AtharDatabase.MIGRATION_4_5,
        AtharDatabase.MIGRATION_5_6,
        AtharDatabase.MIGRATION_6_7,
        AtharDatabase.MIGRATION_7_8,
        AtharDatabase.MIGRATION_8_9,
        AtharDatabase.MIGRATION_9_10,
        AtharDatabase.MIGRATION_10_11,
    )
    .addCallback(
        object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(CanonicalLedgerSchema.SEED_REVISION_SINGLETON_SQL)
            }
        },
    )
