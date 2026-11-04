package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
 * Plays video and audio with Windows' own Media Foundation (MFPlay: the engine behind Windows'
 * players), drawn into a native window handle. Formats Windows can play here: MP4 / MOV / M4V
 * (H.264, HEVC with its extension), WMV, AVI, MKV, WebM (VP9 / AV1 with their extensions), MP3,
 * AAC / M4A, WAV, FLAC, WMA, Opus… What it can't play goes to the default player.
 */
object MediaEngine {
    private val linker = Linker.nativeLinker()
    private val A = ValueLayout.ADDRESS
    private val I = ValueLayout.JAVA_INT
    private val F = ValueLayout.JAVA_FLOAT
    private fun fn(lib: String, name: String, desc: FunctionDescriptor) =
        linker.downcallHandle(SymbolLookup.libraryLookup(lib, Arena.global()).find(name).orElseThrow(), desc)
    private val coInit by lazy { fn("ole32", "CoInitializeEx", FunctionDescriptor.of(I, A, I)) }
    private val mfStartup by lazy { fn("mfplat", "MFStartup", FunctionDescriptor.of(I, I, I)) }
    private val create by lazy { fn("mfplay", "MFPCreateMediaPlayer", FunctionDescriptor.of(I, A, I, I, A, A, A)) }
    private val peek by lazy { fn("user32", "PeekMessageW", FunctionDescriptor.of(I, A, A, I, I, I)) }
    private val translate by lazy { fn("user32", "TranslateMessage", FunctionDescriptor.of(I, A)) }
    private val dispatch by lazy { fn("user32", "DispatchMessageW", FunctionDescriptor.of(ValueLayout.JAVA_LONG, A)) }

    private fun com(obj: MemorySegment, index: Int, desc: FunctionDescriptor, vararg args: Any?): Any? {
        val vtbl = obj.reinterpret(8).get(A, 0).reinterpret(8L * (index + 1))
        return linker.downcallHandle(vtbl.getAtIndex(A, index.toLong()), desc).invokeWithArguments(obj, *args)
    }

    // One thread with a message loop owns every player (MFPlay posts its work to its creating thread).
    private class Worker : Thread("media") {
        val queue = LinkedBlockingQueue<Runnable>()
        init { isDaemon = true }
        override fun run() {
            runCatching { coInit.invokeWithArguments(MemorySegment.NULL, 2) }       // COINIT_APARTMENTTHREADED
            runCatching { mfStartup.invokeWithArguments(0x00020070, 0) }            // MF_VERSION, MFSTARTUP_FULL
            Arena.ofConfined().use { a ->
                val msg = a.allocate(48)
                while (true) {
                    queue.poll(15, TimeUnit.MILLISECONDS)?.let { r -> runCatching { r.run() } }
                    while ((peek.invokeWithArguments(msg, MemorySegment.NULL, 0, 0, 1) as Int) != 0) {
                        translate.invokeWithArguments(msg); dispatch.invokeWithArguments(msg)
                    }
                }
            }
        }
    }
    private val worker by lazy { Worker().also { it.start() } }
    private fun <T> on(block: () -> T): T {
        val f = CompletableFuture<T>()
        worker.queue.put { try { f.complete(block()) } catch (e: Throwable) { f.completeExceptionally(e) } }
        return try { f.get(5, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
    }

    enum class State { Empty, Stopped, Playing, Paused, Closed }

    /** Off in screenshot runs: nothing plays (no sound from the speakers while making pictures). */
    @Volatile var enabled = true

    /** One file being played. All calls are safe from any thread. */
    class Player internal constructor(private var p: MemorySegment?) {
        private fun call(index: Int, desc: FunctionDescriptor, vararg args: Any?): Int =
            on { val x = p ?: return@on -1; com(x, index, desc, *args) as Int }

        fun play() { call(3, FunctionDescriptor.of(I, A)) }
        fun pause() { call(4, FunctionDescriptor.of(I, A)) }
        /** Jumps to [seconds]. */
        fun seek(seconds: Double) = on {
            val x = p ?: return@on
            Arena.ofConfined().use { a ->
                val pv = a.allocate(24); pv.set(ValueLayout.JAVA_SHORT, 0, 20); pv.set(ValueLayout.JAVA_LONG, 8, (seconds * 1e7).toLong())   // VT_I8, 100-ns units
                com(x, 7, FunctionDescriptor.of(I, A, A, A), a.allocate(16), pv)                                                             // GUID_NULL = 100 ns
            }
        }
        private fun time(index: Int): Double = on {
            val x = p ?: return@on 0.0
            Arena.ofConfined().use { a ->
                val pv = a.allocate(24)
                if (com(x, index, FunctionDescriptor.of(I, A, A, A), a.allocate(16), pv) as Int == 0) pv.get(ValueLayout.JAVA_LONG, 8) / 1e7 else 0.0
            }
        }
        /** Seconds played and the length (0 while not known yet). */
        fun position() = time(8)
        fun duration() = time(9)
        fun state(): State = on {
            val x = p ?: return@on State.Closed
            Arena.ofConfined().use { a -> val st = a.allocate(I); com(x, 13, FunctionDescriptor.of(I, A, A), st); State.entries.getOrElse(st.get(I, 0)) { State.Empty } }
        }
        fun volume(v: Float) { call(20, FunctionDescriptor.of(I, A, F), v.coerceIn(0f, 1f)) }
        fun mute(on: Boolean) { call(24, FunctionDescriptor.of(I, A, I), if (on) 1 else 0) }
        /** The video size in pixels, or null for audio / not known yet. */
        fun videoSize(): Pair<Int, Int>? = on {
            val x = p ?: return@on null
            Arena.ofConfined().use { a ->
                val v = a.allocate(8); val ar = a.allocate(8)
                if (com(x, 25, FunctionDescriptor.of(I, A, A, A), v, ar) as Int == 0 && v.get(I, 0) > 0) v.get(I, 0) to v.get(I, 4) else null
            }
        }
        /** Redraws the video (after the window it's drawn in changed size). */
        fun updateVideo() { call(32, FunctionDescriptor.of(I, A)) }
        /** Stops and frees it. */
        fun close() = on {
            val x = p ?: return@on
            p = null
            runCatching { com(x, 37, FunctionDescriptor.of(I, A)) }    // Shutdown
            runCatching { com(x, 2, FunctionDescriptor.of(I, A)) }     // Release
        }
    }

    /** Opens [path] and starts playing it, drawing video into [hwnd] (0 for sound only). Null when Windows can't open it. */
    fun open(path: String, hwnd: Long, autoPlay: Boolean = true): Player? = if (!enabled) null else runCatching {
        on {
            Arena.ofConfined().use { a ->
                val pp = a.allocate(A)
                val hr = create.invokeWithArguments(a.allocateFrom(path, Charsets.UTF_16LE), if (autoPlay) 1 else 0, 0, MemorySegment.NULL, MemorySegment.ofAddress(hwnd), pp) as Int
                if (hr != 0) null else Player(pp.get(A, 0)).also { pl ->
                    // A black frame around the picture instead of the window's own colour.
                    runCatching { com(pp.get(A, 0), 33, FunctionDescriptor.of(I, A, I), 0) }
                }
            }
        }
    }.onFailure { ErrorLog.record(it, "media: open") }.getOrNull()
}

/** A heavyweight surface that leaves its pixels to the video renderer (no AWT painting over it). */
private class VideoCanvas : java.awt.Canvas() {
    init { background = java.awt.Color.BLACK; ignoreRepaint = true }
    override fun paint(g: java.awt.Graphics?) {}
    override fun update(g: java.awt.Graphics?) {}
}

private fun clock(sec: Double): String {
    val t = sec.toLong().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * 快速預覽 of a video or song: plays here (Media Foundation) with play / pause, a seek bar, time and
 * volume. When Windows can't play the format here (or the window is see-through, where native video
 * can't be drawn), it shows the poster and hands the file to the default player.
 */
@Composable
fun MediaPlayerView(path: String, video: Boolean, poster: androidx.compose.ui.graphics.ImageBitmap?, item: FileItem?, translucent: Boolean, openExternally: () -> Unit) {
    val p = LocalPalette.current
    var player by remember(path) { mutableStateOf<MediaEngine.Player?>(null) }
    var failed by remember(path) { mutableStateOf(video && translucent) }
    var pos by remember(path) { mutableStateOf(0.0) }
    var len by remember(path) { mutableStateOf(0.0) }
    var playing by remember(path) { mutableStateOf(false) }
    var dragging by remember(path) { mutableStateOf<Float?>(null) }
    var vol by remember { mutableStateOf(Prefs.str("mediaVolume", "0.8").toFloatOrNull() ?: 0.8f) }
    var muted by remember { mutableStateOf(false) }
    val canvas = remember(path) { if (video && !translucent) VideoCanvas() else null }

    // Open once the surface has a window handle; close when the preview goes away.
    LaunchedEffect(path) {
        if (failed) return@LaunchedEffect
        val hwnd = if (canvas == null) 0L else {
            var h: Long? = null
            repeat(100) { if (h == null || h == 0L) { if (canvas.isDisplayable) h = WindowCorners.hwnd(canvas); if (h == null || h == 0L) delay(20.milliseconds) } }
            h ?: 0L
        }
        if (canvas != null && hwnd == 0L) { failed = true; return@LaunchedEffect }
        val pl = withContext(Dispatchers.IO) { MediaEngine.open(path, hwnd) }
        if (pl == null) { failed = true; return@LaunchedEffect }
        player = pl
        withContext(Dispatchers.IO) { pl.volume(vol) }
        // Follow the position; give up if nothing starts (a format Windows can't play here).
        var waited = 0
        while (true) {
            delay(250.milliseconds)
            val st = withContext(Dispatchers.IO) { pl.state() }
            if (st == MediaEngine.State.Closed) break
            playing = st == MediaEngine.State.Playing
            len = withContext(Dispatchers.IO) { pl.duration() }
            if (dragging == null) pos = withContext(Dispatchers.IO) { pl.position() }
            if (len <= 0.0 && st != MediaEngine.State.Playing) { waited += 250; if (waited > 4000) { failed = true; withContext(Dispatchers.IO) { pl.close() }; player = null; break } }
        }
    }
    DisposableEffect(path) { onDispose { val pl = player; player = null; if (pl != null) Thread { pl.close() }.start() } }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(if (video) Color.Black else Color.Transparent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            when {
                failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (poster != null) Image(poster, null, Modifier.weight(1f, fill = false).fillMaxWidth(), contentScale = ContentScale.Fit)
                    else if (item != null) FileIcon(item, 120.dp)
                    Spacer(Modifier.height(10.dp))
                    T(if (video && translucent) tr("Video can't be shown in a see-through window.", "半透明視窗中無法顯示影片。")
                        else tr("Windows can't play this format here.", "Windows 無法在這裡播放這個格式。"), size = 13.sp, color = if (poster != null) Color.White else p.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    DialogButton(tr("Play in the default app", "以預設應用程式播放"), primary = true) { openExternally() }
                }
                canvas != null -> SwingPanel(factory = {
                    canvas.apply {
                        addComponentListener(object : java.awt.event.ComponentAdapter() {
                            override fun componentResized(e: java.awt.event.ComponentEvent?) { player?.let { pl -> Thread { pl.updateVideo() }.start() } }
                        })
                    }
                }, modifier = Modifier.fillMaxSize())
                else -> {
                    // Sound only: the cover (or icon) while it plays.
                    if (poster != null) Image(poster, null, Modifier.fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit)
                    else if (item != null) FileIcon(item, 140.dp)
                }
            }
        }
        if (!failed) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) tr("Pause", "暫停") else tr("Play", "播放"), {
                val pl = player ?: return@IconBtn
                Thread { if (playing) pl.pause() else pl.play() }.start(); playing = !playing
            }, enabled = player != null)
            T(clock(dragging?.toDouble() ?: pos), size = 12.sp, color = p.textSecondary)
            BoxWithConstraints(Modifier.weight(1f)) {
                FluentSlider((dragging ?: pos.toFloat()).coerceIn(0f, len.toFloat().coerceAtLeast(0.01f)), { v ->
                    dragging = v
                }, width = maxWidth, range = 0f..len.toFloat().coerceAtLeast(0.01f), onChangeFinished = {
                    val to = dragging; dragging = null
                    val pl = player
                    if (to != null && pl != null) { pos = to.toDouble(); Thread { pl.seek(to.toDouble()) }.start() }
                })
            }
            T(if (len > 0) clock(len) else "–:––", size = 12.sp, color = p.textSecondary)
            IconBtn(if (muted || vol == 0f) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, tr("Mute", "靜音"), {
                muted = !muted; val pl = player; if (pl != null) Thread { pl.mute(muted) }.start()
            })
            FluentSlider(vol, { v -> vol = v; muted = false; val pl = player; if (pl != null) Thread { pl.mute(false); pl.volume(v) }.start() }, width = 90.dp,
                onChangeFinished = { Prefs.put(mapOf("mediaVolume" to vol)) })
            IconBtn(Icons.Outlined.OpenInNew, tr("Play in the default app", "以預設應用程式播放"), { openExternally() })
        }
    }
}
