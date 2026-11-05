package app.files

import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Keeps the keyboard in the app window. Quick Look's native surfaces (Windows preview handlers,
 * which may live in another process, and the video renderer) can take the Win32 keyboard focus.
 * When such a surface goes away, AWT still believes the window's own component has focus, so it
 * never takes it back: Space, the arrows and every shortcut stop working until focus happens to
 * move. [reclaim] makes AWT hand the focus out again, which re-syncs the native focus.
 */
object WindowFocus {
    @Volatile private var window: Window? = null

    /** Remembers [w] and reclaims the keyboard on a press in it whenever AWT has lost track of the focus. */
    fun install(w: Window) {
        window = w
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e.id == MouseEvent.MOUSE_PRESSED) {
                val src = e.source as? Component ?: return@addAWTEventListener
                if (src is VideoCanvas) return@addAWTEventListener              // a click meant for the preview itself
                if (SwingUtilities.getWindowAncestor(src) !== w && src !== w) return@addAWTEventListener
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (owner == null || owner is VideoCanvas || SwingUtilities.getWindowAncestor(owner) !== w) reclaim()
            }
        }, AWTEvent.MOUSE_EVENT_MASK)
    }

    /** Gives the keyboard back to the window's content (after a native preview opened or closed). */
    fun reclaim() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            if (!w.isShowing) return@invokeLater
            val target = w.mostRecentFocusOwner?.takeIf { it.isShowing && it !is VideoCanvas }
                ?: w.focusTraversalPolicy?.getDefaultComponent(w)
                ?: return@invokeLater
            // Clearing first matters: requesting focus for the component AWT already thinks is focused does nothing natively.
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
            target.requestFocus()
        }
    }
}
