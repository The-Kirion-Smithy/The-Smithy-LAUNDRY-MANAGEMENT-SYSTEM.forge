package forge.kirion.laundry

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * RP-001 local-only persistence.
 * This is deliberately NOT the production encrypted/role-gated database.
 * Every money/order/stage mutation is atomic and keeps append-only event rows.
 */
class LaundryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "kirion_laundry_prototype.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE customers (id TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX customers_search ON customers(name, phone)")
        db.execSQL("CREATE TABLE services (id TEXT PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL, price_cents INTEGER NOT NULL CHECK(price_cents >= 0), turnaround_hours INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE orders (id TEXT PRIMARY KEY, ticket TEXT NOT NULL UNIQUE, customer_id TEXT NOT NULL REFERENCES customers(id), stage TEXT NOT NULL, total_cents INTEGER NOT NULL, paid_cents INTEGER NOT NULL DEFAULT 0, promised_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX orders_stage ON orders(stage, created_at)")
        db.execSQL("CREATE TABLE order_lines (id TEXT PRIMARY KEY, order_id TEXT NOT NULL REFERENCES orders(id), service_id TEXT NOT NULL REFERENCES services(id), service_name TEXT NOT NULL, unit TEXT NOT NULL, quantity REAL NOT NULL, unit_price_cents INTEGER NOT NULL, line_total_cents INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE payments (id TEXT PRIMARY KEY, order_id TEXT NOT NULL REFERENCES orders(id), amount_cents INTEGER NOT NULL CHECK(amount_cents > 0), method TEXT NOT NULL, reference TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE stage_events (id TEXT PRIMARY KEY, order_id TEXT NOT NULL REFERENCES orders(id), from_stage TEXT, to_stage TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO meta(key,value) VALUES('ticket_sequence',0)")
        seedService(db, "Wash-Dry-Fold", "kg", 7500, 48)
        seedService(db, "Ironing", "item", 2500, 24)
        seedService(db, "Bedding", "flat", 25000, 72)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive fallback: upgrades require an explicit tested migration.
        throw IllegalStateException("Prototype database requires an explicit migration")
    }

    private fun seedService(db: SQLiteDatabase, name: String, unit: String, price: Long, hours: Int) {
        val now = System.currentTimeMillis()
        db.insertOrThrow("services", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("unit", unit)
            put("price_cents", price)
            put("turnaround_hours", hours)
            put("active", 1)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun customers(): List<LaundryCustomer> {
        val result = mutableListOf<LaundryCustomer>()
        readableDatabase.rawQuery(
            "SELECT id, name, phone FROM customers WHERE deleted=0 ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) result.add(LaundryCustomer(c.getString(0), c.getString(1), c.getString(2)))
        }
        return result
    }

    fun services(): List<LaundryService> {
        val result = mutableListOf<LaundryService>()
        readableDatabase.rawQuery(
            "SELECT id,name,unit,price_cents,turnaround_hours FROM services WHERE active=1 ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) result.add(
                LaundryService(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))
            )
        }
        return result
    }

    fun addCustomer(nameInput: String, phoneInput: String) {
        val name = nameInput.trim()
        require(name.isNotBlank() && name.length <= 100) { "Customer name is required (max 100 characters)" }
        val phone = phoneInput.trim()
        require(phone.length <= 40) { "Phone is too long" }
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("customers", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("phone", phone)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun addService(nameInput: String, unit: String, priceInput: String, hoursInput: String) {
        val name = nameInput.trim()
        require(name.isNotBlank() && name.length <= 100) { "Service name is required (max 100 characters)" }
        require(unit in listOf("kg", "item", "flat")) { "Invalid unit" }
        val price = parseCents(priceInput)
        val hours = hoursInput.toIntOrNull()
        require(hours != null && hours in 1..720) { "Turnaround must be 1–720 hours" }
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("services", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("unit", unit)
            put("price_cents", price)
            put("turnaround_hours", hours)
            put("active", 1)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun createOrder(
        nameInput: String, phoneInput: String, serviceId: String,
        quantityInput: String, depositInput: String, paymentMethod: String
    ): String {
        val name = nameInput.trim()
        val phone = phoneInput.trim()
        require(name.isNotBlank() && name.length <= 100) { "Customer name is required" }
        require(phone.length <= 40) { "Phone is too long" }
        val deposit = parseCents(depositInput.ifBlank { "0" })
        checkMethod(paymentMethod)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val service = db.rawQuery(
                "SELECT name,unit,price_cents,turnaround_hours FROM services WHERE id=? AND active=1",
                arrayOf(serviceId)
            ).use { c ->
                require(c.moveToFirst()) { "Choose an existing service" }
                LaundryService(serviceId, c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))
            }
            val total = lineTotalCents(service.priceCents, quantityInput, service.unit)
            require(deposit <= total) { "Deposit cannot exceed the total" }
            val quantity = if (service.unit == "flat") 1.0 else BigDecimal(quantityInput.trim()).toDouble()
            val now = System.currentTimeMillis()
            val customerId = db.rawQuery(
                "SELECT id FROM customers WHERE name=? AND phone=? AND deleted=0 LIMIT 1", arrayOf(name, phone)
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else {
                    val id = UUID.randomUUID().toString()
                    db.insertOrThrow("customers", null, ContentValues().apply {
                        put("id", id); put("name", name); put("phone", phone)
                        put("created_at", now); put("updated_at", now)
                    })
                    id
                }
            }
            val sequence = db.rawQuery("SELECT value FROM meta WHERE key='ticket_sequence'", null).use { c ->
                check(c.moveToFirst()) { "Missing ticket sequence" }
                c.getLong(0) + 1L
            }
            db.execSQL("UPDATE meta SET value=? WHERE key='ticket_sequence'", arrayOf(sequence))
            val ticket = "L-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) +
                "-" + sequence.toString().padStart(6, '0')
            val orderId = UUID.randomUUID().toString()
            db.insertOrThrow("orders", null, ContentValues().apply {
                put("id", orderId); put("ticket", ticket)
                put("customer_id", customerId); put("stage", Stage.RECEIVED.name)
                put("total_cents", total); put("paid_cents", deposit)
                put("promised_at", now + service.turnaroundHours * 3_600_000L)
                put("created_at", now); put("updated_at", now)
            })
            db.insertOrThrow("order_lines", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("order_id", orderId); put("service_id", service.id)
                put("service_name", service.name); put("unit", service.unit)
                put("quantity", quantity); put("unit_price_cents", service.priceCents)
                put("line_total_cents", total)
            })
            db.insertOrThrow("stage_events", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("order_id", orderId); putNull("from_stage")
                put("to_stage", Stage.RECEIVED.name); put("created_at", now)
            })
            if (deposit > 0) {
                db.insertOrThrow("payments", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("order_id", orderId); put("amount_cents", deposit)
                    put("method", paymentMethod); put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
            return ticket
        } finally { db.endTransaction() }
    }

    fun orders(): List<LaundryOrder> {
        val result = mutableListOf<LaundryOrder>()
        readableDatabase.rawQuery(
            """SELECT o.id,o.ticket,c.name,l.service_name,l.quantity,l.unit,o.stage,
                      o.total_cents,o.paid_cents,o.promised_at,o.created_at
               FROM orders o JOIN customers c ON c.id=o.customer_id
               JOIN order_lines l ON l.order_id=o.id
               WHERE o.deleted=0 ORDER BY o.created_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) result.add(
                LaundryOrder(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getDouble(4), c.getString(5), Stage.valueOf(c.getString(6)),
                    c.getLong(7), c.getLong(8), c.getLong(9), c.getLong(10)
                )
            )
        }
        return result
    }

    fun advance(orderId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery(
                "SELECT stage,total_cents,paid_cents FROM orders WHERE id=? AND deleted=0", arrayOf(orderId)
            ).use { c ->
                require(c.moveToFirst()) { "Order not found" }
                Triple(Stage.valueOf(c.getString(0)), c.getLong(1), c.getLong(2))
            }
            val next = current.first.next() ?: throw IllegalArgumentException("Already picked up")
            require(next != Stage.PICKED_UP || current.second == current.third) {
                "Collect the full balance before pickup"
            }
            val now = System.currentTimeMillis()
            db.update("orders", ContentValues().apply {
                put("stage", next.name); put("updated_at", now)
            }, "id=?", arrayOf(orderId))
            db.insertOrThrow("stage_events", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("order_id", orderId)
                put("from_stage", current.first.name); put("to_stage", next.name); put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun recordPayment(orderId: String, amountInput: String, method: String, referenceInput: String) {
        checkMethod(method)
        val amount = parseCents(amountInput)
        require(amount > 0) { "Payment must be greater than zero" }
        val reference = referenceInput.trim()
        require(reference.length <= 150) { "Reference is too long" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val balance = db.rawQuery(
                "SELECT total_cents-paid_cents,stage FROM orders WHERE id=? AND deleted=0", arrayOf(orderId)
            ).use { c ->
                require(c.moveToFirst()) { "Order not found" }
                require(c.getString(1) != Stage.PICKED_UP.name) { "Cannot pay a closed order" }
                c.getLong(0)
            }
            require(amount <= balance) { "Payment exceeds remaining balance" }
            val now = System.currentTimeMillis()
            db.insertOrThrow("payments", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("order_id", orderId)
                put("amount_cents", amount); put("method", method)
                put("reference", reference); put("created_at", now)
            })
            db.execSQL(
                "UPDATE orders SET paid_cents=paid_cents+?,updated_at=? WHERE id=?",
                arrayOf(amount, now, orderId)
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun report(): DayReport {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val db = readableDatabase
        fun count(sql: String, vararg params: String): Long =
            db.rawQuery(sql, params).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        fun decimal(sql: String, vararg params: String): Double =
            db.rawQuery(sql, params).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
        return DayReport(
            received = count("SELECT COUNT(*) FROM orders WHERE created_at>=? AND deleted=0", start.toString()),
            pickedUp = count("SELECT COUNT(*) FROM stage_events WHERE to_stage='PICKED_UP' AND created_at>=?", start.toString()),
            ready = count("SELECT COUNT(*) FROM orders WHERE stage='READY' AND deleted=0"),
            kilograms = decimal(
                """SELECT COALESCE(SUM(l.quantity),0) FROM order_lines l
                   JOIN orders o ON o.id=l.order_id
                   WHERE l.unit='kg' AND o.created_at>=? AND o.deleted=0""", start.toString()
            ),
            collectedCents = count(
                "SELECT COALESCE(SUM(amount_cents),0) FROM payments WHERE created_at>=?", start.toString()
            ),
            cashCents = count(
                "SELECT COALESCE(SUM(amount_cents),0) FROM payments WHERE created_at>=? AND method='Cash'", start.toString()
            ),
            outstandingCents = count(
                "SELECT COALESCE(SUM(total_cents-paid_cents),0) FROM orders WHERE deleted=0 AND stage!='PICKED_UP'"
            )
        )
    }

    private fun checkMethod(method: String) {
        require(method in listOf("Cash", "GCash", "Bank transfer", "Card")) { "Invalid payment method" }
    }
}
