package forge.kirion.laundry

import android.content.Intent
import forge.kirion.laundry.storage.LegacyMigration
import forge.kirion.laundry.storage.SecureLaundryRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DeskSnapshot(
    val customers: List<LaundryCustomer>,
    val services: List<LaundryService>,
    val orders: List<LaundryOrder>,
    val report: DayReport,
    val pendingSync: Long
) {
    companion object {
        fun empty() = DeskSnapshot(emptyList(), emptyList(), emptyList(), DayReport(0, 0, 0, 0.0, 0, 0, 0), 0)
    }
}
private enum class DeskTab(val label: String, val mark: String) {
    HOME("Home", "⌂"), QUEUE("Queue", "≡"), NEW("New", "+"), PEOPLE("People", "◎"), MORE("More", "••")
}

@Composable
fun LaundryApp() {
    val context = LocalContext.current
    var repository by remember { mutableStateOf<SecureLaundryRepository?>(null) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(DeskTab.HOME) }
    var moreSection by remember { mutableStateOf("Reports") }
    var version by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(DeskSnapshot.empty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(version) {
        try {
            val active = repository ?: withContext(Dispatchers.IO) {
                SecureLaundryRepository(LegacyMigration.openOrMigrate(context))
            }.also { repository = it }
            snapshot = active.snapshot().let { DeskSnapshot(it.customers, it.services, it.orders, it.report, it.pendingSync) }
        } catch (e: Exception) {
            error = "Unable to open local data: " + (e.message ?: "Unknown error")
        }
    }

    fun mutate(success: String, action: suspend (SecureLaundryRepository) -> Unit, onSuccess: () -> Unit = {}) {
        val active = repository ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { action(active) }
                error = null
                message = success
                version++
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: "The operation could not be completed"
            } finally {
                busy = false
            }
        }
    }

    fun shareTicket(order: LaundryOrder) {
        val detail = "LAUNDRY TICKET\n" + order.ticket + "\n" +
            "Customer: " + order.customer + "\n" +
            "Service: " + order.service + " (" + order.quantity + " " + order.unit + ")\n" +
            "Total: " + peso(order.totalCents) + "\n" +
            "Paid: " + peso(order.paidCents) + "\n" +
            "Balance: " + peso(order.balanceCents) + "\n" +
            "Promised: " + displayDate(order.promisedAt) + "\n" +
            "Stage: " + order.stage.display
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, detail)
        }
        context.startActivity(Intent.createChooser(intent, "Share ticket (text preview)"))
    }

    Scaffold(
        containerColor = LaundryColors.Background,
        topBar = {
            Surface(shadowElevation = 1.dp, color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = LaundryColors.Ink, shape = RoundedCornerShape(13.dp), modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("K", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("KIRION", letterSpacing = 2.sp, fontSize = 11.sp, fontWeight = FontWeight.Black, color = LaundryColors.Teal)
                            Text("Laundry desk", fontWeight = FontWeight.Bold, color = LaundryColors.Ink, style = MaterialTheme.typography.titleMedium)
                        }
                        InfoPill("● OFFLINE", LaundryColors.TealLight, LaundryColors.Teal)
                    }
                    Text(
                        if(busy) "Saving encrypted data…" else "LOCAL ONLY • " + snapshot.pendingSync + " SYNC PENDING • TEST DATA",
                        fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = LaundryColors.Secondary
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 4.dp) {
                DeskTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination; error = null },
                        icon = {
                            Text(destination.mark, fontSize = if (destination == DeskTab.NEW) 23.sp else 20.sp, fontWeight = FontWeight.Bold)
                        },
                        label = { Text(destination.label, fontSize = 11.sp) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (error != null) {
                Surface(color = LaundryColors.RedLight, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error ?: "", color = LaundryColors.Red, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { error = null }) { Text("Dismiss") }
                    }
                }
            } else if (message != null) {
                Surface(color = LaundryColors.TealLight, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message ?: "", color = LaundryColors.Teal, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { message = null }) { Text("Close") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    DeskTab.HOME -> HomeScreen(
                        report = snapshot.report,
                        orders = snapshot.orders,
                        onCreate = { tab = DeskTab.NEW },
                        onQueue = { tab = DeskTab.QUEUE },
                        onReports = { moreSection = "Reports"; tab = DeskTab.MORE }
                    )
                    DeskTab.QUEUE -> QueueScreen(
                        orders = snapshot.orders,
                        enabled = !busy && repository != null,
                        onStage = { orderId -> mutate("Saved locally; hub sync pending", { store -> store.advance(orderId) }) },
                        onPayment = { orderId, amount, method, reference ->
                            mutate("Payment saved locally; hub sync pending", { store -> store.recordPayment(orderId, amount, method, reference) })
                        },
                        onShare = { order -> shareTicket(order) },
                        onCreate = { tab = DeskTab.NEW }
                    )
                    DeskTab.NEW -> NewOrderScreen(
                        services = snapshot.services, customers = snapshot.customers, enabled = !busy,
                        onCreate = { name, phone, serviceId, quantity, deposit, method ->
                            mutate("Order saved locally; hub sync pending", { store ->
                                store.createOrder(name, phone, serviceId, quantity, deposit, method)
                            }) { tab = DeskTab.QUEUE }
                        },
                        onAddService = { moreSection = "Services"; tab = DeskTab.MORE }
                    )
                    DeskTab.PEOPLE -> PeopleScreen(
                        customers = snapshot.customers, enabled = !busy,
                        onAdd = { name, phone ->
                            mutate("Customer saved locally", { store -> store.addCustomer(name, phone) })
                        }
                    )
                    DeskTab.MORE -> MoreScreen(
                        report = snapshot.report, services = snapshot.services,
                        section = moreSection, onSection = { moreSection = it }, enabled = !busy,
                        onAddService = { name, unit, amount, hours ->
                            mutate("Service saved locally (unverified catalog)", { store -> store.addService(name, unit, amount, hours) })
                        }
                    )
                }
            }
        }
    }
}
