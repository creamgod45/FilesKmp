package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Base64

// ====================================================================== model

enum class ShellKind(val label: String) { PowerShell("PowerShell"), Cmd("命令提示字元 (cmd)") }

/** A toolbar button the person wrote: a PowerShell or cmd script run in the current folder. */
data class CustomCommand(val id: String, val name: String, val shell: ShellKind, val script: String, val keepOpen: Boolean = true)

/** A toolbar button that opens a drop-down of commands the person chose (built-in or their own). */
data class CustomFolder(val id: String, val name: String, val children: List<String>)

/**
 * The person's own toolbar items, kept in %APPDATA%\FilesKmp\toolbar-custom.txt, one per line:
 *   cmd    <id> <shell> <keepOpen 1/0> <name base64> <script base64>
 *   folder <id> <name base64> <child ids, comma-separated>
 * They appear in 自訂工具列 under 自訂 and work anywhere a built-in command does ([TbCommands.get]).
 */
object TbCustom {
    private val file get() = File(Prefs.dir, "toolbar-custom.txt")
    var commands by mutableStateOf<List<CustomCommand>>(emptyList()); private set
    var folders by mutableStateOf<List<CustomFolder>>(emptyList()); private set
    private var cmds by mutableStateOf<Map<String, TbCmd>>(emptyMap())

    init { load() }

    fun isCustom(id: String) = id.startsWith("cmd:") || id.startsWith("folder:")
    fun get(id: String): TbCmd? = cmds[id]
    /** Every custom item, as commands for the 可用選項 tree. */
    val all: List<TbCmd> get() = cmds.values.toList()

    fun command(id: String) = commands.firstOrNull { it.id == id }
    fun folder(id: String) = folders.firstOrNull { it.id == id }
    fun newId(prefix: String) = prefix + java.util.UUID.randomUUID().toString().substring(0, 8)

    fun upsert(c: CustomCommand) { commands = commands.filter { it.id != c.id } + c; changed() }
    fun upsert(f: CustomFolder) { folders = folders.filter { it.id != f.id } + f; changed() }
    fun rename(id: String, name: String) {
        command(id)?.let { upsert(it.copy(name = name)) }
        folder(id)?.let { upsert(it.copy(name = name)) }
    }
    /** Removes an item, and it from every folder. */
    fun delete(id: String) {
        commands = commands.filter { it.id != id }
        folders = folders.filter { it.id != id }.map { f -> f.copy(children = f.children.filter { it != id }) }
        changed()
    }

    private fun changed() { rebuild(); save() }

    private fun rebuild() {
        val m = LinkedHashMap<String, TbCmd>()
        commands.forEach { c ->
            m[c.id] = TbCmd(c.id, c.name, c.name, TbCategory.Custom, Icons.Outlined.Terminal, run = { s -> run(s, c) })
        }
        folders.forEach { f ->
            m[f.id] = TbCmd(f.id, f.name, f.name, TbCategory.Custom, Icons.Filled.Folder,
                groupItems = { s ->
                    f.children.mapNotNull { TbCommands.get(it) }.flatMap { c ->
                        // A built-in group inside a folder (e.g. 新增) shows its own items.
                        if (c.isGroup) c.groupItems!!(s) else listOf(MenuEntry(c.label, c.icon, c.hotkey, enabled = c.enabled(s)) { c.run(s) })
                    }
                })
        }
        cmds = m
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun unb64(s: String) = runCatching { String(Base64.getDecoder().decode(s), Charsets.UTF_8) }.getOrDefault("")

    private fun load() {
        val cs = ArrayList<CustomCommand>(); val fs = ArrayList<CustomFolder>()
        runCatching {
            if (file.isFile) file.readLines(Charsets.UTF_8).forEach { line ->
                val p = line.split('\t')
                when {
                    p.size == 6 && p[0] == "cmd" -> cs += CustomCommand(p[1], unb64(p[4]),
                        runCatching { ShellKind.valueOf(p[2]) }.getOrDefault(ShellKind.PowerShell), unb64(p[5]), p[3] == "1")
                    p.size == 4 && p[0] == "folder" -> fs += CustomFolder(p[1], unb64(p[2]), p[3].split(',').filter { it.isNotBlank() })
                }
            }
        }
        commands = cs; folders = fs; rebuild()
    }

    private fun save() {
        if (Prefs.readOnly) return
        runCatching {
            file.writeText((commands.map { "cmd\t${it.id}\t${it.shell.name}\t${if (it.keepOpen) 1 else 0}\t${b64(it.name)}\t${b64(it.script)}" } +
                folders.map { "folder\t${it.id}\t${b64(it.name)}\t${it.children.joinToString(",")}" }).joinToString("\n"), Charsets.UTF_8)
        }
    }

    // ------------------------------------------------------------ running

    /**
     * Runs [c] in a new, visible console window, in the current folder (the user folder on Home and
     * other non-folder pages). The script can use {folder}, {file} and {selected} (quoted for the
     * shell), or the environment variables FILES_FOLDER, FILES_FILE and FILES_SELECTED (one path per line).
     */
    fun run(s: AppState, c: CustomCommand) {
        val t = s.tab
        val dir = if (!t.isVirtual && File(t.location).isDirectory) t.location else userHome
        val sel = t.selection.toList()
        val script = expand(c.script, c.shell, dir, sel)
        try {
            val pb = when (c.shell) {
                ShellKind.PowerShell -> {
                    val full = "Set-Location -LiteralPath ${psQuote(dir)}\n$script"
                    val enc = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
                    // cmd.exe lines stop at 8191 characters.
                    if (enc.length > 7600) { s.toast = S.scriptTooLong; return }
                    ProcessBuilder(listOf("cmd.exe", "/c", "start", "\"${c.name.replace("\"", "")}\"", "powershell.exe") +
                        (if (c.keepOpen) listOf("-NoExit") else emptyList()) + listOf("-NoProfile", "-EncodedCommand", enc))
                }
                ShellKind.Cmd -> {
                    // A .cmd file keeps multi-line scripts and quoting intact; UTF-8 via chcp.
                    val f = File(AppDirs.cache, "scripts\\${c.id.removePrefix("cmd:")}.cmd").apply { parentFile.mkdirs() }
                    f.writeText("@chcp 65001 >nul\r\n@cd /d \"$dir\"\r\n" + script.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n", Charsets.UTF_8)
                    ProcessBuilder("cmd.exe", "/c", "start", "\"${c.name.replace("\"", "")}\"", "cmd.exe", if (c.keepOpen) "/k" else "/c", f.path)
                }
            }
            pb.directory(File(dir)).environment().apply {
                put("FILES_FOLDER", dir); put("FILES_FILE", sel.firstOrNull() ?: ""); put("FILES_SELECTED", sel.joinToString("\n"))
            }
            pb.start()
        } catch (e: Exception) {
            s.toast = (if (S.zh) "無法執行「${c.name}」：" else "Couldn't run \"${c.name}\": ") + e.message
        }
    }

    fun psQuote(v: String) = "'" + v.replace("'", "''") + "'"
    private fun cmdQuote(v: String) = "\"" + v.replace("\"", "") + "\""

    fun expand(script: String, shell: ShellKind, dir: String, sel: List<String>): String {
        val q: (String) -> String = if (shell == ShellKind.PowerShell) ::psQuote else ::cmdQuote
        return script.replace("{folder}", q(dir))
            .replace("{file}", sel.firstOrNull()?.let(q) ?: q(""))
            .replace("{selected}", sel.joinToString(" ") { q(it) })
    }
}

// ====================================================================== editors

/** 新增 / 編輯指令: name, shell, script, keep the window open. */
@Composable
fun CustomCommandEditor(initial: CustomCommand?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (CustomCommand) -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var shell by remember { mutableStateOf(initial?.shell ?: ShellKind.PowerShell) }
    var script by remember { mutableStateOf(initial?.script ?: "") }
    var keepOpen by remember { mutableStateOf(initial?.keepOpen ?: true) }
    EditorFrame(if (initial == null) S.newCommand else S.editCommand, onDismiss) {
        T(S.itemName, size = 12.sp, color = p.textSecondary)
        PlainField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = S.commandNameHint)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShellKind.entries.forEach { k ->
                Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable { shell = k }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (shell == k) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(16.dp),
                        tint = if (shell == k) p.accent else p.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    T(if (k == ShellKind.Cmd && !S.zh) "Command Prompt (cmd)" else k.label, size = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        T(S.script, size = 12.sp, color = p.textSecondary)
        BasicTextField(
            script, { script = it },
            textStyle = TextStyle(color = p.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(p.text),
            modifier = Modifier.fillMaxWidth().height(130.dp)
                .background(if (p.dark) androidx.compose.ui.graphics.Color(0xFF1F1F1F) else androidx.compose.ui.graphics.Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(8.dp),
        )
        T(S.scriptHelp, Modifier.padding(top = 4.dp), size = 11.sp, color = p.textTertiary, maxLines = 4)
        Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).clickable { keepOpen = !keepOpen }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (keepOpen) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(18.dp),
                tint = if (keepOpen) p.accent else p.textSecondary)
            Spacer(Modifier.width(6.dp))
            T(S.keepWindowOpen, size = 13.sp)
        }
        EditorButtons(onDismiss, onDelete, canSave = name.isNotBlank() && script.isNotBlank()) {
            onSave(CustomCommand(initial?.id ?: TbCustom.newId("cmd:"), name.trim(), shell, script, keepOpen))
        }
    }
}

/** 新增 / 編輯資料夾: a name and the commands it holds, in the order they were ticked. */
@Composable
fun CustomFolderEditor(initial: CustomFolder?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (CustomFolder) -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial?.children.orEmpty()) } }
    // Anything but folders (no folders inside folders).
    val choices = (TbCommands.all + TbCustom.all).filter { !it.id.startsWith("folder:") }
        .sortedWith(compareBy({ it.category != TbCategory.Custom }, { it.category.text }, { it.listLabel }))
    EditorFrame(if (initial == null) S.newFolder2 else S.editFolder, onDismiss) {
        T(S.itemName, size = 12.sp, color = p.textSecondary)
        PlainField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = S.folderNameHint)
        Spacer(Modifier.height(10.dp))
        T(S.folderContents + (if (chosen.isNotEmpty()) "（${chosen.size}）" else ""), size = 12.sp, color = p.textSecondary)
        // The chosen order, each removable.
        if (chosen.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            chosen.forEachIndexed { i, id ->
                Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("${i + 1}.  " + (TbCommands.get(id)?.listLabel ?: id), Modifier.weight(1f), size = 12.sp)
                    IconBtn(Icons.Outlined.Close, S.remove, { chosen.remove(id) }, width = 24.dp, height = 24.dp, iconSize = 11.dp)
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(200.dp).border(1.dp, p.cardStroke, RoundedCornerShape(4.dp))) {
            items(choices.size) { i ->
                val c = choices[i]
                val on = c.id in chosen
                Row(Modifier.fillMaxWidth().height(28.dp).clickable { if (on) chosen.remove(c.id) else chosen.add(c.id) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(16.dp),
                        tint = if (on) p.accent else p.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    T(c.listLabel, Modifier.weight(1f), size = 12.sp)
                    T(c.category.text, size = 11.sp, color = p.textTertiary)
                }
            }
        }
        EditorButtons(onDismiss, onDelete, canSave = name.isNotBlank()) {
            onSave(CustomFolder(initial?.id ?: TbCustom.newId("folder:"), name.trim(), chosen.toList()))
        }
    }
}

@Composable
private fun EditorFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Popup(alignment = Alignment.Center, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Column(
            Modifier.width(520.dp).heightIn(max = 640.dp).shadow8().clip(RoundedCornerShape(8.dp)).background(p.solid)
                .border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).padding(20.dp).verticalScroll(rememberScrollState())
        ) {
            T(title, Modifier.padding(bottom = 12.dp), size = 18.sp, weight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EditorButtons(onDismiss: () -> Unit, onDelete: (() -> Unit)?, canSave: Boolean, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onDelete != null) DialogButton(S.delete, primary = false, onClick = onDelete)
        Spacer(Modifier.weight(1f))
        DialogButton(S.cancel, primary = false, onClick = onDismiss)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.then(if (canSave) Modifier else Modifier.background(androidx.compose.ui.graphics.Color.Transparent))) {
            DialogButton(S.save, primary = canSave) { if (canSave) onSave() }
        }
    }
}
