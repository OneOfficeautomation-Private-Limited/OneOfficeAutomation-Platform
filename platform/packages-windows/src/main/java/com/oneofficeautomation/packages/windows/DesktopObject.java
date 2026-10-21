package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.SelfHealing;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A captured desktop object: the window it lives in, the UI Automation properties recorded for it (each with a match
 * mode and a "use" flag), the path of its ancestors and an index for when several objects match. This is the format
 * Studio shows and edits, shared with web capture:
 *
 * <pre>{"technology": "desktop",
 *  "window": {"title": "Untitled - Notepad", "title_match": "wildcard", "process": "notepad"},
 *  "properties": [{"name": "ControlType", "value": "Edit", "match": "equals", "use": true}, ...],
 *  "path": [{"ControlType": "Window", "Name": "..."}, ...],
 *  "index": 0,
 *  "screenshot": "base64 PNG"}</pre>
 *
 * <p>Text matching ignores case for every mode except regex, which is used as written (add (?i) to ignore case) and
 * may match anywhere in the value. Wildcards (* and ?) must match the whole value. This class has no native code, so
 * the matching and self-healing order are unit-tested.
 */
public final class DesktopObject {
    /** Properties recorded at capture, in the order Studio lists them. */
    public static final List<String> PROPERTIES = List.of("Name", "AutomationId", "ClassName", "ControlType", "FrameworkId",
            "LocalizedControlType", "HelpText", "AccessKey", "IsEnabled", "IsOffscreen", "Value", "BoundingRectangle");
    public static final List<String> MATCHES = List.of("equals", "contains", "starts_with", "ends_with", "regex", "wildcard", "not_equals");

    /** One property test. */
    public record Rule(String name, String value, String match) {
        public Rule {
            if (name == null || name.isBlank()) throw bad("A property has no name");
            match = match == null || match.isBlank() ? "equals" : match.trim().toLowerCase(Locale.ROOT);
            if (!MATCHES.contains(match)) throw bad("Property '" + name + "': match must be one of " + MATCHES + ", not '" + match + "'");
            value = value == null ? "" : value;
            if (match.equals("regex")) compile(value, name);
        }
        public boolean test(Map<String, ?> props) {
            Object v = props.get(name);
            return DesktopObject.matches(match, value, v == null ? "" : String.valueOf(v));
        }
        Map<String, Object> toMap() { Map<String, Object> m = new LinkedHashMap<>(); m.put("name", name); m.put("value", value); m.put("match", match); return m; }
    }

    /** One recorded property: the rule plus whether matching uses it. */
    public record Property(Rule rule, boolean use) {}

    /** The top-level window; null fields are not checked. */
    public record WindowSpec(String title, String titleMatch, String process, String className) {
        public boolean test(String windowTitle, String windowProcess, String windowClass) {
            if (notBlank(title) && !matches(titleMatch, title, windowTitle == null ? "" : windowTitle)) return false;
            if (notBlank(process) && !processName(process).equalsIgnoreCase(processName(windowProcess))) return false;
            return !notBlank(className) || className.equalsIgnoreCase(windowClass == null ? "" : windowClass);
        }
        public boolean isEmpty() { return !notBlank(title) && !notBlank(process) && !notBlank(className); }
        static String processName(String p) {
            String s = p == null ? "" : p.trim();
            return s.toLowerCase(Locale.ROOT).endsWith(".exe") ? s.substring(0, s.length() - 4) : s;
        }
    }

    final WindowSpec window;
    final List<Property> properties;
    final List<Object> path;
    final int index;

    private DesktopObject(WindowSpec window, List<Property> properties, List<Object> path, int index) {
        this.window = window; this.properties = properties; this.path = path; this.index = index;
    }

    static ActionException bad(String message) {
        return new ActionException("DESKTOP_UI.BAD_OBJECT", message, "Capture the object again with Pick object, or fix the object dictionary.");
    }

    static boolean notBlank(Object o) { return o != null && !String.valueOf(o).isBlank(); }

    static Pattern compile(String regex, String what) {
        try { return Pattern.compile(regex); }
        catch (PatternSyntaxException e) { throw bad("Property '" + what + "': invalid regular expression: " + e.getDescription()); }
    }

    /** True when the actual value satisfies the expected value under the match mode. */
    public static boolean matches(String match, String expected, String actual) {
        String e = expected == null ? "" : expected, a = actual == null ? "" : actual;
        String el = e.toLowerCase(Locale.ROOT), al = a.toLowerCase(Locale.ROOT);
        return switch (match == null ? "equals" : match) {
            case "equals" -> al.equals(el);
            case "not_equals" -> !al.equals(el);
            case "contains" -> al.contains(el);
            case "starts_with" -> al.startsWith(el);
            case "ends_with" -> al.endsWith(el);
            case "regex" -> compile(e, "value").matcher(a).find();
            case "wildcard" -> wildcard(e).matcher(a).matches();
            default -> throw bad("Unknown match '" + match + "'");
        };
    }

    static Pattern wildcard(String w) {
        StringBuilder sb = new StringBuilder();
        for (char c : w.toCharArray()) sb.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    /** Reads and validates the object dictionary. */
    @SuppressWarnings("unchecked")
    public static DesktopObject parse(Map<String, Object> o) {
        if (o == null || o.isEmpty()) throw bad("No object was given");
        Object tech = o.get("technology");
        if (tech != null && !"desktop".equalsIgnoreCase(String.valueOf(tech)))
            throw bad("This object was captured with technology '" + tech + "'; Desktop objects needs a desktop capture");
        WindowSpec w = new WindowSpec(null, "wildcard", null, null);
        if (o.get("window") instanceof Map<?, ?> wm) {
            Object tm = wm.get("title_match");
            String titleMatch = tm == null || String.valueOf(tm).isBlank() ? "wildcard" : String.valueOf(tm).trim().toLowerCase(Locale.ROOT);
            if (!MATCHES.contains(titleMatch)) throw bad("window.title_match must be one of " + MATCHES);
            String title = wm.get("title") == null ? null : String.valueOf(wm.get("title"));
            if (titleMatch.equals("regex") && title != null) compile(title, "window title");
            w = new WindowSpec(title, titleMatch, str(wm.get("process")), str(wm.get("class_name")));
        } else if (o.get("window") != null) throw bad("window must be a dictionary");
        List<Property> props = new ArrayList<>();
        if (!(o.get("properties") instanceof List<?> pl)) throw bad("The object has no properties list");
        for (Object p : pl) {
            if (!(p instanceof Map<?, ?> pm)) throw bad("Each property must be a dictionary with name, value, match and use");
            Object use = pm.get("use");
            boolean u = use == null || Boolean.TRUE.equals(use) || "true".equalsIgnoreCase(String.valueOf(use));
            props.add(new Property(new Rule(str(pm.get("name")), pm.get("value") == null ? "" : String.valueOf(pm.get("value")), str(pm.get("match"))), u));
        }
        if (props.stream().noneMatch(Property::use)) throw bad("Mark at least one property with use: true so the object can be found");
        int index = 0;
        if (o.get("index") != null) {
            try { index = new BigDecimal(String.valueOf(o.get("index"))).intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw bad("index must be a whole number"); }
            if (index < 0) throw bad("index must be 0 or more");
        }
        List<Object> path = o.get("path") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        return new DesktopObject(w, List.copyOf(props), path, index);
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }

    public WindowSpec window() { return window; }
    public int index() { return index; }

    /** The rules marked use: true. */
    public List<Rule> primary() { return properties.stream().filter(Property::use).map(Property::rule).toList(); }

    String recorded(String name) {
        for (Property p : properties) if (p.rule().name().equalsIgnoreCase(name)) return p.rule().value();
        return null;
    }

    /** True when every rule matches the element's properties. */
    public static boolean matchesAll(List<Rule> rules, Map<String, ?> props) {
        for (Rule r : rules) if (!r.test(props)) return false;
        return true;
    }

    /**
     * Picks the element for a rule set: the recorded index among the matches. A healing candidate that finds exactly
     * one element takes it even when the recorded index is higher (the index was counted with the original properties).
     */
    public static <E> Optional<E> pick(List<E> matches, int index, boolean healing) {
        if (index < matches.size()) return Optional.of(matches.get(index));
        if (healing && matches.size() == 1) return Optional.of(matches.get(0));
        return Optional.empty();
    }

    // ---------- self-healing ----------
    /** Healing order after the properties marked use: true. Each one combines the recorded ControlType with one stable property. */
    public static final List<String> HEAL_ORDER = List.of("automation_id", "name", "class_name", "help_text", "control_type");

    /**
     * The candidates tried in order: the properties marked use, then one candidate per recorded property in
     * {@link #HEAL_ORDER}, using the recorded values exactly (equals). Each query is the JSON list of rules.
     */
    public List<SelfHealing.Candidate> chain() {
        Map<String, Object> recorded = new LinkedHashMap<>();   // keyed by strategy, as SelfHealing expects
        for (String s : HEAL_ORDER) { String v = recorded(HEAL_PROPERTY.get(s)); if (notBlank(v)) recorded.put(s, v); }
        String ct = recorded("ControlType");
        return SelfHealing.chain(new SelfHealing.Candidate("properties", encode(primary())), recorded, HEAL_ORDER, (strategy, rec) -> {
            List<Rule> rules = new ArrayList<>();
            if (notBlank(ct)) rules.add(new Rule("ControlType", ct, "equals"));
            if (!strategy.equals("control_type")) rules.add(new Rule(HEAL_PROPERTY.get(strategy), String.valueOf(rec.get(strategy)), "equals"));
            return encode(rules);
        });
    }

    static final Map<String, String> HEAL_PROPERTY = Map.of("automation_id", "AutomationId", "name", "Name", "class_name", "ClassName",
            "help_text", "HelpText", "control_type", "ControlType");

    /** Rules as the canonical JSON used for candidate queries. */
    static String encode(List<Rule> rules) {
        List<Object> l = new ArrayList<>();
        for (Rule r : rules) l.add(r.toMap());
        return JsonLite.write(l);
    }

    @SuppressWarnings("unchecked")
    static List<Rule> decode(String query) {
        List<Rule> out = new ArrayList<>();
        for (Object o : (List<Object>) JsonLite.parse(query)) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new Rule(str(m.get("name")), str(m.get("value")), str(m.get("match"))));
        }
        return out;
    }

    // ---------- capture ----------
    static final Pattern GENERATED_ID = Pattern.compile("^(\\d{6,}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*|.*\\d{5,}.*)$");

    /**
     * Builds the object dictionary from what the bridge captured under the pointer. Defaults: ControlType is always
     * used; AutomationId when it looks stable; Name when there is no usable AutomationId; ClassName with Name when
     * there is no usable AutomationId. The window is matched by its title (wildcard, editable) and process.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromCapture(Map<String, Object> raw) {
        Map<String, Object> el = raw.get("element") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> win = raw.get("window") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String aid = String.valueOf(el.getOrDefault("AutomationId", "")), name = String.valueOf(el.getOrDefault("Name", ""));
        boolean useAid = !aid.isBlank() && !GENERATED_ID.matcher(aid).matches();
        boolean useName = !useAid && !name.isBlank() && name.length() <= 120 && name.indexOf('\n') < 0;
        List<Object> props = new ArrayList<>();
        for (String p : PROPERTIES) {
            String v = el.get(p) == null ? "" : String.valueOf(el.get(p));
            boolean use = switch (p) {
                case "ControlType" -> true;
                case "AutomationId" -> useAid;
                case "Name" -> useName;
                case "ClassName" -> !useAid && !v.isBlank();
                default -> false;
            };
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p); pm.put("value", v); pm.put("match", "equals"); pm.put("use", use);
            props.add(pm);
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("title", String.valueOf(win.getOrDefault("title", "")));
        w.put("title_match", "wildcard");
        w.put("process", String.valueOf(win.getOrDefault("process", "")));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "desktop");
        o.put("window", w);
        o.put("properties", props);
        o.put("path", raw.get("path") instanceof List<?> l ? l : List.of());
        o.put("index", BigDecimal.ZERO);
        Object shot = raw.get("screenshot");
        if (shot != null && !String.valueOf(shot).isEmpty()) o.put("screenshot", String.valueOf(shot));
        return o;
    }
}
