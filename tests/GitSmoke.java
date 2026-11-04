import app.files.Git;
import app.files.GitResult;
import java.nio.file.*;
import java.util.*;

// Run with: java -cp build/compose/jars/FilesKmp-windows-x64-1.0.0.jar tests/GitSmoke.java
class GitSmoke {
    static final Git git = Git.INSTANCE;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void ok(GitResult r) { check(r.getOk(), r.getOutput()); }
    static void cmd(Path dir, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git")); command.addAll(List.of(args));
        var p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        var output = new String(p.getInputStream().readAllBytes());
        check(p.waitFor() == 0, output);
    }
    public static void main(String[] args) throws Exception {
        var temp = Files.createTempDirectory("files-git-test-");
        var repo = Files.createDirectory(temp.resolve("source"));
        ok(git.init(repo.toString()));
        check(!git.info(repo.toString()).getBranch().equals("?"), "Unborn branch");
        cmd(repo, "config", "user.name", "Local Test");
        cmd(repo, "config", "user.email", "local@example.invalid");
        Files.writeString(repo.resolve("tracked.txt"), "first");
        Files.createDirectory(repo.resolve("folder"));
        Files.writeString(repo.resolve("folder/nested.txt"), "nested");
        cmd(repo, "add", "."); cmd(repo, "commit", "-m", "fixture");
        var branch = git.info(repo.toString()).getBranch();
        Files.writeString(repo.resolve("tracked.txt"), "modified");
        var info = git.info(repo.toString());
        check(" M".equals(info.getStatus().get("tracked.txt")), "Leading status space lost: " + info.getStatus());
        Files.writeString(repo.resolve("空 白.txt"), "untracked");
        Files.writeString(repo.resolve("folder/nested.txt"), "modified");
        info = git.info(repo.toString());
        check("??".equals(info.getStatus().get("空 白.txt")), "Unicode filename");
        check(" M".equals(git.statusOf(info, repo.resolve("folder").toString(), true)), "Folder aggregation");
        cmd(repo, "add", "."); cmd(repo, "commit", "-m", "changes");
        cmd(repo, "mv", "tracked.txt", "renamed.txt");
        check("R ".equals(git.info(repo.toString()).getStatus().get("renamed.txt")), "Rename parsing");
        cmd(repo, "commit", "-m", "rename");
        ok(git.createBranch(repo.toString(), "feature"));
        ok(git.checkout(repo.toString(), branch));
        ok(git.deleteBranch(repo.toString(), "feature"));
        var remote = Files.createDirectory(temp.resolve("remote.git"));
        cmd(remote, "init", "--bare");
        cmd(remote, "symbolic-ref", "HEAD", "refs/heads/" + branch);
        cmd(repo, "remote", "add", "origin", remote.toString());
        ok(git.push(repo.toString(), branch, false, "origin"));
        ok(git.fetch(repo.toString())); ok(git.pull(repo.toString()));
        check(git.info(repo.toString()).getHasUpstream(), "Upstream missing");
        var destination = Files.createDirectory(temp.resolve("clones"));
        ok(git.clone(remote.toString(), destination.toString()));
        check(Files.exists(destination.resolve("remote/renamed.txt")), "Clone missing files");
        check(git.rootOf(destination.resolve("remote/folder").toString()) != null, "Nested repository detection");
        check(!git.checkout(repo.toString(), "no-such-branch").getOk(), "Invalid checkout must fail");
        System.out.println("PASS: init, unborn branch, status, Unicode, rename, folders, branches, push/fetch/pull, clone, errors. Fixture: " + temp);
    }
}
