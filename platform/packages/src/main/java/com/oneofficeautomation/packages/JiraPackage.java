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
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "project", type = OoaType.STRING, example = "OPS", description = "Project key.") String project,
            @Input(name = "issue_type", type = OoaType.STRING, required = false, defaultValue = "\"Task\"", description = "Issue type name, e.g. Task, Bug, Story.") String type,
            @Input(name = "summary", type = OoaType.STRING, description = "Summary.") String summary,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "extra_fields", type = OoaType.DICTIONARY, required = false, description = "More fields, e.g. {\"priority\": {\"name\": \"High\"}, \"labels\": [\"bot\"]}.") Map<String, Object> extra,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", project));
        fields.put("issuetype", Map.of("name", type));
        fields.put("summary", summary);
        if (description != null) fields.put("description", description);
        if (extra != null) fields.putAll(extra);
        Map<String, Object> r = auth(HttpJson.req(P, "POST", api(site) + "/issue"), email, apiToken, pat).json(Map.of("fields", fields)).send(ctx).map();
        return Out.of("key", r.get("key"), "id", r.get("id"));
    }

    @Action(id = "get_issue", name = "Get issue", category = "Issues", idempotent = true, capabilities = "network", description = "Read an issue's main fields.")
    @Output(name = "issue", type = OoaType.DICTIONARY, description = "key, summary, status, assignee, reporter, priority, created, updated, description.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        Map<String, Object> r = auth(HttpJson.req(P, "GET", api(site) + "/issue/" + key(k)), email, apiToken, pat).send(ctx).map();
        return Map.of("issue", summarize(r));
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
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        List<Object> rows = new ArrayList<>();
        int start = 0;
        while (rows.size() < max) {
            Map<String, Object> body = Map.of("jql", jql, "startAt", start, "maxResults", Math.min(100, max - rows.size()),
                    "fields", List.of("summary", "status", "assignee", "priority", "updated"));
            Map<String, Object> r = auth(HttpJson.req(P, "POST", api(site) + "/search"), email, apiToken, pat).json(body).timeout(120).send(ctx).map();
            List<?> issues = (List<?>) r.getOrDefault("issues", List.of());
            for (Object o : issues) {
                Map<String, Object> s = summarize((Map<?, ?>) o);
                rows.add(new ArrayList<>(Arrays.asList(s.get("key"), s.get("summary"), s.get("status"), s.get("assignee"), s.get("priority"), s.get("updated"))));
            }
            int total = r.get("total") instanceof BigDecimal t ? t.intValue() : 0;
            start += issues.size();
            if (issues.isEmpty() || start >= total) break;
        }
        return Map.of("table", Values.table(List.of("key", "summary", "status", "assignee", "priority", "updated"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "add_comment", name = "Add comment", category = "Issues", capabilities = "network", description = "Add a comment to an issue.")
    public Map<String, Object> comment(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "text", type = OoaType.STRING, description = "Comment text.") String text,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        auth(HttpJson.req(P, "POST", api(site) + "/issue/" + key(k) + "/comment"), email, apiToken, pat).json(Map.of("body", text)).send(ctx);
        return Map.of();
    }

    @Action(id = "transition", name = "Move issue (transition)", category = "Issues", capabilities = "network",
            description = "Move an issue through its workflow by transition name, e.g. In Progress or Done.")
    public Map<String, Object> transition(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "key", type = OoaType.STRING, description = "Issue key.") String k,
            @Input(name = "transition", type = OoaType.STRING, example = "Done", description = "Transition or target status name.") String name,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        String url = api(site) + "/issue/" + key(k) + "/transitions";
        List<?> ts = (List<?>) auth(HttpJson.req(P, "GET", url), email, apiToken, pat).send(ctx).map().getOrDefault("transitions", List.of());
        List<String> names = new ArrayList<>();
        for (Object o : ts) {
            Map<?, ?> t = (Map<?, ?>) o;
            String tn = String.valueOf(t.get("name"));
            String to = t.get("to") instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : "";
            names.add(tn);
            if (tn.equalsIgnoreCase(name) || to.equalsIgnoreCase(name)) {
                auth(HttpJson.req(P, "POST", url), email, apiToken, pat).json(Map.of("transition", Map.of("id", t.get("id")))).send(ctx);
                return Map.of();
            }
        }
        throw new ActionException(P + ".NO_TRANSITION", "Transition '" + name + "' is not available for " + k, "Available now: " + names + ".");
    }

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
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to new value, e.g. {\"summary\": \"Disk full on db01\", \"priority\": {\"name\": \"High\"}}.") Map<String, Object> fields,
            @Input(name = "notify_users", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send the usual change notifications to watchers.") boolean notify,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        if (fields == null || fields.isEmpty()) throw new ActionException(P + ".NO_FIELDS", "No fields to change", "Give at least one field, e.g. {\"summary\": \"New title\"}.");
        auth(HttpJson.req(P, "PUT", api(site) + "/issue/" + key(k) + (notify ? "" : "?notifyUsers=false")), email, apiToken, pat).json(Map.of("fields", fields)).send(ctx);
        return Map.of();
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
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        Object j = auth(HttpJson.req(P, "GET", api(site) + "/project?expand=lead"), email, apiToken, pat).timeout(120).send(ctx).json();
        List<Object> rows = new ArrayList<>();
        if (j instanceof List<?> l) for (Object o : l) {
            Map<?, ?> p = (Map<?, ?>) o;
            rows.add(new ArrayList<>(Arrays.asList(p.get("id"), p.get("key"), p.get("name"), p.get("projectTypeKey"), name(p.get("lead")))));
        }
        return Map.of("table", Values.table(List.of("id", "key", "name", "project_type", "lead"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "update_project", name = "Update project", category = "Projects", idempotent = true, capabilities = "network", description = "Change project details such as its name, lead or description.")
    public Map<String, Object> updateProject(ActionContext ctx,
            @Input(name = "site_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String site,
            @Input(name = "project_key", type = OoaType.STRING, description = "Project key.") String pk,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Project field to new value, e.g. {\"name\": \"Operations\", \"description\": \"...\"}.") Map<String, Object> fields,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Account email (Cloud).") String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = "API token from the vault (Cloud).") Object apiToken,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, required = false, description = "Personal access token (Data Center).") Object pat) {
        if (fields == null || fields.isEmpty()) throw new ActionException(P + ".NO_FIELDS", "No fields to change", "Give at least one field, e.g. {\"name\": \"Operations\"}.");
        auth(HttpJson.req(P, "PUT", api(site) + "/project/" + projectKey(pk)), email, apiToken, pat).json(fields).send(ctx);
        return Map.of();
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
