package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.UiGuard;
import com.oneofficeautomation.packages.ui.SelfHealing;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Desktop objects: find controls in Windows applications by the UI Automation properties recorded at capture time
 * (see {@link DesktopObject} for the object format) and act on them. All UI Automation work goes through one helper
 * process per agent run ({@link UiaBridge}), kept in the run's sessions and stopped when the run ends.
 *
 * <p>Before each action the run's interrupt rules are checked. When the properties marked "use" find nothing, the
 * other recorded properties are tried (AutomationId, Name, ClassName, HelpText, each with the ControlType, and last the
 * ControlType alone when exactly one such control exists) and the one that found the object is logged.
 */
@OoaPackage(id = "ooa.desktop_ui", name = "Desktop objects", version = "1.0.0",
        description = "Capture controls in Windows applications and find them again by their UI Automation properties to click, type, read and select.")
public final class DesktopUiPackage {
    public static final String SESSION = "ooa.desktop_ui.bridge";
    static final long CALL_TIMEOUT_MS = 30_000;

    static final String D_OBJECT = "The captured object (from Pick object or Studio's desktop capture): window, properties with match and use, path and index.";
    static final String D_TIMEOUT = "How long to wait for the object to appear, in seconds.";

    static void windows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new ActionException("SYSTEM.WINDOWS_ONLY", "Desktop objects work on Windows only", "Run this agent on a Windows runner.");
    }

    /** The run's helper, started on first use; a helper placed in the session by a test is used as is. */
    static UiaBridge bridge(ActionContext ctx) {
        if (ctx.sessions().get(SESSION) instanceof UiaBridge b && b.alive()) return b;
        windows();
        UiaBridge b = UiaBridge.start();
        ctx.sessions().put(SESSION, b);
        return b;
    }

    static Object call(ActionContext ctx, String op, Map<String, Object> args) {
        return bridge(ctx).call(op, args, CALL_TIMEOUT_MS, ctx::checkCancelled);
    }

    // ---------- finding ----------
    /** An element found by the helper: its properties (including "ref", the helper's handle for it). */
    record Element(Map<String, Object> props) {
        Object ref() { return props.get("ref"); }
        Map<String, Object> publicProps() {
            Map<String, Object> m = new LinkedHashMap<>(props);
            m.remove("ref");
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> windowHandles(ActionContext ctx, DesktopObject.WindowSpec spec) {
        List<Object> handles = new ArrayList<>();
        for (Object o : (List<Object>) call(ctx, "windows", Map.of())) {
            Map<String, Object> w = (Map<String, Object>) o;
            if (spec.test(String.valueOf(w.get("title")), String.valueOf(w.get("process")), String.valueOf(w.get("class_name")))) handles.add(w.get("handle"));
        }
        return handles;
    }

    /** Every element in the matching windows that satisfies all rules, in UI Automation tree order. */
    @SuppressWarnings("unchecked")
    static List<Element> findAll(ActionContext ctx, DesktopObject.WindowSpec spec, List<DesktopObject.Rule> rules) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (!spec.isEmpty()) {
            List<Object> handles = windowHandles(ctx, spec);
            if (handles.isEmpty()) return List.of();
            args.put("windows", handles);
        }
        Map<String, Object> pre = new LinkedHashMap<>();
        for (DesktopObject.Rule r : rules)
            if (r.match().equals("equals") && (r.name().equals("ControlType") || r.name().equals("AutomationId")) && !r.value().isEmpty()) pre.put(r.name(), r.value());
        if (!pre.isEmpty()) args.put("prefilter", pre);
        if (DesktopObject.needsDetail(rules)) args.put("detail", true);
        List<Element> out = new ArrayList<>();
        for (Object o : (List<Object>) call(ctx, "find", args)) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (DesktopObject.matchesAll(rules, p)) out.add(new Element(p));
        }
        return out;
    }

    /** Resolves one candidate of the healing chain once, without waiting. */
    static Optional<Element> resolve(ActionContext ctx, DesktopObject o, SelfHealing.Candidate c) {
        if (c.strategy().equals("anchor")) return nearAnchor(ctx, o, null);
        List<Element> found = findAll(ctx, o.window(), DesktopObject.decode(c.query()));
        if (c.strategy().equals("control_type")) return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
        if (c.strategy().equals("properties") && found.size() > 1 && o.anchor() != null && o.index() == 0) {
            // Several match: the anchor decides (the one nearest to where the object sat next to it).
            Optional<Element> near = nearAnchor(ctx, o, found);
            if (near.isPresent()) return near;
        }
        return DesktopObject.pick(found, o.index(), !c.strategy().equals("properties"));
    }

    /**
     * The control next to the object's anchor: the anchor is found by its own properties, then the candidate whose
     * top-left corner is nearest to anchor + offset. Without candidates, controls of the recorded ControlType within
     * 250 pixels are considered.
     */
    static Optional<Element> nearAnchor(ActionContext ctx, DesktopObject o, List<Element> candidates) {
        DesktopObject a = o.anchor();
        if (a == null) return Optional.empty();
        Optional<Element> anchor = DesktopObject.pick(findAll(ctx, a.window(), a.primary()), a.index(), false);
        if (anchor.isEmpty()) return Optional.empty();
        double[] at = DesktopObject.topLeft(anchor.get().props().get("BoundingRectangle"));
        if (at == null) return Optional.empty();
        double max = 250;
        if (candidates == null) {
            String ct = o.recorded("ControlType");
            if (ct == null || ct.isBlank()) return Optional.empty();
            candidates = findAll(ctx, o.window(), List.of(new DesktopObject.Rule("ControlType", ct, "equals")));
        } else max = Double.MAX_VALUE;
        List<Map<String, ?>> props = new ArrayList<>();
        for (Element e : candidates) props.add(e.props());
        int i = DesktopObject.nearest(props, at[0] + o.anchorDx, at[1] + o.anchorDy, max);
        return i < 0 ? Optional.empty() : Optional.of(candidates.get(i));
    }

    /** Sets the window size recorded in the object (window.resize) on every matching window. */
    static void resizeWindows(ActionContext ctx, DesktopObject o) {
        if (o.resizeWidth <= 0 || o.window().isEmpty()) return;
        for (Object h : windowHandles(ctx, o.window()))
            call(ctx, "resize", Map.of("handle", h, "width", BigDecimal.valueOf(o.resizeWidth), "height", BigDecimal.valueOf(o.resizeHeight)));
    }

    /** Checks interrupt rules, then finds the object (healing if needed), waiting up to the timeout. */
    static SelfHealing.Found<Element> locate(ActionContext ctx, DesktopObject o, double timeoutSeconds) {
        UiGuard.before(ctx);
        try { resizeWindows(ctx, o); } catch (ActionException e) { ctx.warn("Could not resize the window: " + e.getMessage()); }
        List<SelfHealing.Candidate> chain = new ArrayList<>(o.chain());
        if (o.anchor() != null) chain.add(new SelfHealing.Candidate("anchor", "anchor"));
        try {
            return SelfHealing.locate(chain, c -> resolve(ctx, o, c), (long) (Math.max(0, timeoutSeconds) * 1000), SelfHealing.SYSTEM, 400,
                    () -> UiGuard.before(ctx), ctx::warn, ctx::checkCancelled);
        } catch (ActionException e) {
            if (!"UI.ELEMENT_NOT_FOUND".equals(e.code())) throw e;
            throw new ActionException("DESKTOP_UI.OBJECT_NOT_FOUND", "The desktop object " + describe(o) + " was not found within " + timeoutSeconds + " s",
                    "Check the application window is open, or capture the object again and review which properties are used.", true);
        }
    }

    static String describe(DesktopObject o) {
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (DesktopObject.Rule r : o.primary()) j.add(r.name() + " " + r.match() + " '" + r.value() + "'");
        String w = o.window().title() == null ? "" : " in window '" + o.window().title() + "'";
        return j + w + (o.index() > 0 ? " #" + o.index() : "");
    }

    static Object act(ActionContext ctx, Element e, String action, Map<String, Object> options) {
        Map<String, Object> args = new LinkedHashMap<>(options);
        args.put("ref", e.ref());
        args.put("action", action);
        return call(ctx, "act", args);
    }

    // ---------- actions ----------
    public static final String[] ACTIONS = {"click", "set_text", "get_text", "get_property", "get_all_properties", "select_item", "expand", "collapse",
            "toggle", "check", "uncheck", "invoke", "focus", "get_children", "get_list_items", "get_table", "wait_appear", "wait_disappear",
            "scroll_into_view", "get_coordinates"};

    @Action(id = "object_action", name = "Desktop object action", category = "Capture", timeoutSeconds = 600, capabilities = "desktop",
            description = "Find a captured control in a Windows application and act on it: click, set or read text, read properties, select, expand, toggle, check, list items, read a grid, wait for it or get its position.")
    @Output(name = "result", type = OoaType.ANY, description = "What the action returns: text for get_text, a value for get_property, a dictionary for get_all_properties and get_coordinates, a list for get_children and get_list_items, a table for get_table, true/false for the wait actions.")
    @Output(name = "healed_by", type = OoaType.STRING, description = "Empty when the object was found by its used properties; otherwise the recorded property that found it (update the object).")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "False when the step was skipped because the object was not found (if_not_found = continue).")
    public Map<String, Object> objectAction(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, description = D_OBJECT) Map<String, Object> object,
            @Input(name = "action", type = OoaType.STRING, required = false, defaultValue = "\"click\"", enumValues = {"click", "set_text", "get_text", "get_property", "get_all_properties",
                    "select_item", "expand", "collapse", "toggle", "check", "uncheck", "invoke", "focus", "get_children", "get_list_items", "get_table", "wait_appear",
                    "wait_disappear", "scroll_into_view", "get_coordinates"}, description = "What to do with the object.") String action,
            @Input(name = "click_type", type = OoaType.STRING, required = false, defaultValue = "\"left\"", enumValues = {"left", "right", "double", "middle"},
                    description = "click: which click to make.") String clickType,
            @Input(name = "click_method", type = OoaType.STRING, required = false, defaultValue = "\"auto\"", enumValues = {"auto", "pattern", "mouse"},
                    description = "click: pattern presses the control through UI Automation without moving the mouse (left click only); mouse moves the pointer and clicks; auto uses a pattern when the control offers one, else the mouse.") String clickMethod,
            @Input(name = "offset_x", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "click with the mouse: pixels right of the object's centre (negative for left).") int offsetX,
            @Input(name = "offset_y", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "click with the mouse: pixels below the object's centre (negative for above).") int offsetY,
            @Input(name = "text", type = OoaType.ANY, required = false, description = "set_text: the text to enter (a Credential is accepted and never logged).") Object text,
            @Input(name = "clear_first", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "set_text: remove the existing text first; false appends.") boolean clearFirst,
            @Input(name = "text_method", type = OoaType.STRING, required = false, defaultValue = "\"auto\"", enumValues = {"auto", "set_value", "type"},
                    description = "set_text: set_value writes the value directly through UI Automation; type sends key presses one character at a time; auto writes directly when the control allows it, else types.") String textMethod,
            @Input(name = "type_delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "set_text with typing: pause between characters in milliseconds.") int typeDelay,
            @Input(name = "property", type = OoaType.STRING, required = false, example = "Value", description = "get_property: the property to read, e.g. Name, Value, IsEnabled, ToggleState, IsSelected, ExpandCollapseState.") String property,
            @Input(name = "item", type = OoaType.STRING, required = false, description = "select_item: the text of the item to select in a list, combo box, tree or tab control.") String item,
            @Input(name = "item_index", type = OoaType.NUMBER, required = false, description = "select_item: the position of the item to select (0 is the first), used when item is empty.") Integer itemIndex,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = D_TIMEOUT + " For wait_disappear, how long to wait for it to go.") double timeout,
            @Input(name = "if_not_found", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"},
                    description = "When the object is not there in time: error stops with an error; continue skips this step (result is empty, found is false) and carries on.") String ifNotFound) {
        if ("continue".equals(ifNotFound)) {
            try { return objectAction(ctx, object, action, clickType, clickMethod, offsetX, offsetY, text, clearFirst, textMethod, typeDelay, property, item, itemIndex, timeout, "error"); }
            catch (ActionException e) {
                if (!Set.of("DESKTOP_UI.OBJECT_NOT_FOUND", "DESKTOP_UI.WAIT_TIMEOUT").contains(e.code())) throw e;
                ctx.warn("Skipped '" + action + "': " + e.getMessage());
                Map<String, Object> m = out(null, "");
                m.put("found", false);
                return m;
            }
        }
        DesktopObject o = DesktopObject.parse(object);
        String a = action == null || action.isBlank() ? "click" : action;
        if (!Arrays.asList(ACTIONS).contains(a))
            throw new ActionException("DESKTOP_UI.BAD_ACTION", "Unknown object action '" + a + "'", "Use one of " + String.join(", ", ACTIONS) + ".");
        if (a.equals("wait_appear") || a.equals("wait_disappear")) {
            boolean ok = waitFor(ctx, o, a.equals("wait_appear"), timeout);
            if (!ok) throw new ActionException("DESKTOP_UI.WAIT_TIMEOUT", "The desktop object " + describe(o) + (a.equals("wait_appear") ? " did not appear" : " did not disappear") + " within " + timeout + " s",
                    "Raise timeout_seconds, or use Wait for object with fail_on_timeout false to branch instead.", true);
            return out(true, "");
        }
        SelfHealing.Found<Element> f = locate(ctx, o, timeout);
        String healed = f.healed() ? f.by().strategy() : "";
        Map<String, Object> opt = new LinkedHashMap<>();
        Object result = switch (a) {
            case "click" -> {
                String ct = clickType == null ? "left" : clickType;
                opt.put("button", ct.equals("double") ? "left" : ct);
                opt.put("double", ct.equals("double"));
                opt.put("method", clickMethod == null ? "auto" : clickMethod);
                opt.put("offset_x", BigDecimal.valueOf(offsetX));
                opt.put("offset_y", BigDecimal.valueOf(offsetY));
                act(ctx, f.element(), "click", opt);
                yield true;
            }
            case "set_text" -> {
                String t = text instanceof SecureString s ? s.reveal() : text == null ? "" : String.valueOf(text);
                opt.put("text", t);
                opt.put("clear_first", clearFirst);
                opt.put("method", textMethod == null ? "auto" : textMethod);
                opt.put("delay_ms", BigDecimal.valueOf(Math.max(0, typeDelay)));
                act(ctx, f.element(), "set_text", opt);
                yield true;
            }
            case "get_text" -> act(ctx, f.element(), "get_text", opt);
            case "get_all_properties" -> act(ctx, f.element(), "properties", opt);
            case "get_property" -> {
                if (property == null || property.isBlank()) throw new ActionException("DESKTOP_UI.NO_PROPERTY", "Give the property to read", "For example Name, Value or IsEnabled.");
                yield property(ctx, f.element(), property);
            }
            case "select_item" -> {
                if ((item == null || item.isEmpty()) && itemIndex == null) throw new ActionException("DESKTOP_UI.NO_ITEM", "Give the item text or item_index to select");
                if (item != null && !item.isEmpty()) opt.put("item", item); else opt.put("item_index", BigDecimal.valueOf(itemIndex));
                act(ctx, f.element(), "select_item", opt);
                yield true;
            }
            case "get_children" -> act(ctx, f.element(), "children", opt);
            case "get_list_items" -> act(ctx, f.element(), "list_items", opt);
            case "get_table" -> act(ctx, f.element(), "table", opt);
            case "get_coordinates" -> act(ctx, f.element(), "coordinates", opt);
            case "toggle" -> act(ctx, f.element(), "toggle", opt);
            case "scroll_into_view" -> act(ctx, f.element(), "scroll_into_view", opt);
            default -> act(ctx, f.element(), a, opt);   // expand, collapse, check, uncheck, invoke, focus
        };
        return out(result, healed);
    }

    static Map<String, Object> out(Object result, String healed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", result);
        m.put("healed_by", healed);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Object property(ActionContext ctx, Element e, String name) {
        Map<String, Object> all = (Map<String, Object>) act(ctx, e, "properties", Map.of());
        for (Map.Entry<String, Object> en : all.entrySet()) if (en.getKey().equalsIgnoreCase(name.trim())) return en.getValue();
        throw new ActionException("DESKTOP_UI.UNKNOWN_PROPERTY", "The object has no property '" + name + "'", "Available: " + String.join(", ", all.keySet()) + ".");
    }

    /** Polls until the object is present (appear) or absent (disappear). Disappearing uses only the used properties, without healing. */
    static boolean waitFor(ActionContext ctx, DesktopObject o, boolean appear, double timeoutSeconds) {
        long end = System.currentTimeMillis() + (long) (Math.max(0, timeoutSeconds) * 1000);
        UiGuard.before(ctx);
        while (true) {
            boolean present;
            if (appear) {
                try { locate(ctx, o, 0); present = true; }
                catch (ActionException e) { if (!"DESKTOP_UI.OBJECT_NOT_FOUND".equals(e.code())) throw e; present = false; }
            } else present = DesktopObject.pick(findAll(ctx, o.window(), o.primary()), o.index(), false).isPresent();
            if (present == appear) return true;
            if (System.currentTimeMillis() >= end) return false;
            ctx.checkCancelled();
            SelfHealing.SYSTEM.sleep(400);
        }
    }

    @Action(id = "find_objects", name = "Find desktop objects", category = "Capture", idempotent = true, capabilities = "desktop",
            description = "Return every control that matches the object's used properties (the index is ignored), with their recorded properties.")
    @Output(name = "objects", type = OoaType.LIST, description = "One dictionary of properties per matching control, in the order UI Automation lists them.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matching controls.")
    public Map<String, Object> findObjects(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, description = D_OBJECT) Map<String, Object> object,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "How long to wait for at least one match, in seconds; 0 looks once.") double timeout) {
        DesktopObject o = DesktopObject.parse(object);
        UiGuard.before(ctx);
        long end = System.currentTimeMillis() + (long) (Math.max(0, timeout) * 1000);
        List<Element> found;
        while ((found = findAll(ctx, o.window(), o.primary())).isEmpty() && System.currentTimeMillis() < end) { ctx.checkCancelled(); SelfHealing.SYSTEM.sleep(400); }
        List<Object> list = new ArrayList<>();
        for (Element e : found) list.add(e.publicProps());
        return Map.of("objects", list, "count", BigDecimal.valueOf(list.size()));
    }

    @Action(id = "get_object_properties", name = "Get desktop object properties", category = "Capture", idempotent = true, capabilities = "desktop",
            description = "Find a captured control and return all its current UI Automation properties and states.")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "Current properties: the recorded ones plus states such as IsReadOnly, ToggleState, IsSelected, ExpandCollapseState and SupportedPatterns.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getObjectProperties(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, description = D_OBJECT) Map<String, Object> object,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = D_TIMEOUT) double timeout) {
        SelfHealing.Found<Element> f = locate(ctx, DesktopObject.parse(object), timeout);
        return Map.of("properties", act(ctx, f.element(), "properties", Map.of()));
    }

    @Action(id = "wait_for_object", name = "Wait for desktop object", category = "Capture", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Wait until a captured control appears, or until it disappears, in a Windows application.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the wait ended in the requested state; false after a timeout when fail_on_timeout is false.")
    public Map<String, Object> waitForObject(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, description = D_OBJECT) Map<String, Object> object,
            @Input(name = "state", type = OoaType.STRING, required = false, defaultValue = "\"appear\"", enumValues = {"appear", "disappear"}, description = "Wait for the object to appear or to disappear.") String state,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait in seconds.") double timeout,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the time runs out; false returns found = false instead.") boolean fail) {
        DesktopObject o = DesktopObject.parse(object);
        boolean appear = !"disappear".equals(state);
        boolean ok = waitFor(ctx, o, appear, timeout);
        if (!ok && fail) throw new ActionException("DESKTOP_UI.WAIT_TIMEOUT", "The desktop object " + describe(o) + (appear ? " did not appear" : " did not disappear") + " within " + timeout + " s",
                "Raise timeout_seconds, or set fail_on_timeout to false to branch instead.", true);
        return Map.of("found", ok);
    }

    @Action(id = "get_table", name = "Get desktop table", category = "Capture", idempotent = true, capabilities = "desktop",
            description = "Read a grid, table or detailed list view in a Windows application into a Data Table.")
    @Output(name = "table", type = OoaType.TABLE, description = "The cell texts; columns are named after the grid's column headers.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows read.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTable(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, description = "The captured grid, table or list control. " + D_OBJECT) Map<String, Object> object,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "10000", description = "Stop after this many rows.") int maxRows,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = D_TIMEOUT) double timeout) {
        SelfHealing.Found<Element> f = locate(ctx, DesktopObject.parse(object), timeout);
        Map<String, Object> t = (Map<String, Object>) act(ctx, f.element(), "table", Map.of("max_rows", BigDecimal.valueOf(Math.max(1, maxRows))));
        Map<String, Object> table = tableOf((List<Object>) t.get("columns"), (List<Object>) t.get("rows"));
        return Map.of("table", table, "row_count", BigDecimal.valueOf(((List<?>) table.get("rows")).size()));
    }

    /** Data Table with unique, non-empty column names (Column1, Name_2, ...). */
    static Map<String, Object> tableOf(List<Object> columns, List<Object> rows) {
        List<String> cols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i) == null ? "" : String.valueOf(columns.get(i)).strip();
            if (c.isEmpty()) c = "Column" + (i + 1);
            String base = c;
            for (int n = 2; !seen.add(c.toLowerCase(Locale.ROOT)); n++) c = base + "_" + n;
            cols.add(c);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("columns", cols);
        t.put("rows", rows == null ? new ArrayList<>() : new ArrayList<>(rows));
        return t;
    }

    @Action(id = "pick_object", name = "Pick desktop object", category = "Capture", timeoutSeconds = 900, capabilities = "desktop",
            description = "Design time: point at a control in any Windows application and press Ctrl+Shift+C. A red frame shows the control under the pointer. Returns the captured object, ready for the other Desktop objects actions.")
    @Output(name = "object", type = OoaType.DICTIONARY, description = "The captured object: technology, window, properties (with match and use), path, index and screenshot.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> pickObject(ActionContext ctx,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "How long to wait for the Ctrl+Shift+C press, in seconds.") double timeout,
            @Input(name = "screenshot", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Include a PNG image of the control (base64) in the object.") boolean screenshot,
            @Input(name = "highlight", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Draw a red frame around the control under the pointer while picking.") boolean highlight) {
        long ms = (long) (Math.max(1, timeout) * 1000);
        ctx.info("Point at the control and press Ctrl+Shift+C (waiting " + (ms / 1000) + " s)");
        Map<String, Object> raw = (Map<String, Object>) bridge(ctx).call("pick",
                Map.of("timeout_ms", BigDecimal.valueOf(ms), "screenshot", screenshot, "highlight", highlight), ms + CALL_TIMEOUT_MS, ctx::checkCancelled);
        return Map.of("object", captured(ctx, raw));
    }

    /** Builds the object from a raw capture and sets its index: the captured control's position among the matches in its window. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> captured(ActionContext ctx, Map<String, Object> raw) {
        Map<String, Object> obj = DesktopObject.fromCapture(raw);
        Map<String, Object> el = raw.get("element") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object rid = el.get("RuntimeId");
        Object handle = raw.get("window") instanceof Map<?, ?> w ? w.get("handle") : null;
        if (rid != null && handle != null) {
            try {
                DesktopObject o = DesktopObject.parse(obj);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("windows", List.of(handle));
                List<Object> all = (List<Object>) call(ctx, "find", args);
                int i = 0;
                for (Object x : all) {
                    Map<String, Object> p = (Map<String, Object>) x;
                    if (!DesktopObject.matchesAll(o.primary(), p)) continue;
                    if (String.valueOf(rid).equals(String.valueOf(p.get("RuntimeId")))) { obj.put("index", BigDecimal.valueOf(i)); break; }
                    i++;
                }
            } catch (ActionException e) { ctx.warn("Could not work out the object's index: " + e.getMessage()); }
        }
        return obj;
    }

    @Condition(id = "object_exists", name = "Desktop object exists", description = "True when the captured control can be found now in a Windows application (self-healing applies).")
    public boolean objectExists(ActionContext ctx, @Input(name = "object", type = OoaType.DICTIONARY, description = D_OBJECT) Map<String, Object> object) {
        try { locate(ctx, DesktopObject.parse(object), 0); return true; }
        catch (ActionException e) { if ("DESKTOP_UI.OBJECT_NOT_FOUND".equals(e.code())) return false; throw e; }
    }
}
