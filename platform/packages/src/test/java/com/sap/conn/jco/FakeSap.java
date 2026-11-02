package com.sap.conn.jco;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * A tiny in-memory "SAP system" behind the JCo stand-in, for the SAP BAPI toolkit tests: a user BAPI, an order BAPI
 * that needs a commit, BAPI_TRANSACTION_COMMIT / ROLLBACK and RFC_READ_TABLE on a small MARA table.
 */
public final class FakeSap {
    private FakeSap() {}

    public static final String PASSWORD = "init-pw";
    public static final List<Map<String, Object>> PENDING = new ArrayList<>(), COMMITTED = new ArrayList<>();
    public static final List<String> CALLS = new ArrayList<>();

    record Field(String name, String type, int length, List<Field> sub, boolean table, boolean optional) { }

    static Field ch(String n, int len) { return new Field(n, "CHAR", len, null, false, true); }
    static Field num(String n) { return new Field(n, "INT", 10, null, false, true); }
    static Field bcd(String n) { return new Field(n, "BCD", 13, null, false, true); }
    static Field date(String n) { return new Field(n, "DATE", 8, null, false, true); }
    static Field req(Field f) { return new Field(f.name, f.type, f.length, f.sub, f.table, false); }
    static Field struct(String n, Field... f) { return new Field(n, "STRUCTURE", 0, List.of(f), false, true); }
    static Field tab(String n, Field... f) { return new Field(n, "TABLE", 0, List.of(f), true, true); }
    static Field[] msg() { return new Field[]{ch("TYPE", 1), ch("ID", 20), ch("NUMBER", 3), ch("MESSAGE", 220), ch("FIELD", 30)}; }

    static final class Meta implements JCoListMetaData, JCoRecordMetaData {
        final List<Field> fields;
        Meta(List<Field> f) { fields = f; }
        public int getFieldCount() { return fields.size(); }
        public String getName(int i) { return fields.get(i).name; }
        public String getTypeAsString(int i) { return fields.get(i).type; }
        public String getDescription(int i) { return fields.get(i).name.toLowerCase(Locale.ROOT); }
        public boolean isStructure(int i) { return fields.get(i).sub != null && !fields.get(i).table; }
        public boolean isTable(int i) { return fields.get(i).table; }
        public JCoRecordMetaData getRecordMetaData(int i) { return new Meta(fields.get(i).sub); }
        public int getLength(int i) { return fields.get(i).length; }
        public boolean isOptional(int i) { return fields.get(i).optional; }
        Field field(String n, String owner) {
            for (Field f : fields) if (f.name.equals(n)) return f;
            throw new RuntimeException("(127) JCO_ERROR_FIELD_NOT_FOUND: Field " + n + " not a member of " + owner);
        }
    }

    static Object convert(Field f, Object v, String owner) {
        if (v == null) return f.type.equals("CHAR") ? "" : null;
        try {
            return switch (f.type) {
                case "INT" -> v instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(v).strip());
                case "BCD" -> new BigDecimal(String.valueOf(v));
                case "DATE" -> { String s = String.valueOf(v).replace("-", ""); yield Date.from(LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)),
                        Integer.parseInt(s.substring(6, 8))).atStartOfDay(ZoneId.systemDefault()).toInstant()); }
                default -> { String s = String.valueOf(v); yield s.length() > f.length ? s.substring(0, f.length) : s; }
            };
        } catch (RuntimeException e) {
            throw new RuntimeException("(122) JCO_ERROR_CONVERSION: Cannot convert value " + v + " to " + f.type + " for field " + f.name + " of " + owner);
        }
    }

    static Object initial(Field f, String owner) {
        if (f.table) return new Tab(new Meta(f.sub), owner + "." + f.name);
        if (f.sub != null) return new Rec(new Meta(f.sub), owner + "." + f.name);
        return f.type.equals("CHAR") ? "" : f.type.equals("INT") ? 0 : f.type.equals("BCD") ? BigDecimal.ZERO : null;
    }

    static class Rec implements JCoParameterList, JCoStructure {
        final Meta meta; final String owner; final Map<String, Object> values = new LinkedHashMap<>();
        Rec(Meta m, String owner) { meta = m; this.owner = owner; for (Field f : m.fields) values.put(f.name, initial(f, owner)); }
        public Object getValue(String n) { meta.field(n, owner); return values.get(n); }
        public Object getValue(int i) { return values.get(meta.fields.get(i).name); }
        public void setValue(String n, Object v) {
            Field f = meta.field(n, owner);
            if (f.sub != null) throw new RuntimeException("(122) JCO_ERROR_CONVERSION: Field " + n + " is a structure or table");
            values.put(n, convert(f, v, owner));
        }
        public void setValue(String n, String v) { setValue(n, (Object) v); }
        public void setValue(int i, Object v) { setValue(meta.fields.get(i).name, v); }
        public JCoMetaData getMetaData() { return meta; }
        public JCoStructure getStructure(String n) { Field f = meta.field(n, owner); if (f.sub == null || f.table) throw new RuntimeException("(127) JCO_ERROR_FIELD_NOT_FOUND: " + n + " is not a structure"); return (JCoStructure) values.get(n); }
        public JCoTable getTable(String n) { Field f = meta.field(n, owner); if (!f.table) throw new RuntimeException("(127) JCO_ERROR_FIELD_NOT_FOUND: " + n + " is not a table"); return (JCoTable) values.get(n); }
        String text(String n) { return String.valueOf(values.get(n)).strip(); }
    }

    static final class Tab implements JCoTable {
        final Meta meta; final String owner; final List<Map<String, Object>> rows = new ArrayList<>(); int cur = -1;
        Tab(Meta m, String owner) { meta = m; this.owner = owner; }
        public void appendRow() { Map<String, Object> r = new LinkedHashMap<>(); for (Field f : meta.fields) r.put(f.name, initial(f, owner)); rows.add(r); cur = rows.size() - 1; }
        public int getNumRows() { return rows.size(); }
        public void setRow(int r) { cur = r; }
        public Object getValue(String n) { meta.field(n, owner); return rows.get(cur).get(n); }
        public Object getValue(int i) { return rows.get(cur).get(meta.fields.get(i).name); }
        public void setValue(String n, Object v) { if (cur < 0) throw new RuntimeException("(130) JCO_ERROR_ILLEGAL_TID: no current row"); rows.get(cur).put(n, convert(meta.field(n, owner), v, owner)); }
        public void setValue(String n, String v) { setValue(n, (Object) v); }
        public void setValue(int i, Object v) { setValue(meta.fields.get(i).name, v); }
        public JCoMetaData getMetaData() { return meta; }
        public JCoStructure getStructure(String n) { throw new UnsupportedOperationException(); }
        public JCoTable getTable(String n) { throw new UnsupportedOperationException(); }
        void add(Map<String, Object> values) { appendRow(); values.forEach(this::setValue); }
    }

    static final class Function implements JCoFunction {
        final String name; final Rec imp, exp, chg, tab;
        Function(String name, List<Field> imp, List<Field> exp, List<Field> chg, List<Field> tab) {
            this.name = name;
            this.imp = imp.isEmpty() ? null : new Rec(new Meta(imp), name);
            this.exp = exp.isEmpty() ? null : new Rec(new Meta(exp), name);
            this.chg = chg.isEmpty() ? null : new Rec(new Meta(chg), name);
            this.tab = tab.isEmpty() ? null : new Rec(new Meta(tab), name);
        }
        public String getName() { return name; }
        public JCoParameterList getImportParameterList() { return imp; }
        public JCoParameterList getExportParameterList() { return exp; }
        public JCoParameterList getChangingParameterList() { return chg; }
        public JCoParameterList getTableParameterList() { return tab; }
        public void execute(JCoDestination d) throws JCoException { CALLS.add(name); run(this); }
    }

    public static final class Destination implements JCoDestination {
        final Properties p; public boolean stateful;
        public Destination(Properties p) { this.p = p; }
        public void ping() throws JCoException {
            if (!PASSWORD.equals(p.getProperty("jco.client.passwd")))
                throw new JCoException(JCoException.JCO_ERROR_LOGON_FAILURE, "JCO_ERROR_LOGON_FAILURE", "Name or password is incorrect (repeat logon)");
            if ("nohost".equals(p.getProperty("jco.client.ashost")))
                throw new JCoException(JCoException.JCO_ERROR_COMMUNICATION, "JCO_ERROR_COMMUNICATION", "Connect to SAP gateway failed: partner 'nohost:sapgw00' not reached");
        }
        public JCoRepository getRepository() { return FakeSap::function; }
        public JCoAttributes getAttributes() {
            return new JCoAttributes() {
                public String getSystemID() { return "ERP"; }
                public String getClient() { return p.getProperty("jco.client.client"); }
                public String getUser() { return p.getProperty("jco.client.user", "").toUpperCase(Locale.ROOT); }
                public String getPartnerHost() { return p.getProperty("jco.client.ashost", "msg-" + p.getProperty("jco.client.mshost")); }
                public String getPartnerRelease() { return "758"; }
            };
        }
        public Properties properties() { return p; }
    }

    static JCoFunction function(String name) {
        return switch (name) {
            case "BAPI_USER_GET_DETAIL" -> new Function(name, List.of(req(ch("USERNAME", 12))),
                    List.of(struct("ADDRESS", ch("FIRSTNAME", 40), ch("LASTNAME", 40)), struct("LOGONDATA", date("GLTGB"))), List.of(), List.of(tab("RETURN", msg())));
            case "Z_CREATE_ORDER" -> new Function(name, List.of(struct("HEADER", ch("CUSTOMER", 10), date("DOC_DATE"))), List.of(ch("ORDER_NUMBER", 10)),
                    List.of(num("COUNTER")), List.of(tab("ITEMS", ch("MATERIAL", 18), bcd("QUANTITY")), tab("RETURN", msg())));
            case "BAPI_TRANSACTION_COMMIT" -> new Function(name, List.of(ch("WAIT", 1)), List.of(struct("RETURN", msg())), List.of(), List.of());
            case "BAPI_TRANSACTION_ROLLBACK" -> new Function(name, List.of(), List.of(struct("RETURN", msg())), List.of(), List.of());
            case "RFC_READ_TABLE" -> new Function(name, List.of(ch("QUERY_TABLE", 30), ch("DELIMITER", 1), num("ROWCOUNT"), num("ROWSKIPS")), List.of(), List.of(),
                    List.of(tab("OPTIONS", ch("TEXT", 72)), tab("FIELDS", ch("FIELDNAME", 30), ch("OFFSET", 6), ch("LENGTH", 6), ch("TYPE", 1), ch("FIELDTEXT", 60)), tab("DATA", ch("WA", 512))));
            default -> null;
        };
    }

    static final List<Map<String, String>> MARA = List.of(Map.of("MATNR", "100-100", "MTART", "FERT"), Map.of("MATNR", "100-200", "MTART", "ROH"),
            Map.of("MATNR", "100-300", "MTART", "FERT"));

    static void run(Function f) throws JCoException {
        switch (f.name) {
            case "BAPI_USER_GET_DETAIL" -> {
                String u = f.imp.text("USERNAME");
                if (u.equals("ALICE")) {
                    Rec a = (Rec) f.exp.getStructure("ADDRESS"); a.setValue("FIRSTNAME", "Alice"); a.setValue("LASTNAME", "Example");
                    ((Rec) f.exp.getStructure("LOGONDATA")).setValue("GLTGB", "2026-12-31");
                } else ((Tab) f.tab.getTable("RETURN")).add(Map.of("TYPE", "E", "ID", "01", "NUMBER", "124", "MESSAGE", "User " + u + " does not exist"));
            }
            case "Z_CREATE_ORDER" -> {
                Tab items = (Tab) f.tab.getTable("ITEMS");
                Tab ret = (Tab) f.tab.getTable("RETURN");
                if (items.getNumRows() == 0) { ret.add(Map.of("TYPE", "E", "ID", "V1", "NUMBER", "001", "MESSAGE", "No items")); return; }
                String number = "45" + String.format("%08d", COMMITTED.size() + PENDING.size() + 1);
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("number", number); order.put("customer", ((Rec) f.imp.getStructure("HEADER")).text("CUSTOMER"));
                order.put("doc_date", ((Rec) f.imp.getStructure("HEADER")).values.get("DOC_DATE"));
                List<String> lines = new ArrayList<>();
                for (Map<String, Object> r : items.rows) lines.add(r.get("MATERIAL") + " x " + r.get("QUANTITY"));
                order.put("items", lines);
                PENDING.add(order);
                f.exp.setValue("ORDER_NUMBER", number);
                f.chg.setValue("COUNTER", ((Integer) f.chg.getValue("COUNTER")) + 1);
                ret.add(Map.of("TYPE", "S", "ID", "V1", "NUMBER", "311", "MESSAGE", "Order " + number + " has been saved"));
            }
            case "BAPI_TRANSACTION_COMMIT" -> { COMMITTED.addAll(PENDING); PENDING.clear(); }
            case "BAPI_TRANSACTION_ROLLBACK" -> PENDING.clear();
            case "RFC_READ_TABLE" -> {
                if (!f.imp.text("QUERY_TABLE").equals("MARA")) throw new JCoException(JCoException.JCO_ERROR_ABAP_EXCEPTION, "TABLE_NOT_AVAILABLE", "TABLE_NOT_AVAILABLE");
                Tab fields = (Tab) f.tab.getTable("FIELDS");
                List<String> names = new ArrayList<>();
                for (Map<String, Object> r : fields.rows) names.add(String.valueOf(r.get("FIELDNAME")).strip());
                if (names.isEmpty()) names = List.of("MATNR", "MTART");
                fields.rows.clear();
                Map<String, Integer> len = Map.of("MATNR", 18, "MTART", 4);
                int off = 0;
                for (String n : names) {
                    if (!len.containsKey(n)) throw new JCoException(JCoException.JCO_ERROR_ABAP_EXCEPTION, "FIELD_NOT_VALID", "FIELD_NOT_VALID");
                    fields.add(Map.of("FIELDNAME", n, "OFFSET", String.format("%06d", off), "LENGTH", String.format("%06d", len.get(n)), "TYPE", "C"));
                    off += len.get(n);
                }
                StringBuilder where = new StringBuilder();
                for (Map<String, Object> r : ((Tab) f.tab.getTable("OPTIONS")).rows) where.append(r.get("TEXT")).append(' ');
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)\\s*=\\s*'([^']*)'").matcher(where);
                String wf = m.find() ? m.group(1) : null, wv = wf == null ? null : m.group(2);
                int skip = (Integer) f.imp.getValue("ROWSKIPS"), max = (Integer) f.imp.getValue("ROWCOUNT"), seen = 0;
                Tab data = (Tab) f.tab.getTable("DATA");
                for (Map<String, String> row : MARA) {
                    if (wf != null && !wv.equals(row.get(wf))) continue;
                    if (seen++ < skip) continue;
                    if (max > 0 && data.getNumRows() >= max) break;
                    StringBuilder wa = new StringBuilder();
                    for (String n : names) wa.append(String.format("%-" + len.get(n) + "s", row.get(n)));
                    data.add(Map.of("WA", wa.toString()));
                }
            }
            default -> throw new JCoException(104, "FU_NOT_FOUND", "Function " + f.name + " not found");
        }
    }
}
