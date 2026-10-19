package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Business insights: tag the values that matter (amounts, counts, outcomes) so dashboards and ROI reports can use them. */
@OoaPackage(id = "ooa.insights", name = "Insights", version = "1.0.0", description = "Record business values from bots for dashboards and ROI reports, and read them back.")
public final class InsightsPackage {
    private static final Object LOCK = new Object();

    private static Path file(ActionContext ctx, String category) {
        if (!category.matches("^[A-Za-z0-9_-]{1,64}$")) throw new ActionException("INSIGHTS.BAD_CATEGORY", "Invalid category '" + category + "'", "Use letters, digits, - and _.");
        return ctx.workDir().resolve("insights").resolve(category + ".jsonl");
    }

    @Action(id = "tag_values", name = "Tag business values", category = "Insights", description = "Record a set of values for a category, e.g. invoices: {amount, supplier, minutes_saved}.")
    public Map<String, Object> tag(ActionContext ctx,
            @Input(name = "category", type = OoaType.STRING, example = "invoices", description = "Category (dashboard) name.") String category,
            @Input(name = "values", type = OoaType.DICTIONARY, description = "Values to record; Credentials are refused.") Map<String, Object> values) {
        for (Object v : values.values()) if (v instanceof SecureString) throw new ActionException("INSIGHTS.SECRET", "Credentials cannot be recorded as insights");
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ts", LocalDateTime.now(ZoneOffset.UTC).withNano(0).toString());
        rec.put("run_id", ctx.runId());
        rec.putAll((Map<String, Object>) Values.toJsonable(values));
        Path p = file(ctx, category);
        synchronized (LOCK) {
            try { Files.createDirectories(p.getParent()); Files.writeString(p, JsonLite.write(rec) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    @Action(id = "read_values", name = "Read business values", category = "Insights", idempotent = true, description = "Read recorded values for a category as a table, optionally since a date.")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per record.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    public Map<String, Object> read(ActionContext ctx,
            @Input(name = "category", type = OoaType.STRING, description = "Category name.") String category,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Only records on or after this date (UTC).") LocalDateTime since) {
        Path p = file(ctx, category);
        List<Object> recs = new ArrayList<>();
        if (Files.exists(p)) try {
            for (String line : Files.readAllLines(p)) {
                if (line.isBlank()) continue;
                Map<String, Object> r = (Map<String, Object>) JsonLite.parse(line);
                if (since == null || String.valueOf(r.get("ts")).compareTo(since.toString()) >= 0) recs.add(r);
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("table", Values.coerce(recs, "Table", "records"), "count", BigDecimal.valueOf(recs.size()));
    }
}
