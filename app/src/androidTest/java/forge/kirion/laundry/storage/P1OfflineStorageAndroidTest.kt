package forge.kirion.laundry.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forge.kirion.laundry.LaundryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real Android SQLite/SQLCipher instrumentation on a disposable emulator.
 * No test customer PII; does not substitute for real-phone or cross-phone verification.
 */
@RunWith(AndroidJUnit4::class)
class P1OfflineStorageAndroidTest {
    private lateinit var app: Context

    @Before
    fun cleanSandbox() {
        app = ApplicationProvider.getApplicationContext()
        for (file in listOf(
            SecureLocalDatabaseFactory.LEGACY, SecureLocalDatabaseFactory.TARGET
        )) {
            app.deleteDatabase(file)
            File(app.getDatabasePath(file).path + "-wal").delete()
            File(app.getDatabasePath(file).path + "-shm").delete()
        }
        app.getSharedPreferences("kirion_migration_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Instrumentation tests create disposable app installations; production never clears keys.
        app.getSharedPreferences("kirion_database_key_metadata", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun migratesPlaintextV1WithoutLosingMoneyOrLedgerAndEncryptsFile() = runBlocking {
        val old = LaundryDatabase(app)
        val services = old.services()
        val price = services.first { it.name == "Wash-Dry-Fold" }
        val ticket = old.createOrder(
            "Test Customer Alpha", "0000000000", price.id, "2.50", "50.00", "Cash"
        )
        val before = old.orders().single()
        assertEquals(18750L, before.totalCents)
        assertEquals(5000L, before.paidCents)
        old.close()

        val db = LegacyMigration.openOrMigrate(app)
        val records = db.openHelper.readableDatabase
        records.query("SELECT ticket,total_cents,paid_cents FROM secure_orders").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(ticket, c.getString(0))
            assertEquals(18750L, c.getLong(1))
            assertEquals(5000L, c.getLong(2))
        }
        records.query("SELECT COUNT(*) FROM secure_payments").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0))
        }
        records.query("SELECT COUNT(*) FROM secure_stage_events").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0))
        }
        records.query("SELECT quantity_decimal,unit_price_cents FROM secure_order_lines").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("2.5", c.getString(0))
            assertEquals(7500L, c.getLong(1))
        }
        db.close()

        val file = app.getDatabasePath(SecureLocalDatabaseFactory.TARGET)
        assertTrue(file.exists())
        assertFalse(file.inputStream().use { bytes ->
            val first = ByteArray(16); bytes.read(first)
            String(first, Charsets.US_ASCII).startsWith("SQLite format 3")
        })
        assertTrue(app.getDatabasePath(SecureLocalDatabaseFactory.LEGACY).exists())
        val reopened = LegacyMigration.openOrMigrate(app)
        reopened.openHelper.readableDatabase.query("SELECT COUNT(*) FROM secure_orders").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
        }
        reopened.close()
    }

    @Test
    fun encryptedPortableBackupRestoresToFreshInstallationAndRejectsBadPasswords() = runBlocking {
        val old = LaundryDatabase(app)
        val service = old.services().first { it.unit == "kg" }
        old.createOrder("Test Customer Beta", "", service.id, "1.75", "20.00", "Cash")
        old.close()
        val migrated = LegacyMigration.openOrMigrate(app)
        val pass = "unique owner recovery phrase for test".toCharArray()
        val backup = PortableLaundryBackup.exportEncrypted(migrated, pass)
        migrated.close()

        val target = app.getDatabasePath(SecureLocalDatabaseFactory.TARGET)
        val oldFile = app.getDatabasePath(SecureLocalDatabaseFactory.LEGACY)
        val originalFile = target.readBytes()
        assertThrows(Exception::class.java) {
            runBlocking {
                PortableLaundryBackup.restoreToFreshDevice(
                    app, backup, "incorrect recovery phrase".toCharArray()
                )
            }
        }
        assertTrue(target.readBytes().contentEquals(originalFile))

        // This emulates a *fresh app installation* on one emulator. Separate-device
        // Keystore/enrollment testing is still a manual/pipeline acceptance gate.
        app.deleteDatabase(SecureLocalDatabaseFactory.TARGET)
        app.deleteDatabase(SecureLocalDatabaseFactory.LEGACY)
        val recovered = PortableLaundryBackup.restoreToFreshDevice(app, backup, pass)
        recovered.openHelper.readableDatabase.query(
            "SELECT total_cents,paid_cents FROM secure_orders"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(13125L, it.getLong(0))
            assertEquals(2000L, it.getLong(1))
        }
        recovered.openHelper.readableDatabase.query(
            "SELECT value FROM secure_meta WHERE key='restore_state'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("REQUIRES_ENROLLMENT", it.getString(0))
        }
        recovered.close()
    }
    @Test
    fun freshEncryptedRepositoryPerformsOfflineIntakeToPaidPickupAndOutboxSurvivesRestart() = runBlocking {
        val db = LegacyMigration.openOrMigrate(app)
        val repository = SecureLaundryRepository(db)
        repository.addService("Test Laundry Service", "kg", "75.00", "24")
        val service = repository.snapshot().services.single()
        val ticket = repository.createOrder(
            "Test Customer Gamma", "0000000011", service.id, "2.5", "50.00", "Cash"
        )
        var order = repository.snapshot().orders.single()
        assertEquals(ticket, order.ticket)
        assertEquals(18750L, order.totalCents)
        assertEquals(5000L, order.paidCents)
        for (stage in listOf("WASHING", "DRYING", "FOLDING", "READY")) {
            repository.advance(order.id)
            order = repository.snapshot().orders.single()
            assertEquals(stage, order.stage.name)
        }
        try {
            repository.advance(order.id)
            throw AssertionError("Pickup of unpaid laundry should have been rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: no payment, no pickup.
        }
        repository.recordPayment(order.id, "137.50", "Cash", "REC-TEST")
        repository.advance(order.id)
        order = repository.snapshot().orders.single()
        assertEquals("PICKED_UP", order.stage.name)
        assertEquals(18750L, order.paidCents)
        assertTrue(repository.snapshot().pendingSync > 0)
        db.close()
        val reopened = LegacyMigration.openOrMigrate(app)
        val persisted = SecureLaundryRepository(reopened).snapshot()
        assertEquals("PICKED_UP", persisted.orders.single().stage.name)
        assertTrue(persisted.pendingSync > 0)
        reopened.close()
    }

}
