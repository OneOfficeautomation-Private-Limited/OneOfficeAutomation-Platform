package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.ooxml.POIXMLProperties;
import org.openxmlformats.schemas.officeDocument.x2006.customProperties.CTProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Sensitivity labels as Office stores them in custom document properties:
 * {@code MSIP_Label_<guid>_Enabled|SetDate|Method|Name|SiteId|ActionId|ContentBits}.
 * Newer Office builds may also keep a copy in docMetadata/LabelInfo.xml; that part is not read or written here.
 * Writing a label only marks the file; it does not apply encryption or visual markings.
 */
final class Msip {
    private Msip() {}

    static final String PREFIX = "MSIP_Label_";
    private static final String GUID = "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$";

    private static String value(CTProperty p) {
        if (p.isSetLpwstr()) return p.getLpwstr();
        if (p.isSetLpstr()) return p.getLpstr();
        if (p.isSetBool()) return String.valueOf(p.getBool());
        return "";
    }

    static Map<String, Object> read(POIXMLProperties.CustomProperties cp) {
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        for (CTProperty p : cp.getUnderlyingProperties().getPropertyList()) {
            String n = p.getName();
            if (n == null || !n.startsWith(PREFIX)) continue;
            String rest = n.substring(PREFIX.length());
            int us = rest.lastIndexOf('_');
            if (us < 0) continue;
            labels.computeIfAbsent(rest.substring(0, us), k -> new HashMap<>()).put(rest.substring(us + 1), value(p));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : labels.entrySet()) {
            if (!"true".equalsIgnoreCase(e.getValue().getOrDefault("Enabled", ""))) continue;
            Map<String, String> v = e.getValue();
            out.put("label_id", e.getKey());
            out.put("name", v.getOrDefault("Name", ""));
            out.put("site_id", v.getOrDefault("SiteId", ""));
            out.put("method", v.getOrDefault("Method", ""));
            out.put("has_label", true);
            return out;
        }
        out.put("label_id", ""); out.put("name", ""); out.put("site_id", ""); out.put("method", ""); out.put("has_label", false);
        return out;
    }

    static void write(POIXMLProperties.CustomProperties cp, String labelId, String siteId, String name, String method, String errorPrefix) {
        if (labelId == null || !labelId.trim().matches(GUID))
            throw new ActionException(errorPrefix + ".BAD_LABEL", "Label id '" + labelId + "' is not a GUID", "Copy the label GUID from your compliance centre.");
        if (siteId == null || !siteId.trim().matches(GUID))
            throw new ActionException(errorPrefix + ".BAD_LABEL", "Site id '" + siteId + "' is not a GUID", "Use your Microsoft 365 tenant id.");
        String m = method == null || method.isBlank() ? "Standard" : method;
        if (!m.equals("Standard") && !m.equals("Privileged"))
            throw new ActionException(errorPrefix + ".BAD_LABEL", "Method must be Standard or Privileged", "Use Standard or Privileged.");
        var list = cp.getUnderlyingProperties();
        for (int i = list.sizeOfPropertyArray() - 1; i >= 0; i--)
            if (list.getPropertyArray(i).getName() != null && list.getPropertyArray(i).getName().startsWith(PREFIX)) list.removeProperty(i);
        String base = PREFIX + labelId.trim().toLowerCase(Locale.ROOT) + "_";
        cp.addProperty(base + "Enabled", "true");
        cp.addProperty(base + "SetDate", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        cp.addProperty(base + "Method", m);
        cp.addProperty(base + "Name", name == null ? "" : name);
        cp.addProperty(base + "SiteId", siteId.trim().toLowerCase(Locale.ROOT));
        cp.addProperty(base + "ActionId", UUID.randomUUID().toString());
        cp.addProperty(base + "ContentBits", "0");
    }
}
