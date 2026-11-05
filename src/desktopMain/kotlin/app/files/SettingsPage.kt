package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class SettingsSection(val label: () -> String, val icon: ImageVector) {
    General({ S.general }, Icons.Outlined.Home),
    Appearance({ S.appearance }, Icons.Outlined.Palette),
    Layout({ S.layoutSettings }, Icons.Outlined.ViewQuilt),
    Folders({ S.filesFolders }, Icons.Outlined.Folder),
    Tags({ S.tagsSettings }, Icons.Outlined.LocalOffer),
    Storage({ S.storage }, Icons.Outlined.Storage),
    Search({ S.searchSettings }, Icons.Outlined.Search),
    Shortcuts({ S.shortcuts }, Icons.Outlined.Keyboard),
    Errors({ tr("Error reports", "錯誤報告") }, Icons.Outlined.BugReport),
    About({ S.about }, Icons.Outlined.Info),
}

/** The settings window (a dialog over the app, as in the original). Every change applies and saves at once. */
@Composable
fun SettingsPage(s: AppState) {
    val p = LocalPalette.current
    var section by remember { mutableStateOf(when {
        s.settingsStorageRequest -> SettingsSection.Storage
        else -> SettingsSection.entries.firstOrNull { it.name == s.settingsSectionRequest } ?: SettingsSection.General
    }) }
    LaunchedEffect(Unit) { s.settingsStorageRequest = false; s.settingsSectionRequest = null }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { s.settingsOpen = false } }, contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.86f).widthIn(max = 1000.dp).shadow8()
                .clip(RoundedCornerShape(8.dp)).background(p.solid).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures2 { } }
        ) {
            Column(Modifier.width(240.dp).fillMaxHeight().padding(12.dp)) {
                T(S.settings, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp), size = 20.sp, weight = FontWeight.SemiBold)
                SettingsSection.entries.forEach { sec ->
                    SidebarRow(sec.label(), 0, section == sec, onClick = { section = sec }) {
                        Icon(sec.icon, null, Modifier.size(16.dp), tint = p.text)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(if (p.dark) Color(0xFF272727) else Color(0xFFF9F9F9)).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(section.label(), Modifier.weight(1f), size = 24.sp, weight = FontWeight.SemiBold)
                    IconBtn(Icons.Outlined.Close, S.done, { s.settingsOpen = false })
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (section) {
                        SettingsSection.General -> {
                            ToggleCard(S.continueSession, s.continueSession) { s.continueSession = it }
                            ToggleCard(S.openFoldersNewTab, s.openFoldersInNewTab) { s.openFoldersInNewTab = it }
                            GroupTitle(S.homeWidgets)
                            ToggleCard(S.quickAccess, s.showQuickAccessWidget) { s.showQuickAccessWidget = it }
                            ToggleCard(S.drives, s.showDrivesWidget) { s.showDrivesWidget = it }
                            ToggleCard(S.networkLocations, s.showNetworkWidget) { s.showNetworkWidget = it }
                            ToggleCard(S.tags, s.showTagsWidget) { s.showTagsWidget = it }
                            ToggleCard(S.recentFiles, s.showRecentWidget) { s.showRecentWidget = it }
                            ToggleCard(S.storageAnalysis, s.showStorageWidget) { s.showStorageWidget = it }
                        }
                        SettingsSection.Appearance -> {
                            ChoiceCard(S.theme, listOf(ThemeMode.System to S.themeSystem, ThemeMode.Light to S.themeLight, ThemeMode.Dark to S.themeDark), s.theme) { s.theme = it }
                            ChoiceCard(S.backdropMaterial, Backdrop.entries.map { it to it.text }, s.backdrop) { s.backdrop = it }
                            ChoiceCard(S.cardShadow, ShadowLevel.entries.map { it to it.text }, s.cardShadow) { s.cardShadow = it }
                            GroupTitle(S.translucentWindow)
                            TranslucentSettings(s)
                            GroupTitle(S.gradientBackground)
                            GradientSettings(s)
                            GroupTitle(S.backgroundColor)
                            TintGrid(s)
                            GroupTitle(S.backgroundImage)
                            BackgroundPictures(s)
                            if (s.backgroundImage.isNotBlank() || s.bgImages.isNotEmpty() || s.bgRules.isNotEmpty()) {
                                ChoiceCard(S.imageFit, ImageFit.entries.map { it to it.text }, s.backgroundImageFit) { s.backgroundImageFit = it }
                                ChoiceCard(S.imageAlign, ImageAlign.entries.map { it to it.text }, s.backgroundImageAlign) { s.backgroundImageAlign = it }
                                SettingCard(S.imageOpacity) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconBtn(Icons.Outlined.Remove, "-", { s.backgroundImageOpacity = ((s.backgroundImageOpacity * 10).toInt() - 1).coerceIn(0, 10) / 10f })
                                        T("${(s.backgroundImageOpacity * 100).toInt()}%", Modifier.width(48.dp))
                                        IconBtn(Icons.Outlined.Add, "+", { s.backgroundImageOpacity = ((s.backgroundImageOpacity * 10).toInt() + 1).coerceIn(0, 10) / 10f })
                                    }
                                }
                            }
                            GroupTitle(S.general)
                            ToggleCard(S.showToolbar, s.showToolbar) { s.showToolbar = it }
                            SettingCard(S.customizeToolbarButtons) {
                                DialogButton(S.customizeToolbar, primary = false) { s.settingsOpen = false; s.customizeToolbarOpen = true }
                            }
                            ToggleCard(S.showStatusBar, s.showStatusBar) { s.showStatusBar = it }
                            SettingCard(S.sidebarWidth) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconBtn(Icons.Outlined.Remove, "-", { s.sidebarWidth = (s.sidebarWidth - 15).coerceIn(M.SIDEBAR_MIN, M.SIDEBAR_MAX) })
                                    T("${s.sidebarWidth}", Modifier.width(40.dp))
                                    IconBtn(Icons.Outlined.Add, "+", { s.sidebarWidth = (s.sidebarWidth + 15).coerceIn(M.SIDEBAR_MIN, M.SIDEBAR_MAX) })
                                }
                            }
                        }
                        SettingsSection.Layout -> {
                            ChoiceCard(S.defaultLayout, LayoutMode.entries.map { it to it.text }, s.layout) { s.layout = it }
                            ChoiceCard(S.sortItemsBy, SortBy.entries.map { it to it.text }, s.sortBy) { s.sortBy = it }
                            ChoiceCard(S.sort, listOf(true to S.ascending, false to S.descending), s.sortAscending) { s.sortAscending = it }
                            ChoiceCard(S.paneArrangement, listOf(PaneArrangement.Vertical to S.arrangeVertical, PaneArrangement.Horizontal to S.arrangeHorizontal), s.defaultArrangement) { s.defaultArrangement = it }
                        }
                        SettingsSection.Folders -> {
                            ToggleCard(S.showHidden, s.showHidden) { s.showHidden = it }
                            ToggleCard(S.showSystem, s.showSystem) { s.showSystem = it }
                            ToggleCard(S.fileExtensions, s.showExtensions) { s.showExtensions = it }
                            ToggleCard(S.showThumbnails, s.showThumbnails) { s.showThumbnails = it }
                            ToggleCard(S.calcFolderSizes, s.calculateFolderSizes) { s.calculateFolderSizes = it }
                            ToggleCard(S.showCheckboxes, s.showCheckboxes) { s.showCheckboxes = it }
                            ToggleCard(S.confirmDelete, s.confirmDeleteAlways) { s.confirmDeleteAlways = it }
                            ShellMenuSettings(s)
                        }
                        SettingsSection.Tags -> TagSettings(s)
                        SettingsSection.Storage -> StorageSettings(s)
                        SettingsSection.Shortcuts -> KeymapEditor(s)
                        SettingsSection.Errors -> ErrorReportsSettings(s)
                        SettingsSection.Search -> SearchSettings(s)
                        SettingsSection.About -> {
                            SettingCard("FilesKmp ${AppInfo.version}") {
                                DialogButton(tr("What's new", "更新資訊與介紹"), primary = false) { s.settingsOpen = false; s.openUpdates() }
                            }
                            SettingCard(tr("Settings backups (made before each update)", "設定備份（每次更新前自動建立）") + " · ${SettingsBackup.list().size}") {
                                DialogButton(tr("Open folder", "開啟資料夾"), primary = false) { s.settingsOpen = false; SettingsBackup.root.mkdirs(); s.newTab(SettingsBackup.root.path) }
                            }
                            T(S.aboutText, Modifier.padding(8.dp), color = p.textSecondary, maxLines = 6)
                            T("Settings: ${Prefs.dir.path}", Modifier.padding(8.dp), size = 12.sp, color = p.textTertiary, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** 搜尋: the instant-search index (folders, status, rebuild) and the query syntax. */
@Composable
private fun SearchSettings(s: AppState) {
    val p = LocalPalette.current
    ToggleCard(S.searchRegexMode, s.searchRegex) { s.searchRegex = it }
    ToggleCard(S.searchCaseMode, s.searchCase) { s.searchCase = it }
    GroupTitle(S.searchIndex)
    T(S.searchIndexHint, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), size = 12.sp, color = p.textSecondary, maxLines = 4)
    s.indexRoots.toList().forEach { root ->
        SettingCard(root) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val st = s.indexStatus.entries.firstOrNull { it.key.equals(root, true) }?.value
                T(st?.let { S.indexState(it.first, it.second) } ?: S.loading, size = 12.sp, color = p.textSecondary)
                DialogButton(S.rebuild, primary = false) { s.rebuildIndex(root) }
                DialogButton(S.remove, primary = false) { s.removeIndexRoot(root) }
            }
        }
    }
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DialogButton(S.addIndexFolder, primary = true) { pickFolder()?.let { s.addIndexRoot(it) } }
    }
    GroupTitle(S.searchSyntax)
    T(S.searchSyntaxHelp, Modifier.padding(8.dp), size = 12.sp, color = p.textSecondary, maxLines = 8)
}

/** 半透明視窗: on/off (applies at the next start), how opaque, and blur of what is behind. */
@Composable
private fun TranslucentSettings(s: AppState) {
    val p = LocalPalette.current
    ToggleCard(S.translucentWindow, s.translucentWindow) { s.translucentWindow = it }
    T(if (s.translucentWindow != s.translucentActive) S.restartNeeded else S.translucentHint,
        Modifier.padding(start = 8.dp, bottom = 4.dp), size = 12.sp,
        color = if (s.translucentWindow != s.translucentActive) p.accentText else p.textSecondary, maxLines = 2)
    if (s.translucentWindow) {
        SettingCard(S.windowOpacity) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FluentSlider(s.windowOpacity, { s.windowOpacity = it }, range = 0.3f..1f, steps = 14, width = 200.dp)
                T("${(s.windowOpacity * 100).toInt()}%", Modifier.width(48.dp).padding(start = 8.dp))
            }
        }
        ToggleCard(S.blurBehind, s.blurBehind) { s.blurBehind = it; s.applyWindowBlur?.invoke() }
    }
}

/** 漸層背景: preset swatches, direction and how strongly it shows. */
@Composable
private fun GradientSettings(s: AppState) {
    val p = LocalPalette.current
    ToggleCard(S.gradientBackground, s.gradientOn) { s.gradientOn = it }
    if (!s.gradientOn) return
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientChoices.forEachIndexed { i, g ->
            val sel = i == s.gradientIndex
            val colors = g.colors.map { Color(it) }
            Column(Modifier.width(88.dp).clip(RoundedCornerShape(6.dp)).clickable { s.gradientIndex = i }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(80.dp, 48.dp).clip(RoundedCornerShape(6.dp))
                        .drawBehind { drawRect(angledGradient(colors, s.gradientAngle, size)) }
                        .border(if (sel) 2.dp else 1.dp, if (sel) p.accent else p.cardStroke, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.height(4.dp))
                T(g.name, size = 11.sp)
            }
        }
    }
    SettingCard(S.gradientAngle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FluentSlider(s.gradientAngle, { s.gradientAngle = it }, range = 0f..360f, steps = 24, width = 200.dp)
            T("${s.gradientAngle.toInt()}°", Modifier.width(48.dp).padding(start = 8.dp))
        }
    }
    SettingCard(S.gradientStrength) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FluentSlider(s.gradientStrength, { s.gradientStrength = it }, range = 0.1f..1f, steps = 18, width = 200.dp)
            T("${(s.gradientStrength * 100).toInt()}%", Modifier.width(48.dp).padding(start = 8.dp))
        }
    }
}

/** The original's background colour swatches (AppThemeResourcesFactory). */
@Composable
private fun TintGrid(s: AppState) {
    val p = LocalPalette.current
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TintChoices.forEach { c ->
            val sel = c.argb.equals(s.backgroundTint, true)
            val col = parseArgb(c.argb)
            Column(
                Modifier.width(88.dp).clip(RoundedCornerShape(6.dp)).clickable { s.backgroundTint = c.argb }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(80.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(p.card)
                        .background(if (col.alpha > 0f) col.copy(alpha = 1f) else Color.Transparent)
                        .border(if (sel) 2.dp else 1.dp, if (sel) p.accent else p.cardStroke, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.height(4.dp))
                T(c.name, size = 11.sp)
            }
        }
    }
}

private val pictureExt = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")

/** Windows file picker for background pictures (several at once). */
private fun pickImages(): List<String> {
    val dlg = java.awt.FileDialog(null as java.awt.Frame?, S.backgroundImage, java.awt.FileDialog.LOAD)
    dlg.isMultipleMode = true
    dlg.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in pictureExt }
    dlg.file = "*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp"
    dlg.isVisible = true
    return dlg.files.map { it.path }
}

/** Folder picker (Windows folder dialog via the AWT file dialog in directories mode). */
private fun pickFolder(): String? {
    System.setProperty("sun.awt.windows.useCommonItemDialog", "true")
    val ch = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = S.chooseFolder }
    return if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile.path else null
}

/**
 * Background pictures: a list of several, how they switch (one picture, on a timer, or each
 * time the app opens; in order or shuffled), and per-folder pictures.
 */
@Composable
private fun BackgroundPictures(s: AppState) {
    val p = LocalPalette.current
    // Picture list
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(S.bgPictures + " (${s.bgImages.size})", Modifier.weight(1f), weight = FontWeight.SemiBold)
            DialogButton(S.addPictures, primary = false) {
                pickImages().forEach { if (it !in s.bgImages) s.bgImages.add(it) }
                if (s.backgroundImage.isBlank() && s.bgImages.isNotEmpty()) { s.bgIndex = 0; s.backgroundImage = s.bgImages[0] }
            }
            Spacer(Modifier.width(8.dp))
            DialogButton(S.addFolderPictures, primary = false) {
                pickFolder()?.let { dir ->
                    java.io.File(dir).listFiles { f -> f.isFile && f.extension.lowercase() in pictureExt }?.sortedBy { it.name.lowercase() }
                        ?.forEach { if (it.path !in s.bgImages) s.bgImages.add(it.path) }
                    if (s.backgroundImage.isBlank() && s.bgImages.isNotEmpty()) { s.bgIndex = 0; s.backgroundImage = s.bgImages[0] }
                }
            }
        }
        if (s.bgImages.isEmpty()) T(S.noImage, Modifier.padding(vertical = 6.dp), size = 12.sp, color = p.textSecondary)
        s.bgImages.toList().forEachIndexed { i, path ->
            val current = path == s.backgroundImage
            Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(4.dp)).background(if (current) p.selected else Color.Transparent).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val item = remember(path) { FsExtra.item(path) }
                if (item != null) FileIcon(item, 28.dp) else Icon(Icons.Outlined.BrokenImage, null, Modifier.size(28.dp), tint = p.textTertiary)
                Spacer(Modifier.width(10.dp))
                T(java.io.File(path).name, Modifier.weight(1f), size = 13.sp)
                if (current) T(S.currentPicture, size = 12.sp, color = p.accentText)
                else Hover(Modifier.height(28.dp), onClick = { s.bgIndex = i; s.backgroundImage = path }) {
                    T(S.useThis, Modifier.align(Alignment.Center).padding(horizontal = 8.dp), size = 12.sp, color = p.accentText)
                }
                IconBtn(Icons.Outlined.Close, S.remove, {
                    s.bgImages.remove(path)
                    if (current) { s.backgroundImage = s.bgImages.firstOrNull() ?: ""; s.bgIndex = 0 }
                }, width = 28.dp, height = 28.dp, iconSize = 12.dp)
            }
        }
        if (s.backgroundImage.isNotBlank() && s.backgroundImage !in s.bgImages) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(java.io.File(s.backgroundImage).name, Modifier.weight(1f), size = 13.sp)
                DialogButton(S.remove, primary = false) { s.backgroundImage = "" }
            }
        }
    }
    // When to change (any of them together) and in which order.
    if (s.bgImages.size < 2) T(tr("Add two or more pictures to have them change.", "加入兩張以上的圖片，就能設定自動切換。"), Modifier.padding(start = 2.dp, top = 6.dp), size = 12.sp, color = p.textSecondary)
    else {
    T(tr("When to change the picture (any of these together):", "何時切換圖片（可以同時開啟多個）："), Modifier.padding(start = 2.dp, top = 8.dp), size = 13.sp, color = p.textSecondary)
    ToggleCard(tr("Each time Files opens", "每次開啟 Files 時"), s.bgOnStart) { s.bgOnStart = it }
    ToggleCard(tr("On a timer", "定時切換"), s.bgOnTimer) { s.bgOnTimer = it }
    if (s.bgOnTimer) SettingCard(S.bgInterval) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconBtn(Icons.Outlined.Remove, "-", { s.bgIntervalMin = (s.bgIntervalMin - if (s.bgIntervalMin > 10) 5 else 1).coerceIn(1, 1440) })
            T(S.minutes(s.bgIntervalMin), Modifier.width(72.dp))
            IconBtn(Icons.Outlined.Add, "+", { s.bgIntervalMin = (s.bgIntervalMin + if (s.bgIntervalMin >= 10) 5 else 1).coerceIn(1, 1440) })
        }
    }
    ToggleCard(tr("When the window comes back after being minimized", "視窗從最小化還原時"), s.bgOnRestore) { s.bgOnRestore = it }
    ChoiceCard(tr("Order", "順序"), listOf(false to tr("List order", "依清單順序"), true to tr("Random (each once before any repeats)", "隨機（每張都輪過一次才重複）")), s.bgShuffle) { s.bgShuffle = it }
    SettingCard(S.nextPicture) { DialogButton(S.nextPicture, primary = false) { s.nextBackground() } }
    }
    // Per-folder pictures
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(S.bgFolderRules, weight = FontWeight.SemiBold)
                T(S.bgFolderRulesHint, size = 12.sp, color = p.textSecondary, maxLines = 2)
            }
            DialogButton(S.addRule, primary = false) {
                val folder = pickFolder() ?: return@DialogButton
                val img = pickImages().firstOrNull() ?: return@DialogButton
                s.bgRules.add(BgRule(folder, img, true))
                if (img !in s.bgImages) s.bgImages.add(img)
            }
        }
        s.bgRules.toList().forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = p.folder)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    T(r.folder, size = 13.sp)
                    T("→ " + java.io.File(r.image).name, size = 12.sp, color = p.textSecondary)
                }
                T(S.includeSubfolders, size = 12.sp, color = p.textSecondary)
                Spacer(Modifier.width(6.dp))
                Switch(r.includeSubfolders) { v -> s.bgRules[i] = r.copy(includeSubfolders = v) }
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.Outlined.Close, S.remove, { s.bgRules.removeAt(i) }, width = 28.dp, height = 28.dp, iconSize = 12.dp)
            }
        }
    }
}

@Composable
fun GroupTitle(text: String) {
    T(text, Modifier.padding(top = 16.dp, bottom = 4.dp, start = 2.dp), size = 14.sp, weight = FontWeight.SemiBold)
}

@Composable
fun SettingCard(title: String, trailing: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, Modifier.weight(1f), maxLines = 2)
        trailing()
    }
}

@Composable
fun ToggleCard(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    SettingCard(title) { Switch(value, onChange) }
}

@Composable
fun <V> ChoiceCard(title: String, options: List<Pair<V, String>>, value: V, onChange: (V) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    SettingCard(title) {
        Box {
            Hover(Modifier.height(32.dp).widthIn(min = 160.dp).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)), onClick = { open = true }) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(options.firstOrNull { it.first == value }?.second ?: "", Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp), tint = p.textSecondary)
                }
            }
            Flyout(open, { open = false }) {
                options.forEach { (v, label) ->
                    FlyoutItem(label, if (v == value) Icons.Outlined.Check else null, "") { open = false; onChange(v) }
                }
            }
        }
    }
}

/** Fluent-style toggle switch. */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp, 20.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) p.accent else Color.Transparent)
            .border(1.dp, if (on) p.accent else p.textSecondary, RoundedCornerShape(10.dp))
            .clickable { onChange(!on) },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 4.dp).size(12.dp).background(if (on) (if (p.dark) Color.Black else Color.White) else p.textSecondary, CircleShape))
    }
}

@Composable
private fun TagSettings(s: AppState) {
    val p = LocalPalette.current
    val store = s.tagStore
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TagPalette[4]) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    store.defs.forEach { tag ->
        val count = store.filesWith(tag.name).size
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalOffer, null, Modifier.size(18.dp), tint = Color(tag.color))
                Spacer(Modifier.width(10.dp))
                if (editing == tag.name) {
                    PlainField(editName, { editName = it }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DialogButton(S.done, primary = true) {
                        if (store.renameTag(tag.name, editName)) {
                            s.allPanes().filter { it.isTag && it.tagName == tag.name }.forEach { s.navigate("$TAG_PREFIX${editName.trim()}", it) }
                            editing = null
                        } else s.toast = if (S.zh) "名稱無效或已存在" else "That name is empty or already used"
                    }
                } else {
                    T(tag.name, Modifier.weight(1f), weight = FontWeight.SemiBold)
                    T(S.items(count), size = 12.sp, color = p.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Icons.Outlined.OpenInNew, S.openTag, { s.settingsOpen = false; s.navigate("$TAG_PREFIX${tag.name}") })
                    IconBtn(Icons.Outlined.DriveFileRenameOutline, S.renameTag, { editing = tag.name; editName = tag.name })
                    IconBtn(Icons.Outlined.Delete, S.deleteTag, { store.deleteTag(tag.name) })
                }
            }
            Spacer(Modifier.height(8.dp))
            ColorRow(tag.color) { store.recolor(tag.name, it) }
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingCard(S.newTag) {
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlainField(newName, { newName = it }, Modifier.width(200.dp), placeholder = S.newTag)
                Spacer(Modifier.width(8.dp))
                DialogButton(S.add, primary = true) {
                    if (store.addTag(newName, newColor)) newName = ""
                    else s.toast = if (S.zh) "名稱無效或已存在" else "That name is empty or already used"
                }
            }
            Spacer(Modifier.height(6.dp))
            ColorRow(newColor) { newColor = it }
        }
    }
}
