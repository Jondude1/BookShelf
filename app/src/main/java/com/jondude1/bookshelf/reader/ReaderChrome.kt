package com.jondude1.bookshelf.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.bookshelf.data.ReaderFont
import com.jondude1.bookshelf.data.ReaderPrefs
import com.jondude1.bookshelf.data.ReaderTheme
import com.jondude1.bookshelf.data.SpreadMode
import com.jondude1.bookshelf.ui.Inter
import com.jondude1.bookshelf.ui.Literata
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ReaderColors(val bg: Color, val fg: Color, val dark: Boolean) {
    val muted get() = fg.copy(alpha = 0.55f)
    val panel get() = if (dark) Color(0xF0181A1E) else Color(0xF2FFFDF8)
    val accent get() = if (dark) Color(0xFFD9B36C) else Color(0xFF8A6A2F)
    val line get() = fg.copy(alpha = 0.12f)

    companion object {
        fun of(t: ReaderTheme) = ReaderColors(Color(t.bg), Color(t.fg), t.dark)
    }
}

fun cssColor(c: Color): String {
    val r = (c.red * 255).toInt(); val g = (c.green * 255).toInt(); val b = (c.blue * 255).toInt()
    return "#%02x%02x%02x".format(r, g, b)
}

/** Top and bottom bars that slide in when the reader taps the middle of the page. */
@Composable
fun ReaderChrome(
    visible: Boolean,
    colors: ReaderColors,
    title: String,
    subtitle: String?,
    fraction: Float,
    pageLabel: String,
    timeLabel: String?,
    bookmarked: Boolean,
    ttsAvailable: Boolean,
    ttsPlaying: Boolean,
    sleepLabel: String? = null,
    onSleep: () -> Unit = {},
    onBack: () -> Unit,
    onContents: () -> Unit,
    onSearch: (() -> Unit)?,
    onBookmark: () -> Unit,
    onSettings: () -> Unit,
    onTts: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier.fillMaxWidth().background(colors.panel).windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.fg) }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(title, color = colors.fg, fontFamily = Literata, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search in book", tint = colors.fg) }
                    IconButton(onClick = onBookmark) {
                        Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Bookmark",
                            tint = if (bookmarked) colors.accent else colors.fg)
                    }
                }
            }
        }

        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().background(colors.panel).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp)
            ) {
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(fraction) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pageLabel, color = colors.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("${((if (dragging) dragValue else fraction) * 100).toInt()}%", color = colors.fg, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = if (dragging) dragValue else fraction,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { dragging = false; onSeek(dragValue) },
                    colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.line),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChromeButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Contents", colors, onContents)
                    if (ttsAvailable) ChromeButton(if (ttsPlaying) Icons.Default.StopCircle else Icons.Default.Headphones,
                        if (ttsPlaying) "Stop" else "Listen", colors, onTts)
                    if (sleepLabel != null) ChromeButton(Icons.Default.Bedtime, sleepLabel, colors, onSleep)
                    Spacer(Modifier.weight(1f))
                    if (timeLabel != null) Text(timeLabel, color = colors.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    ChromeButton(Icons.Default.FormatSize, "Display", colors, onSettings)
                }
            }
        }
    }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, colors: ReaderColors, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = colors.fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** Quiet status line drawn in the page margins while reading. */
@Composable
fun ReaderFooter(colors: ReaderColors, left: String, right: String, top: String?, showClock: Boolean, topInset: Int) {
    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(showClock) { while (showClock) { delay(15_000); clock = now() } }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = (topInset - 22).coerceAtLeast(6).dp, start = 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(top ?: "", color = colors.fg.copy(alpha = 0.38f), fontSize = 11.sp, fontFamily = Inter, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (showClock) Text(clock, color = colors.fg.copy(alpha = 0.38f), fontSize = 11.sp, fontFamily = Inter)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp, start = 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(left, color = colors.fg.copy(alpha = 0.42f), fontSize = 11.sp, fontFamily = Inter, modifier = Modifier.weight(1f), maxLines = 1)
            Text(right, color = colors.fg.copy(alpha = 0.42f), fontSize = 11.sp, fontFamily = Inter)
        }
    }
}

private fun now() = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm"))

// ---------------- Display settings ----------------

@Composable
fun ReaderSettingsPanel(prefs: ReaderPrefs, colors: ReaderColors, reflow: Boolean, isPdf: Boolean, isComic: Boolean, voices: List<Pair<String, String>> = emptyList(), onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        SectionLabel("Theme", colors)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReaderTheme.entries.forEach { t ->
                val selected = prefs.theme == t
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange { it.copy(theme = t) } }) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color(t.bg))
                            .border(if (selected) BorderStroke(2.5.dp, colors.accent) else BorderStroke(1.dp, colors.line), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Aa", color = Color(t.fg), fontFamily = Literata, fontSize = 16.sp)
                    }
                    Text(t.label, color = if (selected) colors.fg else colors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        ToggleRow("Switch to night theme after dark", "${prefs.nightStartHour}:00 – ${prefs.nightEndHour}:00 uses ${prefs.nightTheme.label}", prefs.autoNight, colors) { v -> onChange { it.copy(autoNight = v) } }

        if (reflow) {
            SectionLabel("Font", colors)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { f ->
                    FilterChip(selected = prefs.font == f, onClick = { onChange { it.copy(font = f) } },
                        label = { Text(f.label, fontFamily = when (f) { ReaderFont.LITERATA -> Literata; ReaderFont.SANS -> Inter; ReaderFont.MONO -> FontFamily.Monospace; ReaderFont.SERIF -> FontFamily.Serif; else -> null }) })
                }
            }
            LabeledSlider("Text size", "${(prefs.fontScale * 100).toInt()}%", prefs.fontScale, 0.7f..2.2f, colors) { v -> onChange { it.copy(fontScale = (v * 20).toInt() / 20f) } }
            LabeledSlider("Line spacing", "%.2f".format(prefs.lineHeight), prefs.lineHeight, 1.1f..2.2f, colors) { v -> onChange { it.copy(lineHeight = (v * 20).toInt() / 20f) } }
            LabeledSlider("Letter spacing", if (prefs.letterSpacing < 0.005f) "Default" else "+%.2f em".format(prefs.letterSpacing), prefs.letterSpacing, 0f..0.12f, colors) { v ->
                onChange { it.copy(letterSpacing = (v * 100).toInt() / 100f) }
            }
            SectionLabel("Paragraphs", colors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("BOOK" to "Book style", "INDENT" to "Indented", "SPACED" to "Spaced").forEach { (k, l) ->
                    FilterChip(selected = prefs.paragraphs == k, onClick = { onChange { it.copy(paragraphs = k) } }, label = { Text(l) })
                }
            }
            SectionLabel("Margins", colors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Narrow", "Normal", "Wide").forEachIndexed { i, l ->
                    FilterChip(selected = prefs.margin == i, onClick = { onChange { it.copy(margin = i) } }, label = { Text(l) })
                }
            }
            ToggleRow("Justify text", null, prefs.justify, colors) { v -> onChange { it.copy(justify = v) } }
            ToggleRow("Hyphenation", null, prefs.hyphenate, colors) { v -> onChange { it.copy(hyphenate = v) } }
            ToggleRow("Publisher styles", "Keep the book's own fonts and spacing", prefs.publisherStyles, colors) { v -> onChange { it.copy(publisherStyles = v) } }
        }

        SectionLabel("Two-page spread", colors)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpreadMode.entries.forEach { s ->
                FilterChip(selected = prefs.spread == s, onClick = { onChange { it.copy(spread = s) } }, label = { Text(s.label) })
            }
        }
        Text("Auto shows two pages side by side on a tablet in landscape.", color = colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

        if (isComic) ToggleRow("Right-to-left (manga)", null, prefs.comicRightToLeft, colors) { v -> onChange { it.copy(comicRightToLeft = v) } }
        if (isPdf) ToggleRow("Invert PDF pages in dark themes", null, prefs.invertPdfAtNight, colors) { v -> onChange { it.copy(invertPdfAtNight = v) } }

        SectionLabel("Light", colors)
        ToggleRow("Use system brightness", null, prefs.brightness < 0, colors) { v -> onChange { it.copy(brightness = if (v) -1f else 0.5f) } }
        if (prefs.brightness >= 0) LabeledSlider("Brightness", "${(prefs.brightness * 100).toInt()}%", prefs.brightness, 0f..1f, colors) { v -> onChange { it.copy(brightness = v) } }
        LabeledSlider("Warmth", if (prefs.warmth <= 0.01f) "Off" else "${(prefs.warmth * 100).toInt()}%", prefs.warmth, 0f..1f, colors) { v -> onChange { it.copy(warmth = v) } }

        SectionLabel("Page turn", colors)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SLIDE" to "Slide", "FADE" to "Fade", "NONE" to "Instant").forEach { (k, l) ->
                FilterChip(selected = prefs.pageTurn == k, onClick = { onChange { it.copy(pageTurn = k) } }, label = { Text(l) })
            }
        }
        if (!reflow) Text("Applies to EPUB, MOBI and AZW3 books.", color = colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

        SectionLabel("Controls", colors)
        ToggleRow("Tap page edges to turn", null, prefs.tapZones, colors) { v -> onChange { it.copy(tapZones = v) } }
        ToggleRow("Volume buttons turn pages", null, prefs.volumeKeys, colors) { v -> onChange { it.copy(volumeKeys = v) } }
        ToggleRow("Keep screen on", null, prefs.keepScreenOn, colors) { v -> onChange { it.copy(keepScreenOn = v) } }
        ToggleRow("Show clock", null, prefs.showClock, colors) { v -> onChange { it.copy(showClock = v) } }
        if (reflow) LabeledSlider("Read-aloud speed", "%.1fx".format(prefs.ttsRate), prefs.ttsRate, 0.6f..2.2f, colors) { v -> onChange { it.copy(ttsRate = (v * 10).toInt() / 10f) } }
        if (reflow && voices.isNotEmpty()) {
            SectionLabel("Read-aloud voice", colors)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = prefs.ttsVoice == null, onClick = { onChange { it.copy(ttsVoice = null) } }, label = { Text("System default") })
                voices.forEach { (name, label) ->
                    FilterChip(selected = prefs.ttsVoice == name, onClick = { onChange { it.copy(ttsVoice = name) } }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, colors: ReaderColors) {
    Text(text.uppercase(), color = colors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, value: Boolean, colors: ReaderColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.fg, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.bg))
    }
}

@Composable
private fun LabeledSlider(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, colors: ReaderColors, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(title, color = colors.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(v, onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.line))
    }
}

/** Selectable row used by the contents / highlights / bookmarks lists. */
@Composable
fun ListRow(title: String, detail: String?, colors: ReaderColors, indent: Int = 0, current: Boolean = false, dim: Boolean = false, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (20 + indent * 16).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (current) colors.accent else if (dim) colors.fg.copy(alpha = 0.5f) else colors.fg, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (current) Icon(Icons.Default.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        trailing?.invoke()
    }
}

@Composable
fun SheetTabs(tabs: List<String>, selected: Int, colors: ReaderColors, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(t, color = if (sel) colors.accent else colors.muted, style = MaterialTheme.typography.labelLarge) }
        }
    }
    Spacer(Modifier.height(4.dp))
}
