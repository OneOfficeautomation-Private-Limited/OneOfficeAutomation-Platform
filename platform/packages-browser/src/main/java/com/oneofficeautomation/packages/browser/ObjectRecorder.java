package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.ActionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns selectors into captured objects while an agent runs: when OOA_RECORD_OBJECTS names a file, every element a web
 * step finds by its selector is described the way Capture describes a clicked element (same properties, same choice of
 * what to match) and written to that file, one JSON line per step and element. Studio or a script can then put the
 * captured objects in place of the selectors. Off unless the variable is set; recording never changes what the step does.
 */
public final class ObjectRecorder {
    private ObjectRecorder() {}

    private static final String FROM_ENV = System.getenv("OOA_RECORD_OBJECTS");
    private static volatile String file = FROM_ENV;

    /** Records to this file from now on (Studio's "Convert selectors to captured objects"); null goes back to OOA_RECORD_OBJECTS. */
    public static void recordTo(String path) { file = path == null ? FROM_ENV : path; }

    static final String DESCRIBE_JS = "(el) => {\n" + WebObjects.LIB_JS + "\n"
            + "const lab = __labelOf(el);\n"
            + "return { props: __props(el), css: __cssPath(el), xpath: __absXpath(el), dom_xpath: __domXpath(el),\n"
            + "  label_unique: !!lab && Array.from(document.querySelectorAll('input:not([type=hidden]),select,textarea')).filter((x) => __labelOf(x) === lab).length === 1,\n"
            + "  page_rect: __rect(el), page: { url: location.href, title: document.title } };\n}";

    /** Notes the element the locator finds (waiting up to the step's timeout for it), and returns the locator unchanged. */
    @SuppressWarnings("unchecked")
    static Locator note(ActionContext ctx, Locator l, String selector, double timeoutMs) {
        String FILE = file;
        if (FILE == null || FILE.isBlank() || l == null) return l;
        try {
            if (timeoutMs > 0) l.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(timeoutMs));
            if (l.count() == 0) return l;
            Map<String, Object> picked = (Map<String, Object>) l.evaluate(DESCRIBE_JS);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("step", ctx.stepId());
            line.put("selector", selector);
            line.put("object", BrowserPackage.capturedObject(picked));
            synchronized (ObjectRecorder.class) {
                Files.writeString(Path.of(FILE), JsonLite.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            // the element is not there yet, or the page moved on: the step itself reports what matters
        }
        return l;
    }
}
