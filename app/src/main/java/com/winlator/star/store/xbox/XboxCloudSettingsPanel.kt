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
import androidx.compose.runtime.Composable
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
    onChange: (XboxCloudSettings) -> Unit,
) {
    val ctx = LocalContext.current
    var profiles by remember { mutableStateOf(XboxCloudSettings.wineProfiles(ctx)) }
    var naming by remember { mutableStateOf(false) }

    if (section == null || section == "stream") Section("Stream", if (live) "Applies to the next stream." else "Applies when a stream starts.") {
        Choice("Resolution", listOf("720p" to "720p", "1080p" to "1080p", "1080p_hq" to "1080p HQ"), settings.resolution) { onChange(settings.copy(resolution = it)) }
        Choice("Server region", listOf("" to "Automatic") + regions.map { it to it }, settings.region) { onChange(settings.copy(region = it)) }
        Choice("Game language", XboxCloudSettings.LANGUAGES, settings.language) { onChange(settings.copy(language = it)) }
        Choice("Video codec quality", listOf("default" to "Automatic", "high" to "High", "normal" to "Normal", "low" to "Low"), settings.codec) { onChange(settings.copy(codec = it)) }
        Choice("Bitrate limit", listOf(0 to "No limit") + listOf(5, 8, 10, 12, 15, 20, 25, 30, 40, 50).map { it to "$it Mb/s" }, settings.bitrateMbps) { onChange(settings.copy(bitrateMbps = it)) }
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

@Composable
private fun Step(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        Text(value, Modifier.width(112.dp).padding(horizontal = 4.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    Step(label, options.getOrNull(index)?.second ?: "",
        { if (options.isNotEmpty()) onSelect(options[(index - 1 + options.size) % options.size].first) },
        { if (options.isNotEmpty()) onSelect(options[(index + 1) % options.size].first) })
}

@Composable
private fun Toggle(label: String, note: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
