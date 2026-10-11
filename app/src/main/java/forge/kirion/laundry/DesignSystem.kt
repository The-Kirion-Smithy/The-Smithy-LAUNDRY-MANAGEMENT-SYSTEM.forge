package forge.kirion.laundry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LaundryColors {
    val Ink = Color(0xFF142D39)
    val Secondary = Color(0xFF617A85)
    val Teal = Color(0xFF007F79)
    val TealLight = Color(0xFFE4F4F1)
    val Background = Color(0xFFF5F8F8)
    val Outline = Color(0xFFDBE7E7)
    val Amber = Color(0xFF976111)
    val AmberLight = Color(0xFFFFF2DC)
    val Red = Color(0xFFB94A41)
    val RedLight = Color(0xFFFFEAE7)
    val Blue = Color(0xFF326698)
    val BlueLight = Color(0xFFE8F2FF)
    val White = Color.White
}

@Composable
fun LaundryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = LaundryColors.Teal,
            onPrimary = Color.White,
            primaryContainer = LaundryColors.TealLight,
            onPrimaryContainer = LaundryColors.Ink,
            secondary = LaundryColors.Ink,
            background = LaundryColors.Background,
            onBackground = LaundryColors.Ink,
            surface = Color.White,
            onSurface = LaundryColors.Ink,
            outline = LaundryColors.Outline,
            error = LaundryColors.Red
        ),
        typography = Typography(),
        content = content
    )
}

val PanelShape = RoundedCornerShape(20.dp)
val InnerShape = RoundedCornerShape(14.dp)

@Composable
fun Intro(kicker: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kicker.uppercase(), color = LaundryColors.Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(title, color = LaundryColors.Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = LaundryColors.Secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SectionLabel(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LaundryColors.Ink)
        if (detail != null) Text(detail, color = LaundryColors.Secondary, fontSize = 12.sp)
    }
}

@Composable
fun InfoPill(label: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = background, shape = RoundedCornerShape(50.dp)) {
        Text(
            label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StagePill(stage: Stage) {
    val background = when(stage) {
        Stage.READY -> LaundryColors.TealLight
        Stage.PICKED_UP -> Color(0xFFE8EDEE)
        Stage.RECEIVED -> LaundryColors.BlueLight
        else -> LaundryColors.AmberLight
    }
    val color = when(stage) {
        Stage.READY -> LaundryColors.Teal
        Stage.PICKED_UP -> LaundryColors.Secondary
        Stage.RECEIVED -> LaundryColors.Blue
        else -> LaundryColors.Amber
    }
    InfoPill(stage.display, background, color)
}

@Composable
fun MetricPanel(label: String, value: String, helper: String, modifier: Modifier = Modifier, featured: Boolean = false) {
    Surface(
        modifier = modifier, color = if(featured) LaundryColors.Ink else Color.White,
        shape = PanelShape, shadowElevation = if(featured) 0.dp else 1.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = if(featured) Color(0xFFB9D3DA) else LaundryColors.Secondary, fontSize = 12.sp)
            Text(value, color = if(featured) Color.White else LaundryColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(helper, color = if(featured) Color(0xFFB9D3DA) else LaundryColors.Secondary, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@Composable
fun EmptyPanel(title: String, detail: String) {
    Surface(color = Color.White, shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(36.dp).background(LaundryColors.TealLight, CircleShape))
            Text(title, fontWeight = FontWeight.Bold, color = LaundryColors.Ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LaundryColors.Secondary)
        }
    }
}
