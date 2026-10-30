package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K3: the interface language catalogs match the texts of the console and Studio (ooa-i18n-keys.json, made by
 * scripts/i18n-extract.py): every translation is for a current text, keeps its {} places, and each language covers
 * most of the interface. The layer that applies them, ooa-i18n.js, offers exactly these languages.
 */
class I18nCatalogsTest {
    static final List<String> LANGUAGES = List.of("de", "fr", "es", "ja");

    static Object resource(String name) throws Exception {
        try (InputStream in = I18nCatalogsTest.class.getResourceAsStream("/ooa-ui/" + name)) {
            assertNotNull(in, name + " is packaged");
            return JsonLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test @SuppressWarnings("unchecked")
    void catalogsMatchTheInterfaceTexts() throws Exception {
        Set<String> keys = new HashSet<>((List<String>) resource("ooa-i18n-keys.json"));
        assertTrue(keys.size() > 1500, "the extractor found the console's interface texts");
        String layer;
        try (InputStream in = I18nCatalogsTest.class.getResourceAsStream("/ooa-ui/ooa-i18n.js")) { layer = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        for (String lang : LANGUAGES) {
            assertTrue(layer.contains("[\"" + lang + "\","), "the language picker offers " + lang);
            Map<String, Object> cat = (Map<String, Object>) resource("ooa-i18n-" + lang + ".json");
            for (var e : cat.entrySet()) {
                assertTrue(keys.contains(e.getKey()), lang + ": no longer an interface text (run scripts/i18n-merge.py " + lang + " to tidy): " + e.getKey());
                String v = String.valueOf(e.getValue());
                assertFalse(v.isBlank(), lang + ": empty translation of " + e.getKey());
                assertEquals(count(e.getKey()), count(v), lang + ": the {} places differ for " + e.getKey());
            }
            assertTrue(cat.size() >= keys.size() * 85 / 100, lang + " covers " + cat.size() + " of " + keys.size() + " texts; translate the new ones (scripts/i18n-merge.py)");
        }
    }

    static int count(String s) { int n = 0; for (int i = s.indexOf("{}"); i >= 0; i = s.indexOf("{}", i + 2)) n++; return n; }
}
