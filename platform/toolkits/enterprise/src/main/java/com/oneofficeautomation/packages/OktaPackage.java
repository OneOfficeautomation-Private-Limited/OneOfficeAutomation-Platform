package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Okta Management API (v1): users, lifecycle changes and group membership. Sign-in with an API token (SSWS) or an OAuth access token. */
@OoaPackage(id = "ooa.okta", name = "Okta", version = "1.0.0",
        description = "Create and manage Okta users, change their status, reset passwords and manage group membership.")
public final class OktaPackage {
    static final String P = "OKTA";
    private static final Pattern OKTA_ID = Pattern.compile("^[0-9A-Za-z]{20}$");
    private static final String D_URL = "Your Okta address, e.g. https://yourcompany.okta.com.";
    private static final String D_TOKEN = "Okta API token from the vault. Leave empty when you use an access token instead.";
    private static final String D_ACCESS = "OAuth access token from the vault, used instead of an API token.";
    private static final String D_USER = "The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com.";
    private static final List<String> USER_COLS = List.of("id", "status", "login", "email", "first_name", "last_name", "created", "last_login");
    private static final List<String> GROUP_COLS = List.of("id", "name", "description", "type");

    private static String api(String org) { return org.replaceAll("/+$", "") + "/api/v1"; }

    private static HttpJson.Req req(String method, String url, Object apiToken, Object accessToken) {
        HttpJson.Req r = HttpJson.req(P, method, url);
        if (accessToken instanceof SecureString) return r.bearer(accessToken);
        if (apiToken instanceof SecureString) return r.scheme("SSWS", apiToken);
        throw new ActionException(P + ".NO_TOKEN", "An Okta API token or access token from the vault is required", "Map api_token (or access_token) to a Credential variable.");
    }

    private static String user(String u) {
        if (u == null || u.isBlank()) throw new ActionException(P + ".BAD_USER", "A user id or login is required", D_USER);
        return HttpJson.enc(u.trim());
    }

    static Map<String, Object> summarizeUser(Map<?, ?> u) {
        Map<?, ?> p = u.get("profile") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", u.get("id"));
        o.put("status", u.get("status"));
        o.put("login", p.get("login"));
        o.put("email", p.get("email"));
        o.put("first_name", p.get("firstName"));
        o.put("last_name", p.get("lastName"));
        o.put("created", u.get("created"));
        o.put("last_login", u.get("lastLogin"));
        return o;
    }

    private static List<Object> userRow(Map<?, ?> u) { return new ArrayList<>(summarizeUser(u).values()); }

    private static List<Object> groupRow(Map<?, ?> g) {
        Map<?, ?> p = g.get("profile") instanceof Map<?, ?> m ? m : Map.of();
        return new ArrayList<>(Arrays.asList(g.get("id"), p.get("name"), p.get("description"), g.get("type")));
    }

    /** Reads every page by following the Link rel="next" header. */
    private static List<Map<?, ?>> pages(ActionContext ctx, String first, int max, Object apiToken, Object accessToken) {
        List<Map<?, ?>> out = new ArrayList<>();
        String url = first;
        while (url != null && out.size() < max) {
            HttpJson.Response r = req("GET", url, apiToken, accessToken).timeout(120).send(ctx);
            if (!(r.json() instanceof List<?> l)) break;
            for (Object o : l) { if (o instanceof Map<?, ?> m) out.add(m); if (out.size() >= max) break; }
            url = l.isEmpty() ? null : HttpJson.nextLink(r);
        }
        return out;
    }

    private static String groupId(ActionContext ctx, String org, String group, Object apiToken, Object accessToken) {
        if (group == null || group.isBlank()) throw new ActionException(P + ".BAD_GROUP", "A group id or name is required", "Use the group's id or its exact name.");
        String g = group.trim();
        if (OKTA_ID.matcher(g).matches()) return g;
        List<Map<?, ?>> found = pages(ctx, api(org) + "/groups?" + HttpJson.query(Map.of("q", g, "limit", 200)), 1000, apiToken, accessToken);
        for (Map<?, ?> m : found) if (m.get("profile") instanceof Map<?, ?> p && g.equalsIgnoreCase(String.valueOf(p.get("name")))) return String.valueOf(m.get("id"));
        throw new ActionException(P + ".GROUP_NOT_FOUND", "No Okta group is named '" + g + "'", "Check the spelling, or use the group's id.");
    }

    private Map<String, Object> lifecycle(ActionContext ctx, String org, String u, String op, Map<String, Object> q, Object apiToken, Object accessToken) {
        String url = api(org) + "/users/" + user(u) + "/lifecycle/" + op + (q.isEmpty() ? "" : "?" + HttpJson.query(q));
        return req("POST", url, apiToken, accessToken).send(ctx).map();
    }

    // ----- users -----

    @Action(id = "create_user", name = "Create user", category = "Users", capabilities = "network",
            description = "Add a new person to Okta, optionally with a starting password and group memberships.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), login, password, email, first_name, last_name, activate | profile_fields, group_ids")
    @Output(name = "user_id", type = OoaType.STRING, description = "Okta id of the new user.")
    @Output(name = "status", type = OoaType.STRING, description = "Account status, e.g. ACTIVE, PROVISIONED or STAGED.")
    public Map<String, Object> createUser(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "first_name", type = OoaType.STRING, description = "Given name.") String first,
            @Input(name = "last_name", type = OoaType.STRING, description = "Family name.") String last,
            @Input(name = "email", type = OoaType.STRING, example = "ann.lee@acme.com", description = "Primary email address.") String email,
            @Input(name = "login", type = OoaType.STRING, required = false, description = "Sign-in name. Leave empty to use the email address.") String login,
            @Input(name = "profile_fields", type = OoaType.DICTIONARY, required = false, description = "More profile values, e.g. {\"department\": \"Finance\", \"mobilePhone\": \"+1 555 0100\"}.") Map<String, Object> extra,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Starting password from the vault. Leave empty to let the person set one when they activate.") Object password,
            @Input(name = "group_ids", type = OoaType.LIST, required = false, description = "Ids of groups the person should join straight away.") List<Object> groups,
            @Input(name = "activate", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Activate the account now. When off, the account is created in a staged state.") boolean activate,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("firstName", first);
        profile.put("lastName", last);
        profile.put("email", email);
        profile.put("login", login == null || login.isBlank() ? email : login);
        if (extra != null) profile.putAll(extra);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", profile);
        if (password instanceof SecureString s) body.put("credentials", Map.of("password", Map.of("value", s.reveal())));
        if (groups != null && !groups.isEmpty()) { List<String> ids = new ArrayList<>(); for (Object g : groups) ids.add(Values.text(g)); body.put("groupIds", ids); }
        Map<String, Object> r = req("POST", api(org) + "/users?activate=" + activate, apiToken, accessToken).json(body).send(ctx).map();
        return Out.of("user_id", r.get("id"), "status", r.get("status"));
    }

    @Action(id = "get_user", name = "Get user", category = "Users", idempotent = true, capabilities = "network", description = "Look up one person by Okta id or sign-in name.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "id, status, login, email, first_name, last_name, created, last_login.")
    @Output(name = "profile", type = OoaType.DICTIONARY, description = "All profile values held in Okta.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        Map<String, Object> r = req("GET", api(org) + "/users/" + user(u), apiToken, accessToken).send(ctx).map();
        return Out.of("user", summarizeUser(r), "profile", r.get("profile") instanceof Map<?, ?> m ? m : new LinkedHashMap<>());
    }

    @Action(id = "update_user", name = "Update user profile", category = "Users", capabilities = "network",
            description = "Change selected profile values; values you leave out stay as they are.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user, profile_fields")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "The user after the change.")
    public Map<String, Object> updateUser(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "profile_fields", type = OoaType.DICTIONARY, description = "Profile values to change, e.g. {\"title\": \"Team Lead\", \"department\": \"Sales\"}.") Map<String, Object> fields,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        if (fields == null || fields.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No profile values were given", "Fill profile_fields with at least one value.");
        Map<String, Object> r = req("POST", api(org) + "/users/" + user(u), apiToken, accessToken).json(Map.of("profile", fields)).send(ctx).map();
        return Out.of("user", summarizeUser(r));
    }

    @Action(id = "activate_user", name = "Activate user", category = "User status", capabilities = "network",
            description = "Activate a staged or newly created account.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user, send_email")
    @Output(name = "activation_url", type = OoaType.STRING, description = "One-time activation link when no email is sent; empty otherwise.")
    public Map<String, Object> activate(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "send_email", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Email the activation link to the person. Turn off to get the link back instead.") boolean sendEmail,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        Map<String, Object> r = lifecycle(ctx, org, u, "activate", Map.of("sendEmail", sendEmail), apiToken, accessToken);
        return Out.of("activation_url", r.get("activationUrl"));
    }

    @Action(id = "deactivate_user", name = "Deactivate user", category = "User status", capabilities = "network",
            description = "Turn off an account so the person can no longer sign in. Their app access is removed.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user | send_email")
    public Map<String, Object> deactivate(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "send_email", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Tell the organization's admins by email.") boolean sendEmail,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        lifecycle(ctx, org, u, "deactivate", Map.of("sendEmail", sendEmail), apiToken, accessToken);
        return Map.of();
    }

    @Action(id = "suspend_user", name = "Suspend user", category = "User status", capabilities = "network",
            description = "Block sign-in for an active account for now, keeping its app assignments.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user")
    public Map<String, Object> suspend(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        lifecycle(ctx, org, u, "suspend", Map.of(), apiToken, accessToken);
        return Map.of();
    }

    @Action(id = "unsuspend_user", name = "Unsuspend user", category = "User status", capabilities = "network",
            description = "Let a suspended person sign in again.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user")
    public Map<String, Object> unsuspend(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        lifecycle(ctx, org, u, "unsuspend", Map.of(), apiToken, accessToken);
        return Map.of();
    }

    @Action(id = "delete_user", name = "Delete user", category = "Users", capabilities = "network",
            description = "Remove a person from Okta for good. An account that is still active is deactivated first.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user")
    public Map<String, Object> deleteUser(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        Map<String, Object> current = req("GET", api(org) + "/users/" + user(u), apiToken, accessToken).send(ctx).map();
        String id = String.valueOf(current.get("id"));
        if (!"DEPROVISIONED".equals(current.get("status"))) lifecycle(ctx, org, id, "deactivate", Map.of("sendEmail", false), apiToken, accessToken);
        req("DELETE", api(org) + "/users/" + user(id) + "?sendEmail=false", apiToken, accessToken).send(ctx);
        return Map.of();
    }

    @Action(id = "reset_password", name = "Reset password", category = "User status", capabilities = "network",
            description = "Start a password reset. The person gets an email, or you get a one-time reset link to pass on.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user, send_email")
    @Output(name = "reset_url", type = OoaType.STRING, description = "One-time reset link when no email is sent; empty otherwise.")
    public Map<String, Object> resetPassword(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "send_email", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Email the reset link to the person. Turn off to get the link back instead.") boolean sendEmail,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        Map<String, Object> r = lifecycle(ctx, org, u, "reset_password", Map.of("sendEmail", sendEmail), apiToken, accessToken);
        return Out.of("reset_url", r.get("resetPasswordUrl"));
    }

    @Action(id = "list_users", name = "List users", category = "Users", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return people that match a quick text search or an Okta filter expression.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), (Users: All users = / Name or email starts with = search_text / Matching a search expression = filter) | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, status, login, email, first_name, last_name, created, last_login.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of users returned.")
    public Map<String, Object> listUsers(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "search_text", type = OoaType.STRING, required = false, description = "Match the start of first name, last name or email, e.g. ann.") String q,
            @Input(name = "filter", type = OoaType.STRING, required = false, example = "status eq \"ACTIVE\"", description = "Okta search expression, used instead of search_text, e.g. profile.department eq \"Finance\".") String filter,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most users to return.") int max,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (filter != null && !filter.isBlank()) params.put("search", filter);
        else if (q != null && !q.isBlank()) params.put("q", q);
        params.put("limit", Math.max(1, Math.min(200, max)));
        List<Object> rows = new ArrayList<>();
        for (Map<?, ?> u : pages(ctx, api(org) + "/users?" + HttpJson.query(params), max, apiToken, accessToken)) rows.add(userRow(u));
        return Out.of("table", Values.table(USER_COLS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    // ----- groups -----

    public Map<String, Object> listGroups(ActionContext ctx, String org, String q, int max, Object apiToken, Object accessToken) {
        return listGroups(ctx, org, q, max, apiToken, accessToken, null);
    }

    @Action(id = "list_groups", name = "List groups", category = "Groups", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return groups, optionally only those whose name starts with some text or that match a search expression.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), (Groups: All groups = / Name starts with = name_starts_with / Search expression = search) | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, description, type.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of groups returned.")
    public Map<String, Object> listGroups(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "name_starts_with", type = OoaType.STRING, required = false, description = "Only groups whose name starts with this text.") String q,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most groups to return.") int max,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken,
            @Input(name = "search", type = OoaType.STRING, required = false, example = "profile.name eq \"Finance\"",
                    description = "Okta search expression on group properties, e.g. profile.name eq \"Finance\" for an exact name, or type eq \"APP_GROUP\". Used instead of name_starts_with.") String search) {
        boolean hasQ = q != null && !q.isBlank(), hasSearch = search != null && !search.isBlank();
        if (hasQ && hasSearch) throw new ActionException(P + ".BAD_INPUT", "Give either name_starts_with or search, not both", "Clear one of the two inputs.");
        Map<String, Object> params = new LinkedHashMap<>();
        if (hasQ) params.put("q", q);
        if (hasSearch) params.put("search", search.trim());
        params.put("limit", Math.max(1, Math.min(200, max)));
        List<Object> rows = new ArrayList<>();
        for (Map<?, ?> g : pages(ctx, api(org) + "/groups?" + HttpJson.query(params), max, apiToken, accessToken)) rows.add(groupRow(g));
        return Out.of("table", Values.table(GROUP_COLS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "add_user_to_group", name = "Add user to group", category = "Groups", idempotent = true, capabilities = "network",
            description = "Make a person a member of a group. Nothing changes if they are already a member.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), group, user")
    public Map<String, Object> addToGroup(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "group", type = OoaType.STRING, description = "Group id, or the group's exact name.") String group,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        String uid = String.valueOf(req("GET", api(org) + "/users/" + user(u), apiToken, accessToken).send(ctx).map().get("id"));
        req("PUT", api(org) + "/groups/" + groupId(ctx, org, group, apiToken, accessToken) + "/users/" + user(uid), apiToken, accessToken).send(ctx);
        return Map.of();
    }

    @Action(id = "remove_user_from_group", name = "Remove user from group", category = "Groups", capabilities = "network",
            description = "Take a person out of a group.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), group, user")
    public Map<String, Object> removeFromGroup(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "group", type = OoaType.STRING, description = "Group id, or the group's exact name.") String group,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        String uid = String.valueOf(req("GET", api(org) + "/users/" + user(u), apiToken, accessToken).send(ctx).map().get("id"));
        req("DELETE", api(org) + "/groups/" + groupId(ctx, org, group, apiToken, accessToken) + "/users/" + user(uid), apiToken, accessToken).send(ctx);
        return Map.of();
    }

    @Action(id = "list_user_groups", name = "List user's groups", category = "Groups", idempotent = true, capabilities = "network",
            description = "Return every group a person belongs to.",
            panel = "org_url, (Sign in with: An API token = api_token / An access token = access_token), user")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, description, type.")
    @Output(name = "names", type = OoaType.LIST, description = "Group names only.")
    public Map<String, Object> userGroups(ActionContext ctx,
            @Input(name = "org_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String org,
            @Input(name = "user", type = OoaType.STRING, description = D_USER) String u,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_ACCESS) Object accessToken) {
        List<Object> rows = new ArrayList<>();
        List<Object> names = new ArrayList<>();
        for (Map<?, ?> g : pages(ctx, api(org) + "/users/" + user(u) + "/groups?limit=200", Integer.MAX_VALUE, apiToken, accessToken)) {
            List<Object> row = groupRow(g);
            rows.add(row);
            names.add(row.get(1));
        }
        return Out.of("table", Values.table(GROUP_COLS, rows), "names", names);
    }
}
