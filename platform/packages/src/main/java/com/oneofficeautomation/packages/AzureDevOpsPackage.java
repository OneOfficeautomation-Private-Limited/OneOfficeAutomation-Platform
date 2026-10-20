package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Azure DevOps Services and Azure DevOps Server (2022 or later) REST API, version 7.0.
 * Sign-in with a personal access token sent as basic authentication with an empty user name.
 */
@OoaPackage(id = "ooa.azure_devops", name = "Azure DevOps", version = "1.0.0",
        description = "Work with Azure DevOps: work items and WIQL queries, comments and attachments, Git repositories and pull requests, pipelines, builds and sprints. Works with the cloud service and Azure DevOps Server.")
public final class AzureDevOpsPackage {
    static final String P = "AZURE_DEVOPS";
    static final String V = "7.0";
    static final String V_COMMENTS = "7.0-preview.3";
    private static final String D_ORG = "Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server.";
    private static final String D_PAT = "Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write).";
    private static final String D_PROJECT = "Project name, e.g. Finance Portal.";
    private static final String D_REPO = "Repository name or id, e.g. finance-portal-api.";
    private static final String D_MAX = "Most items to return; pages are read until this many are found.";
    private static final Map<String, String> FRIENDLY = Map.of("title", "System.Title", "description", "System.Description",
            "assigned_to", "System.AssignedTo", "state", "System.State", "area_path", "System.AreaPath", "iteration_path", "System.IterationPath",
            "tags", "System.Tags", "priority", "Microsoft.VSTS.Common.Priority");

    static String org(String o) { return o.replaceAll("/+$", ""); }
    static String seg(String what, String v) {
        if (v == null || v.isBlank()) throw new ActionException(P + ".MISSING_" + what.toUpperCase(Locale.ROOT), "The " + what + " is empty", "Fill in the " + what + ".");
        return HttpJson.enc(v.strip());
    }
    static String proj(String org, String project) { return org(org) + "/" + seg("project", project); }
    static String withVersion(String url, String version) { return url + (url.contains("?") ? "&" : "?") + "api-version=" + version; }

    static HttpJson.Req req(String method, String url, Object pat) { return HttpJson.req(P, method, url).basic("", pat); }

    /** Sends; a 203 answer or an HTML page means the token was not accepted (Azure DevOps redirects to its sign-in page). */
    static HttpJson.Response send(HttpJson.Req r, ActionContext ctx) {
        HttpJson.Response resp = r.send(ctx);
        if (resp.status() == 203 || (!r.binary && !resp.text().isBlank() && resp.json() == null && resp.text().stripLeading().startsWith("<")))
            throw new ActionException(P + ".AUTH_FAILED", "Azure DevOps did not accept the personal access token",
                    "Check the token in the vault is valid, not expired, and has the needed scopes.", false, Map.of("status", BigDecimal.valueOf(resp.status())));
        return resp;
    }

    static Map<String, Object> call(ActionContext ctx, String method, String url, Object pat, Object body) {
        HttpJson.Req r = req(method, withVersion(url, V), pat);
        if (body != null) r.json(body);
        return send(r, ctx).map();
    }

    /** Reads pages linked by the x-ms-continuationtoken header. */
    static List<Map<String, Object>> pages(ActionContext ctx, String url, Object pat, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cont = null;
        while (out.size() < max) {
            String u = url + (url.contains("?") ? "&" : "?") + "$top=" + Math.max(1, Math.min(200, max - out.size()))
                    + (cont == null ? "" : "&continuationToken=" + HttpJson.enc(cont));
            HttpJson.Response r = send(req("GET", withVersion(u, V), pat).timeout(120), ctx);
            List<Map<String, Object>> items = HttpJson.maps(r.map().get("value"));
            for (Map<String, Object> m : items) { out.add(m); if (out.size() >= max) break; }
            cont = r.header("x-ms-continuationtoken");
            if (cont == null || cont.isBlank() || items.isEmpty()) break;
        }
        return out;
    }

    static Object who(Object identity) {
        if (identity instanceof Map<?, ?> m) return m.get("displayName") != null ? m.get("displayName") : m.get("uniqueName");
        return identity;
    }

    static String ref(String branch) { return branch.startsWith("refs/") ? branch : "refs/heads/" + branch; }

    static Map<String, Object> result(Map<String, Object> table, int n) { return Out.of("table", table, "count", BigDecimal.valueOf(n)); }

    static List<Map<String, Object>> fieldOps(String op, Map<String, Object> fields) {
        List<Map<String, Object>> ops = new ArrayList<>();
        fields.forEach((k, v) -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("op", op);
            o.put("path", "/fields/" + FRIENDLY.getOrDefault(k, k));
            o.put("value", v);
            ops.add(o);
        });
        return ops;
    }

    static Map<String, Object> workItem(Map<String, Object> w) {
        Map<?, ?> f = w.get("fields") instanceof Map<?, ?> m ? m : Map.of();
        return Out.of("id", w.get("id"), "rev", w.get("rev"), "type", f.get("System.WorkItemType"), "title", f.get("System.Title"),
                "state", f.get("System.State"), "reason", f.get("System.Reason"), "assigned_to", who(f.get("System.AssignedTo")),
                "area_path", f.get("System.AreaPath"), "iteration_path", f.get("System.IterationPath"), "tags", f.get("System.Tags"),
                "priority", f.get("Microsoft.VSTS.Common.Priority"), "description", f.get("System.Description"),
                "created_by", who(f.get("System.CreatedBy")), "created_date", f.get("System.CreatedDate"), "changed_date", f.get("System.ChangedDate"),
                "url", HttpJson.path(w, "_links.html.href"), "fields", f);
    }

    static Map<String, Object> pull(Map<String, Object> p) {
        return Out.of("id", p.get("pullRequestId"), "title", p.get("title"), "description", p.get("description"), "status", p.get("status"),
                "is_draft", p.get("isDraft"), "merge_status", p.get("mergeStatus"), "created_by", who(p.get("createdBy")),
                "source_branch", p.get("sourceRefName"), "target_branch", p.get("targetRefName"), "created", p.get("creationDate"),
                "closed", p.get("closedDate"), "repository", HttpJson.path(p, "repository.name"),
                "last_source_commit", HttpJson.path(p, "lastMergeSourceCommit.commitId"));
    }

    // ---------------------------------------------------------------- projects

    @Action(id = "list_projects", name = "List projects", category = "Projects", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the projects in the organization or collection.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, state, visibility, description, last_update.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of projects returned.")
    public Map<String, Object> listProjects(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "200", description = D_MAX) int max) {
        List<Map<String, Object>> recs = pages(ctx, org(org) + "/_apis/projects", pat, max);
        return result(HttpJson.table(recs, "id", "name", "state", "visibility", "description", "last_update=lastUpdateTime"), recs.size());
    }

    @Action(id = "get_project", name = "Get project", category = "Projects", idempotent = true, capabilities = "network", description = "Read a project's details.")
    @Output(name = "project", type = OoaType.DICTIONARY, description = "id, name, description, state, visibility, default_team, url.")
    public Map<String, Object> getProject(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = "Project name or id.") String project) {
        Map<String, Object> r = call(ctx, "GET", org(org) + "/_apis/projects/" + seg("project", project), pat, null);
        return Out.of("project", Out.of("id", r.get("id"), "name", r.get("name"), "description", r.get("description"), "state", r.get("state"),
                "visibility", r.get("visibility"), "default_team", HttpJson.path(r, "defaultTeam.name"), "url", HttpJson.path(r, "_links.web.href")));
    }

    // ---------------------------------------------------------------- work items

    @Action(id = "create_work_item", name = "Create work item", category = "Work items", capabilities = "network",
            description = "Create a work item such as a Bug, Task or User Story.")
    @Output(name = "id", type = OoaType.NUMBER, description = "Work item id, e.g. 4711.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the work item.")
    public Map<String, Object> createWorkItem(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "type", type = OoaType.STRING, example = "Bug", description = "Work item type, e.g. Bug, Task, User Story, Issue.") String type,
            @Input(name = "title", type = OoaType.STRING, description = "Title of the work item.") String title,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description; simple HTML is allowed.") String description,
            @Input(name = "assigned_to", type = OoaType.STRING, required = false, example = "ana@contoso.com", description = "Person to assign, by sign-in email or display name.") String assignedTo,
            @Input(name = "area_path", type = OoaType.STRING, required = false, example = "Finance Portal\\Payments", description = "Area path.") String area,
            @Input(name = "iteration_path", type = OoaType.STRING, required = false, example = "Finance Portal\\Sprint 42", description = "Iteration (sprint) path.") String iteration,
            @Input(name = "tags", type = OoaType.STRING, required = false, example = "agent; invoices", description = "Tags separated by semicolons.") String tags,
            @Input(name = "parent_id", type = OoaType.NUMBER, required = false, description = "Id of a parent work item to link to.") Integer parent,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "More fields by reference name, e.g. {\"Microsoft.VSTS.Common.Priority\": 1}.") Map<String, Object> extra) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("title", title);
        if (description != null) f.put("description", description);
        if (assignedTo != null) f.put("assigned_to", assignedTo);
        if (area != null) f.put("area_path", area);
        if (iteration != null) f.put("iteration_path", iteration);
        if (tags != null) f.put("tags", tags);
        if (extra != null) f.putAll(extra);
        List<Map<String, Object>> ops = fieldOps("add", f);
        if (parent != null) ops.add(Map.of("op", "add", "path", "/relations/-",
                "value", Map.of("rel", "System.LinkTypes.Hierarchy-Reverse", "url", org(org) + "/_apis/wit/workItems/" + parent)));
        String url = withVersion(proj(org, project) + "/_apis/wit/workitems/$" + seg("type", type), V);
        Map<String, Object> r = send(req("POST", url, pat).json(ops).header("Content-Type", "application/json-patch+json"), ctx).map();
        return Out.of("id", r.get("id"), "url", HttpJson.path(r, "_links.html.href"));
    }

    @Action(id = "get_work_item", name = "Get work item", category = "Work items", idempotent = true, capabilities = "network", description = "Read a work item by id.")
    @Output(name = "work_item", type = OoaType.DICTIONARY, description = "id, rev, type, title, state, reason, assigned_to, area_path, iteration_path, tags, priority, description, created_by, created_date, changed_date, url, fields (all fields).")
    @Output(name = "state", type = OoaType.STRING, description = "Current state, e.g. Active.")
    public Map<String, Object> getWorkItem(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "id", type = OoaType.NUMBER, example = "4711", description = "Work item id.") int id) {
        Map<String, Object> w = workItem(call(ctx, "GET", org(org) + "/_apis/wit/workitems/" + id + "?$expand=links", pat, null));
        return Out.of("work_item", w, "state", w.get("state"));
    }

    @Action(id = "update_work_item", name = "Update work item", category = "Work items", capabilities = "network",
            description = "Change fields of a work item, such as its state or assignee. Only the inputs you fill in are changed.")
    @Output(name = "rev", type = OoaType.NUMBER, description = "New revision number.")
    public Map<String, Object> updateWorkItem(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "id", type = OoaType.NUMBER, description = "Work item id.") int id,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "New title.") String title,
            @Input(name = "state", type = OoaType.STRING, required = false, example = "Resolved", description = "New state, e.g. Active, Resolved, Closed.") String state,
            @Input(name = "assigned_to", type = OoaType.STRING, required = false, description = "New assignee by email or display name.") String assignedTo,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "New description.") String description,
            @Input(name = "iteration_path", type = OoaType.STRING, required = false, description = "New iteration (sprint) path.") String iteration,
            @Input(name = "tags", type = OoaType.STRING, required = false, description = "Replace tags; separate with semicolons.") String tags,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "More fields by reference name, e.g. {\"Microsoft.VSTS.Scheduling.RemainingWork\": 2}.") Map<String, Object> extra) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (title != null) f.put("title", title);
        if (state != null) f.put("state", state);
        if (assignedTo != null) f.put("assigned_to", assignedTo);
        if (description != null) f.put("description", description);
        if (iteration != null) f.put("iteration_path", iteration);
        if (tags != null) f.put("tags", tags);
        if (extra != null) f.putAll(extra);
        if (f.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No changes were given", "Fill in at least one field to change.");
        String url = withVersion(org(org) + "/_apis/wit/workitems/" + id, V);
        Map<String, Object> r = send(req("PATCH", url, pat).json(fieldOps("add", f)).header("Content-Type", "application/json-patch+json"), ctx).map();
        return Out.of("rev", r.get("rev"));
    }

    @Action(id = "delete_work_item", name = "Delete work item", category = "Work items", capabilities = "network",
            description = "Move a work item to the Recycle Bin, or remove it for good.")
    public Map<String, Object> deleteWorkItem(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "id", type = OoaType.NUMBER, description = "Work item id.") int id,
            @Input(name = "permanently", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove for good instead of moving to the Recycle Bin. This cannot be undone.") boolean destroy) {
        call(ctx, "DELETE", org(org) + "/_apis/wit/workitems/" + id + "?destroy=" + destroy, pat, null);
        return Map.of();
    }

    @Action(id = "query_work_items", name = "Query work items (WIQL)", category = "Work items", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Find work items with a WIQL query and return their main fields as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, type, title, state, assigned_to, changed_date, then any extra fields asked for.")
    @Output(name = "ids", type = OoaType.LIST, description = "Ids of the work items found.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of work items returned.")
    public Map<String, Object> queryWorkItems(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "wiql", type = OoaType.STRING, example = "SELECT [System.Id] FROM WorkItems WHERE [System.State] = 'Active' AND [System.AssignedTo] = @Me",
                    description = "WIQL query. Only the ids it finds are used; choose columns with extra_fields.") String wiql,
            @Input(name = "extra_fields", type = OoaType.LIST, required = false, description = "More field reference names to add as columns, e.g. [\"Microsoft.VSTS.Common.Priority\"].") List<Object> extraFields,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = D_MAX) int max) {
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/wit/wiql?$top=" + Math.max(1, max), pat, Map.of("query", wiql));
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> w : HttpJson.maps(r.get("workItems"))) { ids.add(w.get("id")); if (ids.size() >= max) break; }
        List<String> fields = new ArrayList<>(List.of("System.Id", "System.WorkItemType", "System.Title", "System.State", "System.AssignedTo", "System.ChangedDate"));
        List<String> extra = new ArrayList<>();
        if (extraFields != null) for (Object o : extraFields) { String s = Values.text(o); if (!fields.contains(s)) { fields.add(s); extra.add(s); } }
        Map<Object, Map<?, ?>> byId = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 200) {
            List<Object> batch = ids.subList(i, Math.min(ids.size(), i + 200));
            Map<String, Object> b = call(ctx, "POST", org(org) + "/_apis/wit/workitemsbatch", pat, Map.of("ids", batch, "fields", fields));
            for (Map<String, Object> w : HttpJson.maps(b.get("value")))
                byId.put(Values.text(w.get("id")), w.get("fields") instanceof Map<?, ?> m ? m : Map.of());
        }
        List<String> cols = new ArrayList<>(List.of("id", "type", "title", "state", "assigned_to", "changed_date"));
        cols.addAll(extra);
        List<Object> rows = new ArrayList<>();
        for (Object id : ids) {
            Map<?, ?> f = byId.getOrDefault(Values.text(id), Map.of());
            List<Object> row = new ArrayList<>(Arrays.asList(id, f.get("System.WorkItemType"), f.get("System.Title"), f.get("System.State"),
                    who(f.get("System.AssignedTo")), f.get("System.ChangedDate")));
            for (String e : extra) row.add(who(f.get(e)));
            rows.add(row);
        }
        return Out.of("table", Values.table(cols, rows), "ids", ids, "count", BigDecimal.valueOf(ids.size()));
    }

    @Action(id = "add_work_item_comment", name = "Add work item comment", category = "Work items", capabilities = "network",
            description = "Add a comment to a work item's discussion.")
    @Output(name = "comment_id", type = OoaType.NUMBER, description = "Id of the new comment.")
    public Map<String, Object> addComment(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "id", type = OoaType.NUMBER, description = "Work item id.") int id,
            @Input(name = "text", type = OoaType.STRING, example = "Invoice re-sent by the finance agent.", description = "Comment text; simple HTML is allowed.") String text) {
        String url = withVersion(proj(org, project) + "/_apis/wit/workItems/" + id + "/comments", V_COMMENTS);
        Map<String, Object> r = send(req("POST", url, pat).json(Map.of("text", text)), ctx).map();
        return Out.of("comment_id", r.get("id"));
    }

    @Action(id = "list_work_item_comments", name = "List work item comments", category = "Work items", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Read the comments in a work item's discussion, newest first.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, text, created_by, created_date.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of comments returned.")
    public Map<String, Object> listComments(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "id", type = OoaType.NUMBER, description = "Work item id.") int id,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "200", description = D_MAX) int max) {
        List<Map<String, Object>> recs = new ArrayList<>();
        String cont = null;
        while (recs.size() < max) {
            String url = proj(org, project) + "/_apis/wit/workItems/" + id + "/comments?$top=" + Math.min(200, max - recs.size())
                    + (cont == null ? "" : "&continuationToken=" + HttpJson.enc(cont));
            Map<String, Object> r = send(req("GET", withVersion(url, V_COMMENTS), pat), ctx).map();
            List<Map<String, Object>> page = HttpJson.maps(r.get("comments"));
            for (Map<String, Object> c : page) { recs.add(c); if (recs.size() >= max) break; }
            cont = r.get("continuationToken") == null ? null : Values.text(r.get("continuationToken"));
            if (cont == null || cont.isBlank() || page.isEmpty()) break;
        }
        return result(HttpJson.table(recs, "id", "text", "created_by=createdBy.displayName", "created_date=createdDate"), recs.size());
    }

    @Action(id = "attach_file_to_work_item", name = "Attach file to work item", category = "Work items", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Upload a local file and attach it to a work item.")
    @Output(name = "attachment_url", type = OoaType.STRING, description = "Address of the uploaded file.")
    public Map<String, Object> attachFile(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "id", type = OoaType.NUMBER, description = "Work item id.") int id,
            @Input(name = "path", type = OoaType.FILE, example = "out/error-log.txt", description = "Local file to attach.") String path,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Short note shown next to the attachment.") String comment) {
        byte[] data = HttpJson.readFile(ctx, path);
        String fname = HttpJson.safeName(Path.of(path).getFileName().toString());
        String up = withVersion(proj(org, project) + "/_apis/wit/attachments?fileName=" + HttpJson.enc(fname), V);
        Map<String, Object> a = send(req("POST", up, pat).bytes(data, "application/octet-stream").timeout(600), ctx).map();
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("rel", "AttachedFile");
        rel.put("url", a.get("url"));
        if (comment != null) rel.put("attributes", Map.of("comment", comment));
        String url = withVersion(org(org) + "/_apis/wit/workitems/" + id, V);
        send(req("PATCH", url, pat).json(List.of(Map.of("op", "add", "path", "/relations/-", "value", rel))).header("Content-Type", "application/json-patch+json"), ctx);
        return Out.of("attachment_url", a.get("url"));
    }

    // ---------------------------------------------------------------- repos and pull requests

    @Action(id = "list_repositories", name = "List repositories", category = "Repos", idempotent = true, capabilities = "network",
            description = "List the Git repositories of a project.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, default_branch, size, web_url, remote_url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of repositories.")
    public Map<String, Object> listRepositories(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project) {
        List<Map<String, Object>> recs = HttpJson.maps(call(ctx, "GET", proj(org, project) + "/_apis/git/repositories", pat, null).get("value"));
        return result(HttpJson.table(recs, "id", "name", "default_branch=defaultBranch", "size", "web_url=webUrl", "remote_url=remoteUrl"), recs.size());
    }

    @Action(id = "list_pull_requests", name = "List pull requests", category = "Repos", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List pull requests of a repository, filtered by status and target branch.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, title, status, is_draft, created_by, source_branch, target_branch, created, merge_status.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pull requests returned.")
    public Map<String, Object> listPullRequests(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "repository", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"active\"", enumValues = {"active", "completed", "abandoned", "all"}, description = "active, completed, abandoned or all.") String status,
            @Input(name = "target_branch", type = OoaType.STRING, required = false, example = "main", description = "Only pull requests into this branch.") String target,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max) {
        List<Map<String, Object>> recs = new ArrayList<>();
        int skip = 0, top = Math.max(1, Math.min(100, max));
        while (recs.size() < max) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("searchCriteria.status", status);
            if (target != null && !target.isBlank()) q.put("searchCriteria.targetRefName", ref(target));
            q.put("$top", top);
            q.put("$skip", skip);
            List<Map<String, Object>> page = HttpJson.maps(call(ctx, "GET", proj(org, project) + "/_apis/git/repositories/" + seg("repository", repo)
                    + "/pullrequests?" + HttpJson.query(q), pat, null).get("value"));
            for (Map<String, Object> p : page) { recs.add(p); if (recs.size() >= max) break; }
            if (page.size() < top) break;
            skip += top;
        }
        return result(HttpJson.table(recs, "id=pullRequestId", "title", "status", "is_draft=isDraft", "created_by=createdBy.displayName",
                "source_branch=sourceRefName", "target_branch=targetRefName", "created=creationDate", "merge_status=mergeStatus"), recs.size());
    }

    @Action(id = "get_pull_request", name = "Get pull request", category = "Repos", idempotent = true, capabilities = "network", description = "Read a pull request by id.")
    @Output(name = "pull_request", type = OoaType.DICTIONARY, description = "id, title, description, status, is_draft, merge_status, created_by, source_branch, target_branch, created, closed, repository, last_source_commit.")
    @Output(name = "status", type = OoaType.STRING, description = "active, completed or abandoned.")
    public Map<String, Object> getPullRequest(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "repository", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "pull_request_id", type = OoaType.NUMBER, description = "Pull request id.") int id) {
        Map<String, Object> p = pull(call(ctx, "GET", proj(org, project) + "/_apis/git/repositories/" + seg("repository", repo) + "/pullrequests/" + id, pat, null));
        return Out.of("pull_request", p, "status", p.get("status"));
    }

    @Action(id = "create_pull_request", name = "Create pull request", category = "Repos", capabilities = "network",
            description = "Propose merging one branch into another, optionally linking work items.")
    @Output(name = "pull_request_id", type = OoaType.NUMBER, description = "Id of the new pull request.")
    public Map<String, Object> createPullRequest(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "repository", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "source_branch", type = OoaType.STRING, example = "feature/vat-2027", description = "Branch with the changes.") String source,
            @Input(name = "target_branch", type = OoaType.STRING, example = "main", description = "Branch to merge into.") String target,
            @Input(name = "title", type = OoaType.STRING, description = "Title of the pull request.") String title,
            @Input(name = "description", type = OoaType.STRING, required = false, description = "Description in Markdown.") String description,
            @Input(name = "draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create as a draft.") boolean draft,
            @Input(name = "work_item_ids", type = OoaType.LIST, required = false, description = "Work item ids to link, e.g. [4711, 4712].") List<Object> workItems) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sourceRefName", ref(source));
        b.put("targetRefName", ref(target));
        b.put("title", title);
        if (description != null) b.put("description", description);
        b.put("isDraft", draft);
        if (workItems != null && !workItems.isEmpty()) {
            List<Object> refs = new ArrayList<>();
            for (Object w : workItems) refs.add(Map.of("id", Values.text(w)));
            b.put("workItemRefs", refs);
        }
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/git/repositories/" + seg("repository", repo) + "/pullrequests", pat, b);
        return Out.of("pull_request_id", r.get("pullRequestId"));
    }

    @Action(id = "complete_pull_request", name = "Complete pull request", category = "Repos", capabilities = "network",
            description = "Merge an active pull request into its target branch.")
    @Output(name = "status", type = OoaType.STRING, description = "Status after the request, normally completed.")
    @Output(name = "merge_status", type = OoaType.STRING, description = "Merge result, e.g. succeeded, queued or conflicts.")
    public Map<String, Object> completePullRequest(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "repository", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "pull_request_id", type = OoaType.NUMBER, description = "Pull request id.") int id,
            @Input(name = "merge_strategy", type = OoaType.STRING, required = false, defaultValue = "\"noFastForward\"", enumValues = {"noFastForward", "squash", "rebase", "rebaseMerge"},
                    description = "noFastForward (merge commit), squash, rebase or rebaseMerge.") String strategy,
            @Input(name = "delete_source_branch", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete the source branch after merging.") boolean deleteSource,
            @Input(name = "merge_commit_message", type = OoaType.STRING, required = false, description = "Message for the merge commit.") String message) {
        String url = proj(org, project) + "/_apis/git/repositories/" + seg("repository", repo) + "/pullrequests/" + id;
        Map<String, Object> cur = call(ctx, "GET", url, pat, null);
        if (!"active".equals(cur.get("status")))
            throw new ActionException(P + ".NOT_ACTIVE", "Pull request " + id + " is " + cur.get("status"), "Only active pull requests can be completed.");
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("mergeStrategy", strategy);
        opts.put("deleteSourceBranch", deleteSource);
        if (message != null) opts.put("mergeCommitMessage", message);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("status", "completed");
        b.put("lastMergeSourceCommit", Map.of("commitId", String.valueOf(HttpJson.path(cur, "lastMergeSourceCommit.commitId"))));
        b.put("completionOptions", opts);
        Map<String, Object> r = call(ctx, "PATCH", url, pat, b);
        return Out.of("status", r.get("status"), "merge_status", r.get("mergeStatus"));
    }

    @Action(id = "add_pull_request_comment", name = "Comment on pull request", category = "Repos", capabilities = "network",
            description = "Start a new comment thread on a pull request.")
    @Output(name = "thread_id", type = OoaType.NUMBER, description = "Id of the new thread.")
    public Map<String, Object> commentPullRequest(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "repository", type = OoaType.STRING, description = D_REPO) String repo,
            @Input(name = "pull_request_id", type = OoaType.NUMBER, description = "Pull request id.") int id,
            @Input(name = "text", type = OoaType.STRING, description = "Comment text in Markdown.") String text) {
        Map<String, Object> comment = Map.of("parentCommentId", 0, "content", text, "commentType", 1);
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/git/repositories/" + seg("repository", repo) + "/pullRequests/" + id + "/threads", pat,
                Map.of("comments", List.of(comment), "status", "active"));
        return Out.of("thread_id", r.get("id"));
    }

    // ---------------------------------------------------------------- pipelines and builds

    @Action(id = "list_pipelines", name = "List pipelines", category = "Pipelines", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the pipelines of a project.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, folder, revision.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pipelines returned.")
    public Map<String, Object> listPipelines(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "200", description = D_MAX) int max) {
        List<Map<String, Object>> recs = pages(ctx, proj(org, project) + "/_apis/pipelines", pat, max);
        return result(HttpJson.table(recs, "id", "name", "folder", "revision"), recs.size());
    }

    @Action(id = "run_pipeline", name = "Run pipeline", category = "Pipelines", capabilities = "network",
            description = "Start a pipeline run on a branch, with optional parameters and variables.")
    @Output(name = "run_id", type = OoaType.NUMBER, description = "Id of the new run; use it with Get pipeline run.")
    @Output(name = "state", type = OoaType.STRING, description = "State right after starting, normally inProgress.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the run.")
    public Map<String, Object> runPipeline(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, example = "12", description = "Pipeline id, from List pipelines.") int pipeline,
            @Input(name = "branch", type = OoaType.STRING, required = false, example = "main", description = "Branch to run on. Empty uses the pipeline's default branch.") String branch,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, description = "Template parameters, e.g. {\"environment\": \"test\"}.") Map<String, Object> params,
            @Input(name = "variables", type = OoaType.DICTIONARY, required = false, description = "Variables settable at queue time, e.g. {\"releaseNote\": \"Hotfix\"}.") Map<String, Object> vars) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (branch != null && !branch.isBlank()) b.put("resources", Map.of("repositories", Map.of("self", Map.of("refName", ref(branch)))));
        if (params != null && !params.isEmpty()) b.put("templateParameters", params);
        if (vars != null && !vars.isEmpty()) {
            Map<String, Object> v = new LinkedHashMap<>();
            vars.forEach((k, val) -> v.put(k, Map.of("value", Values.text(val))));
            b.put("variables", v);
        }
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/pipelines/" + pipeline + "/runs", pat, b);
        return Out.of("run_id", r.get("id"), "state", r.get("state"), "url", HttpJson.path(r, "_links.web.href"));
    }

    @Action(id = "get_pipeline_run", name = "Get pipeline run", category = "Pipelines", idempotent = true, capabilities = "network",
            description = "Read the state and result of a pipeline run.")
    @Output(name = "state", type = OoaType.STRING, description = "inProgress, canceling or completed.")
    @Output(name = "result", type = OoaType.STRING, description = "Once completed: succeeded, failed or canceled.")
    @Output(name = "run", type = OoaType.DICTIONARY, description = "id, name, state, result, created, finished, url.")
    public Map<String, Object> getRun(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, description = "Pipeline id.") int pipeline,
            @Input(name = "run_id", type = OoaType.NUMBER, description = "Run id, from Run pipeline.") int run) {
        Map<String, Object> r = call(ctx, "GET", proj(org, project) + "/_apis/pipelines/" + pipeline + "/runs/" + run, pat, null);
        return Out.of("state", r.get("state"), "result", r.get("result"), "run", Out.of("id", r.get("id"), "name", r.get("name"), "state", r.get("state"),
                "result", r.get("result"), "created", r.get("createdDate"), "finished", r.get("finishedDate"), "url", HttpJson.path(r, "_links.web.href")));
    }

    @Condition(id = "pipeline_run_finished", name = "Pipeline run finished", description = "True when the pipeline run has completed, whatever its result.")
    public boolean runFinished(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, description = "Pipeline id.") int pipeline,
            @Input(name = "run_id", type = OoaType.NUMBER, description = "Run id.") int run) {
        return "completed".equals(getRun(ctx, org, pat, project, pipeline, run).get("state"));
    }

    @Action(id = "queue_build", name = "Queue build", category = "Builds", capabilities = "network",
            description = "Queue a build of a build definition (classic or YAML) on a branch.")
    @Output(name = "build_id", type = OoaType.NUMBER, description = "Id of the queued build.")
    @Output(name = "build_number", type = OoaType.STRING, description = "Build number, e.g. 20261020.3.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the build.")
    public Map<String, Object> queueBuild(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "definition_id", type = OoaType.NUMBER, example = "7", description = "Build definition id.") int definition,
            @Input(name = "branch", type = OoaType.STRING, required = false, example = "main", description = "Branch to build. Empty uses the definition's default.") String branch,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, description = "Queue-time variables, e.g. {\"Configuration\": \"Release\"}.") Map<String, Object> params) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("definition", Map.of("id", definition));
        if (branch != null && !branch.isBlank()) b.put("sourceBranch", ref(branch));
        if (params != null && !params.isEmpty()) b.put("parameters", com.oneofficeautomation.sdk.JsonLite.write(Values.toJsonable(params)));
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/build/builds", pat, b);
        return Out.of("build_id", r.get("id"), "build_number", r.get("buildNumber"), "url", HttpJson.path(r, "_links.web.href"));
    }

    @Action(id = "get_build", name = "Get build", category = "Builds", idempotent = true, capabilities = "network", description = "Read the status and result of a build.")
    @Output(name = "status", type = OoaType.STRING, description = "notStarted, inProgress, cancelling or completed.")
    @Output(name = "result", type = OoaType.STRING, description = "Once completed: succeeded, partiallySucceeded, failed or canceled.")
    @Output(name = "build", type = OoaType.DICTIONARY, description = "id, build_number, status, result, definition, branch, requested_for, queued, started, finished, url.")
    public Map<String, Object> getBuild(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "build_id", type = OoaType.NUMBER, description = "Build id.") int id) {
        Map<String, Object> r = call(ctx, "GET", proj(org, project) + "/_apis/build/builds/" + id, pat, null);
        return Out.of("status", r.get("status"), "result", r.get("result"), "build", Out.of("id", r.get("id"), "build_number", r.get("buildNumber"),
                "status", r.get("status"), "result", r.get("result"), "definition", HttpJson.path(r, "definition.name"), "branch", r.get("sourceBranch"),
                "requested_for", who(r.get("requestedFor")), "queued", r.get("queueTime"), "started", r.get("startTime"), "finished", r.get("finishTime"),
                "url", HttpJson.path(r, "_links.web.href")));
    }

    @Action(id = "list_builds", name = "List builds", category = "Builds", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List recent builds, newest first, optionally for one definition, branch, status or result.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, build_number, status, result, definition, branch, queued, finished.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of builds returned.")
    public Map<String, Object> listBuilds(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "definition_id", type = OoaType.NUMBER, required = false, description = "Only builds of this definition.") Integer definition,
            @Input(name = "branch", type = OoaType.STRING, required = false, description = "Only builds of this branch.") String branch,
            @Input(name = "status", type = OoaType.STRING, required = false, example = "completed", description = "Only builds with this status, e.g. inProgress or completed.") String status,
            @Input(name = "result", type = OoaType.STRING, required = false, example = "failed", description = "Only builds with this result, e.g. succeeded or failed.") String result,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "50", description = D_MAX) int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("definitions", definition);
        q.put("branchName", branch == null || branch.isBlank() ? null : ref(branch));
        q.put("statusFilter", status);
        q.put("resultFilter", result);
        q.put("queryOrder", "queueTimeDescending");
        List<Map<String, Object>> recs = pages(ctx, proj(org, project) + "/_apis/build/builds?" + HttpJson.query(q), pat, max);
        return result(HttpJson.table(recs, "id", "build_number=buildNumber", "status", "result", "definition=definition.name", "branch=sourceBranch",
                "queued=queueTime", "finished=finishTime"), recs.size());
    }

    // ---------------------------------------------------------------- boards

    @Action(id = "list_iterations", name = "List sprints (iterations)", category = "Boards", idempotent = true, capabilities = "network",
            description = "List a team's sprints, or only the current one.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, path, start_date, finish_date, time_frame.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of sprints.")
    public Map<String, Object> listIterations(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "team", type = OoaType.STRING, required = false, example = "Payments Team", description = "Team name. Empty uses the project's default team.") String team,
            @Input(name = "current_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return only the sprint in progress today.") boolean current) {
        String base = proj(org, project) + (team == null || team.isBlank() ? "" : "/" + seg("team", team));
        List<Map<String, Object>> recs = HttpJson.maps(call(ctx, "GET", base + "/_apis/work/teamsettings/iterations" + (current ? "?$timeframe=current" : ""), pat, null).get("value"));
        return result(HttpJson.table(recs, "id", "name", "path", "start_date=attributes.startDate", "finish_date=attributes.finishDate", "time_frame=attributes.timeFrame"), recs.size());
    }

    @Action(id = "list_iteration_work_items", name = "List sprint work items", category = "Boards", idempotent = true, capabilities = "network",
            description = "Return the ids of the work items planned in a sprint; read details with Get work item or Query work items.")
    @Output(name = "ids", type = OoaType.LIST, description = "Work item ids in the sprint.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of work items.")
    public Map<String, Object> iterationWorkItems(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "team", type = OoaType.STRING, required = false, description = "Team name. Empty uses the project's default team.") String team,
            @Input(name = "iteration_id", type = OoaType.STRING, description = "Sprint id (a GUID) from List sprints.") String iteration) {
        String base = proj(org, project) + (team == null || team.isBlank() ? "" : "/" + seg("team", team));
        Map<String, Object> r = call(ctx, "GET", base + "/_apis/work/teamsettings/iterations/" + seg("iteration", iteration) + "/workitems", pat, null);
        LinkedHashSet<Object> ids = new LinkedHashSet<>();
        for (Map<String, Object> rel : HttpJson.maps(r.get("workItemRelations"))) {
            Object t = HttpJson.path(rel, "target.id");
            if (t != null) ids.add(t);
        }
        return Out.of("ids", new ArrayList<>(ids), "count", BigDecimal.valueOf(ids.size()));
    }
}
