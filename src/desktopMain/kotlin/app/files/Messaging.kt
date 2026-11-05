package app.files

import java.io.File
import java.net.URLEncoder
import java.util.Base64

/**
 * Mail and text messages for shortcuts. Accounts live in the settings file; passwords and tokens
 * are encrypted with DPAPI (WinSys.protect), so only this Windows user on this PC can read them.
 */
object MailAccounts {
    var smtpHost: String get() = Prefs.str("flow.smtp.host", ""); set(v) = Prefs.put(mapOf("flow.smtp.host" to v))
    var smtpPort: Int get() = Prefs.int("flow.smtp.port", 587); set(v) = Prefs.put(mapOf("flow.smtp.port" to v))
    /** "starttls" (587), "ssl" (465) or "none". */
    var smtpSecurity: String get() = Prefs.str("flow.smtp.security", "starttls"); set(v) = Prefs.put(mapOf("flow.smtp.security" to v))
    var smtpUser: String get() = Prefs.str("flow.smtp.user", ""); set(v) = Prefs.put(mapOf("flow.smtp.user" to v))
    var smtpFrom: String get() = Prefs.str("flow.smtp.from", ""); set(v) = Prefs.put(mapOf("flow.smtp.from" to v))
    var smtpPassword: String get() = WinSys.unprotect(Prefs.str("flow.smtp.pass", "")); set(v) = Prefs.put(mapOf("flow.smtp.pass" to WinSys.protect(v)))
    var twilioSid: String get() = Prefs.str("flow.twilio.sid", ""); set(v) = Prefs.put(mapOf("flow.twilio.sid" to v))
    var twilioFrom: String get() = Prefs.str("flow.twilio.from", ""); set(v) = Prefs.put(mapOf("flow.twilio.from" to v))
    var twilioToken: String get() = WinSys.unprotect(Prefs.str("flow.twilio.token", "")); set(v) = Prefs.put(mapOf("flow.twilio.token" to WinSys.protect(v)))
}

object Mailer {
    class Mail(val from: String, val to: List<String>, val cc: List<String>, val bcc: List<String>, val subject: String, val body: String, val html: Boolean, val attachments: List<File>)

    fun addresses(s: String): List<String> = s.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun enc(s: String) = if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    private fun b64Lines(bytes: ByteArray) = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes)

    /** The message in MIME (RFC 5322 / 2045): text plus attachments. [draft] adds X-Unsent so mail apps open it for editing. */
    fun mime(m: Mail, draft: Boolean, includeBcc: Boolean): ByteArray {
        val b = "=_fk_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val sb = StringBuilder()
        fun h(name: String, v: String) { if (v.isNotEmpty()) sb.append(name).append(": ").append(v).append("\r\n") }
        h("From", m.from); h("To", m.to.joinToString(", ")); h("Cc", m.cc.joinToString(", "))
        if (includeBcc) h("Bcc", m.bcc.joinToString(", "))
        h("Subject", enc(m.subject))
        h("Date", java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME))
        if (draft) h("X-Unsent", "1")
        h("MIME-Version", "1.0")
        h("Content-Type", "multipart/mixed; boundary=\"$b\"")
        sb.append("\r\n--").append(b).append("\r\n")
        sb.append("Content-Type: text/${if (m.html) "html" else "plain"}; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
        sb.append(b64Lines(m.body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))).append("\r\n")
        for (f in m.attachments) {
            val type = runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream"
            val name = f.name
            sb.append("--").append(b).append("\r\n")
            sb.append("Content-Type: $type; name=\"${enc(name).replace("\"", "")}\"\r\n")
            sb.append("Content-Disposition: attachment; filename=\"${enc(name).replace("\"", "")}\"; filename*=UTF-8''${URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
            sb.append(b64Lines(f.readBytes())).append("\r\n")
        }
        sb.append("--").append(b).append("--\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Writes an .eml draft and opens it in the default mail app (Outlook opens it ready to send). */
    fun draft(m: Mail, dir: File): File {
        val f = File(dir, "mail-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".eml")
        f.parentFile.mkdirs(); f.writeBytes(mime(m, draft = true, includeBcc = true))
        Fs.open(f.path)
        return f
    }

    /** Through Outlook (COM): shows the message, or sends it when [send]. Values go in as environment variables, never as script text. */
    fun outlook(m: Mail, send: Boolean): String {
        val script = "${'$'}o = New-Object -ComObject Outlook.Application; ${'$'}m = ${'$'}o.CreateItem(0); " +
            "${'$'}m.To = ${'$'}env:FK_TO; ${'$'}m.CC = ${'$'}env:FK_CC; ${'$'}m.BCC = ${'$'}env:FK_BCC; ${'$'}m.Subject = ${'$'}env:FK_SUBJECT; " +
            "if (${'$'}env:FK_HTML -eq '1') { ${'$'}m.HTMLBody = ${'$'}env:FK_BODY } else { ${'$'}m.Body = ${'$'}env:FK_BODY }; " +
            "foreach (${'$'}a in (${'$'}env:FK_ATT -split \"`n\")) { if (${'$'}a) { [void]${'$'}m.Attachments.Add(${'$'}a) } }; " +
            "if (${'$'}env:FK_SEND -eq '1') { ${'$'}m.Send(); 'SENT' } else { ${'$'}m.Display(); 'SHOWN' }"
        val out = Fs.powershell(script, 120_000, mapOf(
            "FK_TO" to m.to.joinToString("; "), "FK_CC" to m.cc.joinToString("; "), "FK_BCC" to m.bcc.joinToString("; "),
            "FK_SUBJECT" to m.subject, "FK_BODY" to m.body, "FK_HTML" to if (m.html) "1" else "0",
            "FK_ATT" to m.attachments.joinToString("\n") { it.absolutePath }, "FK_SEND" to if (send) "1" else "0",
        ))?.trim().orEmpty()
        check(out.endsWith("SENT") || out.endsWith("SHOWN")) { if (S.zh) "無法使用 Outlook（是否已安裝傳統版 Outlook？）" else "Couldn't use Outlook (is classic Outlook installed?)" }
        return out.substringAfterLast('\n')
    }

    class SmtpConfig(val host: String, val port: Int, val security: String, val user: String, val password: String, val from: String) {
        companion object {
            fun saved() = SmtpConfig(MailAccounts.smtpHost.trim(), MailAccounts.smtpPort, MailAccounts.smtpSecurity, MailAccounts.smtpUser, MailAccounts.smtpPassword, MailAccounts.smtpFrom)
        }
    }

    /** Sends through the SMTP account (TLS with the server name checked; AUTH PLAIN / LOGIN). */
    fun smtp(m: Mail, c: SmtpConfig = SmtpConfig.saved()) {
        val host = c.host; val port = c.port; val sec = c.security
        require(host.isNotEmpty()) { if (S.zh) "尚未設定 SMTP 郵件帳戶（捷徑 → 帳戶）" else "No SMTP account set up (Shortcuts → Accounts)" }
        val rcpts = m.to + m.cc + m.bcc
        require(rcpts.isNotEmpty()) { if (S.zh) "沒有收件者" else "No recipients" }
        val ssl = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        fun secure(s: java.net.Socket?): javax.net.ssl.SSLSocket {
            val t = (if (s == null) ssl.createSocket(host, port) else ssl.createSocket(s, host, port, true)) as javax.net.ssl.SSLSocket
            t.sslParameters = t.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            t.startHandshake(); return t
        }
        var sock: java.net.Socket = if (sec == "ssl") secure(null) else java.net.Socket().apply { connect(java.net.InetSocketAddress(host, port), 20_000) }
        sock.soTimeout = 60_000
        var input = sock.getInputStream().bufferedReader(Charsets.UTF_8); var out = sock.getOutputStream()
        fun reply(vararg ok: Int): String {
            val lines = ArrayList<String>()
            while (true) { val l = input.readLine() ?: error("SMTP: connection closed"); lines += l; if (l.length < 4 || l[3] != '-') break }
            val code = lines.last().take(3).toIntOrNull() ?: 0
            if (code !in ok) throw IllegalStateException("SMTP: " + lines.joinToString(" / "))
            return lines.joinToString("\n")
        }
        fun cmd(c: String, vararg ok: Int): String { out.write((c + "\r\n").toByteArray(Charsets.UTF_8)); out.flush(); return reply(*ok) }
        try {
            reply(220)
            var ehlo = cmd("EHLO fileskmp.local", 250)
            if (sec == "starttls") {
                check(ehlo.contains("STARTTLS", true)) { if (S.zh) "伺服器不支援 STARTTLS" else "The server doesn't offer STARTTLS" }
                cmd("STARTTLS", 220)
                sock = secure(sock); input = sock.getInputStream().bufferedReader(Charsets.UTF_8); out = sock.getOutputStream()
                ehlo = cmd("EHLO fileskmp.local", 250)
            }
            val user = c.user
            if (user.isNotEmpty()) {
                val pass = c.password
                val enc = Base64.getEncoder()
                if (ehlo.contains("PLAIN", true)) cmd("AUTH PLAIN " + enc.encodeToString("\u0000$user\u0000$pass".toByteArray(Charsets.UTF_8)), 235)
                else { cmd("AUTH LOGIN", 334); cmd(enc.encodeToString(user.toByteArray(Charsets.UTF_8)), 334); cmd(enc.encodeToString(pass.toByteArray(Charsets.UTF_8)), 235) }
            }
            val from = m.from.ifEmpty { c.from.ifEmpty { user } }
            cmd("MAIL FROM:<${bare(from)}>", 250)
            rcpts.forEach { cmd("RCPT TO:<${bare(it)}>", 250, 251) }
            cmd("DATA", 354)
            val data = String(mime(Mail(from, m.to, m.cc, m.bcc, m.subject, m.body, m.html, m.attachments), draft = false, includeBcc = false), Charsets.US_ASCII)
            // Dot-stuffing: a line that starts with "." gets another one.
            out.write(data.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }.toByteArray(Charsets.US_ASCII))
            out.write("\r\n.\r\n".toByteArray()); out.flush(); reply(250)
            runCatching { cmd("QUIT", 221) }
        } finally { runCatching { sock.close() } }
    }

    private fun bare(a: String) = (Regex("<([^>]+)>").find(a)?.groupValues?.get(1) ?: a).trim().also {
        require(it.matches(Regex("[^\\s<>@]+@[^\\s<>@]+"))) { (if (S.zh) "不是有效的電子郵件地址：" else "Not an email address: ") + a }
    }
}

object Sms {
    /** Opens the Windows sms: handler (Phone Link) with the number and text; attachments go on the clipboard to paste there. */
    fun phone(to: String, body: String, attachments: List<String>) {
        val uri = "sms:" + to.filter { it.isDigit() || it == '+' || it == ',' } + "?body=" + URLEncoder.encode(body, Charsets.UTF_8).replace("+", "%20")
        if (attachments.isNotEmpty()) WinSys.copyFiles(attachments)
        java.awt.Desktop.getDesktop().browse(java.net.URI(uri))
    }

    /** Twilio's Messages API; pictures must be public http(s) addresses (MMS MediaUrl). Returns the message status. */
    fun twilio(to: String, body: String, mediaUrls: List<String>): String {
        val sid = MailAccounts.twilioSid.trim(); val token = MailAccounts.twilioToken; val from = MailAccounts.twilioFrom.trim()
        require(sid.isNotEmpty() && token.isNotEmpty() && from.isNotEmpty()) { if (S.zh) "尚未設定 Twilio 帳戶（捷徑 → 帳戶）" else "No Twilio account set up (Shortcuts → Accounts)" }
        require(sid.matches(Regex("AC[0-9a-fA-F]{32}"))) { "Twilio Account SID" }
        fun e(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        val form = (listOf("To" to to, "From" to from, "Body" to body) + mediaUrls.map { "MediaUrl" to it }).joinToString("&") { (k, v) -> e(k) + "=" + e(v) }
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI("https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json"))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$sid:$token".toByteArray()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(java.time.Duration.ofSeconds(30))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build()
        val r = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        @Suppress("UNCHECKED_CAST") val j = runCatching { Json.parse(r.body()) as? Map<String, Any?> }.getOrNull()
        check(r.statusCode() in 200..299) { "Twilio ${r.statusCode()}: " + (j?.get("message") ?: r.body().take(200)) }
        return (j?.get("status") ?: "sent").toString()
    }
}
