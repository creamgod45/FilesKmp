package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GitMenu(s: AppState) {
    var open by remember { mutableStateOf(false) }
    var branchDialog by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Outlined.AccountTree, "Git", { open = true })
        Flyout(open, { open = false }) {
            val g = s.tab.git
            val ready = s.gitBusy == null
            if (g == null) {
                FlyoutItem(if (S.zh) "初始化 Git 儲存庫" else "Initialize repository", Icons.Outlined.Add, "", ready) { open = false; s.gitInit() }
            } else {
                T("${g.branch}  ↑${g.ahead} ↓${g.behind}", Modifier.padding(10.dp), size = 13.sp)
                FlyoutItem(if (S.zh) "取得遠端變更" else "Fetch", Icons.Outlined.CloudDownload, "", ready) { open = false; s.gitFetch() }
                FlyoutItem(if (S.zh) "拉取" else "Pull", Icons.Outlined.ArrowDownward, "", ready) { open = false; s.gitPull() }
                FlyoutItem(if (S.zh) "推送" else "Push", Icons.Outlined.ArrowUpward, "", ready) { open = false; s.gitPush() }
                FlyoutItem(if (S.zh) "同步" else "Sync", Icons.Outlined.Sync, "", ready) { open = false; s.gitSync() }
                FlyoutSeparator()
                FlyoutItem(if (S.zh) "建立分支…" else "Create branch…", Icons.Outlined.Add, "", ready) { open = false; branchDialog = true }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    g.localBranches.forEach { branch ->
                        FlyoutItem(branch, if (branch == g.branch) Icons.Outlined.Check else Icons.Outlined.AccountTree, "", ready && branch != g.branch) {
                            open = false; s.gitCheckout(branch)
                        }
                    }
                    g.remoteBranches.forEach { branch ->
                        FlyoutItem(branch, Icons.Outlined.Cloud, "", ready) { open = false; s.gitCheckout(branch, remote = true) }
                    }
                }
            }
            FlyoutSeparator()
            FlyoutItem(if (S.zh) "複製儲存庫到此資料夾…" else "Clone repository here…", Icons.Outlined.ContentCopy, "", ready) { open = false; s.cloneDialogOpen = true }
        }
    }
    if (branchDialog) androidx.compose.ui.window.Dialog(onDismissRequest = { branchDialog = false }) {
        GitInput(if (S.zh) "建立分支" else "Create branch", if (S.zh) "分支名稱" else "Branch name", { branchDialog = false }) {
            s.gitCreateBranch(it); branchDialog = false
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
    Column(Modifier.width(420.dp).background(p.flyout, RoundedCornerShape(8.dp)).pointerInput(Unit) { detectTapGestures2 {} }.padding(20.dp)) {
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

@Composable
fun SidebarTree(s: AppState, path: String, label: String, depth: Int = 1, icon: @Composable () -> Unit) {
    val expanded = s.treeExpanded[path] == true
    SidebarRow(label, depth, s.tab.location.equals(path, true), chevron = true, expanded = expanded,
        onToggle = { s.toggleTree(path) },
        menu = { listOf(MenuEntry(S.openInNewTab, Icons.Outlined.Tab) { s.newTab(path) }) },
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
