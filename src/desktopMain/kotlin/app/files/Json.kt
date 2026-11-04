package app.files

/** Minimal JSON (objects → LinkedHashMap, arrays → List, numbers → Double/Long, plus String/Boolean/null). */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); if (i != s.length) err("Unexpected text"); v }

    private class Parser(val s: String) {
        var i = 0
        fun err(m: String): Nothing = throw IllegalArgumentException("JSON: $m at ${line()}")
        fun line(): String { val l = s.substring(0, i.coerceAtMost(s.length)).count { it == '\n' } + 1; return (if (S.zh) "第 $l 行" else "line $l") }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) err("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else err("Unexpected '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) err("Expected $w"); i += w.length; return v }
        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: err("Bad number")
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("Unclosed string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out += value(); ws(); when (s.getOrNull(i++)) { ',' -> continue; ']' -> return out; else -> err("Expected , or ]") } }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); if (s.getOrNull(i) != '"') err("Expected a key")
                val k = str(); ws(); if (s.getOrNull(i++) != ':') err("Expected :")
                out[k] = value(); ws()
                when (s.getOrNull(i++)) { ',' -> continue; '}' -> return out; else -> err("Expected , or }") }
            }
        }
    }

    fun write(v: Any?, indent: String = ""): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) -> "$indent  ${quote(k.toString())}: ${write(x, "$indent  ")}" }
        is List<*> -> if (v.isEmpty()) "[]" else if (v.all { it !is Map<*, *> && it !is List<*> }) v.joinToString(", ", "[", "]") { write(it) }
            else v.joinToString(",\n", "[\n", "\n$indent]") { "$indent  " + write(it, "$indent  ") }
        else -> quote(v.toString())
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
