package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Approximate text matching for names, addresses and references that are spelled slightly differently.
 * Scores run from 0 (nothing in common) to 100 (identical). The algorithms are implemented here, with no extra library.
 */
@OoaPackage(id = "ooa.fuzzy", name = "Fuzzy Match", version = "1.0.0",
        description = "Score how alike two texts are, pick the closest candidate from a list and join tables on near-matching values.")
public final class FuzzyPackage {
    static final String[] ALGORITHMS = {"levenshtein", "jaro_winkler", "token_sort", "token_set"};
    static final String D_ALGO = "levenshtein: edit distance, good for typos. jaro_winkler: favours a shared start, good for short names. "
            + "token_sort: ignores word order. token_set: ignores word order and extra words.";
    static final String D_CASE = "Treat upper and lower case as different.";

    // ---------- algorithms ----------

    static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 1 - distance / longer length, in 0..1. */
    static double levenshteinRatio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1.0 : 1.0 - (double) levenshtein(a, b) / max;
    }

    static double jaro(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] ma = new boolean[a.length()], mb = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int lo = Math.max(0, i - window), hi = Math.min(b.length() - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                if (!mb[j] && a.charAt(i) == b.charAt(j)) { ma[i] = true; mb[j] = true; matches++; break; }
            }
        }
        if (matches == 0) return 0.0;
        int k = 0, halfTranspositions = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!ma[i]) continue;
            while (!mb[k]) k++;
            if (a.charAt(i) != b.charAt(k)) halfTranspositions++;
            k++;
        }
        double m = matches, t = halfTranspositions / 2.0;
        return (m / a.length() + m / b.length() + (m - t) / m) / 3.0;
    }

    /** Jaro similarity raised for a common prefix (up to 4 characters, scaling factor 0.1). */
    static double jaroWinkler(String a, String b) {
        double j = jaro(a, b);
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())); i++) {
            if (a.charAt(i) == b.charAt(i)) prefix++; else break;
        }
        return j + prefix * 0.1 * (1.0 - j);
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("[^\\p{L}\\p{N}]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    static double tokenSort(String a, String b) {
        List<String> ta = tokens(a), tb = tokens(b);
        Collections.sort(ta); Collections.sort(tb);
        return levenshteinRatio(String.join(" ", ta), String.join(" ", tb));
    }

    /** Compares the shared words with each side's shared-plus-remaining words and keeps the best ratio. */
    static double tokenSet(String a, String b) {
        TreeSet<String> sa = new TreeSet<>(tokens(a)), sb = new TreeSet<>(tokens(b));
        if (sa.isEmpty() && sb.isEmpty()) return 1.0;
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        TreeSet<String> common = new TreeSet<>(sa); common.retainAll(sb);
        TreeSet<String> onlyA = new TreeSet<>(sa); onlyA.removeAll(sb);
        TreeSet<String> onlyB = new TreeSet<>(sb); onlyB.removeAll(sa);
        String base = String.join(" ", common);
        String withA = (base + " " + String.join(" ", onlyA)).strip();
        String withB = (base + " " + String.join(" ", onlyB)).strip();
        double best = levenshteinRatio(withA, withB);
        if (!base.isEmpty()) best = Math.max(best, Math.max(levenshteinRatio(base, withA), levenshteinRatio(base, withB)));
        return best;
    }

    /** Score 0..100 rounded to two decimals. */
    static BigDecimal score(String a, String b, String algorithm, boolean caseSensitive) {
        String x = a == null ? "" : a.strip(), y = b == null ? "" : b.strip();
        if (!caseSensitive) { x = x.toLowerCase(Locale.ROOT); y = y.toLowerCase(Locale.ROOT); }
        double s = switch (algorithm == null ? "levenshtein" : algorithm) {
            case "levenshtein" -> levenshteinRatio(x, y);
            case "jaro_winkler" -> jaroWinkler(x, y);
            case "token_sort" -> tokenSort(x, y);
            case "token_set" -> tokenSet(x, y);
            default -> throw new ActionException("FUZZY.BAD_ALGORITHM", "Unknown algorithm '" + algorithm + "'",
                    "Use one of " + String.join(", ", ALGORITHMS) + ".");
        };
        return BigDecimal.valueOf(s * 100).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal threshold(double t) {
        if (Double.isNaN(t) || t < 0 || t > 100)
            throw new ActionException("FUZZY.BAD_THRESHOLD", "Threshold must be between 0 and 100, got " + t, "Use for example 85 to accept close matches only.");
        return BigDecimal.valueOf(t);
    }

    // ---------- actions ----------

    @Action(id = "similarity", name = "Text similarity", category = "Fuzzy match", idempotent = true,
            description = "Score how alike two texts are, from 0 (nothing in common) to 100 (identical).")
    @Output(name = "score", type = OoaType.NUMBER, description = "Similarity from 0 to 100.")
    public Map<String, Object> similarity(ActionContext ctx,
            @Input(name = "text1", type = OoaType.STRING, description = "First text.") String text1,
            @Input(name = "text2", type = OoaType.STRING, description = "Second text.") String text2,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"levenshtein\"", enumValues = {"levenshtein", "jaro_winkler", "token_sort", "token_set"}, description = D_ALGO) String algorithm,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_CASE) boolean caseSensitive) {
        return Out.of("score", score(text1, text2, algorithm, caseSensitive));
    }

    @Action(id = "best_match", name = "Find closest match", category = "Fuzzy match", idempotent = true,
            description = "Compare a text with every entry of a list and return the closest entry when it scores at least the threshold.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when an entry reached the threshold.")
    @Output(name = "match", type = OoaType.STRING, description = "Closest entry, empty when none reached the threshold.")
    @Output(name = "score", type = OoaType.NUMBER, description = "Score of the closest entry (0 to 100), even when below the threshold.")
    @Output(name = "index", type = OoaType.NUMBER, description = "Zero-based position of the match in the list, -1 when none.")
    public Map<String, Object> bestMatch(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "candidates", type = OoaType.LIST, description = "Entries to compare with, e.g. a list of customer names.") List<Object> candidates,
            @Input(name = "threshold", type = OoaType.NUMBER, required = false, defaultValue = "80", min = 0, max = 100, description = "Lowest score (0 to 100) accepted as a match.") double threshold,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"levenshtein\"", enumValues = {"levenshtein", "jaro_winkler", "token_sort", "token_set"}, description = D_ALGO) String algorithm,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_CASE) boolean caseSensitive) {
        BigDecimal min = threshold(threshold);
        BigDecimal best = BigDecimal.ZERO;
        int bestIdx = -1;
        List<Object> list = candidates == null ? List.of() : candidates;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null) continue;
            BigDecimal s = score(text, Values.text(list.get(i)), algorithm, caseSensitive);
            if (bestIdx < 0 || s.compareTo(best) > 0) { best = s; bestIdx = i; }
        }
        boolean found = bestIdx >= 0 && best.compareTo(min) >= 0;
        if (bestIdx < 0) score("", "", algorithm, caseSensitive); // still reject an unknown algorithm on an empty list
        return Out.of("found", found, "match", found ? Values.text(list.get(bestIdx)) : "", "score", best,
                "index", BigDecimal.valueOf(found ? bestIdx : -1));
    }

    @Action(id = "fuzzy_lookup", name = "Fuzzy join tables", category = "Fuzzy match", idempotent = true,
            description = "For each row of the first table, find the row of the second table whose column value is closest and append its columns and the score.")
    @Output(name = "table", type = OoaType.TABLE, description = "First table's columns, then the second table's (renamed with a right_ prefix on a clash), then match_score.")
    @Output(name = "matched", type = OoaType.NUMBER, description = "Rows that found a match.")
    @Output(name = "unmatched", type = OoaType.NUMBER, description = "Rows of the first table without a match.")
    public Map<String, Object> fuzzyLookup(ActionContext ctx,
            @Input(name = "left_table", type = OoaType.TABLE, description = "Table whose rows you want to enrich.") Map<String, Object> left,
            @Input(name = "left_column", type = OoaType.STRING, description = "Column of the first table to match on.") String leftColumn,
            @Input(name = "right_table", type = OoaType.TABLE, description = "Reference table to look values up in.") Map<String, Object> right,
            @Input(name = "right_column", type = OoaType.STRING, description = "Column of the reference table to match on.") String rightColumn,
            @Input(name = "threshold", type = OoaType.NUMBER, required = false, defaultValue = "80", min = 0, max = 100, description = "Lowest score (0 to 100) accepted as a match.") double threshold,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"token_set\"", enumValues = {"levenshtein", "jaro_winkler", "token_sort", "token_set"}, description = D_ALGO) String algorithm,
            @Input(name = "keep_unmatched", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Keep rows without a match, with empty reference columns. Off: drop them.") boolean keepUnmatched,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_CASE) boolean caseSensitive) {
        BigDecimal min = threshold(threshold);
        score("", "", algorithm, caseSensitive);
        int lc = DataTablePackage.col(left, leftColumn), rc = DataTablePackage.col(right, rightColumn);
        List<String> lcols = DataTablePackage.cols(left), rcols = DataTablePackage.cols(right);
        List<String> columns = new ArrayList<>(lcols);
        for (String c : rcols) columns.add(columns.contains(c) || c.equals("match_score") ? "right_" + c : c);
        columns.add("match_score");
        List<List<Object>> rrows = DataTablePackage.rows(right);
        List<String> rkeys = new ArrayList<>();
        for (List<Object> r : rrows) { Object v = DataTablePackage.cell(r, rc); rkeys.add(v == null ? null : Values.text(v)); }
        List<Object> out = new ArrayList<>();
        int matched = 0, unmatched = 0;
        for (List<Object> lr : DataTablePackage.rows(left)) {
            ctx.checkCancelled();
            Object lv = DataTablePackage.cell(lr, lc);
            int best = -1;
            BigDecimal bestScore = BigDecimal.ZERO;
            if (lv != null && !Values.text(lv).isBlank()) for (int i = 0; i < rkeys.size(); i++) {
                if (rkeys.get(i) == null) continue;
                BigDecimal s = score(Values.text(lv), rkeys.get(i), algorithm, caseSensitive);
                if (s.compareTo(bestScore) > 0) { bestScore = s; best = i; }
            }
            boolean ok = best >= 0 && bestScore.compareTo(min) >= 0;
            if (ok) matched++; else unmatched++;
            if (!ok && !keepUnmatched) continue;
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < lcols.size(); i++) row.add(DataTablePackage.cell(lr, i));
            for (int i = 0; i < rcols.size(); i++) row.add(ok ? DataTablePackage.cell(rrows.get(best), i) : null);
            row.add(ok ? bestScore : null);
            out.add(row);
        }
        return Out.of("table", Values.table(columns, out), "matched", BigDecimal.valueOf(matched), "unmatched", BigDecimal.valueOf(unmatched));
    }

    @Condition(id = "texts_similar", name = "Texts are similar", description = "True when two texts score at least the threshold (0 to 100).")
    public boolean textsSimilar(ActionContext ctx,
            @Input(name = "text1", type = OoaType.STRING, description = "First text.") String text1,
            @Input(name = "text2", type = OoaType.STRING, description = "Second text.") String text2,
            @Input(name = "threshold", type = OoaType.NUMBER, required = false, defaultValue = "80", min = 0, max = 100, description = "Lowest score that counts as similar.") double threshold,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"levenshtein\"", enumValues = {"levenshtein", "jaro_winkler", "token_sort", "token_set"}, description = D_ALGO) String algorithm,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_CASE) boolean caseSensitive) {
        return score(text1, text2, algorithm, caseSensitive).compareTo(threshold(threshold)) >= 0;
    }
}
