package forge.kirion.laundry.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Portable *logical* encrypted snapshot for a freshly provisioned replacement Android device.
 * No plaintext database file or JSON backup is written to disk by this class.
 * Exports use a consistent Room read transaction; imports stage and validate before promotion.
 *
 * P1 does not include role checks. Do not expose this API to ordinary staff until P2 owner
 * authorization is enforced; the methods are used by instrumentation tests for now.
 */
object PortableLaundryBackup {
    private const val FORMAT = 1
    private val TABLES = listOf(
        "secure_customers", "secure_services", "secure_orders", "secure_order_lines",
        "secure_payments", "secure_stage_events", "secure_outbox", "secure_meta"
    )

    suspend fun exportEncrypted(db: SecureLocalDatabase, passphrase: CharArray): ByteArray =
        withContext(Dispatchers.IO) {
            val plaintext = db.withTransaction {
                val root = JSONObject().put("format", FORMAT)
                val tables = JSONObject()
                val connection = db.openHelper.readableDatabase
                TABLES.forEach { table ->
                    val records = JSONArray()
                    connection.query("SELECT * FROM $table").use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            for (i in cursor.columnNames.indices) {
                                val key = cursor.getColumnName(i)
                                when (cursor.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> row.put(key, JSONObject.NULL)
                                    Cursor.FIELD_TYPE_INTEGER -> row.put(key, cursor.getLong(i))
                                    Cursor.FIELD_TYPE_FLOAT -> row.put(key, cursor.getDouble(i))
                                    Cursor.FIELD_TYPE_STRING -> row.put(key, cursor.getString(i))
                                    else -> error("Binary database fields are not supported in backup v1")
                                }
                            }
                            records.put(row)
                        }
                    }
                    tables.put(table, records)
                }
                root.put("tables", tables)
                root.toString().toByteArray(Charsets.UTF_8)
            }
            try { PortableBackupCipher.encrypt(plaintext, passphrase) }
            finally { plaintext.fill(0) }
        }

    /**
     * Restore to an empty replacement installation only. Intentionally refuses to overwrite
     * existing active/legacy data; overwriting requires owner-authorized P2 recovery procedure.
     */
    suspend fun restoreToFreshDevice(
        context: Context, backup: ByteArray, passphrase: CharArray
    ): SecureLocalDatabase = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = app.getDatabasePath(SecureLocalDatabaseFactory.TARGET)
        val legacy = app.getDatabasePath(SecureLocalDatabaseFactory.LEGACY)
        check(!target.exists() && !legacy.exists()) {
            "Restore requires an empty replacement installation; existing records preserved"
        }
        // Authenticate entire ciphertext BEFORE any new database is created or changed.
        val plaintext = PortableBackupCipher.decrypt(backup, passphrase)
        val payload = try {
            JSONObject(String(plaintext, Charsets.UTF_8)).also {
                check(it.getInt("format") == FORMAT) { "Unsupported logical backup format" }
            }
        } finally { plaintext.fill(0) }
        val tables = payload.getJSONObject("tables")
        check(tables.length() == TABLES.size &&
            TABLES.all { tables.has(it) }) { "Missing or unexpected backup tables" }
        val stagingName = "kirion-restore-" + UUID.randomUUID() + ".db"
        var stage: SecureLocalDatabase? = null
        try {
            val db = SecureLocalDatabaseFactory.openNamed(app, stagingName)
            stage = db
            db.withTransaction {
                val connection = db.openHelper.writableDatabase
                for (table in TABLES) {
                    val rows = tables.getJSONArray(table)
                    check(rows.length() <= 200000) { "Backup table too large" }
                    for (index in 0 until rows.length()) {
                        val record = rows.getJSONObject(index)
                        val columns = ContentValues()
                        val keys = record.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = record.get(key)
                            when (value) {
                                JSONObject.NULL -> columns.putNull(key)
                                is Boolean -> columns.put(key, if (value) 1 else 0)
                                is Int -> columns.put(key, value)
                                is Long -> columns.put(key, value)
                                is Double -> columns.put(key, value)
                                is String -> columns.put(key, value)
                                else -> error("Unsupported field in backup")
                            }
                        }
                        connection.insert(table, SQLiteDatabase.CONFLICT_ABORT, columns)
                    }
                }
            }
            // Validate exact restored snapshot before appending replacement-device metadata.
            validate(db, tables)
            db.withTransaction {
                // A restored device must never impersonate the original device.
                db.secureDao().putMeta(SecureMeta("device_id", UUID.randomUUID().toString()))
                db.secureDao().putMeta(SecureMeta("restore_state", "REQUIRES_ENROLLMENT"))
            }
            db.close()
            stage = null
            check(!target.exists()) { "Destination appeared during restore" }
            check(app.getDatabasePath(stagingName).renameTo(target)) {
                "Verified restored database could not be promoted"
            }
            SecureLocalDatabaseFactory.open(app)
        } finally {
            stage?.close()
            val file = app.getDatabasePath(stagingName)
            file.delete()
            File(file.path + "-journal").delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    private fun validate(db: SecureLocalDatabase, original: JSONObject) {
        val connection = db.openHelper.readableDatabase
        connection.query("PRAGMA integrity_check").use { c ->
            check(c.moveToFirst() && c.getString(0) == "ok") { "Restored database integrity failed" }
        }
        for (table in TABLES) {
            val expected = original.getJSONArray(table).length().toLong()
            connection.query("SELECT COUNT(*) FROM $table").use { c ->
                check(c.moveToFirst() && c.getLong(0) == expected) {
                    "Restored table row count mismatch"
                }
            }
        }
        connection.query("""
            SELECT COUNT(*) FROM secure_orders o
            WHERE o.total_cents < 0 OR o.paid_cents < 0 OR o.paid_cents > o.total_cents
              OR o.paid_cents != COALESCE(
                (SELECT SUM(p.amount_cents) FROM secure_payments p WHERE p.order_id=o.id), 0)
              OR o.total_cents != COALESCE(
                (SELECT SUM(l.line_total_cents) FROM secure_order_lines l WHERE l.order_id=o.id),0)
        """.trimIndent()).use { c ->
            check(c.moveToFirst() && c.getLong(0) == 0L) {
                "Restored order or payment invariants failed"
            }
        }
        connection.query("""
            SELECT COUNT(*) FROM secure_order_lines l
            LEFT JOIN secure_orders o ON o.id=l.order_id WHERE o.id IS NULL
        """.trimIndent()).use { c ->
            check(c.moveToFirst() && c.getLong(0) == 0L) { "Orphan restored order line" }
        }
    }
}
