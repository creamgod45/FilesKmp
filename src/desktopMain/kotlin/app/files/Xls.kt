package app.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads old Excel workbooks (.xls, Excel 97–2003: BIFF8 in an OLE compound file) for 快速預覽:
 * sheet names and cell values (text, numbers, booleans, errors, formula results). Formatting,
 * dates (shown as their serial numbers, like the .xlsx preview) and charts are left out.
 */
object Xls {
    class Sheet(val name: String, val rows: List<List<String>>, val total: Int)

    /** The sheets of [f], at most [maxRows] rows each; throws with a readable message when it isn't a BIFF8 workbook. */
    fun read(f: File, maxRows: Int = 2000, maxSheets: Int = 12): List<Sheet> {
        val cfb = Cfb(f.readBytes())
        val stream = cfb.stream("Workbook")
            ?: if (cfb.stream("Book") != null) error(tr("This is an Excel 5/95 workbook, too old to preview.", "這是 Excel 5/95 活頁簿，版本太舊無法預覽。"))
            else error(tr("No workbook inside this file.", "這個檔案裡沒有活頁簿。"))
        return Biff(stream).sheets(maxRows, maxSheets)
    }

    // ------------------------------------------------------------------ OLE compound file

    private class Cfb(val d: ByteArray) {
        private val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        private val sec = 1 shl b.getShort(0x1E).toInt()
        private val mini = 1 shl b.getShort(0x20).toInt()
        private val cutoff = b.getInt(0x38)
        private val fat: IntArray
        private val miniFat: IntArray
        private val dir: List<Entry>

        class Entry(val name: String, val type: Int, val start: Int, val size: Long)

        init {
            require(d.size >= 512 && b.getLong(0) == 0xE11AB1A1E011CFD0uL.toLong()) { tr("Not an Excel 97–2003 file.", "不是 Excel 97–2003 檔案。") }
            // FAT sectors: 109 in the header, the rest in a chain of DIFAT sectors.
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) b.getInt(0x4C + i * 4).let { if (it >= 0) fatSectors += it }
            var difat = b.getInt(0x44); var guard = 0
            while (difat >= 0 && guard++ < 10_000) {
                val off = off(difat); val per = sec / 4 - 1
                for (i in 0 until per) b.getInt(off + i * 4).let { if (it >= 0) fatSectors += it }
                difat = b.getInt(off + per * 4)
            }
            fat = IntArray(fatSectors.size * sec / 4) { i -> val s = fatSectors[i / (sec / 4)]; b.getInt(off(s) + (i % (sec / 4)) * 4) }
            val dirBytes = chain(b.getInt(0x30))
            dir = (0 until dirBytes.size / 128).map { i ->
                val e = ByteBuffer.wrap(dirBytes, i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
                val nameLen = (e.getShort(i * 128 + 0x40).toInt() - 2).coerceIn(0, 62)
                Entry(String(dirBytes, i * 128, nameLen, Charsets.UTF_16LE), dirBytes[i * 128 + 0x42].toInt(), e.getInt(i * 128 + 0x74),
                    e.getInt(i * 128 + 0x78).toLong() and 0xFFFFFFFFL)
            }
            val mf = chain(b.getInt(0x3C))
            miniFat = IntArray(mf.size / 4) { ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) }
        }

        private fun off(s: Int) = 512 + s * sec

        /** A chain of regular sectors from [start]. */
        private fun chain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start; var guard = 0
            while (s >= 0 && guard++ < 1_000_000) {
                val o = off(s); if (o + sec > d.size) break
                out.write(d, o, sec)
                s = if (s < fat.size) fat[s] else -2
            }
            return out.toByteArray()
        }

        fun stream(name: String): ByteArray? {
            val e = dir.firstOrNull { it.type == 2 && it.name.equals(name, true) } ?: return null
            if (e.size >= cutoff) return chain(e.start).copyOf(e.size.toInt())
            // Small streams live in the mini stream (the root entry's chain), in 64-byte sectors.
            val ministream = chain(dir.first { it.type == 5 }.start)
            val out = java.io.ByteArrayOutputStream()
            var s = e.start; var guard = 0
            while (s >= 0 && out.size() < e.size && guard++ < 1_000_000) {
                val o = s * mini; if (o + mini > ministream.size) break
                out.write(ministream, o, mini)
                s = if (s < miniFat.size) miniFat[s] else -2
            }
            return out.toByteArray().copyOf(e.size.toInt())
        }
    }

    // ------------------------------------------------------------------ BIFF8 records

    private class Biff(val d: ByteArray) {
        private val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        private fun u16(o: Int) = b.getShort(o).toInt() and 0xFFFF
        private fun u8(o: Int) = d[o].toInt() and 0xFF

        /** Records as (type, body offset, body length), from [start] to the matching EOF. */
        private fun records(start: Int): Sequence<Triple<Int, Int, Int>> = sequence {
            var o = start
            while (o + 4 <= d.size) {
                val type = u16(o); val len = u16(o + 2)
                if (o + 4 + len > d.size) break
                yield(Triple(type, o + 4, len))
                o += 4 + len
                if (type == 0x000A) break   // EOF
            }
        }

        private fun rk(v: Int): Double {
            val x = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
            return if (v and 1 != 0) x / 100 else x
        }
        private fun num(x: Double) = if (x == Math.rint(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString()
            else java.math.BigDecimal(x).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
        private val errors = mapOf(0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A")

        /** A string stored across a record and its CONTINUE records; each continuation of character data starts with a new flags byte. */
        private class Pieces(val parts: List<Pair<Int, Int>>) {
            var part = 0; var pos = parts.firstOrNull()?.first ?: 0
            fun end() = parts[part].first + parts[part].second
            fun next(): Boolean { if (part + 1 >= parts.size) return false; part++; pos = parts[part].first; return true }
        }
        private fun Pieces.byte(): Int { if (pos >= end() && !next()) error("SST"); return u8(pos++) }
        private fun Pieces.short(): Int = byte() or (byte() shl 8)
        private fun Pieces.int(): Int = short() or (short() shl 16)
        private fun Pieces.skip(n: Int) { var k = n; while (k > 0) { if (pos >= end() && !next()) return; val t = minOf(k, end() - pos); pos += t; k -= t } }
        private fun Pieces.unicode(): String {
            val cch = short(); val flags = byte()
            var wide = flags and 1 != 0
            val runs = if (flags and 8 != 0) short() else 0
            val ext = if (flags and 4 != 0) int() else 0
            val sb = StringBuilder(cch)
            while (sb.length < cch) {
                if (pos >= end()) { if (!next()) break; wide = (u8(pos++) and 1) != 0; continue }
                if (wide) { if (pos + 1 >= end()) { pos = end(); continue }; sb.append(((u8(pos) or (u8(pos + 1) shl 8))).toChar()); pos += 2 }
                else { sb.append(u8(pos).toChar()); pos++ }
            }
            skip(runs * 4 + ext)
            return sb.toString()
        }

        /** A short string inside one record: cch (8 or 16 bit), flags, characters. */
        private fun inline(o: Int, cch16: Boolean): String {
            val cch = if (cch16) u16(o) else u8(o)
            var p = o + if (cch16) 2 else 1
            val flags = u8(p++)
            if (flags and 8 != 0) p += 2
            if (flags and 4 != 0) p += 4
            return if (flags and 1 != 0) String(d, p, (cch * 2).coerceAtMost(d.size - p), Charsets.UTF_16LE)
            else String(d, p, cch.coerceAtMost(d.size - p), Charsets.ISO_8859_1)
        }

        fun sheets(maxRows: Int, maxSheets: Int): List<Sheet> {
            // Workbook globals: sheet list and the shared strings.
            val bound = ArrayList<Pair<String, Int>>()
            val sst = ArrayList<String>()
            val all = records(0).toList()
            all.forEachIndexed { i, (type, o, len) ->
                when (type) {
                    0x0085 -> if (u8(o + 5) == 0) bound += inline(o + 6, false) to b.getInt(o)     // worksheets only (not charts / macros)
                    0x00FC -> {
                        val parts = ArrayList<Pair<Int, Int>>(); parts += (o + 8) to (len - 8)
                        var j = i + 1
                        while (j < all.size && all[j].first == 0x003C) { parts += all[j].second to all[j].third; j++ }
                        val pc = Pieces(parts)
                        val unique = b.getInt(o + 4)
                        runCatching { repeat(unique) { sst += pc.unicode() } }
                    }
                }
            }
            return bound.take(maxSheets).map { (name, at) ->
                val cells = java.util.TreeMap<Int, java.util.TreeMap<Int, String>>()
                fun put(r: Int, c: Int, v: String) { if (c < 512) cells.getOrPut(r) { java.util.TreeMap() }[c] = v }
                var pending: Pair<Int, Int>? = null
                if (at in 0 until d.size) for ((type, o, len) in records(at)) {
                    when (type) {
                        0x00FD -> put(u16(o), u16(o + 2), sst.getOrElse(b.getInt(o + 6)) { "" })                // LABELSST
                        0x0203 -> put(u16(o), u16(o + 2), num(b.getDouble(o + 6)))                               // NUMBER
                        0x027E -> put(u16(o), u16(o + 2), num(rk(b.getInt(o + 6))))                              // RK
                        0x00BD -> { val r = u16(o); val first = u16(o + 2); val n = (len - 6) / 6                // MULRK
                            for (k in 0 until n) put(r, first + k, num(rk(b.getInt(o + 4 + k * 6 + 2)))) }
                        0x0204 -> put(u16(o), u16(o + 2), inline(o + 6, true))                                   // LABEL
                        0x0205 -> put(u16(o), u16(o + 2), if (u8(o + 7) == 0) (if (u8(o + 6) != 0) "TRUE" else "FALSE") else errors[u8(o + 6)] ?: "#ERR")
                        0x0006 -> { val r = u16(o); val c = u16(o + 2)                                           // FORMULA: its last result
                            if (u16(o + 12) != 0xFFFF) put(r, c, num(b.getDouble(o + 6)))
                            else when (u8(o + 6)) { 0 -> pending = r to c; 1 -> put(r, c, if (u8(o + 8) != 0) "TRUE" else "FALSE"); 2 -> put(r, c, errors[u8(o + 8)] ?: "#ERR") }
                        }
                        0x0207 -> pending?.let { (r, c) -> put(r, c, inline(o, true)); pending = null }         // STRING (a formula's text result)
                    }
                }
                // Rows keep their place (row 5 is shown as row 5, even when rows 2–4 are empty).
                val total = if (cells.isEmpty()) 0 else cells.lastKey() + 1
                val rows = (0 until minOf(total, maxRows)).map { r -> cells[r]?.let { cols -> (0..cols.lastKey()).map { cols[it] ?: "" } } ?: emptyList() }
                Sheet(name, rows, total)
            }
        }
    }
}
