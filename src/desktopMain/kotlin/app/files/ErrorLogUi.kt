package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 設定 › 錯誤報告: what was recorded (kept on this computer only), grouped by error, with the
 * details, copy, export to a ZIP you can share yourself, and clear.
 */
@Composable
fun ErrorReportsSettings(s: AppState) {
    val p = LocalPalette.current
    var reports by remember { mutableStateOf(ErrorLog.reports()) }
    var on by remember { mutableStateOf(ErrorLog.enabled) }
    var trail by remember { mutableStateOf(ErrorLog.keepTrail) }
    var hide by remember { mutableStateOf(Prefs.bool("errorLogHideUser", true)) }
    var confirmClear by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    fun export(list: List<ErrorReport>) {
        val downloads = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
        runCatching { ErrorLog.export(list, downloads, hide) }
            .onSuccess { s.toast = tr("Saved ${it.name} in Downloads", "已存到「下載」：${it.name}") }
            .onFailure { s.toast = tr("Couldn't export: ${it.message}", "無法匯出：${it.message}") }
    }

    Row(Modifier.fillMaxWidth().background(p.accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = p.accentText); Spacer(Modifier.width(10.dp))
        T(tr("Errors are recorded on this computer only and never sent anywhere. To report a problem, export them and share the ZIP yourself.",
            "錯誤只會記錄在這台電腦上，不會上傳到任何地方。要回報問題時，可以匯出成 ZIP 自行提供。") + "\n" + ErrorLog.dir.path,
            size = 12.sp, maxLines = 4)
    }
    ToggleCard(tr("Record errors", "記錄錯誤"), on) { on = it; ErrorLog.enabled = it; Prefs.put(mapOf("errorLog" to it)) }
    ToggleCard(tr("Include the last 50 actions (folders opened, keys pressed) to help find the cause", "一併記錄最近 50 個操作（開啟的資料夾、按下的按鍵），方便找出原因"), trail) {
        trail = it; ErrorLog.keepTrail = it; Prefs.put(mapOf("errorLogTrail" to it))
    }
    ToggleCard(tr("Hide my user name and user folder when copying or exporting", "複製或匯出時隱藏使用者名稱與個人資料夾路徑"), hide) {
        hide = it; Prefs.put(mapOf("errorLogHideUser" to it))
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DialogButton(tr("Refresh", "重新整理"), primary = false) { reports = ErrorLog.reports() }
        DialogButton(tr("Open folder", "開啟資料夾"), primary = false) { ErrorLog.dir.mkdirs(); s.settingsOpen = false; s.newTab(ErrorLog.dir.path) }
        DialogButton(tr("Export all", "全部匯出"), primary = false) { if (reports.isNotEmpty()) export(reports) }
        DialogButton(if (confirmClear) tr("Delete all? Click again", "確定全部刪除？再按一次") else tr("Clear", "清除"), primary = confirmClear) {
            if (confirmClear) { ErrorLog.clear(); reports = emptyList(); confirmClear = false } else confirmClear = true
        }
    }
    val groups = reports.groupBy { it.signature }.values.sortedByDescending { it.first().id }
    GroupTitle(if (reports.isEmpty()) tr("No errors recorded", "目前沒有錯誤紀錄")
        else tr("${reports.size} reports · ${groups.size} different errors", "${reports.size} 筆紀錄 · ${groups.size} 種錯誤"))
    groups.forEach { g ->
        val last = g.first()
        val expanded = open == last.signature
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, if (last.fatal) p.danger.copy(alpha = 0.5f) else p.cardStroke, RoundedCornerShape(6.dp))
        ) {
            Row(Modifier.fillMaxWidth().clickable { open = if (expanded) null else last.signature }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (last.fatal) Icons.Outlined.ErrorOutline else Icons.Outlined.BugReport, null, Modifier.size(18.dp), tint = if (last.fatal) p.danger else p.textSecondary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(last.type.substringAfterLast('.') + (if (last.message.isNotBlank()) ": " + last.message.lines().first() else ""), size = 13.sp, weight = FontWeight.SemiBold)
                    T("${last.source} · ${last.time} · ${last.version}" + if (last.fatal) tr(" · app ended", " · 程式因此結束") else "", size = 11.sp, color = p.textSecondary)
                }
                if (g.size > 1) T("×${g.size}", Modifier.background(p.control, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp), size = 11.sp)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp), tint = p.textSecondary)
            }
            if (expanded) {
                val text = if (hide) ErrorLog.redact(last.text()) else last.text()
                Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(max = 280.dp).background(if (p.dark) Color(0xFF1E1E1E) else Color(0xFFF6F6F6), RoundedCornerShape(4.dp))
                    .verticalScroll(rememberScrollState()).padding(10.dp)) {
                    SelectionContainer { T(text, size = 11.sp, maxLines = Int.MAX_VALUE, fontFamily = FontFamily.Monospace) }
                }
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(tr("Copy", "複製"), primary = false) { copyText(text); s.toast = tr("Copied", "已複製") }
                    DialogButton(tr("Export this error", "匯出這個錯誤") + if (g.size > 1) " (${g.size})" else "", primary = false) { export(g) }
                }
            }
        }
    }
}
