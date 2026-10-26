package com.oneofficeautomation.controlroom;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the portable SQL the Command Center writes (H2 and PostgreSQL syntax) into Microsoft SQL Server syntax.
 * Statements: TRUE/FALSE become 1/0, a trailing LIMIT/OFFSET becomes OFFSET ... FETCH (or TOP without ORDER BY),
 * two-argument SUBSTRING gets a length and the reserved column name "external" is bracketed. Text inside quotes is
 * never changed. Schema: Unicode text types, BIT, DATETIME2, ADD without COLUMN, composite keys non-clustered (a
 * clustered key may hold only 900 bytes).
 */
final class SqlServerDialect {
    private SqlServerDialect() { }

    /** A translated statement and, when placeholders moved, the old index of each new placeholder. */
    record Statement(String sql, int[] order) {
        Object[] reorder(Object[] params) {
            if (order == null) return params;
            Object[] out = new Object[order.length];
            for (int i = 0; i < order.length; i++) out[i] = params[order[i]];
            return out;
        }
    }

    private static final Pattern LIMIT = Pattern.compile("(?i)\\s+LIMIT\\s+(\\?|\\d+)(?:\\s+OFFSET\\s+(\\?|\\d+))?\\s*$");
    private static final Pattern SUBSTRING2 = Pattern.compile("(?i)SUBSTRING\\(([A-Za-z_][A-Za-z_0-9.]*),\\s*(\\?|\\d+)\\)");
    private static final Pattern BOOL = Pattern.compile("\\b(TRUE|FALSE)\\b");
    private static final Pattern RESERVED = Pattern.compile("(?<![\\w\\[])(external)(?![\\w\\]])");
    private static final Pattern SELECT = Pattern.compile("(?i)^\\s*SELECT\\s+(DISTINCT\\s+)?");
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\bORDER\\s+BY\\b");

    private static final Map<String, Statement> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Statement> e) { return size() > 1000; }
    });

    static Statement statement(String sql) { return CACHE.computeIfAbsent(sql, SqlServerDialect::translate); }

    static Statement translate(String sql) {
        String s = outsideQuotes(sql, part -> {
            part = BOOL.matcher(part).replaceAll(m -> m.group(1).equals("TRUE") ? "1" : "0");
            part = RESERVED.matcher(part).replaceAll("[$1]");
            return SUBSTRING2.matcher(part).replaceAll("SUBSTRING($1, $2, 2147483647)");
        });
        Matcher m = LIMIT.matcher(s);
        if (!m.find()) return new Statement(s, null);
        String limit = m.group(1), offset = m.group(2) == null ? "0" : m.group(2);
        String head = s.substring(0, m.start());
        int marks = count(s, '?');
        if (ORDER_BY.matcher(head).find()) {
            String out = head + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            if (!limit.equals("?") || !offset.equals("?")) return new Statement(out, null);
            int[] order = identity(marks);   // LIMIT ? OFFSET ? -> OFFSET ? ... FETCH NEXT ?: the last two swap
            order[marks - 2] = marks - 1; order[marks - 1] = marks - 2;
            return new Statement(out, order);
        }
        Matcher sel = SELECT.matcher(head);
        if (!sel.find() || !offset.equals("0")) throw new IllegalArgumentException("Cannot translate LIMIT without ORDER BY: " + sql);
        String distinct = sel.group(1) == null ? "" : "DISTINCT ";
        String out = "SELECT " + distinct + "TOP (" + limit + ") " + head.substring(sel.end());
        if (!limit.equals("?")) return new Statement(out, null);
        int[] order = new int[marks];   // TOP (?) is now the first placeholder
        order[0] = marks - 1;
        for (int i = 1; i < marks; i++) order[i] = i - 1;
        return new Statement(out, order);
    }

    private static final Pattern VARCHAR = Pattern.compile("(?i)\\bVARCHAR\\((\\d+)\\)");
    private static final Pattern ADD_COLUMN = Pattern.compile("(?i)^(ALTER TABLE \\w+) ADD COLUMN ");
    private static final Pattern COMPOSITE_KEY = Pattern.compile("(?i)PRIMARY KEY \\(");

    /** A schema statement in SQL Server syntax. */
    static String ddl(String sql) {
        String s = VARCHAR.matcher(sql).replaceAll(m -> Integer.parseInt(m.group(1)) > 4000 ? "NVARCHAR(MAX)" : "NVARCHAR(" + m.group(1) + ")");
        s = s.replaceAll("\\bBOOLEAN\\b", "BIT").replaceAll("\\bTIMESTAMP\\b", "DATETIME2");
        s = ADD_COLUMN.matcher(s).replaceFirst("$1 ADD ");
        s = COMPOSITE_KEY.matcher(s).replaceAll("PRIMARY KEY NONCLUSTERED (");
        return RESERVED.matcher(s).replaceAll("[$1]");
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    private static int[] identity(int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i; return a; }

    /** Applies f to every stretch of the statement that is not inside single quotes. */
    private static String outsideQuotes(String sql, java.util.function.UnaryOperator<String> f) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            int q = sql.indexOf('\'', i);
            if (q < 0) { out.append(f.apply(sql.substring(i))); break; }
            out.append(f.apply(sql.substring(i, q)));
            int end = q + 1;
            while (end < sql.length()) {
                if (sql.charAt(end) == '\'') { if (end + 1 < sql.length() && sql.charAt(end + 1) == '\'') { end += 2; continue; } break; }
                end++;
            }
            out.append(sql, q, Math.min(end + 1, sql.length()));
            i = end + 1;
        }
        return out.toString();
    }
}
