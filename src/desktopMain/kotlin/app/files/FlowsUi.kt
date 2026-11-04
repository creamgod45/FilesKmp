package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** A running (or finished) shortcut, shown in the run panel. */
class FlowRunState(val flow: Flow, val dryRun: Boolean) {
    val log = mutableStateListOf<String>()
    var running by mutableStateOf(true)
    var result by mutableStateOf<List<String>>(emptyList())
    var outcome by mutableStateOf<String?>(null)
    var job: kotlinx.coroutines.Job? = null
}

fun flowIcon(name: String): ImageVector = when (name) {
    "photo", "image" -> Icons.Outlined.Image
    "fingerprint" -> Icons.Outlined.Fingerprint
    "broom" -> Icons.Outlined.CleaningServices
    "chart" -> Icons.Outlined.BarChart
    "archive" -> Icons.Outlined.FolderZip
    "music" -> Icons.Outlined.MusicNote
    "terminal" -> Icons.Outlined.Terminal
    else -> Icons.Outlined.Bolt
}

/** A multi-line text box (scripts, longer text). */
@Composable
fun MultiField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false) {
    val p = LocalPalette.current
    val app = LocalAppState.current
    BasicTextField(
        value, onChange,
        textStyle = TextStyle(color = p.text, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else null),
        cursorBrush = SolidColor(p.text),
        modifier = modifier.background(if (p.dark) Color(0xFF1F1F1F) else Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(8.dp).onFocusChanged { app?.textFocus = it.isFocused },
    )
}

/** 捷徑: the list of shortcuts, an editor (steps or the raw JSON), the action reference, run / dry run. */
@Composable
fun FlowsDialog(s: AppState) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { FlowStore.reload() }
    val all = FlowStore.all()
    var selectedId by remember { mutableStateOf(all.firstOrNull()?.id) }
    val selected = all.firstOrNull { it.id == selectedId }
    var draft by remember(selectedId, FlowStore.mine) { mutableStateOf(selected) }
    var tab by remember { mutableStateOf(0) }
    var jsonText by remember(selectedId, tab) { mutableStateOf(draft?.toJson() ?: "") }
    var message by remember { mutableStateOf<String?>(null) }
    val close = { s.flowsOpen = false }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { close() } }, contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f).widthIn(max = 1280.dp).softShadow(shape, 18.dp).clip(shape).background(p.solid, shape)
                .border(1.dp, p.controlStroke, shape).pointerInput(Unit) { detectTapGestures2 { } }
        ) {
            // ------------------------------------------------ list
            Column(Modifier.width(280.dp).fillMaxHeight().background(if (p.dark) Color(0xFF242424) else Color(0xFFF0F0F0)).padding(12.dp)) {
                T(S.flows, Modifier.padding(start = 6.dp, bottom = 10.dp), size = 20.sp, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogButton("+ " + S.flowNew, primary = true) {
                        val f = FlowStore.save(Flow("", if (S.zh) "新捷徑" else "New shortcut", steps = listOf(FlowStep("files.selected"))))
                        selectedId = f.id; tab = 0
                    }
                    DialogButton(S.flowImport, primary = false) {
                        val d = java.awt.FileDialog(null as java.awt.Frame?, S.flowImport, java.awt.FileDialog.LOAD).apply { file = "*.fkflow.json;*.json"; isVisible = true }
                        d.file?.let { name -> runCatching { FlowStore.importFile(File(d.directory, name)) }.onSuccess { selectedId = it.id; message = null }.onFailure { message = it.message } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    @Composable fun row(f: Flow) = SidebarRow(f.name, 0, f.id == selectedId, onClick = { selectedId = f.id; message = null }) {
                        Icon(flowIcon(f.icon), null, Modifier.size(16.dp), tint = p.accent)
                    }
                    if (FlowStore.mine.isNotEmpty()) { GroupTitle(S.flowMine); FlowStore.mine.forEach { row(it) } }
                    GroupTitle(S.flowSamples); FlowStore.samples().forEach { row(it) }
                    FlowStore.errors.forEach { T(it, Modifier.padding(6.dp), size = 11.sp, color = p.danger, maxLines = 3) }
                }
                Hover(Modifier.fillMaxWidth().height(30.dp), onClick = {
                    val d = java.awt.FileDialog(null as java.awt.Frame?, S.flowSpec, java.awt.FileDialog.SAVE).apply { file = "fileskmp-shortcut-v1.md"; isVisible = true }
                    d.file?.let { name -> File(d.directory, name).writeText(FlowActions.specMarkdown(), Charsets.UTF_8); message = S.saved + " " + name }
                }) { T(S.flowSpec, Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp), size = 12.sp, color = p.accentText) }
            }
            // ------------------------------------------------ editor
            Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                val d = draft
                if (d == null) { T(S.flowNone, color = p.textSecondary); return@Column }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(flowIcon(d.icon), null, Modifier.size(24.dp), tint = p.accent)
                    Spacer(Modifier.width(10.dp))
                    T(d.name, Modifier.weight(1f), size = 20.sp, weight = FontWeight.SemiBold)
                    IconBtn(Icons.Outlined.Close, S.close, close)
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(S.flowEdit, "JSON", S.flowReference).forEachIndexed { i, label ->
                        Hover(Modifier.height(30.dp), onClick = {
                            if (tab == 1 && i != 1) runCatching { Flow.fromJson(d.id, jsonText).copy(builtIn = d.builtIn) }.onSuccess { draft = it; message = null }.onFailure { message = it.message }
                            tab = i
                        }, selected = tab == i) { T(label, Modifier.align(Alignment.Center).padding(horizontal = 12.dp), size = 13.sp) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> FlowEditor(d) { draft = it }
                        1 -> MultiField(jsonText, { jsonText = it }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()), mono = true)
                        else -> ActionReference()
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun current(): Flow? = if (tab == 1) runCatching { Flow.fromJson(d.id, jsonText).copy(builtIn = d.builtIn) }.onFailure { message = it.message }.getOrNull() else d
                    DialogButton(S.flowRun, primary = true) { current()?.let { close(); s.runFlow(it, dryRun = false) } }
                    DialogButton(S.flowDryRun, primary = false) { current()?.let { close(); s.runFlow(it, dryRun = true) } }
                    DialogButton(if (d.builtIn) S.flowSaveCopy else S.flowSave, primary = false) {
                        current()?.let { f -> val saved = FlowStore.save(f); selectedId = saved.id; message = S.saved }
                    }
                    DialogButton(S.flowExport, primary = false) {
                        current()?.let { f ->
                            val dlg = java.awt.FileDialog(null as java.awt.Frame?, S.flowExport, java.awt.FileDialog.SAVE).apply { file = f.name.replace(Regex("[<>:\"/\\\\|?*]"), "_") + ".fkflow.json"; isVisible = true }
                            dlg.file?.let { name -> File(dlg.directory, name).writeText(f.toJson(), Charsets.UTF_8); message = S.saved + " " + name }
                        }
                    }
                    if (!d.builtIn) DialogButton(S.remove, primary = false) { FlowStore.delete(d); selectedId = FlowStore.all().firstOrNull()?.id }
                    T(message ?: "", Modifier.weight(1f), size = 12.sp, color = if (message == S.saved || message?.startsWith(S.saved) == true) p.textSecondary else p.danger, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun FlowEditor(f: Flow, onChange: (Flow) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(S.flowName, Modifier.width(90.dp), size = 13.sp); PlainField(f.name, { onChange(f.copy(name = it)) }, Modifier.weight(1f))
            T(S.flowHotkey, size = 13.sp); PlainField(f.hotkey, { onChange(f.copy(hotkey = it)) }, Modifier.width(140.dp), placeholder = "Ctrl+Alt+1")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(S.flowDescription, Modifier.width(90.dp), size = 13.sp); PlainField(f.description, { onChange(f.copy(description = it)) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            T(S.flowAccepts, Modifier.width(90.dp), size = 13.sp)
            listOf("files", "folder", "text", "none").forEach { a ->
                FlowCheck(a in f.accepts, a) { onChange(f.copy(accepts = if (a in f.accepts) f.accepts - a else f.accepts + a)) }
            }
        }
        GroupTitle(S.flowParams)
        f.params.forEachIndexed { i, prm ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PlainField(prm.id, { v -> onChange(f.copy(params = f.params.toMutableList().also { it[i] = FlowParam(v, prm.label, prm.type, prm.default) })) }, Modifier.width(90.dp), placeholder = "id")
                PlainField(prm.label, { v -> onChange(f.copy(params = f.params.toMutableList().also { it[i] = FlowParam(prm.id, v, prm.type, prm.default) })) }, Modifier.width(160.dp), placeholder = S.flowLabel)
                PlainField(prm.default, { v -> onChange(f.copy(params = f.params.toMutableList().also { it[i] = FlowParam(prm.id, prm.label, prm.type, v) })) }, Modifier.weight(1f), placeholder = S.flowDefault)
                IconBtn(Icons.Outlined.Close, S.remove, { onChange(f.copy(params = f.params.filterIndexed { j, _ -> j != i })) }, width = 28.dp, height = 26.dp, iconSize = 13.dp)
            }
        }
        Hover(Modifier.height(28.dp), onClick = { onChange(f.copy(params = f.params + FlowParam("p${f.params.size + 1}", "", "text", ""))) }) {
            T("+ " + S.flowAddParam, Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp), size = 12.sp, color = p.accentText)
        }
        GroupTitle(S.flowSteps)
        StepList(f.steps) { onChange(f.copy(steps = it)) }
    }
}

@Composable
private fun StepList(steps: List<FlowStep>, depth: Int = 0, onChange: (List<FlowStep>) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(start = (depth * 18).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, st ->
            StepCard(st, i, steps.size, depth,
                onChange = { n -> onChange(steps.toMutableList().also { it[i] = n }) },
                onMove = { d -> val j = i + d; if (j in steps.indices) onChange(steps.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }) },
                onDelete = { onChange(steps.filterIndexed { j, _ -> j != i }) })
            if (i < steps.size - 1) Icon(Icons.Outlined.ArrowDownward, null, Modifier.padding(start = 16.dp).size(12.dp), tint = p.textTertiary)
        }
        AddStepMenu { onChange(steps + FlowStep(it.id)) }
    }
}

@Composable
private fun AddStepMenu(add: (FlowAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val p = LocalPalette.current
    Box {
        Hover(Modifier.height(30.dp), onClick = { open = true }) { T("+ " + S.flowAddStep, Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp), size = 12.sp, color = p.accentText) }
        Flyout(open, { open = false }) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                FlowActions.all.groupBy { it.category }.forEach { (cat, acts) ->
                    T(FlowActions.categoryLabel(cat), Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp), size = 11.sp, color = p.textSecondary)
                    acts.forEach { a -> FlowItemRow(a) { open = false; add(a) } }
                }
            }
        }
    }
}

@Composable
private fun FlowItemRow(a: FlowAction, onClick: () -> Unit) {
    val p = LocalPalette.current
    Hover(Modifier.fillMaxWidth().widthIn(min = 320.dp), onClick = onClick) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            T(a.label + if (a.changes) "  •" else "", size = 13.sp)
            T(a.description, size = 11.sp, color = p.textTertiary, maxLines = 2)
        }
    }
}

@Composable
private fun FlowCheck(on: Boolean, label: String, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(16.dp), tint = if (on) p.accent else p.textSecondary)
        Spacer(Modifier.width(4.dp)); T(label, size = 12.sp)
    }
}

@Composable
private fun StepCard(st: FlowStep, index: Int, total: Int, depth: Int, onChange: (FlowStep) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    val a = FlowActions.byId[st.action]
    Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(6.dp)).border(1.dp, if (a == null) p.danger else p.cardStroke, RoundedCornerShape(6.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowCheck(st.enabled, "${index + 1}. " + (a?.label ?: st.action)) { onChange(FlowStep(st.action, st.args, !st.enabled)) }
            if (a != null) T("  " + FlowActions.categoryLabel(a.category) + if (a.changes) " · " + S.flowChanges else "", size = 11.sp, color = p.textTertiary)
            Spacer(Modifier.weight(1f))
            IconBtn(Icons.Outlined.ArrowUpward, S.moveUp, { onMove(-1) }, enabled = index > 0, width = 26.dp, height = 24.dp, iconSize = 13.dp)
            IconBtn(Icons.Outlined.ArrowDownward, S.moveDown, { onMove(1) }, enabled = index < total - 1, width = 26.dp, height = 24.dp, iconSize = 13.dp)
            IconBtn(Icons.Outlined.Close, S.remove, onDelete, width = 26.dp, height = 24.dp, iconSize = 13.dp)
        }
        if (a == null) { T(S.flowUnknown, size = 11.sp, color = p.danger); return@Column }
        a.args.forEach { g ->
            fun set(v: Any?) = onChange(FlowStep(st.action, st.args + (g.id to v), st.enabled))
            val cur = st.args[g.id] ?: g.default
            if (g.type == "steps") {
                @Suppress("UNCHECKED_CAST") val inner = (cur as? List<FlowStep>).orEmpty()
                T(g.label, size = 12.sp, color = p.textSecondary)
                StepList(inner, depth + 1) { set(it) }
                return@forEach
            }
            Row(verticalAlignment = if (g.type == "multiline") Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(g.label, Modifier.width(150.dp), size = 12.sp, color = p.textSecondary, maxLines = 2)
                when (g.type) {
                    "bool" -> FlowCheck(cur == true || cur.toString() == "true", "") { set(!(cur == true || cur.toString() == "true")) }
                    "multiline" -> MultiField(cur.toString(), { set(it) }, Modifier.weight(1f).heightIn(min = 70.dp), mono = st.action == "shell.run")
                    "choice" -> {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Hover(Modifier.height(30.dp).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)), onClick = { open = true }) {
                                Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    T(cur.toString(), size = 12.sp); Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp))
                                }
                            }
                            Flyout(open, { open = false }) { g.choices.forEach { c -> FlowyChoice(c, c == cur.toString()) { open = false; set(c) } } }
                        }
                    }
                    "number" -> PlainField(cur.toString(), { v -> set(v.toLongOrNull() ?: v) }, Modifier.width(100.dp))
                    else -> PlainField(cur.toString(), { set(it) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FlowyChoice(label: String, on: Boolean, onClick: () -> Unit) = FlyoutItem(label, if (on) Icons.Outlined.Check else null, "", onClick = onClick)

/** Every action with its arguments: the format's reference, in the app. */
@Composable
private fun ActionReference() {
    val p = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
        item { T(S.flowRefHint, Modifier.padding(bottom = 8.dp), size = 12.sp, color = p.textSecondary, maxLines = 6) }
        items(FlowActions.all) { a ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(a.id, size = 12.sp, color = p.accentText, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp)); T(a.label, size = 13.sp, weight = FontWeight.SemiBold)
                    if (a.changes) T("  · " + S.flowChanges, size = 11.sp, color = p.textTertiary)
                }
                T(a.description, size = 12.sp, color = p.textSecondary, maxLines = 3)
                a.args.forEach { g -> T("    ${g.id} (${g.type}) — ${g.label}" + (g.default.takeIf { it is String && it.isNotEmpty() || it is Number || it is Boolean }?.let { "  = $it" } ?: ""), size = 11.sp, color = p.textTertiary, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

/** The run panel (bottom right): live log, the result, stop / close / copy. */
@Composable
fun FlowRunPanel(s: AppState, r: FlowRunState) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.width(460.dp).heightIn(max = 420.dp).softShadow(shape, 14.dp).background(p.flyout, shape).border(1.dp, p.controlStroke, shape).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(flowIcon(r.flow.icon), null, Modifier.size(18.dp), tint = p.accent)
                Spacer(Modifier.width(8.dp))
                T(r.flow.name + if (r.dryRun) "  (" + S.flowDryRun + ")" else "", Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold)
                if (r.running) DialogButton(S.flowStop, primary = false) { r.job?.cancel() }
                else IconBtn(Icons.Outlined.Close, S.close, { s.flowRun = null }, width = 30.dp, height = 28.dp)
            }
            T(if (r.running) S.flowRunning else r.outcome ?: "", Modifier.padding(vertical = 4.dp), size = 12.sp,
                color = if (!r.running && r.outcome != S.flowDone) p.danger else p.textSecondary, maxLines = 3)
            val ls = rememberLazyListState()
            LaunchedEffect(r.log.size) { if (r.log.isNotEmpty()) ls.scrollToItem(r.log.size - 1) }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 260.dp).fillMaxWidth().background(if (p.dark) Color(0xFF1A1A1A) else Color(0xFFF6F6F6), RoundedCornerShape(4.dp)).padding(8.dp), state = ls) {
                items(r.log.toList()) { T(it, size = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3) }
            }
            if (!r.running && r.result.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                T(S.items(r.result.size), Modifier.weight(1f), size = 12.sp, color = p.textSecondary)
                DialogButton(S.flowCopyResult, primary = false) { copyText(r.result.joinToString("\n")) }
            }
        }
    }
}

/** Questions while a shortcut runs (ui.input, ui.choose, ui.confirm, and its parameters). */
@Composable
fun FlowPromptDialog(s: AppState, q: FlowPrompt) {
    val p = LocalPalette.current
    var text by remember(q) { mutableStateOf(q.default) }
    fun done(v: String?) { s.flowPrompt = null; q.answer.complete(v) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).heightIn(max = 560.dp).softShadow(shape, 18.dp).background(p.solid, shape).border(1.dp, p.controlStroke, shape).padding(20.dp)) {
            T(s.flowRun?.flow?.name ?: S.flows, size = 12.sp, color = p.textSecondary)
            Spacer(Modifier.height(4.dp))
            T(q.title, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 4)
            Spacer(Modifier.height(12.dp))
            when (q.kind) {
                "input" -> PlainField(text, { text = it }, Modifier.fillMaxWidth())
                "choose" -> Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    q.options.forEach { o -> Hover(Modifier.fillMaxWidth().height(32.dp), onClick = { done(o) }) { T(File(o).name.ifEmpty { o }, Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)) } }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (q.kind != "choose") DialogButton(if (q.kind == "confirm") S.flowContinue else (if (S.zh) "確定" else "OK"), primary = true) { done(if (q.kind == "confirm") "yes" else text) }
                DialogButton(if (S.zh) "取消" else "Cancel", primary = false) { done(null) }
            }
        }
    }
}
