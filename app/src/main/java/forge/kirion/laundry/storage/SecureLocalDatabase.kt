package forge.kirion.laundry.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * P1 ALPHA ONLY.
 * This schema is deliberately separate from the plaintext v1 prototype and is not used
 * by the production UI until safe, instrumented v1 migration and recovery are implemented.
 */
@Entity(tableName = "secure_customers")
data class SecureCustomer(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "secure_services")
data class SecureService(
    @PrimaryKey val id: String,
    val name: String,
    val unit: String,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    @ColumnInfo(name = "turnaround_hours") val turnaroundHours: Int,
    @ColumnInfo(name = "catalog_revision") val catalogRevision: Long?,
    val active: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "secure_orders", indices = [Index(value = ["ticket"], unique = true)])
data class SecureOrder(
    @PrimaryKey val id: String,
    val ticket: String,
    @ColumnInfo(name = "customer_id") val customerId: String,
    val stage: String,
    @ColumnInfo(name = "total_cents") val totalCents: Long,
    @ColumnInfo(name = "paid_cents") val paidCents: Long,
    @ColumnInfo(name = "promised_at") val promisedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "secure_order_lines")
data class SecureOrderLine(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "service_name") val serviceName: String,
    val unit: String,
    @ColumnInfo(name = "quantity_decimal") val quantityDecimal: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "line_total_cents") val lineTotalCents: Long,
    @ColumnInfo(name = "catalog_revision") val catalogRevision: Long?,
    val note: String = ""
)

@Entity(tableName = "secure_payments")
data class SecurePayment(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val method: String,
    val reference: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "secure_stage_events")
data class SecureStageEvent(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "from_stage") val fromStage: String?,
    @ColumnInfo(name = "to_stage") val toStage: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "secure_outbox",
    indices = [Index(value = ["origin_device_id", "origin_sequence"], unique = true)]
)
data class SecureOutbox(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "origin_device_id") val originDeviceId: String,
    @ColumnInfo(name = "origin_sequence") val originSequence: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    val status: String = "PENDING",
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "secure_meta")
data class SecureMeta(@PrimaryKey val key: String, val value: String)

@Dao
interface SecureLocalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: SecureCustomer)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertService(service: SecureService)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrder(order: SecureOrder)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrderLine(line: SecureOrderLine)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: SecurePayment)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStage(event: SecureStageEvent)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(event: SecureOutbox)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: SecureMeta)

    @Query("SELECT * FROM secure_orders WHERE id=:id LIMIT 1")
    suspend fun getOrder(id: String): SecureOrder?

    @Query("SELECT * FROM secure_outbox WHERE status='PENDING' ORDER BY origin_sequence LIMIT :limit")
    suspend fun pendingOutbox(limit: Int): List<SecureOutbox>

    @Query("SELECT COUNT(*) FROM secure_outbox WHERE status='PENDING'")
    suspend fun pendingCount(): Long

    @Query("UPDATE secure_orders SET paid_cents=paid_cents+:amount, updated_at=:whenMillis WHERE id=:id AND deleted=0 AND stage != 'PICKED_UP' AND paid_cents<=total_cents-:amount")
    suspend fun incrementPaidIfAllowed(id: String, amount: Long, whenMillis: Long): Int

    @Transaction
    suspend fun commitOrder(
        order: SecureOrder,
        lines: List<SecureOrderLine>,
        initialStage: SecureStageEvent,
        mutation: SecureOutbox
    ) {
        require(lines.isNotEmpty()) { "At least one order line required" }
        require(order.totalCents >= 0 && order.paidCents == 0L) { "Initial payments must commit through payment ledger" }
        require(lines.all { it.orderId == order.id && it.unitPriceCents >= 0 && it.lineTotalCents >= 0 })
        require(lines.sumOf { it.lineTotalCents } == order.totalCents)
        require(initialStage.orderId == order.id && mutation.aggregateId == order.id)
        require(mutation.eventType == "order.created" && mutation.status == "PENDING")
        insertOrder(order)
        for (line in lines) insertOrderLine(line)
        insertStage(initialStage)
        insertOutbox(mutation)
    }

    @Transaction
    suspend fun commitPayment(payment: SecurePayment, mutation: SecureOutbox) {
        require(payment.amountCents > 0L && payment.amountCents <= 9_000_000_000_000_000L)
        require(mutation.aggregateId == payment.orderId && mutation.eventType == "payment.recorded")
        require(mutation.status == "PENDING")
        val changed = incrementPaidIfAllowed(payment.orderId, payment.amountCents, payment.createdAt)
        require(changed == 1) { "Order closed, missing, or payment exceeds balance" }
        insertPayment(payment)
        insertOutbox(mutation)
    }

    @Transaction
    suspend fun commitCustomer(customer: SecureCustomer, mutation: SecureOutbox) {
        require(customer.name.isNotBlank() && mutation.aggregateId == customer.id)
        require(mutation.eventType == "customer.created" && mutation.status == "PENDING")
        insertCustomer(customer)
        insertOutbox(mutation)
    }
}

@Database(
    entities = [
        SecureCustomer::class, SecureService::class, SecureOrder::class,
        SecureOrderLine::class, SecurePayment::class, SecureStageEvent::class,
        SecureOutbox::class, SecureMeta::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SecureLocalDatabase : RoomDatabase() {
    abstract fun secureDao(): SecureLocalDao
}

/**
 * A separate staging database is created during verified migration/restore.
 * Neither staging nor existing secure data is ever replaced by Room's destructive migration.
 */
object SecureLocalDatabaseFactory {
    const val TARGET = "kirion_lms_secure.db"
    const val LEGACY = "kirion_laundry_prototype.db"

    fun open(context: Context): SecureLocalDatabase {
        val app = context.applicationContext
        check(!app.getDatabasePath(LEGACY).exists() ||
            app.getSharedPreferences("kirion_migration_v1", Context.MODE_PRIVATE)
                .getBoolean("promoted_and_verified", false)) {
            "Unmigrated plaintext v1 database. Use LegacyMigration.openOrMigrate() first."
        }
        return openNamed(app, TARGET)
    }

    internal fun openNamed(context: Context, name: String): SecureLocalDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = DeviceDatabaseKey.getOrCreate(context.applicationContext)
        return try {
            Room.databaseBuilder(
                context.applicationContext, SecureLocalDatabase::class.java, name
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
        } catch (failure: Exception) {
            passphrase.fill(0)
            throw failure
        }
    }
}
