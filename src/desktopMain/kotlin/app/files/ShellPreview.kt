package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Windows' preview handlers (IPreviewHandler) — what Explorer's preview pane uses: PDF (Edge),
 * Word, Excel, PowerPoint, Outlook mail, fonts… whatever this PC has registered for a type.
 * The handler draws into a native window inside 快速預覽; it reads the file through a read-only stream.
 */
object ShellPreview {
    private val linker = Linker.nativeLinker()
    private val A = ValueLayout.ADDRESS
    private val I = ValueLayout.JAVA_INT
    private fun fn(lib: String, name: String, desc: FunctionDescriptor) =
        linker.downcallHandle(SymbolLookup.libraryLookup(lib, Arena.global()).find(name).orElseThrow(), desc)
    private val assocQuery by lazy { fn("shlwapi", "AssocQueryStringW", FunctionDescriptor.of(I, I, I, A, A, A, A)) }
    private val coInit by lazy { fn("ole32", "CoInitializeEx", FunctionDescriptor.of(I, A, I)) }
    private val coCreate by lazy { fn("ole32", "CoCreateInstance", FunctionDescriptor.of(I, A, A, I, A, A)) }
    private val streamOnFile by lazy { fn("shlwapi", "SHCreateStreamOnFileEx", FunctionDescriptor.of(I, A, I, I, I, A, A)) }
    private val itemFromPath by lazy { fn("shell32", "SHCreateItemFromParsingName", FunctionDescriptor.of(I, A, A, A, A)) }
    private val peek by lazy { fn("user32", "PeekMessageW", FunctionDescriptor.of(I, A, A, I, I, I)) }
    private val translate by lazy { fn("user32", "TranslateMessage", FunctionDescriptor.of(I, A)) }
    private val dispatch by lazy { fn("user32", "DispatchMessageW", FunctionDescriptor.of(ValueLayout.JAVA_LONG, A)) }

    private const val IID_IPreviewHandler = "8895B1C6-B41F-4C1C-A562-0D564250836F"
    private const val IID_IInitializeWithStream = "B824B49D-22AC-4161-AC8A-9916E8FA3F7F"
    private const val IID_IInitializeWithFile = "B7D14566-0509-4CCE-A71F-0A554233BD9B"
    private const val IID_IInitializeWithItem = "7F73BE3F-FB79-493C-A6C7-7EE14E245841"
    private const val IID_IShellItem = "43826D1E-E718-42EE-BC55-A1E261C37BFE"

    /** Types this app previews itself (better, or without a separate process): never handed to Windows. */
    private val ownTypes = setOf("txt", "md", "csv", "tsv", "json", "xml", "log", "ini", "xlsx", "xlsm", "xls")

    private fun guid(a: Arena, s: String): MemorySegment {
        val h = s.trim('{', '}').replace("-", "")
        val m = a.allocate(16)
        m.set(ValueLayout.JAVA_INT_UNALIGNED, 0, h.substring(0, 8).toLong(16).toInt())
        m.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, h.substring(8, 12).toInt(16).toShort())
        m.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, h.substring(12, 16).toInt(16).toShort())
        for (k in 0 until 8) m.set(ValueLayout.JAVA_BYTE, 8L + k, h.substring(16 + k * 2, 18 + k * 2).toInt(16).toByte())
        return m
    }
    private fun com(obj: MemorySegment, index: Int, desc: FunctionDescriptor, vararg args: Any?): Any? {
        val vtbl = obj.reinterpret(8).get(A, 0).reinterpret(8L * (index + 1))
        return linker.downcallHandle(vtbl.getAtIndex(A, index.toLong()), desc).invokeWithArguments(obj, *args)
    }
    private fun release(o: MemorySegment?) { if (o != null && o.address() != 0L) runCatching { com(o, 2, FunctionDescriptor.of(I, A)) } }
    private fun qi(o: MemorySegment, iid: String, a: Arena): MemorySegment? {
        val pp = a.allocate(A)
        return if (com(o, 0, FunctionDescriptor.of(I, A, A, A), guid(a, iid), pp) as Int == 0) pp.get(A, 0) else null
    }

    /** Adobe Reader / Acrobat's PDF preview handler: slow to start, and the app can stop responding meanwhile. */
    private const val CLSID_ADOBE_PDF = "DC6EFB56-9CFA-464D-8880-44885D7DC193"
    fun isAdobe(clsid: String) = clsid.trim('{', '}').equals(CLSID_ADOBE_PDF, ignoreCase = true)

    /** The preview handler registered for [ext] ("pdf"), or null. */
    fun handlerFor(ext: String): String? {
        if (ext.isEmpty() || ext.lowercase() in ownTypes) return null
        return runCatching {
            Arena.ofConfined().use { a ->
                val buf = a.allocate(512); val cch = a.allocate(I).also { it.set(I, 0, 256) }
                // ASSOCSTR_SHELLEXTENSION (16): the handler for the preview-handler shell extension point.
                val hr = assocQuery.invokeWithArguments(0, 16, a.allocateFrom(".$ext", Charsets.UTF_16LE), a.allocateFrom("{$IID_IPreviewHandler}", Charsets.UTF_16LE), buf, cch) as Int
                if (hr == 0) buf.getString(0, Charsets.UTF_16LE).trim().takeIf { it.startsWith("{") } else null
            }
        }.getOrNull()
    }

    // Handlers run on one single-threaded apartment with a message loop, like Explorer's.
    private class Worker : Thread("preview-handler") {
        val queue = LinkedBlockingQueue<Runnable>()
        init { isDaemon = true }
        override fun run() {
            runCatching { coInit.invokeWithArguments(MemorySegment.NULL, 2) }
            Arena.ofConfined().use { a ->
                val msg = a.allocate(48)
                while (true) {
                    queue.poll(15, TimeUnit.MILLISECONDS)?.let { r -> runCatching { r.run() } }
                    while ((peek.invokeWithArguments(msg, MemorySegment.NULL, 0, 0, 1) as Int) != 0) { translate.invokeWithArguments(msg); dispatch.invokeWithArguments(msg) }
                }
            }
        }
    }
    @Volatile private var worker: Worker? = null
    private fun <T> on(timeoutSec: Long = 15, block: () -> T): T {
        val w = worker ?: synchronized(this) { worker ?: Worker().also { it.start(); worker = it } }
        val f = CompletableFuture<T>()
        w.queue.put { try { f.complete(block()) } catch (e: Throwable) { f.completeExceptionally(e) } }
        return try { f.get(timeoutSec, TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
            synchronized(this) { if (worker === w) worker = null }      // stuck in a handler: the next preview gets a new thread
            throw IllegalStateException(tr("The preview didn't answer in time.", "預覽沒有回應。"))
        } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
    }

    /** One open preview. */
    class Handle internal constructor(private var handler: MemorySegment?, private var source: MemorySegment?) {
        fun resize(w: Int, h: Int) = runCatching { on(5) { handler?.let { x -> Arena.ofConfined().use { a -> com(x, 4, FunctionDescriptor.of(I, A, A), rect(a, w, h)) } } } }
        fun close() = runCatching {
            on(5) {
                handler?.let { x -> runCatching { com(x, 6, FunctionDescriptor.of(I, A)) }; release(x) }     // Unload
                release(source); handler = null; source = null
            }
        }
    }
    private fun rect(a: Arena, w: Int, h: Int) = a.allocate(16).also { it.set(I, 0, 0); it.set(I, 4, 0); it.set(I, 8, w); it.set(I, 12, h) }

    /** Shows [path] with handler [clsid] in [hwnd] (w × h pixels). Throws with a reason when it can't. */
    fun open(path: String, clsid: String, hwnd: Long, w: Int, h: Int): Handle = on {
        Arena.ofConfined().use { a ->
            val pp = a.allocate(A)
            // Explorer asks for a separate process (prevhost) first: a crashing handler can't take the app down.
            var hr = coCreate.invokeWithArguments(guid(a, clsid), MemorySegment.NULL, 0x4, guid(a, IID_IPreviewHandler), pp) as Int
            if (hr != 0) hr = coCreate.invokeWithArguments(guid(a, clsid), MemorySegment.NULL, 0x1, guid(a, IID_IPreviewHandler), pp) as Int
            check(hr == 0) { tr("The preview handler couldn't start (0x%08X).", "無法啟動預覽處理常式（0x%08X）。").format(hr) }
            val handler = pp.get(A, 0)
            var source: MemorySegment? = null
            try {
                // Read-only stream first (what Explorer prefers), then a path, then a shell item.
                val ok = qi(handler, IID_IInitializeWithStream, a)?.let { iws ->
                    val sp = a.allocate(A)
                    val made = streamOnFile.invokeWithArguments(a.allocateFrom(path, Charsets.UTF_16LE), 0x40, 0x80, 0, MemorySegment.NULL, sp) as Int == 0   // STGM_READ | STGM_SHARE_DENY_NONE
                    val r = made && (com(iws, 3, FunctionDescriptor.of(I, A, A, I), sp.get(A, 0), 0) as Int == 0)
                    if (made) source = sp.get(A, 0)
                    release(iws); r
                } == true || qi(handler, IID_IInitializeWithFile, a)?.let { iwf ->
                    val r = com(iwf, 3, FunctionDescriptor.of(I, A, A, I), a.allocateFrom(path, Charsets.UTF_16LE), 0) as Int == 0
                    release(iwf); r
                } == true || qi(handler, IID_IInitializeWithItem, a)?.let { iwi ->
                    val ip = a.allocate(A)
                    val r = itemFromPath.invokeWithArguments(a.allocateFrom(path, Charsets.UTF_16LE), MemorySegment.NULL, guid(a, IID_IShellItem), ip) as Int == 0 &&
                        (com(iwi, 3, FunctionDescriptor.of(I, A, A, I), ip.get(A, 0), 0) as Int == 0).also { release(ip.get(A, 0)) }
                    release(iwi); r
                } == true
                check(ok) { tr("The preview handler couldn't read this file.", "預覽處理常式無法讀取這個檔案。") }
                check(com(handler, 3, FunctionDescriptor.of(I, A, A, A), MemorySegment.ofAddress(hwnd), rect(a, w, h)) as Int == 0) { "SetWindow" }   // SetWindow
                val d = com(handler, 5, FunctionDescriptor.of(I, A)) as Int                                                                              // DoPreview
                check(d == 0) { tr("The preview failed (0x%08X).", "預覽失敗（0x%08X）。").format(d) }
                Handle(handler, source)
            } catch (e: Throwable) { release(source); runCatching { com(handler, 6, FunctionDescriptor.of(I, A)) }; release(handler); throw e }
        }
    }
}

/**
 * The component's size in physical pixels. AWT sizes are in logical (DPI-scaled) units, but the
 * preview handler's SetWindow / SetRect take the native window's real pixels: at 200% scaling,
 * passing [java.awt.Component.getWidth] would draw the preview at half size in the corner.
 */
private fun java.awt.Component.devicePixels(): Pair<Int, Int> {
    val t = graphicsConfiguration?.defaultTransform
    val sx = t?.scaleX ?: 1.0; val sy = t?.scaleY ?: 1.0
    return Math.round(width * sx).toInt() to Math.round(height * sy).toInt()
}

/** 快速預覽 through Windows' own preview handler (PDF, Office documents…), with a way out to the default app. */
@Composable
fun ShellPreviewView(path: String, clsid: String, translucent: Boolean, openExternally: () -> Unit) {
    val p = LocalPalette.current
    var error by remember(path) { mutableStateOf<String?>(if (translucent) tr("Documents can't be shown in a see-through window.", "半透明視窗中無法顯示這類文件。") else null) }
    var handle by remember(path) { mutableStateOf<ShellPreview.Handle?>(null) }
    val canvas = remember(path) { if (translucent) null else VideoCanvas() }
    LaunchedEffect(path) {
        val c = canvas ?: return@LaunchedEffect
        var hwnd: Long? = null
        repeat(150) { if (hwnd == null || hwnd == 0L || c.width < 10) { if (c.isDisplayable) hwnd = WindowCorners.hwnd(c); delay(20.milliseconds) } }
        val h = hwnd ?: 0L
        if (h == 0L) { error = tr("No surface to draw the preview on.", "沒有可以顯示預覽的區域。"); return@LaunchedEffect }
        handle = withContext(Dispatchers.IO) {
            runCatching { val (w, ht) = c.devicePixels(); ShellPreview.open(path, clsid, h, w, ht) }.onFailure { e ->
                ErrorLog.record(e, "preview handler $clsid")
                error = e.message ?: tr("Can't preview this file.", "無法預覽這個檔案。")
            }.getOrNull()
        }
    }
    DisposableEffect(path) { onDispose { val x = handle; handle = null; if (x != null) Thread { x.close() }.start() } }
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        val e = error
        if (e != null || canvas == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(e ?: "", size = 13.sp, color = p.textSecondary, maxLines = 3)
            Spacer(Modifier.height(10.dp))
            DialogButton(S.qlOpen, primary = true) { openExternally() }
        } else Column(Modifier.fillMaxSize()) {
            // Adobe's handler is slow to start and can freeze the app meanwhile: say so, and what to change.
            if (ShellPreview.isAdobe(clsid)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp)).background(p.card)
                        .border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = Color(0xFFC98500))
                    Spacer(Modifier.width(10.dp))
                    T(S.qlAdobeSlow, Modifier.weight(1f), size = 12.sp, maxLines = 3)
                }
            }
            SwingPanel(factory = {
                canvas.apply {
                    addComponentListener(object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(ev: java.awt.event.ComponentEvent?) { handle?.let { x -> val (w, h) = devicePixels(); Thread { x.resize(w, h) }.start() } }
                    })
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}
