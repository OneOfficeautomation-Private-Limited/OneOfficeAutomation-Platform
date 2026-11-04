package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/** List actions return a new list; the input variable changes only if the output is mapped back to it. */
@OoaPackage(id = "ooa.list", name = "List", version = "1.0.0", description = "Add, read, remove, sort and join list items.")
public final class ListPackage {

    private static int index(List<Object> l, int i, boolean allowEnd) {
        int max = allowEnd ? l.size() : l.size() - 1;
        if (i < 0 || i > max) throw new ActionException("LIST.INDEX", "Index " + i + " is outside the list (size " + l.size() + ")", "Indexes start at 0.");
        return i;
    }

    @Action(id = "add_item", name = "Add item", category = "List", idempotent = true, description = "Add an item at the end, or at a position.",
            panel = "list, item, (Add at: The end = / A position = position) | items, skip_existing")
    @Output(name = "list", type = OoaType.LIST, description = "Updated list.")
    public Map<String, Object> addItem(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "item", required = false, description = "Item to add.") Object item,
            @Input(name = "position", type = OoaType.NUMBER, required = false, min = 0, description = "Zero-based position; end of list when empty.") Integer pos,
            @Input(name = "items", type = OoaType.LIST, required = false, description = "Several items to add in one go, after 'item' when both are given.") List<Object> items,
            @Input(name = "skip_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Do not add an item that is already in the list.") boolean skipExisting) {
        List<Object> out = new ArrayList<>(list);
        List<Object> adding = new ArrayList<>();
        if (item != null || items == null) adding.add(item);
        if (items != null) adding.addAll(items);
        int at = pos == null ? -1 : index(out, pos, true);
        for (Object x : adding) {
            if (skipExisting && out.stream().anyMatch(y -> Values.looseEquals(y, x))) continue;
            if (at < 0) out.add(x); else out.add(at++, x);
        }
        return Out.of("list", out);
    }

    @Action(id = "get_item", name = "Get item", category = "List", idempotent = true, description = "Read the item at a position.",
            panel = "list, position | if_out_of_range, default")
    @Output(name = "item", description = "The item.")
    public Map<String, Object> getItem(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "position", type = OoaType.NUMBER, description = "Zero-based position; negative counts from the end (-1 = last).") int pos,
            @Input(name = "if_out_of_range", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "default"},
                    description = "When the position is outside the list: fail with LIST.INDEX, or return the default.") String ifOutOfRange,
            @Input(name = "default", required = false, visibleWhen = "if_out_of_range=default", description = "Value returned for a position outside the list when if_out_of_range is default.") Object def) {
        int p = pos < 0 ? list.size() + pos : pos;
        if ((p < 0 || p >= list.size()) && "default".equals(ifOutOfRange)) return Out.of("item", def);
        return Out.of("item", list.get(index(list, p, false)));
    }

    @Action(id = "remove_item", name = "Remove item", category = "List", idempotent = true, description = "Remove the item at a position, or every item equal to a value.",
            panel = "list, (Remove by: Position = position / Value = value, all_occurrences)")
    @Output(name = "list", type = OoaType.LIST, description = "Updated list.")
    @Output(name = "removed", description = "The removed item (by position), or a list of the removed items (by value).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items removed.")
    public Map<String, Object> removeItem(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "Position to remove.") Integer pos,
            @Input(name = "value", required = false, description = "Remove every item equal to this value.") Object value,
            @Input(name = "all_occurrences", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "value!=", description = "With a value: remove every equal item; when off, only the first.") boolean all) {
        List<Object> out = new ArrayList<>(list);
        if (pos != null) {
            Object r = out.remove(index(out, pos, false));
            return Out.of("list", out, "removed", r, "count", BigDecimal.ONE);
        }
        if (value == null) throw new ActionException("LIST.NOTHING_TO_REMOVE", "Give a position or a value to remove");
        List<Object> removed = new ArrayList<>();
        for (Iterator<Object> it = out.iterator(); it.hasNext(); ) {
            Object x = it.next();
            if (Values.looseEquals(x, value)) { it.remove(); removed.add(x); if (!all) break; }
        }
        return Out.of("list", out, "removed", removed, "count", BigDecimal.valueOf(removed.size()));
    }

    @Action(id = "index_of", name = "Find item", category = "List", idempotent = true, description = "Return the zero-based position of the first (or last) item equal to a value.",
            panel = "list, value | ignore_case, last")
    @Output(name = "index", type = OoaType.NUMBER, description = "Zero-based position, or -1 when the value is not in the list.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the value is in the list.")
    public Map<String, Object> indexOf(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "value", description = "Value to look for.") Object value,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Compare text without regard to upper/lower case.") boolean ignoreCase,
            @Input(name = "last", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the last matching position instead of the first.") boolean last) {
        int hit = -1;
        for (int i = 0; i < list.size(); i++) {
            Object x = list.get(i);
            boolean eq = ignoreCase && x instanceof String a && value instanceof String b ? a.equalsIgnoreCase(b) : Values.looseEquals(x, value);
            if (eq) { hit = i; if (!last) break; }
        }
        return Out.of("index", BigDecimal.valueOf(hit), "found", hit >= 0);
    }

    @Action(id = "reverse", name = "Reverse", category = "List", idempotent = true, description = "Return the items in reverse order.",
            panel = "list")
    @Output(name = "list", type = OoaType.LIST, description = "Reversed list.")
    public Map<String, Object> reverse(ActionContext ctx, @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list) {
        List<Object> out = new ArrayList<>(list);
        Collections.reverse(out);
        return Out.of("list", out);
    }

    @Action(id = "sort", name = "Sort", category = "List", idempotent = true, description = "Sort items (numbers numerically, text alphabetically).",
            panel = "list, descending | ignore_case")
    @Output(name = "list", type = OoaType.LIST, description = "Sorted list.")
    public Map<String, Object> sort(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "descending", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Largest first.") boolean desc,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Ignore upper/lower case for text.") boolean ignoreCase) {
        List<Object> out = new ArrayList<>(list);
        Comparator<Object> cmp = (a, b) -> compare(a, b, ignoreCase);
        out.sort(desc ? cmp.reversed() : cmp);
        return Out.of("list", out);
    }

    static int compare(Object a, Object b, boolean ignoreCase) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;
        try { return Values.toNumber(a).compareTo(Values.toNumber(b)); } catch (OoaException ignored) { }
        String x = Values.text(a), y = Values.text(b);
        return ignoreCase ? x.compareToIgnoreCase(y) : x.compareTo(y);
    }

    @Action(id = "join", name = "Join", category = "List", idempotent = true, description = "Join items into one text with a separator.",
            panel = "list, separator | skip_empty, escape_sequences")
    @Output(name = "text", type = OoaType.STRING, description = "Joined text.")
    public Map<String, Object> join(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "separator", type = OoaType.STRING, required = false, defaultValue = "\", \"", description = "Separator; \\n and \\t stand for a line break and a tab.") String sep,
            @Input(name = "skip_empty", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave out empty items.") boolean skipEmpty,
            @Input(name = "escape_sequences", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Turn \\n, \\r and \\t in the separator into a line break, carriage return and tab.") boolean escapes) {
        String s = sep == null ? "" : sep;
        if (escapes) s = s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        StringJoiner j = new StringJoiner(s);
        for (Object o : list) { String t = Values.text(o); if (!(skipEmpty && t.isEmpty())) j.add(t); }
        return Out.of("text", j.toString());
    }

    @Action(id = "size", name = "Get size", category = "List", idempotent = true, description = "Number of items.",
            panel = "list")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> size(ActionContext ctx, @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list) {
        return Out.of("count", BigDecimal.valueOf(list.size()));
    }

    @Action(id = "remove_duplicates", name = "Remove duplicates", category = "List", idempotent = true, description = "Keep the first occurrence of each value.",
            panel = "list")
    @Output(name = "list", type = OoaType.LIST, description = "List without duplicates.")
    public Map<String, Object> removeDuplicates(ActionContext ctx, @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list) {
        List<Object> out = new ArrayList<>();
        for (Object o : list) if (out.stream().noneMatch(x -> Values.looseEquals(x, o))) out.add(o);
        return Out.of("list", out);
    }

    @Condition(id = "contains", name = "List contains item", description = "True when the list contains the value.")
    public boolean contains(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "List.") List<Object> list,
            @Input(name = "value", description = "Value to look for.") Object value) {
        return list.stream().anyMatch(x -> Values.looseEquals(x, value));
    }

    @Condition(id = "is_empty", name = "List is empty", description = "True when the list has no items.")
    public boolean isEmpty(ActionContext ctx, @Input(name = "list", type = OoaType.LIST, description = "List.") List<Object> list) {
        return list.isEmpty();
    }

    @Action(id = "clear", name = "Clear", category = "List", idempotent = true, description = "Return an empty list; map it back to the variable to empty it.",
            panel = "list")
    @Output(name = "list", type = OoaType.LIST, description = "Empty list.")
    public Map<String, Object> clear(ActionContext ctx, @Input(name = "list", type = OoaType.LIST, required = false, description = "List to clear.") List<Object> list) {
        return Out.of("list", new ArrayList<>());
    }

    @Action(id = "set_item", name = "Set item", category = "List", idempotent = true, description = "Replace the item at a position.",
            panel = "list, position, item")
    @Output(name = "list", type = OoaType.LIST, description = "Updated list.")
    public Map<String, Object> setItem(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "position", type = OoaType.NUMBER, description = "Zero-based position.") int pos,
            @Input(name = "item", required = false, description = "New item.") Object item) {
        List<Object> out = new ArrayList<>(list);
        out.set(index(out, pos, false), item);
        return Out.of("list", out);
    }

    @Action(id = "append_list", name = "Append list", category = "List", idempotent = true, description = "Join two lists into one, the second after the first.",
            panel = "list, other")
    @Output(name = "list", type = OoaType.LIST, description = "Combined list.")
    public Map<String, Object> appendList(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "First list.") List<Object> list,
            @Input(name = "other", type = OoaType.LIST, description = "List to add at the end.") List<Object> other) {
        List<Object> out = new ArrayList<>(list);
        if (other != null) out.addAll(other);
        return Out.of("list", out);
    }

    // ---------- shuffle, intersect, subtract, filter and select (gap G06, 3 Nov 2026) ----------
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    @Action(id = "shuffle", name = "Shuffle", category = "List", description = "Return the items in a random order, e.g. to pick a random sample.",
            panel = "list")
    @Output(name = "list", type = OoaType.LIST, description = "The same items in random order.")
    public Map<String, Object> shuffle(ActionContext ctx, @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list) {
        List<Object> out = new ArrayList<>(list);
        Collections.shuffle(out, RANDOM);
        return Out.of("list", out);
    }

    @Action(id = "intersect", name = "Items in both lists", category = "List", idempotent = true,
            description = "Return the items of the first list that are also in the second, in the first list's order, each once.",
            panel = "list, other")
    @Output(name = "list", type = OoaType.LIST, description = "Items in both lists.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> intersect(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "First list.") List<Object> list,
            @Input(name = "other", type = OoaType.LIST, description = "Second list.") List<Object> other) {
        List<Object> out = new ArrayList<>();
        List<Object> b = other == null ? List.of() : other;
        for (Object x : list)
            if (b.stream().anyMatch(y -> Values.looseEquals(x, y)) && out.stream().noneMatch(y -> Values.looseEquals(x, y))) out.add(x);
        return Out.of("list", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "subtract", name = "Items not in other list", category = "List", idempotent = true,
            description = "Return the items of the first list that are not in the second, e.g. invoices not yet processed.",
            panel = "list, other")
    @Output(name = "list", type = OoaType.LIST, description = "Items only in the first list.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> subtract(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "First list.") List<Object> list,
            @Input(name = "other", type = OoaType.LIST, description = "Items to leave out.") List<Object> other) {
        List<Object> b = other == null ? List.of() : other;
        List<Object> out = new ArrayList<>();
        for (Object x : list) if (b.stream().noneMatch(y -> Values.looseEquals(x, y))) out.add(x);
        return Out.of("list", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "filter", name = "Filter", category = "List", idempotent = true,
            description = "Keep the items that pass a test, such as greater than 100 or contains Acme; for a list of records, test one field.",
            panel = "list, field, operator, value, match_case")
    @Output(name = "list", type = OoaType.LIST, description = "Items kept.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items kept.")
    public Map<String, Object> filter(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "Source list.") List<Object> list,
            @Input(name = "operator", type = OoaType.STRING, enumValues = {"eq", "ne", "gt", "ge", "lt", "le", "contains", "not_contains", "starts_with", "ends_with", "matches", "empty", "not_empty"},
                    description = "Test; matches takes a regular expression.") String op,
            @Input(name = "value", required = false, visibleWhen = "operator!=empty|not_empty", description = "Value to compare with (not needed for empty/not_empty).") Object value,
            @Input(name = "field", type = OoaType.STRING, required = false, description = "For a list of records (dictionaries): the field to test; the item itself when empty.") String field,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    visibleWhen = "operator=eq|ne|contains|not_contains|starts_with|ends_with|matches", description = "Compare text with upper/lower case taken into account.") boolean matchCase) {
        List<Object> out = new ArrayList<>();
        for (Object x : list) {
            Object v = field == null || field.isBlank() ? x : x instanceof Map<?, ?> m ? m.get(field) : null;
            if (DataTablePackage.matches(v, op, value, matchCase)) out.add(x);
        }
        return Out.of("list", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "select", name = "Select field", category = "List", idempotent = true,
            description = "Turn a list of records (dictionaries) into a list of one field's values, e.g. the e-mail of every customer.",
            panel = "list, field | skip_missing")
    @Output(name = "list", type = OoaType.LIST, description = "The field's values, in the same order.")
    public Map<String, Object> select(ActionContext ctx,
            @Input(name = "list", type = OoaType.LIST, description = "List of records.") List<Object> list,
            @Input(name = "field", type = OoaType.STRING, example = "email", description = "Field name; a.b reads a field inside a field.") String field,
            @Input(name = "skip_missing", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Leave out records without the field; when off they give an empty value, so the positions still match.") boolean skipMissing) {
        if (field == null || field.isBlank()) throw new ActionException("LIST.NO_FIELD", "No field given", "Fill in the field name, e.g. email.");
        List<Object> out = new ArrayList<>();
        for (Object x : list) {
            Object v = x;
            boolean found = true;
            for (String part : field.split("\\.")) {
                if (v instanceof Map<?, ?> m && m.containsKey(part)) v = m.get(part);
                else { v = null; found = false; break; }
            }
            if (!found && skipMissing) continue;
            out.add(v);
        }
        return Out.of("list", out);
    }
}
