package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Workday REST API (common/v1): workers and generic GET. Tenant-specific business processes use custom REST or RaaS reports. */
@OoaPackage(id = "ooa.workday", name = "Workday", version = "1.0.0", description = "Search and read Workday workers and call Workday REST endpoints or RaaS reports.")
public final class WorkdayPackage {
    static final String P = "WORKDAY";
    private static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String D_HOST = "Workday REST host, e.g. https://wd2-impl-services1.workday.com.";

    private static String api(String host, String tenant) {
        if (!TENANT.matcher(tenant).matches()) throw new ActionException(P + ".BAD_TENANT", "Invalid tenant name '" + tenant + "'");
        return host.replaceAll("/+$", "") + "/ccx/api/v1/" + tenant;
    }

    @Action(id = "search_workers", name = "Search workers", category = "Workers", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "Find workers by name or id text.",
            panel = "host, tenant, access_token, [Only matching workers: search] | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, descriptor, primary_work_email, business_title.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of workers.")
    public Map<String, Object> searchWorkers(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "tenant", type = OoaType.STRING, description = "Tenant name.") String tenant,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = "OAuth access token from the vault.") Object token,
            @Input(name = "search", type = OoaType.STRING, required = false, description = "Name or id text; all workers when empty.") String search,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum workers.") int max) {
        List<Object> rows = new ArrayList<>();
        int offset = 0;
        while (rows.size() < max) {
            Map<String, Object> q = new LinkedHashMap<>(Map.of("limit", Math.min(100, max - rows.size()), "offset", offset));
            if (search != null && !search.isBlank()) q.put("search", search);
            Map<String, Object> r = HttpJson.req(P, "GET", api(host, tenant) + "/workers?" + HttpJson.query(q)).bearer(token).timeout(120).send(ctx).map();
            List<?> data = (List<?>) r.getOrDefault("data", List.of());
            for (Object o : data) {
                Map<?, ?> w = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(w.get("id"), w.get("descriptor"), w.get("primaryWorkEmail"), w.get("businessTitle"))));
            }
            offset += data.size();
            int total = r.get("total") instanceof BigDecimal t ? t.intValue() : offset;
            if (data.isEmpty() || offset >= total) break;
        }
        return Map.of("table", Values.table(List.of("id", "descriptor", "primary_work_email", "business_title"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get", name = "Call REST endpoint (GET)", category = "REST", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "GET any Workday REST resource or RaaS report URL path and return the JSON.",
            panel = "host, path, access_token")
    @Output(name = "json", description = "Response body.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "path", type = OoaType.STRING, example = "/ccx/api/v1/acme/workers/3aa5550b7fe348b98d7b5741afc65534", description = "Path starting with /ccx/.") String path,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = "OAuth access token from the vault.") Object token) {
        if (!path.startsWith("/ccx/") || path.contains("..")) throw new ActionException(P + ".BAD_PATH", "The path must start with /ccx/", "Example: /ccx/service/customreport2/acme/owner/Report?format=json");
        return Out.of("json", HttpJson.req(P, "GET", host.replaceAll("/+$", "") + path).bearer(token).timeout(240).send(ctx).json());
    }

    // ---- Reports (RaaS) and integrations ----

    private static HttpJson.Req auth(HttpJson.Req r, String user, Object password, Object token) {
        return token instanceof SecureString ? r.bearer(token) : r.basic(user == null ? "" : user, password);
    }

    @Action(id = "get_report", name = "Get report (RaaS)", category = "Reports", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Run a custom report exposed as a web service and return its rows as a table.",
            panel = "host, report_path, format, parameters | (Sign in with: User name and password = user, password / An access token = access_token), [Also save the answer to a file: save_to_file]")
    @Output(name = "table", type = OoaType.TABLE, description = "Report rows; columns are the report's field aliases.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows.")
    @Output(name = "path", type = OoaType.FILE, description = "The saved file, when save_to_file was given.")
    public Map<String, Object> getReport(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "report_path", type = OoaType.STRING, example = "/ccx/service/customreport2/acme/isu_reports/Active_Workers", description = "Report path starting with /ccx/service/customreport2/.") String path,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, description = "Report prompts, e.g. {\"Effective_as_of_Date\": \"2026-10-01\"}.") Map<String, Object> params,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"json\"", enumValues = {"json", "csv", "simple_xml", "workday_xml"},
                    description = "Format to request from Workday: json, csv, simple_xml, or workday_xml (which adds reference ids such as WIDs as extra columns).") String format,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Integration system user (basic sign-in), e.g. isu_reports@acme.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault (basic sign-in).") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth access token from the vault (instead of user and password).") Object token,
            @Input(name = "save_to_file", type = OoaType.FILE, required = false, description = "Also save Workday's answer, unchanged, to this file; nothing is saved when empty.") String saveTo) {
        String p = path == null ? "" : path.trim();
        if (!p.startsWith("/ccx/service/customreport2/") || p.contains("..") || p.contains("?"))
            throw new ActionException(P + ".BAD_PATH", "The report path must start with /ccx/service/customreport2/ and have no query", "Example: /ccx/service/customreport2/acme/isu_reports/Active_Workers; put prompts in parameters.");
        String f = format == null || format.isBlank() ? "json" : format;
        if (!Set.of("json", "csv", "simple_xml", "workday_xml").contains(f)) throw new ActionException(P + ".BAD_FORMAT", "Unknown format '" + format + "'", "Use json, csv, simple_xml or workday_xml.");
        Map<String, Object> q = new LinkedHashMap<>();
        if (params != null) q.putAll(params);
        q.put("format", f);
        HttpJson.Req req = auth(HttpJson.req(P, "GET", host.replaceAll("/+$", "") + p + "?" + HttpJson.query(q)), user, password, token).timeout(600);
        if (f.endsWith("xml")) req.header("Accept", "application/xml");
        HttpJson.Response r = req.send(ctx);
        String saved = null;
        if (saveTo != null && !saveTo.isBlank()) { java.nio.file.Path out = ctx.resolvePath(saveTo); HttpJson.writeFile(out, r.bytes()); saved = out.toString(); }
        if (f.equals("csv")) {
            List<List<String>> lines = CsvPackage.parse(r.text(), ',');
            if (lines.isEmpty()) return Out.of("table", Values.table(List.of(), new ArrayList<>()), "count", BigDecimal.ZERO, "path", saved);
            List<Object> rows = new ArrayList<>();
            for (List<String> l : lines.subList(1, lines.size())) if (!(l.size() == 1 && l.get(0).isEmpty())) rows.add(new ArrayList<Object>(l));
            return Out.of("table", Values.table(lines.get(0), rows), "count", BigDecimal.valueOf(rows.size()), "path", saved);
        }
        List<Map<String, Object>> recs = new ArrayList<>();
        if (f.endsWith("xml")) recs = xmlEntries(new String(r.bytes(), java.nio.charset.StandardCharsets.UTF_8));
        else for (Object o : (List<?>) r.map().getOrDefault("Report_Entry", List.of())) if (o instanceof Map<?, ?> m) recs.add(flat("", m));
        return Out.of("table", recs.isEmpty() ? Values.table(List.of(), new ArrayList<>()) : Values.coerce(recs, "Table", "report"), "count", BigDecimal.valueOf(recs.size()), "path", saved);
    }

    /** Earlier signature, kept for callers written before save_to_file. */
    public Map<String, Object> getReport(ActionContext ctx, String host, String path, Map<String, Object> params, String format, String user, Object password, Object token) {
        return getReport(ctx, host, path, params, format, user, password, token, null);
    }

    /**
     * Rows of a RaaS XML answer: one per Report_Entry. Leaf elements become columns (nested groups as Group.Field,
     * repeated values joined with "; "); in Workday XML, reference ids become Field.ID_type columns.
     */
    static List<Map<String, Object>> xmlEntries(String xml) {
        org.w3c.dom.Document d;
        try { d = XmlSupport.parse(xml, true); }
        catch (Exception e) { throw new ActionException(P + ".BAD_RESPONSE", "Workday sent a report that is not valid XML", null); }
        List<Map<String, Object>> out = new ArrayList<>();
        org.w3c.dom.NodeList entries = d.getElementsByTagNameNS("*", "Report_Entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            xmlLeaves((org.w3c.dom.Element) entries.item(i), "", row);
            out.add(row);
        }
        return out;
    }

    private static void xmlLeaves(org.w3c.dom.Element e, String prefix, Map<String, Object> row) {
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof org.w3c.dom.Element c)) continue;
            String name = c.getLocalName() == null ? c.getNodeName() : c.getLocalName();
            boolean hasChildren = false;
            for (org.w3c.dom.Node k = c.getFirstChild(); k != null; k = k.getNextSibling()) if (k instanceof org.w3c.dom.Element) { hasChildren = true; break; }
            if (name.equals("ID") && c.hasAttributes()) {
                String type = null;
                for (int a = 0; a < c.getAttributes().getLength(); a++) { org.w3c.dom.Node at = c.getAttributes().item(a); if ("type".equals(at.getLocalName())) type = at.getNodeValue(); }
                put(row, prefix + "ID" + (type == null ? "" : "_" + type), c.getTextContent().trim());
                continue;
            }
            if (hasChildren) { xmlLeaves(c, prefix + name + ".", row); continue; }
            String key = prefix + name;
            String text = c.getTextContent().trim();
            String descriptor = null;
            for (int a = 0; a < c.getAttributes().getLength(); a++) { org.w3c.dom.Node at = c.getAttributes().item(a); if ("Descriptor".equals(at.getLocalName())) descriptor = at.getNodeValue(); }
            put(row, key, text.isEmpty() && descriptor != null ? descriptor : text);
        }
    }

    private static void put(Map<String, Object> row, String key, String value) {
        Object old = row.get(key);
        row.put(key, old == null ? value : old + "; " + value);
    }

    @Action(id = "authenticate", name = "Authenticate", category = "Authentication", capabilities = "network",
            description = "Get an OAuth access token for a registered API client from its refresh token, for the other Workday actions.",
            panel = "host, tenant, client_id, client_secret, refresh_token")
    @Output(name = "access_token", type = OoaType.CREDENTIAL, description = "Access token; map it to access_token of later actions.")
    @Output(name = "expires_in", type = OoaType.NUMBER, description = "Seconds until the access token expires, when Workday says.")
    public Map<String, Object> authenticate(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "tenant", type = OoaType.STRING, description = "Tenant name.") String tenant,
            @Input(name = "client_id", type = OoaType.STRING, description = "Client id of the API client registered in Workday.") String clientId,
            @Input(name = "client_secret", type = OoaType.CREDENTIAL, description = "Client secret from the vault.") Object clientSecret,
            @Input(name = "refresh_token", type = OoaType.CREDENTIAL, description = "Refresh token issued to the API client, from the vault.") Object refreshToken) {
        if (tenant == null || !TENANT.matcher(tenant).matches()) throw new ActionException(P + ".BAD_TENANT", "Invalid tenant name '" + tenant + "'");
        if (clientId == null || clientId.isBlank()) throw new ActionException(P + ".NO_CLIENT", "The client id is required");
        EnterpriseAuth.secret(clientSecret, P, "client secret");
        EnterpriseAuth.secret(refreshToken, P, "refresh token");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        Map<String, Object> r = EnterpriseAuth.token(ctx, P, host.replaceAll("/+$", "") + "/ccx/oauth2/" + tenant + "/token", form, clientId.strip(), clientSecret);
        return Out.of("access_token", EnterpriseAuth.credential(r, "workday/session"), "expires_in", r.get("expires_in") == null ? null : Values.toNumber(r.get("expires_in")));
    }

    /** Nested groups become Group.Field columns; repeated values are joined with "; ". */
    static Map<String, Object> flat(String prefix, Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String k = prefix + e.getKey();
            if (e.getValue() instanceof Map<?, ?> sub) out.putAll(flat(k + ".", sub));
            else if (e.getValue() instanceof List<?> l) {
                StringJoiner j = new StringJoiner("; ");
                for (Object v : l) j.add(v instanceof Map<?, ?> vm ? String.valueOf(vm.values().iterator().hasNext() ? vm.values().iterator().next() : "") : Values.text(v));
                out.put(k, j.toString());
            } else out.put(k, e.getValue());
        }
        return out;
    }

    private static final Pattern REF_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");
    private static final Pattern WS_VERSION = Pattern.compile("^v\\d{2}\\.\\d$");

    static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /** SOAP envelope for the Integrations web service; a WS-Security user name token is added for basic sign-in. */
    static String envelope(String body, String user, Object password) {
        String header = "";
        if (user != null && !user.isBlank() && password instanceof SecureString pw)
            header = "<soapenv:Header><wsse:Security soapenv:mustUnderstand=\"1\" xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
                    + "<wsse:UsernameToken><wsse:Username>" + xml(user) + "</wsse:Username><wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText\">"
                    + xml(pw.reveal()) + "</wsse:Password></wsse:UsernameToken></wsse:Security></soapenv:Header>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:bsvc=\"urn:com.workday/bsvc\">"
                + header + "<soapenv:Body>" + body + "</soapenv:Body></soapenv:Envelope>";
    }

    private static org.w3c.dom.Document soap(ActionContext ctx, String host, String tenant, String version, String body, String user, Object password, Object token) {
        if (!TENANT.matcher(tenant).matches()) throw new ActionException(P + ".BAD_TENANT", "Invalid tenant name '" + tenant + "'");
        if (!WS_VERSION.matcher(version).matches()) throw new ActionException(P + ".BAD_VERSION", "Invalid web service version '" + version + "'", "Example: v42.0.");
        if (!(token instanceof SecureString) && !(password instanceof SecureString))
            throw new ActionException(P + ".NO_TOKEN", "Sign-in details are required", "Give access_token, or user and password.");
        HttpJson.Req req = HttpJson.req(P, "POST", host.replaceAll("/+$", "") + "/ccx/service/" + tenant + "/Integrations/" + version)
                .bytes(envelope(body, user, password).getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/xml; charset=utf-8").header("Accept", "text/xml").timeout(120);
        if (token instanceof SecureString) req.bearer(token);
        HttpJson.Response r;
        try { r = req.send(ctx); }
        catch (ActionException e) {
            String fault = e.getMessage() == null ? null : between(e.getMessage(), "faultstring>", "</");
            if (fault != null) throw new ActionException(P + ".SOAP_FAULT", "Workday refused the request: " + fault, "Check the integration id, the user's security group and the web service version.");
            throw e;
        }
        try {
            return XmlSupport.parse(new String(r.bytes(), java.nio.charset.StandardCharsets.UTF_8), true);
        } catch (Exception e) { throw new ActionException(P + ".BAD_RESPONSE", "Workday sent a reply that is not valid XML", null); }
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a); if (i < 0) return null;
        int j = s.indexOf(b, i + a.length()); return j < 0 ? null : s.substring(i + a.length(), j);
    }

    /** Text of the first ID element of the given type inside the first element named parent (anywhere when parent is null). */
    static String refId(org.w3c.dom.Document d, String parent, String type) {
        org.w3c.dom.NodeList scope = parent == null ? null : d.getElementsByTagNameNS("*", parent);
        org.w3c.dom.Element root = scope == null ? d.getDocumentElement() : scope.getLength() == 0 ? null : (org.w3c.dom.Element) scope.item(0);
        if (root == null) return null;
        org.w3c.dom.NodeList ids = root.getElementsByTagNameNS("*", "ID");
        for (int i = 0; i < ids.getLength(); i++) {
            org.w3c.dom.Element e = (org.w3c.dom.Element) ids.item(i);
            if (type.equals(e.getAttributeNS("urn:com.workday/bsvc", "type")) || type.equals(e.getAttribute("wd:type")) || type.equals(e.getAttribute("bsvc:type"))) return e.getTextContent().trim();
        }
        return null;
    }

    static String text(org.w3c.dom.Document d, String name) {
        org.w3c.dom.NodeList l = d.getElementsByTagNameNS("*", name);
        return l.getLength() == 0 ? null : l.item(0).getTextContent().trim();
    }

    @Action(id = "launch_integration", name = "Launch integration", category = "Integrations", timeoutSeconds = 180, capabilities = "network",
            description = "Start an integration system (such as an EIB) through the Integrations web service, optionally with launch parameter values, and return the event id to track it.",
            panel = "host, tenant, id_type, integration_system_id, parameters | (Sign in with: User name and password = user, password / An access token = access_token), version")
    @Output(name = "event_id", type = OoaType.STRING, description = "Integration event WID; pass it to Get integration event.")
    public Map<String, Object> launchIntegration(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "tenant", type = OoaType.STRING, description = "Tenant name.") String tenant,
            @Input(name = "integration_system_id", type = OoaType.STRING, example = "INT_Worker_Load", description = "Integration System ID of the integration to start.") String integrationId,
            @Input(name = "version", type = OoaType.STRING, required = false, defaultValue = "\"v42.0\"", description = "Web service version.") String version,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Integration system user with tenant, e.g. isu_int@acme (basic sign-in).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault (basic sign-in).") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth access token from the vault (instead of user and password).") Object token,
            @Input(name = "id_type", type = OoaType.STRING, required = false, defaultValue = "\"integration_system_id\"", enumValues = {"integration_system_id", "wid"},
                    description = "Whether integration_system_id holds the Integration System ID or the integration's Workday ID (WID).") String idType,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, example = "{\"Effective_Date\": \"2026-11-01\", \"Include_Terminated\": false}",
                    description = "Launch parameter name to value; text, numbers, true/false and dates are sent with their Workday type.") Map<String, Object> parameters) {
        if (integrationId == null || !REF_ID.matcher(integrationId).matches()) throw new ActionException(P + ".BAD_ID", "Invalid integration system id '" + integrationId + "'");
        boolean wid = "wid".equals(idType);
        if (wid && !integrationId.matches("^[0-9a-fA-F]{32}$")) throw new ActionException(P + ".BAD_ID", "A WID is 32 hexadecimal characters", "Use id_type integration_system_id for a named id.");
        // the request of the Launch_Integration operation is Launch_Integration_Event_Request (Integrations web service)
        StringBuilder body = new StringBuilder("<bsvc:Launch_Integration_Event_Request bsvc:version=\"" + version + "\"><bsvc:Integration_System_Reference>"
                + "<bsvc:ID bsvc:type=\"" + (wid ? "WID" : "Integration_System_ID") + "\">" + xml(integrationId) + "</bsvc:ID></bsvc:Integration_System_Reference>");
        if (parameters != null) parameters.forEach((name, value) -> {
            if (name == null || !REF_ID.matcher(name).matches()) throw new ActionException(P + ".BAD_ID", "Invalid launch parameter name '" + name + "'");
            String typed = value instanceof Boolean b ? "<bsvc:Boolean>" + b + "</bsvc:Boolean>"
                    : value instanceof Number n ? "<bsvc:Numeric>" + new BigDecimal(n.toString()).toPlainString() + "</bsvc:Numeric>"
                    : value instanceof java.time.LocalDateTime t ? "<bsvc:DateTime>" + t + "</bsvc:DateTime>"
                    : value instanceof String str && str.matches("^\\d{4}-\\d{2}-\\d{2}$") ? "<bsvc:Date>" + str + "</bsvc:Date>"
                    : "<bsvc:Text>" + xml(Values.text(value)) + "</bsvc:Text>";
            body.append("<bsvc:Integration_Launch_Parameter_Data><bsvc:Launch_Parameter_Reference><bsvc:ID bsvc:type=\"Launch_Parameter_Name\">").append(xml(name))
                    .append("</bsvc:ID></bsvc:Launch_Parameter_Reference><bsvc:Launch_Parameter_Value_Data>").append(typed)
                    .append("</bsvc:Launch_Parameter_Value_Data></bsvc:Integration_Launch_Parameter_Data>");
        });
        body.append("</bsvc:Launch_Integration_Event_Request>");
        org.w3c.dom.Document d = soap(ctx, host, tenant, version, body.toString(), user, password, token);
        String event = refId(d, "Integration_Event_Reference", "WID");
        if (event == null) event = refId(d, null, "WID");
        if (event == null) throw new ActionException(P + ".BAD_RESPONSE", "Workday did not return an integration event id", "Check the integration can be launched by this user.");
        return Out.of("event_id", event);
    }

    /** Earlier signature, kept for callers written before id_type. */
    public Map<String, Object> launchIntegration(ActionContext ctx, String host, String tenant, String integrationId, String version, String user, Object password, Object token) {
        return launchIntegration(ctx, host, tenant, integrationId, version, user, password, token, "integration_system_id", null);
    }

    public Map<String, Object> launchIntegration(ActionContext ctx, String host, String tenant, String integrationId, String version, String user, Object password, Object token, String idType) {
        return launchIntegration(ctx, host, tenant, integrationId, version, user, password, token, idType, null);
    }

    @Action(id = "launch_eib", name = "Launch EIB with file", category = "Integrations", timeoutSeconds = 600, capabilities = "network,filesystem",
            description = "Start an inbound EIB with a file to load (a spreadsheet or XML attached at launch) through the Integrations web service's Launch_EIB, and return the event id to track it.",
            panel = "host, tenant, id_type, integration_system_id, file, file_field | (Sign in with: User name and password = user, password / An access token = access_token), content_type, version")
    @Output(name = "event_id", type = OoaType.STRING, description = "Integration event WID; pass it to Get integration event.")
    public Map<String, Object> launchEib(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "tenant", type = OoaType.STRING, description = "Tenant name.") String tenant,
            @Input(name = "integration_system_id", type = OoaType.STRING, example = "INT_Worker_Load", description = "Integration System ID of the EIB to start.") String integrationId,
            @Input(name = "file", type = OoaType.FILE, description = "File to load: the EIB's spreadsheet template filled in, or XML.") String file,
            @Input(name = "file_field", type = OoaType.STRING, example = "Attachment",
                    description = "Name of the EIB data source field that takes the file at launch, as the EIB's Launch / Schedule page shows it.") String field,
            @Input(name = "content_type", type = OoaType.STRING, required = false,
                    description = "MIME type of the file; worked out from the file name (.xlsx, .xls, .xml, .csv, .txt) when empty.") String contentType,
            @Input(name = "version", type = OoaType.STRING, required = false, defaultValue = "\"v42.0\"", description = "Web service version.") String version,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Integration system user with tenant, e.g. isu_int@acme (basic sign-in).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault (basic sign-in).") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth access token from the vault (instead of user and password).") Object token,
            @Input(name = "id_type", type = OoaType.STRING, required = false, defaultValue = "\"integration_system_id\"", enumValues = {"integration_system_id", "wid"},
                    description = "Whether integration_system_id holds the Integration System ID or the EIB's Workday ID (WID).") String idType) {
        if (integrationId == null || !REF_ID.matcher(integrationId).matches()) throw new ActionException(P + ".BAD_ID", "Invalid integration system id '" + integrationId + "'");
        boolean wid = "wid".equals(idType);
        if (wid && !integrationId.matches("^[0-9a-fA-F]{32}$")) throw new ActionException(P + ".BAD_ID", "A WID is 32 hexadecimal characters", "Use id_type integration_system_id for a named id.");
        if (field == null || field.isBlank()) throw new ActionException(P + ".NO_FILE_FIELD", "Say which EIB field takes the file", "Use the data source field name shown on the EIB's Launch / Schedule page.");
        String fieldName = field.strip();
        java.nio.file.Path f = ctx.resolvePath(file);
        if (!java.nio.file.Files.isRegularFile(f)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + f, "Check the path.");
        byte[] content;
        try { content = java.nio.file.Files.readAllBytes(f); }
        catch (java.io.IOException e) { throw new ActionException("FILE.READ_FAILED", "Cannot read " + f + ": " + e.getMessage()); }
        String name = f.getFileName().toString();
        String type = contentType != null && !contentType.isBlank() ? contentType.strip() : mimeType(name);
        // Launch_EIB_Request: the file is Attachment_Data of a field override on the EIB's data source (Integrations web service)
        String body = "<bsvc:Launch_EIB_Request bsvc:version=\"" + xml(version) + "\"><bsvc:Integration_System_Reference>"
                + "<bsvc:ID bsvc:type=\"" + (wid ? "WID" : "Integration_System_ID") + "\">" + xml(integrationId) + "</bsvc:ID></bsvc:Integration_System_Reference>"
                + "<bsvc:Service_Component_Data><bsvc:Data_Source>true</bsvc:Data_Source><bsvc:Field_Override_Data><bsvc:Field_Name>" + xml(fieldName) + "</bsvc:Field_Name>"
                + "<bsvc:Attachment_Data bsvc:Filename=\"" + xml(name) + "\" bsvc:Content_Type=\"" + xml(type) + "\">"
                + "<bsvc:File_Content>" + Base64.getEncoder().encodeToString(content) + "</bsvc:File_Content></bsvc:Attachment_Data>"
                + "</bsvc:Field_Override_Data></bsvc:Service_Component_Data></bsvc:Launch_EIB_Request>";
        org.w3c.dom.Document d = soap(ctx, host, tenant, version, body, user, password, token);
        String event = refId(d, "Integration_Event_Reference", "WID");
        if (event == null) throw new ActionException(P + ".BAD_RESPONSE", "Workday did not return an integration event id", "Check the EIB takes a file at launch and this user may launch it.");
        return Out.of("event_id", event);
    }

    /** The MIME type of an EIB file from its name. */
    static String mimeType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : n.endsWith(".xls") ? "application/vnd.ms-excel"
                : n.endsWith(".xml") ? "text/xml"
                : n.endsWith(".csv") ? "text/csv"
                : n.endsWith(".txt") ? "text/plain"
                : "application/octet-stream";
    }

    /** Earlier signature, kept for callers written before id_type. */
    public Map<String, Object> getIntegrationEvent(ActionContext ctx, String host, String tenant, String eventId, String version, String user, Object password, Object token) {
        return getIntegrationEvent(ctx, host, tenant, eventId, version, user, password, token, "WID");
    }

    private static final Pattern ID_TYPE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    @Action(id = "get_integration_event", name = "Get integration event", category = "Integrations", idempotent = true, timeoutSeconds = 120, capabilities = "network",
            description = "Read the status and timing of an integration run.",
            panel = "host, tenant, event_id | id_type, (Sign in with: User name and password = user, password / An access token = access_token), version")
    @Output(name = "status", type = OoaType.STRING, description = "Run status, e.g. Processing, Completed, Completed with Warnings or Failed.")
    @Output(name = "percent_complete", type = OoaType.NUMBER, description = "Progress percentage when Workday reports it.")
    @Output(name = "started", type = OoaType.STRING, description = "Start time.")
    @Output(name = "completed", type = OoaType.STRING, description = "Completion time; empty while running.")
    @Output(name = "done", type = OoaType.BOOLEAN, description = "True once the run has finished, whether it succeeded or not.")
    public Map<String, Object> getIntegrationEvent(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, description = D_HOST) String host,
            @Input(name = "tenant", type = OoaType.STRING, description = "Tenant name.") String tenant,
            @Input(name = "event_id", type = OoaType.STRING, description = "Integration event WID from Launch integration.") String eventId,
            @Input(name = "version", type = OoaType.STRING, required = false, defaultValue = "\"v42.0\"", description = "Web service version.") String version,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Integration system user with tenant (basic sign-in).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault (basic sign-in).") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth access token from the vault (instead of user and password).") Object token,
            @Input(name = "id_type", type = OoaType.STRING, required = false, defaultValue = "\"WID\"", example = "WID",
                    description = "Kind of id in event_id: WID (the 32-character Workday ID that Launch integration returns), or the name of another ID type "
                            + "your tenant accepts for integration event references, written exactly as Workday names it.") String idType) {
        String type = idType == null || idType.isBlank() ? "WID" : idType.strip();
        if (!ID_TYPE.matcher(type).matches()) throw new ActionException(P + ".BAD_ID_TYPE", "Invalid id type '" + idType + "'", "Use WID, or a Workday ID type name such as the ones shown in the event's reference ids.");
        boolean wid = type.equalsIgnoreCase("WID");
        if (wid) type = "WID";
        if (wid && (eventId == null || !eventId.matches("^[0-9a-fA-F]{32}$")))
            throw new ActionException(P + ".BAD_ID", "Invalid integration event id '" + eventId + "'", "An event id is a 32-character WID.");
        if (!wid && (eventId == null || !REF_ID.matcher(eventId.strip()).matches()))
            throw new ActionException(P + ".BAD_ID", "Invalid integration event id '" + eventId + "'", "Use letters, digits, _ . : or - only.");
        String body = "<bsvc:Get_Integration_Events_Request bsvc:version=\"" + version + "\"><bsvc:Request_References>"
                + "<bsvc:Integration_Event_Reference><bsvc:ID bsvc:type=\"" + type + "\">" + xml(eventId.strip()) + "</bsvc:ID></bsvc:Integration_Event_Reference>"
                + "</bsvc:Request_References></bsvc:Get_Integration_Events_Request>";
        org.w3c.dom.Document d = soap(ctx, host, tenant, version, body, user, password, token);
        String status = refId(d, "Background_Process_Instance_Status_Reference", "Background_Process_Instance_Status_ID");
        if (status == null) status = text(d, "Integration_Event_Status");
        if (status == null) throw new ActionException(P + ".NOT_FOUND", "Integration event " + eventId + " was not found", "Check the event id and the user's access to it.");
        String pct = text(d, "Percent_Complete");
        String completed = text(d, "Completed_DateTime");
        String s = status.replace('_', ' ').toLowerCase(Locale.ROOT);
        boolean done = completed != null && !completed.isEmpty() || s.startsWith("completed") || s.startsWith("failed") || s.startsWith("cancel") || s.startsWith("error");
        return Out.of("status", status, "percent_complete", pct == null || pct.isEmpty() ? null : new BigDecimal(pct),
                "started", text(d, "Initiated_DateTime"), "completed", completed, "done", done);
    }
}
