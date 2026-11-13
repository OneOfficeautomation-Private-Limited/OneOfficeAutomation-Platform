package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bot migration (gap G74): turns Automation Anywhere Task Bots (their JSON content, as the Control Room API or a bot
 * export gives it) and UiPath workflows (.xaml) into OOA agents, with a report of what still needs a person.
 * <ul>
 * <li>Converted: the structure (steps, comments, if / else if / else, loops, try / catch / finally, delays, logs,
 * message boxes as log lines), assignments, and variables with their types and directions. AA's $var$ becomes ${var}
 * (or $var when the whole value is the variable); UiPath's [expression] is kept as an expression to check.</li>
 * <li>Every command it cannot convert becomes a comment step "TODO migrate: ..." with the command and its settings, in
 * its place, so nothing is lost and Studio's search finds them all. The report counts them by command, which is the
 * work list for the migration.</li>
 * <li>The agent is checked like any agent before it is saved; nothing is run.</li>
 * </ul>
 */
final class Migrate {
    private Migrate() { }

    record Result(Map<String, Object> agent, int converted, int todo, Map<String, Integer> todoByCommand, List<String> notes) {
        Map<String, Object> report(String source) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source); m.put("agent", agent.get("id")); m.put("converted", converted); m.put("todo", todo);
            m.put("todo_by_command", todoByCommand); m.put("notes", notes);
            return m;
        }
    }

    // ===================== Automation Anywhere =====================

    static final Pattern AA_VAR = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\$");
    static final Map<String, String> AA_TYPES = Map.of("STRING", "String", "NUMBER", "Number", "BOOLEAN", "Boolean", "LIST", "List", "DICTIONARY", "Dictionary",
        "TABLE", "Table", "DATETIME", "DateTime", "CREDENTIAL", "Credential", "FILE", "String", "ANY", "Any");

    /** An AA Task Bot's content: {nodes, variables, packages}. */
    @SuppressWarnings("unchecked")
    static Result fromAa(Map<String, Object> bot, String name) {
        Ctx c = new Ctx();
        List<Object> vars = new ArrayList<>();
        for (Object o : (List<Object>) bot.getOrDefault("variables", List.of())) {
            Map<String, Object> v = (Map<String, Object>) o;
            Map<String, Object> nv = new LinkedHashMap<>();
            nv.put("name", String.valueOf(v.get("name")));
            String t = String.valueOf(v.getOrDefault("type", "STRING")).toUpperCase(Locale.ROOT);
            nv.put("type", AA_TYPES.getOrDefault(t, "Any"));
            boolean in = Boolean.TRUE.equals(v.get("input")), out = Boolean.TRUE.equals(v.get("output"));
            nv.put("direction", in && out ? "in_out" : in ? "input" : out ? "output" : "local");
            if (v.get("defaultValue") instanceof Map<?, ?> dv) { Object d = value(dv, c); if (d != null && !"".equals(d)) nv.put("default", d); }
            vars.add(nv);
        }
        List<Object> steps = aaNodes((List<Object>) bot.getOrDefault("nodes", List.of()), c);
        return c.result(agent(name, "Migrated from Automation Anywhere", vars, steps));
    }

    @SuppressWarnings("unchecked")
    static List<Object> aaNodes(List<Object> nodes, Ctx c) {
        List<Object> out = new ArrayList<>();
        for (Object o : nodes) {
            Map<String, Object> n = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(n.get("disabled"))) { c.notes.add("Left out a disabled " + n.get("packageName") + "." + n.get("commandName") + " command"); continue; }
            Map<String, Object> s = aaNode(n, c);
            if (s != null) out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> aaAttrs(Map<String, Object> n) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object o : (List<Object>) n.getOrDefault("attributes", List.of())) if (o instanceof Map<?, ?> a) m.put(String.valueOf(a.get("name")), a.get("value"));
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> aaNode(Map<String, Object> n, Ctx c) {
        String pkg = String.valueOf(n.get("packageName")), cmd = String.valueOf(n.get("commandName"));
        Map<String, Object> a = aaAttrs(n);
        List<Object> kids = n.get("children") instanceof List<?> l ? aaNodes((List<Object>) l, c) : List.of();
        String ret = n.get("returnTo") instanceof Map<?, ?> r && r.get("variableName") != null ? String.valueOf(r.get("variableName")) : null;
        String key = (pkg + "." + cmd).toLowerCase(Locale.ROOT);
        switch (key) {
            case "comment.comment" -> { c.converted++; return step("core/comment", Map.of("text", text(a.get("comment"), c))); }
            case "step.step" -> { c.converted++; Map<String, Object> s = step("core/step", Map.of("title", text(a.get("title"), c))); s.put("children", kids); return s; }
            case "messagebox.messagebox" -> { c.converted++; c.notes.add("A message box became a log line (runs are unattended); use a form task for a question"); return step("core/log", Map.of("message", text(a.get("content"), c))); }
            case "logtofile.logtofile" -> { c.converted++; return step("core/log", Map.of("message", text(a.get("logContent") != null ? a.get("logContent") : a.get("content"), c))); }
            case "delay.delay" -> {
                c.converted++;
                Object ms = value(a.get("delay") != null ? a.get("delay") : a.get("delayTime"), c);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("seconds", ms instanceof Number num ? new java.math.BigDecimal(num.toString()).movePointLeft(3) : ms);
                if (!(ms instanceof Number)) args.put("unit", "milliseconds");
                return step("core/delay", args);
            }
            case "string.assign", "number.assign", "boolean.assign", "datetime.assign", "list.assign", "dictionary.assign" -> {
                if (ret == null) break;
                c.converted++;
                Object src = a.containsKey("sourceString") ? a.get("sourceString") : a.containsKey("source") ? a.get("source") : a.containsKey("sourceNumber") ? a.get("sourceNumber")
                    : a.containsKey("sourceBoolean") ? a.get("sourceBoolean") : a.values().stream().findFirst().orElse(null);
                return step("core/set", Map.of("variable", ret, "value", nullToEmpty(value(src, c))));
            }
            case "if.if" -> {
                c.converted++;
                Map<String, Object> s = step("core/if", Map.of("condition", aaCondition(a, c)));
                s.put("children", kids);
                List<Object> elseIf = new ArrayList<>();
                List<Object> els = null;
                for (Object b : n.get("branches") instanceof List<?> bl ? bl : List.of()) {
                    Map<String, Object> bn = (Map<String, Object>) b;
                    List<Object> bk = bn.get("children") instanceof List<?> l ? aaNodes((List<Object>) l, c) : List.of();
                    if ("else".equalsIgnoreCase(String.valueOf(bn.get("commandName")))) els = bk;
                    else elseIf.add(Map.of("condition", aaCondition(aaAttrs(bn), c), "children", bk));
                }
                if (!elseIf.isEmpty()) s.put("elseIf", elseIf);
                if (els != null) s.put("else", els);
                return s;
            }
            case "loop.loop" -> {
                Map<String, Object> s = aaLoop(n, a, c);
                if (s == null) break;
                c.converted++;
                s.put("children", kids);
                return s;
            }
            case "errorhandler.try", "errorhandler.errorhandler" -> {
                c.converted++;
                Map<String, Object> s = step("core/try", null);
                s.put("children", kids);
                List<Object> catches = new ArrayList<>();
                for (Object b : n.get("branches") instanceof List<?> bl ? bl : List.of()) {
                    Map<String, Object> bn = (Map<String, Object>) b;
                    List<Object> bk = bn.get("children") instanceof List<?> l ? aaNodes((List<Object>) l, c) : List.of();
                    String bc = String.valueOf(bn.get("commandName")).toLowerCase(Locale.ROOT);
                    if (bc.equals("finally")) s.put("finally", bk);
                    else {
                        Map<String, Object> cat = new LinkedHashMap<>(Map.of("codes", List.of("*"), "children", bk));
                        Map<String, Object> ba = aaAttrs(bn);
                        if (ba.get("errorMessage") instanceof Map<?, ?> em && em.get("variableName") != null) cat.put("message_to", String.valueOf(em.get("variableName")));
                        catches.add(cat);
                    }
                }
                if (!catches.isEmpty()) s.put("catch", catches);
                return s;
            }
            case "loop.break", "loop.exitloop" -> { c.converted++; return step("core/break", null); }
            case "loop.continue" -> { c.converted++; return step("core/continue", null); }
            case "errorhandler.throw" -> { c.converted++; return step("core/throw", Map.of("code", "BUSINESS.MIGRATED", "message", text(a.get("errorMessage") != null ? a.get("errorMessage") : a.get("message"), c))); }
            case "taskbot.run" -> {
                c.converted++;
                c.notes.add("A Task Bot call became Run agent: point it at the migrated agent " + text(a.get("taskbot"), c));
                return step("core/run_bot", Map.of("bot", text(a.get("taskbot"), c), "inputs", Map.of()));
            }
            default -> { }
        }
        // its settings as values a person can read (paths, ${var}), so the TODO says what to rebuild
        Map<String, Object> readable = new LinkedHashMap<>();
        a.forEach((k, v) -> readable.put(k, value(v, c)));
        if (ret != null) readable.put("result_to", ret);
        return c.todo(pkg + "." + cmd, readable, kids);
    }

    @SuppressWarnings("unchecked")
    static Object aaCondition(Map<String, Object> a, Ctx c) {
        // simple conditions: a left value, an operator and a right value (AA's String/Number/Boolean conditions)
        Object left = value(a.getOrDefault("sourceValue", a.getOrDefault("source", a.get("leftOperand"))), c);
        Object right = value(a.getOrDefault("targetValue", a.getOrDefault("target", a.get("rightOperand"))), c);
        String op = String.valueOf(value(a.getOrDefault("operator", a.get("condition")), c)).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        String oo = switch (op) {
            case "equalto", "equals", "eq", "equal" -> "eq";
            case "notequalto", "notequals", "ne" -> "ne";
            case "greaterthan", "gt" -> "gt";
            case "greaterthanorequalto", "greaterthanequalto", "ge", "gte" -> "ge";
            case "lessthan", "lt" -> "lt";
            case "lessthanorequalto", "lessthanequalto", "le", "lte" -> "le";
            case "contains", "includes" -> "contains";
            case "true", "istrue" -> "true";
            case "false", "isfalse" -> "false";
            default -> null;
        };
        if (oo != null && left != null && !oo.equals("true") && !oo.equals("false") && right != null) return Map.of("op", oo, "left", left, "right", right);
        if ("true".equals(oo) && left != null) return Map.of("op", "eq", "left", left, "right", true);
        if ("false".equals(oo) && left != null) return Map.of("op", "eq", "left", left, "right", false);
        c.todo++;
        c.todoBy.merge("If condition", 1, Integer::sum);
        c.notes.add("A condition could not be converted (" + JsonLite.write(a).replaceAll("\\s+", " ") + "); it is false until someone writes it");
        return "false";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> aaLoop(Map<String, Object> n, Map<String, Object> a, Ctx c) {
        Object it = a.get("iterator");
        String kind = it instanceof Map<?, ?> m ? String.valueOf(((Map<String, Object>) m).getOrDefault("iteratorName", "")).toLowerCase(Locale.ROOT) : "";
        Map<String, Object> args = new LinkedHashMap<>();
        String ret = n.get("returnTo") instanceof Map<?, ?> r && r.get("variableName") != null ? String.valueOf(r.get("variableName")) : null;
        Map<String, Object> ia = new LinkedHashMap<>();
        if (it instanceof Map<?, ?> m && m.get("attributes") instanceof List<?> l)
            for (Object x : l) if (x instanceof Map<?, ?> xa) ia.put(String.valueOf(xa.get("name")), xa.get("value"));
        if (kind.contains("times")) { args.put("iterator", "times"); args.put("count", value(ia.getOrDefault("times", ia.get("count")), c)); }
        else if (kind.contains("list")) { args.put("iterator", "list"); args.put("over", value(ia.getOrDefault("list", ia.get("source")), c)); }
        else if (kind.contains("table") || kind.contains("datatable") || kind.contains("row")) { args.put("iterator", "table_rows"); args.put("over", value(ia.getOrDefault("dataTable", ia.getOrDefault("table", ia.get("source"))), c)); }
        else if (kind.contains("dictionary")) { args.put("iterator", "dictionary_values"); args.put("over", value(ia.getOrDefault("dictionary", ia.get("source")), c)); }
        else if (kind.contains("while") || a.containsKey("condition")) {
            args.put("iterator", "while");
            args.put("condition", aaCondition(ia.isEmpty() ? a : ia, c));
        } else return null;
        if (ret != null) args.put("as", ret);
        return step("core/loop", args);
    }

    /** An AA value: text (with $var$), a number, a boolean, a variable, a file. */
    @SuppressWarnings("unchecked")
    static Object value(Object raw, Ctx c) {
        if (!(raw instanceof Map<?, ?> m0)) return raw;
        Map<String, Object> m = (Map<String, Object>) m0;
        String t = String.valueOf(m.getOrDefault("type", "STRING")).toUpperCase(Locale.ROOT);
        return switch (t) {
            case "NUMBER" -> {
                Object x = m.containsKey("number") ? m.get("number") : m.get("expression");
                try { yield new java.math.BigDecimal(String.valueOf(x).trim()); }
                catch (NumberFormatException e) {
                    // a calculation such as $count$ + 1 stays a calculation: {"expr": "$count + 1"}
                    String conv = AA_VAR.matcher(String.valueOf(x)).replaceAll("\\$$1").trim();
                    yield conv.matches("\\$[A-Za-z_][A-Za-z0-9_]*") ? conv : Map.of("expr", conv);
                }
            }
            case "BOOLEAN" -> m.containsKey("boolean") ? Boolean.valueOf(String.valueOf(m.get("boolean"))) : text(m.get("expression"), c);
            case "VARIABLE" -> "$" + m.get("variableName");
            case "FILE", "STRING" -> m.containsKey("expression") ? text(m.get("expression"), c) : fileText(String.valueOf(m.getOrDefault("string", "")));
            default -> m.containsKey("expression") ? text(m.get("expression"), c) : m.containsKey("string") ? m.get("string") : JsonLite.write(m);
        };
    }

    static String fileText(String s) {
        if (!s.startsWith("file:///")) return s;
        return java.net.URLDecoder.decode(s.substring(8), StandardCharsets.UTF_8);
    }

    /** AA text with $var$: "$x$" alone is the variable; inside text it becomes ${x}. */
    static Object text(Object raw, Ctx c) {
        if (raw instanceof Map<?, ?>) return value(raw, c);
        String s = raw == null ? "" : String.valueOf(raw);
        Matcher whole = AA_VAR.matcher(s);
        if (whole.matches()) return "$" + whole.group(1);
        String out = AA_VAR.matcher(s).replaceAll("\\${$1}");
        if (out.matches(".*\\$[A-Za-z_][^$]*\\$.*")) c.notes.add("Check the expression " + s + " (AA functions such as $x.String:length$ are not converted)");
        return fileText(out);
    }

    // ===================== UiPath =====================

    /** A UiPath workflow (.xaml): its Sequence, arguments and variables. */
    static Result fromUiPath(String xaml, String name) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        Element root = f.newDocumentBuilder().parse(new ByteArrayInputStream(xaml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        Ctx c = new Ctx();
        List<Object> vars = new ArrayList<>();
        // arguments: x:Members/x:Property Name Type="InArgument(x:String)"
        for (Element p : all(root, "Property")) {
            String n = p.getAttribute("Name"), t = p.getAttribute("Type");
            if (n.isEmpty()) continue;
            String dir = t.startsWith("InOutArgument") ? "in_out" : t.startsWith("InArgument") ? "input" : t.startsWith("OutArgument") ? "output" : "local";
            vars.add(Map.of("name", n, "type", uiType(t), "direction", dir));
        }
        for (Element v : all(root, "Variable")) {
            String n = v.getAttribute("Name");
            if (n.isEmpty() || vars.stream().anyMatch(x -> n.equals(((Map<?, ?>) x).get("name")))) continue;
            Map<String, Object> nv = new LinkedHashMap<>(Map.of("name", n, "type", uiType(v.getAttribute("x:TypeArguments")), "direction", "local"));
            if (!v.getAttribute("Default").isEmpty()) {
                Object d = uiExpr(v.getAttribute("Default"), c);
                if ("Number".equals(nv.get("type")) && d instanceof String ds && ds.matches("-?\\d+(\\.\\d+)?")) d = new java.math.BigDecimal(ds);
                nv.put("default", d);
            }
            vars.add(nv);
        }
        List<Object> steps = new ArrayList<>();
        for (Element e : children(root)) if (!e.getLocalName().contains(".") && !List.of("Members", "TextExpression.NamespacesForImplementation", "TextExpression.ReferencesForImplementation").contains(e.getLocalName()))
            steps.addAll(uiActivity(e, c));
        return c.result(agent(name, "Migrated from UiPath", vars, steps));
    }

    static String uiType(String t) {
        String s = t.replaceAll("^(In|Out|InOut)Argument\\((.*)\\)$", "$2").toLowerCase(Locale.ROOT);
        if (s.contains("string") && !s.contains("[]")) return "String";
        if (s.matches(".*(int32|int64|double|decimal|single).*")) return "Number";
        if (s.contains("boolean")) return "Boolean";
        if (s.contains("datetime")) return "DateTime";
        if (s.contains("datatable")) return "Table";
        if (s.contains("dictionary")) return "Dictionary";
        if (s.contains("list") || s.contains("[]")) return "List";
        if (s.contains("securestring")) return "Credential";
        return "Any";
    }

    /** UiPath's [VB expression] stays an expression; a plain value stays as it is. */
    static Object uiExpr(String v, Ctx c) {
        if (v == null) return "";
        String s = v.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            String e = s.substring(1, s.length() - 1).trim();
            if (e.matches("\"[^\"]*\"")) return e.substring(1, e.length() - 1);
            if (e.matches("-?\\d+(\\.\\d+)?")) return new java.math.BigDecimal(e);
            if (e.equalsIgnoreCase("True") || e.equalsIgnoreCase("False")) return Boolean.valueOf(e);
            if (e.matches("[A-Za-z_][A-Za-z0-9_]*")) return "$" + e;
            c.notes.add("Check the expression " + e + " (VB.NET; the agent writes it as {\"expr\": ...})");
            return Map.of("expr", e.replaceAll("\\b([A-Za-z_][A-Za-z0-9_]*)\\b(?![(.\"])", "\\$$1").replace(" AndAlso ", " && ").replace(" OrElse ", " || ")
                .replace(" And ", " && ").replace(" Or ", " || ").replace("<>", "!=").replace(" = ", " == "));
        }
        return s;
    }

    static List<Object> uiActivity(Element e, Ctx c) {
        String n = e.getLocalName();
        String name = e.getAttribute("DisplayName");
        switch (n) {
            case "Sequence", "Flowchart" -> {
                List<Object> kids = new ArrayList<>();
                for (Element k : children(e)) if (!k.getLocalName().contains(".")) kids.addAll(uiActivity(k, c));
                if (name.isEmpty() || name.equals("Sequence") || name.equals("Main Sequence")) { if (n.equals("Sequence")) c.converted++; return kids; }
                c.converted++;
                Map<String, Object> s = step("core/step", Map.of("title", name));
                s.put("children", kids);
                if (n.equals("Flowchart")) c.notes.add("The flowchart " + name + " became a step in document order; check the order of its parts");
                return List.of(s);
            }
            case "Assign" -> {
                Element to = first(e, "Assign.To"), val = first(e, "Assign.Value");
                String target = to == null ? "" : to.getTextContent().trim().replaceAll("^\\[|\\]$", "");
                Object v = val == null ? "" : uiExpr("[" + val.getTextContent().trim().replaceAll("^\\[|\\]$", "") + "]", c);
                if (!target.matches("[A-Za-z_][A-Za-z0-9_]*")) break;
                c.converted++;
                return List.of(step("core/set", Map.of("variable", target, "value", v)));
            }
            case "LogMessage", "WriteLine" -> {
                c.converted++;
                String msg = e.hasAttribute("Message") ? e.getAttribute("Message") : e.getAttribute("Text");
                return List.of(step("core/log", Map.of("message", nullToEmpty(uiExpr(msg, c)))));
            }
            case "MessageBox" -> {
                c.converted++;
                c.notes.add("A message box became a log line (runs are unattended); use a form task for a question");
                return List.of(step("core/log", Map.of("message", nullToEmpty(uiExpr(e.getAttribute("Text"), c)))));
            }
            case "Comment" -> { c.converted++; return List.of(step("core/comment", Map.of("text", e.getAttribute("Text")))); }
            case "Delay" -> {
                c.converted++;
                String d = e.getAttribute("Duration");   // hh:mm:ss
                Matcher m = Pattern.compile("(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(d);
                double secs = m.find() ? Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3)) : 1;
                return List.of(step("core/delay", Map.of("seconds", java.math.BigDecimal.valueOf(secs))));
            }
            case "If" -> {
                c.converted++;
                Map<String, Object> s = step("core/if", Map.of("condition", condition(uiExpr(e.getAttribute("Condition"), c))));
                Element then = first(e, "If.Then"), els = first(e, "If.Else");
                s.put("children", then == null ? List.of() : kids(then, c));
                if (els != null) s.put("else", kids(els, c));
                return List.of(s);
            }
            case "While", "DoWhile" -> {
                c.converted++;
                Map<String, Object> args = new LinkedHashMap<>(Map.of("iterator", "while", "condition", condition(uiExpr(e.getAttribute("Condition"), c))));
                if (n.equals("DoWhile")) args.put("check_at_end", true);
                Map<String, Object> s = step("core/loop", args);
                Element body = first(e, n + ".Body");
                s.put("children", body == null ? kids(e, c) : kids(body, c));
                return List.of(s);
            }
            case "ForEach", "ForEachRow" -> {
                c.converted++;
                Element body = first(e, "ActivityAction");
                Element arg = body == null ? null : first(body, "DelegateInArgument");
                String as = arg == null ? "item" : arg.getAttribute("Name");
                Map<String, Object> s = step("core/loop", Map.of("iterator", n.equals("ForEachRow") ? "table_rows" : "list",
                    "over", nullToEmpty(uiExpr(n.equals("ForEachRow") ? e.getAttribute("DataTable") : e.getAttribute("Values"), c)), "as", as));
                s.put("children", body == null ? List.of() : kids(body, c));
                return List.of(s);
            }
            case "TryCatch" -> {
                c.converted++;
                Map<String, Object> s = step("core/try", null);
                Element tr = first(e, "TryCatch.Try"), fin = first(e, "TryCatch.Finally"), cat = first(e, "TryCatch.Catches");
                s.put("children", tr == null ? List.of() : kids(tr, c));
                if (cat != null) {
                    List<Object> catches = new ArrayList<>();
                    for (Element k : all(cat, "Catch")) catches.add(Map.of("codes", List.of("*"), "children", kids(k, c)));
                    if (!catches.isEmpty()) s.put("catch", catches);
                }
                if (fin != null) s.put("finally", kids(fin, c));
                return List.of(s);
            }
            case "Throw" -> { c.converted++; return List.of(step("core/throw", Map.of("code", "BUSINESS.MIGRATED", "message", nullToEmpty(uiExpr(e.getAttribute("Exception"), c))))); }
            case "Break" -> { c.converted++; return List.of(step("core/break", null)); }
            case "Continue" -> { c.converted++; return List.of(step("core/continue", null)); }
            case "InvokeWorkflowFile" -> {
                c.converted++;
                String wf = e.getAttribute("WorkflowFileName");
                c.notes.add("Invoke workflow " + wf + " became Run agent: migrate that workflow too and point the step at it");
                return List.of(step("core/run_bot", Map.of("bot", wf.replaceAll("\\.xaml$", ".flow.json").replace('\\', '/'), "inputs", Map.of())));
            }
            default -> { }
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            Node at = e.getAttributes().item(i);
            if (!at.getNodeName().startsWith("xmlns") && !at.getNodeName().startsWith("sap")) attrs.put(at.getNodeName(), at.getNodeValue());
        }
        return List.of(c.todo(n, attrs, kids(e, c)));
    }

    static Object condition(Object v) { return v instanceof Map<?, ?> m && m.get("expr") != null ? String.valueOf(m.get("expr")) : v; }

    static List<Object> kids(Element e, Ctx c) {
        List<Object> out = new ArrayList<>();
        for (Element k : children(e)) if (!k.getLocalName().contains(".") && !k.getLocalName().equals("DelegateInArgument") && !k.getLocalName().equals("Variable")) {
            if (k.getLocalName().equals("ActivityAction")) out.addAll(kids(k, c));
            else out.addAll(uiActivity(k, c));
        }
        return out;
    }

    static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element el) out.add(el);
        return out;
    }

    static Element first(Element e, String local) {
        for (Element k : children(e)) if (local.equals(k.getLocalName())) return k;
        return null;
    }

    static List<Element> all(Element e, String local) {
        List<Element> out = new ArrayList<>();
        var nl = e.getElementsByTagNameNS("*", local);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    // ===================== shared =====================

    static Object nullToEmpty(Object o) { return o == null ? "" : o; }

    static Map<String, Object> step(String action, Map<String, Object> args) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("action", action);
        if (args != null) s.put("args", new LinkedHashMap<>(args));
        return s;
    }

    static Map<String, Object> agent(String name, String description, List<Object> vars, List<Object> steps) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("kind", "TaskBot"); a.put("id", id.isEmpty() ? "migrated" : id); a.put("name", name); a.put("description", description);
        a.put("variables", vars); a.put("steps", steps);
        return a;
    }

    static final class Ctx {
        int converted, todo, ids;
        final Map<String, Integer> todoBy = new TreeMap<>();
        final List<String> notes = new ArrayList<>();

        Map<String, Object> todo(String command, Map<String, Object> settings, List<Object> kids) {
            todo++;
            todoBy.merge(command, 1, Integer::sum);
            String s = JsonLite.write(settings);
            Map<String, Object> st = step("core/comment", Map.of("text", "TODO migrate: " + command + " " + (s.length() > 1500 ? s.substring(0, 1500) + "..." : s)));
            if (kids.isEmpty()) return st;
            Map<String, Object> wrap = step("core/step", Map.of("title", "TODO migrate: " + command));
            List<Object> inner = new ArrayList<>();
            inner.add(st);
            inner.addAll(kids);
            wrap.put("children", inner);
            return wrap;
        }

        Result result(Map<String, Object> agent) {
            number((List<Object>) agent.get("steps"));
            return new Result(agent, converted, todo, todoBy, new ArrayList<>(new LinkedHashSet<>(notes)));
        }

        /** Every step gets an id (s1, s2 ...) as Studio gives them, including nested ones. */
        @SuppressWarnings("unchecked")
        void number(List<Object> steps) {
            for (Object o : steps) {
                Map<String, Object> s = (Map<String, Object>) o;
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("id", "s" + (++ids));
                copy.putAll(s);
                s.clear(); s.putAll(copy);
                for (String k : List.of("children", "else", "finally")) if (s.get(k) instanceof List<?> l) number((List<Object>) l);
                for (String k : List.of("elseIf", "catch")) if (s.get(k) instanceof List<?> l) for (Object b : l) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> bl) number((List<Object>) bl);
            }
        }
    }

    // ===================== the command =====================

    /** ooa migrate aa|uipath SOURCE OUT: each bot (a file, or every .json/.xaml in a folder) to OUT/name.flow.json, and OUT/migration-report.json. */
    @SuppressWarnings("unchecked")
    static int run(String from, String source, String out) throws Exception {
        if (!List.of("aa", "uipath").contains(from)) throw new OoaException("CLI.USAGE", "Migrate from aa or uipath", "Run ooa migrate aa <folder> or ooa migrate uipath <folder>.");
        Path src = Path.of(source), dst = Path.of(out);
        Files.createDirectories(dst);
        List<Path> files = new ArrayList<>();
        String ext = from.equals("aa") ? ".json" : ".xaml";
        if (Files.isDirectory(src)) try (Stream<Path> s = Files.walk(src)) { s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(ext)).sorted().forEach(files::add); }
        else files.add(src);
        List<Object> reports = new ArrayList<>();
        int todo = 0;
        for (Path f : files) {
            String base = f.getFileName().toString().replaceAll("(?i)\\" + ext + "$", "");
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Result r;
            try {
                if (from.equals("aa")) {
                    Object j = JsonLite.parse(text);
                    Map<String, Object> bot = j instanceof Map<?, ?> m && m.get("nodes") == null && m.get("content") instanceof Map<?, ?> cm ? (Map<String, Object>) cm : (Map<String, Object>) j;
                    r = fromAa(bot, base);
                } else r = fromUiPath(text, base);
            } catch (Exception e) {
                reports.add(Map.of("source", f.toString(), "error", String.valueOf(e.getMessage())));
                System.out.println("FAILED  " + f + ": " + e.getMessage());
                continue;
            }
            Path target = dst.resolve(base + ".flow.json");
            Files.writeString(target, JsonLite.write(r.agent()), StandardCharsets.UTF_8);
            reports.add(r.report(f.toString()));
            todo += r.todo();
            System.out.printf("%-7s %s -> %s (%d converted, %d to do)%n", r.todo() == 0 ? "DONE" : "PART", f.getFileName(), target.getFileName(), r.converted(), r.todo());
        }
        Files.writeString(dst.resolve("migration-report.json"), JsonLite.write(Map.of("from", from, "bots", reports)), StandardCharsets.UTF_8);
        System.out.println(files.size() + " bot(s); " + todo + " command(s) to migrate by hand (search the agents for \"TODO migrate\"). Report: " + dst.resolve("migration-report.json"));
        return 0;
    }
}
