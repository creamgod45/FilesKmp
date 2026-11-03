package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small coloured tag chips, as in the original's Tag column. */
@Composable
fun TagPills(tags: List<TagDef>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(3).forEach { t ->
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).background(Color(t.color).copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(Color(t.color), CircleShape))
                Spacer(Modifier.width(4.dp))
                T(t.name, size = 11.sp)
            }
        }
        if (tags.size > 3) T("+${tags.size - 3}", size = 11.sp, color = LocalPalette.current.textSecondary)
    }
}

/** "Edit tags" dialog: tick tags on or off for the chosen files, or make a new tag. */
@Composable
fun TagEditorDialog(s: AppState, paths: List<String>) {
    val p = LocalPalette.current
    val store = s.tagStore
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TagPalette[4]) }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { s.tagEditorFor = null } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(380.dp).shadow8().background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures2 { } }.padding(20.dp)
        ) {
            T(S.editTags, size = 20.sp, weight = FontWeight.SemiBold)
            T(if (paths.size == 1) Fs.displayName(paths[0]) else S.selected(paths.size), size = 12.sp, color = p.textSecondary)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(store.defs, key = { it.name }) { tag ->
                    val count = paths.count { tag.name in (store.byPath[it] ?: emptySet()) }
                    val state = when (count) { 0 -> 0; paths.size -> 2; else -> 1 }
                    Hover(Modifier.fillMaxWidth().height(36.dp), onClick = { store.set(paths, tag.name, state != 2) }) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (state) { 2 -> Icons.Outlined.CheckBox; 1 -> Icons.Outlined.IndeterminateCheckBox; else -> Icons.Outlined.CheckBoxOutlineBlank },
                                null, Modifier.size(18.dp), tint = if (state > 0) p.accent else p.textSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Filled.LocalOffer, null, Modifier.size(16.dp), tint = Color(tag.color))
                            Spacer(Modifier.width(8.dp))
                            T(tag.name)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            T(S.newTag, size = 12.sp, color = p.textSecondary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlainField(newName, { newName = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                DialogButton(S.add, primary = false) {
                    if (store.addTag(newName, newColor)) { store.set(paths, newName.trim(), true); newName = "" }
                }
            }
            Spacer(Modifier.height(6.dp))
            ColorRow(newColor) { newColor = it }
            Spacer(Modifier.height(16.dp))
            DialogButton(S.done, primary = true, modifier = Modifier.fillMaxWidth()) {
                s.tagEditorFor = null
                s.allPanes().filter { it.isTag }.forEach { s.load(it, keepSelection = true) }
            }
        }
    }
}

@Composable
fun ColorRow(selected: Long, onPick: (Long) -> Unit) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TagPalette.forEach { c ->
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 2.dp else 0.dp, if (c == selected) p.text else Color.Transparent, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
fun PlainField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    val p = LocalPalette.current
    val app = LocalAppState.current
    Box(
        modifier.height(32.dp).background(if (p.dark) Color(0xFF1F1F1F) else Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, color = p.textTertiary)
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.text),
            // While typing here, the app's single-key shortcuts (Delete, F2, Backspace…) stay off.
            modifier = Modifier.fillMaxWidth().onFocusChanged { app?.textFocus = it.isFocused },
        )
    }
}

@Composable
fun DialogButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(4.dp))
            .background(if (primary) p.accent else p.control)
            .border(1.dp, if (primary) Color.Transparent else p.controlStroke, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, color = if (primary) (if (p.dark) Color.Black else Color.White) else p.text) }
}

val LocalAppState = staticCompositionLocalOf<AppState?> { null }
