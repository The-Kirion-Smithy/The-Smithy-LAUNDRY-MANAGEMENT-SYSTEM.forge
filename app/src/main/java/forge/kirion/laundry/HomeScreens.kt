package forge.kirion.laundry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    report: DayReport,
    orders: List<LaundryOrder>,
    onCreate: () -> Unit,
    onQueue: () -> Unit,
    onReports: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Intro("At a glance", "Good day, team.", "A clear view of what needs attention in the shop.")
        Surface(color = LaundryColors.Ink, shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("READY FOR PICKUP", color = Color(0xFFA4D8D0), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(report.ready.toString() + " orders", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Keep the counter moving — orders are saved on this device.",
                    color = Color(0xFFC2D8DE), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                    Text("+  New drop-off", fontWeight = FontWeight.Bold)
                }
            }
        }
        SectionLabel("Today's numbers", "Live from SQLite")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPanel("Received", report.received.toString(), "Orders today", Modifier.weight(1f))
            MetricPanel("Released", report.pickedUp.toString(), "Picked up today", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPanel("Payments", peso(report.collectedCents), "Collected today", Modifier.weight(1f))
            MetricPanel("Outstanding", peso(report.outstandingCents), "Across open orders", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onQueue, modifier = Modifier.weight(1f)) { Text("View queue") }
            OutlinedButton(onClick = onReports, modifier = Modifier.weight(1f)) { Text("Daily report") }
        }
        SectionLabel("Latest drop-offs", orders.size.toString() + " total")
        if (orders.isEmpty()) {
            EmptyPanel("No laundry yet", "Create your first order to start tracking work.")
        } else {
            orders.take(4).forEach { order ->
                Surface(color = Color.White, shape = InnerShape, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(order.customer, fontWeight = FontWeight.Bold, color = LaundryColors.Ink)
                            Text(order.ticket + " · " + order.service, style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
                        }
                        StagePill(order.stage)
                    }
                }
            }
        }
        Text("Prototype only • No PIN access or encrypted backups yet",
            color = LaundryColors.Secondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ReportsScreen(report: DayReport) {
    Column(
        Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Intro("Insights", "Today's report", "Totals from locally recorded orders and payments.")
        MetricPanel("Payments received", peso(report.collectedCents), "All payment methods", Modifier.fillMaxWidth(), featured = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPanel("New drop-offs", report.received.toString(), "Created today", Modifier.weight(1f))
            MetricPanel("Released", report.pickedUp.toString(), "Collected today", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPanel("Ready now", report.ready.toString(), "Awaiting pickup", Modifier.weight(1f))
            MetricPanel("Weight", String.format(Locale.US, "%.2f kg", report.kilograms), "Received today", Modifier.weight(1f))
        }
        MetricPanel("Cash expected", peso(report.cashCents), "Recorded cash payments today", Modifier.fillMaxWidth())
        MetricPanel("Outstanding balance", peso(report.outstandingCents), "All open and not fully paid orders", Modifier.fillMaxWidth())
        Surface(color = LaundryColors.AmberLight, shape = InnerShape) {
            Text("End-of-day locking, expense tracking and cash reconciliation are not yet implemented.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = LaundryColors.Amber)
        }
    }
}

fun displayDate(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
