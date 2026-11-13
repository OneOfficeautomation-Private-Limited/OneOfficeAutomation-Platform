package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Communications mining (gap G64, first part): find what large numbers of e-mails, tickets or chat messages are about,
 * and route each message to a queue. It runs on the runner with no model or cloud service, so mail never leaves the
 * company: topics come from TF-IDF word weights clustered with k-means (a fixed seed, so a rerun gives the same topics).
 * Pair it with E-mail (read a mailbox to a table) and Work queues (add the routed rows as items).
 */
@OoaPackage(id = "ooa.comms", name = "Communications mining", version = "1.0.0",
        description = "Find the topics in many e-mails, tickets or messages, and route each message to a queue by keywords.")
public final class CommsPackage {
    static final Set<String> STOP = new HashSet<>(Arrays.asList(("a an and are as at be been but by can could do does for from had has have hello hi i if in into is it its "
            + "me my no not of on or our please re fw fwd regards thanks thank that the their them there these they this to us was we were what when "
            + "which who will with would you your yours dear sir madam kind best am pm just also any all get got one two may need let know see sent "
            + "message email mail subject would like wrote original").split(" ")));
    static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{N}_'-]{1,}");

    record Message(String id, String text) { }

    /** The message text without quoted replies ("> ..." and "On ... wrote:" onwards) and the signature ("-- " onwards). */
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\\R")) {
            String t = line.strip();
            if (t.equals("--") || t.startsWith("-----Original Message") || t.matches("(?i)^on .{4,200} wrote:$") || t.matches("(?i)^from: .+") && b.length() > 0) break;
            if (t.startsWith(">")) continue;
            b.append(t).append('\n');
        }
        return b.toString();
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) { String w = m.group().replaceAll("^'+|'+$", ""); if (w.length() > 2 && !STOP.contains(w) && !w.chars().allMatch(Character::isDigit)) out.add(w); }
        return out;
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

    static List<Message> messages(ActionContext ctx, String file, Object table, String idCol, String textCols) {
        List<String> head;
        List<List<Object>> rows = new ArrayList<>();
        if (file != null && !file.isBlank()) {
            Path p = ctx.resolvePath(file);
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Messages file not found: " + p, "Check the path; the file may have been moved or renamed.");
            List<List<String>> c;
            try { c = csv(Files.readString(p, StandardCharsets.UTF_8)); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
            if (c.isEmpty()) throw new ActionException("COMMS.EMPTY", "The messages file is empty", "Check that the messages file has rows below its header line.");
            head = c.get(0);
            for (List<String> r : c.subList(1, c.size())) rows.add(new ArrayList<>(r));
        } else if (table instanceof Map<?, ?> t && t.get("columns") instanceof List<?> cols && t.get("rows") instanceof List<?> rs) {
            head = cols.stream().map(Values::text).toList();
            for (Object r : rs) if (r instanceof List<?> l) rows.add(new ArrayList<>(l));
        } else throw new ActionException("COMMS.NO_MESSAGES", "Give the messages as a CSV file or a table", "Fill in either Messages file (CSV) or Messages table.");
        if (rows.size() > 500_000) throw new ActionException("COMMS.TOO_LARGE", "At most 500,000 messages at a time", "Send the messages in parts of at most 500,000.");
        List<Integer> tc = new ArrayList<>();
        for (String n : textCols.split(",")) if (!n.isBlank()) tc.add(col(head, n));
        if (tc.isEmpty()) throw new ActionException("COMMS.NO_COLUMN", "Name at least one text column", "Name the column that holds the message text.");
        int ic = idCol == null || idCol.isBlank() ? -1 : col(head, idCol);
        List<Message> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            StringBuilder s = new StringBuilder();
            for (int c : tc) if (c < r.size() && r.get(c) != null) s.append(Values.text(r.get(c))).append('\n');
            String id = ic >= 0 && ic < r.size() && r.get(ic) != null ? Values.text(r.get(ic)) : String.valueOf(i + 1);
            out.add(new Message(id, clean(s.toString())));
        }
        return out;
    }

    static int col(List<String> head, String name) {
        for (int i = 0; i < head.size(); i++) if (head.get(i).strip().equalsIgnoreCase(name.strip())) return i;
        throw new ActionException("COMMS.NO_COLUMN", "The messages have no column '" + name + "'", "Columns: " + head);
    }

    private static Map<String, Object> table(List<String> cols, List<List<Object>> rows) { return Map.of("columns", cols, "rows", rows); }

    static double dot(Map<String, Double> a, Map<String, Double> b) {
        if (a.size() > b.size()) { Map<String, Double> t = a; a = b; b = t; }
        double s = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) { Double v = b.get(e.getKey()); if (v != null) s += e.getValue() * v; }
        return s;
    }

    static void normalise(Map<String, Double> v) {
        double n = Math.sqrt(v.values().stream().mapToDouble(x -> x * x).sum());
        if (n > 0) v.replaceAll((k, x) -> x / n);
    }

    @Action(id = "discover_topics", name = "Discover topics", category = "Communications mining", idempotent = true, timeoutSeconds = 900, capabilities = {"filesystem"},
            description = "Group many messages (e-mails, tickets, chats) into topics by the words they use: each topic's keywords, size and examples, and each message's topic.",
            panel = "(Messages: A CSV file = file / A table = table), text_columns, id_column | topics, min_word_count")
    @Output(name = "topics", type = OoaType.TABLE, description = "topic, label (its top words), keywords, messages, share (%), examples (message ids); largest first.")
    @Output(name = "assignments", type = OoaType.TABLE, description = "id, topic, label, score (0-1, how close the message is to its topic).")
    @Output(name = "rules", type = OoaType.DICTIONARY, description = "Topic label to its keywords: a start for Route messages (rename the labels to your queues).")
    @Output(name = "messages", type = OoaType.NUMBER, description = "Messages read.")
    public Map<String, Object> discover(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "table=", example = "mail/inbox.csv", description = "A CSV file with a header row.") String file,
            @Input(name = "table", type = OoaType.TABLE, required = false, visibleWhen = "file=", description = "The messages as a table (for example from E-mail: Get messages).") Object table,
            @Input(name = "text_columns", type = OoaType.STRING, required = false, defaultValue = "\"subject,body\"", description = "The columns with the text, comma separated.") String textCols,
            @Input(name = "id_column", type = OoaType.STRING, required = false, description = "The column with the message id (empty: the row number).") String idCol,
            @Input(name = "topics", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 50, description = "How many topics (0: chosen from the number of messages).") int k,
            @Input(name = "min_word_count", type = OoaType.NUMBER, required = false, defaultValue = "2", min = 1, description = "Leave out words used in fewer messages than this.") int minDf) {
        List<Message> msgs = messages(ctx, file, table, idCol, textCols);
        if (msgs.isEmpty()) throw new ActionException("COMMS.EMPTY", "There are no messages", "Check that the step gets messages to sort.");
        List<List<String>> docs = msgs.stream().map(m -> words(m.text())).toList();
        Map<String, Integer> df = new HashMap<>();
        for (List<String> d : docs) for (String w : new HashSet<>(d)) df.merge(w, 1, Integer::sum);
        int n = docs.size();
        double maxShare = n >= 10 ? 0.6 : 1.0;   // words in most messages say nothing about the topic
        List<Map<String, Double>> vec = new ArrayList<>();
        for (List<String> d : docs) {
            Map<String, Double> tf = new HashMap<>();
            for (String w : d) { int f = df.get(w); if (f >= Math.min(minDf, Math.max(1, n / 2)) && f <= maxShare * n) tf.merge(w, 1.0, Double::sum); }
            tf.replaceAll((w, c) -> (1 + Math.log(c)) * Math.log(1.0 + n / (double) df.get(w)));
            normalise(tf);
            vec.add(tf);
        }
        if (k <= 0) k = (int) Math.max(2, Math.min(20, Math.round(Math.sqrt(n / 2.0))));
        k = Math.min(k, (int) vec.stream().filter(v -> !v.isEmpty()).count());
        if (k < 1) throw new ActionException("COMMS.NO_WORDS", "The messages have too few words in common to group", "Lower min_word_count, or give more messages.");
        int[] assign = kmeans(vec, k, ctx);
        // centroids and top words
        List<Map<String, Double>> cent = centroids(vec, assign, k);
        int[] size = new int[k];
        for (int a : assign) if (a >= 0) size[a]++;
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> size[b] - size[a]);
        int[] rank = new int[k];
        for (int i = 0; i < k; i++) rank[order[i]] = i + 1;
        List<List<Object>> topics = new ArrayList<>();
        Map<String, Object> rules = new LinkedHashMap<>();
        String[] labels = new String[k];
        for (int t : order) {
            if (size[t] == 0) continue;
            List<String> top = cent.get(t).entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(8).map(Map.Entry::getKey).toList();
            labels[t] = String.join(" / ", top.subList(0, Math.min(3, top.size())));
            List<Object> ex = new ArrayList<>();
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < n; i++) if (assign[i] == t) members.add(i);
            members.sort((a, b) -> Double.compare(dot(vec.get(b), cent.get(t)), dot(vec.get(a), cent.get(t))));
            for (int i : members.subList(0, Math.min(3, members.size()))) ex.add(msgs.get(i).id());
            topics.add(List.of(rank[t], labels[t], String.join(", ", top), size[t], BigDecimal.valueOf(100.0 * size[t] / n).setScale(1, RoundingMode.HALF_UP), String.join(", ", ex.stream().map(String::valueOf).toList())));
            rules.put(labels[t], top.subList(0, Math.min(5, top.size())));
        }
        List<List<Object>> as = new ArrayList<>();
        int none = 0;
        for (int i = 0; i < n; i++) {
            int t = assign[i];
            if (t < 0) { none++; as.add(Arrays.asList(msgs.get(i).id(), 0, "(no words in common)", BigDecimal.ZERO)); continue; }
            as.add(List.of(msgs.get(i).id(), rank[t], labels[t], BigDecimal.valueOf(dot(vec.get(i), cent.get(t))).setScale(2, RoundingMode.HALF_UP)));
        }
        ctx.log("info", "Grouped " + n + " messages into " + topics.size() + " topics" + (none > 0 ? " (" + none + " had no words in common with others)" : ""));
        return Map.of("topics", table(List.of("topic", "label", "keywords", "messages", "share", "examples"), topics),
                "assignments", table(List.of("id", "topic", "label", "score"), as), "rules", rules, "messages", n);
    }

    static List<Map<String, Double>> centroids(List<Map<String, Double>> vec, int[] assign, int k) {
        List<Map<String, Double>> c = new ArrayList<>();
        for (int t = 0; t < k; t++) c.add(new HashMap<>());
        for (int i = 0; i < vec.size(); i++) if (assign[i] >= 0) { Map<String, Double> m = c.get(assign[i]); vec.get(i).forEach((w, x) -> m.merge(w, x, Double::sum)); }
        for (Map<String, Double> m : c) normalise(m);
        return c;
    }

    /** Spherical k-means on unit vectors, k-means++ seeding with a fixed seed. Empty vectors get -1. */
    static int[] kmeans(List<Map<String, Double>> vec, int k, ActionContext ctx) {
        int n = vec.size();
        Random rnd = new Random(42);
        List<Integer> live = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!vec.get(i).isEmpty()) live.add(i);
        List<Map<String, Double>> cent = new ArrayList<>();
        cent.add(new HashMap<>(vec.get(live.get(rnd.nextInt(live.size())))));
        double[] d = new double[n];
        while (cent.size() < k) {
            double sum = 0;
            for (int i : live) { double best = 2; for (Map<String, Double> c : cent) best = Math.min(best, 1 - dot(vec.get(i), c)); d[i] = Math.max(0, best) * Math.max(0, best); sum += d[i]; }
            if (sum <= 0) break;
            double r = rnd.nextDouble() * sum;
            int pick = live.get(live.size() - 1);
            for (int i : live) { r -= d[i]; if (r <= 0) { pick = i; break; } }
            cent.add(new HashMap<>(vec.get(pick)));
        }
        k = cent.size();
        int[] a = new int[n];
        Arrays.fill(a, -1);
        for (int it = 0; it < 50; it++) {
            ctx.checkCancelled();
            boolean moved = false;
            for (int i : live) {
                int best = 0; double bs = -1;
                for (int t = 0; t < k; t++) { double s = dot(vec.get(i), cent.get(t)); if (s > bs) { bs = s; best = t; } }
                if (a[i] != best) { a[i] = best; moved = true; }
            }
            if (!moved) break;
            cent = centroids(vec, a, k);
        }
        return a;
    }

    @Action(id = "route_messages", name = "Route messages", category = "Communications mining", idempotent = true, timeoutSeconds = 600, capabilities = {"filesystem"},
            description = "Give each message the queue whose keywords or phrases it matches best (a subject match counts double); messages that match none go to the default queue.",
            panel = "(Messages: A CSV file = file / A table = table), rules, text_columns, id_column, subject_column | default_queue, min_score")
    @Output(name = "routed", type = OoaType.TABLE, description = "id, queue, score, matched (the keywords found); in the messages' order. Add it to a queue with Work queues: Add work items from table.")
    @Output(name = "counts", type = OoaType.DICTIONARY, description = "Queue to how many messages went to it.")
    public Map<String, Object> route(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "table=", example = "mail/inbox.csv", description = "A CSV file with a header row.") String file,
            @Input(name = "table", type = OoaType.TABLE, required = false, visibleWhen = "file=", description = "The messages as a table.") Object table,
            @Input(name = "rules", type = OoaType.DICTIONARY, example = "{\"Invoices\": [\"invoice\", \"payment\", \"overdue\"], \"Returns\": [\"return\", \"refund\", \"damaged\"]}",
                    description = "Queue to its keywords or phrases (a list). Discover topics gives a start.") Map<String, Object> rules,
            @Input(name = "text_columns", type = OoaType.STRING, required = false, defaultValue = "\"subject,body\"", description = "The columns with the text, comma separated.") String textCols,
            @Input(name = "id_column", type = OoaType.STRING, required = false, description = "The column with the message id (empty: the row number).") String idCol,
            @Input(name = "subject_column", type = OoaType.STRING, required = false, defaultValue = "\"subject\"", description = "Matches in this column count double (empty: none).") String subjectCol,
            @Input(name = "default_queue", type = OoaType.STRING, required = false, defaultValue = "\"Unsorted\"", description = "Where messages that match no queue go.") String fallback,
            @Input(name = "min_score", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Matches a queue needs to win.") int minScore) {
        if (rules == null || rules.isEmpty()) throw new ActionException("COMMS.NO_RULES", "Give at least one queue with keywords", "Add at least one queue with the keywords that send a message to it.");
        Map<String, LinkedHashMap<String, Pattern>> pats = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rules.entrySet()) {
            LinkedHashMap<String, Pattern> ps = new LinkedHashMap<>();
            Collection<?> kws = e.getValue() instanceof Collection<?> c ? c : List.of(String.valueOf(e.getValue()).split(","));
            for (Object kw : kws) { String s = Values.text(kw).strip().toLowerCase(Locale.ROOT); if (!s.isEmpty()) ps.put(s, Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(s).replace(" ", "\\E\\s+\\Q") + "\\p{L}{0,3}(?![\\p{L}\\p{N}])")); }
            if (ps.isEmpty()) throw new ActionException("COMMS.NO_RULES", "Queue '" + e.getKey() + "' has no keywords", "Add keywords to the queue the message names.");
            pats.put(e.getKey(), ps);
        }
        List<Message> msgs = messages(ctx, file, table, idCol, textCols);
        List<Message> subjects = null;
        if (subjectCol != null && !subjectCol.isBlank())
            try { subjects = messages(ctx, file, table, idCol, subjectCol); } catch (ActionException e) { if (!"COMMS.NO_COLUMN".equals(e.code())) throw e; }
        List<List<Object>> out = new ArrayList<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String q : pats.keySet()) counts.put(q, 0);
        counts.put(fallback, 0);
        for (int i = 0; i < msgs.size(); i++) {
            String body = msgs.get(i).text().toLowerCase(Locale.ROOT), subj = subjects == null ? "" : subjects.get(i).text().toLowerCase(Locale.ROOT);
            String best = fallback; int bs = 0; List<String> bm = List.of();
            for (Map.Entry<String, LinkedHashMap<String, Pattern>> e : pats.entrySet()) {
                int s = 0; List<String> m = new ArrayList<>();
                for (Map.Entry<String, Pattern> kp : e.getValue().entrySet()) {
                    Pattern p = kp.getValue();
                    int c = 0; Matcher x = p.matcher(body); while (x.find()) c++;
                    Matcher y = p.matcher(subj); while (y.find()) c++;   // the subject is in the body text too, so it counts twice
                    if (c > 0) { s += c; m.add(kp.getKey()); }
                }
                if (s > bs) { bs = s; best = e.getKey(); bm = m; }
            }
            if (bs < minScore) { best = fallback; bm = List.of(); }
            counts.merge(best, 1, (a, b) -> (Integer) a + 1);
            out.add(List.of(msgs.get(i).id(), best, bs, String.join(", ", bm)));
        }
        return Map.of("routed", table(List.of("id", "queue", "score", "matched"), out), "counts", counts);
    }
}
