package com.jondude1.bookshelf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen() {
    val data by Graph.stats.data.collectAsState()
    val prefs by Graph.prefs.reader.collectAsState()
    val today = LocalDate.now()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val minutes = days.map { ((data.days[it.toString()]?.seconds ?: 0) / 60).toInt() }
    val weekMinutes = minutes.takeLast(7).sum()
    val yearFinished = data.finished.values.count { it.startsWith(today.year.toString()) }
    val totalHours = data.days.values.sumOf { it.seconds } / 3600.0
    val totalPages = data.days.values.sumOf { it.pages }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(20.dp)) {
        Text("Reading", style = MaterialTheme.typography.headlineLarge)
        Text("Measured while you turn pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Today", "${minutes.last()} min", Modifier.weight(1f))
            StatTile("This week", formatHM(weekMinutes), Modifier.weight(1f))
            StatTile("Streak", "${Graph.stats.streak()} days", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Finished in ${today.year}", "$yearFinished", Modifier.weight(1f))
            StatTile("All time", "%.1f h".format(totalHours), Modifier.weight(1f))
            StatTile("Pace", "${(60 / data.secondsPerPage.coerceAtLeast(1.0)).let { "%.1f".format(it) }} pg/min", Modifier.weight(1f))
        }

        Text("Last two weeks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
            val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(prefs.dailyGoalMinutes).coerceAtLeast(1)
            val goal = prefs.dailyGoalMinutes
            val goalColor = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val n = minutes.size
                val gap = 6.dp.toPx()
                val bw = (size.width - gap * (n - 1)) / n
                val gy = size.height - size.height * goal / max
                drawLine(goalColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                minutes.forEachIndexed { i, m ->
                    val h = (size.height * m / max).coerceAtLeast(3.dp.toPx())
                    drawRoundRect(if (m >= goal) primary else if (m > 0) primary.copy(alpha = 0.55f) else muted,
                        topLeft = Offset(i * (bw + gap), size.height - h), size = Size(bw, h), cornerRadius = CornerRadius(4.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                days.forEachIndexed { i, d ->
                    Text(if (i % 2 == 1) d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }

        Text("Daily goal", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 28.dp))
        Text("${prefs.dailyGoalMinutes} minutes a day", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(prefs.dailyGoalMinutes.toFloat(), { v -> Graph.prefs.updateReader { it.copy(dailyGoalMinutes = (v / 5).toInt() * 5) } }, valueRange = 5f..120f)
        Text("$totalPages pages turned in BookShelf so far.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp))
    }
}

private fun formatHM(m: Int) = if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
