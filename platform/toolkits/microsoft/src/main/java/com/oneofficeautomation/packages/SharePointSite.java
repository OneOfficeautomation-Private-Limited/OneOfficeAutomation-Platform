package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * G3 (AA parity, 22 Nov 2026): a SharePoint site reached through its own REST API (/_api), opened once by "Sign in to
 * SharePoint site" and used by later steps. It covers what Microsoft Graph does not: list item attachments, and SharePoint
 * on premises (Graph reaches SharePoint Online only). It signs in with an access token for the site, or with Windows
 * (the runner's own account through Kerberos or NTLM, or a Windows user name and password through NTLM); the calls go
 * through the REST toolkit, which does those sign-ins.
 */
final class SharePointSite {
    static final String P = "M365";   // the Microsoft 365 toolkit's codes

    /** Site address without a trailing slash, e.g. https://contoso.sharepoint.com/sites/Finance or https://intranet/sites/hr. */
    final String url;
    final Object token;
    /** null (token), "current_user" or "user_password". */
    final String windows;
    final String user, domain;
    final Object password;
    final RestPackage rest = new RestPackage();
    private String digest;
    private long digestUntil;

    SharePointSite(String url, Object token, String windows, String user, String domain, Object password) {
        String u = url == null ? "" : url.strip().replaceAll("/+$", "");
        if (!u.matches("(?i)^https?://[^/]+.*")) throw new ActionException(P + ".BAD_SITE", "The site address must start with https://, e.g. https://contoso.sharepoint.com/sites/Finance", "Copy the site's address from the browser.");
        this.url = u; this.token = token; this.windows = windows; this.user = user; this.domain = domain; this.password = password;
    }

    /** The scheme and host of the site, for server-relative paths. */
    String host() { int i = url.indexOf('/', url.indexOf("//") + 2); return i < 0 ? url : url.substring(0, i); }

    /** A value inside ('...') in a REST path: quotes doubled, then URL-encoded. */
    static String lit(String v) { return URLEncoder.encode(v.replace("'", "''"), StandardCharsets.UTF_8).replace("+", "%20"); }

    /** The list's REST path, by id (a GUID) or by title. */
    static String list(String list) {
        if (list == null || list.isBlank()) throw new ActionException(P + ".BAD_INPUT", "list is required", "Give the list's title or id.");
        String l = list.strip();
        return l.matches("(?i)^\\{?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}?$")
                ? "/_api/web/lists(guid'" + l.replaceAll("[{}]", "") + "')" : "/_api/web/lists/getbytitle('" + lit(l) + "')";
    }

    /** One REST call: JSON back (odata=nometadata), a JSON body written as verbose OData, extra headers. */
    @SuppressWarnings("unchecked")
    Map<String, Object> call(ActionContext ctx, String method, String path, Map<String, Object> verboseBody, Map<String, Object> extra, String bodyFile) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("Accept", "application/json;odata=nometadata");
        if (!"GET".equals(method) && token == null) h.put("X-RequestDigest", digest(ctx));
        if (extra != null) h.putAll(extra);
        String body = verboseBody == null ? null : JsonLite.write(verboseBody);
        String type = bodyFile != null ? "application/octet-stream" : body != null ? "application/json;odata=verbose" : null;
        Map<String, Object> r = send(ctx, method, url + path, h, body, type, bodyFile);
        Object j = r.get("json");
        return j instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    Map<String, Object> get(ActionContext ctx, String path) { return call(ctx, "GET", path, null, null, null); }

    private Map<String, Object> send(ActionContext ctx, String method, String full, Map<String, Object> headers, String body, String type, String bodyFile) {
        boolean ntlm = "user_password".equals(windows);
        full = full.replace(" ", "%20");   // spaces in an OData query ($filter=Hidden eq false)
        return rest.request(ctx, full, method, null, headers, null, null, body, null, null, token, 120, true, type, false, null, null, null, null, null,
                false, true, null, bodyFile, "current_user".equals(windows) ? "current_user" : null, null, null, null, null, null, null, null, null,
                null, null, null, null, "HMAC-SHA1", ntlm ? user : null, ntlm ? domain : null, ntlm ? password : null);
    }

    /** The form digest SharePoint wants on changes signed in with Windows; kept for 20 minutes. */
    private String digest(ActionContext ctx) {
        if (digest != null && System.currentTimeMillis() < digestUntil) return digest;
        Map<String, Object> r = send(ctx, "POST", url + "/_api/contextinfo", Map.of("Accept", "application/json;odata=nometadata"), null, null, null);
        Object j = r.get("json");
        String d = j instanceof Map<?, ?> m ? Values.text(m.get("FormDigestValue")) : "";
        if (d.isBlank()) throw new ActionException(P + ".NO_DIGEST", "SharePoint did not give a form digest for changes", "Check that the account may change the site.");
        digest = d;
        digestUntil = System.currentTimeMillis() + 20 * 60_000L;
        return d;
    }

    /** Downloads a file by its server-relative address into a folder, keeping its name unless one is given. */
    Map<String, Object> download(ActionContext ctx, String serverRelative, String folder, String name, boolean overwrite) {
        String n = name != null && !name.isBlank() ? name : serverRelative.substring(serverRelative.lastIndexOf('/') + 1);
        String target = ctx.outputPath(folder, n).toString();
        boolean ntlm = "user_password".equals(windows);
        return rest.downloadFile(ctx, url + "/_api/web/GetFileByServerRelativeUrl('" + lit(serverRelative) + "')/$value", target, null, null, null, null, token,
                overwrite, 600, false, null, null, null, null, null, "current_user".equals(windows) ? "current_user" : null,
                null, null, null, null, null, null, null, null, null, null, null, null, "HMAC-SHA1", ntlm ? user : null, ntlm ? domain : null, ntlm ? password : null, null);
    }

    /** The list's item type, needed to add or change items in the older OData form SharePoint 2013 and later all take. */
    String itemType(ActionContext ctx, String list) {
        String t = Values.text(get(ctx, list(list) + "?$select=ListItemEntityTypeFullName").get("ListItemEntityTypeFullName"));
        if (t.isBlank()) throw new ActionException(P + ".NO_LIST", "SharePoint did not describe the list " + list, "Check the list's title or id.");
        return t;
    }

    /** SharePoint's number for a list template, from the names Graph uses. */
    static int template(String name) {
        return switch (name == null ? "genericList" : name.strip()) {
            case "documentLibrary" -> 101; case "survey" -> 102; case "links" -> 103; case "announcements" -> 104; case "contacts" -> 105;
            case "events" -> 106; case "tasks" -> 107; case "discussionBoard" -> 108; case "pictureLibrary" -> 109; case "xmlForm" -> 115;
            case "webPageLibrary" -> 119; case "customGridList" -> 120; case "ganttTasks" -> 150; case "promotedLinks" -> 170; case "issueTracking" -> 1100;
            case "genericList" -> 100;
            default -> throw new ActionException(P + ".BAD_TEMPLATE", "Unknown list template " + name, "Pick one of the templates the step offers.");
        };
    }
}
