package forge.kirion.laundry

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun QueueScreen(
    orders: List<LaundryOrder>,
    enabled: Boolean,
    onStage: (String) -> Unit,
    onPayment: (String, String, String, String) -> Unit,
    onShare: (LaundryOrder) -> Unit,
    onCreate: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Active") }
    val visible = orders.filter { order ->
        val searchMatch = query.isBlank() ||
            order.customer.contains(query, ignoreCase = true) ||
            order.ticket.contains(query, ignoreCase = true) ||
            order.service.contains(query, ignoreCase = true) ||
            order.stage.display.contains(query, ignoreCase = true)
        val filterMatch = when(filter) {
            "Active" -> order.stage != Stage.PICKED_UP
            "Ready" -> order.stage == Stage.READY
            "Unpaid" -> order.balanceCents > 0
            else -> true
        }
        searchMatch && filterMatch
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Intro("Workflow", "Laundry queue", "Find a ticket, collect balances and move work forward.")
        OutlinedTextField(
            query, { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search name, ticket or service") }, singleLine = true, shape = InnerShape
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Active", "Ready", "Unpaid", "All").forEach { value ->
                FilterChip(
                    selected = filter == value, onClick = { filter = value },
                    label = { Text(value) }
                )
            }
        }
        SectionLabel("Orders", visible.size.toString() + " shown")
        if (visible.isEmpty()) {
            EmptyPanel("Nothing in this view", "Try another filter or create a new drop-off.")
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create new order") }
        }
        visible.forEach { order ->
            key(order.id) {
                QueueCard(
                    order = order, enabled = enabled,
                    onStage = { onStage(order.id) },
                    onShare = { onShare(order) },
                    onPayment = { amount, method, reference ->
                        onPayment(order.id, amount, method, reference)
                    }
                )
            }
        }
    }
}

@Composable
private fun QueueCard(
    order: LaundryOrder,
    enabled: Boolean,
    onStage: () -> Unit,
    onShare: () -> Unit,
    onPayment: (String, String, String) -> Unit
) {
    var showPayment by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    var reference by remember { mutableStateOf("") }
    val isOverdue = System.currentTimeMillis() > order.promisedAt &&
        order.stage != Stage.READY && order.stage != Stage.PICKED_UP
    Surface(color = Color.White, shape = PanelShape, modifier = Modifier.fillMaxWidth(), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(order.ticket, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = LaundryColors.Secondary)
                StagePill(order.stage)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(order.customer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(order.service + " · " + order.quantity + " " + order.unit,
                    style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
            }
            LinearProgressIndicator(
                progress = { order.stage.ordinal.toFloat() / Stage.PICKED_UP.ordinal.toFloat() },
                modifier = Modifier.fillMaxWidth(), color = LaundryColors.Teal, trackColor = LaundryColors.TealLight
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Promised " + displayDate(order.promisedAt), style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                if (isOverdue) InfoPill("OVERDUE", LaundryColors.RedLight, LaundryColors.Red)
            }
            HorizontalDivider(color = LaundryColors.Outline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Order total", color = LaundryColors.Secondary, style = MaterialTheme.typography.labelSmall)
                    Text(peso(order.totalCents), fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Balance due", color = LaundryColors.Secondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        peso(order.balanceCents),
                        fontWeight = FontWeight.Bold,
                        color = if(order.balanceCents > 0) LaundryColors.Amber else LaundryColors.Teal
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("Share ticket")
                }
                if (order.stage.next() != null) {
                    val isPickup = order.stage == Stage.READY
                    Button(
                        onClick = onStage,
                        enabled = enabled && (!isPickup || order.balanceCents == 0L),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isPickup) "Mark picked up" else "Next stage")
                    }
                }
            }
            if (order.stage == Stage.READY && order.balanceCents > 0) {
                Text("Collect the remaining balance before pickup.", color = LaundryColors.Amber,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (order.balanceCents > 0 && order.stage != Stage.PICKED_UP) {
                OutlinedButton(
                    onClick = { showPayment = !showPayment },
                    modifier = Modifier.fillMaxWidth(), enabled = enabled
                ) {
                    Text(if(showPayment) "Hide payment form" else "+  Record payment")
                }
                if (showPayment) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            amount, { amount = it }, label = { Text("Payment amount (PHP)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        ValuePicker(
                            label = "Method", options = listOf("Cash", "GCash", "Bank transfer", "Card"),
                            value = method, onValue = { method = it }
                        )
                        OutlinedTextField(
                            reference, { reference = it }, label = { Text("Transaction reference (optional)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Button(
                            onClick = { onPayment(amount, method, reference) },
                            enabled = enabled && amount.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save payment") }
                    }
                }
            }
        }
    }
}
