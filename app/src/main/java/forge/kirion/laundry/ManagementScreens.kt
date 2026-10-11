package forge.kirion.laundry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PeopleScreen(
    customers: List<LaundryCustomer>,
    enabled: Boolean,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(customers.size) {
        if (submitted) { name = ""; phone = ""; submitted = false }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Intro("Directory", "Customers", "Keep returning customers a few taps away.")
        Surface(color = Color.White, shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Add a customer")
                OutlinedTextField(name, { name = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape)
                Button(
                    onClick = { submitted = true; onAdd(name, phone) },
                    enabled = enabled && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+  Save customer") }
            }
        }
        SectionLabel("Customer directory", customers.size.toString() + " people")
        if (customers.isEmpty()) {
            EmptyPanel("No customer records", "Add a test customer to start a laundry ticket.")
        } else {
            customers.forEach { customer ->
                Surface(color = Color.White, shape = InnerShape, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = LaundryColors.TealLight, shape = InnerShape) {
                            Text(customer.name.take(1).uppercase(), modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                fontWeight = FontWeight.Bold, color = LaundryColors.Teal)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(customer.name, fontWeight = FontWeight.SemiBold, color = LaundryColors.Ink)
                            Text(customer.phone.ifBlank { "No phone on file" },
                                style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                        }
                    }
                }
            }
        }
        Text("Use test records only until local encryption and backups are complete.",
            style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
    }
}

@Composable
fun MoreScreen(
    report: DayReport,
    services: List<LaundryService>,
    section: String,
    onSection: (String) -> Unit,
    enabled: Boolean,
    onAddService: (String, String, String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Intro("Shop operations", "Manage", "Today's performance and your services in one place.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Reports", "Services").forEach { option ->
                FilterChip(
                    selected = section == option,
                    onClick = { onSection(option) },
                    label = { Text(option) }
                )
            }
        }
        when (section) {
            "Services" -> ServiceCatalogue(services, enabled, onAddService)
            else -> ReportsScreen(report)
        }
    }
}

@Composable
private fun ServiceCatalogue(
    services: List<LaundryService>,
    enabled: Boolean,
    onAddService: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("kg") }
    var price by remember { mutableStateOf("") }
    var turnaround by remember { mutableStateOf("48") }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(services.size) {
        if(submitted) { name = ""; price = ""; submitted = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Intro("Catalog", "Services & pricing", "Every new order captures its price at intake.")
        Surface(color = Color.White, shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Create a service")
                OutlinedTextField(
                    name, { name = it }, label = { Text("Service name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape
                )
                ValuePicker("Pricing unit", listOf("kg", "item", "flat"), unit) { unit = it }
                OutlinedTextField(
                    price, { price = it }, label = { Text("Rate in PHP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape
                )
                OutlinedTextField(
                    turnaround, { turnaround = it }, label = { Text("Turnaround hours") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = InnerShape
                )
                Button(
                    onClick = { submitted = true; onAddService(name, unit, price, turnaround) },
                    modifier = Modifier.fillMaxWidth(), enabled = enabled && name.isNotBlank()
                ) { Text("+  Add service") }
            }
        }
        SectionLabel("Current offerings", services.size.toString() + " services")
        if(services.isEmpty()) EmptyPanel("No services configured", "Add the first price to start accepting orders.")
        services.forEach { service ->
            Surface(color = Color.White, shape = InnerShape, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(service.name, fontWeight = FontWeight.SemiBold, color = LaundryColors.Ink)
                        Text(service.turnaroundHours.toString() + "h turnaround", style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                    }
                    Text(peso(service.priceCents) + " / " + service.unit, fontWeight = FontWeight.Bold, color = LaundryColors.Teal)
                }
            }
        }
        Text("The starter rates are illustrative. Editing existing rates and PIN-restricted price controls are not in this prototype.",
            style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
    }
}
