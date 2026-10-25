package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.util.*;

/** Dictionary actions return a new dictionary; map the output back to update a variable. */
@OoaPackage(id = "ooa.dictionary", name = "Dictionary", version = "1.0.0", description = "Put, get and remove key/value pairs.")
public final class DictionaryPackage {

    @Action(id = "put", name = "Put", category = "Dictionary", idempotent = true, description = "Add or replace the value for a key.",
            panel = "dictionary, key, value | only_if_missing")
    @Output(name = "dictionary", type = OoaType.DICTIONARY, description = "Updated dictionary.")
    @Output(name = "previous", description = "The value the key had before, empty when the key is new.")
    @Output(name = "existed", type = OoaType.BOOLEAN, description = "True when the key was already there.")
    public Map<String, Object> put(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key.") String key,
            @Input(name = "value", required = false, description = "Value.") Object value,
            @Input(name = "only_if_missing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep the existing value when the key is already there.") boolean onlyIfMissing) {
        Map<String, Object> out = new LinkedHashMap<>(dict);
        boolean existed = out.containsKey(key);
        Object prev = out.get(key);
        if (!(existed && onlyIfMissing)) out.put(key, value);
        return Out.of("dictionary", out, "previous", prev, "existed", existed);
    }

    @Action(id = "get", name = "Get", category = "Dictionary", idempotent = true, description = "Read the value for a key, or a default when the key is missing.",
            panel = "dictionary, key | default, ignore_case, fail_if_missing")
    @Output(name = "value", description = "Value or default.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the key exists.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key.") String key,
            @Input(name = "default", required = false, visibleWhen = "fail_if_missing=false", description = "Value to return when the key is missing.") Object def,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Find the key without regard to upper/lower case.") boolean ignoreCase,
            @Input(name = "fail_if_missing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Fail with DICT.KEY_NOT_FOUND instead of returning the default.") boolean failIfMissing) {
        String k = ignoreCase ? findKey(dict, key) : key;
        boolean found = k != null && dict.containsKey(k);
        if (!found && failIfMissing) throw new ActionException("DICT.KEY_NOT_FOUND", "Key '" + key + "' is not in the dictionary", "Keys: " + String.join(", ", dict.keySet()) + ".");
        return Out.of("value", found ? dict.get(k) : def, "found", found);
    }

    static String findKey(Map<String, Object> dict, String key) {
        if (dict.containsKey(key)) return key;
        for (String k : dict.keySet()) if (k.equalsIgnoreCase(key)) return k;
        return null;
    }

    @Action(id = "remove", name = "Remove", category = "Dictionary", idempotent = true, description = "Remove a key.",
            panel = "dictionary, key | fail_if_missing")
    @Output(name = "dictionary", type = OoaType.DICTIONARY, description = "Updated dictionary.")
    @Output(name = "removed", description = "The value that was removed, empty when the key was not there.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the key was there.")
    public Map<String, Object> remove(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key to remove.") String key,
            @Input(name = "fail_if_missing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Fail with DICT.KEY_NOT_FOUND when the key is not there.") boolean failIfMissing) {
        Map<String, Object> out = new LinkedHashMap<>(dict);
        boolean found = out.containsKey(key);
        if (!found && failIfMissing) throw new ActionException("DICT.KEY_NOT_FOUND", "Key '" + key + "' is not in the dictionary", "Check the key; keys are case-sensitive.");
        Object removed = out.remove(key);
        return Out.of("dictionary", out, "removed", removed, "found", found);
    }

    @Action(id = "keys", name = "Get keys", category = "Dictionary", idempotent = true, description = "List all keys.",
            panel = "dictionary")
    @Output(name = "keys", type = OoaType.LIST, description = "Keys in insertion order.")
    public Map<String, Object> keys(ActionContext ctx, @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict) {
        return Out.of("keys", new ArrayList<Object>(dict.keySet()));
    }

    @Action(id = "values", name = "Get values", category = "Dictionary", idempotent = true, description = "List all values.",
            panel = "dictionary")
    @Output(name = "values", type = OoaType.LIST, description = "Values in insertion order.")
    public Map<String, Object> values(ActionContext ctx, @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict) {
        return Out.of("values", new ArrayList<>(dict.values()));
    }

    @Condition(id = "contains_key", name = "Contains key", description = "True when the dictionary has the key.")
    public boolean containsKey(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key.") String key) {
        return dict.containsKey(key);
    }

    @Condition(id = "contains_value", name = "Contains value", description = "True when any key of the dictionary holds this value.")
    public boolean containsValue(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Dictionary.") Map<String, Object> dict,
            @Input(name = "value", description = "Value to look for.") Object value) {
        return dict.values().stream().anyMatch(v -> com.oneofficeautomation.runner.Values.looseEquals(v, value));
    }

    @Condition(id = "key_has_value", name = "Key has value", description = "True when the key exists and holds this value.")
    public boolean keyHasValue(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key.") String key,
            @Input(name = "value", required = false, description = "Expected value.") Object value) {
        return dict.containsKey(key) && com.oneofficeautomation.runner.Values.looseEquals(dict.get(key), value);
    }

    @Condition(id = "is_empty", name = "Dictionary is empty", description = "True when the dictionary has no keys.")
    public boolean isEmpty(ActionContext ctx, @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Dictionary.") Map<String, Object> dict) {
        return dict.isEmpty();
    }

    @Action(id = "size", name = "Get size", category = "Dictionary", idempotent = true, description = "Number of keys.",
            panel = "dictionary")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of keys.")
    public Map<String, Object> size(ActionContext ctx, @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict) {
        return Out.of("count", java.math.BigDecimal.valueOf(dict.size()));
    }

    @Action(id = "merge", name = "Merge", category = "Dictionary", idempotent = true,
            description = "Combine two dictionaries; for keys in both, keep the second value unless overwrite is off.",
            panel = "dictionary, other, overwrite")
    @Output(name = "dictionary", type = OoaType.DICTIONARY, description = "Merged dictionary.")
    public Map<String, Object> merge(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "First dictionary.") Map<String, Object> dict,
            @Input(name = "other", type = OoaType.DICTIONARY, description = "Dictionary to merge in.") Map<String, Object> other,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Let values from the second dictionary replace existing ones.") boolean overwrite) {
        Map<String, Object> out = new LinkedHashMap<>(dict);
        if (other != null) other.forEach((k, v) -> { if (overwrite || !out.containsKey(k)) out.put(k, v); });
        return Out.of("dictionary", out);
    }
}
