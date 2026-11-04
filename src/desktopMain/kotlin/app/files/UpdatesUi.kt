package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** One feature on the 更新資訊 page. [key] shows that command's current key; [open] adds a button. */
class Feature(
    val icon: ImageVector,
    private val title: Pair<String, String>,
    private val body: Pair<String, String>,
    val key: String? = null,
    val open: ((AppState) -> Unit)? = null,
) {
    val titleText get() = tr(title.first, title.second)
    val bodyText get() = tr(body.first, body.second)
}

class Release(val version: String, val features: List<Feature>)

/** What each version brought, newest first. Add the next version at the top. */
val releases: List<Release> = listOf(
    Release("1.2.0", listOf(
        Feature(Icons.Outlined.Menu, "Windows' own right-click menu" to "Windows 系統右鍵選單",
            ("Right-click shows the Windows 11 menu's commands (from apps like VS Code, Terminal, PowerToys) under the app's own, and “Show more options” " +
                "opens the full classic menu. If this Windows' new menu can't be read, the classic one is used instead.") to
                ("右鍵選單下方會顯示 Windows 11 新版選單的指令（VS Code、終端機、PowerToys 等 App 加入的），「顯示更多選項」則是完整的舊版選單。" +
                    "讀不到這台 Windows 的新版選單時，會自動改用舊版選單。"),
            open = { it.settingsOpen = true }),
        Feature(Icons.Outlined.Wallpaper, "Background pictures: combine when they change" to "背景圖片：切換時機可以同時選",
            ("Change at each start, on a timer and when the window comes back from being minimized — any of them together. " +
                "Random order now shows every picture once before repeating.") to
                ("每次開啟時、定時、以及視窗從最小化還原時，可以任意同時開啟；隨機順序會讓每張圖片都輪過一次才重複。")),
        Feature(Icons.Outlined.BugReport, "Error reports, kept on your computer" to "錯誤報告（只存在你的電腦）",
            ("Errors are recorded with what led to them, so problems can be found and fixed. Nothing is uploaded: " +
                "Settings › Error reports shows them, and can export a ZIP for you to share.") to
                ("發生錯誤時會記錄錯誤內容與之前的操作，方便找出問題；不會上傳。可在「設定 › 錯誤報告」查看，並匯出 ZIP 自行提供。"),
            open = { it.settingsSectionRequest = "Errors"; it.settingsOpen = true }),
        Feature(Icons.Outlined.DataObject, "Shortcuts: tokens and variables as you type" to "捷徑：輸入時提示標記與變數",
            ("Type { in a step's text to pick from the variables earlier steps set, the parameters and every token, each explained. " +
                "Unknown variables, parameters or tokens, and a missing }, are pointed out under the field.") to
                ("在步驟的文字欄輸入 {，就會列出前面步驟設定的變數、參數與所有標記，並附說明；用了不存在的變數、參數或標記，或少了 }，欄位下方會直接提醒。"),
            key = "OpenFlows", open = { it.flowsOpen = true }),
        Feature(Icons.Outlined.Fingerprint, "Shortcuts: comparing hashes works" to "捷徑：雜湊值比對修正",
            ("“Hash” now gives the hash alone by default (choose “hash+name” for the old list), and “equals” ignores spaces " +
                "and line breaks around the text, such as the one a copied hash ends with.") to
                ("「計算雜湊值」預設只輸出雜湊值（要附檔名請選 hash+name）；「相等」比對會忽略前後的空白與換行（例如複製來的雜湊值結尾的換行）。")),
    )),
    Release("1.1.0", listOf(
        Feature(Icons.Outlined.Keyboard, "Your own keyboard shortcuts" to "自訂鍵盤快速鍵",
            ("Give any command one key, several, or none. Keys given to two commands are marked, with a one-click fix. " +
                "Settings › Keyboard shortcuts.") to ("每個指令都能指定一個或多個按鍵，也可以清除；同一個按鍵給了兩個指令會標出來，一鍵就能修正。位置：設定 › 鍵盤快速鍵。"),
            open = { it.openKeymap() }),
        Feature(Icons.Outlined.NewReleases, "This page: what an update changed" to "更新報告（就是這一頁）",
            ("After an update, Files backs up your settings, carries your keys and settings over, and shows here what changed: " +
                "key conflicts, commands split or renamed, and new defaults.") to
                ("更新後第一次開啟時，Files 會先備份設定，再把你的按鍵與設定搬到新版本，並在這裡列出變動：按鍵衝突、被拆分或改名的指令、預設值的變更。")),
        Feature(Icons.Outlined.SwapHoriz, "Shortcuts: clear inputs and outputs" to "捷徑：清楚的輸入與輸出",
            ("Every step shows what it takes and what it gives, and warns when they don't fit. Choose where a step's input comes from, and keep a result in a variable.") to
                ("每個步驟都標出收到什麼、產生什麼，接不上時會警告；可以選擇步驟的輸入來源，也能把結果存成變數。"),
            key = "OpenFlows", open = { it.flowsOpen = true }),
        Feature(Icons.Outlined.Bolt, "Shortcuts: 60 new actions" to "捷徑：60 多個新動作",
            ("Web requests, run another shortcut, sounds and speech, waits you can skip with any key, lists and dictionaries, JSON, base64, " +
                "dates, text search and split, and a debug view.") to
                ("HTTP 呼叫、呼叫其他捷徑、播放聲音與朗讀、可按任意鍵略過的等待、清單與字典、JSON、base64、日期時間、文字搜尋與分割、除錯顯示。")),
        Feature(Icons.Outlined.Apps, "Shortcuts: your computer and apps" to "捷徑：系統與 App",
            ("Volume, screenshots, battery and Do not disturb; find and start apps and read their command-line options; " +
                "send email and text messages with attachments.") to
                ("音量、截圖、電量、勿擾；尋找並啟動 App、讀取它的命令列選項；寄送附檔案的 Email 與簡訊。")),
    )),
)

/** An introduction to Files: shown first on a new install, and at the end of every 更新資訊 page. */
val introduction: List<Feature> = listOf(
    Feature(Icons.Outlined.Tab, "Tabs and split view" to "分頁與分割畫面",
        "Open folders in tabs, and show two folders side by side to move files between them." to "用分頁開多個資料夾，或左右分割同時看兩個資料夾，方便搬移檔案。", key = "NewTab"),
    Feature(Icons.Outlined.Preview, "Quick preview" to "快速預覽",
        "Select a file and press the key to look inside without opening it; arrows go to the next file." to "選取檔案後按下按鍵即可預覽內容，不必開啟；方向鍵切換到下一個。", key = "QuickLook"),
    Feature(Icons.Outlined.Search, "Instant search" to "即時搜尋",
        "Search names as you type, with filters like ext:pdf, size:>10MB or modified:today." to "邊打字邊搜尋，支援 ext:pdf、size:>10MB、modified:today 等條件。", key = "Search"),
    Feature(Icons.Outlined.LocalOffer, "Tags" to "標籤",
        "Colour-tag files and folders and find them all again from the sidebar." to "替檔案與資料夾加上顏色標籤，之後從側邊欄一次找回。"),
    Feature(Icons.Outlined.DriveFileRenameOutline, "Batch rename" to "批次重新命名",
        "Rename many files at once with a live preview." to "一次重新命名多個檔案，即時預覽結果。", key = "BatchRename"),
    Feature(Icons.Outlined.Delete, "Recycle Bin and safe delete" to "資源回收筒與安全刪除",
        "Restore what you deleted; deleting never follows links out of a folder." to "可還原刪除的項目；刪除時不會順著連結刪到資料夾外。", key = "OpenRecycleBin"),
    Feature(Icons.Outlined.Bolt, "Shortcuts (automation)" to "捷徑（自動化）",
        "Chain steps into your own commands: sort photos, send files, call web APIs, and more." to "把步驟串成自己的指令：整理相片、寄送檔案、呼叫網路 API 等。",
        key = "OpenFlows", open = { it.flowsOpen = true }),
    Feature(Icons.Outlined.Storage, "Storage and clean-up" to "儲存空間與清理",
        "See what fills your disk and clean caches safely, with a preview first." to "看看磁碟被什麼佔滿，先預覽再安全清理快取。"),
    Feature(Icons.Outlined.Keyboard, "Keyboard shortcuts" to "鍵盤快速鍵",
        "See every key, and change any of them to suit you." to "查看所有快速鍵，並依習慣修改。", key = "ShowShortcuts", open = { it.openKeymap() }),
)

/**
 * 更新資訊 (a tab): what the last update changed for this person — conflicts to fix (live), key and
 * settings changes, the new features — and an introduction. On a fresh install it's a welcome page.
 */
@Composable
fun UpdatesPage(s: AppState) {
    val p = LocalPalette.current
    val report = s.updateReport ?: remember { Updates.last() }
    @Suppress("UNUSED_VARIABLE") val watch = Keymap.overrides
    val fresh = report?.fresh == true
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(p.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (fresh) Icons.Outlined.WavingHand else Icons.Outlined.NewReleases, null, Modifier.size(26.dp), tint = p.accentText)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    T(when {
                        fresh -> tr("Welcome to Files", "歡迎使用 Files")
                        report != null -> tr("Files was updated to ${report.to}", "Files 已更新到 ${report.to}")
                        else -> "Files ${AppInfo.version}"
                    }, size = 24.sp, weight = FontWeight.SemiBold)
                    T(when {
                        fresh -> tr("A quick tour of what Files can do.", "快速認識 Files 能做什麼。")
                        report != null -> tr("From ${report.from} · ${report.at}", "從 ${report.from} 更新 · ${report.at}")
                        else -> tr("What's new and an introduction.", "更新資訊與功能介紹。")
                    }, size = 13.sp, color = p.textSecondary)
                }
            }
            if (fresh) {
                IntroGrid(s, introduction)
                return@Column
            }
            if (report != null) {
                SectionTitle(Icons.Outlined.ReportProblem, tr("Needs your attention", "需要你處理"))
                ConflictsSection(s, report)
                val keys = report.items.filter { it.kind.isKeys }
                val settings = report.items.filter { it.kind in setOf(ChangeKind.Migrated, ChangeKind.SettingChanged, ChangeKind.SettingKept) }
                SectionTitle(Icons.Outlined.Keyboard, tr("Keyboard shortcut changes", "快速鍵變更"))
                if (keys.isEmpty()) Muted(tr("This update didn't change any of your keys.", "這次更新沒有改動你的快速鍵。"))
                keys.forEach { ChangeCard(s, it) }
                SectionTitle(Icons.Outlined.Settings, tr("Settings changes", "設定變更"))
                if (settings.isEmpty()) Muted(tr("Your settings carried over unchanged.", "你的設定原封不動地沿用。"))
                settings.forEach { ChangeCard(s, it) }
                BackupCard(s, report)
            }
            val newer = releases.filter { r -> report?.from == null || AppInfo.compare(r.version, report.from) > 0 }.ifEmpty { releases.take(1) }
            newer.forEach { r ->
                SectionTitle(Icons.Outlined.AutoAwesome, tr("New in ${r.version}", "${r.version} 新功能"))
                IntroGrid(s, r.features)
            }
            SectionTitle(Icons.Outlined.Explore, tr("Getting to know Files", "認識 Files"))
            IntroGrid(s, introduction)
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = p.accentText); Spacer(Modifier.width(8.dp))
        T(text, size = 16.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Muted(text: String) = T(text, Modifier.padding(start = 26.dp), size = 13.sp, color = LocalPalette.current.textSecondary, maxLines = 3)

@Composable
private fun Card(border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.card).border(1.dp, border ?: p.cardStroke, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    T(text, Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp), size = 11.sp, color = color)
}

/** Keys given to two commands, as they are now (fixing one in the editor removes it here). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictsSection(s: AppState, report: UpdateReport) {
    val p = LocalPalette.current
    val conflicts = Keymap.conflicts()
    if (conflicts.isEmpty()) {
        Row(Modifier.padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = Color(0xFF0F7B0F)); Spacer(Modifier.width(6.dp))
            T(tr("No key conflicts. Every key does one thing.", "沒有按鍵衝突，每個按鍵都只做一件事。"), size = 13.sp, color = p.textSecondary)
        }
        return
    }
    conflicts.forEach { c ->
        Card(border = p.danger.copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyCap(c.key, conflict = true)
                T(tr("is given to ${c.actions.size} commands", "同時指定給 ${c.actions.size} 個指令"), Modifier.weight(1f), size = 13.sp, weight = FontWeight.SemiBold)
                if (c.key in report.newConflicts) Badge(tr("Since this update", "這次更新造成"), p.danger)
                DialogButton(tr("Fix", "調整"), primary = true) { s.openKeymap(c.key) }
            }
            c.actions.forEachIndexed { i, a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${a.category} › ${a.label}", Modifier.weight(1f), size = 13.sp, color = if (i == 0) p.text else p.textSecondary)
                    if (i == 0) Badge(tr("Runs now", "目前執行"), p.accentText)
                    else LinkText(tr("Remove key from this", "移除這個指令的按鍵")) { Keymap.remove(a, c.key) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeCard(s: AppState, item: ReportItem) {
    val p = LocalPalette.current
    val (icon, color) = when (item.kind) {
        ChangeKind.Split -> Icons.Outlined.CallSplit to Color(0xFF8764B8)
        ChangeKind.Renamed -> Icons.Outlined.DriveFileRenameOutline to p.accentText
        ChangeKind.Removed -> Icons.Outlined.RemoveCircleOutline to p.textSecondary
        ChangeKind.DefaultChanged, ChangeKind.SettingChanged -> Icons.Outlined.SwapHoriz to Color(0xFFCA5010)
        ChangeKind.DefaultKept, ChangeKind.SettingKept -> Icons.Outlined.PushPin to Color(0xFF0F7B0F)
        ChangeKind.NewKey -> Icons.Outlined.AddCircleOutline to p.accentText
        ChangeKind.Migrated -> Icons.Outlined.MoveDown to p.accentText
        ChangeKind.Conflict -> Icons.Outlined.Warning to p.danger
    }
    Card {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.padding(top = 2.dp).size(18.dp), tint = color); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(item.kind.text, color)
                    T(item.title, Modifier.weight(1f), size = 13.sp, weight = FontWeight.SemiBold, maxLines = 2)
                }
                if (item.detail.isNotEmpty()) T(item.detail, size = 12.sp, color = p.textSecondary, maxLines = 4)
                if (item.keys.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { item.keys.forEach { KeyCap(it) } }
            }
            if (item.kind.isKeys && item.target != null && item.kind != ChangeKind.Removed) {
                Spacer(Modifier.width(8.dp))
                val label = Keymap.actions().firstOrNull { it.id == item.target }?.label ?: item.target
                DialogButton(tr("Adjust", "調整"), primary = false) { s.openKeymap(label) }
            }
        }
    }
}

/** Where the backup is, and putting it back. */
@Composable
private fun BackupCard(s: AppState, report: UpdateReport) {
    val p = LocalPalette.current
    val dir = report.backup?.let { File(it) }?.takeIf { it.isDirectory } ?: return
    var confirm by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf<File?>(null) }
    SectionTitle(Icons.Outlined.Backup, tr("Backup", "備份"))
    Card {
        T(tr("Before updating, your settings were copied to:", "更新前，你的設定已備份到："), size = 13.sp)
        T(dir.path, size = 12.sp, color = p.textSecondary, maxLines = 2)
        if (restored != null) {
            T(tr("Restored. Restart Files to use these settings (the ones from before the restore are in ${restored!!.name}).",
                "已還原。請重新啟動 Files 讓設定生效（還原前的設定另存於 ${restored!!.name}）。"), size = 13.sp, color = Color(0xFF0F7B0F), maxLines = 3)
            DialogButton(tr("Restart now", "立即重新啟動"), primary = true) { Updates.restart() }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(tr("Open backup folder", "開啟備份資料夾"), primary = false) { s.newTab(dir.path) }
            DialogButton(if (confirm) tr("Put back the old settings? Click again", "確定還原舊設定？再按一次") else tr("Restore settings from before the update", "還原更新前的設定"), primary = confirm) {
                if (!confirm) confirm = true
                else { restored = SettingsBackup.restore(dir); if (restored == null) s.toast = tr("Couldn't restore the backup", "無法還原備份") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntroGrid(s: AppState, features: List<Feature>) {
    val p = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val watch = Keymap.overrides
    val acts = remember(Keymap.overrides) { Keymap.actions().associateBy { it.id } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth > 620.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            features.chunked(cols).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { f ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(8.dp)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(30.dp).background(p.accent.copy(alpha = 0.13f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    Icon(f.icon, null, Modifier.size(17.dp), tint = p.accentText)
                                }
                                Spacer(Modifier.width(10.dp))
                                T(f.titleText, Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold, maxLines = 2)
                            }
                            T(f.bodyText, size = 12.sp, color = p.textSecondary, maxLines = 5)
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                f.key?.let { id -> acts[id]?.let { a -> Keymap.bindings(a).take(2).forEach { KeyCap(it) } } }
                                Spacer(Modifier.weight(1f))
                                f.open?.let { open -> LinkText(tr("Open", "開啟") + " ›") { open(s) } }
                            }
                        }
                    }
                    if (row.size < cols) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
