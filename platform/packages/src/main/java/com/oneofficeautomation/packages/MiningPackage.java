package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Process mining (gap G63, first part): discover how a process really runs from an event log, the rows any system
 * keeps of "case, activity, time" (an ERP's change log, a ticket history, One CommandCenter's own run and task history
 * exported to CSV), and check cases against the expected flow.
 */
@OoaPackage(id = "ooa.mining", name = "Process mining", version = "1.0.0",
        description = "Discover a process from an event log (cases, activities, times): variants, flows, waiting times and a diagram; check conformance.")
public final class MiningPackage {
    record Event(String activity, Instant at) { }

    static final List<DateTimeFormatter> LOCAL = List.of(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"), DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

    /** A time cell: ISO instant or offset, a local date-time in a common form (read as UTC), or a date. */
    static Instant time(Object v, int row) {
        if (v instanceof Instant i) return i;
        String s = v == null ? "" : Values.text(v).strip();
        try { return Instant.parse(s); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeParseException ignored) { }
        for (DateTimeFormatter f : LOCAL) try { return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { }
        throw new ActionException("MINING.BAD_TIME", "Row " + row + ": cannot read the time '" + s + "'", "Use ISO times (2026-11-06T09:30:00Z) or yyyy-MM-dd HH:mm[:ss].");
    }

    /** CSV with quotes, comma or semicolon. */
    static List<List<String>> csv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        String first = text.lines().findFirst().orElse("");
        char sep = first.chars().filter(ch -> ch == ';').count() > first.chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) { if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else if (c == '"') q = false; else cell.append(c); }
            else if (c == '"') q = true;
            else if (c == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') { if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++; row.add(cell.toString()); cell.setLength(0); out.add(row); row = new ArrayList<>(); }
            else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); out.add(row); }
        out.removeIf(r -> r.stream().allMatch(String::isBlank));
        return out;
    }

    /** The log as cases in first-seen order, each its events sorted by time. */
    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, List<Event>> log(ActionContext ctx, String file, Object table, String caseCol, String actCol, String timeCol) {
        List<String> head;
        List<List<Object>> rows = new ArrayList<>();
        if (file != null && !file.isBlank()) {
            Path p = ctx.resolvePath(file);
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Event log not found: " + p);
            List<List<String>> c;
            try { c = csv(Files.readString(p, StandardCharsets.UTF_8)); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
            if (c.isEmpty()) throw new ActionException("MINING.EMPTY", "The event log is empty");
            head = c.get(0);
            for (List<String> r : c.subList(1, c.size())) rows.add(new ArrayList<>(r));
        } else if (table instanceof Map<?, ?> t && t.get("columns") instanceof List<?> cols && t.get("rows") instanceof List<?> rs) {
            head = cols.stream().map(Values::text).toList();
            for (Object r : rs) if (r instanceof List<?> l) rows.add(new ArrayList<>(l));
        } else throw new ActionException("MINING.NO_LOG", "Give the event log as a CSV file or a table");
        int ci = col(head, caseCol), ai = col(head, actCol), ti = col(head, timeCol);
        if (rows.size() > 2_000_000) throw new ActionException("MINING.TOO_LARGE", "At most 2,000,000 events at a time");
        LinkedHashMap<String, List<Event>> cases = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            int max = Math.max(ci, Math.max(ai, ti));
            if (r.size() <= max) continue;
            String c = Values.text(r.get(ci)).strip(), a = Values.text(r.get(ai)).strip();
            if (c.isEmpty() || a.isEmpty()) continue;
            cases.computeIfAbsent(c, k -> new ArrayList<>()).add(new Event(a, time(r.get(ti), i + 2)));
        }
        for (List<Event> e : cases.values()) e.sort(Comparator.comparing(Event::at));
        return cases;
    }

    static int col(List<String> head, String name) {
        for (int i = 0; i < head.size(); i++) if (head.get(i).strip().equalsIgnoreCase(name.strip())) return i;
        throw new ActionException("MINING.NO_COLUMN", "The event log has no column '" + name + "'", "Columns: " + head);
    }

    static BigDecimal minutes(double m) { return BigDecimal.valueOf(m).setScale(1, RoundingMode.HALF_UP); }

    private static Map<String, Object> table(List<String> cols, List<List<Object>> rows) { return Map.of("columns", cols, "rows", rows); }

    @Action(id = "discover_process", name = "Discover process", category = "Process mining", idempotent = true, timeoutSeconds = 600, capabilities = {"filesystem"},
            description = "From an event log (case, activity, time per row): the activities, the variants (paths cases take), the flows between activities with waiting times, case durations and a diagram.",
            panel = "(Event log: A CSV file = file / A table = table), case_column, activity_column, time_column | top_variants, min_edge_count")
    @Output(name = "cases", type = OoaType.NUMBER, description = "Cases in the log.")
    @Output(name = "events", type = OoaType.NUMBER, description = "Events in the log.")
    @Output(name = "activities", type = OoaType.TABLE, description = "activity, events, cases.")
    @Output(name = "variants", type = OoaType.TABLE, description = "variant (activities in order), cases, share (%), avg_duration_minutes; most frequent first.")
    @Output(name = "flows", type = OoaType.TABLE, description = "from, to, count, avg_wait_minutes: one activity followed directly by the next.")
    @Output(name = "start_activities", type = OoaType.DICTIONARY, description = "Activity to how many cases start with it.")
    @Output(name = "end_activities", type = OoaType.DICTIONARY, description = "Activity to how many cases end with it.")
    @Output(name = "duration", type = OoaType.DICTIONARY, description = "Case duration in minutes: average, median, p90, max.")
    @Output(name = "diagram", type = OoaType.STRING, description = "The process as a Mermaid flowchart (paste it into any Mermaid viewer, or a Markdown file).")
    public Map<String, Object> discover(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "table=", example = "logs/orders.csv", description = "A CSV event log with a header row.") String file,
            @Input(name = "table", type = OoaType.TABLE, required = false, visibleWhen = "file=", description = "The event log as a table.") Object table,
            @Input(name = "case_column", type = OoaType.STRING, required = false, defaultValue = "\"case\"", description = "The column with the case id (order number, ticket...).") String caseCol,
            @Input(name = "activity_column", type = OoaType.STRING, required = false, defaultValue = "\"activity\"", description = "The column with what happened.") String actCol,
            @Input(name = "time_column", type = OoaType.STRING, required = false, defaultValue = "\"time\"", description = "The column with when it happened.") String timeCol,
            @Input(name = "top_variants", type = OoaType.NUMBER, required = false, defaultValue = "20", min = 1, max = 1000, description = "How many variants to list.") int top,
            @Input(name = "min_edge_count", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Leave rarer flows out of the diagram.") int minEdge) {
        LinkedHashMap<String, List<Event>> cases = log(ctx, file, table, caseCol, actCol, timeCol);
        Map<String, int[]> acts = new LinkedHashMap<>();   // events, cases
        Map<String, List<Double>> variantDurations = new HashMap<>();
        Map<String, Integer> variants = new LinkedHashMap<>();
        Map<String, long[]> edges = new LinkedHashMap<>();   // count, total wait seconds
        Map<String, Integer> starts = new TreeMap<>(), ends = new TreeMap<>();
        List<Double> durations = new ArrayList<>();
        long events = 0;
        for (List<Event> evs : cases.values()) {
            events += evs.size();
            Set<String> seen = new HashSet<>();
            for (Event e : evs) { int[] a = acts.computeIfAbsent(e.activity(), k -> new int[2]); a[0]++; if (seen.add(e.activity())) a[1]++; }
            for (int i = 1; i < evs.size(); i++) {
                long[] ed = edges.computeIfAbsent(evs.get(i - 1).activity() + "\u0000" + evs.get(i).activity(), k -> new long[2]);
                ed[0]++; ed[1] += Duration.between(evs.get(i - 1).at(), evs.get(i).at()).getSeconds();
            }
            starts.merge(evs.get(0).activity(), 1, Integer::sum);
            ends.merge(evs.get(evs.size() - 1).activity(), 1, Integer::sum);
            double d = Duration.between(evs.get(0).at(), evs.get(evs.size() - 1).at()).getSeconds() / 60.0;
            durations.add(d);
            String v = String.join(" > ", evs.stream().map(Event::activity).toList());
            variants.merge(v, 1, Integer::sum);
            variantDurations.computeIfAbsent(v, k -> new ArrayList<>()).add(d);
        }
        List<List<Object>> actRows = new ArrayList<>();
        acts.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).forEach(e -> actRows.add(List.of(e.getKey(), e.getValue()[0], e.getValue()[1])));
        List<List<Object>> varRows = new ArrayList<>();
        int n = cases.size();
        variants.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(top).forEach(e -> varRows.add(List.of(e.getKey(), e.getValue(),
                BigDecimal.valueOf(100.0 * e.getValue() / Math.max(1, n)).setScale(1, RoundingMode.HALF_UP),
                minutes(variantDurations.get(e.getKey()).stream().mapToDouble(x -> x).average().orElse(0)))));
        List<List<Object>> flowRows = new ArrayList<>();
        StringBuilder mm = new StringBuilder("flowchart LR\n");
        Map<String, String> ids = new LinkedHashMap<>();
        for (String a : acts.keySet()) ids.put(a, "a" + ids.size());
        ids.forEach((a, id) -> mm.append("  ").append(id).append("[\"").append(a.replace("\"", "'")).append(" (").append(acts.get(a)[0]).append(")\"]\n"));
        edges.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).forEach(e -> {
            String[] ft = e.getKey().split("\u0000", 2);
            long c = e.getValue()[0];
            BigDecimal wait = minutes(e.getValue()[1] / 60.0 / c);
            flowRows.add(List.of(ft[0], ft[1], c, wait));
            if (c >= minEdge) mm.append("  ").append(ids.get(ft[0])).append(" -->|").append(c).append(" · ").append(wait.toPlainString()).append(" min| ").append(ids.get(ft[1])).append('\n');
        });
        List<Double> sorted = new ArrayList<>(durations);
        Collections.sort(sorted);
        Map<String, Object> dur = new LinkedHashMap<>();
        dur.put("average", minutes(sorted.stream().mapToDouble(x -> x).average().orElse(0)));
        dur.put("median", minutes(sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2)));
        dur.put("p90", minutes(sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1))));
        dur.put("max", minutes(sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", n); out.put("events", events);
        out.put("activities", table(List.of("activity", "events", "cases"), actRows));
        out.put("variants", table(List.of("variant", "cases", "share", "avg_duration_minutes"), varRows));
        out.put("flows", table(List.of("from", "to", "count", "avg_wait_minutes"), flowRows));
        out.put("start_activities", new LinkedHashMap<>(starts)); out.put("end_activities", new LinkedHashMap<>(ends));
        out.put("duration", dur); out.put("diagram", mm.toString());
        return out;
    }

    @Action(id = "check_conformance", name = "Check conformance", category = "Process mining", idempotent = true, timeoutSeconds = 600, capabilities = {"filesystem"},
            description = "Check every case of an event log against the expected flow: which activity may follow which, where cases may start and end.",
            panel = "(Event log: A CSV file = file / A table = table), allowed_flows, start_activities, end_activities | case_column, activity_column, time_column")
    @Output(name = "conforming", type = OoaType.NUMBER, description = "Cases that follow the expected flow.")
    @Output(name = "deviating", type = OoaType.NUMBER, description = "Cases that do not.")
    @Output(name = "fitness", type = OoaType.NUMBER, description = "Share of conforming cases, 0 to 1.")
    @Output(name = "deviations", type = OoaType.TABLE, description = "case, problem: each deviation found.")
    public Map<String, Object> conformance(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "table=", description = "A CSV event log with a header row.") String file,
            @Input(name = "table", type = OoaType.TABLE, required = false, visibleWhen = "file=", description = "The event log as a table.") Object table,
            @Input(name = "allowed_flows", type = OoaType.DICTIONARY, example = "{\"Create order\": [\"Approve\", \"Cancel\"], \"Approve\": [\"Ship\"]}",
                    description = "Activity to the activities that may come right after it.") Map<String, Object> allowed,
            @Input(name = "start_activities", type = OoaType.LIST, required = false, description = "Activities a case may start with; any when empty.") List<Object> startAllowed,
            @Input(name = "end_activities", type = OoaType.LIST, required = false, description = "Activities a case may end with; any when empty.") List<Object> endAllowed,
            @Input(name = "case_column", type = OoaType.STRING, required = false, defaultValue = "\"case\"", description = "The case id column.") String caseCol,
            @Input(name = "activity_column", type = OoaType.STRING, required = false, defaultValue = "\"activity\"", description = "The activity column.") String actCol,
            @Input(name = "time_column", type = OoaType.STRING, required = false, defaultValue = "\"time\"", description = "The time column.") String timeCol) {
        if (allowed == null || allowed.isEmpty()) throw new ActionException("MINING.NO_MODEL", "Give the allowed flows");
        Set<String> st = new HashSet<>(), en = new HashSet<>();
        if (startAllowed != null) for (Object o : startAllowed) st.add(Values.text(o));
        if (endAllowed != null) for (Object o : endAllowed) en.add(Values.text(o));
        int ok = 0, bad = 0;
        List<List<Object>> dev = new ArrayList<>();
        for (Map.Entry<String, List<Event>> c : log(ctx, file, table, caseCol, actCol, timeCol).entrySet()) {
            List<Event> evs = c.getValue();
            List<String> problems = new ArrayList<>();
            if (!st.isEmpty() && !st.contains(evs.get(0).activity())) problems.add("starts with " + evs.get(0).activity());
            if (!en.isEmpty() && !en.contains(evs.get(evs.size() - 1).activity())) problems.add("ends with " + evs.get(evs.size() - 1).activity());
            for (int i = 1; i < evs.size(); i++) {
                String a = evs.get(i - 1).activity(), b = evs.get(i).activity();
                List<?> next = allowed.get(a) instanceof List<?> l ? l : List.of();
                if (next.stream().noneMatch(x -> Values.text(x).equals(b))) problems.add(a + " followed by " + b);
            }
            if (problems.isEmpty()) ok++; else { bad++; for (String p : problems) if (dev.size() < 10_000) dev.add(List.of(c.getKey(), p)); }
        }
        int total = ok + bad;
        return Map.of("conforming", ok, "deviating", bad, "fitness", total == 0 ? BigDecimal.ONE : BigDecimal.valueOf((double) ok / total).setScale(3, RoundingMode.HALF_UP),
                "deviations", table(List.of("case", "problem"), dev));
    }
}
