package app.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import app.files.FlowActions.CAT_CONTROL
import app.files.FlowActions.CAT_DATA
import app.files.FlowActions.CAT_DEBUG
import app.files.FlowActions.CAT_FILES
import app.files.FlowActions.CAT_INPUT
import app.files.FlowActions.CAT_LIST
import app.files.FlowActions.CAT_TEXT

/*
 * More shortcut actions: text, lists, dictionaries (JSON), numbers, dates, Base64, file CRUD,
 * control flow (if / repeat / wait / call another shortcut) and debugging.
 * Windows, web, apps, sound, mail and SMS actions are in [systemActions].
 */

/** Comparisons shared by "if" and text.test. Numbers compare as numbers, everything else as text. */
internal object FlowCompare {
    val ops = listOf("contains", "notContains", "equals", "notEquals", "startsWith", "endsWith", "matches", "isEmpty", "notEmpty", ">", ">=", "<", "<=")

    fun test(op: String, a: String, b: String, ignoreCase: Boolean = true): Boolean {
        val x = a.trim().toDoubleOrNull(); val y = b.trim().toDoubleOrNull()
        fun cmp(): Int = if (x != null && y != null) x.compareTo(y) else a.compareTo(b, ignoreCase)
        return when (op) {
            "contains" -> a.contains(b, ignoreCase); "notContains" -> !a.contains(b, ignoreCase)
            // Spaces and line breaks around the text don't count (a hash copied from a web page often ends in one).
            "equals", "==" -> if (x != null && y != null) x == y else a.trim().equals(b.trim(), ignoreCase)
            "notEquals", "!=" -> !(if (x != null && y != null) x == y else a.trim().equals(b.trim(), ignoreCase))
            "startsWith" -> a.startsWith(b, ignoreCase); "endsWith" -> a.endsWith(b, ignoreCase)
            "matches" -> Regex(b, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(a)
            "isEmpty" -> a.isBlank(); "notEmpty" -> a.isNotBlank()
            ">" -> cmp() > 0; ">=" -> cmp() >= 0; "<" -> cmp() < 0; "<=" -> cmp() <= 0
            else -> throw FlowStopped((if (S.zh) "未知的比較方式：" else "Unknown comparison: ") + op)
        }
    }
}

/** Dates in shortcuts: read from most common forms, written as yyyy-MM-dd HH:mm:ss unless a format is given. */
internal object FlowTime {
    const val DEFAULT = "yyyy-MM-dd HH:mm:ss"
    val units = listOf("seconds", "minutes", "hours", "days", "weeks", "months", "years")

    fun parse(text: String): LocalDateTime? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.equals("now", true) || t == "現在") return LocalDateTime.now()
        if (t.equals("today", true) || t == "今天") return LocalDate.now().atStartOfDay()
        t.toLongOrNull()?.let { n -> return LocalDateTime.ofInstant(Instant.ofEpochMilli(if (kotlin.math.abs(n) > 100_000_000_000L) n else n * 1000), ZoneId.systemDefault()) }
        runCatching { return OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
        runCatching { return ZonedDateTime.parse(t).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
        runCatching { return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
        for (p in listOf("yyyy-MM-dd'T'HH:mm[:ss][.SSS]", "yyyy-M-d H:mm[:ss]", "yyyy/M/d H:mm[:ss]", "yyyy.M.d H:mm[:ss]", "yyyyMMdd-HHmmss", "yyyyMMddHHmmss"))
            runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p)) }
        for (p in listOf("yyyy-M-d", "yyyy/M/d", "yyyy.M.d", "yyyyMMdd"))
            runCatching { return LocalDate.parse(t, DateTimeFormatter.ofPattern(p)).atStartOfDay() }
        return null
    }

    fun format(d: LocalDateTime, fmt: String): String = when (fmt.trim()) {
        "", "default" -> d.format(DateTimeFormatter.ofPattern(DEFAULT))
        "iso" -> d.atZone(ZoneId.systemDefault()).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        "epoch" -> d.atZone(ZoneId.systemDefault()).toEpochSecond().toString()
        "epochMs" -> d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
        else -> d.format(runCatching { DateTimeFormatter.ofPattern(fmt) }.getOrElse { throw FlowStopped((if (S.zh) "日期格式錯誤：" else "Bad date format: ") + fmt) })
    }

    fun unit(u: String): ChronoUnit = when (u) {
        "seconds" -> ChronoUnit.SECONDS; "minutes" -> ChronoUnit.MINUTES; "hours" -> ChronoUnit.HOURS; "weeks" -> ChronoUnit.WEEKS
        "months" -> ChronoUnit.MONTHS; "years" -> ChronoUnit.YEARS; else -> ChronoUnit.DAYS
    }
}

/** Arithmetic for number.calc: + - * / % ^, parentheses and round floor ceil abs sqrt min max pow. */
internal object Calc {
    // Commas separate function arguments: round(x, 2). Numbers written with thousands separators must not use them.
    fun eval(expr: String): Double = P(expr).run { val v = sum(); ws(); if (i < s.length) bad(); v }

    private class P(val s: String) {
        var i = 0
        fun bad(): Nothing = throw FlowStopped((if (S.zh) "無法計算：" else "Can't calculate: ") + s)
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun eat(c: Char): Boolean { ws(); if (i < s.length && s[i] == c) { i++; return true }; return false }
        fun sum(): Double { var v = term(); while (true) v = if (eat('+')) v + term() else if (eat('-')) v - term() else return v }
        fun term(): Double { var v = pow(); while (true) v = if (eat('*')) v * pow() else if (eat('/')) v / pow() else if (eat('%')) v % pow() else return v }
        fun pow(): Double { val b = unary(); return if (eat('^')) Math.pow(b, pow()) else b }
        fun unary(): Double = if (eat('-')) -unary() else if (eat('+')) unary() else atom()
        fun atom(): Double {
            ws()
            if (eat('(')) { val v = sum(); if (!eat(')')) bad(); return v }
            val st = i
            if (i < s.length && s[i].isLetter()) {
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(st, i).lowercase()
                if (name == "pi") return Math.PI
                if (!eat('(')) bad()
                val a = ArrayList<Double>(); if (!eat(')')) { do a += sum() while (eat(',')); if (!eat(')')) bad() }
                return when (name) {
                    "round" -> if (a.size > 1) Math.round(a[0] * Math.pow(10.0, a[1])) / Math.pow(10.0, a[1]) else Math.round(a[0]).toDouble()
                    "floor" -> Math.floor(a[0]); "ceil" -> Math.ceil(a[0]); "abs" -> Math.abs(a[0]); "sqrt" -> Math.sqrt(a[0])
                    "min" -> a.min(); "max" -> a.max(); "pow" -> Math.pow(a[0], a[1])
                    else -> bad()
                }
            }
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { i++; if (i < s.length && (s[i] == '-' || s[i] == '+')) i++; while (i < s.length && s[i].isDigit()) i++ }
            return s.substring(st, i).toDoubleOrNull() ?: bad()
        }
    }
}

/** Paths into JSON: a.b[0].c, items[*].name, ["key with dots"]. */
internal object JsonPath {
    private val seg = Regex("\\[\\s*\"([^\"]*)\"\\s*]|\\[(\\d+|\\*|-\\d+)]|([^.\\[\\]]+)")

    fun parts(path: String): List<String> = seg.findAll(path).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }.let { if (m.groupValues[2].isNotEmpty()) "[$it]" else it } }.toList()

    fun get(root: Any?, path: String): List<Any?> {
        var cur = listOf(root)
        for (p in parts(path)) cur = cur.flatMap { v ->
            when {
                p == "[*]" -> (v as? List<*>) ?: (v as? Map<*, *>)?.values?.toList() ?: emptyList()
                p.startsWith("[") -> (v as? List<*>)?.let { l -> val n = p.trim('[', ']').toInt(); l.getOrNull(if (n < 0) l.size + n else n)?.let { listOf(it) } } ?: emptyList()
                else -> (v as? Map<*, *>)?.takeIf { it.containsKey(p) }?.let { listOf(it[p]) } ?: emptyList()
            }
        }
        return cur
    }

    @Suppress("UNCHECKED_CAST")
    fun set(root: Any?, path: String, value: Any?): Any? {
        val ps = parts(path)
        if (ps.isEmpty()) return value
        fun put(node: Any?, k: Int): Any? {
            val p = ps[k]
            val last = k == ps.size - 1
            return if (p.startsWith("[")) {
                val l = ((node as? List<Any?>)?.toMutableList() ?: ArrayList())
                val raw = p.trim('[', ']')
                val n = if (raw == "*") l.size else raw.toInt().let { if (it < 0) l.size + it else it }
                while (l.size <= n) l.add(null)
                l[n] = if (last) value else put(l[n], k + 1); l
            } else {
                val m = LinkedHashMap((node as? Map<String, Any?>) ?: emptyMap())
                m[p] = if (last) value else put(m[p], k + 1); m
            }
        }
        return put(root, 0)
    }

    /** A JSON value as a shortcut value: text stays text, everything else is JSON. */
    fun text(v: Any?): String = when (v) { null -> ""; is String -> v; is Double -> numText(v); is Map<*, *>, is List<*> -> Json.write(v); else -> v.toString() }

    /** Text from a field to a JSON value: numbers, true/false/null and JSON stay typed unless [asText]. */
    fun value(s: String, asText: Boolean): Any? {
        if (asText) return s
        val t = s.trim()
        return when {
            t == "true" -> true; t == "false" -> false; t == "null" -> null
            t.toLongOrNull() != null -> t.toLong(); t.toDoubleOrNull() != null && !t.endsWith(".") -> t.toDouble()
            (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("\"") && t.endsWith("\"") && t.length > 1) -> runCatching { Json.parse(t) }.getOrDefault(s)
            else -> s
        }
    }

    fun parse(text: String): Any? = try { Json.parse(text.trim().removePrefix("﻿")) } catch (e: IllegalArgumentException) { throw FlowStopped((if (S.zh) "不是有效的 JSON：" else "Not valid JSON: ") + e.message) }
}

/** "key=value" or "key: value" lines (blank lines and # comments skipped). */
internal fun keyValues(text: String): List<Pair<String, String>> = text.lines().mapNotNull { l ->
    if (l.isBlank() || l.trimStart().startsWith("#")) return@mapNotNull null
    val i = l.indexOf('=').let { e -> val c = l.indexOf(':'); if (e < 0) c else if (c < 0) e else minOf(e, c) }
    if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
}

/** "file2" before "file10": runs of digits compare as numbers, the rest as text ignoring case. */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val si = i; val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val x = a.substring(si, i).trimStart('0'); val y = b.substring(sj, j).trimStart('0')
            val c = if (x.length != y.length) x.length - y.length else x.compareTo(y)
            if (c != 0) return c
        } else {
            val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private fun cancelled(): Nothing = throw FlowStopped(if (S.zh) "已取消" else "Cancelled")

internal fun moreActions(): List<FlowAction> = listOf(
    // ================================================================ input
    FlowAction("ui.pickFiles", "選擇檔案", "Pick files", CAT_INPUT, "執行時開啟檔案選擇視窗。", "Opens a file picker while running.",
        listOf(ArgSpec("title", "標題", "Title", "text", "選擇檔案"), ArgSpec("multiple", "可選多個", "Allow several", "bool", true),
            ArgSpec("folder", "起始資料夾", "Start in", "folder", "{folder}")), input = VType.None, output = VType.Files) { st ->
        val title = arg(st, "title"); val start = arg(st, "folder"); val multi = bool(st, "multiple")
        items = withContext(Dispatchers.Main) {
            val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD).apply { isMultipleMode = multi; directory = start; isVisible = true }
            d.files.map { it.path }
        }.ifEmpty { cancelled() }
    },
    FlowAction("ui.pickFolder", "選擇資料夾", "Pick a folder", CAT_INPUT, "執行時開啟資料夾選擇視窗。", "Opens a folder picker while running.",
        listOf(ArgSpec("title", "標題", "Title", "text", "選擇資料夾"), ArgSpec("folder", "起始資料夾", "Start in", "folder", "{folder}")),
        input = VType.None, output = VType.Files) { st ->
        val title = arg(st, "title"); val start = arg(st, "folder")
        items = listOf(withContext(Dispatchers.Main) {
            val ch = javax.swing.JFileChooser(start).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = title }
            if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile.path else null
        } ?: cancelled())
    },
    FlowAction("file.get", "以路徑取得檔案", "Files from paths", CAT_INPUT, "把每行文字當成檔案或資料夾路徑（可用標記）。", "Treats each text value as a file or folder path (tokens allowed).",
        listOf(ArgSpec("paths", "路徑（每行一個；空白 = 使用輸入）", "Paths (one per line; empty = the input)", "multiline", ""),
            ArgSpec("mustExist", "只保留存在的", "Only existing ones", "bool", true)), input = VType.Text, output = VType.Files) { st ->
        val raw = rawArg(st, "paths")
        val list = if (raw.isBlank()) items else raw.lines().map { expand(it) }
        items = list.map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map { File(it).absolutePath }.filter { !bool(st, "mustExist") || File(it).exists() }
    },

    // ================================================================ text
    FlowAction("text.find", "尋找文字", "Find in text", CAT_TEXT, "找出每個值中所有符合的文字（可用正規表示式與擷取群組）。", "Every match in each value (regex and capture groups allowed).",
        listOf(ArgSpec("find", "尋找", "Find", "text", ""), ArgSpec("regex", "正規表示式", "Regex", "bool", false),
            ArgSpec("group", "擷取群組（0 = 整段）", "Group (0 = whole match)", "number", 0), ArgSpec("ignoreCase", "不分大小寫", "Ignore case", "bool", true)),
        input = VType.Text, output = VType.Text) { st ->
        val f = rawArg(st, "find"); val g = int(st, "group", 0)
        val rx = Regex(if (bool(st, "regex")) f else Regex.escape(f), if (bool(st, "ignoreCase")) setOf(RegexOption.IGNORE_CASE) else emptySet())
        val groupCount = rx.toPattern().matcher("").groupCount()
        if (g !in 0..groupCount) throw FlowStopped(if (S.zh) "擷取群組 $g 不存在（共 $groupCount 個）" else "Capture group $g does not exist ($groupCount in the pattern)")
        items = items.flatMap { v -> rx.findAll(v).map { it.groups[g]?.value ?: "" }.toList() }
    },
    FlowAction("text.test", "檢查文字", "Test text", CAT_TEXT, "包含、開頭、結尾、相等、比對正規表示式、比大小…；可篩選或得到是/否。", "Contains, starts/ends with, equals, regex, compare…; filter the values or get true/false.",
        listOf(ArgSpec("op", "條件", "Condition", "choice", "contains", FlowCompare.ops), ArgSpec("value", "比較值", "Value", "text", ""),
            ArgSpec("ignoreCase", "不分大小寫", "Ignore case", "bool", true),
            ArgSpec("mode", "結果", "Result", "choice", "filter", listOf("filter", "reject", "each", "any", "all"))),
        input = VType.Text, outputFor = { st -> if ((st.args["mode"] ?: "filter") in listOf("filter", "reject")) VType.Same else VType.Bool }) { st ->
        val op = arg(st, "op"); val v = arg(st, "value"); val ic = bool(st, "ignoreCase")
        val r = items.map { FlowCompare.test(op, it, v, ic) }
        items = when (arg(st, "mode")) {
            "reject" -> items.filterIndexed { i, _ -> !r[i] }
            "each" -> r.map { it.toString() }
            "any" -> listOf(r.any { it }.toString())
            "all" -> listOf((r.isNotEmpty() && r.all { it }).toString())
            else -> items.filterIndexed { i, _ -> r[i] }
        }
    },
    FlowAction("text.indexOf", "文字位置", "Position in text", CAT_TEXT, "尋找文字在每個值中的位置（從 1 起算，找不到為 0）。", "Where the text appears in each value (from 1; 0 = not found).",
        listOf(ArgSpec("find", "尋找", "Find", "text", ""), ArgSpec("from", "位置", "Which", "choice", "first", listOf("first", "last")),
            ArgSpec("ignoreCase", "不分大小寫", "Ignore case", "bool", true)), input = VType.Text, output = VType.Number) { st ->
        val f = arg(st, "find"); val ic = bool(st, "ignoreCase")
        items = items.map { v -> ((if (arg(st, "from") == "last") v.lastIndexOf(f, ignoreCase = ic) else v.indexOf(f, ignoreCase = ic)) + 1).toString() }
    },
    FlowAction("text.substring", "擷取部分文字", "Part of text", CAT_TEXT, "從第幾個字取幾個字（負數 = 從結尾算）。", "Characters from a position (negative = from the end) for a length.",
        listOf(ArgSpec("start", "起點（從 1 起算）", "Start (from 1)", "number", 1), ArgSpec("length", "長度（空白 = 到結尾）", "Length (empty = to the end)", "text", "")),
        input = VType.Text, output = VType.Text) { st ->
        val start = int(st, "start", 1); val len = arg(st, "length").trim().toIntOrNull()
        items = items.map { v ->
            val from = (if (start < 0) v.length + start else start - 1).coerceIn(0, v.length)
            val to = if (len == null) v.length else (from + len).coerceIn(from, v.length)
            v.substring(from, to)
        }
    },
    FlowAction("text.case", "變更大小寫", "Change case", CAT_TEXT, "大寫、小寫、字首大寫、句首大寫、camelCase、snake_case、kebab-case。", "UPPER, lower, Title, Sentence, camelCase, snake_case, kebab-case.",
        listOf(ArgSpec("to", "改為", "To", "choice", "upper", listOf("upper", "lower", "title", "sentence", "camel", "snake", "kebab"))),
        input = VType.Text, output = VType.Text) { st ->
        val to = arg(st, "to")
        items = items.map { v ->
            val words = v.split(Regex("[\\s_\\-]+|(?<=[a-z])(?=[A-Z])")).filter { it.isNotEmpty() }
            when (to) {
                "upper" -> v.uppercase(); "lower" -> v.lowercase()
                "title" -> v.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
                "sentence" -> v.lowercase().replaceFirstChar { it.titlecase() }
                "camel" -> words.mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.titlecase() } }.joinToString("")
                "snake" -> words.joinToString("_") { it.lowercase() }
                "kebab" -> words.joinToString("-") { it.lowercase() }
                else -> v
            }
        }
    },
    FlowAction("text.trim", "去除空白", "Trim", CAT_TEXT, "去除前後空白，或把連續空白合成一個。", "Removes surrounding spaces, or collapses runs of spaces.",
        listOf(ArgSpec("mode", "方式", "How", "choice", "both", listOf("both", "start", "end", "collapse", "removeEmpty"))), input = VType.Text, output = VType.Text) { st ->
        items = when (arg(st, "mode")) {
            "start" -> items.map { it.trimStart() }; "end" -> items.map { it.trimEnd() }
            "collapse" -> items.map { it.trim().replace(Regex("\\s+"), " ") }
            "removeEmpty" -> items.filter { it.isNotBlank() }
            else -> items.map { it.trim() }
        }
    },
    FlowAction("text.length", "文字長度", "Text length", CAT_TEXT, "每個值的字數（或行數、字詞數）。", "Characters (or lines, words) in each value.",
        listOf(ArgSpec("count", "計算", "Count", "choice", "characters", listOf("characters", "lines", "words", "bytes"))), input = VType.Text, output = VType.Number) { st ->
        val c = arg(st, "count")
        items = items.map { v ->
            when (c) { "lines" -> v.lines().size; "words" -> v.split(Regex("\\s+")).count { it.isNotEmpty() }; "bytes" -> v.toByteArray(Charsets.UTF_8).size; else -> v.codePointCount(0, v.length) }.toString()
        }
    },
    FlowAction("text.template", "組合文字", "Combine text", CAT_TEXT, "對每個值套用範本，{item} 是該值、{item.index} 是序號。", "Applies a template to each value: {item} is the value, {item.index} its number.",
        listOf(ArgSpec("template", "範本", "Template", "multiline", "{item.index}. {item}")), input = VType.Anything, output = VType.Text) { st ->
        val t = rawArg(st, "template")
        items = items.mapIndexed { i, v -> expand(t, v, i) }
    },

    // ================================================================ lists & dictionaries
    FlowAction("list.make", "清單", "List", CAT_LIST, "以這些項目（每行一個，可用標記）取代目前的值。", "Replaces the values with these items (one per line, tokens allowed).",
        listOf(ArgSpec("items", "項目", "Items", "multiline", "")), input = VType.None, output = VType.Text) { st ->
        items = rawArg(st, "items").lines().filter { it.isNotEmpty() }.map { expand(it) }
    },
    FlowAction("list.item", "取得清單項目", "Get item from list", CAT_LIST, "第一項、最後一項、第 N 項（負數從結尾）、隨機或範圍。", "First, last, item N (negative = from the end), random or a range.",
        listOf(ArgSpec("which", "取得", "Get", "choice", "first", listOf("first", "last", "index", "random", "range")),
            ArgSpec("index", "第幾項（從 1 起算）", "Item (from 1)", "number", 1), ArgSpec("to", "到第幾項（範圍）", "To (range)", "number", 3)),
        input = VType.Anything, output = VType.Same) { st ->
        fun at(n: Int) = if (n < 0) items.size + n else n - 1
        items = when (arg(st, "which")) {
            "first" -> items.take(1); "last" -> items.takeLast(1)
            "random" -> if (items.isEmpty()) emptyList() else listOf(items.random())
            "range" -> { val a = at(int(st, "index", 1)).coerceAtLeast(0); val b = at(int(st, "to", 3)).coerceAtMost(items.size - 1); if (a > b) emptyList() else items.subList(a, b + 1).toList() }
            else -> listOfNotNull(items.getOrNull(at(int(st, "index", 1))))
        }
    },
    FlowAction("list.count", "計數", "Count", CAT_LIST, "有幾個值。", "How many values there are.", input = VType.Anything, output = VType.Number) { items = listOf(items.size.toString()) },
    FlowAction("list.reverse", "反轉順序", "Reverse", CAT_LIST, "把順序倒過來。", "Reverses the order.", input = VType.Anything, output = VType.Same) { items = items.reversed() },
    FlowAction("list.unique", "移除重複", "Remove duplicates", CAT_LIST, "只保留第一次出現的值。", "Keeps the first of each value.",
        listOf(ArgSpec("ignoreCase", "不分大小寫", "Ignore case", "bool", false)), input = VType.Anything, output = VType.Same) { st ->
        items = if (bool(st, "ignoreCase")) items.distinctBy { it.lowercase() } else items.distinct()
    },
    FlowAction("list.shuffle", "隨機排列", "Shuffle", CAT_LIST, "以隨機順序排列。", "Puts the values in random order.", input = VType.Anything, output = VType.Same) { items = items.shuffled() },
    FlowAction("list.sort", "排序文字", "Sort values", CAT_LIST, "依文字、自然順序（file2 < file10）、數值或長度排序。", "Sorts as text, naturally (file2 < file10), as numbers or by length.",
        listOf(ArgSpec("by", "依據", "By", "choice", "natural", listOf("natural", "text", "number", "length")), ArgSpec("descending", "遞減", "Descending", "bool", false)),
        input = VType.Anything, output = VType.Same) { st ->
        val cmp: Comparator<String> = when (arg(st, "by")) {
            "text" -> String.CASE_INSENSITIVE_ORDER
            "number" -> compareBy { it.trim().replace(",", "").toDoubleOrNull() ?: Double.MAX_VALUE }
            "length" -> compareBy { it.length }
            else -> Comparator { a, b -> naturalCompare(a, b) }
        }
        items = items.sortedWith(if (bool(st, "descending")) cmp.reversed() else cmp)
    },
    FlowAction("list.add", "加入項目", "Add to list", CAT_LIST, "在開頭或結尾加入項目（每行一個），或加入一個變數的內容。", "Adds items (one per line) or a variable's values at the start or end.",
        listOf(ArgSpec("items", "項目", "Items", "multiline", ""), ArgSpec("variable", "或變數名稱", "Or a variable", "text", ""),
            ArgSpec("at", "位置", "Where", "choice", "end", listOf("end", "start"))), input = VType.Anything, output = VType.Same) { st ->
        val add = rawArg(st, "items").lines().filter { it.isNotEmpty() }.map { expand(it) } + (vars[arg(st, "variable").trim()] ?: emptyList())
        items = if (arg(st, "at") == "start") add + items else items + add
    },
    FlowAction("list.remove", "移除項目", "Remove from list", CAT_LIST, "移除第 N 項（負數從結尾），或移除與文字相同的值。", "Removes item N (negative = from the end) or values equal to a text.",
        listOf(ArgSpec("index", "第幾項（0 = 不依位置）", "Item (0 = not by position)", "number", 0), ArgSpec("equals", "或等於這段文字", "Or equal to", "text", "")),
        input = VType.Anything, output = VType.Same) { st ->
        val n = int(st, "index", 0); val eq = arg(st, "equals")
        val drop = if (n == 0) -1 else if (n < 0) items.size + n else n - 1
        items = items.filterIndexed { i, v -> i != drop && (eq.isEmpty() || v != eq) }
    },
    FlowAction("json.make", "字典", "Dictionary", CAT_LIST, "以「鍵=值」每行一組建立字典（JSON 物件）；數字、true/false 與 JSON 會保留型別。", "Makes a dictionary (JSON object) from key=value lines; numbers, true/false and JSON keep their type.",
        listOf(ArgSpec("entries", "項目（鍵=值）", "Entries (key=value)", "multiline", "name={item.name}\nsize={item.size}"),
            ArgSpec("perItem", "每個值各建一個", "One per value", "bool", false), ArgSpec("asText", "全部當成文字", "Everything as text", "bool", false)),
        input = VType.Anything, output = VType.Json) { st ->
        val raw = rawArg(st, "entries"); val asText = bool(st, "asText")
        fun make(item: String?, i: Int): String { val m = LinkedHashMap<String, Any?>(); keyValues(raw).forEach { (k, v) -> m[expand(k, item, i)] = JsonPath.value(expand(v, item, i), asText) }; return Json.write(m) }
        items = if (bool(st, "perItem")) items.mapIndexed { i, v -> make(v, i) } else listOf(make(items.firstOrNull(), 0))
    },
    FlowAction("json.get", "取得字典值", "Get dictionary value", CAT_LIST, "以路徑取得值：name、items[0].title、items[*].id；陣列會變成多個值。", "Gets values by path: name, items[0].title, items[*].id; arrays become several values.",
        listOf(ArgSpec("path", "路徑", "Path", "text", "name")), input = VType.Json, output = VType.Text) { st ->
        val path = arg(st, "path")
        items = items.flatMap { v -> JsonPath.get(JsonPath.parse(v), path).flatMap { r -> if (r is List<*> && !path.endsWith("]")) r.map { JsonPath.text(it) } else listOf(JsonPath.text(r)) } }
    },
    FlowAction("json.set", "設定字典值", "Set dictionary value", CAT_LIST, "在路徑設定值（不存在時會建立）。", "Sets a value at a path (created when missing).",
        listOf(ArgSpec("path", "路徑", "Path", "text", "name"), ArgSpec("value", "值", "Value", "text", ""), ArgSpec("asText", "當成文字", "As text", "bool", false)),
        input = VType.Json, output = VType.Json) { st ->
        val path = arg(st, "path"); val asText = bool(st, "asText")
        items = (items.ifEmpty { listOf("{}") }).mapIndexed { i, v -> Json.write(JsonPath.set(JsonPath.parse(v.ifBlank { "{}" }), path, JsonPath.value(expand(rawArg(st, "value"), v, i), asText))) }
    },
    FlowAction("json.keys", "字典的鍵", "Dictionary keys", CAT_LIST, "每個字典的所有鍵。", "Every key of each dictionary.", input = VType.Json, output = VType.Text) {
        items = items.flatMap { v -> (JsonPath.parse(v) as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList() }
    },
    FlowAction("json.values", "字典的值", "Dictionary values", CAT_LIST, "每個字典（或陣列）的所有值。", "Every value of each dictionary (or array).", input = VType.Json, output = VType.Text) {
        items = items.flatMap { v -> when (val j = JsonPath.parse(v)) { is Map<*, *> -> j.values.map { JsonPath.text(it) }; is List<*> -> j.map { JsonPath.text(it) }; else -> listOf(JsonPath.text(j)) } }
    },
    FlowAction("json.encode", "轉成 JSON", "To JSON", CAT_LIST, "把值轉成 JSON：陣列（每個值一項）、物件（鍵=值）或字串。", "Turns the values into JSON: an array, an object (key=value lines) or strings.",
        listOf(ArgSpec("as", "格式", "As", "choice", "array", listOf("array", "object", "string")), ArgSpec("pretty", "易讀排版", "Pretty", "bool", true)),
        input = VType.Anything, output = VType.Json) { st ->
        fun w(v: Any?) = if (bool(st, "pretty")) Json.write(v) else Json.write(v).lines().joinToString("") { it.trim() }.replace("\":", "\": ")
        items = when (arg(st, "as")) {
            "object" -> listOf(w(LinkedHashMap<String, Any?>().apply { items.flatMap { keyValues(it) }.forEach { (k, v) -> put(k, JsonPath.value(v, false)) } }))
            "string" -> items.map { Json.write(it) }
            else -> listOf(w(items.map { JsonPath.value(it, false) }))
        }
    },
    FlowAction("json.decode", "解析 JSON", "From JSON", CAT_LIST, "讀取 JSON：陣列變成多個值；物件可排版或展開成「路徑=值」。", "Reads JSON: arrays become values; objects are formatted or flattened to path=value.",
        listOf(ArgSpec("as", "結果", "Result", "choice", "values", listOf("values", "pretty", "flatten"))), input = VType.Text,
        outputFor = { st -> if ((st.args["as"] ?: "values") == "flatten") VType.Text else VType.Json }) { st ->
        val mode = arg(st, "as")
        fun flat(prefix: String, v: Any?, out: MutableList<String>) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> flat(if (prefix.isEmpty()) k.toString() else "$prefix.$k", x, out) }
                is List<*> -> v.forEachIndexed { i, x -> flat("$prefix[$i]", x, out) }
                else -> out += "$prefix=${JsonPath.text(v)}"
            }
        }
        items = items.flatMap { s ->
            val j = JsonPath.parse(s)
            when (mode) {
                "pretty" -> listOf(Json.write(j))
                "flatten" -> ArrayList<String>().also { flat("", j, it) }
                else -> if (j is List<*>) j.map { JsonPath.text(it) } else listOf(JsonPath.text(j))
            }
        }
    },

    // ================================================================ numbers, dates, encoding
    FlowAction("number.calc", "計算", "Calculate", CAT_DATA, "計算算式：+ - * / % ^、括號、round(x,2) floor ceil abs sqrt min max；可用 {first}、{count}，逐項時用 {item}。", "Evaluates a formula: + - * / % ^, parentheses, round(x,2) floor ceil abs sqrt min max; {first}, {count}, or {item} per value.",
        listOf(ArgSpec("formula", "算式", "Formula", "text", "{first} * 2"), ArgSpec("perItem", "每個值各算一次", "Once per value", "bool", false)),
        input = VType.Anything, output = VType.Number) { st ->
        val f = rawArg(st, "formula")
        items = if (bool(st, "perItem")) items.mapIndexed { i, v -> numText(Calc.eval(expand(f, v, i))) } else listOf(numText(Calc.eval(expand(f))))
    },
    FlowAction("number.stats", "統計", "Statistics", CAT_DATA, "所有數字的總和、平均、最小、最大或中位數。", "Sum, average, minimum, maximum or median of the numbers.",
        listOf(ArgSpec("of", "計算", "Get", "choice", "sum", listOf("sum", "average", "min", "max", "median", "count"))), input = VType.Number, output = VType.Number) { st ->
        val n = numbers().sorted()
        val r = when (arg(st, "of")) {
            "average" -> if (n.isEmpty()) 0.0 else n.average(); "min" -> n.firstOrNull() ?: 0.0; "max" -> n.lastOrNull() ?: 0.0
            "median" -> if (n.isEmpty()) 0.0 else if (n.size % 2 == 1) n[n.size / 2] else (n[n.size / 2 - 1] + n[n.size / 2]) / 2
            "count" -> n.size.toDouble(); else -> n.sum()
        }
        items = listOf(numText(r))
    },
    FlowAction("number.format", "數字格式", "Format number", CAT_DATA, "小數位數、千分位、百分比或檔案大小（KB / MB）。", "Decimals, thousands separators, percent or file size (KB / MB).",
        listOf(ArgSpec("style", "樣式", "Style", "choice", "decimal", listOf("decimal", "percent", "fileSize")), ArgSpec("decimals", "小數位數", "Decimals", "number", 2),
            ArgSpec("grouping", "千分位", "Thousands separators", "bool", true)), input = VType.Number, output = VType.Text) { st ->
        val d = int(st, "decimals", 2).coerceIn(0, 12); val g = bool(st, "grouping")
        items = numbers().map { x ->
            when (arg(st, "style")) {
                "percent" -> String.format("%${if (g) "," else ""}.${d}f%%", x * 100)
                "fileSize" -> Fs.sizeText(x.toLong())
                else -> String.format("%${if (g) "," else ""}.${d}f", x)
            }
        }
    },
    FlowAction("number.random", "隨機數字", "Random number", CAT_DATA, "介於最小與最大值之間的隨機整數。", "Random whole numbers between a minimum and maximum.",
        listOf(ArgSpec("min", "最小", "Minimum", "number", 1), ArgSpec("max", "最大", "Maximum", "number", 100), ArgSpec("count", "數量", "How many", "number", 1)),
        input = VType.None, output = VType.Number) { st ->
        val a = int(st, "min", 1); val b = int(st, "max", 100)
        items = List(int(st, "count", 1).coerceIn(1, 100_000)) { (minOf(a, b)..maxOf(a, b)).random().toString() }
    },
    FlowAction("time.now", "目前日期時間", "Current date", CAT_DATA, "現在的日期時間；格式如 yyyy-MM-dd HH:mm:ss、iso、epoch。", "The date and time now; formats like yyyy-MM-dd HH:mm:ss, iso, epoch.",
        listOf(ArgSpec("format", "格式", "Format", "text", FlowTime.DEFAULT)), input = VType.None, output = VType.Date) { st ->
        items = listOf(FlowTime.format(LocalDateTime.now(), arg(st, "format")))
    },
    FlowAction("time.format", "格式化日期", "Format date", CAT_DATA, "讀取日期（多種寫法、epoch 秒或毫秒）並換成指定格式；也可取出年、月、星期等。", "Reads dates (many forms, epoch seconds or ms) and writes them in a format; also year, month, weekday…",
        listOf(ArgSpec("format", "格式（如 yyyy/MM/dd、EEEE、iso、epoch）", "Format (e.g. yyyy/MM/dd, EEEE, iso, epoch)", "text", "yyyy-MM-dd")),
        input = VType.Date, output = VType.Date) { st ->
        val f = arg(st, "format")
        items = items.map { v -> FlowTime.parse(v)?.let { FlowTime.format(it, f) } ?: throw FlowStopped((if (S.zh) "看不懂的日期：" else "Not a date: ") + v) }
    },
    FlowAction("time.adjust", "調整日期", "Adjust date", CAT_DATA, "加上或減去（負數）一段時間。", "Adds (or with a negative amount, subtracts) time.",
        listOf(ArgSpec("amount", "數量", "Amount", "number", 1), ArgSpec("unit", "單位", "Unit", "choice", "days", FlowTime.units),
            ArgSpec("format", "格式", "Format", "text", FlowTime.DEFAULT)), input = VType.Date, output = VType.Date) { st ->
        val n = arg(st, "amount").trim().toLongOrNull() ?: 0; val u = FlowTime.unit(arg(st, "unit")); val f = arg(st, "format")
        items = items.ifEmpty { listOf("now") }.map { v -> FlowTime.format((FlowTime.parse(v) ?: throw FlowStopped((if (S.zh) "看不懂的日期：" else "Not a date: ") + v)).plus(n, u), f) }
    },
    FlowAction("time.diff", "日期相差", "Time between", CAT_DATA, "每個日期到另一個日期（預設現在）相差多少。", "How long from each date to another (now by default).",
        listOf(ArgSpec("to", "到", "To", "text", "now"), ArgSpec("unit", "單位", "Unit", "choice", "days", FlowTime.units)), input = VType.Date, output = VType.Number) { st ->
        val to = FlowTime.parse(arg(st, "to")) ?: throw FlowStopped((if (S.zh) "看不懂的日期：" else "Not a date: ") + arg(st, "to"))
        val u = FlowTime.unit(arg(st, "unit"))
        items = items.map { v -> (FlowTime.parse(v)?.let { u.between(it, to) } ?: throw FlowStopped((if (S.zh) "看不懂的日期：" else "Not a date: ") + v)).toString() }
    },
    FlowAction("base64.encode", "Base64 編碼", "Base64 encode", CAT_DATA, "把文字或檔案內容編成 Base64。", "Encodes text or file contents as Base64.",
        listOf(ArgSpec("source", "來源", "Source", "choice", "text", listOf("text", "file")), ArgSpec("urlSafe", "網址安全（- _）", "URL-safe (- _)", "bool", false)),
        input = VType.Anything, output = VType.Text) { st ->
        val enc = if (bool(st, "urlSafe")) java.util.Base64.getUrlEncoder().withoutPadding() else java.util.Base64.getEncoder()
        items = if (arg(st, "source") == "file") files().filter { File(it).isFile }.map { p ->
            require(File(p).length() <= 64L shl 20) { (if (S.zh) "檔案超過 64 MiB：" else "File over 64 MiB: ") + p }
            enc.encodeToString(File(p).readBytes())
        } else items.map { enc.encodeToString(it.toByteArray(Charsets.UTF_8)) }
    },
    FlowAction("base64.decode", "Base64 解碼", "Base64 decode", CAT_DATA, "把 Base64 解回文字，或存成檔案（可用 {item.index} 標記）。", "Decodes Base64 to text, or saves it as files ({item.index} tokens allowed).",
        listOf(ArgSpec("to", "結果", "Result", "choice", "text", listOf("text", "file")), ArgSpec("path", "檔案路徑", "File path", "path", "{desktop}\\decoded-{item.index:00}.bin")),
        input = VType.Text, outputFor = { st -> if ((st.args["to"] ?: "text") == "file") VType.Files else VType.Text }) { st ->
        fun dec(v: String): ByteArray {
            val t = v.trim().substringAfter("base64,").replace(Regex("\\s"), "")
            return try { if (t.contains('-') || t.contains('_')) java.util.Base64.getUrlDecoder().decode(t) else java.util.Base64.getDecoder().decode(t) }
            catch (e: IllegalArgumentException) { throw FlowStopped((if (S.zh) "不是有效的 Base64：" else "Not valid Base64: ") + e.message) }
        }
        items = if (arg(st, "to") == "file") items.mapIndexed { i, v ->
            val f = freeName(File(expand(rawArg(st, "path"), "", i)))
            if (dryRun) log("   → ${f.path}") else { f.parentFile?.mkdirs(); f.writeBytes(dec(v)) }
            f.path
        } else items.map { String(dec(it), Charsets.UTF_8) }
    },

    // ================================================================ files (create / read / check)
    FlowAction("file.create", "建立檔案", "Create file", CAT_FILES, "建立文字檔（可寫入內容，可用標記）。", "Creates a text file (with content; tokens allowed).",
        listOf(ArgSpec("path", "路徑", "Path", "path", "{folder}\\新文字文件.txt"), ArgSpec("content", "內容", "Content", "multiline", ""),
            ArgSpec("ifExists", "已存在時", "If it exists", "choice", "rename", listOf("rename", "overwrite", "skip"))),
        changes = true, input = VType.None, output = VType.Files) { st ->
        var f = File(arg(st, "path"))
        if (f.exists()) when (arg(st, "ifExists")) { "skip" -> { items = listOf(f.path); return@FlowAction }; "overwrite" -> {}; else -> f = freeName(f) }
        if (dryRun) log("   + ${f.path}") else { f.parentFile?.mkdirs(); f.writeText(arg(st, "content").replace("\r\n", "\n").replace("\n", "\r\n"), Charsets.UTF_8) }
        items = listOf(f.path)
    },
    FlowAction("file.exists", "檢查檔案是否存在", "File exists", CAT_FILES, "篩選出存在（或不存在）的路徑，或得到是/否。", "Keeps the paths that exist (or don't), or gives true/false.",
        listOf(ArgSpec("mode", "結果", "Result", "choice", "existing", listOf("existing", "missing", "each", "all"))), input = VType.Text,
        outputFor = { st -> when (st.args["mode"] ?: "existing") { "each", "all" -> VType.Bool; else -> VType.Files } }) { st ->
        items = when (arg(st, "mode")) {
            "missing" -> items.filter { !File(it).exists() }
            "each" -> items.map { File(it).exists().toString() }
            "all" -> listOf((items.isNotEmpty() && items.all { File(it).exists() }).toString())
            else -> items.filter { File(it).exists() }
        }
    },
    FlowAction("file.detail", "取得檔案詳細資料", "Get file detail", CAT_FILES, "每個項目的一項資料：名稱、副檔名、資料夾、大小、日期、類型、屬性…", "One detail of each item: name, extension, folder, size, dates, kind, attributes…",
        listOf(ArgSpec("detail", "資料", "Detail", "choice", "name", listOf("name", "stem", "extension", "parent", "path", "size", "sizeText", "modified", "created", "accessed", "isFolder", "readOnly", "hidden", "lines")),
            ArgSpec("format", "日期格式", "Date format", "text", FlowTime.DEFAULT)), input = VType.Files,
        outputFor = { st -> when (st.args["detail"] ?: "name") { "size", "lines" -> VType.Number; "modified", "created", "accessed" -> VType.Date; "isFolder", "readOnly", "hidden" -> VType.Bool; "parent", "path" -> VType.Files; else -> VType.Text } }) { st ->
        val d = arg(st, "detail"); val fmt = arg(st, "format")
        items = files().map { p ->
            val f = File(p)
            val a = runCatching { java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.DosFileAttributes::class.java) }.getOrNull()
            fun date(t: java.nio.file.attribute.FileTime?) = t?.let { FlowTime.format(LocalDateTime.ofInstant(it.toInstant(), ZoneId.systemDefault()), fmt) } ?: ""
            when (d) {
                "stem" -> f.nameWithoutExtension; "extension" -> f.extension; "parent" -> f.parent ?: ""; "path" -> f.absolutePath
                "size" -> (if (f.isDirectory) FsExtra.folderSize(p) else f.length()).toString()
                "sizeText" -> Fs.sizeText(if (f.isDirectory) FsExtra.folderSize(p) else f.length())
                "modified" -> date(a?.lastModifiedTime()); "created" -> date(a?.creationTime()); "accessed" -> date(a?.lastAccessTime())
                "isFolder" -> f.isDirectory.toString(); "readOnly" -> (a?.isReadOnly == true).toString(); "hidden" -> (a?.isHidden == true).toString()
                "lines" -> if (f.isFile) f.bufferedReader().useLines { it.count() }.toString() else "0"
                else -> f.name
            }
        }
    },

    // ================================================================ control
    FlowAction("if", "如果", "If", CAT_CONTROL, "條件成立時執行「則」的步驟，否則執行「否則」的步驟。", "Runs the Then steps when the condition holds, else the Otherwise steps.",
        listOf(ArgSpec("subject", "檢查", "Check", "text", "{first}"), ArgSpec("op", "條件", "Condition", "choice", "notEmpty", FlowCompare.ops),
            ArgSpec("value", "比較值", "Value", "text", ""), ArgSpec("ignoreCase", "不分大小寫", "Ignore case", "bool", true),
            ArgSpec("then", "則", "Then", "steps", emptyList<FlowStep>()), ArgSpec("else", "否則", "Otherwise", "steps", emptyList<FlowStep>())),
        input = VType.Anything, output = VType.Anything) { st ->
        val ok = FlowCompare.test(arg(st, "op"), arg(st, "subject"), arg(st, "value"), bool(st, "ignoreCase"))
        log("   " + (if (ok) (if (S.zh) "條件成立 → 則" else "true → Then") else (if (S.zh) "條件不成立 → 否則" else "false → Otherwise")))
        @Suppress("UNCHECKED_CAST") runSteps((st.args[if (ok) "then" else "else"] as? List<FlowStep>).orEmpty())
    },
    FlowAction("repeat", "重複", "Repeat", CAT_CONTROL, "把內含的步驟執行 N 次（{var.index} 是第幾次），收集每次的結果。", "Runs the inner steps N times ({var.index} is the round) and collects the results.",
        listOf(ArgSpec("times", "次數", "Times", "number", 3), ArgSpec("steps", "步驟", "Steps", "steps", emptyList<FlowStep>())), input = VType.Anything, output = VType.Anything) { st ->
        @Suppress("UNCHECKED_CAST") val inner = (st.args["steps"] as? List<FlowStep>).orEmpty()
        val start = items; val out = ArrayList<String>()
        repeat(int(st, "times", 3).coerceIn(0, 100_000)) { i -> items = start; vars["index"] = listOf((i + 1).toString()); runSteps(inner); out += items }
        items = out
    },
    FlowAction("wait", "等待", "Wait", CAT_CONTROL, "暫停幾秒（可有小數）。", "Pauses for some seconds (decimals allowed).",
        listOf(ArgSpec("seconds", "秒數", "Seconds", "number", 1)), input = VType.Anything, output = VType.Same) { st ->
        val ms = ((arg(st, "seconds").trim().toDoubleOrNull() ?: 1.0) * 1000).toLong().coerceIn(0, 24 * 3600_000L)
        if (dryRun) log("   ⏱ ${ms / 1000.0} s") else delay(ms)
    },
    FlowAction("wait.skippable", "等待（按任意鍵略過）", "Wait (any key skips)", CAT_CONTROL, "顯示倒數；按任意鍵或按一下就提早繼續。{var.skipped} 為 true 表示被略過。", "Shows a countdown; any key or click continues early. {var.skipped} is true when skipped.",
        listOf(ArgSpec("seconds", "秒數", "Seconds", "number", 10), ArgSpec("message", "訊息", "Message", "text", "按任意鍵繼續…")), input = VType.Anything, output = VType.Same) { st ->
        val secs = (arg(st, "seconds").trim().toDoubleOrNull() ?: 10.0).coerceIn(0.0, 24 * 3600.0)
        if (dryRun) { log("   ⏱ ${numText(secs)} s"); return@FlowAction }
        val q = FlowPrompt("wait", arg(st, "message"), emptyList(), numText(secs), CompletableDeferred())
        val r = withTimeoutOrNull((secs * 1000).toLong()) { ask(q) ?: "closed" }
        if (r == null) withContext(Dispatchers.Main) { if (s.flowPrompt === q) s.flowPrompt = null }
        vars["skipped"] = listOf((r != null).toString())
        if (r != null) log(if (S.zh) "   已略過等待" else "   skipped")
    },
    FlowAction("stop", "結束捷徑", "Stop shortcut", CAT_CONTROL, "在這裡結束，顯示訊息。", "Ends the shortcut here with a message.",
        listOf(ArgSpec("message", "訊息", "Message", "text", "")), input = VType.Anything, output = VType.None) { st ->
        throw FlowStopped(arg(st, "message").ifEmpty { if (S.zh) "已結束" else "Stopped" })
    },
    FlowAction("flow.run", "執行捷徑", "Run shortcut", CAT_CONTROL, "以目前的值為輸入執行另一個捷徑，結果為它最後的值。", "Runs another shortcut with the values as its input; its final values are the result.",
        listOf(ArgSpec("shortcut", "捷徑", "Shortcut", "flow", ""), ArgSpec("params", "參數（id=值，每行一個）", "Parameters (id=value per line)", "multiline", "")),
        input = VType.Anything, output = VType.Anything) { st ->
        val ref = arg(st, "shortcut").trim()
        val f = FlowStore.all().firstOrNull { it.id == ref } ?: FlowStore.all().firstOrNull { it.name.equals(ref, true) }
            ?: throw FlowStopped((if (S.zh) "找不到捷徑：" else "No such shortcut: ") + ref)
        if (depth >= FlowRun.MAX_DEPTH) throw FlowStopped(if (S.zh) "捷徑互相呼叫太多層（上限 ${FlowRun.MAX_DEPTH}）" else "Shortcuts call each other too deeply (max ${FlowRun.MAX_DEPTH})")
        val given = keyValues(rawArg(st, "params")).associate { (k, v) -> k to expand(v) }
        val params = LinkedHashMap<String, String>()
        val child = FlowRun(s, f, items, params, dryRun, ask, { onLog("  │ $it") }, depth + 1)
        f.params.forEach { p -> params[p.id] = given[p.id] ?: child.expand(p.default) }
        log("   ↳ ${f.name}")
        child.runSteps(f.steps)
        items = child.items
    },

    // ================================================================ debug
    FlowAction("debug.show", "顯示結果", "Show result", CAT_DEBUG, "暫停並顯示目前的值與數量，可選擇繼續或停止。", "Pauses to show the current values; continue or stop.",
        listOf(ArgSpec("title", "標題", "Title", "text", "目前的值")), input = VType.Anything, output = VType.Same) { st ->
        if (ask(FlowPrompt("show", arg(st, "title"), items, "", CompletableDeferred())) == null) cancelled()
    },
    FlowAction("debug.log", "寫入記錄", "Log", CAT_DEBUG, "把目前的值（最多 50 個）寫進執行記錄，不暫停。", "Writes the values (up to 50) to the run log without pausing.",
        listOf(ArgSpec("label", "標籤", "Label", "text", "debug")), input = VType.Anything, output = VType.Same) { st ->
        val label = arg(st, "label")
        log("   [$label] ${items.size} " + (if (S.zh) "個值" else "values"))
        items.take(50).forEachIndexed { i, v -> log("   [$label] ${i + 1}: " + v.replace("\n", "⏎").take(300)) }
    },
)
