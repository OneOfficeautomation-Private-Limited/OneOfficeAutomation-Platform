package com.oneofficeautomation.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where an agent can run (AA's Platform column): Windows for desktop and Windows toolkits and VBScript, otherwise any runner. */
class PlatformsTest {
    @Test void windowsOnlyForDesktopToolkits() {
        assertEquals("Windows", Platforms.of("{\"steps\":[{\"id\":\"s1\",\"action\":\"ooa.desktop_ui/click\"}]}"));
        assertEquals("Windows", Platforms.of("{\"steps\":[{\"id\":\"s1\",\"action\": \"ooa.script/run_vbscript\"}]}"));
        assertEquals("Windows", Platforms.of("{\"steps\":[{\"id\":\"b\",\"action\":\"core/if\",\"then\":[{\"action\":\"ooa.excel_desktop/open\"}]}]}"));
        assertEquals("Any", Platforms.of("{\"steps\":[{\"id\":\"s1\",\"action\":\"ooa.excel/open\"},{\"action\":\"ooa.script/run_python\"}]}"));
        assertEquals("Any", Platforms.of("{\"description\":\"uses ooa.desktop_ui/click in a note\",\"steps\":[]}"), "only steps' actions count");
        assertEquals("Any", Platforms.of(null));
    }
}
