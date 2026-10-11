package forge.kirion.laundry.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.util.UUID

/**
 * Explicit plaintext v1 -> encrypted Room v1 migration.
 *
 * Caller must not create LaundryDatabase before invoking openOrMigrate(). The source is only
 * read (never modified), the secure staging file is verified, then promoted. Any failure
 * preserves the legacy source. This is not a substitute for an owner-controlled backup.
 */
object LegacyMigration {
    private const val PREFS = "kirion_migration_v1"
    private const val PROMOTED = "promoted_and_verified"
    private val TABLES = listOf("customers", "services", "orders", "order_lines",
        "payments", "stage_events")

    suspend fun openOrMigrate(context: Context): SecureLocalDatabase =
        withContext(Dispatchers.IO) { migrateOnIoThread(context.applicationContext) }

    @Synchronized
    internal fun migrateOnIoThread(context: Context): SecureLocalDatabase {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Database migration must run off the Android UI thread"
        }
        val legacyFile = context.getDatabasePath(SecureLocalDatabaseFactory.LEGACY)
        val targetFile = context.getDatabasePath(SecureLocalDatabaseFactory.TARGET)
        val state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!legacyFile.exists()) {
            val fresh = SecureLocalDatabaseFactory.open(context)
            kotlinx.coroutines.runBlocking {
                fresh.withTransaction {
                    val dao = fresh.secureDao()
                    if (dao.getMeta("device_id") == null)
                        dao.putMeta(SecureMeta("device_id", UUID.randomUUID().toString()))
                    if (dao.getMeta("catalog_source") == null)
                        dao.putMeta(SecureMeta("catalog_source", "UNVERIFIED_LEGACY"))
                }
            }
            return fresh
        }

        if (targetFile.exists()) {
            check(state.getBoolean(PROMOTED, false)) {
                "Both legacy and encrypted files exist without completed promotion; " +
                "owner-assisted recovery needed. Neither database was overwritten."
            }
            return SecureLocalDatabaseFactory.open(context)
        }
        check(!state.getBoolean(PROMOTED, false)) {
            "Previously promoted database is missing; refusing silent empty replacement"
        }

        val source = SQLiteDatabase.openDatabase(
            legacyFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        val stagingName = "kirion-stage-" + UUID.randomUUID().toString() + ".db"
        var staging: SecureLocalDatabase? = null
        try {
            check(source.version == 1) { "Unsupported legacy schema version" }
            assertPragmaOk(source)
            val sourceRows = TABLES.associateWith { count(source, it) }
            val sourcePaid = scalar(source,
                "SELECT COALESCE(SUM(amount_cents),0) FROM payments")
            val sourceOrderPaid = scalar(source,
                "SELECT COALESCE(SUM(paid_cents),0) FROM orders")
            check(sourcePaid == sourceOrderPaid) { "Legacy payment totals do not reconcile" }
            val ticketSequence = scalar(source,
                "SELECT value FROM meta WHERE key='ticket_sequence'")
            check(ticketSequence >= 0) { "Invalid ticket sequence" }

            val target = SecureLocalDatabaseFactory.openNamed(context, stagingName)
            staging = target
            // All target rows commit atomically; no live secure DB is modified until promotion.
            kotlinx.coroutines.runBlocking {
                target.withTransaction {
                    val dao = target.secureDao()
                    walk(source, "SELECT id,name,phone,created_at,updated_at,deleted FROM customers") { c ->
                        dao.insertCustomer(SecureCustomer(c.str(0), c.str(1), c.str(2),
                            c.getLong(3), c.getLong(4), c.getInt(5) != 0))
                    }
                    walk(source, "SELECT id,name,unit,price_cents,turnaround_hours,active,created_at,updated_at FROM services") { c ->
                        dao.insertService(SecureService(c.str(0), c.str(1), c.str(2),
                            c.getLong(3), c.getInt(4), null, c.getInt(5) != 0,
                            c.getLong(6), c.getLong(7)))
                    }
                    walk(source, "SELECT id,ticket,customer_id,stage,total_cents,paid_cents,promised_at,created_at,updated_at,deleted FROM orders") { c ->
                        val total = c.getLong(4); val paid = c.getLong(5)
                        check(total >= 0 && paid in 0..total) { "Invalid legacy order balance" }
                        dao.insertOrder(SecureOrder(c.str(0), c.str(1), c.str(2), c.str(3),
                            total, paid, c.getLong(6), c.getLong(7), c.getLong(8), c.getInt(9) != 0))
                    }
                    walk(source, "SELECT id,order_id,service_id,service_name,unit,quantity,unit_price_cents,line_total_cents,note FROM order_lines") { c ->
                        val precision = BigDecimal.valueOf(c.getDouble(5)).stripTrailingZeros()
                        check(precision.signum() > 0 && precision.scale() <= 2) {
                            "Legacy quantity is not representable to 2 decimals"
                        }
                        dao.insertOrderLine(SecureOrderLine(c.str(0), c.str(1), c.str(2),
                            c.str(3), c.str(4), precision.toPlainString(), c.getLong(6),
                            c.getLong(7), null, c.str(8)))
                    }
                    walk(source, "SELECT id,order_id,amount_cents,method,reference,created_at FROM payments") { c ->
                        check(c.getLong(2) > 0) { "Invalid legacy payment" }
                        dao.insertPayment(SecurePayment(c.str(0), c.str(1), c.getLong(2),
                            c.str(3), c.str(4), c.getLong(5)))
                    }
                    walk(source, "SELECT id,order_id,from_stage,to_stage,created_at FROM stage_events") { c ->
                        dao.insertStage(SecureStageEvent(c.str(0), c.str(1),
                            if (c.isNull(2)) null else c.str(2), c.str(3), c.getLong(4)))
                    }
                    dao.putMeta(SecureMeta("legacy_ticket_sequence", ticketSequence.toString()))
                    dao.putMeta(SecureMeta("catalog_source", "UNVERIFIED_LEGACY"))
                    dao.putMeta(SecureMeta("device_id", UUID.randomUUID().toString()))
                    dao.putMeta(SecureMeta("origin_v1_migration", "completed"))
                }
            }
            val actual = mapOf(
                "customers" to secureCount(target, "secure_customers"),
                "services" to secureCount(target, "secure_services"),
                "orders" to secureCount(target, "secure_orders"),
                "order_lines" to secureCount(target, "secure_order_lines"),
                "payments" to secureCount(target, "secure_payments"),
                "stage_events" to secureCount(target, "secure_stage_events")
            )
            check(sourceRows == actual) { "Migration row totals differ: $sourceRows != $actual" }
            val securePaid = secureScalar(target,
                "SELECT COALESCE(SUM(amount_cents),0) FROM secure_payments")
            val secureOrderPaid = secureScalar(target,
                "SELECT COALESCE(SUM(paid_cents),0) FROM secure_orders")
            check(securePaid == sourcePaid && secureOrderPaid == sourceOrderPaid) {
                "Migration payment totals differ"
            }
            // Check per-order reconciliation, not only global totals; legacy stage audit is preserved.
            val mismatches = secureScalar(target, """
                SELECT COUNT(*) FROM secure_orders o
                WHERE o.paid_cents != COALESCE(
                  (SELECT SUM(p.amount_cents) FROM secure_payments p WHERE p.order_id=o.id),0)
                OR o.total_cents != COALESCE(
                  (SELECT SUM(l.line_total_cents) FROM secure_order_lines l WHERE l.order_id=o.id),0)
            """.trimIndent())
            check(mismatches == 0L) { "Per-order migration reconciliation failed" }
            val cipher = target.openHelper.readableDatabase
            cipher.query("PRAGMA integrity_check").use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok")
            }
            target.close()
            staging = null
            val stagingFile = context.getDatabasePath(stagingName)
            check(stagingFile.exists()) { "Encrypted migration staging file missing" }
            check(!targetFile.exists()) { "Existing encrypted database must never be overwritten" }
            // Room uses TRUNCATE journal mode, so the target is a single closed SQLite file.
            check(stagingFile.renameTo(targetFile)) { "Atomic migration promotion failed" }
            check(state.edit().putBoolean(PROMOTED, true).commit()) {
                "Migration promotion state could not be committed; owner recovery needed"
            }
            // Original plaintext remains untouched for owner-assisted rollback until backup is verified.
            return SecureLocalDatabaseFactory.open(context).also {
                check(secureCount(it, "secure_orders") == sourceRows.getValue("orders"))
            }
        } finally {
            staging?.close()
            source.close()
            // Only unpromoted temporary staging files may be deleted.
            val stage = context.getDatabasePath(stagingName)
            if (stage.exists()) stage.delete()
            File(stage.path + "-journal").delete()
            File(stage.path + "-wal").delete()
            File(stage.path + "-shm").delete()
        }
    }

    private fun Cursor.str(index: Int) = getString(index) ?: ""
    private suspend fun walk(db: SQLiteDatabase, sql: String, block: suspend (Cursor) -> Unit) {
        db.rawQuery(sql, null).use { c -> while (c.moveToNext()) block(c) }
    }
    private fun assertPragmaOk(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check", null).use { c ->
            check(c.moveToFirst() && c.getString(0) == "ok") { "Legacy integrity check failed" }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
            check(!c.moveToFirst()) { "Legacy foreign key violation" }
        }
    }
    private fun count(db: SQLiteDatabase, table: String): Long =
        scalar(db, "SELECT COUNT(*) FROM $table")
    private fun scalar(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { c -> check(c.moveToFirst()); c.getLong(0) }
    private fun secureCount(db: SecureLocalDatabase, table: String): Long =
        secureScalar(db, "SELECT COUNT(*) FROM $table")
    private fun secureScalar(db: SecureLocalDatabase, sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { c ->
            check(c.moveToFirst()); c.getLong(0)
        }
}
