package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.FieldLabels;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T3: every field of every toolkit has a plain label. A label made from the field's name must not leave an
 * abbreviation or code as it was in the name (ms, dn, id, url, json, sha ...): those are written out.
 */
class FieldLabelsTest {

    /** Abbreviations that must not stay as lower-case words in a label. */
    static final Pattern RAW = Pattern.compile("(?i)\\b(ms|dn|ou|url|uri|id|ids|max|min|regex|sam|api|json|xml|csv|html|sql|tls|ssl|smtp|imap|sha|mime|dpi|pdf|ocr|"
        + "jql|soql|uuid|guid|jdbc|xpath|utc|ldap|wid|aws|pii|eol)\\b");

    @Test @SuppressWarnings("unchecked")
    void everyToolkitFieldHasAPlainLabel() {
        Map<String, String> labels = new TreeMap<>();
        for (Map<String, Object> m : FirstPartyPackages.registry().allManifests())
            for (Object a : (List<Object>) m.get("actions")) {
                Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) a).get("inputs");
                Map<String, Object> props = (Map<String, Object>) schema.get("properties");
                for (Map.Entry<String, Object> p : props.entrySet())
                    labels.put(p.getKey(), String.valueOf(((Map<String, Object>) p.getValue()).get("x-ooa-label")));
            }
        assertTrue(labels.size() > 1000, "every toolkit's fields: " + labels.size());
        List<String> raw = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String label = e.getValue();
            // written out, an abbreviation is in capitals or in brackets after its plain words
            String withoutBrackets = label.replaceAll("\\([^)]*\\)", "");
            var m = RAW.matcher(withoutBrackets);
            while (m.find()) {
                // as in the name: all lower case, or only its first letter capitalised (Json, Id, Max); "IDs" and "XPath" are written out
                String w = m.group(), rest = w.substring(1);
                if (rest.equals(rest.toLowerCase(Locale.ROOT)) || w.equalsIgnoreCase("ms") || w.equalsIgnoreCase("dn")) { raw.add(e.getKey() + " -> " + label); break; }
            }
        }
        assertEquals(List.of(), raw, "labels that keep an abbreviation as it was in the name");
    }

    @Test void namesAreWrittenOut() {
        assertEquals("Delay (milliseconds)", FieldLabels.of("delay_ms"));
        assertEquals("Delay before (milliseconds)", FieldLabels.of("delay_before_ms"));
        assertEquals("Search base (distinguished name)", FieldLabels.of("base_dn"));
        assertEquals("Group (distinguished name)", FieldLabels.of("group_dn"));
        assertEquals("Maximum rows", FieldLabels.of("max_rows"));
        assertEquals("API token", FieldLabels.of("api_token"));
        assertEquals("Folder ID", FieldLabels.of("folder_id"));
        assertEquals("Follower IDs", FieldLabels.of("follower_ids"));
        assertEquals("Start address (URL)", FieldLabels.of("start_url"));
        assertEquals("Minimum size (bytes)", FieldLabels.of("min_size_bytes"));
        assertEquals("Sheet name", FieldLabels.of("sheet_name"));
        assertEquals("Record sys_id", FieldLabels.of("sys_id"));
        assertEquals("Maximum duration (seconds)", FieldLabels.of("max_duration_seconds"));
        assertEquals("Timeout (seconds)", FieldLabels.of("timeout_seconds"));
        assertEquals("Seconds", FieldLabels.of("seconds"), "a unit alone stays the name");
    }
}
