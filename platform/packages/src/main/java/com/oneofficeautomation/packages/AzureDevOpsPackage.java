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
            description = "List the projects in the organization or collection.",
            panel = "organization_url, personal_access_token | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, state, visibility, description, last_update.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of projects returned.")
    public Map<String, Object> listProjects(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "200", description = D_MAX) int max) {
        List<Map<String, Object>> recs = pages(ctx, org(org) + "/_apis/projects", pat, max);
        return result(HttpJson.table(recs, "id", "name", "state", "visibility", "description", "last_update=lastUpdateTime"), recs.size());
    }

    @Action(id = "get_project", name = "Get project", category = "Projects", idempotent = true, capabilities = "network", description = "Read a project's details.",
            panel = "organization_url, personal_access_token, project")
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
            description = "Create a work item such as a Bug, Task or User Story.",
            panel = "organization_url, personal_access_token, project, type, title, description, assigned_to | area_path, iteration_path, tags, parent_id, fields")
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

    @Action(id = "get_work_item", name = "Get work item", category = "Work items", idempotent = true, capabilities = "network", description = "Read a work item by id.",
            panel = "organization_url, personal_access_token, id")
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
            description = "Change fields of a work item, such as its state or assignee. Only the inputs you fill in are changed.",
            panel = "organization_url, personal_access_token, id, title, iteration_path, state | assigned_to, description, tags, fields")
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
            description = "Move a work item to the Recycle Bin, or remove it for good.",
            panel = "organization_url, personal_access_token, id | permanently")
    public Map<String, Object> deleteWorkItem(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "id", type = OoaType.NUMBER, description = "Work item id.") int id,
            @Input(name = "permanently", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove for good instead of moving to the Recycle Bin. This cannot be undone.") boolean destroy) {
        call(ctx, "DELETE", org(org) + "/_apis/wit/workitems/" + id + "?destroy=" + destroy, pat, null);
        return Map.of();
    }

    @Action(id = "query_work_items", name = "Query work items (WIQL)", category = "Work items", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Find work items with a WIQL query and return their main fields as a table.",
            panel = "organization_url, personal_access_token, project, wiql | extra_fields, max_results")
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
            description = "Add a comment to a work item's discussion.",
            panel = "organization_url, personal_access_token, project, id, text")
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
            description = "Read the comments in a work item's discussion, newest first.",
            panel = "organization_url, personal_access_token, project, id | max_results")
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
            description = "Upload a local file and attach it to a work item.",
            panel = "organization_url, personal_access_token, project, id, path, comment")
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
            description = "List the Git repositories of a project.",
            panel = "organization_url, personal_access_token, project")
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
            description = "List pull requests of a repository, filtered by status and target branch.",
            panel = "organization_url, personal_access_token, project, repository, status, target_branch | max_results")
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

    @Action(id = "get_pull_request", name = "Get pull request", category = "Repos", idempotent = true, capabilities = "network", description = "Read a pull request by id.",
            panel = "organization_url, personal_access_token, project, repository, pull_request_id")
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
            description = "Propose merging one branch into another, optionally linking work items.",
            panel = "organization_url, personal_access_token, project, repository, source_branch, target_branch, title, description | draft, work_item_ids")
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
            description = "Merge an active pull request into its target branch.",
            panel = "organization_url, personal_access_token, project, repository, pull_request_id, merge_strategy, delete_source_branch | merge_commit_message")
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
            description = "Start a new comment thread on a pull request.",
            panel = "organization_url, personal_access_token, project, repository, pull_request_id, text")
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
            description = "List the pipelines of a project.",
            panel = "organization_url, personal_access_token, project, max_results")
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

    @Action(id = "list_pipeline_runs", name = "List pipeline runs", category = "Pipelines", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the recent runs of one pipeline, newest first, optionally only those in a given state or with a given result.",
            panel = "organization_url, personal_access_token, project, pipeline_id, state, result | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, state, result, created, finished, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of runs returned.")
    public Map<String, Object> listPipelineRuns(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, example = "12", description = "Pipeline id, from List pipelines.") int pipeline,
            @Input(name = "state", type = OoaType.STRING, required = false, enumValues = {"inProgress", "canceling", "completed"},
                    description = "Only runs in this state.") String state,
            @Input(name = "result", type = OoaType.STRING, required = false, enumValues = {"succeeded", "failed", "canceled"},
                    description = "Only completed runs with this result.") String result,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, description = D_MAX) int max) {
        // the service returns the latest runs of the pipeline in one page, newest first; the filters apply here
        HttpJson.Response r = send(req("GET", withVersion(proj(org, project) + "/_apis/pipelines/" + pipeline + "/runs", V), pat).timeout(120), ctx);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> run : HttpJson.maps(r.map().get("value"))) {
            if (state != null && !state.isBlank() && !state.equalsIgnoreCase(String.valueOf(run.get("state")))) continue;
            if (result != null && !result.isBlank() && !result.equalsIgnoreCase(String.valueOf(run.get("result")))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.get("id")); row.put("name", run.get("name")); row.put("state", run.get("state")); row.put("result", run.get("result"));
            row.put("created", run.get("createdDate")); row.put("finished", run.get("finishedDate")); row.put("url", HttpJson.path(run, "_links.web.href"));
            rows.add(row);
            if (rows.size() >= max) break;
        }
        return result(HttpJson.table(rows, "id", "name", "state", "result", "created", "finished", "url"), rows.size());
    }

    // ---------------------------------------------------------------- test plans, suites, cases, points and runs

    private static final String D_PLAN = "Test plan id, from List test plans.";
    private static final String D_SUITE = "Test suite id; a plan's top suite has the plan's root suite id (see Get test plan).";

    static Map<String, Object> plan(Map<String, Object> p) {
        return Out.of("id", p.get("id"), "name", p.get("name"), "state", p.get("state"), "area_path", p.get("areaPath"), "iteration", p.get("iteration"),
                "root_suite_id", HttpJson.path(p, "rootSuite.id"), "owner", who(p.get("owner")), "start_date", p.get("startDate"), "end_date", p.get("endDate"));
    }

    @Action(id = "list_test_plans", name = "List test plans", category = "Test plans", idempotent = true, capabilities = "network", description = "List the test plans of a project.",
            panel = "organization_url, personal_access_token, project, active_only")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, state, area_path, iteration, root_suite_id.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of plans.")
    public Map<String, Object> listTestPlans(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "active_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave out inactive plans.") boolean active) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : pages(ctx, proj(org, project) + "/_apis/testplan/plans" + (active ? "?filterActivePlans=true" : ""), pat, 5000)) rows.add(plan(p));
        return Out.of("table", HttpJson.table(rows, "id", "name", "state", "area_path", "iteration", "root_suite_id"), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_test_plan", name = "Get test plan details", category = "Test plans", idempotent = true, capabilities = "network", description = "Read one test plan.",
            panel = "organization_url, personal_access_token, project, plan_id")
    @Output(name = "plan", type = OoaType.DICTIONARY, description = "Keys: id, name, state, area_path, iteration, root_suite_id, owner, start_date, end_date.")
    public Map<String, Object> getTestPlan(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId) {
        return Out.of("plan", plan(call(ctx, "GET", proj(org, project) + "/_apis/testplan/plans/" + planId, pat, null)));
    }

    /** A date input as the ISO 8601 UTC text the test plan service expects; null when empty. */
    static String isoDate(String what, Object v) {
        if (v == null || Values.text(v).isBlank()) return null;
        try { return Values.toDateTime(v).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")); }
        catch (RuntimeException e) {
            throw new ActionException(P + ".BAD_DATE", "The " + what + " '" + Values.text(v) + "' is not a date", "Use a date such as 2026-11-01 or 2026-11-01T09:00:00Z.");
        }
    }

    static void putDates(Map<String, Object> body, Object start, Object end) {
        String s = isoDate("start_date", start), e = isoDate("end_date", end);
        if (s != null && e != null && e.compareTo(s) < 0)
            throw new ActionException(P + ".BAD_DATES", "The end date " + e + " is before the start date " + s, "Choose an end date on or after the start date.");
        if (s != null) body.put("startDate", s);
        if (e != null) body.put("endDate", e);
    }

    /** Earlier signature, kept for callers written before start_date and end_date. */
    public Map<String, Object> createTestPlan(ActionContext ctx, String org, Object pat, String project, String name, String area, String iteration, String desc) {
        return createTestPlan(ctx, org, pat, project, name, area, iteration, desc, null, null);
    }

    @Action(id = "create_test_plan", name = "Create test plan", category = "Test plans", capabilities = "network", description = "Create a test plan; its root suite is created with it.",
            panel = "organization_url, personal_access_token, project, name, plan_description, area_path, iteration, [Set the plan's dates: start_date, end_date]")
    @Output(name = "plan_id", type = OoaType.NUMBER, description = "Id of the new plan.")
    @Output(name = "root_suite_id", type = OoaType.NUMBER, description = "Id of the plan's root suite, for Create test suite.")
    public Map<String, Object> createTestPlan(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "name", type = OoaType.STRING, description = "Plan name, e.g. Release 3.2 regression.") String name,
            @Input(name = "area_path", type = OoaType.STRING, required = false, description = "Area path; the project's root area when empty.") String area,
            @Input(name = "iteration", type = OoaType.STRING, required = false, description = "Iteration path, e.g. Finance Portal\\Sprint 12.") String iteration,
            @Input(name = "plan_description", type = OoaType.STRING, required = false, description = "Description.") String desc,
            @Input(name = "start_date", type = OoaType.DATETIME, required = false, example = "2026-11-01", description = "First day of the testing period (UTC); the service picks today when empty.") Object start,
            @Input(name = "end_date", type = OoaType.DATETIME, required = false, example = "2026-11-14", description = "Last day of the testing period (UTC); on or after the start date.") Object end) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("name", name));
        if (area != null && !area.isBlank()) b.put("areaPath", area);
        if (iteration != null && !iteration.isBlank()) b.put("iteration", iteration);
        if (desc != null && !desc.isBlank()) b.put("description", desc);
        putDates(b, start, end);
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/testplan/plans", pat, b);
        return Out.of("plan_id", r.get("id"), "root_suite_id", HttpJson.path(r, "rootSuite.id"));
    }

    /** Earlier signature, kept for callers written before start_date and end_date. */
    public Map<String, Object> updateTestPlan(ActionContext ctx, String org, Object pat, String project, int planId, String name, String state, String desc, String area, String iteration) {
        return updateTestPlan(ctx, org, pat, project, planId, name, state, desc, area, iteration, null, null);
    }

    @Action(id = "update_test_plan", name = "Edit test plan", category = "Test plans", capabilities = "network", description = "Rename a test plan, change its state, description, area, iteration or dates; empty inputs are kept.",
            panel = "organization_url, personal_access_token, project, plan_id, name, plan_description, area_path, iteration, state, [Change the plan's dates: start_date, end_date]")
    public Map<String, Object> updateTestPlan(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String name,
            @Input(name = "state", type = OoaType.STRING, required = false, enumValues = {"Active", "Inactive"}, description = "Plan state.") String state,
            @Input(name = "plan_description", type = OoaType.STRING, required = false, description = "New description.") String desc,
            @Input(name = "area_path", type = OoaType.STRING, required = false, description = "New area path.") String area,
            @Input(name = "iteration", type = OoaType.STRING, required = false, description = "New iteration path.") String iteration,
            @Input(name = "start_date", type = OoaType.DATETIME, required = false, description = "New first day of the testing period (UTC).") Object start,
            @Input(name = "end_date", type = OoaType.DATETIME, required = false, description = "New last day of the testing period (UTC).") Object end) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) b.put("name", name);
        if (state != null && !state.isBlank()) b.put("state", state);
        if (desc != null) b.put("description", desc);
        if (area != null && !area.isBlank()) b.put("areaPath", area);
        if (iteration != null && !iteration.isBlank()) b.put("iteration", iteration);
        putDates(b, start, end);
        if (b.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "Nothing to change", "Fill in at least one field.");
        call(ctx, "PATCH", proj(org, project) + "/_apis/testplan/plans/" + planId, pat, b);
        return Map.of();
    }

    @Action(id = "delete_test_plan", name = "Delete test plan", category = "Test plans", capabilities = "network", description = "Delete a test plan with its suites (test cases stay as work items). Needs confirm.",
            panel = "organization_url, personal_access_token, project, plan_id, confirm")
    public Map<String, Object> deleteTestPlan(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true: a safeguard against deleting by mistake.") boolean confirm) {
        if (!confirm) throw new ActionException(P + ".CONFIRM_REQUIRED", "The test plan was not deleted because confirm is false", "Set confirm to true.");
        call(ctx, "DELETE", proj(org, project) + "/_apis/testplan/plans/" + planId, pat, null);
        return Map.of();
    }

    @Action(id = "create_test_suite", name = "Create test suite", category = "Test plans", capabilities = "network",
            description = "Create a static, requirement-based or query-based suite inside a plan's suite.",
            panel = "organization_url, personal_access_token, project, plan_id, suite_type, requirement_id, query, parent_suite_id, name")
    @Output(name = "suite_id", type = OoaType.NUMBER, description = "Id of the new suite.")
    public Map<String, Object> createTestSuite(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "parent_suite_id", type = OoaType.NUMBER, min = 1, description = "Suite to create it in, usually the plan's root suite.") int parent,
            @Input(name = "name", type = OoaType.STRING, description = "Suite name.") String name,
            @Input(name = "suite_type", type = OoaType.STRING, required = false, defaultValue = "\"static\"", enumValues = {"static", "requirement", "query"},
                    description = "static: test cases added by hand; requirement: the tests of one work item; query: tests matching a WIQL query.") String type,
            @Input(name = "requirement_id", type = OoaType.NUMBER, required = false, visibleWhen = "suite_type=requirement", min = 1, description = "Work item whose tests the suite holds.") Integer requirement,
            @Input(name = "query", type = OoaType.STRING, required = false, visibleWhen = "suite_type=query", description = "WIQL query selecting the test cases.") String query) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("suiteType", switch (type == null ? "static" : type) { case "requirement" -> "requirementTestSuite"; case "query" -> "dynamicTestSuite"; default -> "staticTestSuite"; });
        b.put("name", name);
        b.put("parentSuite", Map.of("id", parent));
        if ("requirement".equals(type)) {
            if (requirement == null) throw new ActionException(P + ".MISSING_REQUIREMENT", "A requirement suite needs requirement_id", "Fill in the work item id.");
            b.put("requirementId", requirement);
        }
        if ("query".equals(type)) {
            if (query == null || query.isBlank()) throw new ActionException(P + ".MISSING_QUERY", "A query suite needs a query", "Fill in a WIQL query.");
            b.put("queryString", query);
        }
        return Out.of("suite_id", call(ctx, "POST", proj(org, project) + "/_apis/testplan/Plans/" + planId + "/suites", pat, b).get("id"));
    }

    @Action(id = "update_test_suite", name = "Edit test suite", category = "Test plans", capabilities = "network", description = "Rename a suite or move it under another suite of the plan.",
            panel = "organization_url, personal_access_token, project, plan_id, suite_id, parent_suite_id, name")
    public Map<String, Object> updateTestSuite(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, min = 1, description = D_SUITE) int suiteId,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String name,
            @Input(name = "parent_suite_id", type = OoaType.NUMBER, required = false, min = 1, description = "Move under this suite.") Integer parent) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) b.put("name", name);
        if (parent != null) b.put("parentSuite", Map.of("id", parent));
        if (b.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "Nothing to change", "Fill in name or parent_suite_id.");
        call(ctx, "PATCH", proj(org, project) + "/_apis/testplan/Plans/" + planId + "/suites/" + suiteId, pat, b);
        return Map.of();
    }

    @Action(id = "delete_test_suite", name = "Delete test suite", category = "Test plans", capabilities = "network", description = "Delete a suite and its child suites (test cases stay as work items).",
            panel = "organization_url, personal_access_token, project, plan_id, suite_id")
    public Map<String, Object> deleteTestSuite(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, min = 1, description = D_SUITE) int suiteId) {
        call(ctx, "DELETE", proj(org, project) + "/_apis/testplan/Plans/" + planId + "/suites/" + suiteId, pat, null);
        return Map.of();
    }

    @Action(id = "add_test_cases_to_suite", name = "Add test cases to suite", category = "Test plans", capabilities = "network", description = "Add existing test case work items to a static suite.",
            panel = "organization_url, personal_access_token, project, plan_id, suite_id, test_case_ids")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of test cases added.")
    public Map<String, Object> addTestCases(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, min = 1, description = D_SUITE) int suiteId,
            @Input(name = "test_case_ids", type = OoaType.LIST, description = "Work item ids of the test cases, e.g. [101, 102].") List<Object> ids) {
        List<Object> body = new ArrayList<>();
        for (int id : testCaseIds(ids)) body.add(Map.of("workItem", Map.of("id", id)));
        call(ctx, "POST", proj(org, project) + "/_apis/testplan/Plans/" + planId + "/Suites/" + suiteId + "/TestCase", pat, body);
        return Out.of("count", BigDecimal.valueOf(body.size()));
    }

    @Action(id = "remove_test_cases_from_suite", name = "Remove test cases from suite", category = "Test plans", capabilities = "network",
            description = "Take test cases out of a suite; the test case work items are kept.",
            panel = "organization_url, personal_access_token, project, plan_id, suite_id, test_case_ids")
    public Map<String, Object> removeTestCases(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, min = 1, description = D_SUITE) int suiteId,
            @Input(name = "test_case_ids", type = OoaType.LIST, description = "Work item ids of the test cases.") List<Object> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : testCaseIds(ids)) sb.append(sb.isEmpty() ? "" : ",").append(id);
        call(ctx, "DELETE", proj(org, project) + "/_apis/testplan/Plans/" + planId + "/Suites/" + suiteId + "/TestCase?testCaseIds=" + sb, pat, null);
        return Map.of();
    }

    private static List<Integer> testCaseIds(List<Object> ids) {
        if (ids == null || ids.isEmpty()) throw new ActionException(P + ".MISSING_TEST_CASES", "No test case ids given", "Give one or more work item ids.");
        List<Integer> out = new ArrayList<>();
        for (Object o : ids) {
            try { out.add(Values.toNumber(o).intValueExact()); }
            catch (RuntimeException e) { throw new ActionException(P + ".BAD_ID", "'" + o + "' is not a work item id", "Use whole numbers such as 101."); }
        }
        return out;
    }

    /** Earlier signature, kept for callers written before area_path, iteration_path, priority and automation_status. */
    public Map<String, Object> createTestCase(ActionContext ctx, String org, Object pat, String project, String title, List<Object> steps,
            Map<String, Object> fields, Integer planId, Integer suiteId) {
        return createTestCase(ctx, org, pat, project, title, steps, fields, planId, suiteId, null, null, null, null);
    }

    @Action(id = "create_test_case", name = "Create test case", category = "Test plans", capabilities = "network",
            description = "Create a Test Case work item with numbered steps (action and expected result), optionally added to a suite.",
            panel = "organization_url, personal_access_token, project, title, steps, area_path, iteration_path, priority | automation_status, fields, plan_id, suite_id")
    @Output(name = "id", type = OoaType.NUMBER, description = "Work item id of the test case.")
    public Map<String, Object> createTestCase(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "title", type = OoaType.STRING, description = "Test case title.") String title,
            @Input(name = "steps", type = OoaType.LIST, required = false,
                    description = "Steps in order: text, or {\"action\": ..., \"expected\": ...}, e.g. [{\"action\": \"Open the invoice\", \"expected\": \"Total shows 120.50\"}].") List<Object> steps,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "Other fields, e.g. {\"priority\": 2, \"area_path\": \"Finance Portal\"}.") Map<String, Object> fields,
            @Input(name = "plan_id", type = OoaType.NUMBER, required = false, min = 1, description = "Add the new test case to this plan's suite (with suite_id).") Integer planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, required = false, min = 1, visibleWhen = "plan_id!=", description = "Suite to add it to.") Integer suiteId,
            @Input(name = "area_path", type = OoaType.STRING, required = false, example = "Finance Portal\\Payments", description = "Area path; the project's root area when empty.") String area,
            @Input(name = "iteration_path", type = OoaType.STRING, required = false, example = "Finance Portal\\Sprint 42", description = "Iteration (sprint) path; the project's root iteration when empty.") String iteration,
            @Input(name = "priority", type = OoaType.NUMBER, required = false, min = 1, max = 4, description = "Priority from 1 (highest) to 4 (lowest).") Integer priority,
            @Input(name = "automation_status", type = OoaType.STRING, required = false, enumValues = {"Not Automated", "Planned", "Automated"},
                    description = "Whether the test is automated: Not Automated, Planned or Automated.") String automation) {
        if (priority != null && (priority < 1 || priority > 4))
            throw new ActionException(P + ".BAD_PRIORITY", "Priority " + priority + " is out of range", "Use a whole number from 1 (highest) to 4 (lowest).");
        if (automation != null && !automation.isBlank() && !Set.of("Not Automated", "Planned", "Automated").contains(automation.strip()))
            throw new ActionException(P + ".BAD_AUTOMATION_STATUS", "Unknown automation status '" + automation + "'", "Use Not Automated, Planned or Automated.");
        // named inputs win over the same field given in the fields dictionary
        Map<String, Object> byRef = new LinkedHashMap<>();
        if (fields != null) fields.forEach((k, v) -> byRef.put(FRIENDLY.getOrDefault(k, k), v));
        if (area != null && !area.isBlank()) byRef.put("System.AreaPath", area.strip());
        if (iteration != null && !iteration.isBlank()) byRef.put("System.IterationPath", iteration.strip());
        if (priority != null) byRef.put("Microsoft.VSTS.Common.Priority", priority);
        if (automation != null && !automation.isBlank()) byRef.put("Microsoft.VSTS.TCM.AutomationStatus", automation.strip());
        List<Object> ops = new ArrayList<>();
        ops.add(Map.of("op", "add", "path", "/fields/System.Title", "value", title));
        if (steps != null && !steps.isEmpty()) ops.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.TCM.Steps", "value", stepsXml(steps)));
        byRef.forEach((k, v) -> ops.add(Map.of("op", "add", "path", "/fields/" + k, "value", v)));
        HttpJson.Req r = req("POST", withVersion(proj(org, project) + "/_apis/wit/workitems/$Test%20Case", V), pat).bytes(JsonLite.write(ops).getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json-patch+json");
        Object id = send(r, ctx).map().get("id");
        if (planId != null && suiteId != null && id != null)
            addTestCases(ctx, org, pat, project, planId, suiteId, List.of(id));
        return Out.of("id", id);
    }

    /** The steps field of a Test Case: XML with an action and an expected result per step (both stored as escaped HTML). */
    static String stepsXml(List<Object> steps) {
        StringBuilder sb = new StringBuilder("<steps id=\"0\" last=\"" + (steps.size() + 1) + "\">");
        int i = 2;
        for (Object s : steps) {
            String action, expected;
            if (s instanceof Map<?, ?> m) { action = Values.text(m.get("action")); expected = m.get("expected") == null ? "" : Values.text(m.get("expected")); }
            else { action = Values.text(s); expected = ""; }
            sb.append("<step id=\"").append(i++).append("\" type=\"").append(expected.isEmpty() ? "ActionStep" : "ValidateStep").append("\">")
              .append("<parameterizedString isformatted=\"true\">").append(xml("<P>" + html(action) + "</P>")).append("</parameterizedString>")
              .append("<parameterizedString isformatted=\"true\">").append(xml(expected.isEmpty() ? "" : "<P>" + html(expected) + "</P>")).append("</parameterizedString>")
              .append("<description/></step>");
        }
        return sb.append("</steps>").toString();
    }

    private static String html(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    @Action(id = "list_test_points", name = "List test points", category = "Test plans", idempotent = true, capabilities = "network",
            description = "List the test points of a suite (one per test case and configuration) with their latest outcome.",
            panel = "organization_url, personal_access_token, project, plan_id, suite_id")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, test_case_id, test_case, configuration, tester, outcome, state.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of points.")
    public Map<String, Object> listTestPoints(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, min = 1, description = D_SUITE) int suiteId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : pages(ctx, proj(org, project) + "/_apis/testplan/Plans/" + planId + "/Suites/" + suiteId + "/TestPoint", pat, 10000))
            rows.add(Out.of("id", p.get("id"), "test_case_id", HttpJson.path(p, "testCaseReference.id"), "test_case", HttpJson.path(p, "testCaseReference.name"),
                    "configuration", HttpJson.path(p, "configuration.name"), "tester", who(p.get("tester")),
                    "outcome", HttpJson.path(p, "results.outcome"), "state", HttpJson.path(p, "results.state")));
        return Out.of("table", HttpJson.table(rows, "id", "test_case_id", "test_case", "configuration", "tester", "outcome", "state"), "count", BigDecimal.valueOf(rows.size()));
    }

    private static final java.util.regex.Pattern GUID = java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$");

    /** Address of the identity service: a separate host for the cloud service, the collection itself for Azure DevOps Server. */
    static String identityBase(String org) {
        String o = org(org);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https://dev\\.azure\\.com/([^/]+)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(o);
        if (m.matches()) return "https://vssps.dev.azure.com/" + m.group(1);
        m = java.util.regex.Pattern.compile("^https://([^./]+)\\.visualstudio\\.com(/.*)?$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(o);
        if (m.matches()) return "https://" + m.group(1) + ".vssps.visualstudio.com";
        return o;
    }

    /** Turns an identity id, sign-in email or display name into the reference the test point service stores. */
    static Map<String, Object> identityRef(ActionContext ctx, String org, Object pat, String person) {
        String p = person.strip();
        if (GUID.matcher(p).matches()) return Map.of("id", p);
        String url = identityBase(org) + "/_apis/identities?searchFilter=General&filterValue=" + HttpJson.enc(p) + "&queryMembership=None";
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> i : HttpJson.maps(call(ctx, "GET", url, pat, null).get("value")))
            if (!Boolean.TRUE.equals(i.get("isContainer")) && i.get("id") != null) found.add(i);
        if (found.size() > 1) {
            List<Map<String, Object>> exact = new ArrayList<>();
            for (Map<String, Object> i : found)
                for (Object v : new Object[]{i.get("providerDisplayName"), HttpJson.path(i, "properties.Mail.$value"), HttpJson.path(i, "properties.Account.$value")})
                    if (v != null && p.equalsIgnoreCase(Values.text(v))) { exact.add(i); break; }
            if (exact.size() == 1) found = exact;
        }
        if (found.isEmpty()) throw new ActionException(P + ".TESTER_NOT_FOUND", "No user matches the tester '" + p + "'", "Use the person's sign-in email as shown in Azure DevOps.");
        if (found.size() > 1) throw new ActionException(P + ".AMBIGUOUS_TESTER", found.size() + " users match the tester '" + p + "'", "Use the person's sign-in email instead of the display name.");
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", Values.text(found.get(0).get("id")));
        if (found.get(0).get("providerDisplayName") != null) ref.put("displayName", Values.text(found.get(0).get("providerDisplayName")));
        return ref;
    }

    /** Earlier signature, kept for callers written before tester. */
    public Map<String, Object> updateTestPoint(ActionContext ctx, String org, Object pat, String project, int planId, int suiteId, List<Object> points, String outcome) {
        return updateTestPoint(ctx, org, pat, project, planId, suiteId, points, outcome, null);
    }

    @Action(id = "update_test_point", name = "Update test point", category = "Test plans", capabilities = "network",
            description = "Set the outcome of test points (passed, failed, blocked, not applicable), reset them to active, or assign them to a tester, e.g. after a manual check done by an agent.",
            panel = "organization_url, personal_access_token, project, plan_id, suite_id, point_ids, outcome, tester")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of points updated.")
    public Map<String, Object> updateTestPoint(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "suite_id", type = OoaType.NUMBER, min = 1, description = D_SUITE) int suiteId,
            @Input(name = "point_ids", type = OoaType.LIST, description = "Test point ids, from List test points.") List<Object> points,
            @Input(name = "outcome", type = OoaType.STRING, required = false, enumValues = {"passed", "failed", "blocked", "notApplicable", "active"},
                    description = "New outcome; active resets the point. Leave empty to only change the tester.") String outcome,
            @Input(name = "tester", type = OoaType.STRING, required = false, example = "ana@contoso.com",
                    description = "Person to assign the points to: sign-in email, display name or identity id. Needs the token to read identities.") String tester) {
        boolean hasOutcome = outcome != null && !outcome.isBlank(), hasTester = tester != null && !tester.isBlank();
        if (!hasOutcome && !hasTester) throw new ActionException(P + ".NOTHING_TO_UPDATE", "Neither an outcome nor a tester was given", "Fill in outcome, tester, or both.");
        if (hasOutcome && !Set.of("passed", "failed", "blocked", "notApplicable", "active").contains(outcome))
            throw new ActionException(P + ".BAD_OUTCOME", "Unknown outcome '" + outcome + "'", "Use passed, failed, blocked, notApplicable or active.");
        List<Integer> ids = testCaseIds(points);
        Map<String, Object> who = hasTester ? identityRef(ctx, org, pat, tester) : null;
        List<Object> body = new ArrayList<>();
        for (int id : ids) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", id);
            if (hasOutcome) { if ("active".equals(outcome)) u.put("resetToActive", true); else u.put("results", Map.of("outcome", outcome)); }
            if (who != null) u.put("tester", who);
            body.add(u);
        }
        call(ctx, "PATCH", proj(org, project) + "/_apis/testplan/Plans/" + planId + "/Suites/" + suiteId + "/TestPoint", pat, body);
        return Out.of("count", BigDecimal.valueOf(body.size()));
    }

    /** Earlier signature, kept for callers written before automated and build_id. */
    public Map<String, Object> createTestRun(ActionContext ctx, String org, Object pat, String project, int planId, String name, List<Object> points) {
        return createTestRun(ctx, org, pat, project, planId, name, points, false, null);
    }

    @Action(id = "create_test_run", name = "Create test run", category = "Test plans", capabilities = "network",
            description = "Start a test run for test points of a plan, so their results can be recorded; manual by default, or automated and linked to a build.",
            panel = "organization_url, personal_access_token, project, plan_id, name, point_ids | automated, build_id")
    @Output(name = "run_id", type = OoaType.NUMBER, description = "Id of the new run.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the run.")
    public Map<String, Object> createTestRun(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "plan_id", type = OoaType.NUMBER, min = 1, description = D_PLAN) int planId,
            @Input(name = "name", type = OoaType.STRING, description = "Run name.") String name,
            @Input(name = "point_ids", type = OoaType.LIST, required = false, description = "Test points to include; the run starts empty when none are given.") List<Object> points,
            @Input(name = "automated", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark the run as automated instead of manual.") boolean automated,
            @Input(name = "build_id", type = OoaType.NUMBER, required = false, min = 1, description = "Id of the build the run tests, from Queue build or List builds.") Integer buildId) {
        if (buildId != null && buildId < 1) throw new ActionException(P + ".BAD_ID", "'" + buildId + "' is not a build id", "Use the build's id, a whole number such as 77.");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("plan", Map.of("id", String.valueOf(planId)));
        b.put("automated", automated);
        if (buildId != null) b.put("build", Map.of("id", String.valueOf(buildId)));
        if (points != null && !points.isEmpty()) b.put("pointIds", testCaseIds(points));
        Map<String, Object> r = call(ctx, "POST", proj(org, project) + "/_apis/test/runs", pat, b);
        return Out.of("run_id", r.get("id"), "url", r.get("webAccessUrl"));
    }

    @Action(id = "get_test_run", name = "Get test run details", category = "Test plans", idempotent = true, capabilities = "network",
            description = "Read a test run: state, counts of passed, failed and incomplete tests, dates.",
            panel = "organization_url, personal_access_token, project, run_id")
    @Output(name = "run", type = OoaType.DICTIONARY, description = "Keys: id, name, state, total_tests, passed_tests, failed_tests, incomplete_tests, started, completed, url.")
    public Map<String, Object> getTestRun(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "run_id", type = OoaType.NUMBER, min = 1, description = "Test run id.") int runId) {
        Map<String, Object> r = call(ctx, "GET", proj(org, project) + "/_apis/test/runs/" + runId, pat, null);
        return Out.of("run", Out.of("id", r.get("id"), "name", r.get("name"), "state", r.get("state"), "total_tests", r.get("totalTests"),
                "passed_tests", r.get("passedTests"), "failed_tests", r.get("unanalyzedTests"), "incomplete_tests", r.get("incompleteTests"),
                "started", r.get("startedDate"), "completed", r.get("completedDate"), "url", r.get("webAccessUrl")));
    }

    // ---------------------------------------------------------------- pipeline definitions

    @Action(id = "get_pipeline", name = "Get pipeline details", category = "Pipelines", idempotent = true, capabilities = "network",
            description = "Read a pipeline: name, folder, YAML file and repository.",
            panel = "organization_url, personal_access_token, project, pipeline_id")
    @Output(name = "pipeline", type = OoaType.DICTIONARY, description = "Keys: id, name, folder, yaml_path, repository, url.")
    public Map<String, Object> getPipeline(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, min = 1, description = "Pipeline id, from List pipelines.") int pipeline) {
        Map<String, Object> r = call(ctx, "GET", proj(org, project) + "/_apis/pipelines/" + pipeline, pat, null);
        return Out.of("pipeline", Out.of("id", r.get("id"), "name", r.get("name"), "folder", r.get("folder"), "yaml_path", HttpJson.path(r, "configuration.path"),
                "repository", HttpJson.path(r, "configuration.repository.name") != null ? HttpJson.path(r, "configuration.repository.name") : HttpJson.path(r, "configuration.repository.id"),
                "url", HttpJson.path(r, "_links.web.href")));
    }

    @Action(id = "create_pipeline", name = "Create pipeline", category = "Pipelines", capabilities = "network",
            description = "Create a YAML pipeline from a file in an Azure Repos Git repository.",
            panel = "organization_url, personal_access_token, project, name, repository_id, yaml_path | folder")
    @Output(name = "pipeline_id", type = OoaType.NUMBER, description = "Id of the new pipeline.")
    public Map<String, Object> createPipeline(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "name", type = OoaType.STRING, description = "Pipeline name.") String name,
            @Input(name = "repository_id", type = OoaType.STRING, description = "Repository id (a GUID, from List repositories).") String repo,
            @Input(name = "yaml_path", type = OoaType.STRING, example = "/azure-pipelines.yml", description = "Path of the YAML file in the repository.") String yaml,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"\\\\\"", description = "Folder for the pipeline, e.g. \\Finance.") String folder) {
        Map<String, Object> b = Map.of("name", name, "folder", folder == null || folder.isBlank() ? "\\" : folder,
                "configuration", Map.of("type", "yaml", "path", yaml, "repository", Map.of("id", repo.strip(), "type", "azureReposGit")));
        return Out.of("pipeline_id", call(ctx, "POST", proj(org, project) + "/_apis/pipelines", pat, b).get("id"));
    }

    /** Earlier signature, kept for callers written before yaml_path and repository_id. */
    public Map<String, Object> updatePipeline(ActionContext ctx, String org, Object pat, String project, int pipeline, String name, String folder) {
        return updatePipeline(ctx, org, pat, project, pipeline, name, folder, null, null);
    }

    @Action(id = "update_pipeline", name = "Edit pipeline", category = "Pipelines", capabilities = "network",
            description = "Rename a pipeline, move it to another folder, or point it at a different YAML file or Azure Repos repository (the YAML content itself is changed in the repository).",
            panel = "organization_url, personal_access_token, project, pipeline_id | name, folder, yaml_path, repository_id")
    public Map<String, Object> updatePipeline(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, min = 1, description = "Pipeline id.") int pipeline,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String name,
            @Input(name = "folder", type = OoaType.STRING, required = false, description = "New folder, e.g. \\Finance.") String folder,
            @Input(name = "yaml_path", type = OoaType.STRING, required = false, example = "/pipelines/release.yml", description = "New path of the YAML file in the repository.") String yaml,
            @Input(name = "repository_id", type = OoaType.STRING, required = false, description = "Id (a GUID, from List repositories) of another Azure Repos Git repository that holds the YAML file.") String repo) {
        boolean hasName = name != null && !name.isBlank(), hasFolder = folder != null && !folder.isBlank();
        boolean hasYaml = yaml != null && !yaml.isBlank(), hasRepo = repo != null && !repo.isBlank();
        if (!hasName && !hasFolder && !hasYaml && !hasRepo)
            throw new ActionException(P + ".NOTHING_TO_UPDATE", "Nothing to change", "Fill in name, folder, yaml_path or repository_id.");
        if (hasRepo && !GUID.matcher(repo.strip()).matches())
            throw new ActionException(P + ".BAD_REPOSITORY_ID", "'" + repo + "' is not a repository id", "Use the repository's id (a GUID) from List repositories.");
        String url = proj(org, project) + "/_apis/build/definitions/" + pipeline;
        Map<String, Object> def = new LinkedHashMap<>(call(ctx, "GET", url, pat, null));   // the full definition is sent back with the change
        if (def.isEmpty()) throw new ActionException(P + ".NOT_FOUND", "Pipeline " + pipeline + " was not found", "Check the id with List pipelines.");
        if (hasName) def.put("name", name);
        if (hasFolder) def.put("path", folder);
        if (hasYaml || hasRepo) {
            // a YAML pipeline's definition has process type 2 with the file in yamlFilename
            Map<String, Object> process = def.get("process") instanceof Map<?, ?> m ? new LinkedHashMap<>(castMap(m)) : new LinkedHashMap<>();
            if (!"2".equals(Values.text(process.get("type") == null ? "" : process.get("type")).replaceFirst("\\.0+$", "")))
                throw new ActionException(P + ".NOT_YAML", "Pipeline " + pipeline + " is a classic pipeline, not a YAML one", "Only YAML pipelines can be pointed at another YAML file or repository.");
            if (hasYaml) { process.put("yamlFilename", yaml.strip()); def.put("process", process); }
            if (hasRepo) {
                Map<String, Object> r = def.get("repository") instanceof Map<?, ?> m ? new LinkedHashMap<>(castMap(m)) : new LinkedHashMap<>();
                r.put("id", repo.strip());
                r.put("type", "TfsGit");
                r.remove("name");   // the name and address belonged to the old repository; the service fills them from the id
                r.remove("url");
                def.put("repository", r);
            }
        }
        call(ctx, "PUT", url, pat, def);
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }

    @Action(id = "delete_pipeline", name = "Delete pipeline", category = "Pipelines", capabilities = "network",
            description = "Delete a pipeline and its run history. Needs confirm.",
            panel = "organization_url, personal_access_token, project, pipeline_id, confirm")
    public Map<String, Object> deletePipeline(ActionContext ctx,
            @Input(name = "organization_url", type = OoaType.STRING, pattern = "^https?://", description = D_ORG) String org,
            @Input(name = "personal_access_token", type = OoaType.CREDENTIAL, description = D_PAT) Object pat,
            @Input(name = "project", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "pipeline_id", type = OoaType.NUMBER, min = 1, description = "Pipeline id.") int pipeline,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true: a safeguard against deleting by mistake.") boolean confirm) {
        if (!confirm) throw new ActionException(P + ".CONFIRM_REQUIRED", "The pipeline was not deleted because confirm is false", "Set confirm to true.");
        call(ctx, "DELETE", proj(org, project) + "/_apis/build/definitions/" + pipeline, pat, null);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before stages_to_skip. */
    public Map<String, Object> runPipeline(ActionContext ctx, String org, Object pat, String project, int pipeline, String branch,
            Map<String, Object> params, Map<String, Object> vars) {
        return runPipeline(ctx, org, pat, project, pipeline, branch, params, vars, null);
    }

    @Action(id = "run_pipeline", name = "Run pipeline", category = "Pipelines", capabilities = "network",
            description = "Start a pipeline run on a branch, with optional parameters and variables, optionally skipping some stages.",
            panel = "organization_url, personal_access_token, project, pipeline_id, parameters, variables | branch, stages_to_skip")
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
            @Input(name = "variables", type = OoaType.DICTIONARY, required = false, description = "Variables settable at queue time, e.g. {\"releaseNote\": \"Hotfix\"}.") Map<String, Object> vars,
            @Input(name = "stages_to_skip", type = OoaType.LIST, required = false, description = "Names of YAML stages not to run, as written after 'stage:' in the YAML, e.g. [\"DeployProd\"].") List<Object> skip) {
        List<String> stages = new ArrayList<>();
        if (skip != null) for (Object s : skip) {
            String n = s == null ? "" : Values.text(s).strip();
            if (n.isEmpty()) throw new ActionException(P + ".BAD_STAGE", "A stage name in stages_to_skip is empty", "List the stage names as written in the YAML, e.g. [\"DeployProd\"].");
            if (!stages.contains(n)) stages.add(n);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        if (!stages.isEmpty()) b.put("stagesToSkip", stages);
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
            description = "Read the state and result of a pipeline run.",
            panel = "organization_url, personal_access_token, project, pipeline_id, run_id")
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
            description = "Queue a build of a build definition (classic or YAML) on a branch.",
            panel = "organization_url, personal_access_token, project, definition_id, branch | parameters")
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

    @Action(id = "get_build", name = "Get build", category = "Builds", idempotent = true, capabilities = "network", description = "Read the status and result of a build.",
            panel = "organization_url, personal_access_token, project, build_id")
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
            description = "List recent builds, newest first, optionally for one definition, branch, status or result.",
            panel = "organization_url, personal_access_token, project, definition_id, branch | status, result, max_results")
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
            description = "List a team's sprints, or only the current one.",
            panel = "organization_url, personal_access_token, project, current_only | team")
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
            description = "Return the ids of the work items planned in a sprint; read details with Get work item or Query work items.",
            panel = "organization_url, personal_access_token, project, iteration_id | team")
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
