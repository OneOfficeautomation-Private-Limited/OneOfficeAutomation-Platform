package com.oneofficeautomation.sdk;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Files chosen by name rules instead of one exact path, as a File field's "Name rule" mode (AA's file fields offer a
 * folder plus regular-expression conditions; ours are rules written in words, a regular expression being one kind).
 * Written in a step as
 * <pre>{"$files": {"folder": "C:\\Invoices", "rules": [{"what": "name", "op": "starts", "value": "INV_"},
 *   {"what": "type", "op": "in", "value": "pdf, xlsx"}], "all": true, "matchCase": false, "subfolders": false}}</pre>
 * Rule kinds: name (is, starts, ends, contains, wildcard, regex), type (in), size (atLeast, atMost, exactly; value
 * with unit B/KB/MB/GB, default KB) and date (which created|modified; lastDays, on, before, after, between with value
 * and value2 as yyyy-MM-dd; inclusive). A command that works on one file gets the first match (sorted by path); a
 * command that works on many asks {@link ActionContext#selectedFiles(String)} for all of them.
 */
public final class FileSelection {
    public static final String KEY = "$files";

    public record Rule(String what, String op, String value, String value2, String which, String unit, boolean inclusive) {}

    private final String folder;
    private final List<Rule> rules;
    private final boolean all, matchCase, subfolders;

    private FileSelection(String folder, List<Rule> rules, boolean all, boolean matchCase, boolean subfolders) {
        this.folder = folder; this.rules = rules; this.all = all; this.matchCase = matchCase; this.subfolders = subfolders;
    }

    /** True when the value is a {"$files": {...}} selection. */
    public static boolean isSelection(Object v) { return v instanceof Map<?, ?> m && m.size() == 1 && m.get(KEY) instanceof Map; }

    /** Reads a {"$files": {...}} value; a malformed one fails with FILE.BAD_RULE and says what to fix. */
    @SuppressWarnings("unchecked")
    public static FileSelection parse(Object v) {
        if (!isSelection(v)) throw bad("is not a name-rule selection");
        Map<String, Object> m = (Map<String, Object>) ((Map<?, ?>) v).get(KEY);
        Object f = m.get("folder");
        if (!(f instanceof String folder) || folder.isBlank()) throw bad("has no folder");
        List<Rule> rules = new ArrayList<>();
        if (m.get("rules") instanceof List<?> l) for (Object o : l) {
            if (!(o instanceof Map<?, ?> r)) throw bad("has a rule that is not a list of fields");
            String what = str(r.get("what")), op = str(r.get("op"));
            if (!List.of("name", "type", "size", "date").contains(what)) throw bad("has a rule on '" + what + "'; use name, type, size or date");
            Rule rule = new Rule(what, op, str(r.get("value")), str(r.get("value2")), str(r.get("which")).isEmpty() ? "modified" : str(r.get("which")),
                    str(r.get("unit")).isEmpty() ? "KB" : str(r.get("unit")), !Boolean.FALSE.equals(r.get("inclusive")));
            check(rule, Boolean.TRUE.equals(m.get("matchCase")));
            rules.add(rule);
        }
        return new FileSelection(folder.strip(), List.copyOf(rules), !Boolean.FALSE.equals(m.get("all")),
                Boolean.TRUE.equals(m.get("matchCase")), Boolean.TRUE.equals(m.get("subfolders")));
    }

    /** A selection from a legacy folder + wildcard pattern (the older "pattern" inputs). */
    public static FileSelection ofWildcard(String folder, String pattern, boolean subfolders) {
        List<Rule> r = pattern == null || pattern.isBlank() ? List.of() : List.of(new Rule("name", "wildcard", pattern, "", "modified", "KB", true));
        return new FileSelection(folder, r, true, false, subfolders);
    }

    public String folder() { return folder; }
    public List<Rule> rules() { return rules; }
    public boolean subfolders() { return subfolders; }

    /** The matching files (not folders), sorted by path; {@code resolver} turns the folder text into a path. */
    public List<Path> resolve(Function<String, Path> resolver) {
        Path dir = resolver.apply(folder);
        if (!Files.isDirectory(dir)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + folder, "Check the folder of the name rules; it must exist on the runner.");
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = subfolders ? Files.walk(dir) : Files.list(dir)) {
            s.filter(Files::isRegularFile).filter(p -> matches(dir, p)).sorted(Comparator.comparing(Path::toString)).forEach(out::add);
        } catch (IOException e) {
            throw new ActionException("FILE.IO", "Cannot list " + folder + ": " + e.getMessage(), "Check the folder exists and the runner may read it.");
        }
        return out;
    }

    /** Whether one file (below {@code base}) meets the rules: all of them, or any one when "all" is off. */
    public boolean matches(Path base, Path file) {
        if (rules.isEmpty()) return true;
        for (Rule r : rules) {
            boolean hit = test(r, file);
            if (all && !hit) return false;
            if (!all && hit) return true;
        }
        return all;
    }

    /** The rules in words, for the step line and the log: "name starts with INV_ and type is pdf, xlsx". */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Rule r : rules) parts.add(switch (r.what()) {
            case "name" -> "name " + switch (r.op()) { case "is" -> "is"; case "starts" -> "starts with"; case "ends" -> "ends with";
                case "contains" -> "contains"; case "regex" -> "matches pattern"; default -> "matches"; } + " " + r.value();
            case "type" -> "type is " + r.value();
            case "size" -> "size " + switch (r.op()) { case "atMost" -> "at most"; case "exactly" -> "exactly"; default -> "at least"; } + " " + r.value() + " " + r.unit();
            default -> r.which() + " " + switch (r.op()) { case "lastDays" -> "in the last " + r.value() + " days"; case "on" -> "on " + r.value();
                case "before" -> "before " + r.value(); case "after" -> "after " + r.value(); default -> "between " + r.value() + " and " + r.value2(); };
        });
        return (parts.isEmpty() ? "every file" : String.join(all ? " and " : " or ", parts)) + " in " + folder + (subfolders ? " and sub-folders" : "");
    }

    private boolean test(Rule r, Path file) {
        String name = file.getFileName().toString();
        String n = matchCase ? name : name.toLowerCase(Locale.ROOT), v = matchCase ? r.value() : r.value().toLowerCase(Locale.ROOT);
        try {
            return switch (r.what()) {
                case "name" -> switch (r.op()) {
                    case "is" -> n.equals(v);
                    case "starts" -> n.startsWith(v);
                    case "ends" -> n.endsWith(v);
                    case "contains" -> n.contains(v);
                    case "regex" -> Pattern.compile(r.value(), matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(name).find();
                    default -> glob(v).matches(Path.of(n));
                };
                case "type" -> {
                    String ext = name.lastIndexOf('.') > 0 ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                    boolean any = false;
                    for (String t : r.value().split("[,;\\s]+")) if (!t.isBlank() && t.replaceFirst("^\\*?\\.", "").toLowerCase(Locale.ROOT).equals(ext)) any = true;
                    yield any;
                }
                case "size" -> {
                    long size = Files.size(file), lim = Math.round(Double.parseDouble(r.value()) * switch (r.unit().toUpperCase(Locale.ROOT)) {
                        case "B" -> 1d; case "MB" -> 1024d * 1024; case "GB" -> 1024d * 1024 * 1024; default -> 1024d; });
                    yield switch (r.op()) { case "atMost" -> size <= lim; case "exactly" -> size == lim; default -> size >= lim; };
                }
                default -> {
                    BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                    Instant t = ("created".equals(r.which()) ? a.creationTime() : a.lastModifiedTime()).toInstant();
                    LocalDate d = t.atZone(ZoneId.systemDefault()).toLocalDate();
                    yield switch (r.op()) {
                        case "lastDays" -> !t.isBefore(Instant.now().minus(Long.parseLong(r.value().strip()), ChronoUnit.DAYS));
                        case "on" -> d.equals(LocalDate.parse(r.value().strip()));
                        case "before" -> r.inclusive() ? !d.isAfter(LocalDate.parse(r.value().strip())) : d.isBefore(LocalDate.parse(r.value().strip()));
                        case "after" -> r.inclusive() ? !d.isBefore(LocalDate.parse(r.value().strip())) : d.isAfter(LocalDate.parse(r.value().strip()));
                        default -> {
                            LocalDate from = LocalDate.parse(r.value().strip()), to = LocalDate.parse(r.value2().strip());
                            yield r.inclusive() ? !d.isBefore(from) && !d.isAfter(to) : d.isAfter(from) && d.isBefore(to);
                        }
                    };
                }
            };
        } catch (IOException e) {
            return false;
        }
    }

    private static PathMatcher glob(String pattern) { return FileSystems.getDefault().getPathMatcher("glob:" + pattern); }

    private static void check(Rule r, boolean matchCase) {
        switch (r.what()) {
            case "name" -> {
                if (!List.of("is", "starts", "ends", "contains", "wildcard", "regex").contains(r.op())) throw bad("has a name rule '" + r.op() + "'; use is, starts, ends, contains, wildcard or regex");
                if (r.value().isEmpty()) throw bad("has a name rule without a value");
                if (r.op().equals("regex")) {
                    try { Pattern.compile(r.value()); }
                    catch (PatternSyntaxException e) { throw bad("has a pattern that is not a valid regular expression: " + e.getDescription()); }
                }
            }
            case "type" -> { if (r.value().isBlank()) throw bad("has a type rule without file types (pdf, xlsx)"); }
            case "size" -> {
                if (!List.of("atLeast", "atMost", "exactly").contains(r.op())) throw bad("has a size rule '" + r.op() + "'; use atLeast, atMost or exactly");
                try { Double.parseDouble(r.value()); } catch (NumberFormatException e) { throw bad("has a size that is not a number: " + r.value()); }
            }
            default -> {
                if (!List.of("lastDays", "on", "before", "after", "between").contains(r.op())) throw bad("has a date rule '" + r.op() + "'; use lastDays, on, before, after or between");
                try {
                    if (r.op().equals("lastDays")) Long.parseLong(r.value().strip());
                    else { LocalDate.parse(r.value().strip()); if (r.op().equals("between")) LocalDate.parse(r.value2().strip()); }
                } catch (RuntimeException e) { throw bad("has a date rule with a value that is not " + (r.op().equals("lastDays") ? "a whole number of days" : "a date like 2026-11-21")); }
            }
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static ActionException bad(String what) {
        return new ActionException("FILE.BAD_RULE", "The file name rules " + what, "Open the step and fix the rule in the file field's Name rule mode.");
    }
}
