package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import com.oneofficeautomation.sdk.LoopIterator;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import java.math.BigDecimal;
import java.time.*;

@OoaPackage(id = "ooa.file", name = "File", version = "1.0.0", description = "Read, write, copy, move and delete files.")
public final class FilePackage {

    private static ActionException io(String what, Path p, IOException e) {
        return new ActionException("FILE.IO_ERROR", "Cannot " + what + " " + p + ": " + e.getMessage(), "Check the path and permissions.", true);
    }

    /** Charset by name; "ansi" or "default" is the runner's default code page, "unicode" is UTF-16LE. */
    static Charset charset(String enc) {
        if (enc == null || enc.isBlank()) return java.nio.charset.StandardCharsets.UTF_8;
        String e = enc.strip().toLowerCase(Locale.ROOT);
        if (e.equals("ansi") || e.equals("default")) return Charset.defaultCharset();
        if (e.equals("unicode")) return java.nio.charset.StandardCharsets.UTF_16LE;
        try { return Charset.forName(e); }
        catch (IllegalArgumentException x) { throw new ActionException("FILE.BAD_ENCODING", "Unknown encoding '" + enc + "'", "Use utf-8, utf-16le, windows-1252 or ansi."); }
    }

    /** Size and age limits shared by List, Delete and Print files; null limits are ignored. */
    record Filter(Long minBytes, Long maxBytes, Integer modifiedWithinDays, Integer olderThanDays) {
        static final Filter NONE = new Filter(null, null, null, null);
        boolean test(Path p) {
            if (this.equals(NONE)) return true;
            try {
                long size = Files.size(p);
                if (minBytes != null && size < minBytes) return false;
                if (maxBytes != null && size > maxBytes) return false;
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis();
                if (modifiedWithinDays != null && age > modifiedWithinDays * 86_400_000L) return false;
                if (olderThanDays != null && age < olderThanDays * 86_400_000L) return false;
                return true;
            } catch (IOException e) { return false; }
        }
    }

    static Filter filter(Number min, Number max, Number within, Number older) {
        return new Filter(min == null ? null : min.longValue(), max == null ? null : max.longValue(), within == null ? null : within.intValue(), older == null ? null : older.intValue());
    }

    /** True when the file was last changed after 'after' and before 'before' (local time); an empty limit always passes. */
    static boolean modifiedBetween(Path p, LocalDateTime after, LocalDateTime before) {
        if (after == null && before == null) return true;
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(modified(p)), ZoneId.systemDefault());
        return (after == null || t.isAfter(after)) && (before == null || t.isBefore(before));
    }

    @Action(id = "read_text", name = "Read text", category = "Files", idempotent = true, capabilities = "filesystem", description = "Read a text file.",
            panel = "path, encoding")
    @Output(name = "text", type = OoaType.STRING, description = "File content.")
    public Map<String, Object> readText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding.") String enc) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path and permissions.");
        try { return Map.of("text", Files.readString(p, charset(enc))); } catch (IOException e) { throw io("read", p, e); }
    }

    @Action(id = "write_text", name = "Write text", category = "Files", capabilities = "filesystem", description = "Write text to a file, overwriting or appending.",
            panel = "path, fail_if_exists, text, append | encoding")
    public Map<String, Object> writeText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "text", type = OoaType.STRING, description = "Text to write.") String text,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Append instead of overwrite.") boolean append,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", example = "utf-16le", description = "Text encoding, e.g. utf-8, utf-16le, windows-1252; ansi uses the runner's default.") String enc,
            @Input(name = "fail_if_exists", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop instead of writing when the file already exists.") boolean failIfExists) {
        Path p = ctx.resolvePath(path);
        if (failIfExists && Files.exists(p)) throw new ActionException("FILE.EXISTS", "File already exists: " + p, "Choose another name or turn off fail_if_exists.");
        Charset cs = charset(enc);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (append) Files.writeString(p, text, cs, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else Files.writeString(p, text, cs);
        } catch (IOException e) { throw io("write", p, e); }
        return Map.of();
    }

    @Action(id = "delete", name = "Delete", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Delete a file if it exists, or every file in a folder that matches a pattern and optional size and age limits.",
            panel = "path, [Delete several files by name pattern: pattern, include_subfolders], [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: older_than_days, modified_after, modified_before]")
    @Output(name = "deleted", type = OoaType.NUMBER, description = "Number of files deleted.")
    public Map<String, Object> delete(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File path, or a folder when pattern is given.") String path,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.tmp", description = "Delete the files in the folder at path that match this glob pattern.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "With pattern, also delete matching files in sub-folders.") boolean sub,
            @Input(name = "older_than_days", type = OoaType.NUMBER, required = false, min = 0, description = "With pattern, only files last changed more than this many days ago.") Integer older,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With pattern, only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With pattern, only files at most this large.") Long max,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, visibleWhen = "pattern!=", description = "With pattern, only files last changed after this date and time (local time).") LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, visibleWhen = "pattern!=", description = "With pattern, only files last changed before this date and time (local time).") LocalDateTime before) {
        Path p = ctx.resolvePath(path);
        if (pattern == null || pattern.isBlank()) {
            try { return Map.of("deleted", BigDecimal.valueOf(Files.deleteIfExists(p) ? 1 : 0)); } catch (IOException e) { throw io("delete", p, e); }
        }
        int n = 0;
        for (String f : files(p, pattern, sub, filter(min, max, null, older))) {
            if (!modifiedBetween(Path.of(f), after, before)) continue;
            ctx.checkCancelled();
            try { if (Files.deleteIfExists(Path.of(f))) n++; } catch (IOException e) { throw io("delete", Path.of(f), e); }
        }
        return Map.of("deleted", BigDecimal.valueOf(n));
    }

    public Map<String, Object> delete(ActionContext ctx, String path, String pattern, boolean sub, Integer older, Long min, Long max) {
        return delete(ctx, path, pattern, sub, older, min, max, null, null);
    }

    @Action(id = "list", name = "List files", category = "Folders", idempotent = true, capabilities = "filesystem",
            description = "List files in a folder matching a glob pattern.",
            panel = "folder, pattern, include_subfolders, [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: modified_within_days, older_than_days] | sort_by, descending")
    @Output(name = "files", type = OoaType.LIST, description = "File paths in the chosen order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files found.")
    @Output(name = "first", type = OoaType.FILE, description = "First file of the list (e.g. the newest with sort_by modified, descending); empty when none.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder path.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob pattern, e.g. *.pdf.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also list matching files in sub-folders.") boolean sub,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "Only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "Only files at most this large.") Long max,
            @Input(name = "modified_within_days", type = OoaType.NUMBER, required = false, min = 0, description = "Only files changed in the last this many days.") Integer within,
            @Input(name = "older_than_days", type = OoaType.NUMBER, required = false, min = 0, description = "Only files last changed more than this many days ago.") Integer older,
            @Input(name = "sort_by", type = OoaType.STRING, required = false, defaultValue = "\"name\"", enumValues = {"name", "modified", "size"},
                    description = "Order of the list: by path name, by last change (oldest first) or by size (smallest first).") String sortBy,
            @Input(name = "descending", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Reverse the order, e.g. newest first with sort_by modified.") boolean descending) {
        List<Object> list = new ArrayList<>(files(ctx.resolvePath(folder), pattern, sub, filter(min, max, within, older)));
        Comparator<Object> by = switch (sortBy == null ? "name" : sortBy) {
            case "modified" -> Comparator.comparingLong(p -> modified(Path.of((String) p)));
            case "size" -> Comparator.comparingLong(p -> size(Path.of((String) p)));
            case "name" -> Comparator.comparing(p -> (String) p);
            default -> throw new ActionException("FILE.BAD_OPTION", "Unknown sort '" + sortBy + "'", "Use name, modified or size.");
        };
        list.sort(descending ? by.reversed() : by);
        return Out.of("files", list, "count", BigDecimal.valueOf(list.size()), "first", list.isEmpty() ? "" : list.get(0));
    }

    public Map<String, Object> list(ActionContext ctx, String folder, String pattern, boolean sub, Long min, Long max, Integer within, Integer older) {
        return list(ctx, folder, pattern, sub, min, max, within, older, "name", false);
    }

    private static long modified(Path p) { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; } }
    private static long size(Path p) { try { return Files.size(p); } catch (IOException e) { return 0; } }

    @Condition(id = "exists", name = "File exists", description = "True when the file exists.")
    public boolean exists(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File path.") String path) {
        return Files.isRegularFile(ctx.resolvePath(path));
    }

    private static Path existing(ActionContext ctx, String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        return p;
    }

    private static boolean compare(long actual, String op, long expected) {
        return switch (op == null ? "eq" : op) {
            case "eq" -> actual == expected; case "ne" -> actual != expected; case "gt" -> actual > expected;
            case "ge" -> actual >= expected; case "lt" -> actual < expected; case "le" -> actual <= expected;
            default -> throw new ActionException("FILE.BAD_OPERATOR", "Unknown operator '" + op + "'", "Use eq, ne, gt, ge, lt or le.");
        };
    }

    @Condition(id = "size_is", name = "File size is", description = "Compare the size of a file with a number of bytes, KB or MB, e.g. larger than 0 bytes.")
    public boolean sizeIs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"gt\"", enumValues = {"eq", "ne", "gt", "ge", "lt", "le"}, description = "How to compare.") String op,
            @Input(name = "size", type = OoaType.NUMBER, min = 0, description = "Size to compare with.") BigDecimal size,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"bytes\"", enumValues = {"bytes", "KB", "MB", "GB"}, description = "Unit of the size (1 KB = 1024 bytes).") String unit) {
        long mult = switch (unit == null ? "bytes" : unit) { case "KB" -> 1024L; case "MB" -> 1024L * 1024; case "GB" -> 1024L * 1024 * 1024; default -> 1L; };
        return compare(size(existing(ctx, path)), op, size.multiply(BigDecimal.valueOf(mult)).longValue());
    }

    @Condition(id = "date_is", name = "File date is", description = "Compare when a file was last changed or created with a date, or check its age in days.")
    public boolean dateIs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "which", type = OoaType.STRING, required = false, defaultValue = "\"modified\"", enumValues = {"modified", "created"}, description = "Which date of the file.") String which,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"before\"", enumValues = {"before", "after", "on", "older_than_days", "newer_than_days"},
                    description = "Before, after or on a date (calendar day, UTC), or older/newer than a number of days.") String op,
            @Input(name = "date", type = OoaType.DATETIME, required = false, visibleWhen = "operator=before|after|on", description = "Date to compare with (UTC).") java.time.LocalDateTime date,
            @Input(name = "days", type = OoaType.NUMBER, required = false, visibleWhen = "operator=older_than_days|newer_than_days", min = 0, description = "Age in days.") BigDecimal days) {
        Path p = existing(ctx, path);
        long ms;
        try {
            ms = "created".equals(which) ? ((java.nio.file.attribute.FileTime) Files.getAttribute(p, "creationTime")).toMillis() : Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) { throw io("read the date of", p, e); }
        LocalDateTime fileDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
        String o = op == null ? "before" : op;
        if (o.endsWith("_days")) {
            if (days == null) throw new ActionException("FILE.BAD_OPTION", "Give the number of days", "Fill in 'days'.");
            long ageMs = System.currentTimeMillis() - ms, limit = days.multiply(BigDecimal.valueOf(86_400_000L)).longValue();
            return o.startsWith("older") ? ageMs > limit : ageMs < limit;
        }
        if (date == null) throw new ActionException("FILE.BAD_OPTION", "Give the date to compare with", "Fill in 'date'.");
        return switch (o) {
            case "after" -> fileDate.isAfter(date);
            case "on" -> fileDate.toLocalDate().equals(date.toLocalDate());
            default -> fileDate.isBefore(date);
        };
    }

    @Condition(id = "has_extension", name = "File has extension", description = "True when the file name ends with one of the extensions, e.g. pdf, xlsx (case is ignored).")
    public boolean hasExtension(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path or name.") String path,
            @Input(name = "extensions", type = OoaType.STRING, description = "One or more extensions separated by commas, with or without the dot.", example = "pdf, xlsx") String extensions) {
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : extensions.split("[,; ]+")) {
            String x = e.strip().toLowerCase(Locale.ROOT);
            if (x.isEmpty()) continue;
            if (name.endsWith(x.startsWith(".") ? x : "." + x)) return true;
        }
        return false;
    }

    @Action(id = "read_variables", name = "Read variables from file", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Read name=value lines (a settings or .properties-style file) into a Dictionary; blank lines and lines starting with # or ; are skipped.",
            panel = "path, encoding, separator, trim")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Name to value, in file order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of values read.")
    public Map<String, Object> readVariables(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Text file with one name=value per line.") String path,
            @Input(name = "separator", type = OoaType.STRING, required = false, defaultValue = "\"=\"", description = "Text between the name and the value, e.g. = or : or a tab (\\t).") String separator,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding: utf-8, ansi, unicode ...") String encoding,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Remove spaces around names and values.") boolean trim) {
        Path p = existing(ctx, path);
        String sep = separator == null || separator.isEmpty() ? "=" : separator.replace("\\t", "\t");
        List<String> lines;
        try { lines = Files.readAllLines(p, charset(encoding)); } catch (IOException e) { throw io("read", p, e); }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String line : lines) {
            String l = line.startsWith("﻿") ? line.substring(1) : line;
            if (l.isBlank() || l.stripLeading().startsWith("#") || l.stripLeading().startsWith(";")) continue;
            int i = l.indexOf(sep);
            String name = i < 0 ? l : l.substring(0, i), value = i < 0 ? "" : l.substring(i + sep.length());
            values.put(trim ? name.strip() : name, trim ? value.strip() : value);
        }
        return Out.of("values", values, "count", BigDecimal.valueOf(values.size()));
    }

    @Action(id = "wait_for_file", name = "Wait for file", category = "Files", capabilities = "filesystem", timeoutSeconds = 3600,
            description = "Wait until a file appears and has stopped growing (for example a download or an export), then return its path.",
            panel = "path, timeout_seconds | stable_seconds, ignore_temporary, fail_on_timeout")
    @Output(name = "path", type = OoaType.FILE, description = "Path of the file found; empty when time ran out and fail_on_timeout is off.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the file was found in time.")
    public Map<String, Object> waitForFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to wait for; the name may use * and ? (e.g. Downloads/report*.xlsx), the newest match wins.") String path,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 0, description = "How long to wait at most.") BigDecimal timeout,
            @Input(name = "stable_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 0,
                    description = "The file's size must stay the same for this long, so a file still being written is not taken.") BigDecimal stable,
            @Input(name = "ignore_temporary", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Skip browser download placeholders (.crdownload, .part, .tmp) and Office lock files (~$...).") boolean ignoreTemp,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail with FILE.WAIT_TIMEOUT when time runs out; otherwise found is false.") boolean failOnTimeout) {
        // Windows paths may not contain * or ?, so only the folder part is resolved as a path
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        Path dir = ctx.resolvePath(cut < 0 ? "." : path.substring(0, Math.max(cut, 1)));
        String name = path.substring(cut + 1);
        String full = dir.resolve("x").getParent() + java.io.File.separator + name;
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + name);
        long end = System.nanoTime() + (long) ((timeout == null ? 60 : timeout.doubleValue()) * 1e9);
        long stableMs = (long) ((stable == null ? 1 : stable.doubleValue()) * 1000);
        Path last = null; long lastSize = -1, since = 0;
        while (true) {
            Path cand = null;
            if (dir != null && Files.isDirectory(dir)) try (Stream<Path> s = Files.list(dir)) {
                cand = s.filter(Files::isRegularFile).filter(x -> m.matches(x.getFileName()))
                        .filter(x -> !ignoreTemp || !temporary(x.getFileName().toString()))
                        .max(Comparator.comparingLong(FilePackage::modified)).orElse(null);
            } catch (IOException e) { cand = null; }
            if (cand != null) {
                long sz = size(cand);
                if (!cand.equals(last) || sz != lastSize) { last = cand; lastSize = sz; since = System.nanoTime(); }
                else if ((System.nanoTime() - since) / 1_000_000 >= stableMs) return Out.of("path", cand.toString(), "found", true);
            }
            if (System.nanoTime() >= end) {
                if (failOnTimeout) throw new ActionException("FILE.WAIT_TIMEOUT", "No finished file matching " + full + " appeared in time",
                        "Raise timeout_seconds, or check the folder and name pattern.", true);
                return Out.of("path", "", "found", false);
            }
            ctx.checkCancelled();
            try { Thread.sleep(Math.min(200, Math.max(20, stableMs / 4))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    private static boolean temporary(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".crdownload") || l.endsWith(".part") || l.endsWith(".tmp") || l.endsWith(".download") || n.startsWith("~$");
    }

    @LoopIterator(id = "files_in_folder", name = "Each file in folder", description = "Yield each file path in a folder.")
    public Iterable<Object> filesInFolder(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder path.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob pattern.") String pattern) {
        return new ArrayList<>(files(ctx.resolvePath(folder), pattern, false, Filter.NONE));
    }

    static List<String> files(Path dir, String glob, boolean subfolders, Filter filter) {
        if (!Files.isDirectory(dir)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + dir, "Check the folder path.");
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
        try (Stream<Path> s = subfolders ? Files.walk(dir) : Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> m.matches(p.getFileName())).filter(filter::test).map(Path::toString).sorted().toList();
        } catch (IOException e) { throw io("list", dir, e); }
    }

    @Action(id = "copy", name = "Copy", category = "Files", capabilities = "filesystem",
            description = "Copy a file, or every file of a folder that matches a pattern and optional size and date limits, replacing or keeping existing targets.",
            panel = "source, target, overwrite, [Copy several files by name pattern: pattern, include_subfolders], [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: modified_after, modified_before] | keep_both")
    @Output(name = "path", type = OoaType.FILE, description = "Path of the copy (the target folder when copying several files).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files copied.")
    @Output(name = "files", type = OoaType.LIST, description = "Paths of the copies.")
    public Map<String, Object> copy(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source file, or a folder when pattern is given.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Target file, or a folder when pattern is given.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing target.") boolean overwrite,
            @Input(name = "keep_both", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    visibleWhen = "overwrite=false", description = "When the target exists and overwrite is false, use a free name such as report (2).pdf instead of stopping.") boolean keepBoth,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf",
                    description = "Copy every file in the source folder whose name matches this glob pattern; the target is then a folder. A source such as in/*.pdf works too.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "With a pattern, also take matching files in sub-folders, keeping their folders under the target.") boolean sub,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at most this large.") Long max,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed after this date and time (local time).") LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed before this date and time (local time).") LocalDateTime before) {
        return transferMany(ctx, source, target, overwrite, false, keepBoth, pattern, sub, min, max, after, before);
    }

    public Map<String, Object> copy(ActionContext ctx, String source, String target, boolean overwrite, boolean keepBoth) {
        return copy(ctx, source, target, overwrite, keepBoth, null, false, null, null, null, null);
    }

    @Action(id = "move", name = "Move or rename", category = "Files", capabilities = "filesystem",
            description = "Move or rename a file, or move every file of a folder that matches a pattern and optional size and date limits, replacing or keeping existing targets.",
            panel = "source, target | overwrite, keep_both, [Move several files by name pattern: pattern, include_subfolders], [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: modified_after, modified_before]")
    @Output(name = "path", type = OoaType.FILE, description = "New path of the file (the target folder when moving several files).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files moved.")
    @Output(name = "files", type = OoaType.LIST, description = "New paths of the files.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source file, or a folder when pattern is given.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "New path or name, or a folder when pattern is given.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing target.") boolean overwrite,
            @Input(name = "keep_both", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    visibleWhen = "overwrite=false", description = "When the target exists and overwrite is false, use a free name such as report (2).pdf instead of stopping.") boolean keepBoth,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf",
                    description = "Move every file in the source folder whose name matches this glob pattern; the target is then a folder. A source such as in/*.pdf works too.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "With a pattern, also take matching files in sub-folders, keeping their folders under the target.") boolean sub,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at most this large.") Long max,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed after this date and time (local time).") LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed before this date and time (local time).") LocalDateTime before) {
        return transferMany(ctx, source, target, overwrite, true, keepBoth, pattern, sub, min, max, after, before);
    }

    public Map<String, Object> move(ActionContext ctx, String source, String target, boolean overwrite, boolean keepBoth) {
        return move(ctx, source, target, overwrite, keepBoth, null, false, null, null, null, null);
    }

    /** One file, or every file of a folder matching a glob (given as pattern, or as a wildcard in the source name) and the limits. */
    private static Map<String, Object> transferMany(ActionContext ctx, String source, String target, boolean overwrite, boolean move, boolean keepBoth,
            String pattern, boolean sub, Long min, Long max, LocalDateTime after, LocalDateTime before) {
        if (min != null && min < 0 || max != null && max < 0) throw new ActionException("FILE.BAD_OPTION", "Sizes cannot be negative", "Use 0 or more bytes.");
        if (min != null && max != null && min > max) throw new ActionException("FILE.BAD_OPTION", "min_size_bytes " + min + " is larger than max_size_bytes " + max, "Swap the two limits.");
        if (after != null && before != null && !after.isBefore(before))
            throw new ActionException("FILE.BAD_OPTION", "modified_after " + after + " is not before modified_before " + before, "Choose a date range whose start is before its end.");
        String glob = pattern == null || pattern.isBlank() ? null : pattern.strip(), folder = source;
        if (glob == null && source != null) {
            int cut = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
            String name = source.substring(cut + 1);
            if (name.contains("*") || name.contains("?")) { glob = name; folder = cut < 0 ? "." : source.substring(0, Math.max(cut, 1)); }
        }
        if (glob == null) {
            Path t = transfer(ctx, source, target, overwrite, move, keepBoth);
            return Out.of("path", t.toString(), "count", BigDecimal.ONE, "files", new ArrayList<Object>(List.of(t.toString())));
        }
        Path dir = ctx.resolvePath(folder), to = ctx.resolvePath(target);
        if (Files.isRegularFile(to)) throw new ActionException("FILE.BAD_TARGET", "The target " + to + " is a file", "With a pattern, the target must be a folder.");
        List<Object> done = new ArrayList<>();
        for (String f : files(dir, glob, sub, filter(min, max, null, null))) {
            Path from = Path.of(f);
            if (!modifiedBetween(from, after, before)) continue;
            ctx.checkCancelled();
            done.add(transfer(from, to.resolve(dir.relativize(from)), overwrite, move, keepBoth).toString());
        }
        return Out.of("path", to.toString(), "count", BigDecimal.valueOf(done.size()), "files", done);
    }

    /** First free name next to the target: report.pdf, report (2).pdf, report (3).pdf ... */
    static Path freeName(Path t) {
        if (!Files.exists(t)) return t;
        String n = t.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 2; ; i++) { Path c = t.resolveSibling(base + " (" + i + ")" + ext); if (!Files.exists(c)) return c; }
    }

    private static Path transfer(ActionContext ctx, String source, String target, boolean overwrite, boolean move, boolean keepBoth) {
        return transfer(ctx.resolvePath(source), ctx.resolvePath(target), overwrite, move, keepBoth);
    }

    private static Path transfer(Path s, Path t, boolean overwrite, boolean move, boolean keepBoth) {
        if (!Files.isRegularFile(s)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + s, "Check the source path.");
        if (Files.exists(t) && !overwrite) {
            if (!keepBoth) throw new ActionException("FILE.EXISTS", "Target exists: " + t, "Set overwrite to true or choose another name.");
            t = freeName(t);
        }
        try {
            if (t.getParent() != null) Files.createDirectories(t.getParent());
            CopyOption[] o = overwrite ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0];
            if (move) Files.move(s, t, o); else Files.copy(s, t, o);
        } catch (IOException e) { throw io(move ? "move" : "copy", s, e); }
        return t;
    }

    @Action(id = "get_info", name = "Get file info", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Return a file's name, extension, folder, size and last modified time.",
            panel = "path")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "name, extension, folder, size_bytes, modified, exists.")
    public Map<String, Object> getInfo(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File path.") String path) {
        Path p = ctx.resolvePath(path);
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        String name = p.getFileName().toString();
        info.put("name", name);
        info.put("extension", name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "");
        info.put("folder", p.getParent() == null ? "" : p.getParent().toString());
        info.put("exists", Files.isRegularFile(p));
        try {
            if (Files.isRegularFile(p)) {
                info.put("size_bytes", BigDecimal.valueOf(Files.size(p)));
                info.put("modified", LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneOffset.UTC).withNano(0));
            }
        } catch (IOException e) { throw io("inspect", p, e); }
        return Map.of("info", info);
    }

    public Map<String, Object> zip(ActionContext ctx, String source, String target, String pattern, String compression, boolean overwrite, boolean deleteOriginals) {
        return zip(ctx, source, target, pattern, compression, overwrite, deleteOriginals, null);
    }

    @Action(id = "zip", name = "Zip", category = "Archives", capabilities = "filesystem",
            description = "Compress files or a folder into a .zip archive, optionally protected with a password (AES-256, opened by 7-Zip, WinZip and most tools).",
            panel = "source, pattern, target, [Protect with a password: password], delete_originals, compression | overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Created archive.")
    public Map<String, Object> zip(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "File or folder to compress.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Archive path, e.g. out/report.zip.") String target,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf", description = "Only add files whose names match this glob pattern; all files when empty.") String pattern,
            @Input(name = "compression", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "fast", "fastest", "store"},
                    description = "normal: default size and speed; fast and fastest trade size for speed; store adds files uncompressed.") String compression,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing archive; when false an existing archive stops the step.") boolean overwrite,
            @Input(name = "delete_originals", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete the files that were added once the archive is complete.") boolean deleteOriginals,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Encrypt every file with this password (AES-256). Windows Explorer cannot open such archives; 7-Zip, WinZip and most tools can.") Object password) {
        Path s = ctx.resolvePath(source), t = ctx.resolvePath(target);
        String pw = password instanceof SecureString ss ? ss.reveal() : password == null ? null : String.valueOf(password);
        if (pw != null && pw.isEmpty()) throw new ActionException("ZIP.BAD_PASSWORD", "The password is empty", "Pick a credential with a value, or leave the password out.");
        if (!Files.exists(s)) throw new ActionException("FILE.NOT_FOUND", "Not found: " + s, "Check the source path.");
        if (!overwrite && Files.exists(t)) throw new ActionException("FILE.EXISTS", "Archive already exists: " + t, "Set overwrite to true or choose another name.");
        int level = switch (compression == null || compression.isBlank() ? "normal" : compression) {
            case "fast" -> 3; case "fastest" -> 1; case "store" -> 0; case "normal" -> java.util.zip.Deflater.DEFAULT_COMPRESSION;
            default -> throw new ActionException("ZIP.BAD_COMPRESSION", "Unknown compression '" + compression + "'", "Use normal, fast, fastest or store.");
        };
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern));
        List<Path> added = new ArrayList<>();
        try {
            if (t.getParent() != null) Files.createDirectories(t.getParent());
            Path abs = t.toAbsolutePath().normalize();
            if (pw != null) {
                Path base = Files.isDirectory(s) ? s : s.getParent();
                List<ZipCrypt.Item> items = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(s)) {
                    for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(f -> m.matches(f.getFileName())).sorted()::iterator)
                        if (!f.toAbsolutePath().normalize().equals(abs)) { items.add(new ZipCrypt.Item(base.relativize(f).toString().replace('\\', '/'), f)); added.add(f); }
                }
                ZipCrypt.writeAes(t, items, level == java.util.zip.Deflater.DEFAULT_COMPRESSION ? 6 : level, pw);
                if (deleteOriginals) for (Path f : added) Files.deleteIfExists(f);
                return Map.of("path", t.toString());
            }
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(t)); Stream<Path> walk = Files.walk(s)) {
                zos.setLevel(level);
                Path base = Files.isDirectory(s) ? s : s.getParent();
                for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(f -> m.matches(f.getFileName())).sorted()::iterator) {
                    if (f.toAbsolutePath().normalize().equals(abs)) continue;  // never add the archive to itself
                    zos.putNextEntry(new ZipEntry(base.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, zos);
                    zos.closeEntry();
                    added.add(f);
                }
            }
            if (deleteOriginals) for (Path f : added) Files.deleteIfExists(f);
        } catch (IOException e) { throw io("zip", s, e); }
        return Map.of("path", t.toString());
    }

    public Map<String, Object> unzip(ActionContext ctx, String archive, String folder, boolean overwrite) { return unzip(ctx, archive, folder, overwrite, null); }

    @Action(id = "unzip", name = "Unzip", category = "Archives", capabilities = "filesystem",
            description = "Extract a .zip archive into a folder, also a password-protected one (AES or the older ZipCrypto); entries that would escape the folder are rejected.",
            panel = "archive, folder, [The archive has a password: password], overwrite")
    @Output(name = "files", type = OoaType.LIST, description = "Extracted file paths.")
    public Map<String, Object> unzip(ActionContext ctx,
            @Input(name = "archive", type = OoaType.FILE, description = "Archive path.") String archive,
            @Input(name = "folder", type = OoaType.FILE, description = "Destination folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace files that already exist; when false they are kept and skipped.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password of a protected archive, from the vault.") Object password) {
        Path a = ctx.resolvePath(archive), dest = ctx.resolvePath(folder).toAbsolutePath().normalize();
        String pw = password instanceof SecureString ss ? ss.reveal() : password == null ? null : String.valueOf(password);
        try {
            if (pw != null || (Files.isRegularFile(a) && ZipCrypt.anyEncrypted(a))) {
                Files.createDirectories(dest);
                return Map.of("files", ZipCrypt.extract(a, dest, overwrite, pw));
            }
        } catch (NoSuchFileException e) {
            throw new ActionException("FILE.NOT_FOUND", "Archive not found: " + a, "Check the path.");
        } catch (IOException e) { throw io("unzip", a, e); }
        List<Object> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(a))) {
            Files.createDirectories(dest);
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest))  // zip-slip protection
                    throw new ActionException("ZIP.UNSAFE_ENTRY", "Archive entry '" + e.getName() + "' points outside the destination", "The archive may be malicious; do not extract it.");
                if (e.isDirectory()) { Files.createDirectories(target); continue; }
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                if (!overwrite && Files.exists(target)) continue;
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                out.add(target.toString());
            }
        } catch (NoSuchFileException e) {
            throw new ActionException("FILE.NOT_FOUND", "Archive not found: " + a, "Check the path.");
        } catch (IOException e) { throw io("unzip", a, e); }
        return Map.of("files", out);
    }

    // ---------- Path parts ----------

    @Action(id = "get_name", name = "Get file name", category = "Paths", idempotent = true,
            description = "Return the last part of a path, with and without its extension; the file does not need to exist.",
            panel = "path")
    @Output(name = "name", type = OoaType.STRING, description = "Name with extension, e.g. report.pdf.")
    @Output(name = "name_without_extension", type = OoaType.STRING, description = "Name without the last extension, e.g. report.")
    @Output(name = "extension", type = OoaType.STRING, description = "Extension without the dot, e.g. pdf; empty when there is none.")
    public Map<String, Object> getName(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File or folder path.") String path) {
        Path f = Path.of(path).getFileName();
        String name = f == null ? "" : f.toString();
        int dot = name.lastIndexOf('.');
        boolean hasExt = dot > 0;
        return Out.of("name", name, "name_without_extension", hasExt ? name.substring(0, dot) : name, "extension", hasExt ? name.substring(dot + 1) : "");
    }

    @Action(id = "get_parent", name = "Get parent folder", category = "Paths", idempotent = true,
            description = "Return the folder that contains a file or folder, as a full path.",
            panel = "path")
    @Output(name = "folder", type = OoaType.FILE, description = "Parent folder, or empty for a drive or root.")
    public Map<String, Object> getParent(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File or folder path.") String path) {
        Path parent = ctx.resolvePath(path).toAbsolutePath().normalize().getParent();
        return Out.of("folder", parent == null ? "" : parent.toString());
    }

    // ---------- Links and shortcuts (shared with the Folder package) ----------

    /** PowerShell that creates a Windows .lnk with WScript.Shell; every value arrives through an OOA_LNK_* environment variable. */
    static final String LNK_SCRIPT = "$ErrorActionPreference='Stop'; $w = New-Object -ComObject WScript.Shell; $s = $w.CreateShortcut($env:OOA_LNK_PATH); "
            + "$s.TargetPath = $env:OOA_LNK_TARGET; if ($env:OOA_LNK_ARGS) { $s.Arguments = $env:OOA_LNK_ARGS }; "
            + "if ($env:OOA_LNK_WORKDIR) { $s.WorkingDirectory = $env:OOA_LNK_WORKDIR }; if ($env:OOA_LNK_DESC) { $s.Description = $env:OOA_LNK_DESC }; $s.Save()";

    static Map<String, String> lnkEnv(Path link, Path target, String args, String workDir, String desc) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OOA_LNK_PATH", link.toString());
        env.put("OOA_LNK_TARGET", target.toString());
        env.put("OOA_LNK_ARGS", args == null ? "" : args);
        env.put("OOA_LNK_WORKDIR", workDir == null ? "" : workDir);
        env.put("OOA_LNK_DESC", desc == null ? "" : desc);
        return env;
    }

    static String desktopEntry(Path target, String args, String desc, boolean folder) {
        String name = target.getFileName() == null ? target.toString() : target.getFileName().toString();
        String nl = "\n";
        return "[Desktop Entry]" + nl + "Version=1.0" + nl + "Name=" + name.replaceAll("[\\r\\n]", " ") + nl
                + (desc == null || desc.isBlank() ? "" : "Comment=" + desc.replaceAll("[\\r\\n]", " ") + nl)
                + (folder ? "Type=Link" + nl + "URL=file://" + target.toUri().getRawPath() + nl
                          : "Type=Application" + nl + "Exec=\"" + target.toString().replace("\"", "\\\"") + "\"" + (args == null || args.isBlank() ? "" : " " + args.replaceAll("[\\r\\n]", " ")) + nl);
    }

    /** Creates a shortcut: .lnk on Windows, a .desktop file when the name ends in .desktop, otherwise a symbolic link. */
    static Path shortcut(ActionContext ctx, String areaCode, String target, String shortcut, String args, String workDir, String desc, boolean overwrite, boolean folder) {
        Path t = ctx.resolvePath(target).toAbsolutePath().normalize();
        if (folder ? !Files.isDirectory(t) : !Files.isRegularFile(t))
            throw new ActionException(areaCode + ".NOT_FOUND", (folder ? "Folder" : "File") + " not found: " + t, "Check the path of the item the shortcut should open.");
        Path link = ctx.resolvePath(shortcut).toAbsolutePath().normalize();
        String lower = link.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ProcessRunner.WINDOWS && !lower.endsWith(".lnk")) link = link.resolveSibling(link.getFileName() + ".lnk");
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            if (!overwrite) throw new ActionException(areaCode + ".EXISTS", "Shortcut already exists: " + link, "Set overwrite to true or choose another name.");
            try { Files.delete(link); } catch (IOException e) { throw io("replace", link, e); }
        }
        try { if (link.getParent() != null) Files.createDirectories(link.getParent()); } catch (IOException e) { throw io("create folder for", link, e); }
        if (ProcessRunner.WINDOWS) {
            ProcessRunner.Result r = ProcessRunner.run(ctx, ScriptPackage.powershellCommand(LNK_SCRIPT), null, null, 60, lnkEnv(link, t, args, workDir, desc), java.nio.charset.StandardCharsets.UTF_8);
            if (r.exitCode() != 0 || !Files.exists(link))
                throw new ActionException(areaCode + ".SHORTCUT_FAILED", "Cannot create shortcut " + link + ": " + ApplicationPackage.tail(r.stderr() + r.stdout()), "Check the folder is writable.");
        } else if (lower.endsWith(".desktop")) {
            try { Files.writeString(link, desktopEntry(t, args, desc, folder)); } catch (IOException e) { throw io("write", link, e); }
        } else {
            symlink(areaCode, link, t);
        }
        return link;
    }

    static void symlink(String areaCode, Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException e) {
            throw new ActionException(areaCode + ".LINK_NOT_SUPPORTED", "Symbolic links are not supported here: " + link, "Use a shortcut instead.");
        } catch (FileSystemException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("privilege") || m.contains("1314") || e instanceof AccessDeniedException)
                throw new ActionException(areaCode + ".LINK_NOT_PERMITTED", "Not allowed to create a symbolic link at " + link,
                        "On Windows, turn on Developer Mode or run the agent with administrator rights; or use Create shortcut.");
            throw io("link", link, e);
        } catch (IOException e) { throw io("link", link, e); }
    }

    static Path symbolicLink(ActionContext ctx, String areaCode, String target, String link, boolean overwrite, boolean folder) {
        Path t = ctx.resolvePath(target).toAbsolutePath().normalize();
        if (folder ? !Files.isDirectory(t) : !Files.isRegularFile(t))
            throw new ActionException(areaCode + ".NOT_FOUND", (folder ? "Folder" : "File") + " not found: " + t, "Check the path the link should point to.");
        Path l = ctx.resolvePath(link).toAbsolutePath().normalize();
        if (Files.exists(l, LinkOption.NOFOLLOW_LINKS)) {
            if (!overwrite) throw new ActionException(areaCode + ".EXISTS", "Link path already exists: " + l, "Set overwrite to true or choose another name.");
            if (Files.isDirectory(l, LinkOption.NOFOLLOW_LINKS))
                throw new ActionException(areaCode + ".EXISTS", "A real folder exists at " + l + "; it will not be replaced", "Choose another name for the link.");
            try { Files.delete(l); } catch (IOException e) { throw io("replace", l, e); }
        }
        try { if (l.getParent() != null) Files.createDirectories(l.getParent()); } catch (IOException e) { throw io("create folder for", l, e); }
        symlink(areaCode, l, t);
        return l;
    }

    @Action(id = "create_shortcut", name = "Create shortcut", category = "Files", capabilities = "filesystem",
            description = "Create a shortcut that opens a file: a .lnk file on Windows, a .desktop file or symbolic link elsewhere.",
            panel = "path, shortcut | arguments, working_folder, comment, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Created shortcut.")
    public Map<String, Object> createShortcut(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File the shortcut opens.") String path,
            @Input(name = "shortcut", type = OoaType.FILE, example = "C:/Users/Public/Desktop/Report.lnk", description = "Shortcut to create; .lnk is added on Windows when missing.") String shortcut,
            @Input(name = "arguments", type = OoaType.STRING, required = false, description = "Command-line arguments passed when the shortcut is opened.") String args,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder the program starts in.") String workDir,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Tooltip text shown for the shortcut.") String comment,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing shortcut.") boolean overwrite) {
        String wd = workDir == null || workDir.isBlank() ? null : ctx.resolvePath(workDir).toString();
        return Map.of("path", shortcut(ctx, "FILE", path, shortcut, args, wd, comment, overwrite, false).toString());
    }

    @Action(id = "create_symbolic_link", name = "Create symbolic link", category = "Files", capabilities = "filesystem",
            description = "Create a symbolic link that points to a file; on Windows this needs Developer Mode or administrator rights.",
            panel = "path, link | overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Created link.")
    public Map<String, Object> createSymbolicLink(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File the link points to.") String path,
            @Input(name = "link", type = OoaType.FILE, description = "Link to create.") String link,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing file link (never a real folder).") boolean overwrite) {
        return Map.of("path", symbolicLink(ctx, "FILE", path, link, overwrite, false).toString());
    }

    @Action(id = "print_files", name = "Print files", category = "Files", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Print several files, in order, on a named printer or with each file's default application.",
            panel = "(Print: Listed files = files / Files in a folder = folder, pattern, include_subfolders, modified_within_days), printer, copies")
    @Output(name = "printed", type = OoaType.NUMBER, description = "Number of files sent to the printer.")
    public Map<String, Object> printFiles(ActionContext ctx,
            @Input(name = "files", type = OoaType.LIST, required = false, visibleWhen = "folder=", description = "File paths to print (or use folder).") List<Object> files,
            @Input(name = "printer", type = OoaType.STRING, required = false, description = "Printer name; the default printer when empty.") String printer,
            @Input(name = "copies", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 999, description = "Copies of each file.") int copies,
            @Input(name = "folder", type = OoaType.FILE, required = false, visibleWhen = "files=", description = "Print the files in this folder that match pattern, after any files in the list.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", example = "*.pdf", visibleWhen = "files=", description = "With folder, glob pattern of the files to print.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "files=", description = "With folder, also print matching files in sub-folders.") boolean sub,
            @Input(name = "modified_within_days", type = OoaType.NUMBER, required = false, visibleWhen = "files=", min = 0, description = "With folder, only files changed in the last this many days.") Integer within) {
        List<Object> all = new ArrayList<>(files == null ? List.of() : files);
        if (folder != null && !folder.isBlank()) all.addAll(files(ctx.resolvePath(folder), pattern, sub, filter(null, null, within, null)));
        if (all.isEmpty()) throw new ActionException("FILE.NO_FILES", "Give at least one file to print", "Fill in the files list, or a folder with matching files.");
        List<String> paths = new ArrayList<>();
        for (Object o : all) {
            Path p = ctx.resolvePath(com.oneofficeautomation.runner.Values.text(o));
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Nothing was printed; fix the path and run again.");
            paths.add(p.toString());
        }
        PrinterPackage pp = new PrinterPackage();
        int n = 0;
        for (String p : paths) { ctx.checkCancelled(); pp.print(ctx, p, printer, Math.max(1, copies)); n++; }
        return Map.of("printed", BigDecimal.valueOf(n));
    }
}
