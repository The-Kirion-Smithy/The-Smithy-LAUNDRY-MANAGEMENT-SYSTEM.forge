package forge.kirion.laundry.storage

import android.database.Cursor
import androidx.room.withTransaction
import forge.kirion.laundry.DayReport
import forge.kirion.laundry.LaundryCustomer
import forge.kirion.laundry.LaundryOrder
import forge.kirion.laundry.LaundryService
import forge.kirion.laundry.Stage
import forge.kirion.laundry.lineTotalCents
import forge.kirion.laundry.parseCents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * P1 offline-only repository. Every mutation commits to SQLCipher Room and its immutable
 * local outbox in one transaction. No remote events are acknowledged before hub exists.
 */
class SecureLaundryRepository(private val database: SecureLocalDatabase) {
    data class Snapshot(
        val customers: List<LaundryCustomer>, val services: List<LaundryService>,
        val orders: List<LaundryOrder>, val report: DayReport, val pendingSync: Long
    )

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        val customers = mutableListOf<LaundryCustomer>()
        db.query("SELECT id,name,phone FROM secure_customers WHERE deleted=0 ORDER BY name").use { c ->
            while (c.moveToNext()) customers.add(LaundryCustomer(c.s(0), c.s(1), c.s(2)))
        }
        val services = mutableListOf<LaundryService>()
        db.query("SELECT id,name,unit,price_cents,turnaround_hours FROM secure_services WHERE active=1 ORDER BY name").use { c ->
            while(c.moveToNext()) services.add(LaundryService(c.s(0),c.s(1),c.s(2),c.getLong(3),c.getInt(4)))
        }
        val orders = mutableListOf<LaundryOrder>()
        db.query("""
            SELECT o.id,o.ticket,c.name,l.service_name,CAST(l.quantity_decimal AS REAL),l.unit,
                   o.stage,o.total_cents,o.paid_cents,o.promised_at,o.created_at
            FROM secure_orders o JOIN secure_customers c ON c.id=o.customer_id
            JOIN secure_order_lines l ON l.order_id=o.id
            WHERE o.deleted=0 ORDER BY o.created_at DESC
        """.trimIndent()).use { c ->
            while (c.moveToNext()) orders.add(LaundryOrder(
                c.s(0),c.s(1),c.s(2),c.s(3),c.getDouble(4),c.s(5),
                Stage.valueOf(c.s(6)),c.getLong(7),c.getLong(8),c.getLong(9),c.getLong(10)
            ))
        }
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun count(sql: String, vararg bind: Any): Long =
            db.query(androidx.sqlite.db.SimpleSQLiteQuery(sql, bind)).use { c ->
                check(c.moveToFirst()); c.getLong(0)
            }
        fun decimal(sql: String, vararg bind: Any): Double =
            db.query(androidx.sqlite.db.SimpleSQLiteQuery(sql, bind)).use { c ->
                check(c.moveToFirst()); c.getDouble(0)
            }
        val report = DayReport(
            received=count("SELECT COUNT(*) FROM secure_orders WHERE created_at>=? AND deleted=0",start),
            pickedUp=count("SELECT COUNT(*) FROM secure_stage_events WHERE to_stage='PICKED_UP' AND created_at>=?",start),
            ready=count("SELECT COUNT(*) FROM secure_orders WHERE stage='READY' AND deleted=0"),
            kilograms=decimal("""
                SELECT COALESCE(SUM(CAST(l.quantity_decimal AS REAL)),0)
                FROM secure_order_lines l JOIN secure_orders o ON o.id=l.order_id
                WHERE l.unit='kg' AND o.created_at>=? AND o.deleted=0
            """.trimIndent(),start),
            collectedCents=count("SELECT COALESCE(SUM(amount_cents),0) FROM secure_payments WHERE created_at>=?",start),
            cashCents=count("SELECT COALESCE(SUM(amount_cents),0) FROM secure_payments WHERE created_at>=? AND method='Cash'",start),
            outstandingCents=count("SELECT COALESCE(SUM(total_cents-paid_cents),0) FROM secure_orders WHERE deleted=0 AND stage!='PICKED_UP'")
        )
        Snapshot(customers,services,orders,report,database.secureDao().pendingCount())
    }

    suspend fun addCustomer(nameInput: String, phoneInput: String) = withContext(Dispatchers.IO) {
        val name=nameInput.trim(); val phone=phoneInput.trim()
        require(name.isNotBlank() && name.length<=100 && phone.length<=40)
        database.withTransaction {
            val dao=database.secureDao();val id=UUID.randomUUID().toString();val now=System.currentTimeMillis()
            val entry=SecureCustomer(id,name,phone,now,now)
            dao.commitCustomer(entry,event(dao,id,"customer.created",
                JSONObject().put("customer_id",id).put("name",name),now))
        }
    }

    suspend fun addService(nameInput:String,unit:String,priceInput:String,hoursInput:String) =
        withContext(Dispatchers.IO) {
            val name=nameInput.trim(); val price=parseCents(priceInput); val hours=hoursInput.toIntOrNull()
            require(name.isNotBlank() && name.length<=100)
            require(unit in listOf("kg","item","flat") && hours!=null && hours in 1..720)
            database.withTransaction {
                val dao=database.secureDao();val id=UUID.randomUUID().toString();val now=System.currentTimeMillis()
                val service=SecureService(id,name,unit,price,hours,null,true,now,now)
                dao.commitService(service,event(dao,id,"service.created",
                    JSONObject().put("service_id",id).put("price_cents",price).put("unit",unit),now))
            }
        }

    suspend fun createOrder(
        customerName:String,phoneInput:String,serviceId:String,
        quantityInput:String,depositInput:String,method:String
    ):String = withContext(Dispatchers.IO) {
        val name=customerName.trim();val phone=phoneInput.trim()
        require(name.isNotBlank() && name.length<=100 && phone.length<=40)
        require(method in METHODS)
        val deposit=parseCents(depositInput.ifBlank { "0" })
        database.withTransaction {
            val dao=database.secureDao()
            val service=dao.getService(serviceId) ?: error("Service not found")
            val total=lineTotalCents(service.priceCents,quantityInput,service.unit)
            require(deposit<=total) { "Payment exceeds order total" }
            val quantity=if(service.unit=="flat") BigDecimal.ONE else BigDecimal(quantityInput.trim())
            val now=System.currentTimeMillis()
            val customerId=dao.existingCustomerId(name,phone)?:run {
                val id=UUID.randomUUID().toString()
                dao.commitCustomer(SecureCustomer(id,name,phone,now,now),
                    event(dao,id,"customer.created",JSONObject().put("customer_id",id).put("name",name),now))
                id
            }
            val orderId=UUID.randomUUID().toString()
            // A fully random ticket component avoids date/sequence collisions across devices.
            val ticket="L-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+
                "-"+orderId.uppercase()
            val order=SecureOrder(orderId,ticket,customerId,Stage.RECEIVED.name,total,0L,
                now+service.turnaroundHours*3_600_000L,now,now)
            val lineId=UUID.randomUUID().toString()
            val line=SecureOrderLine(lineId,orderId,service.id,service.name,service.unit,
                quantity.stripTrailingZeros().toPlainString(),service.priceCents,total,service.catalogRevision)
            val firstStage=SecureStageEvent(UUID.randomUUID().toString(),orderId,null,Stage.RECEIVED.name,now)
            val linePayload=JSONObject().put("line_id",lineId).put("service_id",service.id)
                .put("unit",service.unit).put("quantity_decimal",line.quantityDecimal)
                .put("unit_price_cents",service.priceCents).put("line_total_cents",total)
                .put("catalog_revision",service.catalogRevision ?: JSONObject.NULL)
            val payload=JSONObject().put("customer_id",customerId).put("ticket_id",ticket)
                .put("total_cents",total).put("order_lines",org.json.JSONArray().put(linePayload))
                .put("promised_at",java.time.Instant.ofEpochMilli(order.promisedAt).toString())
                .put("local_catalog_policy","LEGACY_UNVERIFIED")
            dao.commitOrder(order,listOf(line),firstStage,event(dao,orderId,"order.created",payload,now))
            if(deposit>0) {
                val paymentId=UUID.randomUUID().toString()
                val payment=SecurePayment(paymentId,orderId,deposit,method,"",now)
                dao.commitPayment(payment,event(dao,orderId,"payment.recorded",
                    JSONObject().put("order_id",orderId).put("payment_id",paymentId)
                        .put("amount_cents",deposit).put("method",method),now))
            }
            ticket
        }
    }

    suspend fun recordPayment(orderId:String,amountInput:String,method:String,referenceInput:String) =
        withContext(Dispatchers.IO) {
            val amount=parseCents(amountInput);val ref=referenceInput.trim()
            require(amount>0 && ref.length<=150 && method in METHODS)
            database.withTransaction {
                val dao=database.secureDao();val now=System.currentTimeMillis()
                val paymentId=UUID.randomUUID().toString()
                dao.commitPayment(SecurePayment(paymentId,orderId,amount,method,ref,now),
                    event(dao,orderId,"payment.recorded",
                        JSONObject().put("order_id",orderId).put("payment_id",paymentId)
                            .put("amount_cents",amount).put("method",method)
                            .put("reference",ref),now))
            }
        }

    suspend fun advance(orderId:String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao=database.secureDao()
            val order=dao.getOrder(orderId) ?: error("Order not found")
            val from=Stage.valueOf(order.stage);val next=from.next() ?: error("Already picked up")
            require(next!=Stage.PICKED_UP || order.paidCents==order.totalCents) {
                "Collect full payment before pickup"
            }
            val now=System.currentTimeMillis()
            val ev=SecureStageEvent(UUID.randomUUID().toString(),orderId,from.name,next.name,now)
            dao.commitStage(order,next.name,ev,event(dao,orderId,"order.stage_advanced",
                JSONObject().put("order_id",orderId).put("from_stage",from.name)
                    .put("to_stage",next.name),now))
        }
    }

    private suspend fun event(
        dao:SecureLocalDao,aggregate:String,type:String,payload:JSONObject,time:Long
    ):SecureOutbox {
        val device=dao.getMeta("device_id") ?: error("Device identity not initialized")
        val sequence=(dao.getMeta("device_sequence")?.toLongOrNull()?:0L)+1L
        dao.putMeta(SecureMeta("device_sequence",sequence.toString()))
        return SecureOutbox(UUID.randomUUID().toString(),aggregate,type,device,sequence,1,
            "PENDING",payload.toString(),time)
    }

    private fun Cursor.s(i:Int):String=getString(i)?:""
    companion object {private val METHODS=setOf("Cash","GCash","Bank transfer","Card")}
}
