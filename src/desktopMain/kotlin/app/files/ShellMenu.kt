package app.files

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One item of Windows' own right-click menu. [run] invokes it (on the shell thread). */
class ShellItem(
    val label: String,
    val verb: String?,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val separator: Boolean = false,
    val icon: ImageBitmap? = null,
    val children: List<ShellItem> = emptyList(),
    val run: (() -> Unit)? = null,
)

/**
 * Windows' right-click menu for some items.
 * - [Source.Modern]: the Windows 11 menu — the commands apps register for it (IExplorerCommand,
 *   `FileExplorerContextMenus` in their package) and Windows' usual verbs; [more] is the full classic
 *   menu ("Show more options").
 * - [Source.Classic]: the classic menu (IContextMenu), when the Windows 11 one couldn't be read.
 */
class ShellMenuResult(val source: Source, val top: List<ShellItem>, val more: List<ShellItem>, val note: String? = null) {
    enum class Source { Modern, Classic, Unavailable }
}

/** Which menu to read (設定 › 檔案和資料夾 › Windows 右鍵選單). */
enum class ShellMenuMode(private val en: String, private val zh: String) {
    Auto("Automatic (Windows 11 menu, classic if it can't be read)", "自動（Windows 11 新版選單，讀不到時改用舊版）"),
    Modern("Windows 11 menu only", "只用 Windows 11 新版選單"),
    Classic("Classic menu only", "只用舊版選單"),
    Off("Off", "不顯示");
    val text get() = tr(en, zh)
}

object ShellMenu {
    private val linker = Linker.nativeLinker()
    private val A = ValueLayout.ADDRESS
    private val I = ValueLayout.JAVA_INT
    private val J = ValueLayout.JAVA_LONG
    private val global = Arena.global()

    private fun fn(lib: String, name: String, desc: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(SymbolLookup.libraryLookup(lib, global).find(name).orElseThrow(), desc)

    private val oleInitialize by lazy { fn("ole32", "OleInitialize", FunctionDescriptor.of(I, A)) }
    private val coCreateInstance by lazy { fn("ole32", "CoCreateInstance", FunctionDescriptor.of(I, A, A, I, A, A)) }
    private val coTaskMemFree by lazy { fn("ole32", "CoTaskMemFree", FunctionDescriptor.ofVoid(A)) }
    private val shParseDisplayName by lazy { fn("shell32", "SHParseDisplayName", FunctionDescriptor.of(I, A, A, A, I, A)) }
    private val shBindToParent by lazy { fn("shell32", "SHBindToParent", FunctionDescriptor.of(I, A, A, A, A)) }
    private val shBindToObject by lazy { fn("shell32", "SHBindToObject", FunctionDescriptor.of(I, A, A, A, A, A)) }
    private val shCreateItemArray by lazy { fn("shell32", "SHCreateShellItemArrayFromIDLists", FunctionDescriptor.of(I, I, A, A)) }
    private val ilFindLastId by lazy { fn("shell32", "ILFindLastID", FunctionDescriptor.of(A, A)) }
    private val extractIconEx by lazy { fn("shell32", "ExtractIconExW", FunctionDescriptor.of(I, A, I, A, A, I)) }
    private val createPopupMenu by lazy { fn("user32", "CreatePopupMenu", FunctionDescriptor.of(A)) }
    private val destroyMenu by lazy { fn("user32", "DestroyMenu", FunctionDescriptor.of(I, A)) }
    private val getMenuItemCount by lazy { fn("user32", "GetMenuItemCount", FunctionDescriptor.of(I, A)) }
    private val getMenuItemInfo by lazy { fn("user32", "GetMenuItemInfoW", FunctionDescriptor.of(I, A, I, I, A)) }
    private val peekMessage by lazy { fn("user32", "PeekMessageW", FunctionDescriptor.of(I, A, A, I, I, I)) }
    private val translateMessage by lazy { fn("user32", "TranslateMessage", FunctionDescriptor.of(I, A)) }
    private val dispatchMessage by lazy { fn("user32", "DispatchMessageW", FunctionDescriptor.of(J, A)) }
    private val getIconInfo by lazy { fn("user32", "GetIconInfo", FunctionDescriptor.of(I, A, A)) }
    private val destroyIcon by lazy { fn("user32", "DestroyIcon", FunctionDescriptor.of(I, A)) }
    private val getDC by lazy { fn("user32", "GetDC", FunctionDescriptor.of(A, A)) }
    private val releaseDC by lazy { fn("user32", "ReleaseDC", FunctionDescriptor.of(I, A, A)) }
    private val getObject by lazy { fn("gdi32", "GetObjectW", FunctionDescriptor.of(I, A, I, A)) }
    private val getDIBits by lazy { fn("gdi32", "GetDIBits", FunctionDescriptor.of(I, A, A, I, I, A, A, I)) }
    private val deleteObject by lazy { fn("gdi32", "DeleteObject", FunctionDescriptor.of(I, A)) }

    private const val IID_IShellFolder = "000214E6-0000-0000-C000-000000000046"
    private const val IID_IContextMenu = "000214E4-0000-0000-C000-000000000046"
    private const val IID_IContextMenu2 = "000214F4-0000-0000-C000-000000000046"
    private const val IID_IContextMenu3 = "BCFCE0A0-EC17-11D0-8D10-00A0C90F2719"
    private const val IID_IExplorerCommand = "A08CE4D0-FA25-44AB-B57C-C7B1C323E0B9"
    private const val IID_IShellItemArray = "B63EA76D-1F85-456F-A19C-48159EFA858B"
    private const val FIRST_ID = 1
    private const val LAST_ID = 0x7FFF

    /** Our window, owner of whatever the commands show (set when the window opens). */
    @Volatile var ownerHwnd: Long = 0
    private val owner get() = MemorySegment.ofAddress(ownerHwnd)

    // ------------------------------------------------------------------ the shell thread
    // Shell extensions are COM objects that expect one single-threaded apartment with a message
    // loop, and the menu must be invoked on the thread that built it. A stuck extension only
    // costs that thread: the next request starts a new one.

    private class Sta : Thread("shell-menu") {
        val queue = LinkedBlockingQueue<Runnable>()
        init { isDaemon = true }
        override fun run() {
            runCatching { oleInitialize.invokeWithArguments(MemorySegment.NULL) }
            Arena.ofConfined().use { arena ->
                val msg = arena.allocate(48)
                while (true) {
                    queue.poll(25, TimeUnit.MILLISECONDS)?.let { r -> runCatching { r.run() } }
                    while ((peekMessage.invokeWithArguments(msg, MemorySegment.NULL, 0, 0, 1) as Int) != 0) {
                        translateMessage.invokeWithArguments(msg); dispatchMessage.invokeWithArguments(msg)
                    }
                }
            }
        }
    }
    @Volatile private var sta: Sta? = null
    private fun thread(): Sta = sta ?: synchronized(this) { sta ?: Sta().also { it.start(); sta = it } }

    /** Runs [block] on the shell thread and waits up to [timeoutMs]. */
    private fun <T> onSta(timeoutMs: Long, block: () -> T): T {
        val f = CompletableFuture<T>()
        val t = thread()
        t.queue.put { try { f.complete(block()) } catch (e: Throwable) { f.completeExceptionally(e) } }
        return try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.TimeoutException) {
            synchronized(this) { if (sta === t) sta = null }   // that thread is stuck in an extension: use a new one
            throw IllegalStateException(tr("A shell extension didn't answer in time", "某個 Windows 外掛程式沒有回應"))
        } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
    }

    // ------------------------------------------------------------------ COM helpers

    private fun com(obj: MemorySegment, index: Int, desc: FunctionDescriptor, vararg args: Any?): Any? {
        val vtbl = obj.reinterpret(8).get(A, 0).reinterpret(8L * (index + 1))
        return linker.downcallHandle(vtbl.getAtIndex(A, index.toLong()), desc).invokeWithArguments(obj, *args)
    }
    private fun release(o: MemorySegment?) { if (o != null && o.address() != 0L) runCatching { com(o, 2, FunctionDescriptor.of(I, A)) } }
    private fun guid(arena: Arena, s: String): MemorySegment {
        val h = s.replace("-", "")
        val m = arena.allocate(16)
        m.set(ValueLayout.JAVA_INT_UNALIGNED, 0, h.substring(0, 8).toLong(16).toInt())
        m.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, h.substring(8, 12).toInt(16).toShort())
        m.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, h.substring(12, 16).toInt(16).toShort())
        for (k in 0 until 8) m.set(ValueLayout.JAVA_BYTE, 8L + k, h.substring(16 + k * 2, 18 + k * 2).toInt(16).toByte())
        return m
    }
    private fun qi(obj: MemorySegment, iid: String, arena: Arena): MemorySegment? {
        val pp = arena.allocate(A)
        return if (com(obj, 0, FunctionDescriptor.of(I, A, A, A), guid(arena, iid), pp) as Int == 0) pp.get(A, 0) else null
    }
    private fun wstr(p: MemorySegment): String = p.reinterpret(Long.MAX_VALUE).getString(0, Charsets.UTF_16LE)
    private fun taskString(pp: MemorySegment): String? {
        val p = pp.get(A, 0); if (p.address() == 0L) return null
        return try { wstr(p) } finally { coTaskMemFree.invokeWithArguments(p) }
    }

    // ------------------------------------------------------------------ one menu at a time

    /** What the open menu holds on to until it's closed or the next one is read (COM objects, menus, item ids). */
    private class Session(val arena: Arena) {
        val objects = ArrayList<MemorySegment>()
        val menus = ArrayList<MemorySegment>()
        val pidls = ArrayList<MemorySegment>()
        fun close() {
            objects.asReversed().forEach { release(it) }
            menus.forEach { runCatching { destroyMenu.invokeWithArguments(it) } }
            pidls.forEach { runCatching { coTaskMemFree.invokeWithArguments(it) } }
            arena.close()
        }
    }
    private var session: Session? = null   // shell thread only

    /** Frees the last menu read (call when the app's menu closes). */
    fun close() { runCatching { thread().queue.put { session?.close(); session = null } } }

    /**
     * Reads Windows' menu for [paths] (files and folders in one folder), or for the empty space of
     * folder [background]. [mode] picks the Windows 11 menu, the classic one, or Windows 11 with the
     * classic one as fallback.
     */
    fun query(paths: List<String>, background: String? = null, mode: ShellMenuMode = ShellMenuMode.Auto, timeoutMs: Long = 6000): ShellMenuResult {
        if (mode == ShellMenuMode.Off) return ShellMenuResult(ShellMenuResult.Source.Unavailable, emptyList(), emptyList())
        val handlers = if (mode == ShellMenuMode.Classic || !isWindows11) null else runCatching { ModernHandlers.all() }.onFailure {
            ErrorLog.record(it, "shell-menu: reading Windows 11 menu handlers")
        }.getOrNull()
        return onSta(timeoutMs) {
            session?.close()
            val sess = Session(Arena.ofShared()); session = sess
            val classic = runCatching { classic(sess, paths, background) }.onFailure { ErrorLog.record(it, "shell-menu: classic menu") }
            if (mode == ShellMenuMode.Classic || handlers == null) {
                val items = classic.getOrNull()
                if (items == null) ShellMenuResult(ShellMenuResult.Source.Unavailable, emptyList(), emptyList(), classic.exceptionOrNull()?.message)
                else ShellMenuResult(ShellMenuResult.Source.Classic, items, emptyList(),
                    if (mode == ShellMenuMode.Auto && isWindows11) tr("Showing the classic menu: the Windows 11 menu couldn't be read.", "已改用舊版選單：無法讀取 Windows 11 新版選單。") else null)
            } else {
                val modern = runCatching { modern(sess, handlers, paths, background) }.onFailure { ErrorLog.record(it, "shell-menu: Windows 11 menu") }
                val classicItems = classic.getOrNull().orEmpty()
                val m = modern.getOrNull()
                when {
                    m != null -> ShellMenuResult(ShellMenuResult.Source.Modern, m + classicItems.filter { it.verb != null && it.verb.lowercase() in modernVerbs }, classicItems)
                    mode == ShellMenuMode.Modern -> ShellMenuResult(ShellMenuResult.Source.Unavailable, emptyList(), emptyList(), modern.exceptionOrNull()?.message)
                    classic.isSuccess -> ShellMenuResult(ShellMenuResult.Source.Classic, classicItems, emptyList(),
                        tr("Showing the classic menu: the Windows 11 menu couldn't be read.", "已改用舊版選單：無法讀取 Windows 11 新版選單。"))
                    else -> ShellMenuResult(ShellMenuResult.Source.Unavailable, emptyList(), emptyList(), classic.exceptionOrNull()?.message)
                }
            }
        }
    }

    /** Windows 11 shows these classic verbs in its own menu too; the rest wait under "Show more options". */
    private val modernVerbs = setOf("openas", "edit", "print", "runas", "runasuser", "pintohome", "pintostartscreen", "windows.modernshare",
        "windows.share", "setdesktopwallpaper", "windows.rotateleft", "windows.rotateright", "mount", "eject", "format")

    val isWindows11: Boolean by lazy { (System.getProperty("os.version").split('.').getOrNull(0)?.toIntOrNull() ?: 0) >= 10 && build >= 22000 }
    val build: Int by lazy {
        WinReg.string(WinReg.HKLM, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuild")?.toIntOrNull() ?: 0
    }

    // ------------------------------------------------------------------ items as the shell knows them

    private class Items(val absolute: List<MemorySegment>, val parent: MemorySegment?, val children: List<MemorySegment>)

    private fun parse(sess: Session, path: String): MemorySegment {
        val pp = sess.arena.allocate(A)
        val hr = shParseDisplayName.invokeWithArguments(sess.arena.allocateFrom(path, Charsets.UTF_16LE), MemorySegment.NULL, pp, 0, MemorySegment.NULL) as Int
        check(hr == 0) { "SHParseDisplayName 0x%08X: %s".format(hr, path) }
        return pp.get(A, 0).also { sess.pidls += it }
    }

    /** The items, with the folder they're in (only items in the same folder as the first one). */
    private fun items(sess: Session, paths: List<String>): Items {
        val first = paths.first()
        val dir = File(first).parentFile?.path
        val same = paths.filter { File(it).parentFile?.path.equals(dir, true) }.ifEmpty { listOf(first) }
        val abs = same.map { parse(sess, it) }
        val pp = sess.arena.allocate(A)
        check(shBindToParent.invokeWithArguments(abs[0], guid(sess.arena, IID_IShellFolder), pp, MemorySegment.NULL) as Int == 0) { "SHBindToParent" }
        val parent = pp.get(A, 0).also { sess.objects += it }
        return Items(abs, parent, abs.map { ilFindLastId.invokeWithArguments(it) as MemorySegment })
    }

    // ------------------------------------------------------------------ classic menu (IContextMenu)

    private fun classic(sess: Session, paths: List<String>, background: String?): List<ShellItem> {
        val arena = sess.arena
        val pp = arena.allocate(A)
        val cm: MemorySegment
        var dir: String? = null
        if (background != null) {
            val pidl = parse(sess, background)
            check(shBindToObject.invokeWithArguments(MemorySegment.NULL, pidl, MemorySegment.NULL, guid(arena, IID_IShellFolder), pp) as Int == 0) { "SHBindToObject" }
            val folder = pp.get(A, 0).also { sess.objects += it }
            // IShellFolder::CreateViewObject(hwnd, IID_IContextMenu, &cm)
            check(com(folder, 8, FunctionDescriptor.of(I, A, A, A, A), owner, guid(arena, IID_IContextMenu), pp) as Int == 0) { "CreateViewObject" }
            cm = pp.get(A, 0); dir = background
        } else {
            val it = items(sess, paths)
            val arr = arena.allocate(A, it.children.size.toLong())
            it.children.forEachIndexed { i, c -> arr.setAtIndex(A, i.toLong(), c) }
            // IShellFolder::GetUIObjectOf(hwnd, cidl, apidl, IID_IContextMenu, NULL, &cm)
            check(com(it.parent!!, 10, FunctionDescriptor.of(I, A, A, I, A, A, A, A), owner, it.children.size, arr, guid(arena, IID_IContextMenu), MemorySegment.NULL, pp) as Int == 0) { "GetUIObjectOf" }
            cm = pp.get(A, 0); dir = File(paths.first()).parent
        }
        sess.objects += cm
        val cm3 = qi(cm, IID_IContextMenu3, arena)?.also { sess.objects += it }
        val cm2 = if (cm3 == null) qi(cm, IID_IContextMenu2, arena)?.also { sess.objects += it } else null
        val menu = createPopupMenu.invokeWithArguments() as MemorySegment
        sess.menus += menu
        // QueryContextMenu(hmenu, 0, first, last, CMF_NORMAL | CMF_EXPLORE)
        val hr = com(cm, 3, FunctionDescriptor.of(I, A, A, I, I, I, I), menu, 0, FIRST_ID, LAST_ID, 0x4) as Int
        check(hr >= 0) { "QueryContextMenu 0x%08X".format(hr) }
        val invoke = { id: Int ->
            onShellThreadLater {
                Arena.ofConfined().use { a ->
                    val ci = a.allocate(104)
                    ci.set(I, 0, 104)
                    ci.set(I, 4, 0x4000 or 0x00100000)                       // CMIC_MASK_UNICODE | CMIC_MASK_ASYNCOK
                    ci.set(A, 8, owner)
                    ci.set(A, 16, MemorySegment.ofAddress((id - FIRST_ID).toLong()))   // lpVerb = MAKEINTRESOURCE(offset)
                    ci.set(I, 40, 1)                                              // SW_SHOWNORMAL
                    ci.set(A, 64, MemorySegment.ofAddress((id - FIRST_ID).toLong()))   // lpVerbW
                    dir?.let { ci.set(A, 80, a.allocateFrom(it, Charsets.UTF_16LE)) }  // lpDirectoryW
                    val r = com(cm, 4, FunctionDescriptor.of(I, A, A), ci) as Int
                    if (r != 0) ErrorLog.record(IllegalStateException("InvokeCommand 0x%08X".format(r)), "shell-menu: classic command")
                }
            }
        }
        return readMenu(sess, cm, cm2, cm3, menu, invoke, 0)
    }

    private fun readMenu(sess: Session, cm: MemorySegment, cm2: MemorySegment?, cm3: MemorySegment?, menu: MemorySegment, invoke: (Int) -> Unit, depth: Int): List<ShellItem> {
        val arena = sess.arena
        val n = getMenuItemCount.invokeWithArguments(menu) as Int
        val out = ArrayList<ShellItem>()
        for (i in 0 until n) {
            val mii = arena.allocate(80)
            mii.set(I, 0, 80); mii.set(I, 4, 0x1C7)                               // STRING | ID | SUBMENU | FTYPE | STATE | BITMAP
            if (getMenuItemInfo.invokeWithArguments(menu, i, 1, mii) as Int == 0) continue
            val type = mii.get(I, 8); val state = mii.get(I, 12); val id = mii.get(I, 16)
            if (type and 0x800 != 0) { if (out.isNotEmpty() && !out.last().separator) out += ShellItem("", null, separator = true); continue }
            var label = ""
            val cch = mii.get(I, 64)
            if (cch > 0) {
                val buf = arena.allocate(((cch + 1) * 2).toLong())
                mii.set(I, 4, 0x40); mii.set(A, 56, buf); mii.set(I, 64, cch + 1)
                if (getMenuItemInfo.invokeWithArguments(menu, i, 1, mii) as Int != 0) label = wstr(buf)
                mii.set(I, 4, 0x1C7)
            }
            label = cleanLabel(label)
            val sub = mii.get(A, 24)
            val verb = if (sub.address() == 0L && id in FIRST_ID..LAST_ID) verbOf(cm, id - FIRST_ID, arena) else null
            if (label.isEmpty()) label = verb ?: continue
            val bmp = mii.get(A, 72)
            val icon = if (bmp.address() > 11) runCatching { bitmapImage(bmp) }.getOrNull() else null
            val children = if (sub.address() != 0L && depth < 4) {
                // Submenus like "Send to" and "Open with" fill themselves when they are about to open.
                runCatching {
                    when {
                        cm3 != null -> com(cm3, 7, FunctionDescriptor.of(I, A, I, J, J, A), 0x117, sub.address(), i.toLong(), arena.allocate(J))
                        cm2 != null -> com(cm2, 6, FunctionDescriptor.of(I, A, I, J, J), 0x117, sub.address(), i.toLong())
                        else -> null
                    }
                }
                readMenu(sess, cm, cm2, cm3, sub, invoke, depth + 1)
            } else emptyList()
            if (sub.address() != 0L && children.isEmpty()) continue
            out += ShellItem(label, verb, enabled = state and 3 == 0, checked = state and 8 != 0, icon = icon, children = children,
                run = if (sub.address() == 0L) ({ invoke(id) }) else null)
        }
        while (out.lastOrNull()?.separator == true) out.removeAt(out.size - 1)
        return out
    }

    /** "開啟檔案(&H)\tCtrl+O" → "開啟檔案": no access keys, no shortcut text. */
    fun cleanLabel(raw: String): String = raw.substringBefore('\t').replace("&&", "\u0000").replace("&", "").replace("\u0000", "&")
        .replace(Regex("\\s*\\([A-Za-z0-9]\\)\\s*(\\.\\.\\.|…)?$"), "$1").trim()

    private fun verbOf(cm: MemorySegment, offset: Int, arena: Arena): String? = runCatching {
        val buf = arena.allocate(512)
        // GetCommandString(idCmd, GCS_VERBW, NULL, buf, 256)
        if (com(cm, 5, FunctionDescriptor.of(I, A, J, I, A, A, I), offset.toLong(), 4, MemorySegment.NULL, buf, 256) as Int == 0) wstr(buf).takeIf { it.isNotBlank() } else null
    }.getOrNull()

    // ------------------------------------------------------------------ Windows 11 menu (IExplorerCommand)

    private fun modern(sess: Session, handlers: List<ModernHandlers.Handler>, paths: List<String>, background: String?): List<ShellItem> {
        val arena = sess.arena
        val targets = if (background != null) listOf(background) else paths
        val types = if (background != null) setOf("directory\\background") else paths.map { ModernHandlers.typesOf(it) }.reduce { a, b -> a intersect b }
        val wanted = handlers.filter { it.type.lowercase() in types }.distinctBy { it.clsid.lowercase() }
        val abs = targets.map { parse(sess, it) }
        val arr = arena.allocate(A, abs.size.toLong()); abs.forEachIndexed { i, p -> arr.setAtIndex(A, i.toLong(), p) }
        val pp = arena.allocate(A)
        check(shCreateItemArray.invokeWithArguments(abs.size, arr, pp) as Int == 0) { "SHCreateShellItemArrayFromIDLists" }
        val psia = pp.get(A, 0).also { sess.objects += it }
        var created = 0
        val out = ArrayList<ShellItem>()
        for (h in wanted) {
            val cmd = runCatching {
                val p2 = arena.allocate(A)
                val hr = coCreateInstance.invokeWithArguments(guid(arena, h.clsid), MemorySegment.NULL, 0x5, guid(arena, IID_IExplorerCommand), p2) as Int
                if (hr != 0) null else p2.get(A, 0)
            }.getOrNull() ?: continue
            created++
            sess.objects += cmd
            command(sess, cmd, psia, 0)?.let { out += it }
        }
        // Handlers were registered but none of them could be created: this Windows talks differently.
        check(wanted.isEmpty() || created > 0) { "No Windows 11 menu handler could be created (${wanted.size} registered)" }
        return out
    }

    private fun command(sess: Session, cmd: MemorySegment, psia: MemorySegment, depth: Int): ShellItem? {
        val arena = sess.arena
        val pp = arena.allocate(A)
        val st = arena.allocate(I)
        // GetState(psia, fOkToBeSlow = TRUE, &state): ECS_HIDDEN = 2, ECS_DISABLED = 1
        val state = if (com(cmd, 7, FunctionDescriptor.of(I, A, A, I, A), psia, 1, st) as Int == 0) st.get(I, 0) else 0
        if (state and 2 != 0) return null
        pp.set(A, 0, MemorySegment.NULL)
        if (com(cmd, 3, FunctionDescriptor.of(I, A, A, A), psia, pp) as Int != 0) return null
        val title = taskString(pp)?.let { cleanLabel(it) }.takeIf { !it.isNullOrEmpty() } ?: return null
        pp.set(A, 0, MemorySegment.NULL)
        val icon = if (com(cmd, 4, FunctionDescriptor.of(I, A, A, A), psia, pp) as Int == 0) taskString(pp)?.let { runCatching { resourceIcon(it) }.getOrNull() } else null
        val fl = arena.allocate(I)
        val flags = if (com(cmd, 9, FunctionDescriptor.of(I, A, A), fl) as Int == 0) fl.get(I, 0) else 0
        val children = ArrayList<ShellItem>()
        if (flags and 1 != 0 && depth < 3) runCatching {
            // EnumSubCommands → IEnumExplorerCommand::Next(1, &cmd, &fetched)
            if (com(cmd, 10, FunctionDescriptor.of(I, A, A), pp) as Int == 0) {
                val en = pp.get(A, 0).also { sess.objects += it }
                val one = arena.allocate(A); val got = arena.allocate(I)
                while (com(en, 3, FunctionDescriptor.of(I, A, I, A, A), 1, one, got) as Int == 0 && got.get(I, 0) == 1) {
                    val sub = one.get(A, 0).also { sess.objects += it }
                    command(sess, sub, psia, depth + 1)?.let { children += it }
                }
            }
        }
        return ShellItem(title, null, enabled = state and 1 == 0, icon = icon, children = children,
            run = if (children.isEmpty()) ({
                onShellThreadLater {
                    val r = com(cmd, 8, FunctionDescriptor.of(I, A, A, A), psia, MemorySegment.NULL) as Int
                    if (r != 0) ErrorLog.record(IllegalStateException("IExplorerCommand::Invoke 0x%08X".format(r)), "shell-menu: Windows 11 command $title")
                }
            }) else null)
    }

    /** Runs [block] on the shell thread without waiting (invoking a command). */
    private fun onShellThreadLater(block: () -> Unit) {
        thread().queue.put { try { block() } catch (e: Throwable) { ErrorLog.record(e, "shell-menu: invoke") } }
    }

    // ------------------------------------------------------------------ icons

    /** "C:\path\app.exe,-101" or "C:\x.ico" → a small icon. */
    private fun resourceIcon(spec: String): ImageBitmap? = Arena.ofConfined().use { a ->
        val m = Regex("^\"?(.*?)\"?(?:,(-?\\d+))?$").find(spec.trim()) ?: return null
        val file = m.groupValues[1].ifEmpty { return null }
        val index = m.groupValues[2].toIntOrNull() ?: 0
        val small = a.allocate(A)
        if ((extractIconEx.invokeWithArguments(a.allocateFrom(file, Charsets.UTF_16LE), index, MemorySegment.NULL, small, 1) as Int) <= 0) return null
        val h = small.get(A, 0)
        if (h.address() == 0L) return null
        try { iconImage(h) } finally { destroyIcon.invokeWithArguments(h) }
    }

    private fun iconImage(hicon: MemorySegment): ImageBitmap? = Arena.ofConfined().use { a ->
        val info = a.allocate(32)
        if (getIconInfo.invokeWithArguments(hicon, info) as Int == 0) return null
        val mask = info.get(A, 16); val color = info.get(A, 24)
        try { if (color.address() == 0L) null else bitmapImage(color) }
        finally { if (mask.address() != 0L) deleteObject.invokeWithArguments(mask); if (color.address() != 0L) deleteObject.invokeWithArguments(color) }
    }

    /** A 32-bit HBITMAP (menu item bitmap) as an image. */
    private fun bitmapImage(hbm: MemorySegment): ImageBitmap? = Arena.ofConfined().use { a ->
        val bm = a.allocate(32)
        if (getObject.invokeWithArguments(hbm, 32, bm) as Int == 0) return null
        val w = bm.get(I, 4); val h = Math.abs(bm.get(I, 8))
        if (w <= 0 || h <= 0 || w > 256 || h > 256) return null
        val bmi = a.allocate(44)
        bmi.set(I, 0, 40); bmi.set(I, 4, w); bmi.set(I, 8, -h)
        bmi.set(ValueLayout.JAVA_SHORT, 12, 1); bmi.set(ValueLayout.JAVA_SHORT, 14, 32)
        val px = a.allocate((w * h * 4).toLong())
        val dc = getDC.invokeWithArguments(MemorySegment.NULL) as MemorySegment
        val lines = try { getDIBits.invokeWithArguments(dc, hbm, 0, h, px, bmi, 0) as Int } finally { releaseDC.invokeWithArguments(MemorySegment.NULL, dc) }
        if (lines == 0) return null
        val argb = IntArray(w * h) { px.get(ValueLayout.JAVA_INT_UNALIGNED, it * 4L) }
        val hasAlpha = argb.any { it ushr 24 != 0 }
        if (!hasAlpha) for (k in argb.indices) argb[k] = argb[k] or (0xFF shl 24)
        val img = BufferedImage(w, h, if (hasAlpha) BufferedImage.TYPE_INT_ARGB_PRE else BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, argb, 0, w)
        img.toComposeImageBitmap()
    }
}

/**
 * The commands apps add to the Windows 11 menu, from their package manifests
 * (`desktop4:FileExplorerContextMenus` → ItemType → Verb Clsid). Read once in the background.
 */
object ModernHandlers {
    class Handler(val pkg: String, val type: String, val verb: String, val clsid: String)

    @Volatile private var cache: List<Handler>? = null
    private val lock = Any()

    /** The item types a path matches: "*", its extension, "Directory", "Drive". */
    fun typesOf(path: String): Set<String> {
        val f = File(path)
        return when {
            f.parentFile == null || path.matches(Regex("^[A-Za-z]:\\\\?$")) -> setOf("drive", "directory")
            f.isDirectory -> setOf("directory")
            else -> setOf("*") + ("." + f.name.substringAfterLast('.', "")).lowercase().takeIf { f.name.contains('.') }.let { listOfNotNull(it) }
        }
    }

    fun all(): List<Handler> = cache ?: synchronized(lock) { cache ?: read().also { cache = it } }

    /** Tests only: pretend these are the registered handlers (null = read them again). */
    internal fun testSet(list: List<Handler>?) { cache = list }

    /** Starts reading in the background (at app start), so the first right-click is quick. */
    fun preload() { Thread { runCatching { all() } }.apply { isDaemon = true; start() } }

    /**
     * This user's installed packages (registry: AppModel\Repository\Packages → PackageRootFolder),
     * the newest version of each, and the Explorer menu commands each AppxManifest.xml declares.
     * Starts no other program: security software rightly distrusts scripts that walk installed packages.
     */
    private fun read(): List<Handler> {
        val root = "Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\CurrentVersion\\AppModel\\Repository\\Packages"
        val packages = WinReg.subKeys(WinReg.HKCU, root) ?: error("Can't read the installed packages")
        val folders = packages.mapNotNull { full -> WinReg.string(WinReg.HKCU, "$root\\$full", "PackageRootFolder")?.let { full to File(it) } }
            .filter { File(it.second, "AppxManifest.xml").isFile }
            // A package can be listed in several versions: keep the newest of each name.
            .groupBy { it.first.substringBefore('_') }.map { (_, v) -> v.maxBy { it.first } }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val guid = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        return folders.flatMap { (full, dir) ->
            runCatching {
                val manifest = File(dir, "AppxManifest.xml")
                if (!manifest.readText(Charsets.UTF_8).contains("FileExplorerContextMenus")) return@runCatching emptyList()
                val doc = factory.newDocumentBuilder().parse(manifest)
                val menus = doc.getElementsByTagNameNS("*", "FileExplorerContextMenus")
                (0 until menus.length).flatMap { i ->
                    val types = (menus.item(i) as org.w3c.dom.Element).getElementsByTagNameNS("*", "ItemType")
                    (0 until types.length).flatMap { j ->
                        val t = types.item(j) as org.w3c.dom.Element
                        val verbs = t.getElementsByTagNameNS("*", "Verb")
                        (0 until verbs.length).mapNotNull { k ->
                            val v = verbs.item(k) as org.w3c.dom.Element
                            val clsid = v.getAttribute("Clsid").trim('{', '}')
                            if (guid.matches(clsid)) Handler(full.substringBefore('_'), t.getAttribute("Type"), v.getAttribute("Id"), clsid) else null
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** Reads the registry directly (advapi32), without starting reg.exe or PowerShell. */
object WinReg {
    private val linker = Linker.nativeLinker()
    private val A = ValueLayout.ADDRESS
    private val I = ValueLayout.JAVA_INT
    private fun fn(name: String, desc: FunctionDescriptor) =
        linker.downcallHandle(SymbolLookup.libraryLookup("advapi32", Arena.global()).find(name).orElseThrow(), desc)
    private val openKey by lazy { fn("RegOpenKeyExW", FunctionDescriptor.of(I, A, A, I, I, A)) }
    private val enumKey by lazy { fn("RegEnumKeyExW", FunctionDescriptor.of(I, A, I, A, A, A, A, A, A)) }
    private val getValue by lazy { fn("RegGetValueW", FunctionDescriptor.of(I, A, A, A, I, A, A, A)) }
    private val closeKey by lazy { fn("RegCloseKey", FunctionDescriptor.of(I, A)) }

    /** HKEY_CURRENT_USER / HKEY_LOCAL_MACHINE: sign-extended handles, as Windows defines them. */
    val HKCU: MemorySegment = MemorySegment.ofAddress(0x80000001L.toInt().toLong())
    val HKLM: MemorySegment = MemorySegment.ofAddress(0x80000002L.toInt().toLong())

    /** A string value (REG_SZ / REG_EXPAND_SZ), or null. */
    fun string(root: MemorySegment, key: String, value: String): String? = Arena.ofConfined().use { a ->
        val size = a.allocate(I).also { it.set(I, 0, 8192) }
        val buf = a.allocate(8192)
        val r = getValue.invokeWithArguments(root, a.allocateFrom(key, Charsets.UTF_16LE), a.allocateFrom(value, Charsets.UTF_16LE), 0x2 or 0x4,
            MemorySegment.NULL, buf, size) as Int
        if (r != 0) null else buf.getString(0, Charsets.UTF_16LE)
    }

    /** The names of a key's sub-keys, or null when the key can't be opened. */
    fun subKeys(root: MemorySegment, key: String): List<String>? = Arena.ofConfined().use { a ->
        val ph = a.allocate(A)
        if (openKey.invokeWithArguments(root, a.allocateFrom(key, Charsets.UTF_16LE), 0, 0x20019, ph) as Int != 0) return null
        val h = ph.get(A, 0)
        try {
            val out = ArrayList<String>()
            val name = a.allocate(1024); val len = a.allocate(I)
            var i = 0
            while (true) {
                len.set(I, 0, 512)
                val r = enumKey.invokeWithArguments(h, i++, name, len, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as Int
                if (r == 259) break          // ERROR_NO_MORE_ITEMS
                if (r == 0) out += name.getString(0, Charsets.UTF_16LE)
            }
            out
        } finally { closeKey.invokeWithArguments(h) }
    }
}
