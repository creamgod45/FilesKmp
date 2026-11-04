package app.files

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** What the status bar and the Git column need to know about the repository a folder is in. */
data class GitInfo(
    val root: String,
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val hasUpstream: Boolean,
    val localBranches: List<String>,
    val remoteBranches: List<String>,
    val remotes: List<String>,
    /** Repo-relative path (with '/') → two-letter porcelain code, e.g. " M", "??", "A ". */
    val status: Map<String, String>,
)

class GitResult(val ok: Boolean, val output: String)

/**
 * Git through the git command line installed on this PC, so it uses the person's own
 * credentials (Git Credential Manager / SSH). The original uses LibGit2Sharp (git2 dll).
 */
object Git {
    private fun run(dir: File, timeoutSec: Long, vararg args: String): GitResult = try {
        val p = ProcessBuilder(listOf("git", "-c", "core.quotepath=off", "-c", "color.ui=false") + args)
            .directory(dir).redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0"; environment()["LC_ALL"] = "C.UTF-8" }
            .start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread.ofVirtual().start { out.append(String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8)) }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); GitResult(false, "timeout") }
        else { reader.join(2000); GitResult(p.exitValue() == 0, out.toString().trim()) }
    } catch (e: Exception) { GitResult(false, e.message ?: "git not found") }

    /** The repository root for [dir], or null when it isn't inside one. */
    fun rootOf(dir: String): String? {
        val f = File(dir)
        if (!f.isDirectory) return null
        val r = run(f, 5, "rev-parse", "--show-toplevel")
        return if (r.ok && r.output.isNotBlank()) File(r.output.lines().first()).path else null
    }

    fun info(root: String): GitInfo {
        val d = File(root)
        val branch = run(d, 5, "rev-parse", "--abbrev-ref", "HEAD").let { if (it.ok) it.output else "?" }
        val counts = run(d, 5, "rev-list", "--left-right", "--count", "HEAD...@{u}")
        val (ahead, behind) = if (counts.ok) counts.output.split(Regex("\\s+")).let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) } else 0 to 0
        val locals = run(d, 5, "for-each-ref", "--format=%(refname:short)", "refs/heads").output.lines().filter { it.isNotBlank() }
        val remotesB = run(d, 5, "for-each-ref", "--format=%(refname:short)", "refs/remotes").output.lines().filter { it.isNotBlank() && !it.endsWith("/HEAD") }
        val remotes = run(d, 5, "remote").output.lines().filter { it.isNotBlank() }
        val status = HashMap<String, String>()
        val st = run(d, 20, "status", "--porcelain=v1", "-z", "--untracked-files=normal")
        if (st.ok) {
            val parts = st.output.split('\u0000')
            var i = 0
            while (i < parts.size) {
                val e = parts[i]
                if (e.length > 3) {
                    val code = e.substring(0, 2)
                    status[e.substring(3).trimEnd('/')] = code
                    if (code.startsWith("R") || code.startsWith("C")) i++   // the next field is the old name
                }
                i++
            }
        }
        return GitInfo(root, branch, ahead, behind, counts.ok, locals, remotesB, remotes, status)
    }

    /** Status of one item (folders: anything changed inside). */
    fun statusOf(info: GitInfo, path: String, isDir: Boolean): String? {
        val rel = File(info.root).toPath().relativize(File(path).toPath()).toString().replace('\\', '/')
        info.status[rel]?.let { return it }
        if (isDir) {
            val prefix = "$rel/"
            val inside = info.status.filterKeys { it.startsWith(prefix) }.values
            if (inside.isNotEmpty()) return if (inside.all { it == "??" }) "??" else " M"
        }
        return null
    }

    fun fetch(root: String) = run(File(root), 300, "fetch", "--all", "--prune")
    fun pull(root: String) = run(File(root), 300, "pull", "--ff-only")
    fun push(root: String, branch: String, hasUpstream: Boolean, remote: String?) =
        if (hasUpstream || remote == null) run(File(root), 300, "push")
        else run(File(root), 300, "push", "-u", remote, branch)
    fun checkout(root: String, branch: String) = run(File(root), 60, "checkout", branch)
    /** Remote branch "origin/x": make a local tracking branch x. */
    fun checkoutRemote(root: String, remoteBranch: String) = run(File(root), 60, "checkout", "--track", remoteBranch)
    fun createBranch(root: String, name: String) = run(File(root), 30, "checkout", "-b", name)
    fun deleteBranch(root: String, name: String) = run(File(root), 30, "branch", "-D", name)
    fun init(dir: String) = run(File(dir), 30, "init")
    fun clone(url: String, intoDir: String) = run(File(intoDir), 1800, "clone", url)
    fun isValidBranchName(name: String) = name.isNotBlank() && run(File("."), 5, "check-ref-format", "--branch", name).ok

    /** Short text for a status code, in the app language. */
    fun label(code: String): String = when {
        code == "??" -> if (S.zh) "未追蹤" else "Untracked"
        code.contains('U') || code == "AA" || code == "DD" -> if (S.zh) "衝突" else "Conflict"
        code.contains('A') -> if (S.zh) "已新增" else "Added"
        code.contains('D') -> if (S.zh) "已刪除" else "Deleted"
        code.contains('R') -> if (S.zh) "已重新命名" else "Renamed"
        code.contains('M') -> if (S.zh) "已修改" else "Modified"
        code == "!!" -> if (S.zh) "已忽略" else "Ignored"
        else -> code.trim()
    }

    fun color(code: String): Long = when {
        code == "??" -> 0xFF2E9E6B
        code.contains('U') -> 0xFFD13438
        code.contains('A') -> 0xFF2E9E6B
        code.contains('D') -> 0xFFD13438
        else -> 0xFFC98A00
    }
}
