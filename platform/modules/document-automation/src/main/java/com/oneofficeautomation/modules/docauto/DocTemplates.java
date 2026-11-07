package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

/**
 * Document Automation processes in templates (bundle.document_processes, and the document types they read in
 * bundle.document_types): sharing pulls their definitions from DocFlow, without what belongs to the company they come
 * from; installing creates them as drafts in the installing company's DocFlow, in the chosen folder, with their "Start
 * a bot" deliveries pointed at the template's agents as installed. One CommandCenter's Templates call this part through
 * the module contract (ModuleContext.TemplatePart).
 */
public final class DocTemplates implements ModuleContext.TemplatePart {
    private final ModuleContext ctx;
    private final DocFlowProxy docflow;

    public DocTemplates(ModuleContext ctx, DocFlowProxy docflow) { this.ctx = ctx; this.docflow = docflow; }


    /** Delivery settings that belong to the company a process comes from and are never shared: credentials and the platform's stamps. */
    private static final java.util.regex.Pattern PRIVATE = java.util.regex.Pattern.compile("(?i).*(auth|secret|token|password|api.?key|requestedBy|platform).*");

    /**
     * A process definition as a template carries it: a draft, without its email inbox, watched folder and review
     * webhook; a "Start a bot" delivery to one of the template's agents is marked (in_template) so that it starts that
     * agent as installed; other deliveries lose their credentials and are switched off for the installing company to
     * check.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> portable(Map<String, Object> flow, Collection<String> agents) {
        Map<String, Object> p = new LinkedHashMap<>(flow);
        for (String k : List.of("createdAt", "updatedAt", "version", "stats", "docTypeNames", "isInbox")) p.remove(k);
        p.put("status", "draft");
        p.put("intake", new LinkedHashMap<>(Map.of("inputFolder", "", "email", Map.of())));
        if (p.get("review") instanceof Map<?, ?> rv) { Map<String, Object> review = new LinkedHashMap<>((Map<String, Object>) rv); review.put("slaWebhook", ""); p.put("review", review); }
        List<Object> deliveries = new ArrayList<>();
        if (p.get("deliveries") instanceof List<?> l) for (Object o : l) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> d = new LinkedHashMap<>((Map<String, Object>) raw);
            Map<String, Object> params = new LinkedHashMap<>();
            if (d.get("params") instanceof Map<?, ?> pm) ((Map<String, Object>) pm).forEach((k, v) -> { if (!PRIVATE.matcher(k).matches()) params.put(k, v); });
            if ("start_bot".equals(d.get("type"))) {
                params.remove("url");
                if (agents.contains(String.valueOf(params.getOrDefault("bot", "")))) params.put("in_template", true);
                else d.put("enabled", false);   // an agent the template does not carry: chosen again after installing
            } else d.put("enabled", false);
            d.put("params", params);
            deliveries.add(d);
        }
        p.put("deliveries", deliveries);
        return p;
    }

    /** A document type as a template carries it: its fields and checks, without what it learned from the company's documents. */
    public static Map<String, Object> portableType(Map<String, Object> type) {
        Map<String, Object> t = new LinkedHashMap<>(type);
        for (String k : List.of("learning", "createdAt", "updatedAt", "version", "usedBy", "learnedCount")) t.remove(k);
        return t;
    }

    /** Puts the chosen processes (by id; the person must be able to see them) and the document types they read into a bundle. */
    @SuppressWarnings("unchecked")
    public void addToBundle(User by, Map<String, Object> bundle, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        Set<String> agents = new HashSet<>();
        for (Object a : (List<Object>) bundle.get("agents")) agents.add(String.valueOf(((Map<String, Object>) a).get("path")));
        List<Object> processes = new ArrayList<>();
        Map<String, Object> types = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(ids)) {
            if (id == null || !id.matches("[A-Za-z0-9._-]{1,120}")) throw new OoaException("CR.BAD_INPUT", "Not a process id: " + id);
            Map<String, Object> flow = (Map<String, Object>) docflow.json(by, "GET", "df/flows/" + id, null);
            if (Boolean.TRUE.equals(flow.get("isInbox"))) throw new OoaException("CR.BAD_INPUT", "The Smart Inbox cannot go into a template", "Choose processes you made.");
            processes.add(portable(flow, agents));
            if (flow.get("docTypes") instanceof List<?> l) for (Object t : l) {
                String tid = String.valueOf(t);
                if (!tid.matches("[A-Za-z0-9._-]{1,120}")) continue;
                if (!types.containsKey(tid)) types.put(tid, portableType((Map<String, Object>) docflow.json(by, "GET", "df/doctypes/" + tid, null)));
            }
        }
        bundle.put("document_processes", processes);
        bundle.put("document_types", new ArrayList<>(types.values()));
    }

    /**
     * Creates a template's processes as drafts in the chosen folder, as the person installing (who needs docs.manage,
     * edit access to that folder, and may start the agents its deliveries start): first the document types this company
     * does not have yet, then each process not there yet. Lookup rules on reference data this company does not have are
     * switched off. Returns each process with created, skipped (already there) or failed.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> install(User by, Map<String, Object> bundle, String folder) {
        if (!(bundle.get("document_processes") instanceof List<?> list) || list.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        String prefix = ctx.normalizeFolder(folder) + "/";
        DocFlowProxy d = docflow;
        Set<String> there = new HashSet<>();
        try {
            for (Object o : bundle.get("document_types") instanceof List<?> l ? l : List.of()) {
                Map<String, Object> type = (Map<String, Object>) o;
                String tid = String.valueOf(type.get("id"));
                if (tid.matches("[A-Za-z0-9._-]{1,120}") && d.exchange(by, "GET", "df/doctypes/" + tid, null, null, Map.of(), by.username()).status() != 200)
                    d.json(by, "POST", "df/doctypes", type);
            }
            for (Object o : (List<Object>) d.json(by, "GET", "df/flows", null))
                there.add(String.valueOf(((Map<String, Object>) o).get("name")).toLowerCase(Locale.ROOT) + "|" + DocFlowProxy.folderKey(((Map<String, Object>) o).get("folder")));
        } catch (OoaException e) {
            for (Object o : list) out.add(new LinkedHashMap<>(Map.of("name", String.valueOf(((Map<String, Object>) o).get("name")), "status", "failed", "error", String.valueOf(e.getMessage()))));
            return out;
        }
        for (Object o : list) {
            Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) o);
            String at = (prefix + DocFlowProxy.folderKey(p.get("folder"))).replaceAll("/+$", "");
            p.put("folder", at.replace("/", " / "));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", p.get("name"));
            r.put("folder", p.get("folder"));
            if (there.contains(String.valueOf(p.get("name")).toLowerCase(Locale.ROOT) + "|" + at)) { r.put("status", "skipped"); out.add(r); continue; }
            List<Object> deliveries = new ArrayList<>();
            for (Object x : p.get("deliveries") instanceof List<?> l ? l : List.of()) {
                Map<String, Object> dl = new LinkedHashMap<>((Map<String, Object>) x);
                Map<String, Object> params = dl.get("params") instanceof Map<?, ?> pm ? new LinkedHashMap<>((Map<String, Object>) pm) : new LinkedHashMap<>();
                if (Boolean.TRUE.equals(params.remove("in_template"))) params.put("bot", prefix + params.get("bot"));
                dl.put("params", params);
                deliveries.add(dl);
            }
            p.put("deliveries", deliveries);
            List<Object> rules = new ArrayList<>(), off = new ArrayList<>();
            for (Object x : p.get("rules") instanceof List<?> l ? l : List.of()) {
                Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>) x);
                Map<String, Object> params = rule.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
                if ("lookup".equals(rule.get("type")) && params.get("table") instanceof String table && !table.isBlank() && (!table.matches("[A-Za-z0-9._-]{1,120}")
                        || d.exchange(by, "GET", "df/reftables/" + table, "limit=1", null, Map.of(), by.username()).status() != 200)) {
                    rule.put("enabled", false);
                    Map<String, Object> np = new LinkedHashMap<>(params);
                    np.remove("table");
                    rule.put("params", np);
                    off.add(rule.getOrDefault("name", "lookup"));
                }
                rules.add(rule);
            }
            p.put("rules", rules);
            try {
                Map<String, Object> made = (Map<String, Object>) d.json(by, "POST", "df/flows", p);
                r.put("id", made.get("id"));
                r.put("status", "created");
                if (!off.isEmpty()) r.put("note", "Switched off, as this company has no such reference data: " + off);
            } catch (OoaException e) {
                r.put("status", "failed");
                r.put("error", e.getMessage());
            }
            out.add(r);
        }
        return out;
    }
}
