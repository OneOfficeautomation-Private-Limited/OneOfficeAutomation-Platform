package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * SAP BAPIs and remote-enabled function modules through the SAP Java Connector (JCo 3), without SAP GUI: log on, call a
 * BAPI with import values, structures and tables, read its exports and RETURN messages, commit or roll back in the same
 * SAP session, and read any table with RFC_READ_TABLE. JCo is SAP's licensed library and is added to the runner by the
 * customer (see SapJco).
 */
@OoaPackage(id = "ooa.sap_bapi", name = "SAP BAPI", version = "1.0.0",
        description = "Call SAP BAPIs and RFC function modules through SAP JCo: connect, run a function with imports, structures and tables, commit or roll back, read tables and function details.")
public final class SapBapiPackage {

    static final String D_SESSION = "Session name; one SAP logon per session.";

    /** One SAP logon: a destination served from memory, kept in a stateful context so BAPI_TRANSACTION_COMMIT sees the BAPI's work. */
    static final class Session implements AutoCloseable {
        final String name; final Object destination; boolean closed;
        Session(String name, Object destination) { this.name = name; this.destination = destination; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { SapJco.callStatic("JCoContext", "end", destination); } catch (RuntimeException ignored) { }
            SapJco.removeDestination(name);
        }
    }

    static Session session(ActionContext ctx, String name) {
        Object s = ctx.sessions().get("sap_bapi:" + (name == null || name.isBlank() ? "sap" : name));
        if (s instanceof Session ss && !ss.closed) return ss;
        throw new ActionException("SAP_BAPI.NO_SESSION", "No SAP BAPI connection '" + (name == null || name.isBlank() ? "sap" : name) + "'", "Run Connect first, with the same session name.");
    }

    @Action(id = "connect", name = "Connect", category = "Session", timeoutSeconds = 120, capabilities = "network",
            description = "Log on to an SAP system through SAP JCo (application server or message server with a logon group, optional SAP router and SNC) and keep the logon for the following steps.",
            panel = "(Server: Application server = host, system_number / Message server and logon group = message_server, system_id, logon_group), client, user, password, language | router, snc_partner_name, snc_library, properties, session")
    @Output(name = "system", type = OoaType.DICTIONARY, description = "system_id, client, user, host and release of the logon.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, required = false, description = "Application server host name or IP address.") String host,
            @Input(name = "system_number", type = OoaType.STRING, required = false, pattern = "^\\d{2}$", example = "00", description = "Two-digit SAP system (instance) number.") String sysnr,
            @Input(name = "client", type = OoaType.STRING, pattern = "^\\d{3}$", example = "100", description = "Three-digit SAP client.") String client,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "SAP user name (empty with SNC single sign-on).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "SAP password from the vault (empty with SNC single sign-on).") Object password,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"EN\"", description = "Logon language, e.g. EN or DE.") String language,
            @Input(name = "message_server", type = OoaType.STRING, required = false, description = "Message server host, for load balancing instead of one application server.") String msHost,
            @Input(name = "system_id", type = OoaType.STRING, required = false, pattern = "^[A-Za-z0-9]{3}$", description = "Three-character system ID (with a message server).") String sysId,
            @Input(name = "logon_group", type = OoaType.STRING, required = false, defaultValue = "\"PUBLIC\"", description = "Logon group (with a message server).") String group,
            @Input(name = "router", type = OoaType.STRING, required = false, example = "/H/saprouter.example.com/S/3299/H/", description = "SAP router string.") String router,
            @Input(name = "snc_partner_name", type = OoaType.STRING, required = false, example = "p:CN=SAP-ERP, O=Example", description = "SNC name of the SAP system; turns on SNC (secure, single sign-on).") String sncPartner,
            @Input(name = "snc_library", type = OoaType.FILE, required = false, description = "Path of the SNC library (e.g. sapcrypto.dll) when SNC is used.") String sncLib,
            @Input(name = "properties", type = OoaType.DICTIONARY, required = false, description = "Other JCo destination properties, e.g. {\"jco.destination.pool_capacity\": \"3\"}.") Map<String, Object> extra,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        String sname = session == null || session.isBlank() ? "sap" : session;
        Object old = ctx.sessions().get("sap_bapi:" + sname);
        if (old instanceof Session s) s.close();
        boolean viaMs = msHost != null && !msHost.isBlank();
        if (!viaMs && (host == null || host.isBlank() || sysnr == null || sysnr.isBlank()))
            throw new ActionException("SAP_BAPI.BAD_SERVER", "Give host and system_number, or message_server and system_id", "Fill the application server or the message server fields.");
        if (viaMs && (sysId == null || sysId.isBlank()))
            throw new ActionException("SAP_BAPI.BAD_SERVER", "A message server needs system_id", "Fill system_id (three characters, e.g. PRD).");
        boolean snc = sncPartner != null && !sncPartner.isBlank();
        if (!snc && (user == null || user.isBlank()))
            throw new ActionException("SAP_BAPI.NO_USER", "Give user and password, or snc_partner_name for single sign-on", "Fill user and password from the vault.");
        if (!snc && !(password instanceof SecureString))
            throw new ActionException("SAP_BAPI.NO_SECRET", "Input 'password' must come from the vault (a Credential)",
                    "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
        Properties p = new Properties();
        if (viaMs) { p.setProperty("jco.client.mshost", msHost.strip()); p.setProperty("jco.client.r3name", sysId.strip().toUpperCase(Locale.ROOT)); p.setProperty("jco.client.group", group == null || group.isBlank() ? "PUBLIC" : group.strip()); }
        else { p.setProperty("jco.client.ashost", host.strip()); p.setProperty("jco.client.sysnr", sysnr.strip()); }
        p.setProperty("jco.client.client", client);
        p.setProperty("jco.client.lang", language == null || language.isBlank() ? "EN" : language.strip());
        if (user != null && !user.isBlank()) p.setProperty("jco.client.user", user.strip());
        if (password instanceof SecureString pw) p.setProperty("jco.client.passwd", pw.reveal());
        if (router != null && !router.isBlank()) p.setProperty("jco.client.saprouter", router.strip());
        if (snc) {
            p.setProperty("jco.client.snc_mode", "1");
            p.setProperty("jco.client.snc_partnername", sncPartner.strip());
            p.setProperty("jco.client.snc_qop", "9");
            if (sncLib != null && !sncLib.isBlank()) p.setProperty("jco.client.snc_lib", ctx.resolvePath(sncLib).toString());
        }
        if (extra != null) extra.forEach((k, v) -> { if (!k.startsWith("jco.")) throw new ActionException("SAP_BAPI.BAD_PROPERTY", "Property '" + k + "' is not a JCo property", "JCo properties start with jco., e.g. jco.client.trace."); p.setProperty(k, Values.text(v)); });
        String dest = SapJco.addDestination(p);
        Session s;
        try {
            Object d = SapJco.callStatic("JCoDestinationManager", "getDestination", dest);
            SapJco.call(d, "ping");
            SapJco.callStatic("JCoContext", "begin", d);
            s = new Session(dest, d);
        } catch (RuntimeException e) { SapJco.removeDestination(dest); throw e; }
        ctx.sessions().put("sap_bapi:" + sname, s);
        Map<String, Object> sys = new LinkedHashMap<>();
        try {
            Object a = SapJco.call(s.destination, "getAttributes");
            sys.put("system_id", SapJco.call(a, "getSystemID")); sys.put("client", SapJco.call(a, "getClient")); sys.put("user", SapJco.call(a, "getUser"));
            sys.put("host", SapJco.call(a, "getPartnerHost")); sys.put("release", SapJco.call(a, "getPartnerRelease"));
        } catch (RuntimeException e) { sys.put("client", client); }
        ctx.info("Connected session '" + sname + "' to SAP " + (viaMs ? sysId + " via " + msHost : host + " " + sysnr) + ", client " + client);
        return Out.of("system", sys);
    }

    @Action(id = "call_function", name = "Run BAPI / function", category = "Functions", timeoutSeconds = 600, capabilities = "network",
            description = "Run a BAPI or remote-enabled function module with import values (structures as dictionaries) and table rows; returns exports, tables, changing values and the RETURN messages. Fails on error messages (type E or A) unless told otherwise.",
            panel = "function, imports, tables, changing, commit | fail_on_error, session")
    @Output(name = "exports", type = OoaType.DICTIONARY, description = "Export parameters (structures as dictionaries, tables as Tables).")
    @Output(name = "tables", type = OoaType.DICTIONARY, description = "Every table parameter after the call, as Tables.")
    @Output(name = "changing", type = OoaType.DICTIONARY, description = "Changing parameters after the call.")
    @Output(name = "messages", type = OoaType.LIST, description = "BAPI RETURN messages (type, id, number, message).")
    @Output(name = "success", type = OoaType.BOOLEAN, description = "True when no RETURN message has type E or A.")
    public Map<String, Object> callFunction(ActionContext ctx,
            @Input(name = "function", type = OoaType.STRING, pattern = "^[A-Za-z0-9_/]{1,30}$", example = "BAPI_MATERIAL_GET_DETAIL", description = "Function module or BAPI name.") String function,
            @Input(name = "imports", type = OoaType.DICTIONARY, required = false, description = "Import parameters: {\"MATERIAL\": \"100-100\", \"HEADDATA\": {\"MATERIAL\": \"X\"}}.") Map<String, Object> imports,
            @Input(name = "tables", type = OoaType.DICTIONARY, required = false, description = "Table parameters: name to a Table or a list of dictionaries (one per row).") Map<String, Object> tables,
            @Input(name = "changing", type = OoaType.DICTIONARY, required = false, description = "Changing parameters.") Map<String, Object> changing,
            @Input(name = "commit", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Run BAPI_TRANSACTION_COMMIT (wait) right after a successful call.") boolean commit,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Stop with SAP_BAPI.FAILED when RETURN has an error (type E or A); the call is rolled back.") boolean failOnError,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        Session s = session(ctx, session);
        Object f = function(s, function);
        fill(f, "getImportParameterList", imports, "import", function);
        fill(f, "getChangingParameterList", changing, "changing", function);
        if (tables != null && !tables.isEmpty()) {
            Object tl = SapJco.call(f, "getTableParameterList");
            if (tl == null) throw new ActionException("SAP_BAPI.UNKNOWN_PARAMETER", function + " has no table parameters", "Remove tables, or check the function.");
            for (Map.Entry<String, Object> e : tables.entrySet()) {
                Object t = table(tl, e.getKey(), function);
                for (Map<String, Object> row : SapJco.rows(e.getValue())) {
                    SapJco.call(t, "appendRow");
                    for (Map.Entry<String, Object> c : row.entrySet()) set(t, c.getKey(), c.getValue(), function + "." + e.getKey());
                }
            }
        }
        ctx.info("Calling " + function);
        SapJco.call(f, "execute", s.destination);
        Map<String, Object> exports = SapJco.record(SapJco.call(f, "getExportParameterList"));
        Map<String, Object> tbls = SapJco.record(SapJco.call(f, "getTableParameterList"));
        Map<String, Object> chg = SapJco.record(SapJco.call(f, "getChangingParameterList"));
        List<Object> messages = messages(exports.get("RETURN") != null ? exports.get("RETURN") : tbls.get("RETURN"));
        boolean ok = messages.stream().noneMatch(m -> isError(((Map<?, ?>) m).get("type")));
        if (!ok && failOnError) {
            rollback(s);
            Map<?, ?> first = (Map<?, ?>) messages.stream().filter(m -> isError(((Map<?, ?>) m).get("type"))).findFirst().orElseThrow();
            throw new ActionException("SAP_BAPI.FAILED", function + " returned an error: " + first.get("message"), "See the messages; the call was rolled back.", false,
                    Map.of("messages", messages));
        }
        if (ok && commit) messages.addAll(commitWork(ctx, s, true));
        return Out.of("exports", exports, "tables", tbls, "changing", chg, "messages", messages, "success", ok);
    }

    @Action(id = "commit", name = "Commit", category = "Functions", timeoutSeconds = 300, capabilities = "network",
            description = "Commit the work of the BAPIs called in this session (BAPI_TRANSACTION_COMMIT), waiting for the update by default.")
    @Output(name = "messages", type = OoaType.LIST, description = "RETURN messages of the commit.")
    public Map<String, Object> commit(ActionContext ctx,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the update is done (WAIT = X).") boolean wait,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        return Out.of("messages", commitWork(ctx, session(ctx, session), wait));
    }

    @Action(id = "rollback", name = "Roll back", category = "Functions", timeoutSeconds = 120, capabilities = "network",
            description = "Undo the work of the BAPIs called in this session since the last commit (BAPI_TRANSACTION_ROLLBACK).")
    public Map<String, Object> rollback(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        rollback(session(ctx, session));
        return Map.of();
    }

    @Action(id = "read_table", name = "Read table", category = "Tables", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Read rows of an SAP table or view with RFC_READ_TABLE: chosen fields, a WHERE condition in ABAP syntax and a row limit; values come back as text.",
            panel = "table, fields, where, max_rows | skip_rows, session")
    @Output(name = "table", type = OoaType.TABLE, description = "The rows read, one column per field.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows read.")
    public Map<String, Object> readTable(ActionContext ctx,
            @Input(name = "table", type = OoaType.STRING, pattern = "^[A-Za-z0-9_/]{1,30}$", example = "MARA", description = "SAP table or view name.") String table,
            @Input(name = "fields", type = OoaType.LIST, required = false, description = "Fields to read, e.g. [\"MATNR\", \"MTART\"]; all fields when empty (only for narrow tables).") List<Object> fields,
            @Input(name = "where", type = OoaType.STRING, required = false, example = "MTART = 'FERT' AND ERSDA >= '20260101'", description = "WHERE condition in ABAP Open SQL syntax.") String where,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "1000", min = 0, description = "Most rows to read (0 = no limit).") int maxRows,
            @Input(name = "skip_rows", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Rows to skip first, for reading in pages.") int skip,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        Session s = session(ctx, session);
        Object f = function(s, "RFC_READ_TABLE");
        Object in = SapJco.call(f, "getImportParameterList");
        SapJco.setValue(in, "QUERY_TABLE", table.toUpperCase(Locale.ROOT));
        SapJco.setValue(in, "DELIMITER", "");
        SapJco.setValue(in, "ROWCOUNT", maxRows);
        SapJco.setValue(in, "ROWSKIPS", skip);
        Object tl = SapJco.call(f, "getTableParameterList");
        Object ft = SapJco.call(tl, "getTable", "FIELDS");
        if (fields != null) for (Object x : fields) { SapJco.call(ft, "appendRow"); SapJco.setValue(ft, "FIELDNAME", Values.text(x).strip().toUpperCase(Locale.ROOT)); }
        if (where != null && !where.isBlank()) {
            Object ot = SapJco.call(tl, "getTable", "OPTIONS");
            for (String line : wrap(where.strip(), 72)) { SapJco.call(ot, "appendRow"); SapJco.setValue(ot, "TEXT", line); }
        }
        SapJco.call(f, "execute", s.destination);
        Map<String, Object> fl = SapJco.table(SapJco.call(tl, "getTable", "FIELDS"));
        List<String> cols = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<?> fcols = (List<?>) fl.get("columns");
        int iName = fcols.indexOf("FIELDNAME"), iOff = fcols.indexOf("OFFSET"), iLen = fcols.indexOf("LENGTH");
        for (Object r : (List<?>) fl.get("rows")) {
            List<?> row = (List<?>) r;
            cols.add(String.valueOf(row.get(iName)).strip());
            spans.add(new int[]{Integer.parseInt(String.valueOf(row.get(iOff)).strip()), Integer.parseInt(String.valueOf(row.get(iLen)).strip())});
        }
        Map<String, Object> data = SapJco.table(SapJco.call(tl, "getTable", "DATA"));
        int iWa = ((List<?>) data.get("columns")).indexOf("WA");
        List<Object> rows = new ArrayList<>();
        for (Object r : (List<?>) data.get("rows")) {
            String wa = String.valueOf(((List<?>) r).get(iWa));
            List<Object> row = new ArrayList<>();
            for (int[] sp : spans) row.add(sp[0] >= wa.length() ? "" : wa.substring(sp[0], Math.min(wa.length(), sp[0] + sp[1])).strip());
            rows.add(row);
        }
        return Out.of("table", Values.table(cols, rows), "row_count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_function_info", name = "Get function details", category = "Functions", idempotent = true, timeoutSeconds = 120, capabilities = "network",
            description = "List the parameters of a BAPI or function module (direction, type, optional, description and the fields of structures and tables), to see what to fill in.")
    @Output(name = "parameters", type = OoaType.LIST, description = "One dictionary per parameter: name, direction, type, optional, description, fields.")
    public Map<String, Object> getFunctionInfo(ActionContext ctx,
            @Input(name = "function", type = OoaType.STRING, pattern = "^[A-Za-z0-9_/]{1,30}$", description = "Function module or BAPI name.") String function,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        Object f = function(session(ctx, session), function);
        List<Object> out = new ArrayList<>();
        for (String[] dir : List.of(new String[]{"import", "getImportParameterList"}, new String[]{"export", "getExportParameterList"},
                new String[]{"changing", "getChangingParameterList"}, new String[]{"table", "getTableParameterList"})) {
            Object list = SapJco.call(f, dir[1]);
            if (list == null) continue;
            Object md = SapJco.call(list, "getMetaData");
            int n = (Integer) SapJco.call(md, "getFieldCount");
            for (int i = 0; i < n; i++) {
                Map<String, Object> p = new LinkedHashMap<>();
                String name = (String) SapJco.call(md, "getName", i);
                p.put("name", name); p.put("direction", dir[0]);
                p.put("type", SapJco.call(md, "getTypeAsString", i));
                p.put("optional", optional(md, i));
                p.put("description", SapJco.call(md, "getDescription", i));
                boolean struct = Boolean.TRUE.equals(SapJco.call(md, "isStructure", i)), tab = Boolean.TRUE.equals(SapJco.call(md, "isTable", i));
                if (struct || tab) {
                    Object rec = SapJco.call(md, "getRecordMetaData", i);
                    List<Object> fs = new ArrayList<>();
                    int fn = (Integer) SapJco.call(rec, "getFieldCount");
                    for (int j = 0; j < fn; j++) fs.add(Out.of("name", SapJco.call(rec, "getName", j), "type", SapJco.call(rec, "getTypeAsString", j),
                            "length", BigDecimal.valueOf(((Number) SapJco.call(rec, "getLength", j)).longValue()), "description", SapJco.call(rec, "getDescription", j)));
                    p.put("fields", fs);
                }
                out.add(p);
            }
        }
        return Out.of("parameters", out);
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Session", capabilities = "network",
            description = "End the SAP logon of the session (uncommitted work is not saved).")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"sap\"", description = D_SESSION) String session) {
        Object s = ctx.sessions().remove("sap_bapi:" + (session == null || session.isBlank() ? "sap" : session));
        if (s instanceof Session ss) ss.close();
        return Map.of();
    }

    // ---------------------------------------------------------------- helpers

    static Object function(Session s, String name) {
        Object repo = SapJco.call(s.destination, "getRepository");
        Object f = SapJco.call(repo, "getFunction", name.toUpperCase(Locale.ROOT));
        if (f == null) throw new ActionException("SAP_BAPI.NO_FUNCTION", "SAP has no remote-enabled function " + name.toUpperCase(Locale.ROOT),
                "Check the name in transaction SE37 and that it is remote-enabled (BAPIs are).");
        return f;
    }

    @SuppressWarnings("unchecked")
    static void fill(Object f, String listGetter, Map<String, Object> values, String what, String function) {
        if (values == null || values.isEmpty()) return;
        Object list = SapJco.call(f, listGetter);
        if (list == null) throw new ActionException("SAP_BAPI.UNKNOWN_PARAMETER", function + " has no " + what + " parameters", "Remove them, or check the function.");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String name = e.getKey().toUpperCase(Locale.ROOT);
            if (e.getValue() instanceof Map<?, ?> m && !(m.containsKey("columns") && m.containsKey("rows"))) {
                Object st = structure(list, name, function);
                for (Map.Entry<?, ?> fe : m.entrySet()) set(st, String.valueOf(fe.getKey()), fe.getValue(), function + "." + name);
            } else if (e.getValue() instanceof List<?> || (e.getValue() instanceof Map<?, ?> m2 && m2.containsKey("rows"))) {
                Object t = table(list, name, function);
                for (Map<String, Object> row : SapJco.rows(e.getValue())) { SapJco.call(t, "appendRow"); for (Map.Entry<String, Object> c : row.entrySet()) set(t, c.getKey(), c.getValue(), function + "." + name); }
            } else set(list, name, e.getValue(), function);
        }
    }

    static Object structure(Object list, String name, String function) {
        try { return SapJco.call(list, "getStructure", name); }
        catch (ActionException e) { throw unknown(name, function, e); }
    }

    static Object table(Object list, String name, String function) {
        try { return SapJco.call(list, "getTable", name.toUpperCase(Locale.ROOT)); }
        catch (ActionException e) { throw unknown(name, function, e); }
    }

    static void set(Object record, String field, Object value, String where) {
        try { SapJco.setValue(record, field.toUpperCase(Locale.ROOT), SapJco.in(value)); }
        catch (ActionException e) { throw unknown(field, where, e); }
    }

    static ActionException unknown(String name, String where, ActionException cause) {
        String m = cause.getMessage() == null ? "" : cause.getMessage();
        if (m.matches("(?is).*(not (a member|found|supported)|field .* (does not exist|unknown)|cannot convert|conversion).*") || cause.code().equals("SAP_BAPI.ERROR"))
            return new ActionException("SAP_BAPI.BAD_PARAMETER", "Cannot set " + where + "." + name + ": " + m.replaceFirst("^SAP JCo \\w+: ", ""),
                    "Check the parameter and field names and types with Get function details.");
        return cause;
    }

    static boolean optional(Object md, int i) {
        try { return Boolean.TRUE.equals(SapJco.call(md, "isOptional", i)); } catch (RuntimeException e) { return false; }
    }

    static boolean isError(Object type) { String t = String.valueOf(type); return t.equals("E") || t.equals("A"); }

    /** RETURN (a structure or a table) as a list of {type, id, number, message, ...}. */
    @SuppressWarnings("unchecked")
    static List<Object> messages(Object ret) {
        List<Object> out = new ArrayList<>();
        if (ret instanceof Map<?, ?> m && m.get("columns") instanceof List<?> cols) {
            for (Object r : (List<?>) m.get("rows")) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) row.put(String.valueOf(cols.get(i)), ((List<?>) r).get(i));
                out.add(message(row));
            }
        } else if (ret instanceof Map<?, ?> m && !String.valueOf(m.get("TYPE") == null ? "" : m.get("TYPE")).isBlank()) out.add(message((Map<String, Object>) m));
        return out;
    }

    static Map<String, Object> message(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", String.valueOf(r.getOrDefault("TYPE", "")).strip());
        m.put("id", String.valueOf(r.getOrDefault("ID", "")).strip());
        m.put("number", String.valueOf(r.getOrDefault("NUMBER", "")).strip());
        m.put("message", String.valueOf(r.getOrDefault("MESSAGE", "")).strip());
        if (r.get("FIELD") != null) m.put("field", String.valueOf(r.get("FIELD")).strip());
        return m;
    }

    static List<Object> commitWork(ActionContext ctx, Session s, boolean wait) {
        Object f = function(s, "BAPI_TRANSACTION_COMMIT");
        if (wait) SapJco.setValue(SapJco.call(f, "getImportParameterList"), "WAIT", "X");
        SapJco.call(f, "execute", s.destination);
        List<Object> msgs = messages(SapJco.record(SapJco.call(f, "getExportParameterList")).get("RETURN"));
        if (msgs.stream().anyMatch(m -> isError(((Map<?, ?>) m).get("type"))))
            throw new ActionException("SAP_BAPI.COMMIT_FAILED", "The commit failed: " + ((Map<?, ?>) msgs.get(0)).get("message"), "See the messages.", false, Map.of("messages", msgs));
        ctx.info("Committed");
        return msgs;
    }

    static void rollback(Session s) {
        try { SapJco.call(function(s, "BAPI_TRANSACTION_ROLLBACK"), "execute", s.destination); }
        catch (ActionException e) { if (!"SAP_BAPI.NO_FUNCTION".equals(e.code())) throw e; }
    }

    /** ABAP OPTIONS lines are at most 72 characters; a condition is split at spaces. */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + w.length() > width) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
