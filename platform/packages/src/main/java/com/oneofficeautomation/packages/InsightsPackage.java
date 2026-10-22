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
@OoaPackage(id = "ooa.insights", name = "Insights", version = "1.0.0", description = "Record business values and timed transactions from agents for dashboards and ROI reports, and read them back.")
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
    @Action(id = "read_values", name = "Read business values", category = "Insights", idempotent = true, description = "Read recorded values for a category as a table, optionally for a time range or one transaction name.")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per record.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of records.")
    public Map<String, Object> read(ActionContext ctx,
            @Input(name = "category", type = OoaType.STRING, description = "Category name.") String category,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Only records on or after this date (UTC).") LocalDateTime since,
            @Input(name = "until", type = OoaType.DATETIME, required = false, description = "Only records before this date (UTC).") LocalDateTime until,
            @Input(name = "transaction", type = OoaType.STRING, required = false, description = "Only records of this transaction name (from Close transaction).") String transaction,
            @Input(name = "max_records", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Return only the newest this many records; all when 0.") int max) {
        Path p = file(ctx, category);
        List<Object> recs = new ArrayList<>();
        if (Files.exists(p)) try {
            for (String line : Files.readAllLines(p)) {
                if (line.isBlank()) continue;
                Map<String, Object> r = (Map<String, Object>) JsonLite.parse(line);
                String ts = String.valueOf(r.get("ts"));
                if (since != null && ts.compareTo(since.withNano(0).toString()) < 0) continue;
                if (until != null && ts.compareTo(until.withNano(0).toString()) >= 0) continue;
                if (transaction != null && !transaction.isBlank() && !transaction.equals(r.get("transaction"))) continue;
                recs.add(r);
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        if (max > 0 && recs.size() > max) recs = new ArrayList<>(recs.subList(recs.size() - max, recs.size()));
        return Map.of("table", recs.isEmpty() ? Values.table(List.of(), new ArrayList<>()) : Values.coerce(recs, "Table", "records"), "count", BigDecimal.valueOf(recs.size()));
    }

    /** Earlier signature, kept for callers written before the range and transaction filters. */
    public Map<String, Object> read(ActionContext ctx, String category, LocalDateTime since) { return read(ctx, category, since, null, null, 0); }

    /** An open transaction: its name, category and start time. */
    record Transaction(String category, String name, long startNanos, String startedUtc) {}

    private static String txKey(String name) {
        if (name == null || name.isBlank() || name.contains("<") || name.contains(">") || name.length() > 100)
            throw new ActionException("INSIGHTS.BAD_TRANSACTION", "Invalid transaction name '" + name + "'", "Use up to 100 characters without < or >.");
        return "insights_tx:" + name.strip();
    }

    @Action(id = "open_transaction", name = "Open transaction", category = "Insights",
            description = "Mark the start of a business transaction (e.g. one invoice); Close transaction records its values and how long it took.")
    public Map<String, Object> openTransaction(ActionContext ctx,
            @Input(name = "transaction", type = OoaType.STRING, example = "Invoice", description = "Transaction name; Close transaction must use the same name.") String name,
            @Input(name = "category", type = OoaType.STRING, example = "invoices", description = "Category (dashboard) the values go to.") String category) {
        file(ctx, category);
        ctx.sessions().put(txKey(name), new Transaction(category, name.strip(), System.nanoTime(), LocalDateTime.now(ZoneOffset.UTC).withNano(0).toString()));
        return Map.of();
    }

    @Action(id = "close_transaction", name = "Close transaction", category = "Insights",
            description = "End a transaction opened with Open transaction and record its values, outcome and duration for dashboards.")
    @Output(name = "duration_seconds", type = OoaType.NUMBER, description = "Seconds between Open and Close.")
    public Map<String, Object> closeTransaction(ActionContext ctx,
            @Input(name = "transaction", type = OoaType.STRING, description = "Name used in Open transaction.") String name,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Business values to record, e.g. {\"amount\": 120, \"supplier\": \"Acme\"}; leave out sensitive fields. Credentials are refused.") Map<String, Object> values,
            @Input(name = "outcome", type = OoaType.STRING, required = false, defaultValue = "\"success\"", enumValues = {"success", "failure", "business_exception"}, description = "How the transaction ended.") String outcome) {
        String k = txKey(name);
        if (!(ctx.sessions().remove(k) instanceof Transaction t))
            throw new ActionException("INSIGHTS.NOT_OPEN", "Transaction '" + name + "' was not opened", "Add Open transaction with the same name before this step.");
        BigDecimal secs = BigDecimal.valueOf(System.nanoTime() - t.startNanos()).movePointLeft(9).setScale(3, java.math.RoundingMode.HALF_UP);
        Map<String, Object> rec = new LinkedHashMap<>();
        if (values != null) rec.putAll(values);
        if (rec.keySet().stream().anyMatch(x -> Set.of("transaction", "outcome", "started", "duration_seconds").contains(x)))
            throw new ActionException("INSIGHTS.RESERVED_NAME", "transaction, outcome, started and duration_seconds are recorded automatically", "Rename that value.");
        rec.put("transaction", t.name());
        rec.put("outcome", outcome == null || outcome.isBlank() ? "success" : outcome);
        rec.put("started", t.startedUtc());
        rec.put("duration_seconds", secs);
        tag(ctx, t.category(), rec);
        return Map.of("duration_seconds", secs);
    }
}
