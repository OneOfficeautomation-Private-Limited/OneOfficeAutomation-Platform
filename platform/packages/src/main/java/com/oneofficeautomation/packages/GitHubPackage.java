package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub REST API (github.com and GitHub Enterprise Server) with a personal access token or app installation token. */
@OoaPackage(id = "ooa.github", name = "GitHub", version = "1.0.0",
        description = "Work with GitHub repositories: issues, pull requests, branches, files, releases and Actions workflows. Works with github.com and GitHub Enterprise Server.")
public final class GitHubPackage {
    static final String P = "GITHUB";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final String D_BASE = "API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3.";
    private static final String DEF_BASE = "\"https://api.github.com\"";
    private static final String D_TOKEN = "Personal access token or app token from the vault, with access to the repository.";
    private static final String D_OWNER = "User or organization that owns the repository, e.g. acme-corp.";
    private static final String D_REPO = "Repository name without the owner, e.g. billing-service.";
    private static final String D_MAX = "Most items to return; pages are read until this many are found.";
    private static final String[] ISSUE_COLS = {"number", "title", "state", "author=user.login", "comments", "created_at", "updated_at", "url=html_url"};
    private static final String[] PR_COLS = {"number", "title", "state", "draft", "author=user.login", "head=head.ref", "base=base.ref", "updated_at", "url=html_url"};

    static String api(String base) { return base.replaceAll("/+$", ""); }
    static String name(String what, String v) {
        if (v == null || !NAME.matcher(v).matches() || v.equals(".") || v.equals(".."))
            throw new ActionException(P + ".BAD_NAME", "Invalid " + what + " '" + v + "'", "Use the plain name as shown on GitHub, e.g. acme-corp or billing-service.");
        return v;
    }
    static String repo(String owner, String repo) { return "/repos/" + name("owner", owner) + "/" + name("repository", repo); }

    static HttpJson.Req req(String method, String base, String path, Object token) {
        return HttpJson.req(P, method, api(base) + path).bearer(token)
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28");
    }

    /** Sends and turns GitHub's "403 rate limit exceeded" into a retryable error. */
    static HttpJson.Response send(HttpJson.Req r, ActionContext ctx) {
        try { return r.send(ctx); }
        catch (ActionException e) {
            if (e.code().equals(P + ".FORBIDDEN") && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("rate limit"))
                throw new ActionException(P + ".RATE_LIMITED", e.getMessage(), "GitHub's request limit was reached; retry after it resets.", true, e.details());
            throw e;
        }
    }

    /** Reads every page (Link: rel="next") until max items are collected. listKey names the array inside an object response. */
    static List<Map<String, Object>> pages(ActionContext ctx, String base, String path, Object token, int max, String listKey, Predicate<Map<String, Object>> keep) {
        List<Map<String, Object>> out = new ArrayList<>();
        String url = api(base) + path + (path.contains("?") ? "&" : "?") + "per_page=" + Math.max(1, Math.min(100, max));
        while (url != null && out.size() < max) {
            HttpJson.Response r = send(HttpJson.req(P, "GET", url).bearer(token).header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28").timeout(120), ctx);
            List<Map<String, Object>> items = HttpJson.maps(listKey == null ? r.json() : r.map().get(listKey));
            for (Map<String, Object> m : items) { if (keep == null || keep.test(m)) out.add(m); if (out.size() >= max) break; }
            String link = r.header("Link");
            Matcher m = link == null ? null : NEXT.matcher(link);
            url = m != null && m.find() && HttpJson.sameOrigin(api(base), m.group(1)) && !items.isEmpty() ? m.group(1) : null;
        }
        return out;
    }

    static Map<String, Object> result(List<Map<String, Object>> recs, String... cols) {
        return Out.of("table", HttpJson.table(recs, cols), "count", BigDecimal.valueOf(recs.size()));
    }

    static List<Object> names(Object list, String key) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : HttpJson.maps(list)) out.add(m.get(key));
        return out;
    }

    static Map<String, Object> issue(Map<String, Object> i) {
        return Out.of("number", i.get("number"), "title", i.get("title"), "state", i.get("state"), "state_reason", i.get("state_reason"),
                "body", i.get("body"), "author", HttpJson.path(i, "user.login"), "labels", names(i.get("labels"), "name"),
                "assignees", names(i.get("assignees"), "login"), "milestone", HttpJson.path(i, "milestone.title"), "comments", i.get("comments"),
                "created_at", i.get("created_at"), "updated_at", i.get("updated_at"), "closed_at", i.get("closed_at"),
                "is_pull_request", i.get("pull_request") != null, "url", i.get("html_url"));
    }

    static Map<String, Object> pull(Map<String, Object> p) {
        return Out.of("number", p.get("number"), "title", p.get("title"), "state", p.get("state"), "draft", p.get("draft"),
                "merged", p.get("merged"), "mergeable", p.get("mergeable"), "body", p.get("body"), "author", HttpJson.path(p, "user.login"),
                "head", HttpJson.path(p, "head.ref"), "head_sha", HttpJson.path(p, "head.sha"), "base", HttpJson.path(p, "base.ref"),
                "created_at", p.get("created_at"), "updated_at", p.get("updated_at"), "merged_at", p.get("merged_at"), "url", p.get("html_url"));
    }

    // ---------------------------------------------------------------- repositories

    @Action(id = "list_repositories", name = "List repositories", category = "Repositories", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the repositories of an organization, or the repositories the token's user can access when no organization is given.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: name, full_name, private, default_branch, description, updated_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of repositories returned.")
    public Map<String, Object> listRepositories(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "organization", type = OoaType.STRING, required = false, example = "acme-corp", description = "Organization name. Leave empty for the repositories of the token's user.") String org,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max) {
        String path = org == null || org.isBlank() ? "/user/repos?sort=updated" : "/orgs/" + name("organization", org) + "/repos?sort=updated";
        return result(pages(ctx, base, path, token, max, null, null),
                "name", "full_name", "private", "default_branch", "description", "updated_at", "url=html_url");
    }

    @Action(id = "get_repository", name = "Get repository", category = "Repositories", idempotent = true, capabilities = "network",
            description = "Read a repository's main details, such as its default branch and visibility.")
    @Output(name = "repository", type = OoaType.DICTIONARY, description = "name, full_name, description, private, default_branch, open_issues, archived, url.")
    @Output(name = "default_branch", type = OoaType.STRING, description = "Default branch, e.g. main.")
    public Map<String, Object> getRepository(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo) {
        Map<String, Object> r = send(req("GET", base, repo(owner, repo), token), ctx).map();
        Map<String, Object> o = Out.of("name", r.get("name"), "full_name", r.get("full_name"), "description", r.get("description"),
                "private", r.get("private"), "default_branch", r.get("default_branch"), "open_issues", r.get("open_issues_count"),
                "archived", r.get("archived"), "url", r.get("html_url"));
        return Out.of("repository", o, "default_branch", r.get("default_branch"));
    }

    // ---------------------------------------------------------------- issues

    @Action(id = "create_issue", name = "Create issue", category = "Issues", capabilities = "network", description = "Open a new issue, optionally with labels and assignees.")
    @Output(name = "number", type = OoaType.NUMBER, description = "Issue number, e.g. 42.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the issue.")
    public Map<String, Object> createIssue(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "title", type = OoaType.STRING, example = "Invoice upload fails for PDF over 10 MB", description = "Short title of the issue.") String title,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Details in Markdown.") String body,
            @Input(name = "labels", type = OoaType.LIST, required = false, description = "Label names, e.g. [\"bug\", \"finance\"]. A comma-separated text also works.") Object labels,
            @Input(name = "assignees", type = OoaType.LIST, required = false, description = "GitHub user names to assign, e.g. [\"octo-dev\"].") Object assignees,
            @Input(name = "milestone", type = OoaType.NUMBER, required = false, description = "Milestone number to put the issue in.") Integer milestone) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        if (body != null) b.put("body", body);
        if (labels != null) b.put("labels", HttpJson.addressList(labels));
        if (assignees != null) b.put("assignees", HttpJson.addressList(assignees));
        if (milestone != null) b.put("milestone", milestone);
        Map<String, Object> r = send(req("POST", base, repo(owner, repo) + "/issues", token).json(b), ctx).map();
        return Out.of("number", r.get("number"), "url", r.get("html_url"));
    }

    @Action(id = "get_issue", name = "Get issue", category = "Issues", idempotent = true, capabilities = "network", description = "Read an issue by its number.")
    @Output(name = "issue", type = OoaType.DICTIONARY, description = "number, title, state, state_reason, body, author, labels, assignees, milestone, comments, created_at, updated_at, closed_at, is_pull_request, url.")
    @Output(name = "state", type = OoaType.STRING, description = "open or closed.")
    public Map<String, Object> getIssue(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, example = "42", description = "Issue number.") int number) {
        Map<String, Object> i = issue(send(req("GET", base, repo(owner, repo) + "/issues/" + number, token), ctx).map());
        return Out.of("issue", i, "state", i.get("state"));
    }

    @Action(id = "update_issue", name = "Update issue", category = "Issues", capabilities = "network",
            description = "Change an issue's title, text, state, labels or assignees. Only the inputs you fill in are changed.")
    public Map<String, Object> updateIssue(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, description = "Issue number.") int number,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "New title.") String title,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "New text in Markdown (replaces the old text).") String body,
            @Input(name = "state", type = OoaType.STRING, required = false, enumValues = {"open", "closed"}, description = "open or closed.") String state,
            @Input(name = "labels", type = OoaType.LIST, required = false, description = "Replace all labels with these, e.g. [\"bug\"].") Object labels,
            @Input(name = "assignees", type = OoaType.LIST, required = false, description = "Replace all assignees with these user names.") Object assignees) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (title != null) b.put("title", title);
        if (body != null) b.put("body", body);
        if (state != null) b.put("state", state);
        if (labels != null) b.put("labels", HttpJson.addressList(labels));
        if (assignees != null) b.put("assignees", HttpJson.addressList(assignees));
        if (b.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No changes were given", "Fill in at least one of title, body, state, labels or assignees.");
        send(req("PATCH", base, repo(owner, repo) + "/issues/" + number, token).json(b), ctx);
        return Map.of();
    }

    @Action(id = "close_issue", name = "Close issue", category = "Issues", capabilities = "network", description = "Close an issue as completed or as not planned.")
    public Map<String, Object> closeIssue(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, description = "Issue number.") int number,
            @Input(name = "reason", type = OoaType.STRING, required = false, defaultValue = "\"completed\"", enumValues = {"completed", "not_planned"}, description = "Why it is closed: completed or not_planned.") String reason) {
        send(req("PATCH", base, repo(owner, repo) + "/issues/" + number, token).json(Map.of("state", "closed", "state_reason", reason)), ctx);
        return Map.of();
    }

    @Action(id = "add_issue_comment", name = "Comment on issue or pull request", category = "Issues", capabilities = "network",
            description = "Add a comment to an issue or a pull request conversation.")
    @Output(name = "comment_id", type = OoaType.NUMBER, description = "Id of the new comment.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the comment.")
    public Map<String, Object> addIssueComment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, description = "Issue or pull request number.") int number,
            @Input(name = "text", type = OoaType.STRING, example = "Fixed by the nightly agent run.", description = "Comment text in Markdown.") String text) {
        Map<String, Object> r = send(req("POST", base, repo(owner, repo) + "/issues/" + number + "/comments", token).json(Map.of("body", text)), ctx).map();
        return Out.of("comment_id", r.get("id"), "url", r.get("html_url"));
    }

    @Action(id = "list_issues", name = "List issues", category = "Issues", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List a repository's issues, filtered by state, labels or assignee. Pull requests are left out.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: number, title, state, author, comments, created_at, updated_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of issues returned.")
    public Map<String, Object> listIssues(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "state", type = OoaType.STRING, required = false, defaultValue = "\"open\"", enumValues = {"open", "closed", "all"}, description = "open, closed or all.") String state,
            @Input(name = "labels", type = OoaType.STRING, required = false, example = "bug,finance", description = "Only issues having all these labels (comma-separated).") String labels,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = "Only issues assigned to this user name; * for any, none for unassigned.") String assignee,
            @Input(name = "since", type = OoaType.STRING, required = false, example = "2026-10-01T00:00:00Z", description = "Only issues updated at or after this time (ISO 8601).") String since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("state", state);
        q.put("labels", labels);
        q.put("assignee", assignee);
        q.put("since", since);
        List<Map<String, Object>> recs = pages(ctx, base, repo(owner, repo) + "/issues?" + HttpJson.query(q), token, max, null, m -> m.get("pull_request") == null);
        return result(recs, ISSUE_COLS);
    }

    // ---------------------------------------------------------------- pull requests

    @Action(id = "list_pull_requests", name = "List pull requests", category = "Pull requests", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List pull requests, filtered by state and target branch.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: number, title, state, draft, author, head, base, updated_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pull requests returned.")
    public Map<String, Object> listPullRequests(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "state", type = OoaType.STRING, required = false, defaultValue = "\"open\"", enumValues = {"open", "closed", "all"}, description = "open, closed or all.") String state,
            @Input(name = "target_branch", type = OoaType.STRING, required = false, example = "main", description = "Only pull requests that merge into this branch.") String target,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("state", state);
        q.put("base", target);
        return result(pages(ctx, base, repo(owner, repo) + "/pulls?" + HttpJson.query(q), token, max, null, null), PR_COLS);
    }

    @Action(id = "get_pull_request", name = "Get pull request", category = "Pull requests", idempotent = true, capabilities = "network",
            description = "Read a pull request, including whether it is merged and whether it can be merged.")
    @Output(name = "pull_request", type = OoaType.DICTIONARY, description = "number, title, state, draft, merged, mergeable, body, author, head, head_sha, base, created_at, updated_at, merged_at, url.")
    @Output(name = "merged", type = OoaType.BOOLEAN, description = "True when already merged.")
    public Map<String, Object> getPullRequest(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, description = "Pull request number.") int number) {
        Map<String, Object> p = pull(send(req("GET", base, repo(owner, repo) + "/pulls/" + number, token), ctx).map());
        return Out.of("pull_request", p, "merged", Boolean.TRUE.equals(p.get("merged")));
    }

    @Action(id = "create_pull_request", name = "Create pull request", category = "Pull requests", capabilities = "network",
            description = "Propose merging one branch into another.")
    @Output(name = "number", type = OoaType.NUMBER, description = "Pull request number.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the pull request.")
    public Map<String, Object> createPullRequest(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "title", type = OoaType.STRING, description = "Title of the pull request.") String title,
            @Input(name = "source_branch", type = OoaType.STRING, example = "feature/rates-2027", description = "Branch with the changes. For a fork use owner:branch.") String head,
            @Input(name = "target_branch", type = OoaType.STRING, example = "main", description = "Branch to merge into.") String target,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Description in Markdown.") String body,
            @Input(name = "draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Open as a draft that cannot be merged yet.") boolean draft) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        b.put("head", head);
        b.put("base", target);
        if (body != null) b.put("body", body);
        b.put("draft", draft);
        Map<String, Object> r = send(req("POST", base, repo(owner, repo) + "/pulls", token).json(b), ctx).map();
        return Out.of("number", r.get("number"), "url", r.get("html_url"));
    }

    @Action(id = "merge_pull_request", name = "Merge pull request", category = "Pull requests", capabilities = "network",
            description = "Merge a pull request with a merge commit, squash or rebase.")
    @Output(name = "sha", type = OoaType.STRING, description = "Commit id of the merge.")
    @Output(name = "merged", type = OoaType.BOOLEAN, description = "True when merged.")
    public Map<String, Object> mergePullRequest(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, description = "Pull request number.") int number,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"merge\"", enumValues = {"merge", "squash", "rebase"}, description = "merge, squash or rebase.") String method,
            @Input(name = "commit_title", type = OoaType.STRING, required = false, description = "Title of the merge commit; GitHub picks one when empty.") String commitTitle,
            @Input(name = "commit_message", type = OoaType.STRING, required = false, description = "Extra text for the merge commit.") String commitMessage,
            @Input(name = "expected_head_sha", type = OoaType.STRING, required = false, description = "Merge only if the source branch is still at this commit id.") String sha) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("merge_method", method);
        if (commitTitle != null) b.put("commit_title", commitTitle);
        if (commitMessage != null) b.put("commit_message", commitMessage);
        if (sha != null) b.put("sha", sha);
        try {
            Map<String, Object> r = send(req("PUT", base, repo(owner, repo) + "/pulls/" + number + "/merge", token).json(b), ctx).map();
            return Out.of("sha", r.get("sha"), "merged", Boolean.TRUE.equals(r.get("merged")));
        } catch (ActionException e) {
            Object st = e.details() == null ? null : e.details().get("status");
            if (st instanceof BigDecimal s && (s.intValue() == 405 || s.intValue() == 409))
                throw new ActionException(P + ".NOT_MERGEABLE", e.getMessage(),
                        "The pull request cannot be merged now: check conflicts, required reviews and checks, or whether the source branch changed.", false, e.details());
            throw e;
        }
    }

    @Condition(id = "pull_request_is_merged", name = "Pull request is merged", description = "True when the pull request has been merged.")
    public boolean isMerged(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "number", type = OoaType.NUMBER, description = "Pull request number.") int number) {
        try { send(req("GET", base, repo(owner, repo) + "/pulls/" + number + "/merge", token), ctx); return true; }
        catch (ActionException e) { if (e.code().equals(P + ".NOT_FOUND")) return false; throw e; }
    }

    // ---------------------------------------------------------------- branches and files

    @Action(id = "list_branches", name = "List branches", category = "Branches", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "List a repository's branches.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: name, sha, protected.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of branches returned.")
    public Map<String, Object> listBranches(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max) {
        return result(pages(ctx, base, repo(owner, repo) + "/branches", token, max, null, null), "name", "sha=commit.sha", "protected");
    }

    @Action(id = "create_branch", name = "Create branch", category = "Branches", capabilities = "network",
            description = "Create a branch starting from another branch (the default branch when none is given).")
    @Output(name = "sha", type = OoaType.STRING, description = "Commit id the new branch points to.")
    public Map<String, Object> createBranch(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "branch", type = OoaType.STRING, example = "agent/update-rates", description = "Name of the new branch.") String branch,
            @Input(name = "from_branch", type = OoaType.STRING, required = false, example = "main", description = "Branch to start from. Empty means the default branch.") String from) {
        String r = repo(owner, repo);
        if (from == null || from.isBlank()) from = String.valueOf(send(req("GET", base, r, token), ctx).map().get("default_branch"));
        Object sha = HttpJson.path(send(req("GET", base, r + "/git/ref/heads/" + HttpJson.encPath(from), token), ctx).map(), "object.sha");
        send(req("POST", base, r + "/git/refs", token).json(Map.of("ref", "refs/heads/" + branch, "sha", String.valueOf(sha))), ctx);
        return Out.of("sha", sha);
    }

    @Action(id = "get_file", name = "Get file contents", category = "Files", idempotent = true, timeoutSeconds = 300, capabilities = {"network", "filesystem"},
            description = "Read a file from a repository as text, or save it to a local file.")
    @Output(name = "text", type = OoaType.STRING, description = "File contents as UTF-8 text (empty when saved to a file).")
    @Output(name = "sha", type = OoaType.STRING, description = "Version id of the file; pass it to Create or update file to change it safely.")
    @Output(name = "size", type = OoaType.NUMBER, description = "Size in bytes.")
    public Map<String, Object> getFile(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "path", type = OoaType.STRING, example = "config/rates.json", description = "Path of the file inside the repository.") String path,
            @Input(name = "ref", type = OoaType.STRING, required = false, description = "Branch, tag or commit to read from. Empty means the default branch.") String ref,
            @Input(name = "save_to", type = OoaType.FILE, required = false, description = "Local file to save the contents to, e.g. downloads/rates.json.") String saveTo) {
        String url = repo(owner, repo) + "/contents/" + HttpJson.encPath(path) + (ref == null || ref.isBlank() ? "" : "?ref=" + HttpJson.enc(ref));
        HttpJson.Response r = send(req("GET", base, url, token), ctx);
        if (!(r.json() instanceof Map<?, ?>) || !"file".equals(r.map().get("type")))
            throw new ActionException(P + ".NOT_A_FILE", "'" + path + "' is not a file", "Give the path of a file, not a folder.");
        Map<String, Object> m = r.map();
        byte[] data;
        if ("base64".equals(m.get("encoding"))) data = Base64.getMimeDecoder().decode(String.valueOf(m.get("content")));
        else data = send(req("GET", base, url, token).header("Accept", "application/vnd.github.raw").binaryResponse().timeout(300), ctx).bytes();
        if (saveTo != null && !saveTo.isBlank()) {
            Path p = ctx.resolvePath(saveTo);
            try { if (p.getParent() != null) Files.createDirectories(p.getParent()); Files.write(p, data); }
            catch (IOException e) { throw new ActionException("FILE.WRITE_FAILED", "Cannot write " + p + ": " + e.getMessage(), "Check the folder exists and is writable."); }
            return Out.of("text", "", "sha", m.get("sha"), "size", BigDecimal.valueOf(data.length));
        }
        return Out.of("text", new String(data, StandardCharsets.UTF_8), "sha", m.get("sha"), "size", BigDecimal.valueOf(data.length));
    }

    @Action(id = "put_file", name = "Create or update file", category = "Files", timeoutSeconds = 300, capabilities = {"network", "filesystem"},
            description = "Commit a new or changed file to a branch, from text or from a local file.")
    @Output(name = "commit_sha", type = OoaType.STRING, description = "Id of the new commit.")
    @Output(name = "sha", type = OoaType.STRING, description = "New version id of the file.")
    public Map<String, Object> putFile(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "path", type = OoaType.STRING, example = "reports/2026-10.csv", description = "Path of the file inside the repository.") String path,
            @Input(name = "message", type = OoaType.STRING, example = "Update October report", description = "Commit message.") String message,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "New contents as text. Use this or local_file.") String text,
            @Input(name = "local_file", type = OoaType.FILE, required = false, description = "Local file whose contents to commit. Use this or text.") String local,
            @Input(name = "branch", type = OoaType.STRING, required = false, description = "Branch to commit to. Empty means the default branch.") String branch,
            @Input(name = "sha", type = OoaType.STRING, required = false, description = "Current version id of the file. Leave empty to look it up; it is not needed for new files.") String sha) {
        byte[] data;
        if (local != null && !local.isBlank()) data = HttpJson.readFile(ctx, local);
        else if (text != null) data = text.getBytes(StandardCharsets.UTF_8);
        else throw new ActionException(P + ".NO_CONTENT", "Nothing to commit", "Fill in text or local_file.");
        String url = repo(owner, repo) + "/contents/" + HttpJson.encPath(path);
        boolean hasBranch = branch != null && !branch.isBlank();
        if (sha == null || sha.isBlank()) {
            try {
                Object existing = send(req("GET", base, url + (hasBranch ? "?ref=" + HttpJson.enc(branch) : ""), token), ctx).json();
                sha = existing instanceof Map<?, ?> m && m.get("sha") != null ? String.valueOf(m.get("sha")) : null;
            } catch (ActionException e) { if (!e.code().equals(P + ".NOT_FOUND")) throw e; sha = null; }
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("message", message);
        b.put("content", Base64.getEncoder().encodeToString(data));
        if (hasBranch) b.put("branch", branch);
        if (sha != null && !sha.isBlank()) b.put("sha", sha);
        Map<String, Object> r = send(req("PUT", base, url, token).json(b).timeout(300), ctx).map();
        return Out.of("commit_sha", HttpJson.path(r, "commit.sha"), "sha", HttpJson.path(r, "content.sha"));
    }

    // ---------------------------------------------------------------- releases

    @Action(id = "create_release", name = "Create release", category = "Releases", capabilities = "network",
            description = "Publish a release for a tag; the tag is created from the target branch when it does not exist yet.")
    @Output(name = "release_id", type = OoaType.NUMBER, description = "Id of the release.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the release.")
    public Map<String, Object> createRelease(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "tag", type = OoaType.STRING, example = "v2.4.0", description = "Tag name of the release.") String tag,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Release title. Empty uses the tag.") String relName,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "Release notes in Markdown.") String notes,
            @Input(name = "target", type = OoaType.STRING, required = false, description = "Branch or commit to tag when the tag is new. Empty means the default branch.") String target,
            @Input(name = "draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Save as an unpublished draft.") boolean draft,
            @Input(name = "prerelease", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark as a pre-release.") boolean prerelease) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tag_name", tag);
        if (relName != null) b.put("name", relName);
        if (notes != null) b.put("body", notes);
        if (target != null) b.put("target_commitish", target);
        b.put("draft", draft);
        b.put("prerelease", prerelease);
        Map<String, Object> r = send(req("POST", base, repo(owner, repo) + "/releases", token).json(b), ctx).map();
        return Out.of("release_id", r.get("id"), "url", r.get("html_url"));
    }

    @Action(id = "get_latest_release", name = "Get latest release", category = "Releases", idempotent = true, capabilities = "network",
            description = "Read the newest published release (drafts and pre-releases are skipped).")
    @Output(name = "release", type = OoaType.DICTIONARY, description = "id, tag, name, notes, published_at, author, url, assets (list of file names).")
    @Output(name = "tag", type = OoaType.STRING, description = "Tag of the release, e.g. v2.4.0.")
    public Map<String, Object> latestRelease(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo) {
        Map<String, Object> r = send(req("GET", base, repo(owner, repo) + "/releases/latest", token), ctx).map();
        Map<String, Object> o = Out.of("id", r.get("id"), "tag", r.get("tag_name"), "name", r.get("name"), "notes", r.get("body"),
                "published_at", r.get("published_at"), "author", HttpJson.path(r, "author.login"), "url", r.get("html_url"), "assets", names(r.get("assets"), "name"));
        return Out.of("release", o, "tag", r.get("tag_name"));
    }

    // ---------------------------------------------------------------- Actions workflows

    @Action(id = "run_workflow", name = "Run workflow", category = "Workflows", capabilities = "network",
            description = "Start a GitHub Actions workflow that allows manual runs, with optional inputs.")
    public Map<String, Object> runWorkflow(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "workflow", type = OoaType.STRING, example = "deploy.yml", description = "Workflow file name (e.g. deploy.yml) or numeric workflow id.") String workflow,
            @Input(name = "ref", type = OoaType.STRING, example = "main", description = "Branch or tag to run the workflow on.") String ref,
            @Input(name = "inputs", type = OoaType.DICTIONARY, required = false, description = "Workflow inputs, e.g. {\"environment\": \"staging\"}.") Map<String, Object> inputs) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ref", ref);
        if (inputs != null && !inputs.isEmpty()) b.put("inputs", inputs);
        send(req("POST", base, repo(owner, repo) + "/actions/workflows/" + HttpJson.enc(workflow) + "/dispatches", token).json(b), ctx);
        return Map.of();
    }

    @Action(id = "list_workflow_runs", name = "List workflow runs", category = "Workflows", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List recent GitHub Actions runs, newest first, for one workflow or the whole repository.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, status, conclusion, branch, event, created_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of runs returned.")
    public Map<String, Object> listRuns(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "workflow", type = OoaType.STRING, required = false, description = "Workflow file name or id. Empty lists runs of all workflows.") String workflow,
            @Input(name = "branch", type = OoaType.STRING, required = false, description = "Only runs on this branch.") String branch,
            @Input(name = "status", type = OoaType.STRING, required = false, example = "in_progress", description = "Only runs with this status or result, e.g. queued, in_progress, completed, success, failure.") String status,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "30", description = D_MAX) int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("branch", branch);
        q.put("status", status);
        String path = repo(owner, repo) + (workflow == null || workflow.isBlank() ? "/actions/runs" : "/actions/workflows/" + HttpJson.enc(workflow) + "/runs");
        String qs = HttpJson.query(q);
        return result(pages(ctx, base, path + (qs.isEmpty() ? "" : "?" + qs), token, max, "workflow_runs", null),
                "id", "name", "status", "conclusion", "branch=head_branch", "event", "created_at", "url=html_url");
    }

    @Action(id = "get_workflow_run", name = "Get workflow run", category = "Workflows", idempotent = true, capabilities = "network",
            description = "Read the status and result of one GitHub Actions run.")
    @Output(name = "status", type = OoaType.STRING, description = "queued, in_progress or completed.")
    @Output(name = "conclusion", type = OoaType.STRING, description = "Result once completed: success, failure, cancelled, skipped or timed_out.")
    @Output(name = "run", type = OoaType.DICTIONARY, description = "id, name, status, conclusion, branch, sha, event, created_at, updated_at, url.")
    public Map<String, Object> getRun(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "owner", type = OoaType.STRING, description = D_OWNER) String owner,
            @Input(name = "repo", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "run_id", type = OoaType.NUMBER, description = "Run id, from List workflow runs.") long runId) {
        Map<String, Object> r = send(req("GET", base, repo(owner, repo) + "/actions/runs/" + runId, token), ctx).map();
        Map<String, Object> o = Out.of("id", r.get("id"), "name", r.get("name"), "status", r.get("status"), "conclusion", r.get("conclusion"),
                "branch", r.get("head_branch"), "sha", r.get("head_sha"), "event", r.get("event"), "created_at", r.get("created_at"),
                "updated_at", r.get("updated_at"), "url", r.get("html_url"));
        return Out.of("status", r.get("status"), "conclusion", r.get("conclusion"), "run", o);
    }
}
