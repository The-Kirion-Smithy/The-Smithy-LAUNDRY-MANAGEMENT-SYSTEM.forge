package forge.kirion.laundry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box

@Composable
fun NewOrderScreen(
    services: List<LaundryService>,
    customers: List<LaundryCustomer>,
    enabled: Boolean,
    onCreate: (String, String, String, String, String, String) -> Unit,
    onAddService: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var chosenCustomer by remember { mutableStateOf("") }
    var serviceId by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var deposit by remember { mutableStateOf("0") }
    var method by remember { mutableStateOf("Cash") }

    val service = services.firstOrNull { it.id == serviceId } ?: services.firstOrNull()
    val total = service?.let { runCatching { lineTotalCents(it.priceCents, quantity, it.unit) }.getOrNull() }
    val paid = runCatching { parseCents(deposit.ifBlank { "0" }) }.getOrNull()
    val paymentValid = paid != null && total != null && paid <= total

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Intro("Counter", "New drop-off", "Register a customer, choose a service and save a ticket.")
        Surface(shape = PanelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("01  Customer")
                if (customers.isNotEmpty()) {
                    ValuePicker(
                        label = "Returning customer",
                        options = customers.map { it.id },
                        value = chosenCustomer,
                        display = { id -> customers.firstOrNull { it.id == id }?.let {
                            it.name + (if(it.phone.isNotBlank()) " · " + it.phone else "")
                        } ?: "Select customer" }
                    ) { id ->
                        chosenCustomer = id
                        customers.firstOrNull { it.id == id }?.let { name = it.name; phone = it.phone }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Full name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape)
            }
        }

        Surface(shape = PanelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("02  Laundry service")
                if (services.isEmpty()) {
                    Text("No services exist yet. Add a priced service to continue.", color = LaundryColors.Secondary)
                    OutlinedButton(onClick = onAddService) { Text("Set up services") }
                } else if (service != null) {
                    services.forEach { option ->
                        val selected = option.id == service.id
                        Surface(
                            onClick = { serviceId = option.id },
                            color = if(selected) LaundryColors.TealLight else LaundryColors.Background,
                            shape = InnerShape,
                            border = BorderStroke(1.dp, if(selected) LaundryColors.Teal else LaundryColors.Outline)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(option.name, fontWeight = FontWeight.Bold, color = LaundryColors.Ink)
                                    Text(option.turnaroundHours.toString() + "h turnaround", style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                                }
                                Text(peso(option.priceCents) + "/" + option.unit,
                                    color = if(selected) LaundryColors.Teal else LaundryColors.Ink, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (service.unit != "flat") {
                        OutlinedTextField(
                            quantity, { quantity = it },
                            label = { Text(if(service.unit == "kg") "Weight (kg)" else "Number of items") },
                            keyboardOptions = KeyboardOptions(keyboardType = if(service.unit == "kg") KeyboardType.Decimal else KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape
                        )
                    } else {
                        Text("Flat-rate service • one service per ticket", style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                    }
                    Text("Expected turnaround: " + service.turnaroundHours + " hours", style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                }
            }
        }
        Surface(shape = PanelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("03  Payment")
                OutlinedTextField(
                    deposit, { deposit = it }, label = { Text("Pay now (PHP) · 0 = unpaid") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = InnerShape
                )
                ValuePicker("Payment method", listOf("Cash", "GCash", "Bank transfer", "Card"), method) { method = it }
                if(!paymentValid && total != null) {
                    Text("Payment must be a valid amount no greater than the order total.",
                        style = MaterialTheme.typography.bodySmall, color = LaundryColors.Red)
                }
            }
        }
        Surface(shape = PanelShape, color = LaundryColors.Ink, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ORDER SUMMARY", color = Color(0xFFB2D7D3), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(total?.let(::peso) ?: "Enter a valid quantity",
                    color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Balance after payment", color = Color(0xFFC4D8DD))
                    Text(if (paymentValid && total != null && paid != null) peso(total - paid) else "—", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { if(service != null) onCreate(name, phone, service.id, quantity, deposit, method) },
                    enabled = enabled && name.isNotBlank() && service != null && total != null && paymentValid,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save drop-off", fontWeight = FontWeight.Bold) }
                Text("Offline entry • prices are locked to this order", color = Color(0xFFB9D0D5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun ValuePicker(
    label: String,
    options: List<String>,
    value: String,
    display: (String) -> String = { it },
    onValue: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) {
            Text(label + ": " + if (value.isNotBlank()) display(value) else "Choose")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = { expanded = false; onValue(option) }
                )
            }
        }
    }
}
