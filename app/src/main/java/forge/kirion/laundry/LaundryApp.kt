package forge.kirion.laundry

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class UiSnapshot(
    val customers: List<LaundryCustomer>,
    val services: List<LaundryService>,
    val orders: List<LaundryOrder>,
    val report: DayReport
)

@Composable
fun LaundryApp() {
    val context = LocalContext.current
    val db = remember(context) { LaundryDatabase(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("Queue") }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapshot by remember {
        mutableStateOf(UiSnapshot(emptyList(), emptyList(), emptyList(), DayReport(0, 0, 0, 0.0, 0, 0, 0)))
    }
    LaunchedEffect(revision) {
        try {
            snapshot = withContext(Dispatchers.IO) {
                UiSnapshot(db.customers(), db.services(), db.orders(), db.report())
            }
        } catch (e: Exception) {
            error = "Database error: " + (e.message ?: "unknown")
        }
    }
    fun mutate(action: () -> Unit, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                error = null
                revision++
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: "Operation failed"
            }
        }
    }
    val tabs = listOf("Queue", "New order", "Customers", "Services", "Today")
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("KIRION · LAUNDRY", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Offline Android prototype · local device only", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEach { tab ->
                if (page == tab) Button(onClick = { page = tab }) { Text(tab) }
                else OutlinedButton(onClick = { page = tab }) { Text(tab) }
            }
        }
        if (error != null) Card(Modifier.fillMaxWidth()) {
            Text("Attention: " + error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                "Queue" -> QueuePage(
                    orders = snapshot.orders,
                    onNext = { id -> mutate({ db.advance(id) }) },
                    onPayment = { id, amount, method, reference ->
                        mutate({ db.recordPayment(id, amount, method, reference) })
                    },
                    onShare = { order ->
                        val content = "LAUNDRY TICKET\n" +
                            order.ticket + "\n" +
                            "Customer: " + order.customer + "\n" +
                            "Service: " + order.service + " (" + order.quantity + " " + order.unit + ")\n" +
                            "Total: " + peso(order.totalCents) + "\n" +
                            "Paid: " + peso(order.paidCents) + "\n" +
                            "Balance: " + peso(order.balanceCents) + "\n" +
                            "Promised: " + dateTime(order.promisedAt) + "\n" +
                            "Stage: " + order.stage.display
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, content)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share ticket (prototype text)"))
                    }
                )
                "New order" -> IntakePage(
                    services = snapshot.services,
                    customers = snapshot.customers,
                    onSave = { name, phone, service, quantity, deposit, method ->
                        mutate({ db.createOrder(name, phone, service, quantity, deposit, method) }) {
                            page = "Queue"
                        }
                    }
                )
                "Customers" -> CustomersPage(snapshot.customers) { name, phone ->
                    mutate({ db.addCustomer(name, phone) })
                }
                "Services" -> ServicesPage(snapshot.services) { name, unit, amount, hours ->
                    mutate({ db.addService(name, unit, amount, hours) })
                }
                "Today" -> TodayPage(snapshot.report)
            }
        }
    }
}

@Composable
private fun QueuePage(
    orders: List<LaundryOrder>,
    onNext: (String) -> Unit,
    onPayment: (String, String, String, String) -> Unit,
    onShare: (LaundryOrder) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    var reference by remember { mutableStateOf("") }
    val visible = orders.filter {
        search.isBlank() || it.ticket.contains(search, ignoreCase = true) ||
            it.customer.contains(search, ignoreCase = true) ||
            it.stage.display.contains(search, ignoreCase = true)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Laundry queue · " + orders.size + " orders", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Search ticket, customer or stage") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        if (visible.isEmpty()) Text("No matching orders. Add your first drop-off from New order.")
        visible.forEach { order ->
            key(order.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(order.ticket, fontWeight = FontWeight.Bold)
                            Text(order.stage.display)
                        }
                        Text(order.customer + " · " + order.service + " · " + order.quantity + " " + order.unit)
                        Text("Total " + peso(order.totalCents) + "  |  Paid " + peso(order.paidCents))
                        Text("Balance " + peso(order.balanceCents), fontWeight = FontWeight.SemiBold)
                        Text("Promised: " + dateTime(order.promisedAt))
                        if (System.currentTimeMillis() > order.promisedAt &&
                            order.stage !in listOf(Stage.READY, Stage.PICKED_UP)) {
                            Text("OVERDUE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onShare(order) }) { Text("Share ticket") }
                            if (order.stage.next() != null) {
                                Button(onClick = { onNext(order.id) }) {
                                    Text(if (order.stage == Stage.READY) "Pickup" else "Next stage")
                                }
                            }
                        }
                        if (order.balanceCents > 0 && order.stage != Stage.PICKED_UP) {
                            OutlinedButton(onClick = {
                                selectedId = if (selectedId == order.id) null else order.id
                                amount = ""; method = "Cash"; reference = ""
                            }) { Text(if (selectedId == order.id) "Hide payment" else "Record payment") }
                            if (selectedId == order.id) {
                                OutlinedTextField(
                                    amount, { amount = it }, label = { Text("Amount in PHP") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                Picker("Payment method", listOf("Cash", "GCash", "Bank transfer", "Card"), method) {
                                    method = it
                                }
                                OutlinedTextField(
                                    reference, { reference = it },
                                    label = { Text("Reference (optional)") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                Button(onClick = {
                                    onPayment(order.id, amount, method, reference)
                                    selectedId = null
                                }) { Text("Save payment") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntakePage(
    services: List<LaundryService>,
    customers: List<LaundryCustomer>,
    onSave: (String, String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var selectedServiceId by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var deposit by remember { mutableStateOf("0") }
    var method by remember { mutableStateOf("Cash") }
    val service = services.firstOrNull { it.id == selectedServiceId } ?: services.firstOrNull()
    val calculated = service?.let { runCatching { lineTotalCents(it.priceCents, quantity, it.unit) }.getOrNull() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("New drop-off", style = MaterialTheme.typography.titleMedium)
        Text("Returning customer: select an existing customer or type a name.")
        if (customers.isNotEmpty()) {
            Picker(
                "Existing customer",
                customers.map { it.id },
                "",
                display = { id -> customers.firstOrNull { it.id == id }?.let {
                    it.name + if (it.phone.isNotBlank()) " · " + it.phone else ""
                } ?: "Choose" }
            ) { id ->
                customers.firstOrNull { it.id == id }?.let { name = it.name; phone = it.phone }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth())
        if (services.isNotEmpty() && service != null) {
            Picker("Service", services.map { it.id }, service.id,
                display = { id ->
                    services.firstOrNull { it.id == id }?.let {
                        it.name + " · " + peso(it.priceCents) + "/" + it.unit
                    } ?: id
                }
            ) { selectedServiceId = it }
            OutlinedTextField(
                quantity, { quantity = it },
                label = { Text(if (service.unit == "kg") "Weight (kg)" else if (service.unit == "item") "Item count" else "Flat service (quantity ignored)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Text("Total: " + (calculated?.let(::peso) ?: "Enter a valid quantity"),
                style = MaterialTheme.typography.titleMedium)
            Text("Promised pickup defaults to " + service.turnaroundHours + " hours from intake.")
            OutlinedTextField(
                deposit, { deposit = it }, label = { Text("Deposit / payment (PHP, 0 = unpaid)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Picker("Payment method", listOf("Cash", "GCash", "Bank transfer", "Card"), method) {
                method = it
            }
            Button(
                onClick = { onSave(name, phone, service.id, quantity, deposit, method) },
                enabled = calculated != null && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save drop-off") }
        } else {
            Text("Create your first service on the Services screen.")
        }
        Text("Prototype: one service line per order; PDF/image receipt comes later.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CustomersPage(customers: List<LaundryCustomer>, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Customers · " + customers.size, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onAdd(name, phone); name = ""; phone = "" }, enabled = name.isNotBlank()) {
            Text("Add customer")
        }
        HorizontalDivider()
        customers.forEach { customer ->
            Text(customer.name + if (customer.phone.isNotBlank()) " · " + customer.phone else "")
        }
    }
}

@Composable
private fun ServicesPage(
    services: List<LaundryService>,
    onAdd: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("kg") }
    var price by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("48") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Service & prices", style = MaterialTheme.typography.titleMedium)
        Text("Sample rates are placeholders; replace with your actual shop prices.")
        OutlinedTextField(name, { name = it }, label = { Text("Service name") }, modifier = Modifier.fillMaxWidth())
        Picker("Pricing unit", listOf("kg", "item", "flat"), unit) { unit = it }
        OutlinedTextField(price, { price = it }, label = { Text("Price in PHP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hours, { hours = it }, label = { Text("Turnaround hours") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            onAdd(name, unit, price, hours)
            name = ""; price = ""
        }, enabled = name.isNotBlank()) { Text("Add service") }
        HorizontalDivider()
        services.forEach {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(it.name, fontWeight = FontWeight.SemiBold)
                    Text(peso(it.priceCents) + " / " + it.unit + " · " + it.turnaroundHours + " hours")
                }
            }
        }
        Text("Current prototype adds new service prices. Editing/deactivating rates is a later slice.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TodayPage(report: DayReport) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Today's shop summary", style = MaterialTheme.typography.titleMedium)
        ReportLine("Orders received today", report.received.toString())
        ReportLine("Picked up today", report.pickedUp.toString())
        ReportLine("Ready for pickup now", report.ready.toString())
        ReportLine("Kilos received today", String.format(Locale.US, "%.2f", report.kilograms))
        ReportLine("Payments collected today", peso(report.collectedCents))
        ReportLine("Cash expected from today's payments", peso(report.cashCents))
        ReportLine("All open unpaid balance", peso(report.outstandingCents))
        Spacer(Modifier.height(6.dp))
        Text("Numbers come from local SQLite transactions, not generated demo charts.")
        Text("End-of-day close and cash-count reconciliation are not implemented yet.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReportLine(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Picker(
    title: String,
    items: List<String>,
    value: String,
    display: (String) -> String = { it },
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(title + ": " + if (value.isNotBlank()) display(value) else "Choose")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(display(item)) },
                    onClick = { expanded = false; onSelect(item) }
                )
            }
        }
    }
}

private fun dateTime(epoch: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epoch))
