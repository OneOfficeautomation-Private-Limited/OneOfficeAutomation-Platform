package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Zendesk Support REST API (v2). Sign-in with an agent email plus API token (sent as "email/token"), or an OAuth token.
 * Lists use cursor paging (links.next) and fall back to offset paging (next_page), both only within the same site.
 */
@OoaPackage(id = "ooa.zendesk", name = "Zendesk", version = "1.0.0",
        description = "Handle Zendesk Support tickets, comments, users, organizations, groups, attachments and views.")
public final class ZendeskPackage {
    static final String P = "ZENDESK";
    private static final Pattern ID = Pattern.compile("^\\d{1,20}$");
    private static final Pattern SUBDOMAIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,62}$");
    private static final String D_SITE = "Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme.";
    private static final String D_EMAIL = "Email of the agent the API token belongs to. Leave empty when using an OAuth token.";
    private static final String D_TOKEN = "Zendesk API token from the vault (used with email).";
    private static final String D_OAUTH = "OAuth access token from the vault, instead of email and API token.";
    private static final String D_TICKET = "Ticket number, e.g. 35436.";
    private static final String D_MAX = "Most items to return; pages are read until this many are found.";
    private static final String[] TICKET_COLS = {"id", "subject", "status", "priority", "type", "requester_id", "assignee_id", "group_id", "updated_at"};

    static String site(String s) {
        if (s == null || s.isBlank()) throw new ActionException(P + ".BAD_URL", "The Zendesk address is empty", D_SITE);
        String t = s.strip().replaceAll("/+$", "");
        if (SUBDOMAIN.matcher(t).matches()) return "https://" + t.toLowerCase(Locale.ROOT) + ".zendesk.com";
        if (!t.matches("(?i)^https?://[^/\\s]+$")) throw new ActionException(P + ".BAD_URL", "Invalid Zendesk address '" + s + "'", D_SITE);
        return t;
    }
    static String api(String s) { return site(s) + "/api/v2"; }
    static String id(String what, Object v) {
        String s = v == null ? "" : Values.text(v).strip();
        if (!ID.matcher(s).matches()) throw new ActionException(P + ".BAD_ID", "Invalid " + what + " id '" + s + "'", "Use the numeric id, e.g. 35436.");
        return s;
    }

    static HttpJson.Req req(String method, String url, String email, Object apiToken, Object oauth) {
        HttpJson.Req r = HttpJson.req(P, method, url);
        if (oauth instanceof SecureString) return r.bearer(oauth);
        if (email == null || email.isBlank())
            throw new ActionException(P + ".NO_EMAIL", "An agent email is needed with an API token", "Fill in email, or use oauth_token instead.");
        return r.basic(email.strip() + "/token", apiToken);
    }

    static Map<String, Object> call(ActionContext ctx, String method, String url, String email, Object apiToken, Object oauth, Object body) {
        HttpJson.Req r = req(method, url, email, apiToken, oauth);
        if (body != null) r.json(body);
        return r.send(ctx).map();
    }

    /** Reads list pages, following links.next (cursor) or next_page (offset) on the same site. */
    static List<Map<String, Object>> pages(ActionContext ctx, String site, String path, String key, int max, String email, Object apiToken, Object oauth, boolean cursor) {
        List<Map<String, Object>> out = new ArrayList<>();
        String url = api(site) + path + (cursor ? (path.contains("?") ? "&" : "?") + "page%5Bsize%5D=" + Math.max(1, Math.min(100, max)) : "");
        while (url != null && out.size() < max) {
            Map<String, Object> r = req("GET", url, email, apiToken, oauth).timeout(120).send(ctx).map();
            List<Map<String, Object>> items = HttpJson.maps(r.get(key));
            for (Map<String, Object> m : items) { out.add(m); if (out.size() >= max) break; }
            Object next = Boolean.TRUE.equals(HttpJson.path(r, "meta.has_more")) ? HttpJson.path(r, "links.next") : r.get("next_page");
            url = next instanceof String n && !n.isBlank() && !items.isEmpty() && HttpJson.sameOrigin(site(site), n) ? n : null;
        }
        return out;
    }

    static Map<String, Object> result(List<Map<String, Object>> recs, String... cols) {
        return Out.of("table", HttpJson.table(recs, cols), "count", BigDecimal.valueOf(recs.size()));
    }

    static List<Map<String, Object>> customFields(Map<String, Object> custom) {
        List<Map<String, Object>> out = new ArrayList<>();
        custom.forEach((k, v) -> out.add(Map.of("id", new BigDecimal(id("custom field", k)), "value", v == null ? "" : v)));
        return out;
    }

    static Map<String, Object> ticket(String site, Map<?, ?> t) {
        Map<String, Object> custom = new LinkedHashMap<>();
        for (Map<String, Object> cf : HttpJson.maps(t.get("custom_fields"))) custom.put(Values.text(cf.get("id")), cf.get("value"));
        return Out.of("id", t.get("id"), "subject", t.get("subject"), "description", t.get("description"), "status", t.get("status"),
                "priority", t.get("priority"), "type", t.get("type"), "requester_id", t.get("requester_id"), "assignee_id", t.get("assignee_id"),
                "group_id", t.get("group_id"), "organization_id", t.get("organization_id"), "tags", t.get("tags"), "custom_fields", custom,
                "created_at", t.get("created_at"), "updated_at", t.get("updated_at"), "url", site(site) + "/agent/tickets/" + Values.text(t.get("id")));
    }

    // ---------------------------------------------------------------- tickets

    @Action(id = "create_ticket", name = "Create ticket", category = "Tickets", capabilities = "network",
            description = "Open a ticket with a first comment, requester, priority, tags and custom fields.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), subject, description, priority, requester_email, custom_fields | requester_name, type, status, assignee_id, group_id, tags, public, upload_tokens")
    @Output(name = "ticket_id", type = OoaType.NUMBER, description = "Number of the new ticket.")
    @Output(name = "url", type = OoaType.STRING, description = "Agent web address of the ticket.")
    public Map<String, Object> createTicket(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "subject", type = OoaType.STRING, example = "Refund not received for order 88123", description = "Ticket subject.") String subject,
            @Input(name = "description", type = OoaType.STRING, description = "Text of the first comment.") String description,
            @Input(name = "requester_email", type = OoaType.STRING, required = false, example = "pat@customer.com", description = "Email of the person asking; a user is created if needed. Empty means the signed-in agent.") String requesterEmail,
            @Input(name = "requester_name", type = OoaType.STRING, required = false, description = "Name to use when a new requester is created.") String requesterName,
            @Input(name = "priority", type = OoaType.STRING, required = false, enumValues = {"low", "normal", "high", "urgent"}, description = "low, normal, high or urgent.") String priority,
            @Input(name = "type", type = OoaType.STRING, required = false, enumValues = {"problem", "incident", "question", "task"}, description = "problem, incident, question or task.") String type,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "open", "pending", "hold", "solved"}, description = "Starting status, e.g. new or open.") String status,
            @Input(name = "assignee_id", type = OoaType.NUMBER, required = false, description = "Agent user id to assign.") String assignee,
            @Input(name = "group_id", type = OoaType.NUMBER, required = false, description = "Group id to assign, from List groups.") String group,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Tags, e.g. [\"refund\", \"agent\"].") Object tags,
            @Input(name = "custom_fields", type = OoaType.DICTIONARY, required = false, description = "Custom field id to value, e.g. {\"360012345678\": \"order_88123\"}. Ids come from List ticket fields.") Map<String, Object> custom,
            @Input(name = "public", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Whether the first comment is visible to the requester.") boolean isPublic,
            @Input(name = "upload_tokens", type = OoaType.LIST, required = false, description = "Tokens from Upload attachment, to attach files to the first comment.") Object uploads) {
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("body", description);
        comment.put("public", isPublic);
        if (uploads != null) comment.put("uploads", HttpJson.addressList(uploads));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("subject", subject);
        t.put("comment", comment);
        if (requesterEmail != null && !requesterEmail.isBlank()) {
            Map<String, Object> rq = new LinkedHashMap<>();
            rq.put("email", requesterEmail.strip());
            rq.put("name", requesterName == null || requesterName.isBlank() ? requesterEmail.strip() : requesterName);
            t.put("requester", rq);
        }
        if (priority != null) t.put("priority", priority);
        if (type != null) t.put("type", type);
        if (status != null) t.put("status", status);
        if (assignee != null && !assignee.isBlank()) t.put("assignee_id", new BigDecimal(id("assignee", assignee)));
        if (group != null && !group.isBlank()) t.put("group_id", new BigDecimal(id("group", group)));
        if (tags != null) t.put("tags", HttpJson.addressList(tags));
        if (custom != null && !custom.isEmpty()) t.put("custom_fields", customFields(custom));
        Map<String, Object> r = call(ctx, "POST", api(site) + "/tickets", email, apiToken, oauth, Map.of("ticket", t));
        Object tid = HttpJson.path(r, "ticket.id");
        return Out.of("ticket_id", tid, "url", site(site) + "/agent/tickets/" + Values.text(tid));
    }

    @Action(id = "get_ticket", name = "Get ticket", category = "Tickets", idempotent = true, capabilities = "network", description = "Read a ticket's fields.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), ticket_id")
    @Output(name = "ticket", type = OoaType.DICTIONARY, description = "id, subject, description, status, priority, type, requester_id, assignee_id, group_id, organization_id, tags, custom_fields (id to value), created_at, updated_at, url.")
    @Output(name = "status", type = OoaType.STRING, description = "new, open, pending, hold, solved or closed.")
    public Map<String, Object> getTicket(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "ticket_id", type = OoaType.NUMBER, description = D_TICKET) String ticket) {
        Map<?, ?> t = (Map<?, ?>) call(ctx, "GET", api(site) + "/tickets/" + id("ticket", ticket), email, apiToken, oauth, null).getOrDefault("ticket", Map.of());
        Map<String, Object> o = ticket(site, t);
        return Out.of("ticket", o, "status", o.get("status"));
    }

    @Action(id = "update_ticket", name = "Update ticket", category = "Tickets", capabilities = "network",
            description = "Change a ticket's status, priority, assignee, group, tags or custom fields. Only the inputs you fill in are changed.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), ticket_id, status, priority, assignee_id, custom_fields | type, subject, group_id, tags")
    public Map<String, Object> updateTicket(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "ticket_id", type = OoaType.NUMBER, description = D_TICKET) String ticket,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "open", "pending", "hold", "solved", "closed"}, description = "New status.") String status,
            @Input(name = "priority", type = OoaType.STRING, required = false, enumValues = {"low", "normal", "high", "urgent"}, description = "New priority.") String priority,
            @Input(name = "type", type = OoaType.STRING, required = false, enumValues = {"problem", "incident", "question", "task"}, description = "New type.") String type,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = "New subject.") String subject,
            @Input(name = "assignee_id", type = OoaType.NUMBER, required = false, description = "Agent user id to assign.") String assignee,
            @Input(name = "group_id", type = OoaType.NUMBER, required = false, description = "Group id to assign.") String group,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Replace all tags with these. To add tags without removing others use Add tags.") Object tags,
            @Input(name = "custom_fields", type = OoaType.DICTIONARY, required = false, description = "Custom field id to new value.") Map<String, Object> custom) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (status != null) t.put("status", status);
        if (priority != null) t.put("priority", priority);
        if (type != null) t.put("type", type);
        if (subject != null) t.put("subject", subject);
        if (assignee != null && !assignee.isBlank()) t.put("assignee_id", new BigDecimal(id("assignee", assignee)));
        if (group != null && !group.isBlank()) t.put("group_id", new BigDecimal(id("group", group)));
        if (tags != null) t.put("tags", HttpJson.addressList(tags));
        if (custom != null && !custom.isEmpty()) t.put("custom_fields", customFields(custom));
        if (t.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No changes were given", "Fill in at least one field to change.");
        call(ctx, "PUT", api(site) + "/tickets/" + id("ticket", ticket), email, apiToken, oauth, Map.of("ticket", t));
        return Map.of();
    }

    @Action(id = "delete_ticket", name = "Delete ticket", category = "Tickets", capabilities = "network",
            description = "Delete a ticket. Admins can restore deleted tickets for about 30 days.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), ticket_id")
    public Map<String, Object> deleteTicket(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "ticket_id", type = OoaType.NUMBER, description = D_TICKET) String ticket) {
        call(ctx, "DELETE", api(site) + "/tickets/" + id("ticket", ticket), email, apiToken, oauth, null);
        return Map.of();
    }

    public Map<String, Object> listTickets(ActionContext ctx, String site, String email, Object apiToken, Object oauth, String requester, String org, int max) {
        return listTickets(ctx, site, email, apiToken, oauth, requester, org, max, null, null, null);
    }

    @Action(id = "list_tickets", name = "List tickets", category = "Tickets", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List all tickets, or only those requested by one user (by id or email) or belonging to one organization, optionally only with a given status or priority.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), (Requested by: Anyone = / A user id = requester_id / An email address = requester_email), organization_id, status, priority | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, subject, status, priority, type, requester_id, assignee_id, group_id, updated_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tickets returned.")
    public Map<String, Object> listTickets(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "requester_id", type = OoaType.NUMBER, required = false, description = "Only tickets requested by this user id.") String requester,
            @Input(name = "organization_id", type = OoaType.NUMBER, required = false, description = "Only tickets of this organization id.") String org,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_MAX) int max,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "open", "pending", "hold", "solved", "closed"},
                    description = "Only tickets with this status.") String status,
            @Input(name = "priority", type = OoaType.STRING, required = false, enumValues = {"low", "normal", "high", "urgent"},
                    description = "Only tickets with this priority.") String priority,
            @Input(name = "requester_email", type = OoaType.STRING, required = false, example = "pat@customer.com",
                    description = "Only tickets requested by the user with this email address; used instead of requester_id.") String requesterEmail) {
        String st = blank(status) ? null : choice("status", status, "new", "open", "pending", "hold", "solved", "closed");
        String pr = blank(priority) ? null : choice("priority", priority, "low", "normal", "high", "urgent");
        if (!blank(requesterEmail)) {
            if (!blank(requester)) throw new ActionException("ZENDESK.BAD_INPUT", "Both requester_id and requester_email were given", "Give the requester by id or by email, not both.");
            if (requesterEmail.matches(".*[\\s\"].*") || !requesterEmail.contains("@"))
                throw new ActionException("ZENDESK.BAD_INPUT", "requester_email is not an email address: " + requesterEmail, "Give an address such as pat@customer.com.");
            // the requester by email is found with search; the other filters become search terms
            StringBuilder terms = new StringBuilder("type:ticket requester:" + requesterEmail.strip());
            if (st != null) terms.append(" status:").append(st);
            if (pr != null) terms.append(" priority:").append(pr);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("query", terms.toString());
            q.put("sort_by", "updated_at");
            q.put("sort_order", "desc");
            List<Map<String, Object>> found = pages(ctx, site, "/search?" + HttpJson.query(q), "results", blank(org) ? max : SCAN_LIMIT, email, apiToken, oauth, false);
            if (!blank(org)) {
                String o = id("organization", org);
                found = new ArrayList<>(found.stream().filter(t -> o.equals(Values.text(t.get("organization_id")))).limit(Math.max(0, max)).toList());
            }
            return result(found, TICKET_COLS);
        }
        String path = !blank(requester) ? "/users/" + id("requester", requester) + "/tickets/requested"
                : !blank(org) ? "/organizations/" + id("organization", org) + "/tickets" : "/tickets";
        List<Map<String, Object>> recs = pages(ctx, site, path, "tickets", st == null && pr == null ? max : SCAN_LIMIT, email, apiToken, oauth, true);
        // status and priority are not filters of these lists, so the tickets are filtered here, up to max_results
        if (st != null || pr != null) {
            recs = new ArrayList<>(recs.stream().filter(t -> (st == null || st.equals(String.valueOf(t.get("status"))))
                    && (pr == null || pr.equals(String.valueOf(t.get("priority"))))).limit(Math.max(0, max)).toList());
        }
        return result(recs, TICKET_COLS);
    }

    /** Most tickets read to filter by status, priority or organization on this side. */
    private static final int SCAN_LIMIT = 10_000;

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String choice(String name, String value, String... allowed) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (!Arrays.asList(allowed).contains(v))
            throw new ActionException("ZENDESK.BAD_INPUT", name + " must be one of " + String.join(", ", allowed) + ", not '" + value + "'", "Pick one of the listed values.");
        return v;
    }

    @Action(id = "search_tickets", name = "Search tickets", category = "Tickets", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Find tickets with Zendesk search terms, e.g. status<solved priority:urgent tags:refund. Search returns at most 1000 results.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), query | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, subject, status, priority, type, requester_id, assignee_id, group_id, updated_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tickets returned.")
    public Map<String, Object> searchTickets(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "query", type = OoaType.STRING, example = "status<solved requester:pat@customer.com", description = "Search terms; type:ticket is added for you.") String query,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("query", "type:ticket " + query);
        q.put("sort_by", "updated_at");
        q.put("sort_order", "desc");
        return result(pages(ctx, site, "/search?" + HttpJson.query(q), "results", max, email, apiToken, oauth, false), TICKET_COLS);
    }

    @Action(id = "add_comment", name = "Add comment", category = "Tickets", capabilities = "network",
            description = "Reply to a ticket with a public comment or add an internal note, optionally changing its status.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), ticket_id, text, public, upload_tokens | html, new_status")
    public Map<String, Object> addComment(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "ticket_id", type = OoaType.NUMBER, description = D_TICKET) String ticket,
            @Input(name = "text", type = OoaType.STRING, example = "Your refund was issued today.", description = "Comment text.") String text,
            @Input(name = "public", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "true for a reply the requester sees, false for an internal note.") boolean isPublic,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat the text as HTML.") boolean html,
            @Input(name = "new_status", type = OoaType.STRING, required = false, enumValues = {"open", "pending", "hold", "solved"}, description = "Also set this status, e.g. pending or solved.") String status,
            @Input(name = "upload_tokens", type = OoaType.LIST, required = false, description = "Tokens from Upload attachment, to attach files to this comment.") Object uploads) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put(html ? "html_body" : "body", text);
        c.put("public", isPublic);
        if (uploads != null) c.put("uploads", HttpJson.addressList(uploads));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("comment", c);
        if (status != null) t.put("status", status);
        call(ctx, "PUT", api(site) + "/tickets/" + id("ticket", ticket), email, apiToken, oauth, Map.of("ticket", t));
        return Map.of();
    }

    @Action(id = "list_comments", name = "List comments", category = "Tickets", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Read a ticket's conversation, oldest first, including internal notes.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), ticket_id | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, author_id, body, public, created_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of comments.")
    public Map<String, Object> listComments(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "ticket_id", type = OoaType.NUMBER, description = D_TICKET) String ticket,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_MAX) int max) {
        return result(pages(ctx, site, "/tickets/" + id("ticket", ticket) + "/comments", "comments", max, email, apiToken, oauth, true),
                "id", "author_id", "body=plain_body", "public", "created_at");
    }

    @Action(id = "add_tags", name = "Add tags", category = "Tickets", capabilities = "network", description = "Add tags to a ticket and keep the ones it already has.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), ticket_id, tags")
    @Output(name = "tags", type = OoaType.LIST, description = "All tags on the ticket afterwards.")
    public Map<String, Object> addTags(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "ticket_id", type = OoaType.NUMBER, description = D_TICKET) String ticket,
            @Input(name = "tags", type = OoaType.LIST, description = "Tags to add, e.g. [\"vip\", \"escalated\"].") Object tags) {
        Map<String, Object> r = call(ctx, "PUT", api(site) + "/tickets/" + id("ticket", ticket) + "/tags", email, apiToken, oauth, Map.of("tags", HttpJson.addressList(tags)));
        return Out.of("tags", r.getOrDefault("tags", List.of()));
    }

    // ---------------------------------------------------------------- users

    @Action(id = "create_user", name = "Create user", category = "Users", capabilities = "network",
            description = "Create an end user or agent, or update the existing one with the same email when asked to.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), name, user_email, role, user_fields | organization_id, phone, external_id, update_if_exists")
    @Output(name = "user_id", type = OoaType.NUMBER, description = "Id of the user.")
    public Map<String, Object> createUser(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "name", type = OoaType.STRING, example = "Pat Kim", description = "Full name.") String userName,
            @Input(name = "user_email", type = OoaType.STRING, required = false, example = "pat@customer.com", description = "Email of the new user.") String userEmail,
            @Input(name = "role", type = OoaType.STRING, required = false, defaultValue = "\"end-user\"", enumValues = {"end-user", "agent", "admin"}, description = "end-user, agent or admin.") String role,
            @Input(name = "organization_id", type = OoaType.NUMBER, required = false, description = "Organization id to put the user in.") String org,
            @Input(name = "phone", type = OoaType.STRING, required = false, description = "Phone number.") String phone,
            @Input(name = "external_id", type = OoaType.STRING, required = false, description = "Your own id for the user, e.g. a CRM customer number.") String external,
            @Input(name = "update_if_exists", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Update the existing user with this email or external id instead of failing.") boolean upsert,
            @Input(name = "user_fields", type = OoaType.DICTIONARY, required = false, description = "Custom user fields by field key, e.g. {\"customer_tier\": \"gold\"}.") Map<String, Object> fields) {
        checkFieldKeys(fields, "user_fields");
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", userName);
        if (userEmail != null && !userEmail.isBlank()) u.put("email", userEmail.strip());
        u.put("role", role);
        if (org != null && !org.isBlank()) u.put("organization_id", new BigDecimal(id("organization", org)));
        if (phone != null) u.put("phone", phone);
        if (external != null) u.put("external_id", external);
        if (fields != null && !fields.isEmpty()) u.put("user_fields", fields);
        Map<String, Object> r = call(ctx, "POST", api(site) + (upsert ? "/users/create_or_update" : "/users"), email, apiToken, oauth, Map.of("user", u));
        return Out.of("user_id", HttpJson.path(r, "user.id"));
    }

    /** Earlier signature, kept for callers written before user_fields. */
    public Map<String, Object> createUser(ActionContext ctx, String site, String email, Object apiToken, Object oauth, String userName, String userEmail,
                                          String role, String org, String phone, String external, boolean upsert) {
        return createUser(ctx, site, email, apiToken, oauth, userName, userEmail, role, org, phone, external, upsert, null);
    }

    /** Custom field keys are names such as customer_tier; anything else is a mistake worth reporting before the request. */
    private static void checkFieldKeys(Map<String, Object> fields, String input) {
        if (fields == null) return;
        for (String k : fields.keySet())
            if (k == null || !k.matches("^[A-Za-z0-9_-]{1,64}$"))
                throw new ActionException("ZENDESK.BAD_INPUT", input + " has an invalid field key '" + k + "'", "Use the field keys shown in the Zendesk admin settings, e.g. customer_tier.");
    }

    @Action(id = "find_users", name = "Find users", category = "Users", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Find users by name, email or other search terms, optionally only with a given role or in a given organization.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), query, role, organization_id | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, email, role, organization_id, created_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of users found.")
    public Map<String, Object> findUsers(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "query", type = OoaType.STRING, required = false, example = "pat@customer.com", description = "Name, email, or search terms such as role:agent; may be empty when role or organization_id is given.") String query,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = D_MAX) int max,
            @Input(name = "role", type = OoaType.STRING, required = false, enumValues = {"end-user", "agent", "admin"}, description = "Only users with this role.") String role,
            @Input(name = "organization_id", type = OoaType.NUMBER, required = false, description = "Only users of this organization id.") String org) {
        StringBuilder terms = new StringBuilder(blank(query) ? "" : query.strip());
        if (!blank(role)) terms.append(terms.length() > 0 ? " " : "").append("role:").append(choice("role", role, "end-user", "agent", "admin"));
        if (!blank(org)) terms.append(terms.length() > 0 ? " " : "").append("organization:").append(id("organization", org));
        if (terms.length() == 0) throw new ActionException("ZENDESK.BAD_INPUT", "No search terms were given", "Fill in query, role or organization_id.");
        return result(pages(ctx, site, "/users/search?" + HttpJson.query(Map.of("query", terms.toString())), "users", max, email, apiToken, oauth, false),
                "id", "name", "email", "role", "organization_id", "created_at");
    }

    /** Earlier signature, kept for callers written before the role and organization filters. */
    public Map<String, Object> findUsers(ActionContext ctx, String site, String email, Object apiToken, Object oauth, String query, int max) {
        return findUsers(ctx, site, email, apiToken, oauth, query, max, null, null);
    }

    @Action(id = "get_user", name = "Get user", category = "Users", idempotent = true, capabilities = "network", description = "Read a user's details.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), user_id")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "id, name, email, role, phone, organization_id, external_id, time_zone, suspended, created_at, user_fields.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "user_id", type = OoaType.NUMBER, description = "User id.") String user) {
        Map<String, Object> u = call(ctx, "GET", api(site) + "/users/" + id("user", user), email, apiToken, oauth, null);
        Object d = u.get("user");
        return Out.of("user", Out.of("id", HttpJson.path(d, "id"), "name", HttpJson.path(d, "name"), "email", HttpJson.path(d, "email"),
                "role", HttpJson.path(d, "role"), "phone", HttpJson.path(d, "phone"), "organization_id", HttpJson.path(d, "organization_id"),
                "external_id", HttpJson.path(d, "external_id"), "time_zone", HttpJson.path(d, "time_zone"), "suspended", HttpJson.path(d, "suspended"),
                "created_at", HttpJson.path(d, "created_at"), "user_fields", HttpJson.path(d, "user_fields")));
    }

    @Action(id = "update_user", name = "Update user", category = "Users", capabilities = "network",
            description = "Change a user's name, email, phone, organization or role. Only the inputs you fill in are changed.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), user_id, name, role, user_fields | user_email, phone, organization_id")
    public Map<String, Object> updateUser(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "user_id", type = OoaType.NUMBER, description = "User id.") String user,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String userName,
            @Input(name = "user_email", type = OoaType.STRING, required = false, description = "New primary email.") String userEmail,
            @Input(name = "phone", type = OoaType.STRING, required = false, description = "New phone number.") String phone,
            @Input(name = "organization_id", type = OoaType.NUMBER, required = false, description = "New organization id.") String org,
            @Input(name = "role", type = OoaType.STRING, required = false, enumValues = {"end-user", "agent", "admin"}, description = "New role.") String role,
            @Input(name = "user_fields", type = OoaType.DICTIONARY, required = false, description = "Custom user fields by key, e.g. {\"customer_tier\": \"gold\"}.") Map<String, Object> fields) {
        Map<String, Object> u = new LinkedHashMap<>();
        if (userName != null) u.put("name", userName);
        if (userEmail != null) u.put("email", userEmail.strip());
        if (phone != null) u.put("phone", phone);
        if (org != null && !org.isBlank()) u.put("organization_id", new BigDecimal(id("organization", org)));
        if (role != null) u.put("role", role);
        if (fields != null && !fields.isEmpty()) u.put("user_fields", fields);
        if (u.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No changes were given", "Fill in at least one field to change.");
        call(ctx, "PUT", api(site) + "/users/" + id("user", user), email, apiToken, oauth, Map.of("user", u));
        return Map.of();
    }

    // ---------------------------------------------------------------- organizations and groups

    @Action(id = "create_organization", name = "Create organization", category = "Organizations", capabilities = "network",
            description = "Create a customer organization; users with matching email domains can join it automatically.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), name, domain_names, organization_fields | details, notes, external_id")
    @Output(name = "organization_id", type = OoaType.NUMBER, description = "Id of the new organization.")
    public Map<String, Object> createOrganization(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "name", type = OoaType.STRING, example = "Northwind Traders", description = "Organization name (must be unique).") String orgName,
            @Input(name = "domain_names", type = OoaType.LIST, required = false, description = "Email domains, e.g. [\"northwind.com\"].") Object domains,
            @Input(name = "details", type = OoaType.STRING, required = false, description = "Details such as an address.") String details,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "Internal notes.") String notes,
            @Input(name = "external_id", type = OoaType.STRING, required = false, description = "Your own id, e.g. a CRM account number.") String external,
            @Input(name = "organization_fields", type = OoaType.DICTIONARY, required = false, description = "Custom organization fields by field key, e.g. {\"account_tier\": \"enterprise\"}.") Map<String, Object> fields) {
        checkFieldKeys(fields, "organization_fields");
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", orgName);
        if (domains != null) o.put("domain_names", HttpJson.addressList(domains));
        if (details != null) o.put("details", details);
        if (notes != null) o.put("notes", notes);
        if (external != null) o.put("external_id", external);
        if (fields != null && !fields.isEmpty()) o.put("organization_fields", fields);
        return Out.of("organization_id", HttpJson.path(call(ctx, "POST", api(site) + "/organizations", email, apiToken, oauth, Map.of("organization", o)), "organization.id"));
    }

    /** Earlier signature, kept for callers written before organization_fields. */
    public Map<String, Object> createOrganization(ActionContext ctx, String site, String email, Object apiToken, Object oauth, String orgName, Object domains,
                                                  String details, String notes, String external) {
        return createOrganization(ctx, site, email, apiToken, oauth, orgName, domains, details, notes, external, null);
    }

    @Action(id = "update_organization", name = "Update organization", category = "Organizations", capabilities = "network",
            description = "Change an organization's name, email domains, details, notes, external id or custom fields; empty inputs are kept.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), organization_id, name, organization_fields | domain_names, details, notes, external_id")
    public Map<String, Object> updateOrganization(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "organization_id", type = OoaType.NUMBER, description = "Organization id.") long orgId,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "New name.") String orgName,
            @Input(name = "domain_names", type = OoaType.LIST, required = false, description = "Email domains (replaces the list).") Object domains,
            @Input(name = "details", type = OoaType.STRING, required = false, description = "Details such as an address.") String details,
            @Input(name = "notes", type = OoaType.STRING, required = false, description = "Internal notes.") String notes,
            @Input(name = "external_id", type = OoaType.STRING, required = false, description = "Your own id.") String external,
            @Input(name = "organization_fields", type = OoaType.DICTIONARY, required = false, description = "Custom organization fields by key.") Map<String, Object> fields) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (orgName != null && !orgName.isBlank()) o.put("name", orgName);
        if (domains != null) o.put("domain_names", HttpJson.addressList(domains));
        if (details != null) o.put("details", details);
        if (notes != null) o.put("notes", notes);
        if (external != null) o.put("external_id", external);
        if (fields != null && !fields.isEmpty()) o.put("organization_fields", fields);
        if (o.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "Nothing to change", "Fill in at least one field.");
        call(ctx, "PUT", api(site) + "/organizations/" + orgId, email, apiToken, oauth, Map.of("organization", o));
        return Map.of();
    }

    @Action(id = "get_organization", name = "Get organization", category = "Organizations", idempotent = true, capabilities = "network", description = "Read an organization's details.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), organization_id")
    @Output(name = "organization", type = OoaType.DICTIONARY, description = "id, name, domain_names, details, notes, external_id, group_id, tags, created_at, organization_fields.")
    public Map<String, Object> getOrganization(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "organization_id", type = OoaType.NUMBER, description = "Organization id.") String org) {
        Object o = call(ctx, "GET", api(site) + "/organizations/" + id("organization", org), email, apiToken, oauth, null).get("organization");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "domain_names", "details", "notes", "external_id", "group_id", "tags", "created_at", "organization_fields"))
            out.put(k, HttpJson.path(o, k));
        return Out.of("organization", out);
    }

    @Action(id = "list_organizations", name = "List organizations", category = "Organizations", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List customer organizations.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token) | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, domain_names, external_id, created_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of organizations returned.")
    public Map<String, Object> listOrganizations(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = D_MAX) int max) {
        return result(pages(ctx, site, "/organizations", "organizations", max, email, apiToken, oauth, true), "id", "name", "domain_names", "external_id", "created_at");
    }

    @Action(id = "list_groups", name = "List groups", category = "Groups", idempotent = true, capabilities = "network", description = "List the agent groups tickets can be assigned to.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token)")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, name, description, default.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of groups.")
    public Map<String, Object> listGroups(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth) {
        return result(pages(ctx, site, "/groups", "groups", 5000, email, apiToken, oauth, true), "id", "name", "description", "default");
    }

    // ---------------------------------------------------------------- attachments, views, fields

    @Action(id = "upload_attachment", name = "Upload attachment", category = "Attachments", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Upload a local file and get a token; pass the token to Create ticket or Add comment to attach the file.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), path | add_to_token")
    @Output(name = "token", type = OoaType.STRING, description = "Upload token to pass in upload_tokens.")
    @Output(name = "attachment_id", type = OoaType.NUMBER, description = "Id of the stored file.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "path", type = OoaType.FILE, example = "out/receipt-88123.pdf", description = "Local file to upload.") String path,
            @Input(name = "add_to_token", type = OoaType.STRING, required = false, description = "Token of an earlier upload, to group several files under one token.") String existing) {
        byte[] data = HttpJson.readFile(ctx, path);
        Path p = ctx.resolvePath(path);
        String type;
        try { type = Optional.ofNullable(Files.probeContentType(p)).orElse("application/octet-stream"); } catch (java.io.IOException e) { type = "application/octet-stream"; }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("filename", HttpJson.safeName(p.getFileName().toString()));
        if (existing != null && !existing.isBlank()) q.put("token", existing);
        Map<String, Object> r = req("POST", api(site) + "/uploads?" + HttpJson.query(q), email, apiToken, oauth).bytes(data, type).timeout(600).send(ctx).map();
        return Out.of("token", HttpJson.path(r, "upload.token"), "attachment_id", HttpJson.path(r, "upload.attachment.id"));
    }

    @Action(id = "download_attachment", name = "Download attachment", category = "Attachments", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save an attachment (from a ticket comment) to a local folder under its own name.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), attachment_id, folder, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    public Map<String, Object> downloadAttachment(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "attachment_id", type = OoaType.NUMBER, description = "Attachment id, from List comments.") long attachmentId,
            @Input(name = "folder", type = OoaType.FILE, required = false, defaultValue = "\".\"", description = "Folder to save into.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file of the same name; otherwise a number is added.") boolean overwrite) {
        Map<String, Object> a = call(ctx, "GET", api(site) + "/attachments/" + attachmentId, email, apiToken, oauth, null);
        Object url = HttpJson.path(a, "attachment.content_url");
        if (url == null) throw new ActionException(P + ".NOT_FOUND", "Attachment " + attachmentId + " has no file", "Check the id with List comments.");
        // the file is served from the same Zendesk account, so the agent's sign-in is sent along
        byte[] data = req("GET", Values.text(url), email, apiToken, oauth).binaryResponse().timeout(600).send(ctx).bytes();
        Path dir = ctx.resolvePath(folder == null || folder.isBlank() ? "." : folder);
        String name = HttpJson.safeName(Values.text(Optional.ofNullable(HttpJson.path(a, "attachment.file_name")).orElse("attachment-" + attachmentId)));
        Path out = dir.resolve(name);
        for (int i = 2; !overwrite && Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
        HttpJson.writeFile(out, data);
        return Out.of("path", out.toString());
    }

    @Action(id = "list_views", name = "List views", category = "Views", idempotent = true, capabilities = "network", description = "List the active ticket views the agent can use.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token)")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, title, position, updated_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of views.")
    public Map<String, Object> listViews(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth) {
        return result(pages(ctx, site, "/views/active", "views", 5000, email, apiToken, oauth, true), "id", "title", "position", "updated_at");
    }

    @Action(id = "list_view_tickets", name = "List tickets in view", category = "Views", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the tickets a view currently shows, e.g. a team's unassigned queue.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token), view_id | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, subject, status, priority, type, requester_id, assignee_id, group_id, updated_at.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tickets returned.")
    public Map<String, Object> listViewTickets(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth,
            @Input(name = "view_id", type = OoaType.NUMBER, description = "View id, from List views.") String view,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_MAX) int max) {
        return result(pages(ctx, site, "/views/" + id("view", view) + "/tickets", "tickets", max, email, apiToken, oauth, true), TICKET_COLS);
    }

    @Action(id = "list_ticket_fields", name = "List ticket fields", category = "Tickets", idempotent = true, capabilities = "network",
            description = "List ticket fields with their ids, to fill custom_fields in Create ticket and Update ticket.",
            panel = "site, (Sign in with: Email and API token = email, api_token / OAuth token = oauth_token)")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, title, type, active, required.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of fields.")
    public Map<String, Object> listTicketFields(ActionContext ctx,
            @Input(name = "site", type = OoaType.STRING, description = D_SITE) String site,
            @Input(name = "email", type = OoaType.STRING, required = false, description = D_EMAIL) String email,
            @Input(name = "api_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object apiToken,
            @Input(name = "oauth_token", type = OoaType.CREDENTIAL, required = false, description = D_OAUTH) Object oauth) {
        return result(pages(ctx, site, "/ticket_fields", "ticket_fields", 5000, email, apiToken, oauth, true), "id", "title", "type", "active", "required");
    }
}
