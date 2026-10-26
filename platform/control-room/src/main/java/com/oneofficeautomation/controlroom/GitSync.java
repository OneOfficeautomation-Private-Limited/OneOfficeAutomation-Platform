package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Git sync of the agent repository (K2): the Command Center stays the source of truth and mirrors its repository to a Git
 * remote (GitHub, GitLab, Azure DevOps, any Git server). Each sync makes the branch hold the latest version of every
 * agent, workflow and form as indented JSON under {@code agents/}, and {@code ooa-repository.json} with each one's latest
 * and live production version and SHA-256. A sync commits only when something changed, with the change's comment and
 * author, and pushes. Changes made in Git are not read back: the next sync overwrites them.
 * <p>Settings: {@code git_remote_url} (https://, or file: for a local test remote), {@code git_branch} (main),
 * {@code git_username} and {@code git_token_ref} (a vault reference to the access token), {@code git_auto_push}
 * (1: sync after each check-in, production change or deletion). The working copy lives in {@code <data>/git-sync}.
 */
public final class GitSync {
    private final ControlRoom cr;
    private final Db db;
    private final Path workDir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ooa-git-sync"); t.setDaemon(true); return t; });
    private volatile Map<String, Object> last = Map.of("state", "never");

    GitSync(ControlRoom cr, Path dataDir) { this.cr = cr; this.db = cr.db; this.workDir = dataDir.resolve("git-sync"); }

    boolean configured() { return !String.valueOf(cr.platform.setting("git_remote_url")).isBlank(); }

    /** After a change to the repository: syncs in the background when automatic push is on. */
    void changed(String who, String message) {
        if (!configured() || cr.platform.intSetting("git_auto_push") != 1) return;
        worker.submit(() -> { try { sync(who, message); } catch (Exception ignored) { /* kept in status() and the audit trail */ } });
    }

    /** Syncs now (administrators): returns the result, also shown by status(). */
    public Map<String, Object> syncNow(User by, String ip) throws Exception {
        cr.require(by, "settings.manage", "sync the agent repository to Git");
        if (!configured()) throw new OoaException("CR.BAD_INPUT", "Set the Git remote first (Administration > Settings > Git)");
        try { return worker.submit(() -> sync(by.username(), "Sync from ONEOFFICEAUTOMATION Command Center")).get(5, TimeUnit.MINUTES); }
        catch (ExecutionException e) { throw e.getCause() instanceof Exception x ? x : e; }
    }

    public Map<String, Object> status(User by) {
        cr.require(by, "settings.manage", "see the Git sync");
        Map<String, Object> m = new LinkedHashMap<>(last);
        m.put("configured", configured());
        m.put("remote", cr.platform.setting("git_remote_url"));
        m.put("branch", branch());
        return m;
    }

    String branch() { String b = String.valueOf(cr.platform.setting("git_branch")).trim(); return b.isEmpty() ? "main" : b; }

    /** One sync: fetch, reset to the remote branch, write the repository, commit when it changed, push. Runs on the worker. */
    synchronized Map<String, Object> sync(String who, String message) throws Exception {
        String url = String.valueOf(cr.platform.setting("git_remote_url")).trim(), branch = branch();
        CredentialsProvider creds = credentials();
        try {
            Git git = open(url, creds);
            try (git) {
                boolean remoteHasBranch = false;
                for (Ref r : git.lsRemote().setCredentialsProvider(creds).setHeads(true).call()) if (r.getName().equals("refs/heads/" + branch)) remoteHasBranch = true;
                if (remoteHasBranch) {
                    git.fetch().setCredentialsProvider(creds).setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/remotes/origin/" + branch)).call();
                    git.checkout().setName(branch).setCreateBranch(git.getRepository().findRef("refs/heads/" + branch) == null).setForced(true)
                        .setStartPoint("origin/" + branch).call();
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + branch).call();
                } else if (git.getRepository().resolve("HEAD") != null && !branch.equals(git.getRepository().getBranch())) {
                    git.checkout().setName(branch).setCreateBranch(git.getRepository().findRef("refs/heads/" + branch) == null).call();
                }
                int agents = write(git.getRepository().getWorkTree().toPath());
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();   // deletions too
                boolean dirty = !git.status().call().isClean();
                String commit = null;
                if (dirty || git.getRepository().resolve("HEAD") == null) {
                    PersonIdent author = new PersonIdent(who + " (ONEOFFICEAUTOMATION)", who.replaceAll("[^A-Za-z0-9._-]", "_") + "@ooa.invalid");
                    RevCommit c = git.commit().setMessage(message == null || message.isBlank() ? "Sync from ONEOFFICEAUTOMATION Command Center" : message)
                        .setAuthor(author).setCommitter(new PersonIdent("ONEOFFICEAUTOMATION Command Center", "command-center@ooa.invalid")).setAllowEmpty(!remoteHasBranch).call();
                    if (!branch.equals(git.getRepository().getBranch())) git.branchRename().setNewName(branch).call();
                    commit = c.getName();
                }
                for (PushResult pr : git.push().setCredentialsProvider(creds).setRemote("origin").setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch)).call()) {
                    for (RemoteRefUpdate u : pr.getRemoteUpdates())
                        if (u.getStatus() != RemoteRefUpdate.Status.OK && u.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                            throw new OoaException("CR.GIT", "The Git server refused the push: " + u.getStatus() + (u.getMessage() == null ? "" : " (" + u.getMessage() + ")"), "Check the token's write access to the branch.");
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("state", "ok"); r.put("at", Instant.now().toString()); r.put("agents", agents); r.put("commit", commit == null ? "" : commit); r.put("changed", commit != null);
                last = r;
                if (commit != null) cr.audit.record(who, "repo.git_sync", "repository", url, Map.of("commit", commit, "branch", branch, "agents", agents), "ok", null);
                return r;
            }
        } catch (Exception e) {
            String why = e instanceof OoaException o ? o.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            last = Map.of("state", "failed", "at", Instant.now().toString(), "error", why);
            cr.audit.record(who, "repo.git_sync", "repository", url, Map.of("error", why), "failed", null);
            throw e instanceof OoaException o ? o : new OoaException("CR.GIT", "Git sync failed: " + why, "Check the remote address, branch and token in Administration > Settings > Git.");
        }
    }

    /** The working copy, cloned (or initialised for an empty remote) when missing or pointing elsewhere. */
    private Git open(String url, CredentialsProvider creds) throws Exception {
        if (Files.isDirectory(workDir.resolve(".git"))) {
            Git git = Git.open(workDir.toFile());
            if (url.equals(git.getRepository().getConfig().getString("remote", "origin", "url"))) return git;
            git.close();
        }
        if (Files.exists(workDir)) deleteTree(workDir);
        Git git = Git.init().setDirectory(workDir.toFile()).setInitialBranch(branch()).call();
        var cfg = git.getRepository().getConfig();
        cfg.setString("remote", "origin", "url", url);
        cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        cfg.save();
        return git;
    }

    /** The access token from the vault, as a password for the Git server (GitHub, GitLab and Azure DevOps all accept this). */
    private CredentialsProvider credentials() {
        String ref = String.valueOf(cr.platform.setting("git_token_ref")).trim();
        if (ref.isEmpty()) return null;
        String user = String.valueOf(cr.platform.setting("git_username")).trim();
        return new UsernamePasswordCredentialsProvider(user.isEmpty() ? "ooa" : user, cr.systemCredential(ref));
    }

    /** Writes agents/ and ooa-repository.json from the database; returns the number of agents. */
    private int write(Path tree) throws Exception {
        Path agents = tree.resolve("agents");
        if (Files.exists(agents)) deleteTree(agents);
        Files.createDirectories(agents);
        List<Map<String, Object>> index = new ArrayList<>();
        for (Map<String, Object> b : db.query("SELECT b.path, b.latest_version, b.production_version, v.content, v.sha256, v.author, v.created FROM bots b " +
                "JOIN bot_versions v ON v.path = b.path AND v.version = b.latest_version WHERE b.deleted_at IS NULL ORDER BY b.path")) {
            String path = (String) b.get("path");
            Path file = agents.resolve(path).normalize();
            if (!file.startsWith(agents)) continue;
            Files.createDirectories(file.getParent());
            Files.writeString(file, pretty(JsonLite.parse((String) b.get("content"))) + "\n", StandardCharsets.UTF_8);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", path); e.put("latest_version", b.get("latest_version")); e.put("production_version", b.get("production_version"));
            e.put("sha256", b.get("sha256")); e.put("author", b.get("author")); e.put("updated", String.valueOf(b.get("created")));
            index.add(e);
        }
        Files.writeString(tree.resolve("ooa-repository.json"), pretty(Map.of("format", "ooa-git-sync-1", "agents", index)) + "\n", StandardCharsets.UTF_8);
        if (!Files.exists(tree.resolve("README.md")))
            Files.writeString(tree.resolve("README.md"), "# Agent repository\n\nMirrored from a ONEOFFICEAUTOMATION Command Center. Change agents in the Command Center:"
                + " changes made here are overwritten by the next sync.\n", StandardCharsets.UTF_8);
        return index.size();
    }

    /** Indented JSON (two spaces), so the Git history shows readable line-by-line changes. Keys keep their order. */
    static String pretty(Object o) { StringBuilder sb = new StringBuilder(); pretty(o, sb, ""); return sb.toString(); }

    private static void pretty(Object o, StringBuilder sb, String indent) {
        String in = indent + "  ";
        if (o instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(in).append(JsonLite.write(String.valueOf(e.getKey()))).append(": ");
                pretty(e.getValue(), sb, in);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append(indent).append('}');
        } else if (o instanceof List<?> l && !l.isEmpty()) {
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) { sb.append(in); pretty(l.get(i), sb, in); sb.append(i + 1 < l.size() ? ",\n" : "\n"); }
            sb.append(indent).append(']');
        } else sb.append(JsonLite.write(o));
    }

    static void deleteTree(Path p) throws Exception {
        try (var w = Files.walk(p)) {
            for (Path x : w.sorted(Comparator.reverseOrder()).toList()) { x.toFile().setWritable(true); Files.deleteIfExists(x); }
        }
    }
}
