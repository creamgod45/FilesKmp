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
    private fun hwndOf(window: Window): Long? {
        val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", Component::class.java).invoke(accessor, window) ?: return null
        return Class.forName("sun.awt.windows.WComponentPeer").getMethod("getHWnd").invoke(peer) as? Long
    }
}
