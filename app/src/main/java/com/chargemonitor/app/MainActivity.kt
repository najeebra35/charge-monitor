package com.chargemonitor.app

import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

// ---------- Theme ----------

private val Green = Color(0xFF3DDC84)
private val Cyan = Color(0xFF4FC3F7)
private val Purple = Color(0xFFB388FF)
private val Yellow = Color(0xFFFFD54F)
private val Orange = Color(0xFFFF8A65)
private val Red = Color(0xFFFF5252)
private val Muted = Color(0xFF9AA3AE)

private val AppColors = darkColorScheme(
    primary = Green,
    secondary = Cyan,
    background = Color(0xFF0E1116),
    surface = Color(0xFF171B22),
    surfaceVariant = Color(0xFF232A35),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Muted,
)

private const val HISTORY = 120

private fun Float.f(d: Int = 1) = String.format(Locale.US, "%.${d}f", this)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = AppColors) { ChargeMonitorScreen() }
        }
    }
}

// ---------- Screen ----------

@Composable
fun ChargeMonitorScreen() {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var snap by remember { mutableStateOf<BatterySnapshot?>(null) }
    val history = remember { mutableStateListOf<Float>() }
    var peak by remember { mutableFloatStateOf(0f) }
    var wasPlugged by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val s = withContext(Dispatchers.IO) {
                    try { BatteryReader.read(context) } catch (_: Throwable) { null }
                }
                if (s != null) {
                    // Reset session stats when charger is (re)connected
                    if (s.isPlugged && !wasPlugged) { peak = 0f; history.clear() }
                    wasPlugged = s.isPlugged
                    val w = abs(s.powerW ?: 0f)
                    history.add(w)
                    if (history.size > HISTORY) history.removeAt(0)
                    if (s.isCharging && w > peak) peak = w
                }
                snap = s
                delay(1000)
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        val s = snap
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val (speed, accent) = speedOf(s)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("⚡ Charge Monitor", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            item { HeroCard(s, speed, accent, peak) }
            item { StatGrid(s) }
            item { ChargerCard(s) }
            item { GraphCard(history.toList(), accent, peak) }
            item { BatteryCard(s) }
            item { SysfsCard(s.sysfs) }
            item { NoteCard() }
        }
    }
}

private fun speedOf(s: BatterySnapshot): Pair<String, Color> {
    if (!s.isPlugged) return "On battery" to Muted
    if (s.isFull) return "Fully charged" to Green
    if (!s.isCharging) return "Plugged · not charging" to Orange
    val w = abs(s.powerW ?: 0f)
    return when {
        w < 2.5f -> "Slow charging" to Red
        w < 7.5f -> "Normal charging" to Yellow
        w < 15f -> "Fast charging" to Green
        w < 30f -> "Super fast charging" to Cyan
        else -> "Hyper charging" to Purple
    }
}

private fun plugName(p: Int) = when (p) {
    BatteryManager.BATTERY_PLUGGED_AC -> "AC wall charger"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB (PC / Laptop)"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
    8 -> "Dock"
    0 -> "Not connected"
    else -> "Unknown ($p)"
}

private fun statusName(st: Int) = when (st) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
    BatteryManager.BATTERY_STATUS_FULL -> "Full"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
    else -> "Unknown"
}

private fun healthName(h: Int) = when (h) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
    else -> "Unknown"
}

private fun tempColor(t: Float) = when {
    t >= 45f -> Red
    t >= 40f -> Orange
    t >= 37f -> Yellow
    else -> Green
}

private fun formatDuration(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

// ---------- Components ----------

@Composable
private fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun HeroCard(s: BatterySnapshot, speed: String, accent: Color, peak: Float) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelRing(s.levelPct, accent, Modifier.size(118.dp))
            Spacer(Modifier.width(18.dp))
            Column {
                Text(
                    "${statusName(s.status)} · ${plugName(s.plugged)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                )
                val w = s.powerW
                Text(
                    if (w != null) "${abs(w).f()} W" else "-- W",
                    fontSize = 42.sp, fontWeight = FontWeight.Bold, color = accent
                )
                Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.18f)) {
                    Text(speed, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                if (peak > 0f) {
                    Spacer(Modifier.height(6.dp))
                    Text("Session peak: ${peak.f()} W",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LevelRing(level: Int, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * level.coerceIn(0, 100) / 100f, false, tl, sz,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$level%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Battery", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Tile(val label: String, val value: String, val sub: String, val color: Color? = null)

@Composable
private fun StatGrid(s: BatterySnapshot) {
    val cur = s.currentMa
    val tiles = listOf(
        Tile("Voltage", "${(s.voltageMv / 1000f).f(3)} V", "Battery voltage"),
        Tile(
            "Current",
            if (cur != null) "${abs(cur)} mA" else "N/A",
            if (cur != null) "${(abs(cur) / 1000f).f(2)} A · ${if (cur >= 0) "in" else "out"}" else "Not reported"
        ),
        Tile("Power", s.powerW?.let { "${abs(it).f(2)} W" } ?: "N/A", "Into battery (V × A)"),
        Tile("Temperature", "${s.tempC.f()} °C", "Battery temp", tempColor(s.tempC)),
        Tile("Health", healthName(s.health), "Android report",
            if (s.health == BatteryManager.BATTERY_HEALTH_GOOD) Green else Orange),
        Tile("Level", "${s.levelPct}%", statusName(s.status)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { StatTile(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(t: Tile, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(t.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t.value, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = t.color ?: MaterialTheme.colorScheme.onSurface)
            Text(t.sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.45f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, Modifier.weight(0.55f), textAlign = TextAlign.End, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ChargerCard(s: BatterySnapshot) {
    SectionCard("🔌 Charger") {
        if (!s.isPlugged) {
            Text("Charger connect pannunga — watts, protocol, amps ellam inga live-ah varum.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            return@SectionCard
        }
        InfoRow("Connection", plugName(s.plugged))
        InfoRow("Charging type", s.charger.protocol, Green)
        InfoRow("Detected via", s.charger.source)
        s.maxChargerVoltageMv?.let { InfoRow("Charger max voltage", "${(it / 1000f).f(2)} V") }
        s.maxChargerCurrentMa?.let { InfoRow("Charger max current", "$it mA (${(it / 1000f).f(2)} A)") }
        s.chargerRatedW?.let { InfoRow("Charger negotiated power", "${it.f()} W") }
        if (s.inputVoltageMv != null) InfoRow("Live input voltage", "${(s.inputVoltageMv / 1000f).f(2)} V")
        if (s.inputCurrentMa != null) InfoRow("Live input current", "${s.inputCurrentMa} mA")
        s.inputPowerW?.let { InfoRow("Live charger output", "${it.f()} W", Cyan) }
        s.chargeTimeRemainingMs?.let { InfoRow("Time to full", formatDuration(it)) }
    }
}

@Composable
private fun GraphCard(data: List<Float>, color: Color, peak: Float) {
    val maxData = data.maxOrNull() ?: 0f
    val scaleMax = ceil((maxOf(maxData, peak, 5f) * 1.15f) / 5f) * 5f
    val avg = if (data.isNotEmpty()) data.average().toFloat() else 0f
    SectionCard("📈 Live power (last 2 min)") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Now ${(data.lastOrNull() ?: 0f).f()} W", fontSize = 13.sp, color = color)
            Text("Avg ${avg.f()} W", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Max ${maxData.f()} W", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Box {
            PowerGraph(data, color, scaleMax, Modifier.fillMaxWidth().height(160.dp))
            Text("${scaleMax.toInt()} W", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PowerGraph(data: List<Float>, color: Color, maxY: Float, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        if (data.size < 2) return@Canvas
        val stepX = size.width / (HISTORY - 1)
        val startX = size.width - stepX * (data.size - 1)
        val line = Path()
        val fill = Path()
        data.forEachIndexed { i, v ->
            val x = startX + i * stepX
            val y = size.height - (v / maxY).coerceIn(0f, 1f) * size.height
            if (i == 0) {
                line.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y)
            } else {
                line.lineTo(x, y); fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun BatteryCard(s: BatterySnapshot) {
    SectionCard("🔋 Battery details") {
        InfoRow("Status", statusName(s.status))
        InfoRow("Health", healthName(s.health))
        InfoRow("Technology", s.technology)
        InfoRow("Temperature", "${s.tempC.f()} °C", tempColor(s.tempC))
        InfoRow("Voltage", "${s.voltageMv} mV")
        s.chargeCounterMah?.let { InfoRow("Charge now", "$it mAh") }
        s.estimatedFullMah?.let { InfoRow("Estimated full capacity", "$it mAh") }
        s.designCapacityMah?.let { InfoRow("Design capacity", "$it mAh") }
        val est = s.estimatedFullMah
        val design = s.designCapacityMah
        if (est != null && design != null && design > 0) {
            val pct = (est * 100f / design).coerceIn(0f, 120f)
            InfoRow("Estimated battery health", "${pct.f(0)}%",
                if (pct >= 80) Green else if (pct >= 65) Yellow else Red)
        }
        s.cycleCount?.let { InfoRow("Charge cycles", "$it") }
    }
}

@Composable
private fun SysfsCard(map: Map<String, String>) {
    var open by remember { mutableStateOf(false) }
    SectionCard {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("🛠 Advanced (kernel data)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (open) "Hide ▲" else "${map.size} ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Spacer(Modifier.height(10.dp))
                if (map.isEmpty()) {
                    Text("Unga phone indha kernel details-ah apps-ku block pannirukku (Android 10+ la idhu normal).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    map.forEach { (k, v) ->
                        Text("$k = $v", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard() {
    SectionCard("ℹ️ Note") {
        Text(
            "• Power = battery voltage × battery current. Idhu battery-kulla pogura power; " +
                "charger kudukkura power idha vida 10–20% adhigama irukkum (heat + phone usage).\n" +
                "• PD / QC / VOOC protocol-ah Android direct-ah sollaadhu. Phone kernel data " +
                "kidaichaa exact-ah kaattum, illana voltage & watts vachi estimate pannum.\n" +
                "• Screen on-la irundha accurate reading-ku konjam neram wait pannunga.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp
        )
    }
}
