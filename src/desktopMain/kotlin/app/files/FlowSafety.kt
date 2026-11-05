package app.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/*
 * What a shortcut may do without asking, so that a shortcut someone else wrote (imported, or a
 * file dropped into the shortcuts folder) cannot quietly send files away or run programs.
 *
 *  - NetPolicy: the hosts http.request and url.open may reach. Anything else asks first
 *    ("allow once" / "always trust this host") and stops when refused.
 *  - FlowTrust: steps that run programs or send data out (shell.run, app.launch, app.cli, open,
 *    open.with, url.open with a non-web address, email.send, sms.send, flow.run) ask once per
 *    step and run unless the person trusted this exact set of steps. Trust is stored outside the
 *    shortcut file, so a file cannot mark itself trusted; changing one of those steps asks again.
 */

/** The "Permissions" section of the format reference (FlowActions.specMarkdown, docs/SHORTCUTS-SPEC.md). */
const val SAFETY_SPEC = """## Permissions

A shortcut file cannot grant itself permissions; the app keeps them (Shortcuts → Permissions).

**Trusted hosts.** `http.request` and `url.open` (http/https) reach only hosts on the list without asking; any other host asks
first (*allow this time* / *always trust*) and stops when refused. Entries: `example.com` (that host), `*.example.com` (the domain
and its subdomains), `10.0.0.5`, `192.168.0.0/16`, `::1`. Redirects are followed by the app and each hop is checked; https never
redirects to http. Default list: `api.github.com`, `www.bing.com`.

**Trusted shortcuts.** These steps ask before they run unless the shortcut is trusted: `shell.run`, `app.launch`, `app.cli`,
`open`, `open.with`, `email.send`, `sms.send`, `flow.run`, and `url.open` with a non-web address (`mailto:` excepted).
Trust covers exactly those steps (with their arguments): changing one asks again, other edits don't. Imported shortcuts start
untrusted; built-in samples are trusted as shipped; a dry run never asks.

"""

/** The hosts shortcuts may reach over the network without asking. */
object NetPolicy {
    private const val KEY_ALLOW = "flows.net.allow"
    private const val KEY_RESTRICT = "flows.net.restrict"

    /** Entries, one per host: "example.com", "*.example.com" (any subdomain and the domain), "10.0.0.5", "192.168.0.0/16", "::1". */
    var entries by mutableStateOf(load()); private set
    /** Off: every host is allowed (the person chose to turn the check off). */
    var restrict by mutableStateOf(Prefs.bool(KEY_RESTRICT, true)); private set

    private fun load(): List<String> = Prefs.str(KEY_ALLOW, "api.github.com\nwww.bing.com").split('\n', ',').mapNotNull { normalizeEntry(it) }.distinct()

    fun saveEntries(list: List<String>) {
        entries = list.mapNotNull { normalizeEntry(it) }.distinct()
        Prefs.put(mapOf(KEY_ALLOW to entries.joinToString("\n")))
    }

    fun saveRestrict(on: Boolean) { restrict = on; Prefs.put(mapOf(KEY_RESTRICT to on)) }

    fun add(host: String) { normalizeEntry(host)?.let { if (it !in entries) saveEntries(entries + it) } }

    /** Tests only: a policy that lives in memory. */
    internal fun testSet(list: List<String>, restrictOn: Boolean = true) { entries = list.mapNotNull { normalizeEntry(it) }; restrict = restrictOn }

    /**
     * One entry as stored, or null when it isn't a host, an address or a range. A pasted URL is
     * cut down to its host ("https://user@Example.COM:8443/x" → "example.com").
     */
    fun normalizeEntry(raw: String): String? {
        var t = raw.trim().lowercase()
        if (t.isEmpty() || t.startsWith("#")) return null
        // A range: "192.168.0.0/16", "fd00::/8".
        Regex("\\[?([0-9a-f:.]+)]?/(\\d{1,3})").matchEntire(t)?.let { m ->
            val ip = literalIp(m.groupValues[1]) ?: return null
            val bits = m.groupValues[2].toInt()
            if (bits > ip.address.size * 8) return null
            return ip.hostAddress.substringBefore('%') + "/" + bits
        }
        if ("://" in t) t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (t.startsWith("[")) t = t.substringAfter('[').substringBefore(']')
        else if (t.count { it == ':' } == 1) t = t.substringBefore(':') // host:port (an IPv6 address has two or more ':')
        val wildcard = t.startsWith("*.")
        val host = normalizeHost(t.removePrefix("*.")) ?: return null
        if (wildcard && literalIp(host) != null) return null
        return if (wildcard) "*.$host" else host
    }

    /** The host as compared: lower case, ASCII (IDN), no trailing dot or brackets; null when empty or not a host name / address. */
    fun normalizeHost(h: String?): String? {
        var t = h?.trim()?.lowercase()?.trimEnd('.') ?: return null
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length - 1)
        if (t.isEmpty()) return null
        literalIp(t)?.let { return it.hostAddress.substringBefore('%') }
        val ascii = runCatching { IDN.toASCII(t, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        if (!ascii.matches(Regex("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*"))) return null
        return ascii
    }

    /** An IP address written out (dotted IPv4 or IPv6), never a name: nothing here looks a name up in DNS. */
    fun literalIp(t: String): InetAddress? {
        if (Regex("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}").matches(t)) return InetAddress.getByName(t)
        val v6 = t.substringBefore('%')
        // Only hex digits, ':' and '.' (an embedded IPv4): getByName parses it without a DNS lookup.
        if (':' in v6 && v6.all { it in "0123456789abcdefABCDEF:." }) return runCatching { InetAddress.getByName(v6) }.getOrNull()
        return null
    }

    /** True when shortcuts may reach [host] without asking. */
    fun allows(host: String?, list: List<String> = entries): Boolean {
        if (!restrict) return true
        val h = normalizeHost(host) ?: return false
        val ip = literalIp(h)
        return list.any { e ->
            when {
                '/' in e -> ip != null && inRange(ip, e)
                e.startsWith("*.") -> ip == null && (h == e.drop(2) || h.endsWith("." + e.drop(2)))
                else -> h == e || (ip != null && literalIp(e)?.let { it == ip } == true)
            }
        }
    }

    private fun inRange(ip: InetAddress, cidr: String): Boolean {
        val base = literalIp(cidr.substringBefore('/')) ?: return false
        val bits = cidr.substringAfter('/').toIntOrNull() ?: return false
        if ((base is Inet4Address) != (ip is Inet4Address)) return false
        val a = base.address; val b = ip.address
        var left = bits
        for (i in a.indices) {
            if (left <= 0) return true
            val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            left -= 8
        }
        return true
    }
}

/** Which shortcuts the person trusts to run programs and send data without asking. */
object FlowTrust {
    private const val KEY = "flows.trusted"

    /** Steps that run other programs or send data out of this PC. */
    val gated = setOf("shell.run", "app.launch", "app.cli", "open", "open.with", "url.open", "email.send", "sms.send", "flow.run")

    private var trusted: MutableSet<String> = Prefs.raw(KEY)?.split(',')?.filter { it.length == 64 }?.toMutableSet() ?: mutableSetOf()
    /** No list saved yet (first run of a version with trust): shortcuts already there were made before it and are trusted once. */
    private var migrate = Prefs.raw(KEY) == null

    /** The steps that need trust, in order (nested steps included), each as action + its effective arguments. */
    fun gatedSteps(steps: List<FlowStep>): List<FlowStep> = steps.flatMap { st ->
        val spec = FlowActions.byId[st.action]
        val nested = spec?.args.orEmpty().filter { it.type == "steps" }.flatMap { a -> gatedSteps((st.args[a.id] as? List<*>).orEmpty().filterIsInstance<FlowStep>()) }
        (if (st.enabled && st.action in gated) listOf(st) else emptyList()) + nested
    }

    /** One step as text that changes only when what it does changes (defaults filled in, numbers written one way). */
    fun stepKey(st: FlowStep): String {
        val spec = FlowActions.byId[st.action]
        val args = spec?.args.orEmpty().filter { it.type != "steps" }.joinToString("\u0001") { a ->
            val v = st.args[a.id] ?: a.default
            a.id + "=" + when (v) { is Number -> numText(v.toDouble()); null -> ""; else -> v.toString() }
        }
        return st.action + "\u0002" + st.input + "\u0002" + args
    }

    /** SHA-256 of the gated steps; null when the shortcut has none (nothing to trust). */
    fun signature(f: Flow): String? {
        val g = gatedSteps(f.steps)
        if (g.isEmpty()) return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(g.joinToString("\u0003") { stepKey(it) }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * A sample is trusted only as shipped: one edited in the JSON tab keeps builtIn = true, so its
     * steps are compared with the real sample's.
     */
    fun isTrusted(f: Flow): Boolean {
        val sig = signature(f) ?: return true
        if (f.builtIn && FlowStore.samples().firstOrNull { it.id == f.id }?.let { signature(it) } == sig) return true
        return sig in trusted
    }

    fun trust(f: Flow) { signature(f)?.let { if (trusted.add(it)) save() } }

    fun untrustAll() { trusted.clear(); save() }

    /** Called with the person's shortcuts after loading: the first time, they are all trusted (see [migrate]). */
    fun loaded(mine: List<Flow>) {
        if (!migrate) return
        migrate = false
        mine.forEach { f -> signature(f)?.let { trusted += it } }
        save()
    }

    /** Tests only. */
    internal fun testReset(trustedFlows: List<Flow> = emptyList()) { trusted = trustedFlows.mapNotNull { signature(it) }.toMutableSet(); migrate = false }

    private fun save() { if (!Prefs.readOnly) Prefs.put(mapOf(KEY to trusted.joinToString(","))) }

    /** What a step is about to do, for the question. */
    fun describe(run: FlowRun, st: FlowStep): String {
        val a = FlowActions.byId[st.action]
        fun arg(id: String) = runCatching { run.arg(st, id) }.getOrDefault("")
        val what = when (st.action) {
            "shell.run" -> arg("shell") + ":\n" + run.rawArg(st, "script").lines().take(12).joinToString("\n")
            "app.launch" -> arg("app") + " " + run.rawArg(st, "args") + if (run.bool(st, "admin")) "  (admin)" else ""
            "app.cli" -> arg("app") + " --help"
            "open" -> run.files().take(8).joinToString("\n")
            "open.with" -> arg("app") + "\n" + run.files().take(8).joinToString("\n")
            "url.open" -> arg("url")
            "email.send" -> arg("method") + " → " + arg("to") + (arg("cc").takeIf { it.isNotBlank() }?.let { ", cc $it" } ?: "") + (arg("bcc").takeIf { it.isNotBlank() }?.let { ", bcc $it" } ?: "") +
                "\n" + run.files().size + (if (S.zh) " 個檔案" else " files")
            "sms.send" -> arg("to")
            "flow.run" -> arg("shortcut")
            else -> ""
        }
        return (a?.label ?: st.action) + " (" + st.action + ")\n" + what
    }
}

/** A run's answers so far: steps allowed once and hosts allowed once. */
class FlowPermissions {
    val steps = HashSet<String>()
    val hosts = HashSet<String>()
}

/** Asks before a step that needs trust, unless the shortcut is trusted or this step was allowed in this run. */
suspend fun FlowRun.requireTrust(st: FlowStep) {
    if (dryRun || FlowTrust.isTrusted(flow)) return
    val key = FlowTrust.stepKey(st)
    if (key in permissions.steps) return
    val all = FlowTrust.gatedSteps(flow.steps)
    val title = if (S.zh) "「${flow.name}」不是受信任的捷徑，想要執行程式或傳送資料" else "\"${flow.name}\" isn't a trusted shortcut and wants to run a program or send data"
    val detail = FlowTrust.describe(this, st) +
        if (all.size > 1) "\n\n" + (if (S.zh) "這個捷徑共有 ${all.size} 個需要權限的步驟：" else "All ${all.size} steps that need permission:") + "\n" +
            all.joinToString("\n") { "  • " + (FlowActions.byId[it.action]?.label ?: it.action) + " (" + it.action + ")" } else ""
    val once = if (S.zh) "允許這一步" else "Allow this step"
    val always = if (S.zh) "信任這個捷徑" else "Trust this shortcut"
    when (ask(FlowPrompt("permission", title, listOf(once, always), detail, kotlinx.coroutines.CompletableDeferred()))) {
        once -> permissions.steps += key
        always -> FlowTrust.trust(flow)
        else -> throw FlowStopped(if (S.zh) "已拒絕：" + st.action else "Not allowed: " + st.action)
    }
}

/** Asks before reaching a host that isn't on the list (http.request, url.open); stops when refused. */
suspend fun FlowRun.requireHost(uri: URI) {
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") throw FlowStopped((if (S.zh) "只能連到 http:// 或 https:// 網址：" else "Only http:// and https:// addresses: ") + uri)
    val host = NetPolicy.normalizeHost(uri.host) ?: throw FlowStopped((if (S.zh) "網址沒有主機名稱：" else "No host in the address: ") + uri)
    if (NetPolicy.allows(host) || host in permissions.hosts) return
    val title = if (S.zh) "「${flow.name}」想要連線到 $host" else "\"${flow.name}\" wants to connect to $host"
    val detail = (if (S.zh) "這個主機不在信任的網域清單中（捷徑 → 權限）。\n\n" else "This host isn't on the trusted list (Shortcuts → Permissions).\n\n") + uri
    val once = if (S.zh) "允許這一次" else "Allow this time"
    val always = (if (S.zh) "永遠信任 " else "Always trust ") + host
    when (ask(FlowPrompt("permission", title, listOf(once, always), detail, kotlinx.coroutines.CompletableDeferred()))) {
        once -> permissions.hosts += host
        always -> NetPolicy.add(host)
        else -> throw FlowStopped((if (S.zh) "未允許連線到 " else "Not allowed to connect to ") + host)
    }
}
