package com.winlator.star.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.core.FastOpenGL

/** The game editor's pill values: follow the container, force on, force off (the shortcut extra). */
val FAST_OPENGL_OVERRIDES: List<String> = listOf("", "1", "0")

/**
 * The game editor's "Fast OpenGL" row: a label, a segmented pill group (Container | On | Off) and one
 * helper line, in the same frame as [SyncModeSelector]. [selected] is the stored override ("" = the
 * container's, "1", "0"); [containerOn] the container's switch, shown on the Container pill.
 * [lockedReason] non-null (Wayland: always on; X11: what the layer/driver lacks) → only the current
 * pill stays live and the line says why.
 *
 * Touch only; a D-pad host drives [onPick] itself ([focused] draws its highlight). [onHelp] non-null
 * → the app's usual "?" beside the label (R.string.help_fast_opengl).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FastOpenGLSelector(
    selected: String,
    containerOn: Boolean,
    lockedReason: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onHelp: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val containerLabel = if (containerOn) "On" else "Off"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) cs.primary else cs.primary.copy(alpha = 0.33f),
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 10.dp),
            ) {
                Text(FastOpenGL.TITLE, fontSize = 14.sp, color = cs.onSurfaceVariant)
                if (onHelp != null) {
                    IconButton(onClick = onHelp, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Help, contentDescription = "What is Fast OpenGL?", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(vertical = 4.dp)
                    .background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(50))
                    .padding(3.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (value in FAST_OPENGL_OVERRIDES) {
                    val on = value == selected
                    val available = on || lockedReason == null
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(32.dp)
                            .widthIn(min = 44.dp)
                            .background(if (on) cs.primary else Color.Transparent, RoundedCornerShape(50))
                            .selectable(
                                selected = on,
                                enabled = available,
                                role = Role.RadioButton,
                                onClick = { if (!on) onPick(value) },
                            )
                            .padding(horizontal = 9.dp),
                    ) {
                        Text(
                            when (value) { "1" -> "On"; "0" -> "Off"; else -> "Container ($containerLabel)" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                on -> cs.onPrimary
                                available -> cs.onSurface
                                else -> cs.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
            }
        }
        Text(
            fastOpenGLGameHelper(selected, containerOn, lockedReason),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

/** The game editor's helper line: why it's locked, else what this game runs with. */
fun fastOpenGLGameHelper(selected: String, containerOn: Boolean, lockedReason: String?): String {
    val c = if (containerOn) "On" else "Off"
    return when {
        lockedReason != null -> "$lockedReason."
        selected == "1" || selected == "0" ->
            (if (selected == "1") "On" else "Off") + " for this game only (container: $c)."
        else -> "Following the container: $c.  " + FastOpenGL.HINT
    }
}
