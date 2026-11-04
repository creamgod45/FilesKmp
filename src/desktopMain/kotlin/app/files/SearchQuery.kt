package app.files

import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Pattern

/**
 * Search syntax, modelled on Everything's:
 *  - words separated by spaces must all match (AND); `a | b` is OR; `!word` excludes
 *  - "quoted text" keeps spaces; `*` and `?` wildcards match the whole name
 *  - text with a `\` (or `path:`) matches the full path instead of the name
 *  - `regex:pattern` for one term, or the regex switch for the whole query
 *  - filters: `ext:js;ts`  `size:>10mb`  `size:1kb..5mb`  `dm:today`  `dm:>2025-01-01`
 *    `dm:<30d` (older than 30 days)  `dc:` (created)  `file:`  `folder:`
 * Case-insensitive unless the case switch is on.
 */
class SearchQuery private constructor(private val clauses: List<List<Term>>, val needsPath: Boolean) {

    /** What a term looks at; [lowerName] is already lower-cased when the search ignores case. */
    class Candidate(val name: CharSequence, val lowerName: CharSequence, val isDir: Boolean, val size: Long, val modified: Long, val created: Long, val path: () -> String)

    sealed class Term(val negated: Boolean) {
        abstract fun test(c: Candidate): Boolean
        fun matches(c: Candidate) = test(c) != negated
    }

    private class Text(val needle: String, val onPath: Boolean, val caseSensitive: Boolean, neg: Boolean) : Term(neg) {
        override fun test(c: Candidate): Boolean {
            val hay: CharSequence = if (onPath) c.path().let { if (caseSensitive) it else it.lowercase() } else if (caseSensitive) c.name else c.lowerName
            return contains(hay, needle)
        }
    }

    private class Rx(val pattern: Pattern, val onPath: Boolean, val whole: Boolean, neg: Boolean) : Term(neg) {
        override fun test(c: Candidate): Boolean {
            val m = pattern.matcher(if (onPath) c.path() else c.name)
            return if (whole) m.matches() else m.find()
        }
    }

    private class Ext(val exts: Set<String>, neg: Boolean) : Term(neg) {
        override fun test(c: Candidate): Boolean {
            if (c.isDir) return false
            val n = c.lowerName
            val dot = lastIndexOf(n, '.')
            return dot >= 0 && n.subSequence(dot + 1, n.length).toString() in exts
        }
    }

    private class Range(val what: Char, val min: Long, val max: Long, neg: Boolean) : Term(neg) {
        override fun test(c: Candidate): Boolean {
            val v = when (what) { 's' -> if (c.isDir) return false else c.size; 'm' -> c.modified; else -> c.created }
            return v in min..max
        }
    }

    private class Kind(val dirs: Boolean, neg: Boolean) : Term(neg) {
        override fun test(c: Candidate) = c.isDir == dirs
    }

    fun matches(c: Candidate): Boolean = clauses.all { clause -> clause.any { it.matches(c) } }

    val isEmpty get() = clauses.isEmpty()

    companion object {
        /** Null when the query is blank; throws IllegalArgumentException with a readable message for a bad regex. */
        fun parse(text: String, regex: Boolean = false, caseSensitive: Boolean = false): SearchQuery? {
            val q = text.trim()
            if (q.isEmpty()) return null
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if (regex) {
                val p = compile(q, flags)
                return SearchQuery(listOf(listOf(Rx(p, q.contains("\\\\") || q.contains('/'), false, false))), q.contains("\\\\"))
            }
            val clauses = ArrayList<MutableList<Term>>()
            var orNext = false
            var needsPath = false
            for (tok in tokens(q)) {
                if (tok == "|") { orNext = true; continue }
                val term = term(tok, flags, caseSensitive) ?: continue
                if (term is Text && term.onPath || term is Rx && term.onPath) needsPath = true
                if (orNext && clauses.isNotEmpty()) clauses.last().add(term) else clauses.add(mutableListOf(term))
                orNext = false
            }
            return if (clauses.isEmpty()) null else SearchQuery(clauses, needsPath)
        }

        private fun compile(p: String, flags: Int): Pattern = try { Pattern.compile(p, flags) }
            catch (e: java.util.regex.PatternSyntaxException) { throw IllegalArgumentException((if (S.zh) "正規表示式錯誤：" else "Regex error: ") + e.description) }

        /** Splits on spaces, keeping "quoted text" (and key:"quoted value") together; `|` stands alone. */
        private fun tokens(q: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            for (ch in q) {
                when {
                    ch == '"' -> quoted = !quoted
                    !quoted && ch.isWhitespace() -> { if (sb.isNotEmpty()) out += sb.toString(); sb.clear() }
                // Inside regex:… a | belongs to the pattern, not the OR operator.
                !quoted && ch == '|' && !sb.startsWith("regex:") && !sb.startsWith("!regex:") -> { if (sb.isNotEmpty()) out += sb.toString(); sb.clear(); out += "|" }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        private fun term(raw: String, flags: Int, cs: Boolean): Term? {
            var t = raw
            val neg = t.startsWith("!") && t.length > 1
            if (neg) t = t.substring(1)
            val key = t.substringBefore(':', "").lowercase()
            val value = t.substringAfter(':', "")
            when (key) {
                "regex" -> return Rx(compile(value, flags), value.contains("\\\\"), false, neg)
                "ext" -> return Ext(value.lowercase().split(';', ',').map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() }.toSet(), neg)
                "size" -> return sizeRange(value)?.let { Range('s', it.first, it.second, neg) }
                "dm", "datemodified" -> return dateRange(value)?.let { Range('m', it.first, it.second, neg) }
                "dc", "datecreated" -> return dateRange(value)?.let { Range('c', it.first, it.second, neg) }
                "file" -> if (value.isEmpty()) return Kind(false, neg)
                "folder" -> if (value.isEmpty()) return Kind(true, neg)
                "path" -> return Text(if (cs) value else value.lowercase(), true, cs, neg)
            }
            if (t.contains('*') || t.contains('?')) {
                val onPath = t.contains('\\')
                return Rx(compile(globToRegex(t), flags), onPath, true, neg)
            }
            return Text(if (cs) t else t.lowercase(), t.contains('\\'), cs, neg)
        }

        private fun globToRegex(g: String) = buildString {
            for (ch in g) when (ch) {
                '*' -> append(".*"); '?' -> append('.')
                else -> append(Pattern.quote(ch.toString()))
            }
        }

        /** "10mb", ">1gb", "<500kb", "1kb..5mb", "empty". */
        private fun sizeRange(v: String): Pair<Long, Long>? {
            fun bytes(s: String): Long? {
                val m = Regex("(?i)^([\\d.]+)\\s*(b|kb|k|mb|m|gb|g|tb|t)?$").find(s.trim()) ?: return null
                val n = m.groupValues[1].toDoubleOrNull() ?: return null
                val mult = when (m.groupValues[2].lowercase()) { "kb", "k" -> 1L shl 10; "mb", "m" -> 1L shl 20; "gb", "g" -> 1L shl 30; "tb", "t" -> 1L shl 40; else -> 1L }
                return (n * mult).toLong()
            }
            return when {
                v.equals("empty", true) -> 0L to 0L
                v.contains("..") -> bytes(v.substringBefore(".."))?.let { a -> bytes(v.substringAfter(".."))?.let { b -> a to b } }
                v.startsWith(">=") -> bytes(v.drop(2))?.let { it to Long.MAX_VALUE }
                v.startsWith(">") -> bytes(v.drop(1))?.let { it + 1 to Long.MAX_VALUE }
                v.startsWith("<=") -> bytes(v.drop(2))?.let { 0L to it }
                v.startsWith("<") -> bytes(v.drop(1))?.let { 0L to it - 1 }
                else -> bytes(v)?.let { it to it }
            }
        }

        /** "today", "yesterday", "thisweek", "thismonth", "thisyear", "2025-01-01", ">2025-01-01", "2025-01-01..2025-02-01". */
        private fun dateRange(v: String): Pair<Long, Long>? {
            val zone = ZoneId.systemDefault()
            fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun day(s: String): Pair<Long, Long>? {
                val today = LocalDate.now()
                val (a, b) = when (s.lowercase()) {
                    "today" -> today to today.plusDays(1)
                    "yesterday" -> today.minusDays(1) to today
                    "thisweek" -> today.minusDays((today.dayOfWeek.value - 1).toLong()) to today.plusDays(1)
                    "thismonth" -> today.withDayOfMonth(1) to today.plusDays(1)
                    "thisyear" -> today.withDayOfYear(1) to today.plusDays(1)
                    else -> Regex("^(\\d+)d$").find(s.lowercase())?.let { m -> today.minusDays(m.groupValues[1].toLong()).let { it to it.plusDays(1) } }
                        ?: runCatching { LocalDate.parse(s.replace('/', '-')) }.getOrNull()?.let { it to it.plusDays(1) } ?: return null
                }
                return start(a) to start(b) - 1
            }
            return when {
                v.contains("..") -> day(v.substringBefore(".."))?.let { a -> day(v.substringAfter(".."))?.let { b -> a.first to b.second } }
                v.startsWith(">") -> day(v.trimStart('>', '='))?.let { it.first to Long.MAX_VALUE }
                v.startsWith("<") -> day(v.trimStart('<', '='))?.let { 0L to it.second }
                else -> day(v)
            }
        }

        fun contains(hay: CharSequence, needle: String): Boolean {
            val n = needle.length
            if (n == 0) return true
            val first = needle[0]
            val last = hay.length - n
            var i = 0
            while (i <= last) {
                if (hay[i] == first) {
                    var j = 1
                    while (j < n && hay[i + j] == needle[j]) j++
                    if (j == n) return true
                }
                i++
            }
            return false
        }

        private fun lastIndexOf(s: CharSequence, ch: Char): Int {
            var i = s.length - 1
            while (i >= 0) { if (s[i] == ch) return i; i-- }
            return -1
        }
    }
}
