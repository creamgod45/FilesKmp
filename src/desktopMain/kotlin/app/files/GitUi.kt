package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dimmed backdrop + card used by the Git dialogs. Clicking outside closes it. */
@Composable
private fun GitDialogFrame(title: String, width: Dp, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { onDismiss() } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(width).heightIn(max = 680.dp).softShadow(shape, elevation = 18.dp).background(p.solid, shape)
                .border(1.dp, p.controlStroke, shape).pointerInput(Unit) { detectTapGestures2 { } }.padding(20.dp)
        ) {
            T(title, size = 20.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun GitBranchDialog(s: AppState) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { s.gitBranchDialog = false } }, contentAlignment = Alignment.Center) {
        GitInput(if (S.zh) "建立分支" else "Create branch", if (S.zh) "分支名稱" else "Branch name", { s.gitBranchDialog = false }) {
            s.gitCreateBranch(it); s.gitBranchDialog = false
        }
    }
}

@Composable
fun GitCloneDialog(s: AppState) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { s.cloneDialogOpen = false } }, contentAlignment = Alignment.Center) {
        GitInput(if (S.zh) "複製儲存庫" else "Clone repository", if (S.zh) "儲存庫 URL 或本機路徑" else "Repository URL or local path", { s.cloneDialogOpen = false }) {
            s.gitClone(it); s.cloneDialogOpen = false
        }
    }
}

@Composable
private fun GitInput(title: String, hint: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf("") }
    Column(Modifier.width(420.dp).softShadow(RoundedCornerShape(8.dp), elevation = 18.dp).background(p.flyout, RoundedCornerShape(8.dp)).pointerInput(Unit) { detectTapGestures2 {} }.padding(20.dp)) {
        T(title, size = 20.sp)
        Spacer(Modifier.height(12.dp))
        T(hint, size = 12.sp, color = p.textSecondary)
        PlainField(text, { text = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(if (S.zh) "取消" else "Cancel", primary = false) { dismiss() }
            DialogButton(if (S.zh) "確定" else "OK", primary = true) { if (text.isNotBlank()) submit(text) }
        }
    }
}

/** A Windows file picker (AWT): returns the chosen path or null. */
private fun pickFile(title: String, startDir: String?, save: Boolean = false, name: String = ""): String? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
    startDir?.let { d.directory = it }
    if (name.isNotEmpty()) d.file = name
    else if (!save) d.file = "*.patch;*.diff"
    d.isVisible = true
    return d.file?.let { File(d.directory, it).path }
}

@Composable
private fun CheckRow(on: Boolean, label: String, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(18.dp), tint = if (on) p.accent else p.textSecondary)
        Spacer(Modifier.width(8.dp))
        T(label, size = 13.sp)
    }
}

/** Command output, selectable, in a fixed-width font. */
@Composable
private fun OutputBox(text: String, ok: Boolean?) {
    val p = LocalPalette.current
    val border = when (ok) { true -> Color(0xFF2E9E6B); false -> p.danger; null -> p.controlStroke }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 260.dp).background(if (p.dark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA), RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp)).verticalScroll(rememberScrollState()).padding(10.dp)
    ) {
        SelectionContainer { T(text, size = 12.sp, maxLines = Int.MAX_VALUE, fontFamily = FontFamily.Monospace) }
    }
}

/**
 * 套用修補檔: `git apply --check` (試套用, changes nothing) and `git apply` in the repository
 * of the current folder, with the full output shown.
 */
@Composable
fun GitApplyPatchDialog(s: AppState, initial: String) {
    val p = LocalPalette.current
    val g = s.tab.git
    val scope = rememberCoroutineScope()
    var patch by remember { mutableStateOf(initial) }
    var threeWay by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    val close = { s.gitApplyPatchFor = null }
    GitDialogFrame(S.gitApplyPatch.trimEnd('…'), 620.dp, close) {
        if (g == null) { T(if (S.zh) "此資料夾不在 Git 儲存庫中" else "This folder isn't in a Git repository", color = p.textSecondary); return@GitDialogFrame }
        T("${S.gitRepo}: ${g.root}  (${g.branch})", size = 12.sp, color = p.textSecondary)
        Spacer(Modifier.height(10.dp))
        T(S.gitPatchFile, size = 12.sp, color = p.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlainField(patch, { patch = it; ok = null }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            DialogButton(S.gitBrowse, primary = false) {
                pickFile(S.gitPatchFile, if (s.tab.isVirtual) g.root else s.tab.location)?.let { patch = it; ok = null; output = "" }
            }
        }
        Spacer(Modifier.height(6.dp))
        CheckRow(threeWay, S.gitThreeWay) { threeWay = !threeWay }
        Spacer(Modifier.height(10.dp))
        fun run(check: Boolean) {
            val f = File(patch.trim().trim('"'))
            if (!f.isFile) { ok = false; output = if (S.zh) "找不到修補檔：${f.path}" else "Patch file not found: ${f.path}"; return }
            running = true; output = if (S.zh) "執行中…" else "Running…"; ok = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { Git.applyPatch(g.root, f.path, check, threeWay) }
                running = false
                ok = r.ok
                val head = if (r.ok) (if (check) S.gitDryRunOk else S.gitApplied) else if (S.zh) "失敗：" else "Failed:"
                output = head + if (r.output.isNotBlank()) "\n\n" + r.output else ""
                if (r.ok && !check) s.afterGitChange(g.root)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(S.gitDryRun, primary = false) { if (!running) run(check = true) }
            DialogButton(S.gitApply, primary = true) { if (!running) run(check = false) }
            Spacer(Modifier.weight(1f))
            DialogButton(S.close, primary = false) { close() }
        }
        if (output.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutputBox(output, ok)
        }
    }
}

/**
 * 從提交紀錄建立 .patch: choose one or more commits (click, Shift+click for a range, Ctrl+click
 * to add or remove) and write them with `git format-patch`, oldest first.
 */
@Composable
fun GitCreatePatchDialog(s: AppState) {
    val p = LocalPalette.current
    val g = s.tab.git
    val scope = rememberCoroutineScope()
    var commits by remember { mutableStateOf<List<GitCommit>?>(null) }
    var chosen by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var perCommit by remember { mutableStateOf(false) }
    var outDir by remember { mutableStateOf(if (s.tab.isVirtual) g?.root ?: "" else s.tab.location) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var running by remember { mutableStateOf(false) }
    val close = { s.gitCreatePatchOpen = false }
    LaunchedEffect(g?.root) { commits = g?.let { withContext(Dispatchers.IO) { Git.log(it.root) } } ?: emptyList() }
    GitDialogFrame(S.gitCreatePatch.trimEnd('…'), 760.dp, close) {
        if (g == null) { T(if (S.zh) "此資料夾不在 Git 儲存庫中" else "This folder isn't in a Git repository", color = p.textSecondary); return@GitDialogFrame }
        T("${S.gitRepo}: ${g.root}  (${g.branch})", size = 12.sp, color = p.textSecondary)
        T(S.gitPickCommits, size = 12.sp, color = p.textSecondary, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        val list = commits
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 360.dp).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp))) {
            if (list == null) T(S.loading, Modifier.padding(12.dp), color = p.textSecondary)
            else if (list.isEmpty()) T(if (S.zh) "沒有提交紀錄" else "No commits", Modifier.padding(12.dp), color = p.textSecondary)
            else LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxWidth().padding(2.dp)) {
                itemsIndexed(list, key = { _, c -> c.hash }) { i, c ->
                    val on = i in chosen
                    Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (on) p.selected else Color.Transparent)
                        .pointerInput(i) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Press) continue
                                    val mods: PointerKeyboardModifiers = e.keyboardModifiers
                                    val a = anchor
                                    chosen = when {
                                        mods.isShiftPressed && a != null -> (minOf(a, i)..maxOf(a, i)).toSet()
                                        mods.isCtrlPressed -> if (on) chosen - i else chosen + i
                                        else -> if (on && chosen.size == 1) emptySet() else setOf(i)
                                    }
                                    if (!mods.isShiftPressed) anchor = i
                                    result = null
                                }
                            }
                        }) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(16.dp), tint = if (on) p.accent else p.textTertiary)
                            Spacer(Modifier.width(8.dp))
                            T(c.short, Modifier.width(72.dp), size = 12.sp, color = p.accentText, fontFamily = FontFamily.Monospace)
                            T(c.subject + if (c.isMerge) "  (merge)" else "", Modifier.weight(1f), size = 13.sp)
                            T(c.author, Modifier.width(120.dp).padding(start = 8.dp), size = 12.sp, color = p.textSecondary)
                            T(c.date, Modifier.width(84.dp).padding(start = 8.dp), size = 12.sp, color = p.textSecondary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        T(S.gitChosen(chosen.size), size = 12.sp, color = p.textSecondary)
        Spacer(Modifier.height(10.dp))
        T(S.gitOutputTo, size = 12.sp, color = p.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlainField(outDir, { outDir = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CheckRow(!perCommit, S.gitOneFile) { perCommit = false }
            CheckRow(perCommit, S.gitPerCommit) { perCommit = true }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(S.gitCreate, primary = true) {
                val cs = list ?: return@DialogButton
                if (chosen.isEmpty() || running) return@DialogButton
                // The list is newest first; patches go oldest first so they apply in order.
                val picked = chosen.sortedDescending().map { cs[it] }
                val dir = File(outDir.trim().trim('"'))
                val out = if (perCommit) dir else File(dir, patchName(picked))
                running = true
                scope.launch {
                    val (r, files) = withContext(Dispatchers.IO) { Git.formatPatch(g.root, picked.map { it.hash }, out, perCommit) }
                    running = false
                    result = if (r.ok) true to (S.gitPatchWritten(files.size) + "\n" + files.joinToString("\n") { it.path })
                        else false to ((if (S.zh) "失敗：" else "Failed:") + "\n" + r.output)
                    if (r.ok) s.allPanes().filter { it.location.equals(dir.path, true) }.forEach { s.load(it, keepSelection = true) }
                }
            }
            Spacer(Modifier.weight(1f))
            DialogButton(S.close, primary = false) { close() }
        }
        result?.let { (ok, text) -> Spacer(Modifier.height(12.dp)); OutputBox(text, ok) }
    }
}

/** `abc1234-fix-login.patch` for one commit, `abc1234..def5678.patch` for several (oldest..newest). */
private fun patchName(oldestFirst: List<GitCommit>): String {
    if (oldestFirst.size == 1) {
        val c = oldestFirst[0]
        val slug = c.subject.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(48)
        return "${c.short}${if (slug.isNotEmpty()) "-$slug" else ""}.patch"
    }
    return "${oldestFirst.first().short}..${oldestFirst.last().short}.patch"
}

@Composable
fun SidebarTree(s: AppState, path: String, label: String, depth: Int = 1, icon: @Composable () -> Unit) {
    val expanded = s.treeExpanded[path] == true
    SidebarRow(label, depth, s.tab.location.equals(path, true), chevron = true, expanded = expanded,
        onToggle = { s.toggleTree(path) },
        menu = { sidebarFolderMenu(s, path, tree = true) },
        onClick = { s.navigate(path) }, icon = icon)
    if (expanded) {
        val children = s.treeChildren[path]
        if (children == null) T(S.loading, Modifier.padding(start = ((depth + 1) * 14 + 25).dp), size = 12.sp)
        else if (children.isEmpty()) T(if (S.zh) "沒有子資料夾" else "No subfolders", Modifier.padding(start = ((depth + 1) * 14 + 25).dp), size = 12.sp)
        else children.forEach { child ->
            key(child) { SidebarTree(s, child, java.io.File(child).name, depth + 1) { FolderGlyph(M.SIDEBAR_ICON.dp) } }
        }
    }
}
