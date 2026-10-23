package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Interactive forms: show a form made in Studio's form designer to the person at the runner, wait for them to submit
 * it or press a button, react to their changes, and read or change its fields while it stays open. The form window
 * closes when the agent closes it or the run ends.
 */
@OoaPackage(id = "ooa.forms", name = "Interactive forms", version = "1.0.0",
        description = "Show a form to the person at the runner, react to its buttons and changes, and read or change its fields while it is open.")
public final class FormsPackage {

    private static final String D_NAME = "Name of the open form (by default the form's id, as given when it was displayed).";
    private static final String D_ELEMENT = "Field or button id on the form, as set in the form designer.";
    private static final Set<String> NOT_INPUTS = Set.of("heading", "paragraph", "button", "area");

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fields(Object list) { return list instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l.stream().filter(Map.class::isInstance).toList() : List.of(); }

    /** The field, button or area with this id, also inside dynamic areas; null when there is none. */
    static Map<String, Object> find(Map<String, Object> form, String id) { return find(fields(form.get("fields")), id); }
    private static Map<String, Object> find(List<Map<String, Object>> list, String id) {
        for (Map<String, Object> f : list) {
            if (id.equals(f.get("id"))) return f;
            Map<String, Object> in = find(fields(f.get("fields")), id);
            if (in != null) return in;
        }
        return null;
    }
    static boolean isInput(Map<String, Object> f) { return f != null && !NOT_INPUTS.contains(String.valueOf(f.get("type"))); }
    private static void inputs(List<Map<String, Object>> list, List<Map<String, Object>> out) {
        for (Map<String, Object> f : list) { if (isInput(f)) out.add(f); inputs(fields(f.get("fields")), out); }
    }
    static List<Map<String, Object>> inputs(Map<String, Object> form) { List<Map<String, Object>> out = new ArrayList<>(); inputs(fields(form.get("fields")), out); return out; }

    private static FormWindow.Session session(ActionContext ctx, String name) { return session(ctx, name, false); }
    /** The open form; with closedToo, also one the person has just closed (so a wait can report that). */
    private static FormWindow.Session session(ActionContext ctx, String name, boolean closedToo) {
        Object s = ctx.sessions().get("form:" + (name == null ? "" : name.strip()));
        if (!(s instanceof FormWindow.Session fs) || (fs.closed && !closedToo))
            throw new ActionException("FORMS.NOT_OPEN", "The form '" + name + "' is not open", "Display it first (Display form) with this name, and do not close it before this step.");
        return fs;
    }
    private static Map<String, Object> element(FormWindow.Session s, String id) {
        Map<String, Object> f = id == null ? null : find(s.form, id.strip());
        if (f == null) throw new ActionException("FORMS.ELEMENT_NOT_FOUND", "The form has no field or button '" + id + "'", "Use an id from the form designer (select the field to see its id).");
        return f;
    }

    private static Object initial(Map<String, Object> f) {
        Object d = f.get("default");
        if ("checkbox".equals(f.get("type"))) return d instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(d));
        if ("number".equals(f.get("type")) && d != null && !String.valueOf(d).isBlank()) try { return new BigDecimal(String.valueOf(d)); } catch (NumberFormatException ignored) { }
        return d == null ? "" : d;
    }

    /** Problems with the current answers, by field id: required, number limits, email address, a choice not offered. */
    static Map<String, String> problems(FormWindow.Session s) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> f : inputs(s.form)) {
            String id = String.valueOf(f.get("id"));
            Map<String, Object> st = s.elements.getOrDefault(id, Map.of());
            if (Boolean.TRUE.equals(st.get("hidden")) || Boolean.TRUE.equals(st.get("disabled"))) continue;
            Object v = s.values.get(id);
            String text = v == null ? "" : String.valueOf(v).strip();
            boolean empty = text.isEmpty() || ("checkbox".equals(f.get("type")) && !Boolean.TRUE.equals(v) && !"true".equalsIgnoreCase(text));
            if (Boolean.TRUE.equals(f.get("required")) && empty) { out.put(id, "Required"); continue; }
            if (text.isEmpty()) continue;
            switch (String.valueOf(f.get("type"))) {
                case "number" -> {
                    try {
                        BigDecimal n = new BigDecimal(text);
                        if (f.get("min") instanceof Number mn && n.compareTo(new BigDecimal(mn.toString())) < 0) out.put(id, "At least " + mn);
                        else if (f.get("max") instanceof Number mx && n.compareTo(new BigDecimal(mx.toString())) > 0) out.put(id, "At most " + mx);
                    } catch (NumberFormatException e) { out.put(id, "Enter a number"); }
                }
                case "email" -> { if (!text.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) out.put(id, "Enter an email address"); }
                case "dropdown", "radio" -> {
                    Object opts = st.containsKey("options") ? st.get("options") : f.get("options");
                    if (opts instanceof List<?> l && !l.isEmpty() && l.stream().map(String::valueOf).noneMatch(text::equals)) out.put(id, "Choose one of the options");
                }
                default -> { }
            }
        }
        return out;
    }

    private static Map<String, Object> answers(FormWindow.Session s) {
        synchronized (s) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map<String, Object> f : inputs(s.form)) {
                String id = String.valueOf(f.get("id"));
                Object v = s.values.get(id);
                if ("number".equals(f.get("type")) && v != null && !String.valueOf(v).isBlank()) try { v = new BigDecimal(String.valueOf(v).strip()); } catch (NumberFormatException ignored) { }
                if ("checkbox".equals(f.get("type"))) v = Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
                out.put(id, v == null ? "" : v);
            }
            return out;
        }
    }

    private static Map<String, Object> waitFor(ActionContext ctx, FormWindow.Session s, Set<String> types, double timeoutSeconds) {
        long end = System.nanoTime() + (long) (Math.max(0.1, timeoutSeconds) * 1e9);
        while (true) {
            ctx.checkCancelled();
            long left = end - System.nanoTime();
            if (left <= 0) return null;
            Map<String, Object> e;
            try { e = s.events.poll(Math.min(left, 250_000_000L), TimeUnit.NANOSECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Stopped while waiting for the form"); }
            if (e == null) continue;
            String t = String.valueOf(e.get("type"));
            if (t.equals("close") || types.contains("any") || types.contains(t)) return e;
        }
    }

    private static Map<String, Object> changed(FormWindow.Session s, String id, String key, Object value) {
        synchronized (s) {
            if (value == null || Boolean.FALSE.equals(value)) s.el(id).remove(key); else s.el(id).put(key, value);
            s.changed();
        }
        return Map.of();
    }

    // ---------------------------------------------------------------- show, wait, close

    @Action(id = "display", name = "Display form", category = "Interactive forms", capabilities = "desktop", timeoutSeconds = 86400,
            description = "Show a form from the form designer to the person at the runner. Wait until they submit it, press a button or close it; or keep it open and go on, reacting to it with Wait for form event.")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "The answers by field id (numbers as Number, checkboxes as true/false).")
    @Output(name = "button", type = OoaType.STRING, description = "How the wait ended: submit, the id of the button pressed, closed, or timeout. Empty when not waiting.")
    @Output(name = "submitted", type = OoaType.BOOLEAN, description = "True when the person submitted the form.")
    @Output(name = "name", type = OoaType.STRING, description = "Name of the open form, for the other form actions.")
    @Output(name = "address", type = OoaType.STRING, description = "Local address of the form window (this computer only).")
    public Map<String, Object> display(ActionContext ctx,
            @Input(name = "form", type = OoaType.FILE, example = "Forms/Leave request.form.json", description = "The form (.form.json) made in Studio's form designer.") String formPath,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name to refer to the open form by; the form's id when empty.") String name,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Window title; the form's name when empty.") String title,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Values to fill in first, by field id (the designer's defaults otherwise).") Map<String, Object> values,
            @Input(name = "wait", type = OoaType.STRING, required = false, defaultValue = "\"until_done\"", enumValues = {"until_done", "no"},
                    description = "until_done: wait until the form is submitted, a button is pressed or it is closed. no: keep it open and go on.") String wait,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", min = 1, max = 86400, visibleWhen = "wait=until_done",
                    description = "How long to wait for the person.") double timeoutSeconds,
            @Input(name = "check_answers", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Check required fields and limits in the window before it can be submitted.") boolean check,
            @Input(name = "width", type = OoaType.NUMBER, required = false, defaultValue = "560", min = 320, max = 2000, description = "Window width in pixels.") int width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, defaultValue = "640", min = 240, max = 2000, description = "Window height in pixels.") int height) {
        Path p = ctx.resolvePath(formPath);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Form not found: " + p, "Choose a .form.json file made in the form designer.");
        Map<String, Object> form;
        try {
            Object o = JsonLite.parse(Files.readString(p));
            if (!(o instanceof Map<?, ?> m) || !(m.get("fields") instanceof List)) throw new IllegalArgumentException("no fields");
            @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) o;
            form = f;
        } catch (Exception e) { throw new ActionException("FORMS.BAD_FORM", "Not a form from the form designer: " + p.getFileName() + " (" + e.getMessage() + ")", "Open it in Studio's form designer and save it again."); }
        String n = name == null || name.isBlank() ? String.valueOf(form.getOrDefault("id", p.getFileName().toString().replace(".form.json", ""))) : name.strip();
        Object old = ctx.sessions().remove("form:" + n);
        if (old instanceof FormWindow.Session os) os.close();
        Map<String, Object> def = new LinkedHashMap<>(form);
        def.put("check", check);
        FormWindow.Session s = FormWindow.open(n, def, title == null || title.isBlank() ? String.valueOf(form.getOrDefault("name", n)) : title.strip());
        for (Map<String, Object> f : inputs(form)) s.values.put(String.valueOf(f.get("id")), initial(f));
        if (values != null) for (var e : values.entrySet()) {
            if (!isInput(find(form, e.getKey()))) throw new ActionException("FORMS.ELEMENT_NOT_FOUND", "The form has no field '" + e.getKey() + "' to fill in", "Use field ids from the form designer.");
            s.values.put(e.getKey(), e.getValue());
        }
        ctx.sessions().put("form:" + n, s);
        FormWindow.show(s, width, height);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", n); out.put("address", s.address());
        if (!"until_done".equals(wait)) { out.put("values", answers(s)); out.put("button", ""); out.put("submitted", false); return out; }
        Map<String, Object> e = waitFor(ctx, s, Set.of("submit", "button"), timeoutSeconds);
        String how = e == null ? "timeout" : e.get("type").equals("close") ? "closed" : e.get("type").equals("submit") ? "submit" : String.valueOf(e.get("element"));
        out.put("values", answers(s)); out.put("button", how); out.put("submitted", "submit".equals(how));
        if (e == null || e.get("type").equals("submit") || e.get("type").equals("close")) { s.close(); ctx.sessions().remove("form:" + n); }
        return out;
    }

    @Action(id = "wait_for_event", name = "Wait for form event", category = "Interactive forms", capabilities = "desktop", timeoutSeconds = 86400,
            description = "Wait until the person presses a button, changes a field, submits or closes the open form; then react to it (e.g. in an If on the event and element).")
    @Output(name = "event", type = OoaType.STRING, description = "button, change, submit, close or timeout.")
    @Output(name = "element", type = OoaType.STRING, description = "Id of the button pressed or the field changed; empty for submit, close and timeout.")
    @Output(name = "value", type = OoaType.ANY, description = "The changed field's new value.")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "All answers by field id.")
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when nothing happened in time.")
    public Map<String, Object> waitForEvent(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "events", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "button", "change", "submit"},
                    description = "Which events to wait for (closing the form always ends the wait).") String events,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, max = 86400, description = "How long to wait.") double timeoutSeconds,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop with FORMS.TIMEOUT when nothing happened in time (otherwise timed_out is true).") boolean fail) {
        FormWindow.Session s = session(ctx, name, true);
        Map<String, Object> e = s.closed && s.events.isEmpty() ? Map.of("type", "close", "element", "")
                : waitFor(ctx, s, Set.of(events == null || events.isBlank() ? "any" : events), timeoutSeconds);
        if (e == null && fail) throw new ActionException("FORMS.TIMEOUT", "Nothing happened on the form within " + timeoutSeconds + " s", "Raise the time-out, or handle timed_out.", true);
        Map<String, Object> out = new LinkedHashMap<>();
        String type = e == null ? "timeout" : String.valueOf(e.get("type"));
        out.put("event", type);
        out.put("element", e == null ? "" : String.valueOf(e.get("element")));
        out.put("value", e == null || e.get("value") == null ? "" : e.get("value"));
        out.put("values", answers(s));
        out.put("timed_out", e == null);
        if (type.equals("close") || type.equals("submit")) { if (type.equals("submit")) s.close(); ctx.sessions().remove("form:" + s.name); }
        return out;
    }

    @Action(id = "close", name = "Close form", category = "Interactive forms", capabilities = "desktop", description = "Close the open form window.")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "The last answers by field id.")
    public Map<String, Object> close(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name) {
        Object o = ctx.sessions().remove("form:" + (name == null ? "" : name.strip()));
        if (!(o instanceof FormWindow.Session s)) throw new ActionException("FORMS.NOT_OPEN", "The form '" + name + "' is not open", "Check the name given when it was displayed.");
        Map<String, Object> v = answers(s);
        s.close();
        return Map.of("values", v);
    }

    // ---------------------------------------------------------------- read and change fields

    @Action(id = "get", name = "Get field value", category = "Interactive forms", capabilities = "desktop", idempotent = true,
            description = "Read one field's current value, or all answers when no field is given.")
    @Output(name = "value", type = OoaType.ANY, description = "The field's value (all answers as a Dictionary when no field is given).")
    public Map<String, Object> get(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "element", type = OoaType.STRING, required = false, description = D_ELEMENT + " Empty for all answers.") String element) {
        FormWindow.Session s = session(ctx, name);
        Map<String, Object> all = answers(s);
        if (element == null || element.isBlank()) return Map.of("value", all);
        if (!isInput(element(s, element))) throw new ActionException("FORMS.NOT_A_FIELD", "'" + element + "' is not a field with a value", "Choose an input field, not a button, heading or area.");
        return Map.of("value", all.get(element.strip()));
    }

    @Action(id = "set", name = "Set field value", category = "Interactive forms", capabilities = "desktop", description = "Change a field's value on the open form.")
    public Map<String, Object> set(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element,
            @Input(name = "value", type = OoaType.ANY, description = "New value (text, number, true/false for a checkbox, an option for a drop-down).") Object value) {
        FormWindow.Session s = session(ctx, name);
        if (!isInput(element(s, element))) throw new ActionException("FORMS.NOT_A_FIELD", "'" + element + "' is not a field with a value", "Choose an input field, not a button, heading or area.");
        synchronized (s) { s.values.put(element.strip(), value == null ? "" : value); s.errors.remove(element.strip()); s.changed(); }
        return Map.of();
    }

    @Action(id = "assign", name = "Assign options", category = "Interactive forms", capabilities = "desktop",
            description = "Fill a drop-down or choice list with options, e.g. from a list or a data table column.")
    public Map<String, Object> assign(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "element", type = OoaType.STRING, description = "Id of a drop-down or choice field.") String element,
            @Input(name = "options", type = OoaType.LIST, description = "The options to offer, in order.") List<Object> options,
            @Input(name = "select", type = OoaType.STRING, required = false, description = "Option to select; the current choice is kept when it is still offered.") String select) {
        FormWindow.Session s = session(ctx, name);
        Map<String, Object> f = element(s, element);
        if (!Set.of("dropdown", "radio").contains(String.valueOf(f.get("type")))) throw new ActionException("FORMS.NOT_A_LIST", "'" + element + "' is not a drop-down or choice field", null);
        List<String> opts = options == null ? List.of() : options.stream().map(o -> o == null ? "" : String.valueOf(o)).toList();
        synchronized (s) {
            String id = element.strip();
            s.el(id).put("options", opts);
            if (select != null && !select.isBlank()) s.values.put(id, select);
            else if (!opts.contains(String.valueOf(s.values.get(id)))) s.values.put(id, "");
            s.changed();
        }
        return Map.of();
    }

    @Action(id = "reset", name = "Reset form", category = "Interactive forms", capabilities = "desktop",
            description = "Put every field back to its designer default (or empty) and clear the messages.")
    public Map<String, Object> reset(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name) {
        FormWindow.Session s = session(ctx, name);
        synchronized (s) { for (Map<String, Object> f : inputs(s.form)) s.values.put(String.valueOf(f.get("id")), initial(f)); s.errors.clear(); s.changed(); }
        return Map.of();
    }

    @Action(id = "validate", name = "Validate form", category = "Interactive forms", capabilities = "desktop", idempotent = true,
            description = "Check the answers (required fields, number limits, email addresses, choices) and, if wanted, show the problems next to the fields.")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when there are no problems.")
    @Output(name = "errors", type = OoaType.DICTIONARY, description = "Problems by field id, e.g. {\"email\": \"Required\"}.")
    public Map<String, Object> validate(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "show_messages", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Show the problems on the form.") boolean show) {
        FormWindow.Session s = session(ctx, name);
        Map<String, String> p;
        synchronized (s) { p = problems(s); if (show) { s.errors.clear(); s.errors.putAll(p); s.changed(); } }
        return Map.of("valid", p.isEmpty(), "errors", new LinkedHashMap<>(p));
    }

    // ---------------------------------------------------------------- look and behaviour of elements

    @Action(id = "show", name = "Show element", category = "Interactive forms", capabilities = "desktop", description = "Show a hidden field, button or area.")
    public Map<String, Object> show(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "hidden", false);
    }
    @Action(id = "hide", name = "Hide element", category = "Interactive forms", capabilities = "desktop", description = "Hide a field, button or area (a hidden field is not checked).")
    public Map<String, Object> hide(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "hidden", true);
    }
    @Action(id = "enable", name = "Enable element", category = "Interactive forms", capabilities = "desktop", description = "Let the person use a field or button again.")
    public Map<String, Object> enable(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "disabled", false);
    }
    @Action(id = "disable", name = "Disable element", category = "Interactive forms", capabilities = "desktop", description = "Grey out a field or button so it cannot be used (a disabled field is not checked).")
    public Map<String, Object> disable(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "disabled", true);
    }
    @Action(id = "highlight", name = "Highlight element", category = "Interactive forms", capabilities = "desktop", description = "Draw attention to a field or button with a coloured outline.")
    public Map<String, Object> highlight(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element,
            @Input(name = "color", type = OoaType.STRING, required = false, defaultValue = "\"#d97706\"", pattern = "^(#[0-9A-Fa-f]{6}|[a-z]{3,20})$", description = "Outline colour, e.g. #d97706 or red.") String color) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "highlight", color == null || color.isBlank() ? "#d97706" : color.strip());
    }
    @Action(id = "unhighlight", name = "Remove highlight", category = "Interactive forms", capabilities = "desktop", description = "Remove the highlight from a field or button.")
    public Map<String, Object> unhighlight(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "highlight", null);
    }
    @Action(id = "set_focus", name = "Set focus", category = "Interactive forms", capabilities = "desktop", description = "Put the cursor in a field (or on a button).")
    public Map<String, Object> setFocus(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element) {
        FormWindow.Session s = session(ctx, name); element(s, element);
        synchronized (s) { s.focus = element.strip(); s.changed(); }
        return Map.of();
    }
    @Action(id = "change_label", name = "Change label", category = "Interactive forms", capabilities = "desktop", description = "Change the label of a field or the text of a button or heading.")
    public Map<String, Object> changeLabel(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name, @Input(name = "element", type = OoaType.STRING, description = D_ELEMENT) String element,
            @Input(name = "label", type = OoaType.STRING, description = "The new label.") String label) {
        FormWindow.Session s = session(ctx, name); element(s, element); return changed(s, element.strip(), "label", label == null ? "" : label);
    }
    @Action(id = "change_title", name = "Change form title", category = "Interactive forms", capabilities = "desktop", description = "Change the title shown on the form window.")
    public Map<String, Object> changeTitle(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "title", type = OoaType.STRING, description = "The new title.") String title) {
        FormWindow.Session s = session(ctx, name);
        synchronized (s) { s.title = title == null ? "" : title; s.changed(); }
        return Map.of();
    }

    @Action(id = "dynamic_area", name = "Set dynamic area", category = "Interactive forms", capabilities = "desktop",
            description = "Replace what a dynamic area on the form shows with new fields, e.g. one line per invoice found. Their answers are read like any other field.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of fields now in the area.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> dynamicArea(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = D_NAME) String name,
            @Input(name = "element", type = OoaType.STRING, description = "Id of a dynamic area on the form.") String element,
            @Input(name = "fields", type = OoaType.LIST, description = "Fields as Dictionaries with id, type (text, number, checkbox, dropdown, paragraph, button ...), label and optionally options, default, required.") List<Object> list) {
        FormWindow.Session s = session(ctx, name);
        Map<String, Object> area = element(s, element);
        if (!"area".equals(area.get("type"))) throw new ActionException("FORMS.NOT_AN_AREA", "'" + element + "' is not a dynamic area", "Add a Dynamic area in the form designer.");
        List<Map<String, Object>> fs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object o : list == null ? List.of() : list) {
            if (!(o instanceof Map<?, ?> m)) throw new ActionException("FORMS.BAD_FIELD", "Each field must be a Dictionary with at least id and type", null);
            Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) m);
            String id = String.valueOf(f.getOrDefault("id", ""));
            if (!id.matches("^[A-Za-z_][A-Za-z0-9_]{0,63}$") || !ids.add(id)) throw new ActionException("FORMS.BAD_FIELD", "Field ids must be unique and use letters, digits and _ ('" + id + "')", null);
            f.putIfAbsent("type", "text");
            if ("area".equals(f.get("type"))) throw new ActionException("FORMS.BAD_FIELD", "A dynamic area cannot hold another area", null);
            fs.add(f);
        }
        synchronized (s) {
            List<Map<String, Object>> before = fields(area.get("fields"));
            for (Map<String, Object> f : fs) {
                Map<String, Object> clash = find(s.form, String.valueOf(f.get("id")));
                if (clash != null && before.stream().noneMatch(x -> x == clash))
                    throw new ActionException("FORMS.BAD_FIELD", "The form already has a field '" + f.get("id") + "'", "Use ids that are not used elsewhere on the form.");
            }
            for (Map<String, Object> old : before) { s.values.remove(String.valueOf(old.get("id"))); s.elements.remove(String.valueOf(old.get("id"))); }
            area.put("fields", fs);
            for (Map<String, Object> f : fs) if (isInput(f)) s.values.put(String.valueOf(f.get("id")), initial(f));
            s.changed();
        }
        return Map.of("count", new BigDecimal(fs.size()));
    }
}
