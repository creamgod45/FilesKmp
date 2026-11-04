package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Keyboard shortcuts written the way the toolbar shows them ("Ctrl+Shift+N", "Alt+Left", "F11",
 * "Ctrl++", "Ctrl+`"): one parser, so every command that names a shortcut also answers to it.
 */
object Hotkeys {
    /** One key as shortcuts write it: its canonical name, other spellings people use, and the keys that produce it. */
    private class Named(val name: String, val keys: List<Key>, val aliases: Set<String> = emptySet())

    private val table: List<Named> = buildList {
        add(Named("Left", listOf(Key.DirectionLeft), setOf("←"))); add(Named("Right", listOf(Key.DirectionRight), setOf("→")))
        add(Named("Up", listOf(Key.DirectionUp), setOf("↑"))); add(Named("Down", listOf(Key.DirectionDown), setOf("↓")))
        add(Named("Enter", listOf(Key.Enter, Key.NumPadEnter), setOf("return"))); add(Named("Delete", listOf(Key.Delete), setOf("del")))
        add(Named("Insert", listOf(Key.Insert), setOf("ins"))); add(Named("Tab", listOf(Key.Tab)))
        add(Named("Space", listOf(Key.Spacebar), setOf("spacebar"))); add(Named("Esc", listOf(Key.Escape), setOf("escape")))
        add(Named("Backspace", listOf(Key.Backspace))); add(Named("Home", listOf(Key.MoveHome))); add(Named("End", listOf(Key.MoveEnd)))
        add(Named("PageUp", listOf(Key.PageUp), setOf("pgup"))); add(Named("PageDown", listOf(Key.PageDown), setOf("pgdn")))
        add(Named("+", listOf(Key.Equals, Key.Plus, Key.NumPadAdd), setOf("plus", "="))); add(Named("-", listOf(Key.Minus, Key.NumPadSubtract), setOf("minus")))
        add(Named(",", listOf(Key.Comma))); add(Named(".", listOf(Key.Period))); add(Named("`", listOf(Key.Grave)))
        add(Named("/", listOf(Key.Slash))); add(Named(";", listOf(Key.Semicolon))); add(Named("'", listOf(Key.Apostrophe)))
        add(Named("[", listOf(Key.LeftBracket))); add(Named("]", listOf(Key.RightBracket))); add(Named("\\", listOf(Key.Backslash)))
        val letters = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M, Key.N, Key.O, Key.P,
            Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
        letters.forEachIndexed { i, k -> add(Named(('A' + i).toString(), listOf(k))) }
        listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
            .forEachIndexed { i, k -> add(Named(i.toString(), listOf(k))) }
        listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
            .forEachIndexed { i, k -> add(Named("F${i + 1}", listOf(k))) }
    }
    private val byName: Map<String, Named> = buildMap {
        table.forEach { n -> put(n.name.lowercase(), n); n.aliases.forEach { put(it.lowercase(), n) } }
    }
    private val modifierKeys = setOf(Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight, Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight, Key.CapsLock, Key.NumLock, Key.Function)

    /** Splits "Ctrl+Shift+N" into modifiers and key; "Ctrl++" ends in the "+" key itself. */
    private fun split(spec: String): Pair<List<String>, String>? {
        val t = spec.trim()
        if (t.isEmpty()) return null
        if (t == "+") return emptyList<String>() to "+"
        val parts = if (t.endsWith("++")) t.dropLast(2).split('+').map { it.trim() } + "+" else t.split('+').map { it.trim() }
        if (parts.any { it.isEmpty() }) return null
        return parts.dropLast(1) to parts.last()
    }

    /**
     * The one way a shortcut is written ("Ctrl+Alt+Shift+Key", canonical key names), or null when
     * [spec] isn't a shortcut this app can read. "ctrl+shift+n", "Shift+Control+N" → "Ctrl+Shift+N".
     */
    fun normalize(spec: String): String? {
        val (mods, key) = split(spec) ?: return null
        val named = byName[key.lowercase()] ?: return null
        val m = mods.map { it.lowercase() }
        if (m.any { it !in setOf("ctrl", "control", "alt", "shift") }) return null
        val ctrl = "ctrl" in m || "control" in m
        // Shift is part of how "+" is typed on many layouts: never part of its name.
        val shift = "shift" in m && named.name != "+"
        return buildString {
            if (ctrl) append("Ctrl+"); if ("alt" in m) append("Alt+"); if (shift) append("Shift+")
            append(named.name)
        }
    }

    /** The shortcut [e] is, written like [normalize] writes it; null for a lone modifier or a key with no name here. */
    fun specOf(e: KeyEvent): String? {
        if (e.key in modifierKeys) return null
        val named = table.firstOrNull { e.key in it.keys } ?: return null
        return buildString {
            if (e.isCtrlPressed) append("Ctrl+"); if (e.isAltPressed) append("Alt+")
            if (e.isShiftPressed && named.name != "+") append("Shift+")
            append(named.name)
        }
    }

    /** True for a lone modifier key (Ctrl, Shift, Alt, Win): recording waits for the real key. */
    fun isModifier(e: KeyEvent) = e.key in modifierKeys

    /** True when [e] is exactly [spec] (all of its modifiers and no others). */
    fun matches(spec: String, e: KeyEvent): Boolean {
        val n = normalize(spec) ?: return false
        return n == specOf(e)
    }

    /**
     * Keys that move around the file list. They can't be given to a command (without Ctrl or Alt),
     * because the list needs them: arrows, Home, End and Esc, with or without Shift.
     */
    fun isReserved(spec: String): Boolean {
        val n = normalize(spec) ?: return false
        if (n.startsWith("Ctrl+") || n.contains("Alt+")) return false
        return n.removePrefix("Shift+") in setOf("Left", "Right", "Up", "Down", "Home", "End", "Esc")
    }

    /** Runs the command whose shortcut is [e]: the person's keys first, then the defaults (Keymap.kt). */
    fun dispatch(s: AppState, e: KeyEvent): Boolean = Keymap.dispatch(s, specOf(e) ?: return false)
}
