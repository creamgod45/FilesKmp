package app.files

import java.awt.Component
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Windows 11 draws rounded corners only for windows with a normal frame. The app window is
 * undecorated, so ask DWM for the standard rounded corners (and its thin border) explicitly.
 * Uses the JDK's own foreign-function API; on Windows 10 DWM ignores the request.
 * Needs the JVM options set in build.gradle.kts (--add-exports for the HWND, native access).
 */
object WindowCorners {
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    fun round(window: Window) {
        try {
            val hwnd = hwndOf(window) ?: return
            Arena.ofConfined().use { arena ->
                val dwm = SymbolLookup.libraryLookup("dwmapi", arena)
                val setAttribute = Linker.nativeLinker().downcallHandle(
                    dwm.find("DwmSetWindowAttribute").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                )
                val preference = arena.allocate(ValueLayout.JAVA_INT)
                preference.set(ValueLayout.JAVA_INT, 0, DWMWCP_ROUND)
                setAttribute.invokeWithArguments(MemorySegment.ofAddress(hwnd), DWMWA_WINDOW_CORNER_PREFERENCE, preference, 4)
            }
        } catch (_: Throwable) {
            // Not Windows 11, or the JVM options are missing: keep square corners.
        }
    }

    /** The native window handle, through AWT's internal accessor (exported by a JVM option). */
    fun hwnd(window: Window): Long? = runCatching { hwndOf(window) }.getOrNull()
    /** The native handle of a heavyweight component (a Canvas): where native video is drawn. */
    fun hwnd(component: Component): Long? = runCatching { hwndOf(component) }.getOrNull()

    private fun hwndOf(window: Component): Long? {
        val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", Component::class.java).invoke(accessor, window) ?: return null
        return Class.forName("sun.awt.windows.WComponentPeer").getMethod("getHWnd").invoke(peer) as? Long
    }
}

/**
 * An undecorated window maximizes over the whole monitor, covering the taskbar. AWT passes
 * Frame.maximizedBounds to Windows (WM_GETMINMAXINFO), so keep it at the work area of the monitor
 * the window is on: relative to that monitor, in AWT's scaled units. Refreshed when the window moves
 * (another monitor), changes state, or comes back to the front (the taskbar may have moved).
 */
object WindowWorkArea {
    /** The work area for a window on [gc], as maximizedBounds wants it. */
    fun boundsFor(gc: java.awt.GraphicsConfiguration): java.awt.Rectangle {
        val b = gc.bounds
        val i = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return java.awt.Rectangle(i.left, i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    /**
     * How much the JDK scales maximizedBounds on the way to Windows, per display scale. The JBR in use
     * scales them twice on a HiDPI monitor (at 200% a 1470-wide work area became a 2940-wide window), so
     * the first guess is the display scale; after each maximize the real size corrects it.
     */
    private val extraScale = HashMap<Double, Double>()
    private val corrected = HashSet<Double>()

    fun install(frame: java.awt.Frame) {
        fun update() {
            val gc = frame.graphicsConfiguration ?: return
            val want = boundsFor(gc)
            val f = extraScale.getOrPut(gc.defaultTransform.scaleX) { gc.defaultTransform.scaleX }
            val r = java.awt.Rectangle((want.x / f).toInt(), (want.y / f).toInt(), (want.width / f).toInt(), (want.height / f).toInt())
            if (frame.maximizedBounds == r) return
            frame.maximizedBounds = r
            // Already maximized to the old area (taskbar moved, other monitor): maximize again to the new one.
            if (frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH == java.awt.Frame.MAXIMIZED_BOTH) {
                val s = frame.extendedState
                frame.extendedState = java.awt.Frame.NORMAL
                frame.extendedState = s
            }
        }
        update()
        frame.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) = update()
            override fun componentResized(e: java.awt.event.ComponentEvent) = update()
        })
        frame.addWindowStateListener { e ->
            update()
            // Check the guess once the window is maximized: wrong size → learn the real factor and maximize again.
            if (e.newState and java.awt.Frame.MAXIMIZED_BOTH == java.awt.Frame.MAXIMIZED_BOTH) javax.swing.Timer(150) {
                val gc = frame.graphicsConfiguration ?: return@Timer
                val sent = frame.maximizedBounds ?: return@Timer
                val got = frame.bounds
                val want = boundsFor(gc)
                if (sent.width <= 0 || kotlin.math.abs(got.width - want.width) <= 2 && kotlin.math.abs(got.height - want.height) <= 2) return@Timer
                val observed = got.width.toDouble() / sent.width
                // Learn once per display scale, so a size Windows clamps can't make it loop.
                if (observed !in 0.25..8.0 || !corrected.add(gc.defaultTransform.scaleX)) return@Timer
                extraScale[gc.defaultTransform.scaleX] = observed
                update()
            }.apply { isRepeats = false; start() }
        }
        frame.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent) = update()
        })
    }
}

/**
 * 模糊背後的內容 for the see-through window: asks DWM (SetWindowCompositionAttribute, the call
 * Windows' own shell uses) to blur what is behind the window. Off again with [on] = false.
 */
object WindowBlur {
    private const val WCA_ACCENT_POLICY = 19
    private const val ACCENT_DISABLED = 0
    private const val ACCENT_ENABLE_BLURBEHIND = 3

    fun set(window: Window, on: Boolean) {
        try {
            val hwnd = WindowCorners.hwnd(window) ?: return
            Arena.ofConfined().use { arena ->
                val user32 = SymbolLookup.libraryLookup("user32", arena)
                val fn = Linker.nativeLinker().downcallHandle(
                    user32.find("SetWindowCompositionAttribute").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
                // ACCENT_POLICY { int AccentState, AccentFlags, GradientColor, AnimationId }
                val policy = arena.allocate(16)
                policy.set(ValueLayout.JAVA_INT, 0, if (on) ACCENT_ENABLE_BLURBEHIND else ACCENT_DISABLED)
                // WINDOWCOMPOSITIONATTRIBDATA on x64 { int Attrib; (pad); void* pvData; SIZE_T cbData } = 24 bytes
                val data = arena.allocate(24)
                data.set(ValueLayout.JAVA_INT, 0, WCA_ACCENT_POLICY)
                data.set(ValueLayout.ADDRESS, 8, policy)
                data.set(ValueLayout.JAVA_LONG, 16, 16L)
                fn.invokeWithArguments(MemorySegment.ofAddress(hwnd), data)
            }
        } catch (_: Throwable) {}
    }
}
