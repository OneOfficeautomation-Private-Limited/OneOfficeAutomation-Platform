package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Asana REST API (1.0) with a personal access token or OAuth token. Every answer is wrapped in {"data": ...}. */
@OoaPackage(id = "ooa.asana", name = "Asana", version = "1.0.0",
        description = "Manage Asana work: workspaces, projects, sections, tasks and subtasks, followers, comments, attachments, tags and users.")
public final class AsanaPackage {
    static final String P = "ASANA";
    private static final Pattern GID = Pattern.compile("^\\d{1,30}$");
    private static final Pattern USER = Pattern.compile("^(\\d{1,30}|me|[^@\\s/]+@[^@\\s/]+)$");
    private static final String D_BASE = "API address. Keep the default unless your company routes Asana through a proxy.";
    private static final String DEF_BASE = "\"https://app.asana.com/api/1.0\"";
    private static final String D_TOKEN = "Personal access token or OAuth token from the vault.";
    private static final String D_TASK = "Task id: the long number at the end of the task's link, e.g. 1208000000000001.";
    private static final String D_PROJECT = "Project id: the long number in the project's link, e.g. 1207000000000001.";
    private static final String D_WS = "Workspace id, from List workspaces.";
    private static final String D_USER = "User id, an email address, or me for the token's own user.";
    private static final String D_MAX = "Most items to return; pages are read until this many are found.";
    private static final String TASK_LIST_FIELDS = "name,completed,assignee.name,due_on,modified_at,permalink_url";
    private static final String[] TASK_COLS = {"gid", "name", "completed", "assignee=assignee.name", "due_on", "modified_at", "url=permalink_url"};
    private static final String TASK_FIELDS = "name,notes,completed,completed_at,assignee.name,assignee.email,due_on,due_at,start_on,projects.name,tags.name,"
            + "parent.name,memberships.section.name,memberships.project.name,created_at,modified_at,permalink_url,custom_fields.name,"
            + "custom_fields.display_value,followers.name,num_subtasks";

    static String api(String base) { return base.replaceAll("/+$", ""); }
    static String gid(String what, String v) {
        if (v == null || !GID.matcher(v.strip()).matches())
            throw new ActionException(P + ".BAD_ID", "Invalid " + what + " id '" + v + "'", "Use the numeric id from the item's link, e.g. 1208000000000001.");
        return v.strip();
    }
    static String user(String v) {
        if (v == null || !USER.matcher(v.strip()).matches())
            throw new ActionException(P + ".BAD_USER", "Invalid user '" + v + "'", "Use a numeric user id, an email address, or me.");
        return v.strip();
    }

    static HttpJson.Response send(HttpJson.Req r, ActionContext ctx) {
        try { return r.send(ctx); }
        catch (ActionException e) {
            Object st = e.details() == null ? null : e.details().get("status");
            if (st instanceof BigDecimal s && s.intValue() == 402)
                throw new ActionException(P + ".PREMIUM_REQUIRED", e.getMessage(), "This feature needs a paid Asana plan for the workspace.", false, e.details());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(ActionContext ctx, String method, String base, String path, Object token, Map<String, Object> body) {
        HttpJson.Req r = HttpJson.req(P, method, api(base) + path).bearer(token);
        if (body != null) r.json(Map.of("data", body));
        Object d = send(r, ctx).map().get("data");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** Reads pages linked by next_page.offset. */
    static List<Map<String, Object>> pages(ActionContext ctx, String base, String path, Object token, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        String offset = null;
        while (out.size() < max) {
            String url = api(base) + path + (path.contains("?") ? "&" : "?") + "limit=" + Math.max(1, Math.min(100, max - out.size()))
                    + (offset == null ? "" : "&offset=" + HttpJson.enc(offset));
            Map<String, Object> r = send(HttpJson.req(P, "GET", url).bearer(token).timeout(120), ctx).map();
            List<Map<String, Object>> items = HttpJson.maps(r.get("data"));
            for (Map<String, Object> m : items) { out.add(m); if (out.size() >= max) break; }
            Object next = HttpJson.path(r, "next_page.offset");
            if (next == null || items.isEmpty()) break;
            offset = Values.text(next);
        }
        return out;
    }

    static Map<String, Object> result(List<Map<String, Object>> recs, String... cols) {
        return Out.of("table", HttpJson.table(recs, cols), "count", BigDecimal.valueOf(recs.size()));
    }

    static List<Object> names(Object list, String path) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : HttpJson.maps(list)) { Object v = HttpJson.path(m, path); if (v != null && !out.contains(v)) out.add(v); }
        return out;
    }

    static List<String> ids(String what, Object v) {
        List<String> out = new ArrayList<>();
        for (String s : HttpJson.addressList(v)) out.add(what.equals("user") ? user(s) : gid(what, s));
        return out;
    }

    static Map<String, Object> task(Map<String, Object> t) {
        Map<String, Object> custom = new LinkedHashMap<>();
        for (Map<String, Object> cf : HttpJson.maps(t.get("custom_fields"))) custom.put(String.valueOf(cf.get("name")), cf.get("display_value"));
        return Out.of("gid", t.get("gid"), "name", t.get("name"), "notes", t.get("notes"), "completed", t.get("completed"),
                "completed_at", t.get("completed_at"), "assignee", HttpJson.path(t, "assignee.name"), "assignee_email", HttpJson.path(t, "assignee.email"),
                "due_on", t.get("due_on"), "due_at", t.get("due_at"), "start_on", t.get("start_on"), "projects", names(t.get("projects"), "name"),
                "sections", names(t.get("memberships"), "section.name"), "tags", names(t.get("tags"), "name"), "followers", names(t.get("followers"), "name"),
                "parent", HttpJson.path(t, "parent.name"), "subtasks", t.get("num_subtasks"), "custom_fields", custom,
                "created_at", t.get("created_at"), "modified_at", t.get("modified_at"), "url", t.get("permalink_url"));
    }

    // ---------------------------------------------------------------- workspaces, projects, sections

    @Action(id = "list_workspaces", name = "List workspaces", category = "Workspaces", idempotent = true, capabilities = "network",
            description = "List the workspaces and organizations the token can see.",
            panel = "token | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, is_organization.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of workspaces.")
    public Map<String, Object> listWorkspaces(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        return result(pages(ctx, base, "/workspaces?opt_fields=name,is_organization", token, 1000), "gid", "name", "is_organization");
    }

    @Action(id = "list_projects", name = "List projects", category = "Projects", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the projects in a workspace, optionally only those of one team.",
            panel = "token, team_id, workspace_id, include_archived | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, archived, owner, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of projects returned.")
    public Map<String, Object> listProjects(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = D_WS) String ws,
            @Input(name = "team_id", type = OoaType.STRING, required = false, description = "Only projects of this team id.") String team,
            @Input(name = "include_archived", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also list archived projects.") boolean archived,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_MAX) int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("workspace", gid("workspace", ws));
        if (team != null && !team.isBlank()) q.put("team", gid("team", team));
        if (!archived) q.put("archived", "false");
        q.put("opt_fields", "name,archived,owner.name,permalink_url");
        return result(pages(ctx, base, "/projects?" + HttpJson.query(q), token, max), "gid", "name", "archived", "owner=owner.name", "url=permalink_url");
    }

    @Action(id = "get_project", name = "Get project", category = "Projects", idempotent = true, capabilities = "network", description = "Read a project's details.",
            panel = "token, project_id | base_url")
    @Output(name = "project", type = OoaType.DICTIONARY, description = "gid, name, notes, archived, owner, team, workspace, due_on, created_at, modified_at, url.")
    public Map<String, Object> getProject(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "project_id", type = OoaType.STRING, description = D_PROJECT) String project) {
        Map<String, Object> p = data(ctx, "GET", base, "/projects/" + gid("project", project)
                + "?opt_fields=name,notes,archived,owner.name,team.name,workspace.name,due_on,created_at,modified_at,permalink_url", token, null);
        return Out.of("project", Out.of("gid", p.get("gid"), "name", p.get("name"), "notes", p.get("notes"), "archived", p.get("archived"),
                "owner", HttpJson.path(p, "owner.name"), "team", HttpJson.path(p, "team.name"), "workspace", HttpJson.path(p, "workspace.name"),
                "due_on", p.get("due_on"), "created_at", p.get("created_at"), "modified_at", p.get("modified_at"), "url", p.get("permalink_url")));
    }

    @Action(id = "create_project", name = "Create project", category = "Projects", capabilities = "network",
            description = "Create a project in a workspace. Organization workspaces also need a team.",
            panel = "token, name, workspace_id, team_id, notes | base_url")
    @Output(name = "project_id", type = OoaType.STRING, description = "Id of the new project.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the project.")
    public Map<String, Object> createProject(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = D_WS) String ws,
            @Input(name = "name", type = OoaType.STRING, example = "Q1 2027 month-end close", description = "Project name.") String projName,
            @Input(name = "team_id", type = OoaType.STRING, required = false, description = "Team id; required in organization workspaces.") String team,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "Project description.") String notes) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", projName);
        b.put("workspace", gid("workspace", ws));
        if (team != null && !team.isBlank()) b.put("team", gid("team", team));
        if (notes != null) b.put("notes", notes);
        Map<String, Object> p = data(ctx, "POST", base, "/projects?opt_fields=permalink_url", token, b);
        return Out.of("project_id", p.get("gid"), "url", p.get("permalink_url"));
    }

    @Action(id = "list_sections", name = "List sections", category = "Projects", idempotent = true, capabilities = "network",
            description = "List the sections (board columns) of a project.",
            panel = "token, project_id | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of sections.")
    public Map<String, Object> listSections(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "project_id", type = OoaType.STRING, description = D_PROJECT) String project) {
        return result(pages(ctx, base, "/projects/" + gid("project", project) + "/sections?opt_fields=name", token, 1000), "gid", "name");
    }

    @Action(id = "create_section", name = "Create section", category = "Projects", capabilities = "network", description = "Add a section (board column) to a project.",
            panel = "token, project_id, name | base_url")
    @Output(name = "section_id", type = OoaType.STRING, description = "Id of the new section.")
    public Map<String, Object> createSection(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "project_id", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "name", type = OoaType.STRING, example = "Waiting for approval", description = "Section name.") String secName) {
        return Out.of("section_id", data(ctx, "POST", base, "/projects/" + gid("project", project) + "/sections", token, Map.of("name", secName)).get("gid"));
    }

    // ---------------------------------------------------------------- tasks

    /** Earlier signature, kept for callers written before parent_task_id and completed. */
    public Map<String, Object> createTask(ActionContext ctx, String base, Object token, String taskName, String project, String section, String ws,
            String notes, String assignee, String due, Object tags, Object followers, Map<String, Object> custom) {
        return createTask(ctx, base, token, taskName, project, section, ws, notes, assignee, due, tags, followers, custom, null, false);
    }

    @Action(id = "create_task", name = "Create task", category = "Tasks", capabilities = "network",
            description = "Create a task in a project (optionally in one of its sections), in a workspace, or as a subtask of another task.",
            panel = "token, workspace_id, name, project_id, assignee, due_on, notes | parent_task_id, completed, section_id, tag_ids, follower_ids, custom_fields, base_url")
    @Output(name = "task_id", type = OoaType.STRING, description = "Id of the new task.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the task.")
    public Map<String, Object> createTask(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "name", type = OoaType.STRING, example = "Approve invoice INV-2041", description = "Task name.") String taskName,
            @Input(name = "project_id", type = OoaType.STRING, required = false, description = "Project to add the task to. Give this or workspace_id.") String project,
            @Input(name = "section_id", type = OoaType.STRING, required = false, description = "Section of the project to place the task in.") String section,
            @Input(name = "workspace_id", type = OoaType.STRING, required = false, description = "Workspace, when the task belongs to no project.") String ws,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "Task description.") String notes,
            @Input(name = "assignee", type = OoaType.STRING, required = false, example = "ana@contoso.com", description = D_USER) String assignee,
            @Input(name = "due_on", type = OoaType.STRING, required = false, example = "2026-11-15", description = "Due date as YYYY-MM-DD.") String due,
            @Input(name = "tag_ids", type = OoaType.LIST, required = false, description = "Tag ids to add.") Object tags,
            @Input(name = "follower_ids", type = OoaType.LIST, required = false, description = "Users to follow the task: ids, emails or me.") Object followers,
            @Input(name = "custom_fields", type = OoaType.DICTIONARY, required = false, description = "Custom field id to value, e.g. {\"1201234567890\": \"High\"}. For drop-downs use the option id.") Map<String, Object> custom,
            @Input(name = "parent_task_id", type = OoaType.STRING, required = false, description = "Create the task as a subtask of this task; then project_id and workspace_id may stay empty.") String parent,
            @Input(name = "completed", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the task already marked complete, e.g. to record work that is done.") boolean completed) {
        boolean hasProject = project != null && !project.isBlank(), hasParent = parent != null && !parent.isBlank();
        if (!hasProject && !hasParent && (ws == null || ws.isBlank()))
            throw new ActionException(P + ".MISSING_LOCATION", "No project, parent task or workspace given", "Fill in project_id, parent_task_id, or workspace_id for a task outside projects.");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", taskName);
        if (hasParent) b.put("parent", gid("parent task", parent));
        if (completed) b.put("completed", true);
        if (hasProject && section != null && !section.isBlank())
            b.put("memberships", List.of(Map.of("project", gid("project", project), "section", gid("section", section))));
        else if (hasProject) b.put("projects", List.of(gid("project", project)));
        if (ws != null && !ws.isBlank()) b.put("workspace", gid("workspace", ws));
        if (notes != null) b.put("notes", notes);
        if (assignee != null && !assignee.isBlank()) b.put("assignee", user(assignee));
        if (due != null && !due.isBlank()) b.put("due_on", due);
        if (tags != null) b.put("tags", ids("tag", tags));
        if (followers != null) b.put("followers", ids("user", followers));
        if (custom != null && !custom.isEmpty()) b.put("custom_fields", custom);
        Map<String, Object> t = data(ctx, "POST", base, "/tasks?opt_fields=permalink_url", token, b);
        return Out.of("task_id", t.get("gid"), "url", t.get("permalink_url"));
    }

    @Action(id = "get_task", name = "Get task", category = "Tasks", idempotent = true, capabilities = "network", description = "Read a task with its assignee, dates, projects, tags and custom fields.",
            panel = "token, task_id | base_url")
    @Output(name = "task", type = OoaType.DICTIONARY, description = "gid, name, notes, completed, completed_at, assignee, assignee_email, due_on, due_at, start_on, projects, sections, tags, followers, parent, subtasks, custom_fields (name to value), created_at, modified_at, url.")
    @Output(name = "completed", type = OoaType.BOOLEAN, description = "True when the task is done.")
    public Map<String, Object> getTask(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task) {
        Map<String, Object> t = task(data(ctx, "GET", base, "/tasks/" + gid("task", task) + "?opt_fields=" + TASK_FIELDS, token, null));
        return Out.of("task", t, "completed", Boolean.TRUE.equals(t.get("completed")));
    }

    /** Earlier signature, kept for callers written before parent_task_id. */
    public Map<String, Object> updateTask(ActionContext ctx, String base, Object token, String task, String taskName, String notes, String assignee,
            String due, Boolean completed, Map<String, Object> custom) {
        return updateTask(ctx, base, token, task, taskName, notes, assignee, due, completed, custom, null);
    }

    @Action(id = "update_task", name = "Update task", category = "Tasks", capabilities = "network",
            description = "Change a task's name, notes, assignee, due date, completion, custom fields or parent task. Only the inputs you fill in are changed.",
            panel = "token, task_id, name, assignee, due_on, notes, completed | parent_task_id, custom_fields, base_url")
    public Map<String, Object> updateTask(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String taskName,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "New description (replaces the old one).") String notes,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = "New assignee: user id, email or me; none to unassign.") String assignee,
            @Input(name = "due_on", type = OoaType.STRING, required = false, description = "New due date as YYYY-MM-DD.") String due,
            @Input(name = "completed", type = OoaType.BOOLEAN, required = false, description = "true marks the task done, false reopens it.") Boolean completed,
            @Input(name = "custom_fields", type = OoaType.DICTIONARY, required = false, description = "Custom field id to new value.") Map<String, Object> custom,
            @Input(name = "parent_task_id", type = OoaType.STRING, required = false,
                    description = "Move the task under this parent task, making it a subtask; none makes it a top-level task again.") String parent) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (taskName != null) b.put("name", taskName);
        if (notes != null) b.put("notes", notes);
        if (assignee != null) b.put("assignee", assignee.equalsIgnoreCase("none") ? null : user(assignee));
        if (due != null) b.put("due_on", due.isBlank() ? null : due);
        if (completed != null) b.put("completed", completed);
        if (custom != null && !custom.isEmpty()) b.put("custom_fields", custom);
        boolean move = parent != null && !parent.isBlank();
        if (b.isEmpty() && !move) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No changes were given", "Fill in at least one field to change.");
        String id = gid("task", task);
        Map<String, Object> p = new LinkedHashMap<>();
        if (move) p.put("parent", parent.strip().equalsIgnoreCase("none") ? null : gid("parent task", parent));
        if (!b.isEmpty()) data(ctx, "PUT", base, "/tasks/" + id, token, b);
        if (move) data(ctx, "POST", base, "/tasks/" + id + "/setParent", token, p);
        return Map.of();
    }

    @Action(id = "delete_task", name = "Delete task", category = "Tasks", capabilities = "network",
            description = "Delete a task. It goes to the trash of the user who owns the token and can be restored from there for a limited time.",
            panel = "token, task_id | base_url")
    public Map<String, Object> deleteTask(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task) {
        data(ctx, "DELETE", base, "/tasks/" + gid("task", task), token, null);
        return Map.of();
    }

    @Action(id = "list_tasks", name = "List tasks", category = "Tasks", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the tasks of a project, of a section, or assigned to a user in a workspace.",
            panel = "token, workspace_id, project_id, assignee, modified_since | section_id, only_open, max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, completed, assignee, due_on, modified_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tasks returned.")
    public Map<String, Object> listTasks(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "project_id", type = OoaType.STRING, required = false, description = "List the tasks of this project.") String project,
            @Input(name = "section_id", type = OoaType.STRING, required = false, description = "Or list the tasks of this section.") String section,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = "Or list tasks assigned to this user (id, email or me); also needs workspace_id.") String assignee,
            @Input(name = "workspace_id", type = OoaType.STRING, required = false, description = "Workspace, used together with assignee.") String ws,
            @Input(name = "only_open", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave out completed tasks.") boolean open,
            @Input(name = "modified_since", type = OoaType.STRING, required = false, example = "2026-10-01T00:00:00Z", description = "Only tasks changed at or after this time (ISO 8601).") String modified,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_MAX) int max) {
        String path;
        Map<String, Object> q = new LinkedHashMap<>();
        if (section != null && !section.isBlank()) path = "/sections/" + gid("section", section) + "/tasks";
        else if (project != null && !project.isBlank()) path = "/projects/" + gid("project", project) + "/tasks";
        else if (assignee != null && !assignee.isBlank() && ws != null && !ws.isBlank()) {
            path = "/tasks";
            q.put("assignee", user(assignee));
            q.put("workspace", gid("workspace", ws));
        } else throw new ActionException(P + ".MISSING_FILTER", "No project, section or assignee given", "Fill in project_id, section_id, or assignee together with workspace_id.");
        if (open) q.put("completed_since", "now");
        if (modified != null && !modified.isBlank()) q.put("modified_since", modified);
        q.put("opt_fields", TASK_LIST_FIELDS);
        return result(pages(ctx, base, path + "?" + HttpJson.query(q), token, max), TASK_COLS);
    }

    @Action(id = "search_tasks", name = "Search tasks", category = "Tasks", idempotent = true, capabilities = "network",
            description = "Search a workspace for tasks by text, assignee, project or completion (needs a paid Asana plan). Returns up to 100 tasks, most recently changed first.",
            panel = "token, workspace_id, text, assignee, project_id, completed | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, completed, assignee, due_on, modified_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tasks found.")
    public Map<String, Object> searchTasks(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = D_WS) String ws,
            @Input(name = "text", type = OoaType.STRING, required = false, example = "invoice", description = "Words to look for in task names and descriptions.") String text,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = "Only tasks assigned to this user (id or me).") String assignee,
            @Input(name = "project_id", type = OoaType.STRING, required = false, description = "Only tasks in this project.") String project,
            @Input(name = "completed", type = OoaType.BOOLEAN, required = false, description = "true for done tasks only, false for open tasks only; empty for both.") Boolean completed,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Most tasks to return (up to 100).") int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (text != null && !text.isBlank()) q.put("text", text);
        if (assignee != null && !assignee.isBlank()) q.put("assignee.any", user(assignee));
        if (project != null && !project.isBlank()) q.put("projects.any", gid("project", project));
        if (completed != null) q.put("completed", completed);
        q.put("sort_by", "modified_at");
        q.put("limit", Math.max(1, Math.min(100, max)));
        q.put("opt_fields", TASK_LIST_FIELDS);
        Map<String, Object> r = send(HttpJson.req(P, "GET", api(base) + "/workspaces/" + gid("workspace", ws) + "/tasks/search?" + HttpJson.query(q)).bearer(token), ctx).map();
        return result(HttpJson.maps(r.get("data")), TASK_COLS);
    }

    @Action(id = "add_followers", name = "Add followers", category = "Tasks", capabilities = "network", description = "Add people as followers so they are notified about the task.",
            panel = "token, task_id, followers | base_url")
    public Map<String, Object> addFollowers(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "followers", type = OoaType.LIST, description = "Users to add: ids, emails or me, e.g. [\"ana@contoso.com\"].") Object followers) {
        data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/addFollowers", token, Map.of("followers", ids("user", followers)));
        return Map.of();
    }

    @Action(id = "remove_followers", name = "Remove followers", category = "Tasks", capabilities = "network", description = "Stop people from following a task.",
            panel = "token, task_id, followers | base_url")
    public Map<String, Object> removeFollowers(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "followers", type = OoaType.LIST, description = "Users to remove: ids, emails or me.") Object followers) {
        data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/removeFollowers", token, Map.of("followers", ids("user", followers)));
        return Map.of();
    }

    @Action(id = "add_task_to_section", name = "Move task to section", category = "Tasks", capabilities = "network",
            description = "Place a task in a section of a project it belongs to, e.g. to move it to another board column.",
            panel = "token, task_id, section_id | base_url")
    public Map<String, Object> addToSection(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "section_id", type = OoaType.STRING, description = "Target section id, from List sections.") String section,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task) {
        data(ctx, "POST", base, "/sections/" + gid("section", section) + "/addTask", token, Map.of("task", gid("task", task)));
        return Map.of();
    }

    @Action(id = "add_task_to_project", name = "Add task to project", category = "Tasks", capabilities = "network",
            description = "Also show a task in another project, optionally in a given section.",
            panel = "token, task_id, project_id, section_id | base_url")
    public Map<String, Object> addToProject(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "project_id", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "section_id", type = OoaType.STRING, required = false, description = "Section of that project to place the task in.") String section) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("project", gid("project", project));
        if (section != null && !section.isBlank()) b.put("section", gid("section", section));
        data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/addProject", token, b);
        return Map.of();
    }

    @Action(id = "create_subtask", name = "Create subtask", category = "Subtasks", capabilities = "network", description = "Add a subtask under a task.",
            panel = "token, parent_task_id, name, assignee, due_on, notes | base_url")
    @Output(name = "task_id", type = OoaType.STRING, description = "Id of the new subtask.")
    @Output(name = "url", type = OoaType.STRING, description = "Web address of the subtask.")
    public Map<String, Object> createSubtask(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "parent_task_id", type = OoaType.STRING, description = "Id of the parent task.") String parent,
            @Input(name = "name", type = OoaType.STRING, description = "Subtask name.") String taskName,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "Subtask description.") String notes,
            @Input(name = "assignee", type = OoaType.STRING, required = false, description = D_USER) String assignee,
            @Input(name = "due_on", type = OoaType.STRING, required = false, description = "Due date as YYYY-MM-DD.") String due) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", taskName);
        if (notes != null) b.put("notes", notes);
        if (assignee != null && !assignee.isBlank()) b.put("assignee", user(assignee));
        if (due != null && !due.isBlank()) b.put("due_on", due);
        Map<String, Object> t = data(ctx, "POST", base, "/tasks/" + gid("task", parent) + "/subtasks?opt_fields=permalink_url", token, b);
        return Out.of("task_id", t.get("gid"), "url", t.get("permalink_url"));
    }

    @Action(id = "list_subtasks", name = "List subtasks", category = "Subtasks", idempotent = true, capabilities = "network", description = "List the subtasks of a task.",
            panel = "token, task_id | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, completed, assignee, due_on, modified_at, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of subtasks.")
    public Map<String, Object> listSubtasks(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = "Id of the parent task.") String task) {
        return result(pages(ctx, base, "/tasks/" + gid("task", task) + "/subtasks?opt_fields=" + TASK_LIST_FIELDS, token, 1000), TASK_COLS);
    }

    // ---------------------------------------------------------------- comments and attachments

    private static final String D_FORMAT = "plain sends the text as it is; html sends Asana rich text, wrapped in <body>...</body>, e.g. <body>Paid <strong>today</strong></body>.";

    /** The story body for a comment: text or html_text, and is_pinned when given. */
    static Map<String, Object> commentBody(String text, String format, Boolean pinned) {
        String f = format == null || format.isBlank() ? "plain" : format.strip().toLowerCase(Locale.ROOT);
        if (!f.equals("plain") && !f.equals("html"))
            throw new ActionException(P + ".BAD_FORMAT", "Unknown comment format '" + format + "'", "Use plain or html.");
        Map<String, Object> b = new LinkedHashMap<>();
        if (text != null) b.put(f.equals("html") ? "html_text" : "text", text);
        if (pinned != null) b.put("is_pinned", pinned);
        return b;
    }

    /** Earlier signature, kept for callers written before text_format and pinned. */
    public Map<String, Object> addComment(ActionContext ctx, String base, Object token, String task, String text) {
        return addComment(ctx, base, token, task, text, "plain", false);
    }

    @Action(id = "add_comment", name = "Add comment", category = "Comments", capabilities = "network", description = "Post a comment on a task, as plain or rich text, optionally pinned to the top.",
            panel = "token, task_id, text | text_format, pinned, base_url")
    @Output(name = "comment_id", type = OoaType.STRING, description = "Id of the new comment.")
    public Map<String, Object> addComment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "text", type = OoaType.STRING, example = "Invoice approved by the finance agent.", description = "Comment text.") String text,
            @Input(name = "text_format", type = OoaType.STRING, required = false, defaultValue = "\"plain\"", enumValues = {"plain", "html"}, description = D_FORMAT) String format,
            @Input(name = "pinned", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Pin the comment to the top of the task's activity.") boolean pinned) {
        Map<String, Object> b = commentBody(text, format, pinned ? Boolean.TRUE : null);
        return Out.of("comment_id", data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/stories", token, b).get("gid"));
    }

    @Action(id = "get_comment", name = "Get comment", category = "Comments", idempotent = true, capabilities = "network",
            description = "Read one comment (or other task story) by its id.",
            panel = "token, comment_id | base_url")
    @Output(name = "comment", type = OoaType.DICTIONARY, description = "gid, type, text, html_text, pinned, author, created_at, task, task_id.")
    public Map<String, Object> getComment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "comment_id", type = OoaType.STRING, description = "Comment id, from Add comment or List comments.") String comment) {
        Map<String, Object> s = data(ctx, "GET", base, "/stories/" + gid("comment", comment)
                + "?opt_fields=type,resource_subtype,text,html_text,is_pinned,created_by.name,created_at,target.name", token, null);
        return Out.of("comment", Out.of("gid", s.get("gid"), "type", s.get("resource_subtype") != null ? s.get("resource_subtype") : s.get("type"),
                "text", s.get("text"), "html_text", s.get("html_text"), "pinned", s.get("is_pinned"), "author", HttpJson.path(s, "created_by.name"),
                "created_at", s.get("created_at"), "task", HttpJson.path(s, "target.name"), "task_id", HttpJson.path(s, "target.gid")));
    }

    @Action(id = "list_comments", name = "List comments", category = "Comments", idempotent = true, capabilities = "network",
            description = "Read the comments on a task, oldest first. System messages such as assignment changes are left out.",
            panel = "token, task_id | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, text, author, created_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of comments.")
    public Map<String, Object> listComments(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task) {
        List<Map<String, Object>> all = pages(ctx, base, "/tasks/" + gid("task", task) + "/stories?opt_fields=type,resource_subtype,text,created_by.name,created_at", token, 5000);
        List<Map<String, Object>> comments = new ArrayList<>();
        for (Map<String, Object> s : all) if ("comment".equals(s.get("type")) || "comment_added".equals(s.get("resource_subtype"))) comments.add(s);
        return result(comments, "gid", "text", "author=created_by.name", "created_at");
    }

    @Action(id = "upload_attachment", name = "Upload attachment", category = "Attachments", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Attach a local file to a task.",
            panel = "token, task_id, path | file_name, base_url")
    @Output(name = "attachment_id", type = OoaType.STRING, description = "Id of the attachment.")
    @Output(name = "url", type = OoaType.STRING, description = "Address to view the attachment.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "path", type = OoaType.FILE, example = "out/INV-2041.pdf", description = "Local file to attach (Asana accepts up to 100 MB).") String path,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = "Name to give the attachment; the local file name when empty.") String fileName) {
        byte[] bytes = HttpJson.readFile(ctx, path);
        String fname = HttpJson.safeName(fileName == null || fileName.isBlank() ? Path.of(path).getFileName().toString() : fileName.strip()).replace("\"", "");
        Object[] mp = HttpJson.multipart("form-data", List.of(
                new Object[]{"Content-Disposition: form-data; name=\"parent\"", gid("task", task).getBytes(StandardCharsets.UTF_8)},
                new Object[]{"Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\nContent-Type: application/octet-stream", bytes}));
        Map<String, Object> r = send(HttpJson.req(P, "POST", api(base) + "/attachments?opt_fields=name,view_url,permanent_url").bearer(token)
                .bytes((byte[]) mp[0], (String) mp[1]).timeout(600), ctx).map();
        Object d = r.get("data");
        return Out.of("attachment_id", HttpJson.path(d, "gid"), "url", HttpJson.path(d, "view_url") != null ? HttpJson.path(d, "view_url") : HttpJson.path(d, "permanent_url"));
    }

    public Map<String, Object> upload(ActionContext ctx, String base, Object token, String task, String path) {
        return upload(ctx, base, token, task, path, null);
    }

    /** Earlier signature, kept for callers written before text_format and pinned. */
    public Map<String, Object> updateComment(ActionContext ctx, String base, Object token, String comment, String text) {
        return updateComment(ctx, base, token, comment, text, "plain", null);
    }

    @Action(id = "update_comment", name = "Update comment", category = "Comments", capabilities = "network",
            description = "Change the text of a comment (only the comment's author can), or pin or unpin it.",
            panel = "token, comment_id, text | text_format, pinned, base_url")
    public Map<String, Object> updateComment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "comment_id", type = OoaType.STRING, description = "Comment id, from Add comment or List comments.") String comment,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "New comment text; leave empty to keep the text and only pin or unpin.") String text,
            @Input(name = "text_format", type = OoaType.STRING, required = false, defaultValue = "\"plain\"", enumValues = {"plain", "html"}, description = D_FORMAT) String format,
            @Input(name = "pinned", type = OoaType.BOOLEAN, required = false, description = "true pins the comment to the top of the task, false unpins it; empty leaves it as it is.") Boolean pinned) {
        Map<String, Object> b = commentBody(text == null || text.isEmpty() ? null : text, format, pinned);
        if (b.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No changes were given", "Fill in text or pinned.");
        data(ctx, "PUT", base, "/stories/" + gid("comment", comment), token, b);
        return Map.of();
    }

    @Action(id = "delete_comment", name = "Delete comment", category = "Comments", capabilities = "network", description = "Delete a comment (only the comment's author can).",
            panel = "token, comment_id | base_url")
    public Map<String, Object> deleteComment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "comment_id", type = OoaType.STRING, description = "Comment id, from Add comment or List comments.") String comment) {
        data(ctx, "DELETE", base, "/stories/" + gid("comment", comment), token, null);
        return Map.of();
    }

    @Action(id = "list_attachments", name = "List attachments", category = "Attachments", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the files attached to a task, with their size and download link.",
            panel = "token, task_id | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, size (bytes), host, created_at, download_url, view_url. The download link is temporary; use Download attachment to save a file.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of attachments returned.")
    public Map<String, Object> listAttachments(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, description = D_MAX) int max) {
        String path = "/attachments?parent=" + gid("task", task) + "&opt_fields=name,size,host,created_at,download_url,view_url";
        return result(pages(ctx, base, path, token, Math.max(1, max)), "gid", "name", "size", "host", "created_at", "download_url", "view_url");
    }

    @Action(id = "download_attachment", name = "Download attachment", category = "Attachments", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save an attachment of a task to a local folder.",
            panel = "token, attachment_id, folder | overwrite, base_url")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    public Map<String, Object> downloadAttachment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "attachment_id", type = OoaType.STRING, description = "Attachment id, from Upload attachment or the task's attachments.") String attachment,
            @Input(name = "folder", type = OoaType.FILE, required = false, defaultValue = "\".\"", description = "Folder to save into.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file of the same name; otherwise a number is added.") boolean overwrite) {
        Map<String, Object> a = data(ctx, "GET", base, "/attachments/" + gid("attachment", attachment) + "?opt_fields=name,download_url", token, null);
        Object url = a.get("download_url");
        if (url == null) throw new ActionException(P + ".NO_DOWNLOAD", "Attachment " + attachment + " has no download address (links to Google Drive, Box and similar are not files)",
                "Open the link from the task instead.");
        byte[] bytes = send(HttpJson.req(P, "GET", Values.text(url)).timeout(600), ctx).bytes();   // a signed address: no token is sent
        java.nio.file.Path dir = ctx.resolvePath(folder == null || folder.isBlank() ? "." : folder);
        String name = HttpJson.safeName(Values.text(a.getOrDefault("name", "attachment")));
        java.nio.file.Path out = dir.resolve(name);
        for (int i = 2; !overwrite && java.nio.file.Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
        HttpJson.writeFile(out, bytes);
        return Out.of("path", out.toString());
    }

    @Action(id = "delete_attachment", name = "Delete attachment", category = "Attachments", capabilities = "network", description = "Remove an attachment from its task.",
            panel = "token, attachment_id | base_url")
    public Map<String, Object> deleteAttachment(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "attachment_id", type = OoaType.STRING, description = "Attachment id.") String attachment) {
        data(ctx, "DELETE", base, "/attachments/" + gid("attachment", attachment), token, null);
        return Map.of();
    }

    @Action(id = "duplicate_task", name = "Duplicate task", category = "Tasks", capabilities = "network",
            description = "Copy a task with a new name, choosing what is copied along (notes, assignee, subtasks, attachments, tags, followers, projects, dates).",
            panel = "token, task_id, name, include | base_url")
    @Output(name = "task_id", type = OoaType.STRING, description = "Id of the new task (it may take a moment to fill in its subtasks).")
    public Map<String, Object> duplicateTask(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "name", type = OoaType.STRING, description = "Name of the copy.") String name,
            @Input(name = "include", type = OoaType.LIST, required = false,
                    description = "What to copy: any of notes, assignee, subtasks, attachments, tags, followers, projects, dates; notes, assignee and projects when empty.") List<Object> include) {
        Set<String> allowed = Set.of("notes", "assignee", "subtasks", "attachments", "tags", "followers", "projects", "dates");
        List<String> inc = new ArrayList<>();
        for (Object o : include == null || include.isEmpty() ? List.<Object>of("notes", "assignee", "projects") : include) {
            String v = Values.text(o).strip().toLowerCase(Locale.ROOT);
            if (!allowed.contains(v)) throw new ActionException(P + ".BAD_INCLUDE", "Cannot copy '" + o + "'", "Use notes, assignee, subtasks, attachments, tags, followers, projects or dates.");
            inc.add(v);
        }
        Map<String, Object> job = data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/duplicate", token, Map.of("name", name, "include", String.join(",", inc)));
        return Out.of("task_id", HttpJson.path(job, "new_task.gid"));
    }

    @Action(id = "update_project", name = "Update project", category = "Projects", capabilities = "network",
            description = "Change a project's name, notes, colour, owner, due date or archived state; empty inputs are left as they are.",
            panel = "token, project_id, name, owner, notes, due_on, archived | fields, base_url")
    public Map<String, Object> updateProject(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "project_id", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String name,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "New description.") String notes,
            @Input(name = "owner", type = OoaType.STRING, required = false, description = D_USER) String owner,
            @Input(name = "due_on", type = OoaType.STRING, required = false, pattern = "^\\d{4}-\\d{2}-\\d{2}$", example = "2026-12-31", description = "Due date, yyyy-MM-dd.") String due,
            @Input(name = "archived", type = OoaType.BOOLEAN, required = false, description = "Archive (true) or restore (false) the project.") Boolean archived,
            @Input(name = "fields", type = OoaType.DICTIONARY, required = false, description = "Other project fields by their API name, e.g. {\"color\": \"dark-green\"}.") Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (fields != null) body.putAll(fields);
        if (name != null && !name.isBlank()) body.put("name", name);
        if (notes != null) body.put("notes", notes);
        if (owner != null && !owner.isBlank()) body.put("owner", user(owner));
        if (due != null && !due.isBlank()) body.put("due_on", due);
        if (archived != null) body.put("archived", archived);
        if (body.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "Nothing to change", "Fill in at least one field.");
        data(ctx, "PUT", base, "/projects/" + gid("project", project), token, body);
        return Map.of();
    }

    @Action(id = "delete_project", name = "Delete project", category = "Projects", capabilities = "network",
            description = "Delete a project; its tasks stay in other projects they belong to. Needs confirm, as this cannot be undone from the API.",
            panel = "token, project_id, confirm | base_url")
    public Map<String, Object> deleteProject(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "project_id", type = OoaType.STRING, description = D_PROJECT) String project,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true: a safeguard against deleting by mistake.") boolean confirm) {
        if (!confirm) throw new ActionException(P + ".CONFIRM_REQUIRED", "The project was not deleted because confirm is false", "Set confirm to true.");
        data(ctx, "DELETE", base, "/projects/" + gid("project", project), token, null);
        return Map.of();
    }

    @Action(id = "create_team", name = "Create team", category = "Teams", capabilities = "network", description = "Create a team in an organization workspace.",
            panel = "token, name, team_description, workspace_id | base_url")
    @Output(name = "team_id", type = OoaType.STRING, description = "Id of the new team.")
    public Map<String, Object> createTeam(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = "Organization (workspace) id, from List workspaces.") String ws,
            @Input(name = "name", type = OoaType.STRING, description = "Team name.") String name,
            @Input(name = "team_description", type = OoaType.STRING, required = false, description = "What the team is for.") String desc) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "organization", gid("workspace", ws)));
        if (desc != null && !desc.isBlank()) body.put("description", desc);
        return Out.of("team_id", data(ctx, "POST", base, "/teams", token, body).get("gid"));
    }

    @Action(id = "get_team", name = "Get team", category = "Teams", idempotent = true, capabilities = "network", description = "Read a team's name, description, organization and link.",
            panel = "token, team_id | base_url")
    @Output(name = "team", type = OoaType.DICTIONARY, description = "Keys: gid, name, description, organization, url.")
    public Map<String, Object> getTeam(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team) {
        Map<String, Object> t = data(ctx, "GET", base, "/teams/" + gid("team", team) + "?opt_fields=name,description,organization.name,permalink_url", token, null);
        return Out.of("team", Out.of("gid", t.get("gid"), "name", t.get("name"), "description", t.get("description"),
                "organization", HttpJson.path(t, "organization.name"), "url", t.get("permalink_url")));
    }

    @Action(id = "update_team", name = "Update team", category = "Teams", capabilities = "network", description = "Rename a team or change its description.",
            panel = "token, team_id, name, team_description | base_url")
    public Map<String, Object> updateTeam(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "team_id", type = OoaType.STRING, description = "Team id.") String team,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String name,
            @Input(name = "team_description", type = OoaType.STRING, required = false, description = "New description.") String desc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) body.put("name", name);
        if (desc != null) body.put("description", desc);
        if (body.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "Nothing to change", "Fill in name or team_description.");
        data(ctx, "PUT", base, "/teams/" + gid("team", team), token, body);
        return Map.of();
    }

    @Action(id = "list_teams", name = "List teams", category = "Teams", idempotent = true, capabilities = "network",
            description = "List the teams of an organization workspace that the token's user can see.",
            panel = "token, workspace_id | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, description, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of teams returned.")
    public Map<String, Object> listTeams(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = "Organization (workspace) id, from List workspaces.") String ws,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, description = D_MAX) int max) {
        return result(pages(ctx, base, "/workspaces/" + gid("workspace", ws) + "/teams?opt_fields=name,description,permalink_url", token, Math.max(1, max)),
                "gid", "name", "description", "url=permalink_url");
    }

    @Action(id = "list_user_teams", name = "List teams of user", category = "Teams", idempotent = true, capabilities = "network",
            description = "List the teams a person belongs to in an organization workspace.",
            panel = "token, user, workspace_id | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, description, url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of teams returned.")
    public Map<String, Object> listUserTeams(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "user", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_USER) String u,
            @Input(name = "workspace_id", type = OoaType.STRING, description = "Organization (workspace) id, from List workspaces.") String ws,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, description = D_MAX) int max) {
        String path = "/users/" + HttpJson.enc(user(u == null || u.isBlank() ? "me" : u)) + "/teams?organization=" + gid("workspace", ws)
                + "&opt_fields=name,description,permalink_url";
        return result(pages(ctx, base, path, token, Math.max(1, max)), "gid", "name", "description", "url=permalink_url");
    }

    // ---------------------------------------------------------------- tags

    @Action(id = "list_tags", name = "List tags", category = "Tags", idempotent = true, capabilities = "network", description = "List the tags of a workspace.",
            panel = "token, workspace_id | base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, color.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tags.")
    public Map<String, Object> listTags(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = D_WS) String ws) {
        return result(pages(ctx, base, "/workspaces/" + gid("workspace", ws) + "/tags?opt_fields=name,color", token, 5000), "gid", "name", "color");
    }

    @Action(id = "create_tag", name = "Create tag", category = "Tags", capabilities = "network", description = "Create a tag in a workspace.",
            panel = "token, workspace_id, name | base_url")
    @Output(name = "tag_id", type = OoaType.STRING, description = "Id of the new tag.")
    public Map<String, Object> createTag(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = D_WS) String ws,
            @Input(name = "name", type = OoaType.STRING, example = "needs-review", description = "Tag name.") String tagName) {
        return Out.of("tag_id", data(ctx, "POST", base, "/tags", token, Map.of("name", tagName, "workspace", gid("workspace", ws))).get("gid"));
    }

    @Action(id = "add_tag_to_task", name = "Add tag to task", category = "Tags", capabilities = "network", description = "Put a tag on a task.",
            panel = "token, task_id, tag_id | base_url")
    public Map<String, Object> addTag(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "tag_id", type = OoaType.STRING, description = "Tag id, from List tags.") String tag) {
        data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/addTag", token, Map.of("tag", gid("tag", tag)));
        return Map.of();
    }

    @Action(id = "remove_tag_from_task", name = "Remove tag from task", category = "Tags", capabilities = "network", description = "Take a tag off a task.",
            panel = "token, task_id, tag_id | base_url")
    public Map<String, Object> removeTag(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "task_id", type = OoaType.STRING, description = D_TASK) String task,
            @Input(name = "tag_id", type = OoaType.STRING, description = "Tag id.") String tag) {
        data(ctx, "POST", base, "/tasks/" + gid("task", task) + "/removeTag", token, Map.of("tag", gid("tag", tag)));
        return Map.of();
    }

    // ---------------------------------------------------------------- users

    @Action(id = "list_users", name = "List users", category = "Users", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "List the people in a workspace.",
            panel = "token, workspace_id | max_results, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: gid, name, email.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of users returned.")
    public Map<String, Object> listUsers(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "workspace_id", type = OoaType.STRING, description = D_WS) String ws,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "2000", description = D_MAX) int max) {
        return result(pages(ctx, base, "/users?workspace=" + gid("workspace", ws) + "&opt_fields=name,email", token, max), "gid", "name", "email");
    }

    @Action(id = "get_user", name = "Get user", category = "Users", idempotent = true, capabilities = "network",
            description = "Read a person's name, email and workspaces; use me to check which account the token belongs to.",
            panel = "token, user | base_url")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "gid, name, email, workspaces (names).")
    @Output(name = "user_id", type = OoaType.STRING, description = "Numeric user id.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, required = false, defaultValue = DEF_BASE, pattern = "^https?://", description = D_BASE) String base,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "user", type = OoaType.STRING, required = false, defaultValue = "\"me\"", description = D_USER) String u) {
        Map<String, Object> d = data(ctx, "GET", base, "/users/" + HttpJson.enc(user(u)) + "?opt_fields=name,email,workspaces.name", token, null);
        return Out.of("user", Out.of("gid", d.get("gid"), "name", d.get("name"), "email", d.get("email"), "workspaces", names(d.get("workspaces"), "name")),
                "user_id", d.get("gid"));
    }
}
