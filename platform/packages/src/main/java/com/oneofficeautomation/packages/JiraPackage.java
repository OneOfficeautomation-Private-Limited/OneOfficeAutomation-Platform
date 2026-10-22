package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Jira Cloud and Data Center REST API (v2). Cloud: email + API token; Data Center: personal access token. */
@OoaPackage(id = "ooa.jira", name = "Jira", version = "1.0.0", description = "Create, read and search Jira issues, add comments, move issues through the workflow and attach files.")
public final class JiraPackage {
    static final String P = "JIRA";
    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]*-\\d+$");
    private static final String D_URL = "Site URL, e.g. https://yourcompany.atlassian.net.";

    private static HttpJson.Req auth(HttpJson.Req r, String email, Object apiToken, Object pat) {
        return pat instanceof SecureString ? r.bearer(pat) : r.basic(email == null ? "" : email, apiToken);
    }
    private static String key(String k) { if (!KEY.matcher(k).matches()) throw new ActionException(P + ".BAD_KEY", "Invalid issue key '" + k + "'", "Example: OPS-123."); return k; }
    private static String api(String site) { return site.replaceAll("/+$", "") + "/rest/api/2"; }

    @Action(id = "create_issue", name = "Create issue", category = "Issues", capabilities = "network", description = "Create an issue and return its key.")
    @Output(name = "key", type = OoaType.STRING, description = "Issue key, e.g. OPS-123.")
    @Output(name = "id", type = OoaType.STRING, description = "Issue id.")
    @Output(name = "url", type = OoaType.STRING, description = "Link to the issue in the browser.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "project", type = OoaType.STRING, example = "OPS", description = "Project key.") String project,
            @Input(name = "issue_type", type = OoaType.STRING, required = false, defaultValue = "\"Task\"", description = "Issue type name, e.g. Task, Bug, Story.") String type,
            @Input(name = "summary", type = OoaType.STRING, description = "Summary.") String summary,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "extra_fields", type = OoaType.DICTIONARY, required = false, description = "More fields, e.g. {\"priority\": {\"name\": \"High\"}, \"customfield_10010\": \"Gold\"}.") Map<String, Object> extra,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "priority", type = OoaType.STRING, required = false, example = "High", description = "Priority name, e.g. Highest, High, Medium, Low, Lowest.") String priority,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = "Assignee: an email address (looked up), a Cloud account id, or a Data Center user name; the project default when empty.") String assignee,
            @Input(name = "labels", type = OoaType.LIST, required = false, description = "Labels, e.g. [\"invoice\", \"urgent\"].") List<Object> labels,
            @Input(name = "components", type = OoaType.LIST, required = false, description = "Names of existing project components.") List<Object> components,
            @Input(name = "due_date", type = OoaType.STRING, required = false, example = "2026-12-31", description = "Due date, yyyy-MM-dd.") String due,
            @Input(name = "parent_key", type = OoaType.STRING, required = false, example = "OPS-12", description = "Parent issue key, for a sub-task.") String parent) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", project));
        fields.put("issuetype", Map.of("name", type));
        fields.put("summary", summary);
        if (description != null) fields.put("description", description);
        common(ctx, site, fields, priority, assignee, labels, components, due, email, apiToken, pat);
        if (parent != null && !parent.isBlank()) fields.put("parent", Map.of("key", key(parent.strip())));
        if (extra != null) fields.putAll(extra);
        Map<String, Object> r = auth(HttpJson.req(P, "POST", api(site) + "/issue"), email, apiToken, pat).json(Map.of("fields", fields)).send(ctx).map();
        return Out.of("key", r.get("key"), "id", r.get("id"), "url", r.get("key") == null ? null : site.replaceAll("/+$", "") + "/browse/" + r.get("key"));
    }

    /** Earlier signature, kept for callers written before the named issue fields. */
    public Map<String, Object> create(ActionContext ctx, String site, String project, String type, String summary, String description, Map<String, Object> extra,
                                      String email, Object apiToken, Object pat) {
        return create(ctx, site, project, type, summary, description, extra, email, apiToken, pat, null, null, null, null, null, null);
    }

    /** Fills the named issue fields shared by Create and Update issue; empty inputs are left out. */
    private static void common(ActionContext ctx, String site, Map<String, Object> fields, String priority, String assignee, List<Object> labels, List<Object> components,
                               String due, String email, Object apiToken, Object pat) {
        if (priority != null && !priority.isBlank()) fields.put("priority", Map.of("name", priority.strip()));
        if (assignee != null && !assignee.isBlank()) fields.put("assignee", user(ctx, site, assignee.strip(), email, apiToken, pat));
        if (labels != null) { List<Object> l = new ArrayList<>(); for (Object o : labels) { String s = Values.text(o).strip(); if (s.contains(" ")) throw new ActionException(P + ".BAD_LABEL", "Labels cannot contain spaces: '" + s + "'"); if (!s.isEmpty()) l.add(s); } fields.put("labels", l); }
        if (components != null) { List<Object> l = new ArrayList<>(); for (Object o : components) l.add(Map.of("name", Values.text(o).strip())); fields.put("components", l); }
        if (due != null && !due.isBlank()) {
            try { java.time.LocalDate.parse(due.strip()); } catch (java.time.format.DateTimeParseException e) { throw new ActionException(P + ".BAD_DATE", "Invalid due date '" + due + "'", "Use yyyy-MM-dd."); }
            fields.put("duedate", due.strip());
        }
    }

    /** A user reference: an email is looked up (accountId on Cloud, name on Data Center); otherwise the value is used as is. */
    static Map<String, Object> user(ActionContext ctx, String site, String who, String email, Object apiToken, Object pat) {
        boolean dc = pat instanceof SecureString;
        if (!who.contains("@")) return Map.of(dc ? "name" : "accountId", who);
        String url = api(site) + "/user/search?" + (dc ? "username=" : "query=") + HttpJson.enc(who);
        Object j = auth(HttpJson.req(P, "GET", url), email, apiToken, pat).send(ctx).json();
        if (j instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> u) {
            Object id = dc ? u.get("name") : u.get("accountId");
            if (id != null) return Map.of(dc ? "name" : "accountId", String.valueOf(id));
        }
        throw new ActionException(P + ".USER_NOT_FOUND", "No Jira user with email " + who, "Check the address, or give the account id.");
    }

    @Action(id = "get_issue", name = "Get issue", category = "Issues", idempotent = true, capabilities = "network", description = "Read an issue's main fields, and optionally more fields by name.")
    @Output(name = "issue", type = OoaType.DICTIONARY, description = "key, summary, status, assignee, reporter, priority, created, updated, description, plus the extra fields asked for.")
    @Output(name = "raw", type = OoaType.DICTIONARY, description = "The full issue as Jira returned it.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "extra_fields", type = OoaType.LIST, required = false, description = "More fields to return in issue, e.g. [\"labels\", \"duedate\", \"customfield_10010\"].") List<Object> extraFields) {
        Map<String, Object> r = auth(HttpJson.req(P, "GET", api(site) + "/issue/" + key(k)), email, apiToken, pat).send(ctx).map();
        Map<String, Object> s = summarize(r);
        if (extraFields != null) for (Object f : extraFields) { String n = Values.text(f).strip(); s.put(n, simple(HttpJson.path(r.get("fields"), n))); }
        return Map.of("issue", s, "raw", r);
    }

    /** Earlier signature, kept for callers written before extra_fields. */
    public Map<String, Object> get(ActionContext ctx, String site, String k, String email, Object apiToken, Object pat) { return get(ctx, site, k, email, apiToken, pat, null); }

    /** Objects like {"name": "High"} or {"displayName": "Ann"} become their readable text; lists of them become lists. */
    static Object simple(Object v) {
        if (v instanceof Map<?, ?> m) for (String n : List.of("displayName", "name", "value", "key")) if (m.get(n) != null) return m.get(n);
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(simple(x)); return o; }
        return v;
    }

    static Map<String, Object> summarize(Map<?, ?> issue) {
        Map<?, ?> f = issue.get("fields") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("key", issue.get("key"));
        o.put("summary", f.get("summary"));
        o.put("status", f.get("status") instanceof Map<?, ?> s ? s.get("name") : null);
        o.put("assignee", f.get("assignee") instanceof Map<?, ?> a ? a.get("displayName") : null);
        o.put("reporter", f.get("reporter") instanceof Map<?, ?> rp ? rp.get("displayName") : null);
        o.put("priority", f.get("priority") instanceof Map<?, ?> p ? p.get("name") : null);
        o.put("created", f.get("created"));
        o.put("updated", f.get("updated"));
        o.put("description", f.get("description"));
        return o;
    }

    @Action(id = "search", name = "Search issues (JQL)", category = "Issues", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "Find issues with a JQL query.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: key, summary, status, assignee, priority, updated.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of issues returned.")
    public Map<String, Object> search(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "jql", type = OoaType.STRING, example = "project = OPS AND status = \"To Do\" ORDER BY created DESC", description = "JQL query.") String jql,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum issues.") int max,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "extra_fields", type = OoaType.LIST, required = false, description = "More fields to add as columns, e.g. [\"labels\", \"duedate\", \"reporter\"].") List<Object> extraFields) {
        List<String> more = new ArrayList<>();
        if (extraFields != null) for (Object f : extraFields) { String n = Values.text(f).strip(); if (!n.matches("^[A-Za-z0-9_.]{1,80}$")) throw new ActionException(P + ".BAD_FIELD", "Invalid field name '" + n + "'"); more.add(n); }
        List<Object> want = new ArrayList<>(List.of("summary", "status", "assignee", "priority", "updated"));
        for (String m : more) if (!want.contains(m.split("\\.")[0])) want.add(m.split("\\.")[0]);
        List<Object> rows = new ArrayList<>();
        int start = 0;
        while (rows.size() < max) {
            Map<String, Object> body = Map.of("jql", jql, "startAt", start, "maxResults", Math.min(100, max - rows.size()), "fields", want);
            Map<String, Object> r = auth(HttpJson.req(P, "POST", api(site) + "/search"), email, apiToken, pat).json(body).timeout(120).send(ctx).map();
            List<?> issues = (List<?>) r.getOrDefault("issues", List.of());
            for (Object o : issues) {
                Map<String, Object> s = summarize((Map<?, ?>) o);
                List<Object> row = new ArrayList<>(Arrays.asList(s.get("key"), s.get("summary"), s.get("status"), s.get("assignee"), s.get("priority"), s.get("updated")));
                for (String m : more) row.add(simple(HttpJson.path(((Map<?, ?>) o).get("fields"), m)));
                rows.add(row);
            }
            int total = r.get("total") instanceof BigDecimal t ? t.intValue() : 0;
            start += issues.size();
            if (issues.isEmpty() || start >= total) break;
        }
        List<String> cols = new ArrayList<>(List.of("key", "summary", "status", "assignee", "priority", "updated"));
        cols.addAll(more);
        return Map.of("table", Values.table(cols, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    /** Earlier signature, kept for callers written before extra_fields. */
    public Map<String, Object> search(ActionContext ctx, String site, String jql, int max, String email, Object apiToken, Object pat) { return search(ctx, site, jql, max, email, apiToken, pat, null); }

    @Action(id = "add_comment", name = "Add comment", category = "Issues", capabilities = "network", description = "Add a comment to an issue, optionally visible only to a project role or group.")
    @Output(name = "comment_id", type = OoaType.STRING, description = "Id of the new comment.")
    @Output(name = "author", type = OoaType.STRING, description = "Display name of the author.")
    @Output(name = "created", type = OoaType.STRING, description = "When it was created (ISO 8601).")
    public Map<String, Object> comment(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "text", type = OoaType.STRING, description = "Comment text.") String text,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "visible_to_role", type = OoaType.STRING, required = false, example = "Administrators", description = "Only members of this project role can see the comment.") String role,
            @Input(name = "visible_to_group", type = OoaType.STRING, required = false, example = "jira-servicedesk-users", description = "Only members of this group can see the comment (instead of a role).") String group) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("body", text);
        if (role != null && !role.isBlank() && group != null && !group.isBlank()) throw new ActionException(P + ".BAD_VISIBILITY", "Give a role or a group, not both");
        if (role != null && !role.isBlank()) body.put("visibility", Map.of("type", "role", "value", role.strip()));
        if (group != null && !group.isBlank()) body.put("visibility", Map.of("type", "group", "value", group.strip()));
        Map<String, Object> r = auth(HttpJson.req(P, "POST", api(site) + "/issue/" + key(k) + "/comment"), email, apiToken, pat).json(body).send(ctx).map();
        return Out.of("comment_id", r.get("id") == null ? null : Values.text(r.get("id")), "author", name(r.get("author")), "created", r.get("created"));
    }

    /** Earlier signature, kept for callers written before the visibility options. */
    public Map<String, Object> comment(ActionContext ctx, String site, String k, String text, String email, Object apiToken, Object pat) { return comment(ctx, site, k, text, email, apiToken, pat, null, null); }

    @Action(id = "transition", name = "Move issue (transition)", category = "Issues", capabilities = "network",
            description = "Move an issue through its workflow by transition name, e.g. In Progress or Done, optionally with a comment and field values.")
    public Map<String, Object> transition(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "transition", type = OoaType.STRING, example = "Done", description = "Transition or target status name.") String name,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Comment to add with the move.") String comment,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "Fields the transition screen asks for, e.g. {\"resolution\": {\"name\": \"Fixed\"}}.") Map<String, Object> fields) {
        String url = api(site) + "/issue/" + key(k) + "/transitions";
        List<?> ts = (List<?>) auth(HttpJson.req(P, "GET", url), email, apiToken, pat).send(ctx).map().getOrDefault("transitions", List.of());
        List<String> names = new ArrayList<>();
        for (Object o : ts) {
            Map<?, ?> t = (Map<?, ?>) o;
            String tn = String.valueOf(t.get("name"));
            String to = t.get("to") instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : "";
            names.add(tn);
            if (tn.equalsIgnoreCase(name) || to.equalsIgnoreCase(name)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("transition", Map.of("id", t.get("id")));
                if (fields != null && !fields.isEmpty()) body.put("fields", fields);
                if (comment != null && !comment.isBlank()) body.put("update", Map.of("comment", List.of(Map.of("add", Map.of("body", comment)))));
                auth(HttpJson.req(P, "POST", url), email, apiToken, pat).json(body).send(ctx);
                return Map.of();
            }
        }
        throw new ActionException(P + ".NO_TRANSITION", "Transition '" + name + "' is not available for " + k, "Available now: " + names + ".");
    }

    /** Earlier signature, kept for callers written before comment and fields. */
    public Map<String, Object> transition(ActionContext ctx, String site, String k, String name, String email, Object apiToken, Object pat) { return transition(ctx, site, k, name, email, apiToken, pat, null, null); }

    @Action(id = "attach_file", name = "Attach file", category = "Issues", timeoutSeconds = 600, capabilities = {"network", "filesystem"}, description = "Attach a local file to an issue.")
    public Map<String, Object> attach(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "path", type = OoaType.FILE, description = "File to attach.") String path,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        Path p = ctx.resolvePath(path);
        byte[] data;
        try { data = Files.readAllBytes(p); } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read " + p, "Check the path."); }
        String fname = p.getFileName().toString().replace("\"", "");
        Object[] mp = HttpJson.multipart("form-data", Collections.singletonList(new Object[]{"Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\nContent-Type: application/octet-stream", data}));
        auth(HttpJson.req(P, "POST", api(site) + "/issue/" + key(k) + "/attachments"), email, apiToken, pat).header("X-Atlassian-Token", "no-check")
                .bytes((byte[]) mp[0], (String) mp[1]).timeout(600).send(ctx);
        return Map.of();
    }

    // ---- Comments, issue maintenance and projects ----

    private static final Pattern PROJECT_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,19}$");
    private static String projectKey(String k) { if (k == null || !PROJECT_KEY.matcher(k).matches()) throw new ActionException(P + ".BAD_PROJECT_KEY", "Invalid project key '" + k + "'", "Use upper-case letters and digits, e.g. OPS."); return k; }
    private static String commentId(String id) { if (id == null || !id.matches("^\\d{1,19}$")) throw new ActionException(P + ".BAD_COMMENT_ID", "Invalid comment id '" + id + "'", "Comment ids are numbers; read them with Get comments."); return id; }
    private static String name(Object user) { return user instanceof Map<?, ?> m && m.get("displayName") != null ? String.valueOf(m.get("displayName")) : null; }

    @Action(id = "get_comments", name = "Get comments", category = "Comments", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "List the comments on an issue, oldest first, as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, author, created, updated, body.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of comments returned.")
    public Map<String, Object> getComments(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Maximum comments to read.") int max,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        List<Object> rows = new ArrayList<>();
        int start = 0;
        while (rows.size() < max) {
            String url = api(site) + "/issue/" + key(k) + "/comment?startAt=" + start + "&maxResults=" + Math.min(100, max - rows.size());
            Map<String, Object> r = auth(HttpJson.req(P, "GET", url), email, apiToken, pat).timeout(120).send(ctx).map();
            List<?> cs = (List<?>) r.getOrDefault("comments", List.of());
            for (Object o : cs) {
                Map<?, ?> c = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(c.get("id"), name(c.get("author")), c.get("created"), c.get("updated"), c.get("body"))));
            }
            start += cs.size();
            int total = r.get("total") instanceof BigDecimal t ? t.intValue() : start;
            if (cs.isEmpty() || start >= total) break;
        }
        return Map.of("table", Values.table(List.of("id", "author", "created", "updated", "body"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "update_comment", name = "Update comment", category = "Comments", idempotent = true, capabilities = "network", description = "Replace the text of an existing comment.")
    public Map<String, Object> updateComment(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "comment_id", type = OoaType.STRING, description = "Comment id, from Get comments.") String id,
            @Input(name = "text", type = OoaType.STRING, description = "New comment text.") String text,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        auth(HttpJson.req(P, "PUT", api(site) + "/issue/" + key(k) + "/comment/" + commentId(id)), email, apiToken, pat).json(Map.of("body", text)).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_comment", name = "Delete comment", category = "Comments", capabilities = "network", description = "Remove a comment from an issue.")
    public Map<String, Object> deleteComment(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "comment_id", type = OoaType.STRING, description = "Comment id, from Get comments.") String id,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        auth(HttpJson.req(P, "DELETE", api(site) + "/issue/" + key(k) + "/comment/" + commentId(id)), email, apiToken, pat).send(ctx);
        return Map.of();
    }

    @Action(id = "update_issue", name = "Update issue", category = "Issues", idempotent = true, capabilities = "network", description = "Change fields of an issue, such as summary, priority, labels or assignee.")
    public Map<String, Object> updateIssue(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "Field name to new value, e.g. {\"summary\": \"Disk full on db01\", \"priority\": {\"name\": \"High\"}}.") Map<String, Object> fields,
            @Input(name = "notify_users", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send the usual change notifications to watchers.") boolean notify,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "summary", type = OoaType.STRING, required = false, description = "New summary; unchanged when empty.") String summary,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "New description; unchanged when empty.") String description,
            @Input(name = "priority", type = OoaType.STRING, required = false, description = "New priority name; unchanged when empty.") String priority,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = "New assignee: an email address (looked up), a Cloud account id or a Data Center user name; unchanged when empty.") String assignee,
            @Input(name = "labels", type = OoaType.LIST, required = false, description = "Replace the labels with these; unchanged when empty.") List<Object> labels,
            @Input(name = "due_date", type = OoaType.STRING, required = false, description = "New due date, yyyy-MM-dd; unchanged when empty.") String due) {
        Map<String, Object> all = new LinkedHashMap<>();
        if (summary != null && !summary.isBlank()) all.put("summary", summary);
        if (description != null && !description.isBlank()) all.put("description", description);
        common(ctx, site, all, priority, assignee, labels == null || labels.isEmpty() ? null : labels, null, due, email, apiToken, pat);
        if (fields != null) all.putAll(fields);
        if (all.isEmpty()) throw new ActionException(P + ".NO_FIELDS", "No fields to change", "Give at least one field, e.g. {\"summary\": \"New title\"}.");
        auth(HttpJson.req(P, "PUT", api(site) + "/issue/" + key(k) + (notify ? "" : "?notifyUsers=false")), email, apiToken, pat).json(Map.of("fields", all)).send(ctx);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the named fields. */
    public Map<String, Object> updateIssue(ActionContext ctx, String site, String k, Map<String, Object> fields, boolean notify, String email, Object apiToken, Object pat) {
        return updateIssue(ctx, site, k, fields, notify, email, apiToken, pat, null, null, null, null, null, null);
    }

    @Action(id = "delete_issue", name = "Delete issue", category = "Issues", capabilities = "network", description = "Permanently delete an issue, optionally with its sub-tasks.")
    public Map<String, Object> deleteIssue(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "delete_subtasks", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete its sub-tasks (required when it has any).") boolean subtasks,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        auth(HttpJson.req(P, "DELETE", api(site) + "/issue/" + key(k) + "?deleteSubtasks=" + subtasks), email, apiToken, pat).send(ctx);
        return Map.of();
    }

    @Action(id = "create_project", name = "Create project", category = "Projects", capabilities = "network", description = "Create a project with a key, name, type and project lead.")
    @Output(name = "id", type = OoaType.STRING, description = "Project id.")
    @Output(name = "key", type = OoaType.STRING, description = "Project key.")
    public Map<String, Object> createProject(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "project_key", type = OoaType.STRING, example = "OPS", description = "New project key (upper-case letters and digits).") String pk,
            @Input(name = "project_name", type = OoaType.STRING, description = "Project name.") String name,
            @Input(name = "project_type", type = OoaType.STRING, required = false, defaultValue = "\"software\"", enumValues = {"software", "business", "service_desk"}, description = "Project type key.") String type,
            @Input(name = "lead_account_id", type = OoaType.STRING, required = false, description = "Project lead's account id (Cloud).") String leadId,
            @Input(name = "lead_user_name", type = OoaType.STRING, required = false, description = "Project lead's user name (Data Center).") String leadName,
            @Input(name = "template_key", type = OoaType.STRING, required = false, description = "Project template key; the site default when empty.") String template,
            @Input(name = "project_description", type = OoaType.STRING, required = false, description = "Project description.") String description,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        if ((leadId == null || leadId.isBlank()) && (leadName == null || leadName.isBlank()))
            throw new ActionException(P + ".NO_LEAD", "A project lead is required", "Give lead_account_id (Cloud) or lead_user_name (Data Center).");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", projectKey(pk));
        body.put("name", name);
        body.put("projectTypeKey", type);
        if (leadId != null && !leadId.isBlank()) body.put("leadAccountId", leadId); else body.put("lead", leadName);
        if (template != null && !template.isBlank()) body.put("projectTemplateKey", template);
        if (description != null) body.put("description", description);
        Map<String, Object> r = auth(HttpJson.req(P, "POST", api(site) + "/project"), email, apiToken, pat).json(body).send(ctx).map();
        return Out.of("id", r.get("id") == null ? null : Values.text(r.get("id")), "key", r.getOrDefault("key", pk));
    }

    @Action(id = "get_projects", name = "Get projects", category = "Projects", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "List the projects the account can see as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, key, name, project_type, lead.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of projects.")
    public Map<String, Object> getProjects(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "query", type = OoaType.STRING, required = false, description = "Only projects whose key or name contains this text.") String query,
            @Input(name = "project_type", type = OoaType.STRING, required = false, enumValues = {"software", "business", "service_desk"}, description = "Only projects of this type.") String type,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Maximum projects.") int max,
            @Input(name = "start_at", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Skip this many projects first (for paging).") int startAt) {
        Object j = auth(HttpJson.req(P, "GET", api(site) + "/project?expand=lead,description"), email, apiToken, pat).timeout(120).send(ctx).json();
        List<Object> rows = new ArrayList<>();
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        int skipped = 0;
        if (j instanceof List<?> l) for (Object o : l) {
            Map<?, ?> p = (Map<?, ?>) o;
            if (!q.isEmpty() && !String.valueOf(p.get("key")).toLowerCase(Locale.ROOT).contains(q) && !String.valueOf(p.get("name")).toLowerCase(Locale.ROOT).contains(q)) continue;
            if (type != null && !type.isBlank() && !type.equals(p.get("projectTypeKey"))) continue;
            if (skipped++ < Math.max(0, startAt)) continue;
            if (rows.size() >= Math.max(0, max)) break;
            rows.add(new ArrayList<>(Arrays.asList(p.get("id"), p.get("key"), p.get("name"), p.get("projectTypeKey"), name(p.get("lead")))));
        }
        return Map.of("table", Values.table(List.of("id", "key", "name", "project_type", "lead"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    /** Earlier signature, kept for callers written before the filters. */
    public Map<String, Object> getProjects(ActionContext ctx, String site, String email, Object apiToken, Object pat) { return getProjects(ctx, site, email, apiToken, pat, null, null, 1000, 0); }

    @Action(id = "update_project", name = "Update project", category = "Projects", idempotent = true, capabilities = "network", description = "Change project details such as its name, lead, description, default assignee or category.")
    public Map<String, Object> updateProject(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "project_key", type = OoaType.STRING, description = "Project key.") String pk,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "Project field to new value, e.g. {\"name\": \"Operations\", \"description\": \"...\"}.") Map<String, Object> fields,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat,
            @Input(name = "project_name", type = OoaType.STRING, required = false, description = "New name; unchanged when empty.") String newName,
            @Input(name = "project_description", type = OoaType.STRING, required = false, description = "New description; unchanged when empty.") String description,
            @Input(name = "lead", type = OoaType.STRING, required = false, description = "New lead: an email address (looked up), a Cloud account id or a Data Center user name.") String lead,
            @Input(name = "assignee_type", type = OoaType.STRING, required = false, enumValues = {"PROJECT_LEAD", "UNASSIGNED"}, description = "Who new issues go to by default.") String assigneeType,
            @Input(name = "category_id", type = OoaType.STRING, required = false, description = "Id of the project category.") String categoryId) {
        Map<String, Object> all = new LinkedHashMap<>();
        if (newName != null && !newName.isBlank()) all.put("name", newName.strip());
        if (description != null && !description.isBlank()) all.put("description", description);
        if (lead != null && !lead.isBlank()) {
            Map<String, Object> u = user(ctx, site, lead.strip(), email, apiToken, pat);
            if (u.containsKey("accountId")) all.put("leadAccountId", u.get("accountId")); else all.put("lead", u.get("name"));
        }
        if (assigneeType != null && !assigneeType.isBlank()) all.put("assigneeType", assigneeType);
        if (categoryId != null && !categoryId.isBlank()) {
            if (!categoryId.matches("^\\d{1,19}$")) throw new ActionException(P + ".BAD_ID", "Invalid category id '" + categoryId + "'");
            all.put("categoryId", new BigDecimal(categoryId));
        }
        if (fields != null) all.putAll(fields);
        if (all.isEmpty()) throw new ActionException(P + ".NO_FIELDS", "No fields to change", "Give at least one field, e.g. {\"name\": \"Operations\"}.");
        auth(HttpJson.req(P, "PUT", api(site) + "/project/" + projectKey(pk)), email, apiToken, pat).json(all).send(ctx);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the named fields. */
    public Map<String, Object> updateProject(ActionContext ctx, String site, String pk, Map<String, Object> fields, String email, Object apiToken, Object pat) {
        return updateProject(ctx, site, pk, fields, email, apiToken, pat, null, null, null, null, null);
    }

    @Action(id = "delete_project", name = "Delete project", category = "Projects", capabilities = "network", description = "Delete a project and the issues in it (Cloud keeps it in the trash for a while).")
    public Map<String, Object> deleteProject(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "project_key", type = OoaType.STRING, description = "Project key.") String pk,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        auth(HttpJson.req(P, "DELETE", api(site) + "/project/" + projectKey(pk)), email, apiToken, pat).send(ctx);
        return Map.of();
    }
}
