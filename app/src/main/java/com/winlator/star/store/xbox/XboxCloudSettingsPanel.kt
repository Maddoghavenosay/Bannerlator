package com.winlator.star.store.xbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Every Xbox Cloud Gaming player setting, as rows a controller can drive (each value has − / + buttons).
 * Shared by Xbox › Profile › Cloud gaming settings and the in-player menu ([live] = changes reach the stream
 * at once; stream-start settings say they apply to the next stream).
 */
@Composable
fun XboxCloudSettingsPanel(
    settings: XboxCloudSettings,
    regions: List<String>,
    live: Boolean,
    /** "stream", "picture" or "overlay" shows that section only; null shows all. */
    section: String? = null,
    /** The row to select when the panel opens (the last one used), or null for no initial selection. */
    restoreRow: Int? = null,
    onRowFocused: (Int) -> Unit = {},
    /** The settings the running stream started with; rows that differ say they need a restart (null: no stream). */
    startedWith: XboxCloudSettings? = null,
    onChange: (XboxCloudSettings) -> Unit,
) {
    val focus = remember(section) { PanelFocus() }
    focus.next = 0
    focus.onFocused = onRowFocused
    CompositionLocalProvider(LocalPanelFocus provides focus) {
        PanelContent(settings, regions, live, section, startedWith, onChange)
    }
    LaunchedEffect(section) {
        if (restoreRow != null) runCatching { focus.requester(restoreRow.coerceIn(0, (focus.count - 1).coerceAtLeast(0))).requestFocus() }
    }
}

/** Gives each settings row its place in the page, so the last-used row can be selected again. */
private class PanelFocus {
    var next = 0
    var count = 0
    var onFocused: (Int) -> Unit = {}
    private val requesters = mutableMapOf<Int, FocusRequester>()
    fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    fun take(): Int = next++.also { count = maxOf(count, next) }
}

private val LocalPanelFocus = compositionLocalOf<PanelFocus?> { null }

@Composable
private fun PanelContent(
    settings: XboxCloudSettings,
    regions: List<String>,
    live: Boolean,
    section: String?,
    startedWith: XboxCloudSettings?,
    onChange: (XboxCloudSettings) -> Unit,
) {
    val ctx = LocalContext.current
    // Stream-start settings changed during a stream wait for a restart; back at the starting value, no note.
    fun pending(differs: (XboxCloudSettings) -> Boolean) = startedWith != null && differs(startedWith)
    var profiles by remember { mutableStateOf(XboxCloudSettings.wineProfiles(ctx)) }
    var naming by remember { mutableStateOf(false) }

    if (section == null || section == "stream") Section("Stream", if (live) "Applies to the next stream." else "Applies when a stream starts.") {
        // What Microsoft sends depends on the device profile we report: the PC profile gave 2560x1440 on the
        // Pocket FIT (10-23), so it is labelled by profile, not by a fixed size.
        Choice("Resolution", listOf("720p" to "720p (phone profile)", "1080p" to "Best (PC profile)", "1080p_hq" to "1080p HQ (TV profile)"),
            settings.resolution, pending { it.resolution != settings.resolution }) { onChange(settings.copy(resolution = it)) }
        Choice("Server region", listOf("" to "Automatic") + regions.map { it to it }, settings.region,
            pending { it.region != settings.region }) { onChange(settings.copy(region = it)) }
        Choice("Game language", XboxCloudSettings.LANGUAGES, settings.language,
            pending { it.language != settings.language }) { onChange(settings.copy(language = it)) }
        Choice("Video codec quality", listOf("default" to "Automatic", "high" to "High", "normal" to "Normal", "low" to "Low"), settings.codec,
            pending { it.codec != settings.codec }) { onChange(settings.copy(codec = it)) }
        Choice("Bitrate limit", listOf(0 to "No limit") + listOf(5, 8, 10, 12, 15, 20, 25, 30, 40, 50).map { it to "$it Mb/s" }, settings.bitrateMbps,
            pending { it.bitrateMbps != settings.bitrateMbps }) { onChange(settings.copy(bitrateMbps = it)) }
        Toggle("Stop quality drops", "Keeps the resolution up when this device decodes slowly.", settings.preventDrops) { onChange(settings.copy(preventDrops = it)) }
    }

    if (section == null || section == "picture") Section("Picture", "Shader effects on the stream; the profiles are shared with the Wine games' screen effects.") {
        Choice("Profile", listOf("" to "None") + profiles.keys.map { it to it }, settings.profile) { name ->
            onChange(if (name.isEmpty()) settings.copy(profile = "") else settings.withEffects(profiles.getValue(name), name))
        }
        Step("Brightness", "${settings.brightness}", { onChange(settings.copy(brightness = (settings.brightness - 5).coerceAtLeast(-50))) },
            { onChange(settings.copy(brightness = (settings.brightness + 5).coerceAtMost(50))) })
        Step("Contrast", "${settings.contrast}", { onChange(settings.copy(contrast = (settings.contrast - 10).coerceAtLeast(-100))) },
            { onChange(settings.copy(contrast = (settings.contrast + 10).coerceAtMost(100))) })
        Step("Gamma", String.format("%.1f", settings.gamma), { onChange(settings.copy(gamma = ((settings.gamma * 10).toInt() - 1).coerceAtLeast(5) / 10f)) },
            { onChange(settings.copy(gamma = ((settings.gamma * 10).toInt() + 1).coerceAtMost(30) / 10f)) })
        Step("Saturation", "${settings.saturation}%", { onChange(settings.copy(saturation = (settings.saturation - 10).coerceAtLeast(0))) },
            { onChange(settings.copy(saturation = (settings.saturation + 10).coerceAtMost(200))) })
        Step("Sharpen", if (settings.sharpen == 0) "Off" else "${settings.sharpen}", { onChange(settings.copy(sharpen = (settings.sharpen - 1).coerceAtLeast(0))) },
            { onChange(settings.copy(sharpen = (settings.sharpen + 1).coerceAtMost(10))) })
        Choice("Sharpen type", listOf("cas" to "Contrast-adaptive", "usm" to "Unsharp mask"), settings.sharpenMode) { onChange(settings.copy(sharpenMode = it)) }
        Step("Deband", if (settings.deband == 0) "Off" else "${settings.deband}", { onChange(settings.copy(deband = (settings.deband - 1).coerceAtLeast(0))) },
            { onChange(settings.copy(deband = (settings.deband + 1).coerceAtMost(8))) })
        Toggle("FXAA", "Smooths jagged edges.", settings.fxaa) { onChange(settings.copy(fxaa = it)) }
        Toggle("CRT", "Scanlines and colour fringes.", settings.crt) { onChange(settings.copy(crt = it)) }
        Toggle("NTSC", "Old TV signal look.", settings.ntsc) { onChange(settings.copy(ntsc = it)) }
        Toggle("Toon", "Cartoon outlines.", settings.toon) { onChange(settings.copy(toon = it)) }
        Choice("Frame-rate cap", listOf(0 to "Off", 30 to "30 fps", 45 to "45 fps", 60 to "60 fps"), settings.maxFps) { onChange(settings.copy(maxFps = it)) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { naming = true }) { Text("Save as profile") }
            OutlinedButton(onClick = { onChange(settings.withoutEffects()) }) { Text("Reset picture") }
        }
        Text("Effects cost a little GPU time; with none set the stream is shown directly.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }

    if (section == null || section == "overlay") Section("Overlay and sound", null) {
        Choice("Performance overlay", listOf(0 to "Off", 1 to "FPS only", 2 to "Basic", 3 to "Detailed", 4 to "Full"), settings.statsLevel) {
            onChange(settings.copy(statsLevel = it))
        }
        Choice("Overlay layout", listOf("horizontal" to "Horizontal", "vertical" to "Vertical"), settings.statsLayout) { onChange(settings.copy(statsLayout = it)) }
        Text("Top right. Basic: fps, ping, resolution. Detailed: + bitrate, decode time, packet loss. Full: + codec, jitter, " +
            "dropped frames, server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Step("Volume", "${settings.volume}%", { onChange(settings.copy(volume = (settings.volume - 25).coerceAtLeast(100))) },
            { onChange(settings.copy(volume = (settings.volume + 25).coerceAtMost(300))) })
        Step("Controller rumble", if (settings.rumble == 0) "Off" else "${settings.rumble}%", { onChange(settings.copy(rumble = (settings.rumble - 25).coerceAtLeast(0))) },
            { onChange(settings.copy(rumble = (settings.rumble + 25).coerceAtMost(100))) })
    }

    if (naming) {
        var name by remember { mutableStateOf(settings.profile) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save picture profile") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.replace(":", "").replace(",", "").take(40) }, singleLine = true,
                label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                XboxCloudSettings.saveWineProfile(ctx, name.trim(), settings)
                profiles = XboxCloudSettings.wineProfiles(ctx)
                onChange(settings.copy(profile = name.trim()))
                naming = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, note: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * One settings row, driven by a controller: it takes focus as a whole (highlighted), Left / Right change its
 * value, A / Enter selects; the − / + buttons and switches inside stay for touch only.
 */
@Composable
private fun SettingRow(
    onLeft: (() -> Unit)?,
    onRight: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val panel = LocalPanelFocus.current
    val index = panel?.take() ?: -1
    var focused by remember { mutableStateOf(false) }
    val currentSelect by rememberUpdatedState(onSelect)
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(shape)
            .background(if (focused) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
            .border(2.dp, if (focused) cs.primary else Color.Transparent, shape)
            .let { if (panel != null && index >= 0) it.focusRequester(panel.requester(index)) else it }
            .onFocusChanged { state -> focused = state.isFocused; if (state.isFocused && index >= 0) panel?.onFocused?.invoke(index) }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val action = when (event.key) {
                    Key.DirectionLeft -> onLeft
                    Key.DirectionRight -> onRight
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA -> onSelect
                    else -> null
                }
                if (action != null) { action(); true } else false
            }
            .focusable()
            // A tap selects too, without a second focus stop (a clickable would be one).
            .pointerInput(Unit) { detectTapGestures { currentSelect?.invoke() } }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val noFocus = Modifier.focusProperties { canFocus = false }

@Composable
private fun Step(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    SettingRow(onLeft = onMinus, onRight = onPlus, onSelect = onPlus) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        OutlinedButton(onClick = onMinus, modifier = noFocus.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        Text(value, Modifier.width(112.dp).padding(horizontal = 4.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        OutlinedButton(onClick = onPlus, modifier = noFocus.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, needsRestart: Boolean = false, onSelect: (T) -> Unit) {
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    Step(label, options.getOrNull(index)?.second ?: "",
        { if (options.isNotEmpty()) onSelect(options[(index - 1 + options.size) % options.size].first) },
        { if (options.isNotEmpty()) onSelect(options[(index + 1) % options.size].first) })
    if (needsRestart) Text("Takes effect when you restart the stream.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(label: String, note: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    SettingRow(onLeft = { onToggle(false) }, onRight = { onToggle(true) }, onSelect = { onToggle(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onToggle, modifier = noFocus)
    }
}
