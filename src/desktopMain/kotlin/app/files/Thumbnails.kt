package app.files

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

/**
 * Thumbnails for pictures and videos, made by Windows itself (the same shell thumbnail
 * Explorer shows, via IShellItemImageFactory with "thumbnail only"), so videos get a frame
 * and photos keep their rotation. The original asks the shell the same way
 * (FileThumbnailHelper / IconService). Work happens in small helper processes, newest
 * request first, and results are cached in memory and on disk.
 */
object Thumbnails {
    val videoExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts", "m2ts", "flv")
    val imageExt = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "ico", "jfif", "avif", "raw", "cr2", "nef", "arw", "dng", "psd", "svg")
    private val otherExt = setOf("pdf")

    fun supports(item: FileItem) = !item.isDir && (item.ext in imageExt || item.ext in videoExt || item.ext in otherExt)

    /** Two sizes keep the cache small: small views and large (grid / preview) views. */
    fun bucketFor(px: Int) = if (px <= 64) M.THUMB_SMALL else M.THUMB_LARGE

    private val cacheDir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "FilesKmp\\thumbs").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > M.THUMB_MEMORY_ITEMS
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    private val queue = LinkedBlockingDeque<Request>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    private class Request(val key: String, val path: String, val px: Int, val out: File)

    private fun keyOf(item: FileItem, px: Int): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("${item.path.lowercase()}|${item.size}|${item.modified}|$px".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun busy(): Boolean = waiting.isNotEmpty()

    fun cached(item: FileItem, px: Int): ImageBitmap? = synchronized(memory) { memory[keyOf(item, bucketFor(px))] }

    /** The thumbnail, or null when Windows has none for this file (then the file icon is shown). */
    suspend fun get(item: FileItem, px: Int): ImageBitmap? {
        val size = bucketFor(px)
        val key = keyOf(item, size)
        synchronized(memory) { memory[key] }?.let { return it }
        if (key in failed) return null
        val out = File(cacheDir, "$key.png")
        if (out.exists()) {
            decode(out)?.let { bmp -> synchronized(memory) { memory[key] = bmp }; return bmp }
        }
        ensureStarted()
        val d = waiting.getOrPut(key) {
            CompletableDeferred<ImageBitmap?>().also { queue.addFirst(Request(key, item.path, size, out)) }  // newest first
        }
        return try {
            d.await()
        } catch (e: CancellationException) {
            // Scrolled away: drop the request if nobody else waits for it.
            if (!d.isCompleted) { queue.removeIf { it.key == key }; waiting.remove(key) }
            throw e
        }
    }

    private fun decode(f: File): ImageBitmap? = try {
        org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap()
    } catch (_: Exception) { null }

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        started = true
        repeat(M.THUMB_WORKERS) { scope.launch { worker() } }
    }

    private fun finish(r: Request, bmp: ImageBitmap?) {
        if (bmp != null) synchronized(memory) { memory[r.key] = bmp } else failed += r.key
        waiting.remove(r.key)?.complete(bmp)
    }

    /** One helper process per worker; restarted if it dies. Falls back to decoding pictures directly. */
    private suspend fun worker() {
        var helper: Helper? = null
        while (currentCoroutineContext().isActive) {
            val r = runInterruptible { queue.takeFirst() }
            if (!waiting.containsKey(r.key)) continue
            if (helper == null || !helper.alive()) helper = Helper.start()
            val ok = helper?.make(r.path, r.px, r.out) ?: false
            val bmp = if (ok && r.out.exists()) decode(r.out) else fallback(r)
            finish(r, bmp)
        }
    }

    /** Without the shell helper, pictures can still be shrunk directly. */
    private fun fallback(r: Request): ImageBitmap? {
        val ext = r.path.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")) return null
        return try {
            val img = org.jetbrains.skia.Image.makeFromEncoded(File(r.path).readBytes())
            val scale = r.px.toFloat() / maxOf(img.width, img.height)
            if (scale >= 1f) return img.toComposeImageBitmap()
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
            val small = surface.makeImageSnapshot()
            small.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes?.let { r.out.writeBytes(it) }
            small.toComposeImageBitmap()
        } catch (_: Throwable) { null }
    }

    /** A PowerShell process that asks the Windows shell for thumbnails, one request per line. */
    private class Helper(val p: Process, val inp: BufferedWriter, val out: BufferedReader) {
        fun alive() = p.isAlive

        fun make(path: String, px: Int, dest: File): Boolean = try {
            inp.write("$path\t$px\t${dest.path}\n"); inp.flush()
            val line = out.readLine() ?: ""
            line.startsWith("OK")
        } catch (_: Exception) { false }

        companion object {
            private val SCRIPT = """
[Console]::InputEncoding = [Text.Encoding]::UTF8
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Drawing;
using System.Drawing.Imaging;
[StructLayout(LayoutKind.Sequential)] public struct ThumbSize { public int cx; public int cy; }
[ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IShellItemImageFactory { [PreserveSig] int GetImage(ThumbSize size, int flags, out IntPtr phbm); }
public static class ShellThumb {
  [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
  static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid riid, [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory ppv);
  [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);
  public static bool Save(string path, int px, string dest) {
    IShellItemImageFactory f = null;
    try {
      SHCreateItemFromParsingName(path, IntPtr.Zero, typeof(IShellItemImageFactory).GUID, out f);
      IntPtr hbm;
      // SIIGBF_THUMBNAILONLY (0x8) | SIIGBF_BIGGERSIZEOK (0x1): a real thumbnail or nothing.
      int hr = f.GetImage(new ThumbSize { cx = px, cy = px }, 0x8 | 0x1, out hbm);
      if (hr != 0 || hbm == IntPtr.Zero) return false;
      try { using (Bitmap b = Image.FromHbitmap(hbm)) { b.Save(dest, ImageFormat.Png); } }
      finally { DeleteObject(hbm); }
      return true;
    } catch { return false; }
    finally { if (f != null) Marshal.ReleaseComObject(f); }
  }
}
'@
while (§true) {
  §line = [Console]::In.ReadLine()
  if (§line -eq §null) { break }
  §p = §line.Split("`t")
  §ok = §false
  try { §ok = [ShellThumb]::Save(§p[0], [int]§p[1], §p[2]) } catch { }
  if (§ok) { [Console]::Out.WriteLine("OK") } else { [Console]::Out.WriteLine("NO") }
  [Console]::Out.Flush()
}
""".trimIndent().replace('§', '$')

            fun start(): Helper? = try {
                val encoded = java.util.Base64.getEncoder().encodeToString(SCRIPT.toByteArray(StandardCharsets.UTF_16LE))
                val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                Helper(
                    p,
                    p.outputStream.bufferedWriter(StandardCharsets.UTF_8),
                    p.inputStream.bufferedReader(StandardCharsets.UTF_8),
                )
            } catch (_: Exception) { null }
        }
    }
}

/** Compose helper: the thumbnail for [item] at about [px] pixels, loaded in the background. */
@Composable
fun rememberThumbnail(item: FileItem, px: Int, enabled: Boolean): ImageBitmap? {
    if (!enabled || !Thumbnails.supports(item)) return null
    val initial = remember(item.path, item.modified, px) { Thumbnails.cached(item, px) }
    val state = produceState(initial, item.path, item.modified, item.size, px) {
        if (value == null) value = Thumbnails.get(item, px)
    }
    return state.value
}
