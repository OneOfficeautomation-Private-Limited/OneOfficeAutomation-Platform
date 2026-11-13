package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Decision tables (gap G48): business rules kept as a table that business people can keep in Excel, outside the agent.
 * Columns whose header starts with "in:" are conditions on the inputs, "out:" columns are what a matching rule gives,
 * and an optional "rule" column names the rule. A condition cell is one of:
 * <ul>
 * <li>empty or "-": any value;</li>
 * <li>a value: equal (numbers as numbers; text without regard to case);</li>
 * <li>"&lt;10", "&lt;=10", "&gt;10", "&gt;=10", "!=10";</li>
 * <li>"10..20" (both included) or "[10..20)" with brackets for included and parentheses for left out;</li>
 * <li>"EU, US" (any of these); "not EU" or "not EU, US" (none of these).</li>
 * </ul>
 * The hit policy says what happens when several rules match: first (the first, in order), unique (exactly one, else an
 * error), collect (all of them, as a list).
 */
@OoaPackage(id = "ooa.rules", name = "Business rules", version = "1.0.0", description = "Decide with a decision table kept as a CSV file (or a table): conditions in in: columns, results in out: columns.")
public final class RulesPackage {

    @Action(id = "evaluate_decision_table", name = "Evaluate decision table", category = "Decision tables", idempotent = true, timeoutSeconds = 60,
            description = "Find the rules of a decision table that match the inputs and return what they give.",
            panel = "(Decision table: A CSV file = file / A table value = table), inputs, hit_policy | default_outputs, fail_when_none")
    @Output(name = "outputs", type = OoaType.DICTIONARY, description = "The out: values of the matching rule (first and unique); the first rule's for collect.")
    @Output(name = "matched", type = OoaType.BOOLEAN, description = "True when a rule matched.")
    @Output(name = "rule", type = OoaType.STRING, description = "The matching rule's name (its rule column, else its row number).")
    @Output(name = "all", type = OoaType.LIST, description = "With collect: the outputs of every matching rule, in order.")
    public Map<String, Object> evaluate(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "table=", example = "Rules/approval limits.csv", description = "A CSV file with a header row of in:NAME, out:NAME and rule columns.") String file,
            @Input(name = "table", type = OoaType.TABLE, required = false, visibleWhen = "file=", description = "The decision table as a table value, with the same columns.") Object table,
            @Input(name = "inputs", type = OoaType.DICTIONARY, example = "{\"amount\": 4200, \"region\": \"EU\"}", description = "The values the in: columns test, by name.") Map<String, Object> inputs,
            @Input(name = "hit_policy", type = OoaType.STRING, required = false, defaultValue = "\"first\"", enumValues = {"first", "unique", "collect"}, description = "When several rules match.") String policy,
            @Input(name = "default_outputs", type = OoaType.DICTIONARY, required = false, description = "What to give when no rule matches.") Map<String, Object> defaults,
            @Input(name = "fail_when_none", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Fail the step when no rule matches.") boolean failNone) {
        List<String> head;
        List<List<Object>> rows;
        if (file != null && !file.isBlank()) {
            Path p = ctx.resolvePath(file);
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Decision table not found: " + p, "Check the path.");
            List<List<String>> csv;
            try { csv = csv(Files.readString(p, StandardCharsets.UTF_8)); }
            catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
            if (csv.isEmpty()) throw new ActionException("RULES.EMPTY", "The decision table is empty", "Add rows to the decision table.");
            head = csv.get(0);
            rows = new ArrayList<>();
            for (List<String> r : csv.subList(1, csv.size())) if (r.stream().anyMatch(c -> !c.isBlank())) rows.add(new ArrayList<>(r));
        } else if (table instanceof Map<?, ?> t && t.get("columns") instanceof List<?> cols && t.get("rows") instanceof List<?> rs) {
            head = cols.stream().map(Values::text).toList();
            rows = new ArrayList<>();
            for (Object r : rs) if (r instanceof List<?> l) rows.add(new ArrayList<>(l));
        } else throw new ActionException("RULES.NO_TABLE", "Give the decision table as a CSV file or a table", "Fill in file or table.");
        return decide(head, rows, inputs == null ? Map.of() : inputs, policy == null ? "first" : policy, defaults, failNone);
    }

    static Map<String, Object> decide(List<String> head, List<List<Object>> rows, Map<String, Object> inputs, String policy, Map<String, Object> defaults, boolean failNone) {
        int ruleCol = -1;
        List<int[]> ins = new ArrayList<>(), outs = new ArrayList<>();
        List<String> inNames = new ArrayList<>(), outNames = new ArrayList<>();
        for (int i = 0; i < head.size(); i++) {
            String h = head.get(i).strip(), low = h.toLowerCase(Locale.ROOT);
            if (low.startsWith("in:")) { ins.add(new int[]{i}); inNames.add(h.substring(3).strip()); }
            else if (low.startsWith("out:")) { outs.add(new int[]{i}); outNames.add(h.substring(4).strip()); }
            else if (low.equals("rule")) ruleCol = i;
        }
        if (ins.isEmpty() || outs.isEmpty()) throw new ActionException("RULES.BAD_TABLE", "A decision table needs in: and out: columns", "Header example: rule, in:amount, in:region, out:approver");
        for (String n : inNames) if (!inputs.containsKey(n)) throw new ActionException("RULES.MISSING_INPUT", "The decision table tests " + n + ", but the inputs have no " + n, "Add the input the message names, or remove its column from the table.");
        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            boolean ok = true;
            for (int k = 0; k < ins.size() && ok; k++) {
                int c = ins.get(k)[0];
                String cond = c < row.size() && row.get(c) != null ? Values.text(row.get(c)).strip() : "";
                ok = matches(cond, inputs.get(inNames.get(k)), r + 2, head.get(c));
            }
            if (!ok) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            for (int k = 0; k < outs.size(); k++) { int c = outs.get(k)[0]; o.put(outNames.get(k), c < row.size() ? value(row.get(c)) : null); }
            hits.add(o);
            names.add(ruleCol >= 0 && ruleCol < row.size() && row.get(ruleCol) != null && !Values.text(row.get(ruleCol)).isBlank() ? Values.text(row.get(ruleCol)).strip() : "row " + (r + 2));
            if (policy.equals("first")) break;
        }
        if (policy.equals("unique") && hits.size() > 1) throw new ActionException("RULES.NOT_UNIQUE", "Several rules match: " + String.join(", ", names), "Make the rules exclusive, or use hit policy first.");
        Map<String, Object> out = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            if (failNone) throw new ActionException("RULES.NO_MATCH", "No rule of the decision table matches " + inputs, "Add a rule that matches these inputs, or a last row that matches anything.");
            out.put("outputs", defaults == null ? Map.of() : defaults); out.put("matched", false); out.put("rule", ""); out.put("all", List.of());
            return out;
        }
        out.put("outputs", hits.get(0)); out.put("matched", true); out.put("rule", names.get(0)); out.put("all", policy.equals("collect") ? hits : List.of(hits.get(0)));
        return out;
    }

    /** A result cell: numbers as numbers, true/false as booleans, the rest as text. */
    static Object value(Object v) {
        if (v == null) return null;
        if (!(v instanceof String s)) return v;
        String t = s.strip();
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) return Boolean.parseBoolean(t);
        BigDecimal n = num(t);
        return n != null ? n : t;
    }

    static BigDecimal num(String s) {
        try { return new BigDecimal(s.strip().replace(",", "").replace("_", "")); } catch (NumberFormatException e) { return null; }
    }

    /** Whether a value meets a condition cell (see the class comment). */
    static boolean matches(String cond, Object value, int row, String column) {
        if (cond.isEmpty() || cond.equals("-")) return true;
        String c = cond.strip();
        if (c.regionMatches(true, 0, "not ", 0, 4)) return !inList(c.substring(4), value);
        java.util.regex.Matcher range = java.util.regex.Pattern.compile("^([\\[(]?)\\s*(-?[\\d.,_]+)\\s*\\.\\.\\s*(-?[\\d.,_]+)\\s*([\\])]?)$").matcher(c);
        if (range.matches()) {
            BigDecimal v = number(value, row, column), lo = num(range.group(2)), hi = num(range.group(3));
            if (lo == null || hi == null) throw bad(cond, row, column);
            boolean loIn = !range.group(1).equals("("), hiIn = !range.group(4).equals(")");
            int a = v.compareTo(lo), b = v.compareTo(hi);
            return (loIn ? a >= 0 : a > 0) && (hiIn ? b <= 0 : b < 0);
        }
        for (String op : List.of("<=", ">=", "!=", "<", ">", "=")) {
            if (!c.startsWith(op)) continue;
            String rest = c.substring(op.length()).strip();
            BigDecimal limit = num(rest);
            if (limit == null) {
                boolean eq = text(value).equalsIgnoreCase(rest);
                if (op.equals("=")) return eq;
                if (op.equals("!=")) return !eq;
                throw bad(cond, row, column);
            }
            int cmp = number(value, row, column).compareTo(limit);
            return switch (op) { case "<=" -> cmp <= 0; case ">=" -> cmp >= 0; case "<" -> cmp < 0; case ">" -> cmp > 0; case "!=" -> cmp != 0; default -> cmp == 0; };
        }
        return inList(c, value);
    }

    private static boolean inList(String list, Object value) {
        for (String item : list.split(",")) {
            String t = item.strip();
            if (t.isEmpty()) continue;
            BigDecimal a = num(t), b = value instanceof Number n ? new BigDecimal(n.toString()) : value instanceof String s ? num(s) : null;
            if (a != null && b != null ? a.compareTo(b) == 0 : t.equalsIgnoreCase(text(value))) return true;
        }
        return false;
    }

    private static String text(Object v) { return v == null ? "" : Values.text(v).strip(); }

    private static BigDecimal number(Object v, int row, String column) {
        BigDecimal n = v instanceof Number x ? new BigDecimal(x.toString()) : v == null ? null : num(Values.text(v));
        if (n == null) throw new ActionException("RULES.NOT_A_NUMBER", "Row " + row + ", " + column + " compares numbers, but the input is " + (v == null ? "empty" : "'" + Values.text(v) + "'"), "Give a number for the input the message names.");
        return n;
    }

    private static ActionException bad(String cond, int row, String column) {
        return new ActionException("RULES.BAD_CONDITION", "Row " + row + ", " + column + ": cannot read the condition '" + cond + "'", "Use a value, <, <=, >, >=, !=, 10..20, a list such as EU, US, or not ...");
    }

    /** CSV with quotes (RFC 4180), commas or semicolons (Excel in many locales) as the separator. */
    static List<List<String>> csv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        String first = text.lines().findFirst().orElse("");
        char sep = first.chars().filter(ch -> ch == ';').count() > first.chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); out.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); out.add(row); }
        return out;
    }
}
