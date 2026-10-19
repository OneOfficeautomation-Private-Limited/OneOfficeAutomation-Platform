package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.util.*;

/** Dictionary actions return a new dictionary; map the output back to update a variable. */
@OoaPackage(id = "ooa.dictionary", name = "Dictionary", version = "1.0.0", description = "Put, get and remove key/value pairs.")
public final class DictionaryPackage {

    @Action(id = "put", name = "Put", category = "Dictionary", idempotent = true, description = "Add or replace the value for a key.")
    @Output(name = "dictionary", type = OoaType.DICTIONARY, description = "Updated dictionary.")
    public Map<String, Object> put(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key.") String key,
            @Input(name = "value", required = false, description = "Value.") Object value) {
        Map<String, Object> out = new LinkedHashMap<>(dict);
        out.put(key, value);
        return Out.of("dictionary", out);
    }

    @Action(id = "get", name = "Get", category = "Dictionary", idempotent = true, description = "Read the value for a key, or a default when the key is missing.")
    @Output(name = "value", description = "Value or default.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the key exists.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key.") String key,
            @Input(name = "default", required = false, description = "Value to return when the key is missing.") Object def) {
        boolean found = dict.containsKey(key);
        return Out.of("value", found ? dict.get(key) : def, "found", found);
    }

    @Action(id = "remove", name = "Remove", category = "Dictionary", idempotent = true, description = "Remove a key.")
    @Output(name = "dictionary", type = OoaType.DICTIONARY, description = "Updated dictionary.")
    public Map<String, Object> remove(ActionContext ctx,
            @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict,
            @Input(name = "key", type = OoaType.STRING, description = "Key to remove.") String key) {
        Map<String, Object> out = new LinkedHashMap<>(dict);
        out.remove(key);
        return Out.of("dictionary", out);
    }

    @Action(id = "keys", name = "Get keys", category = "Dictionary", idempotent = true, description = "List all keys.")
    @Output(name = "keys", type = OoaType.LIST, description = "Keys in insertion order.")
    public Map<String, Object> keys(ActionContext ctx, @Input(name = "dictionary", type = OoaType.DICTIONARY, description = "Source dictionary.") Map<String, Object> dict) {
        return Out.of("keys", new ArrayList<Object>(dict.keySet()));
    }

    @Action(id = "values", name = "Get values", category = "Dictionary", idempotent = true, description = "List all values.")
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
}
