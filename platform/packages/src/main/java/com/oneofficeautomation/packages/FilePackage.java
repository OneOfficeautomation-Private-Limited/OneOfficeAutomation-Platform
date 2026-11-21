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
        // F3: the picker's UTF-8 with BOM reads as UTF-8 here (writing the mark is up to the toolkits that write files)
        if (e.equals("utf-8-bom") || e.equals("utf-8 bom") || e.equals("utf8bom")) return java.nio.charset.StandardCharsets.UTF_8;
        try { return Charset.forName(e); }
        catch (IllegalArgumentException x) { throw new ActionException("FILE.BAD_ENCODING", "Unknown encoding '" + enc + "'", "Use utf-8, utf-16le, windows-1252 or ansi."); }
    }

    static boolean auto(String enc) { return enc != null && enc.strip().equalsIgnoreCase("auto"); }

    /** Text of a file; encoding "auto" reads a byte order mark (UTF-8, UTF-16LE/BE), else UTF-8 when valid, else windows-1252. */
    static String readText(Path p, String enc) throws IOException {
        if (!auto(enc)) return Files.readString(p, charset(enc));
        return decodeAuto(Files.readAllBytes(p));
    }

    static String decodeAuto(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF)
            return new String(b, 3, b.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE)
            return new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF)
            return new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    /** Trims spaces: none, leading, trailing or both. */
    static String trim(String s, String side) {
        String t = side == null || side.isBlank() ? "none" : side.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "none" -> s;
            case "leading" -> s.stripLeading();
            case "trailing" -> s.stripTrailing();
            case "both" -> s.strip();
            default -> throw new ActionException("FILE.BAD_OPTION", "Unknown trim '" + side + "'", "Use none, leading, trailing or both.");
        };
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

    /**
     * The deepest folder holding every file of a name-rule selection (their own folder when they sit side by side), so
     * copies keep the sub-folders below it.
     */
    static Path commonFolder(List<Path> files) {
        Path base = null;
        for (Path f : files) {
            Path dir = f.toAbsolutePath().normalize().getParent();
            if (dir == null) continue;
            if (base == null) { base = dir; continue; }
            while (base != null && !dir.startsWith(base)) base = base.getParent();
        }
        return base == null ? Path.of(".").toAbsolutePath().normalize() : base;
    }

    /** At most this many files are handled at the same time when "several files at once" is on. */
    static final int PARALLEL_FILES = 4;

    /** Runs the work for every item, in order or up to four at a time; the results keep the items' order. */
    static <T> List<Object> forEach(ActionContext ctx, List<T> items, boolean parallel, java.util.function.Function<T, Object> work) {
        List<Object> out = new ArrayList<>();
        if (!parallel || items.size() < 2) {
            for (T it : items) { ctx.checkCancelled(); Object r = work.apply(it); if (r != null) out.add(r); }
            return out;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(PARALLEL_FILES, items.size()), r -> {
            Thread t = new Thread(r, "ooa-files"); t.setDaemon(true); return t; });
        try {
            List<java.util.concurrent.Future<Object>> futures = new ArrayList<>();
            for (T it : items) futures.add(pool.submit(() -> { ctx.checkCancelled(); return work.apply(it); }));
            for (java.util.concurrent.Future<Object> f : futures) {
                try { Object r = f.get(); if (r != null) out.add(r); }
                catch (java.util.concurrent.ExecutionException e) {
                    for (java.util.concurrent.Future<Object> g : futures) g.cancel(true);
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new ActionException("FILE.IO_ERROR", String.valueOf(e.getCause()), "Check the paths and permissions.", true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ActionException("RUN.CANCELLED", "Interrupted", "The run was stopped before this step finished. Run the agent again when it should go on.");
                }
            }
            return out;
        } finally { pool.shutdownNow(); }
    }

    /** True when the file was last changed after 'after' and before 'before' (UTC, like every DateTime and File info's modified); an empty limit always passes. */
    static boolean modifiedBetween(Path p, LocalDateTime after, LocalDateTime before) {
        if (after == null && before == null) return true;
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(modified(p)), ZoneOffset.UTC);
        return (after == null || t.isAfter(after)) && (before == null || t.isBefore(before));
    }

    @Action(id = "read_text", name = "Read text", category = "Files", idempotent = true, capabilities = "filesystem", description = "Read a text file.",
            panel = "path, encoding | trim")
    @Output(name = "text", type = OoaType.STRING, description = "File content.")
    public Map<String, Object> readText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", newStepDefault = "\"auto\"", example = "auto",
                    description = "Text encoding, e.g. utf-8, utf-16le, windows-1252; auto reads a byte order mark, else takes UTF-8 when it fits, else windows-1252.") String enc,
            @Input(name = "trim", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "leading", "trailing", "both"},
                    description = "Remove spaces and line breaks at the start, the end or both ends of the text.") String trim) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path and permissions.");
        String text;
        try { text = readText(p, enc); } catch (IOException e) { throw io("read", p, e); }
        return Map.of("text", trim(text, trim));
    }

    @Action(id = "write_text", name = "Write text", category = "Files", capabilities = "filesystem", description = "Write text to a file, overwriting or appending.",
            panel = "path, text, append | fail_if_exists, encoding")
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

    @Action(id = "create", name = "Create file", category = "Files", capabilities = "filesystem",
            description = "Create an empty file (or one holding some text), choosing what happens when a file of that name is already there.",
            panel = "new_file, if_exists | text, encoding")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the file (the numbered name with keep both).")
    @Output(name = "created", type = OoaType.BOOLEAN, description = "True when a file was written; false when an existing file was kept.")
    public Map<String, Object> create(ActionContext ctx,
            @Input(name = "new_file", type = OoaType.FILE, label = "File", description = "File to create; its folder is created when missing.") String newFile,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"fail\"", enumValues = {"fail", "replace", "keep_both", "keep"},
                    description = "When the file is already there: stop with FILE.EXISTS, replace it, create a numbered name such as notes (2).txt, or leave it as it is.") String ifExists,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Text to put in the new file; empty for an empty file.") String text,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", visibleWhen = "text!=", description = "Text encoding, e.g. utf-8, utf-16le, windows-1252.") String enc) {
        Path p = ctx.resolvePath(newFile);
        String mode = ifExists == null || ifExists.isBlank() ? "fail" : ifExists.strip();
        if (!List.of("fail", "replace", "keep_both", "keep").contains(mode))
            throw new ActionException("FILE.BAD_OPTION", "Unknown if_exists '" + ifExists + "'", "Use fail, replace, keep_both or keep.");
        if (Files.isDirectory(p)) throw new ActionException("FILE.BAD_TARGET", p + " is a folder", "Give the path of a file, including its name.");
        if (Files.exists(p)) switch (mode) {
            case "fail" -> throw new ActionException("FILE.EXISTS", "File already exists: " + p, "Choose another name, or choose what to do when it exists.");
            case "keep" -> { return Out.of("path", p.toString(), "created", false); }
            case "keep_both" -> p = freeName(p);
            default -> { /* replace */ }
        }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, text == null ? "" : text, charset(enc));
        } catch (IOException e) { throw io("create", p, e); }
        return Out.of("path", p.toString(), "created", true);
    }

    @Action(id = "rename", name = "Rename file", category = "Files", capabilities = "filesystem",
            description = "Give a file a new name in the same folder.",
            panel = "path, new_name | overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "New full path of the file.")
    public Map<String, Object> rename(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to rename.") String path,
            @Input(name = "new_name", type = OoaType.STRING, example = "invoice-2026-11.pdf", description = "New name only, with its extension and without any folder.") String newName,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace a file that already has the new name.") boolean overwrite) {
        Path p = existing(ctx, path);
        String n = newName == null ? "" : newName.strip();
        if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..") || n.matches(".*[\\x00-\\x1f<>:\"|?*].*"))
            throw new ActionException("FILE.BAD_NAME", "Invalid file name '" + newName + "'", "Give only the new name, without a folder or the characters < > : \" | ? *.");
        Path t = p.resolveSibling(n);
        if (t.equals(p)) return Out.of("path", p.toString());
        // a change of case only (report.pdf to Report.pdf) is the same file on Windows: renamed, not "already exists"
        boolean sameFile = Files.exists(t) && t.getFileName().toString().equalsIgnoreCase(p.getFileName().toString());
        if (Files.exists(t) && !sameFile && !overwrite) throw new ActionException("FILE.EXISTS", "A file named '" + n + "' already exists in " + p.getParent(), "Choose another name or turn on overwrite.");
        try { Files.move(p, t, overwrite && !sameFile ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0]); }
        catch (IOException e) { throw io("rename", p, e); }
        return Out.of("path", t.toString());
    }

    @Action(id = "delete", name = "Delete", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Delete a file if it exists, every file the name rules choose, or every file in a folder that matches a pattern and optional size and age limits.",
            panel = "path, [Delete several files by name pattern: pattern, include_subfolders], [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: older_than_days, modified_after, modified_before] | parallel")
    @Output(name = "deleted", type = OoaType.NUMBER, description = "Number of files deleted.")
    @Output(name = "files", type = OoaType.LIST, description = "Paths of the files deleted.")
    public Map<String, Object> delete(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File path (or name rules choosing several files), or a folder when pattern is given.") String path,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.tmp", description = "Delete the files in the folder at path that match this glob pattern.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "With pattern, also delete matching files in sub-folders.") boolean sub,
            @Input(name = "older_than_days", type = OoaType.NUMBER, required = false, min = 0, description = "With pattern, only files last changed more than this many days ago.") Integer older,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With pattern, only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With pattern, only files at most this large.") Long max,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, visibleWhen = "pattern!=", description = "With pattern, only files last changed after this date and time (UTC, like every DateTime; Text to date converts local times).") LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, visibleWhen = "pattern!=", description = "With pattern, only files last changed before this date and time (UTC, like every DateTime; Text to date converts local times).") LocalDateTime before,
            @Input(name = "parallel", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Several files at once",
                    description = "Delete up to four files at the same time; faster for many files on a network share.") boolean parallel) {
        List<Path> chosen = ctx.selectedFiles("path");
        List<Path> targets;
        if (chosen != null) targets = chosen;
        else if (pattern == null || pattern.isBlank()) {
            Path p = ctx.resolvePath(path);
            try { boolean gone = Files.deleteIfExists(p); return Out.of("deleted", BigDecimal.valueOf(gone ? 1 : 0), "files", new ArrayList<Object>(gone ? List.of(p.toString()) : List.of())); }
            catch (IOException e) { throw io("delete", p, e); }
        } else {
            targets = new ArrayList<>();
            for (String f : files(ctx.resolvePath(path), pattern, sub, filter(min, max, null, older))) if (modifiedBetween(Path.of(f), after, before)) targets.add(Path.of(f));
        }
        List<Object> gone = forEach(ctx, targets, parallel, f -> {
            try { return Files.deleteIfExists(f) ? f.toString() : null; } catch (IOException e) { throw io("delete", f, e); }
        });
        return Out.of("deleted", BigDecimal.valueOf(gone.size()), "files", gone);
    }

    public Map<String, Object> delete(ActionContext ctx, String path, String pattern, boolean sub, Integer older, Long min, Long max) {
        return delete(ctx, path, pattern, sub, older, min, max, null, null, false);
    }

    /** Delete with the signature before parallel was added. */
    public Map<String, Object> delete(ActionContext ctx, String path, String pattern, boolean sub, Integer older, Long min, Long max, LocalDateTime after, LocalDateTime before) {
        return delete(ctx, path, pattern, sub, older, min, max, after, before, false);
    }

    @Action(id = "list", name = "List files", category = "Folders", idempotent = true, capabilities = "filesystem",
            description = "List the files in a folder that match a pattern, or the files the name rules choose.",
            panel = "folder, pattern, include_subfolders, [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: modified_within_days, older_than_days] | sort_by, descending")
    @Output(name = "files", type = OoaType.LIST, description = "File paths in the chosen order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files found.")
    @Output(name = "first", type = OoaType.FILE, description = "First file of the list (e.g. the newest with sort_by modified, descending); empty when none.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder path, or name rules (a folder and rules on name, type, size and date) choosing the files.") String folder,
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
        Filter limits = filter(min, max, within, older);
        List<Path> chosen = ctx.selectedFiles("folder");
        List<Object> list = chosen != null ? new ArrayList<>(chosen.stream().filter(limits::test).map(Path::toString).toList())
                : new ArrayList<>(files(ctx.resolvePath(folder), pattern, sub, limits));
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

    /** Milliseconds in one unit of an age: days (default), hours, minutes or seconds. */
    static long unitMillis(String unit) {
        return switch (unit == null || unit.isBlank() ? "days" : unit.strip().toLowerCase(Locale.ROOT)) {
            case "days" -> 86_400_000L; case "hours" -> 3_600_000L; case "minutes" -> 60_000L; case "seconds" -> 1000L;
            default -> throw new ActionException("FILE.BAD_OPTION", "Unknown unit '" + unit + "'", "Use days, hours, minutes or seconds.");
        };
    }

    @Condition(id = "date_is", name = "File date is", description = "Compare when a file was last changed or created with a date or a range, or check its age in days, hours, minutes or seconds.")
    public boolean dateIs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "which", type = OoaType.STRING, required = false, defaultValue = "\"modified\"", enumValues = {"modified", "created"}, description = "Which date of the file.") String which,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"before\"", enumValues = {"before", "after", "on", "between", "older_than_days", "newer_than_days"},
                    description = "Before, after or on a date (calendar day, UTC), between two dates, or older/newer than an age.") String op,
            @Input(name = "date", type = OoaType.DATETIME, required = false, visibleWhen = "operator=before|after|on|between", description = "Date to compare with (UTC); the start of the range for between.") java.time.LocalDateTime date,
            @Input(name = "days", type = OoaType.NUMBER, required = false, label = "Age", visibleWhen = "operator=older_than_days|newer_than_days", min = 0, description = "Age in the chosen unit (days when no unit is chosen).") BigDecimal days,
            @Input(name = "until", type = OoaType.DATETIME, required = false, visibleWhen = "operator=between", description = "End of the range for between (UTC).") java.time.LocalDateTime until,
            @Input(name = "inclusive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "operator=before|after|between",
                    description = "Count a file dated exactly on the date (or on either end of the range) as matching.") boolean inclusive,
            @Input(name = "unit", type = OoaType.STRING, required = false, defaultValue = "\"days\"", enumValues = {"days", "hours", "minutes", "seconds"},
                    visibleWhen = "operator=older_than_days|newer_than_days", description = "Unit of the age.") String unit) {
        Path p = existing(ctx, path);
        long ms;
        try {
            ms = "created".equals(which) ? ((java.nio.file.attribute.FileTime) Files.getAttribute(p, "creationTime")).toMillis() : Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) { throw io("read the date of", p, e); }
        LocalDateTime fileDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
        String o = op == null ? "before" : op;
        if (o.endsWith("_days")) {
            if (days == null) throw new ActionException("FILE.BAD_OPTION", "Give the age", "Fill in the age.");
            long ageMs = System.currentTimeMillis() - ms, limit = days.multiply(BigDecimal.valueOf(unitMillis(unit))).longValue();
            return o.startsWith("older") ? ageMs > limit : ageMs < limit;
        }
        if (date == null) throw new ActionException("FILE.BAD_OPTION", "Give the date to compare with", "Fill in 'date'.");
        return switch (o) {
            case "after" -> fileDate.isAfter(date) || inclusive && fileDate.equals(date);
            case "on" -> fileDate.toLocalDate().equals(date.toLocalDate());
            case "between" -> {
                if (until == null) throw new ActionException("FILE.BAD_OPTION", "Give the end of the range", "Fill in 'until'.");
                if (until.isBefore(date)) throw new ActionException("FILE.BAD_OPTION", "The range ends (" + until + ") before it starts (" + date + ")", "Swap the two dates.");
                yield inclusive ? !fileDate.isBefore(date) && !fileDate.isAfter(until) : fileDate.isAfter(date) && fileDate.isBefore(until);
            }
            default -> fileDate.isBefore(date) || inclusive && fileDate.equals(date);
        };
    }

    /** File date is with the signature before between, inclusive and unit were added. */
    public boolean dateIs(ActionContext ctx, String path, String which, String op, LocalDateTime date, BigDecimal days) {
        return dateIs(ctx, path, which, op, date, days, null, false, "days");
    }

    /** Extensions as text (pdf, xlsx) or as a List variable (which arrives as JSON text such as ["pdf","xlsx"]). */
    static List<String> extensionList(String extensions) {
        String t = extensions == null ? "" : extensions.strip();
        if (t.startsWith("[")) {
            try {
                if (JsonLite.parse(t) instanceof List<?> l) { List<String> out = new ArrayList<>(); for (Object o : l) if (o != null) out.add(String.valueOf(o)); return out; }
            } catch (IllegalArgumentException ignored) { /* not a list: read as text */ }
        }
        return List.of(t.split("[,; ]+"));
    }

    @Condition(id = "has_extension", name = "File has extension", description = "True when the file name ends with one of the extensions, e.g. pdf, xlsx (case is ignored).")
    public boolean hasExtension(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path or name.") String path,
            @Input(name = "extensions", type = OoaType.STRING, description = "One or more extensions separated by commas, with or without the dot, or a List variable of them.", example = "pdf, xlsx") String extensions) {
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : extensionList(extensions)) {
            String x = e.strip().toLowerCase(Locale.ROOT);
            if (x.isEmpty()) continue;
            if (name.endsWith(x.startsWith(".") ? x : "." + x)) return true;
        }
        return false;
    }

    @Action(id = "read_variables", name = "Read variables from file", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Read name=value lines (a settings or .properties-style file) into a Dictionary; blank lines and lines starting with # or ; are skipped.",
            panel = "path, encoding, separator, trim, trim_side, lowercase_keys")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Name to value, in file order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of values read.")
    public Map<String, Object> readVariables(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Text file with one name=value per line.") String path,
            @Input(name = "separator", type = OoaType.STRING, required = false, defaultValue = "\"=\"", description = "Text between the name and the value, e.g. = or : or a tab (\\t).") String separator,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding: utf-8, ansi, unicode ...") String encoding,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Remove spaces around names and values.") boolean trim,
            @Input(name = "trim_side", type = OoaType.STRING, required = false, defaultValue = "\"both\"", enumValues = {"both", "leading", "trailing"}, visibleWhen = "trim=true",
                    description = "Which spaces trim removes: on both sides, only before, or only after the name and the value.") String trimSide,
            @Input(name = "lowercase_keys", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Turn names into lower case, so Server and server are the same variable.") boolean lowercaseKeys) {
        Path p = existing(ctx, path);
        String sep = separator == null || separator.isEmpty() ? "=" : separator.replace("\\t", "\t");
        String side = !trim ? "none" : trimSide == null || trimSide.isBlank() ? "both" : trimSide;
        trim("", side);  // refuses an unknown trim_side before reading
        List<String> lines;
        try { lines = auto(encoding) ? readText(p, encoding).lines().toList() : Files.readAllLines(p, charset(encoding)); } catch (IOException e) { throw io("read", p, e); }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String line : lines) {
            String l = line.startsWith("﻿") ? line.substring(1) : line;
            if (l.isBlank() || l.stripLeading().startsWith("#") || l.stripLeading().startsWith(";")) continue;
            int i = l.indexOf(sep);
            String name = i < 0 ? l : l.substring(0, i), value = i < 0 ? "" : l.substring(i + sep.length());
            String key = trim(name, side);
            values.put(lowercaseKeys ? key.toLowerCase(Locale.ROOT) : key, trim(value, side));
        }
        return Out.of("values", values, "count", BigDecimal.valueOf(values.size()));
    }

    /** Read variables with the signature before trim_side and lowercase_keys were added. */
    public Map<String, Object> readVariables(ActionContext ctx, String path, String separator, String encoding, boolean trim) {
        return readVariables(ctx, path, separator, encoding, trim, "both", false);
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
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted", "The run was stopped before this step finished. Run the agent again when it should go on."); }
        }
    }

    private static boolean temporary(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".crdownload") || l.endsWith(".part") || l.endsWith(".tmp") || l.endsWith(".download") || n.startsWith("~$");
    }

    @LoopIterator(id = "files_in_folder", name = "Each file in folder",
            description = "Yield each file in a folder (or each file the name rules choose): its path, or with details its name, extension, folder, size and date.")
    public Iterable<Object> filesInFolder(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder path, or name rules choosing the files.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob pattern.") String pattern,
            @Input(name = "details", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Each pass gives the file's details",
                    description = "Give each file as a Dictionary with path, name, name_without_extension, extension, folder, size_bytes and modified, instead of its path alone.") boolean details) {
        List<Path> chosen = ctx.selectedFiles("folder");
        List<String> paths = chosen != null ? chosen.stream().map(Path::toString).toList() : files(ctx.resolvePath(folder), pattern, false, Filter.NONE);
        List<Object> out = new ArrayList<>();
        for (String p : paths) out.add(details ? fileDetails(Path.of(p)) : p);
        return out;
    }

    public Iterable<Object> filesInFolder(ActionContext ctx, String folder, String pattern) { return filesInFolder(ctx, folder, pattern, false); }

    /** One file of Each file in folder with details: path, name, name_without_extension, extension, folder, size_bytes, modified (UTC). */
    static Map<String, Object> fileDetails(Path p) {
        Map<String, Object> d = new LinkedHashMap<>();
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        d.put("path", p.toString());
        d.put("name", name);
        d.put("name_without_extension", dot > 0 ? name.substring(0, dot) : name);
        d.put("extension", dot > 0 ? name.substring(dot + 1) : "");
        d.put("folder", p.getParent() == null ? "" : p.getParent().toString());
        d.put("size_bytes", BigDecimal.valueOf(size(p)));
        d.put("modified", LocalDateTime.ofInstant(Instant.ofEpochMilli(modified(p)), ZoneOffset.UTC).withNano(0));
        return d;
    }

    static List<String> files(Path dir, String glob, boolean subfolders, Filter filter) {
        if (!Files.isDirectory(dir)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + dir, "Check the folder path.");
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
        try (Stream<Path> s = subfolders ? Files.walk(dir) : Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> m.matches(p.getFileName())).filter(filter::test).map(Path::toString).sorted().toList();
        } catch (IOException e) { throw io("list", dir, e); }
    }

    /**
     * G2 (AA parity): AA's "Copy Control Room file" with the repository path known only while the agent runs (from a
     * variable). One CommandCenter checks the run's user may view the file's folder and records it in the Audit Trail.
     */
    @Action(id = "get_from_command_center", name = "Get file from One CommandCenter", category = "Files", timeoutSeconds = 120, capabilities = {"filesystem", "network"},
            description = "Copy a file kept in One CommandCenter (its Agents page, Files) to the runner, by its path there; the path may come from a variable.",
            panel = "repository_path, save_to | overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Where the file was saved on the runner.")
    @Output(name = "size", type = OoaType.NUMBER, description = "Size in bytes.")
    public Map<String, Object> getFromCommandCenter(ActionContext ctx,
            @Input(name = "repository_path", type = OoaType.STRING, example = "Templates/invoice.xlsx", description = "The file's path in One CommandCenter, e.g. Templates/invoice.xlsx or $template$.") String repoPath,
            @Input(name = "save_to", type = OoaType.FILE, required = false,
                    description = "File or folder on the runner; the run's temporary folder when empty (the path is returned).") String saveTo,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", newStepDefault = "false",
                    description = "Replace a file already there; when off, an existing file stops the step.") boolean overwrite) {
        if (repoPath == null || repoPath.isBlank()) throw new ActionException("FILE.NO_PATH", "Give the file's path in One CommandCenter", "For example Templates/invoice.xlsx.");
        @SuppressWarnings("unchecked")
        java.util.function.Function<Map<String, Object>, Map<String, Object>> cc;
        try { cc = ctx.service("command_center", java.util.function.Function.class); }
        catch (OoaException e) {
            throw new ActionException("FILE.NO_COMMAND_CENTER", "Getting a file from One CommandCenter works when One CommandCenter runs the agent",
                    "Run the agent from One CommandCenter, or add the file to the agent's files (Details > Files from One CommandCenter).");
        }
        String name = repoPath.strip().replace('\\', '/');
        Path target = ctx.outputPath(saveTo, name.substring(name.lastIndexOf('/') + 1));
        if (Files.exists(target) && !overwrite)
            throw new ActionException("FILE.EXISTS", "The file " + target + " is already there", "Turn on overwrite, or save it somewhere else.");
        Map<String, Object> r = cc.apply(Map.of("call", "repo_file", "args", Map.of("path", name)));
        byte[] b = java.util.Base64.getDecoder().decode(String.valueOf(r.get("content")));
        try { Files.write(target, b); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + target + ": " + e.getMessage(), "Check that the folder exists and may be written."); }
        return Out.of("path", target.toString(), "size", java.math.BigDecimal.valueOf(b.length));
    }

    @Action(id = "copy", name = "Copy", category = "Files", capabilities = "filesystem",
            description = "Copy a file, every file the name rules choose, or every file of a folder that matches a pattern and optional size and date limits, replacing or keeping existing targets.",
            panel = "source, target, overwrite, [Copy several files by name pattern: pattern, include_subfolders], [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: modified_after, modified_before] | keep_both, parallel")
    @Output(name = "path", type = OoaType.FILE, description = "Path of the copy (the target folder when copying several files).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files copied.")
    @Output(name = "files", type = OoaType.LIST, description = "Paths of the copies.")
    public Map<String, Object> copy(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source file, name rules choosing several files, or a folder when pattern is given.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Target file, or a folder when several files are copied.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing target.") boolean overwrite,
            @Input(name = "keep_both", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    visibleWhen = "overwrite=false", description = "When the target exists and overwrite is false, use a free name such as report (2).pdf instead of stopping.") boolean keepBoth,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf",
                    description = "Copy every file in the source folder whose name matches this glob pattern; the target is then a folder. A source such as in/*.pdf works too.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "With a pattern, also take matching files in sub-folders, keeping their folders under the target.") boolean sub,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at most this large.") Long max,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed after this date and time (UTC, like every DateTime; Text to date converts local times).") LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed before this date and time (UTC, like every DateTime; Text to date converts local times).") LocalDateTime before,
            @Input(name = "parallel", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Several files at once",
                    description = "Copy up to four files at the same time; faster for many files on a network share.") boolean parallel) {
        return transferMany(ctx, source, target, overwrite, false, keepBoth, pattern, sub, min, max, after, before, parallel);
    }

    public Map<String, Object> copy(ActionContext ctx, String source, String target, boolean overwrite, boolean keepBoth) {
        return copy(ctx, source, target, overwrite, keepBoth, null, false, null, null, null, null, false);
    }

    /** Copy with the signature before parallel was added. */
    public Map<String, Object> copy(ActionContext ctx, String source, String target, boolean overwrite, boolean keepBoth, String pattern, boolean sub,
            Long min, Long max, LocalDateTime after, LocalDateTime before) {
        return copy(ctx, source, target, overwrite, keepBoth, pattern, sub, min, max, after, before, false);
    }

    @Action(id = "move", name = "Move or rename", category = "Files", capabilities = "filesystem",
            description = "Move or rename a file, or move every file the name rules choose or every file of a folder that matches a pattern and optional size and date limits, replacing or keeping existing targets.",
            panel = "source, target | overwrite, keep_both, [Move several files by name pattern: pattern, include_subfolders], [Filter by size: min_size_bytes, max_size_bytes], [Filter by date: modified_after, modified_before], parallel")
    @Output(name = "path", type = OoaType.FILE, description = "New path of the file (the target folder when moving several files).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of files moved.")
    @Output(name = "files", type = OoaType.LIST, description = "New paths of the files.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source file, name rules choosing several files, or a folder when pattern is given.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "New path or name, or a folder when several files are moved.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing target.") boolean overwrite,
            @Input(name = "keep_both", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    visibleWhen = "overwrite=false", description = "When the target exists and overwrite is false, use a free name such as report (2).pdf instead of stopping.") boolean keepBoth,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf",
                    description = "Move every file in the source folder whose name matches this glob pattern; the target is then a folder. A source such as in/*.pdf works too.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "With a pattern, also take matching files in sub-folders, keeping their folders under the target.") boolean sub,
            @Input(name = "min_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at least this large.") Long min,
            @Input(name = "max_size_bytes", type = OoaType.NUMBER, required = false, min = 0, description = "With a pattern, only files at most this large.") Long max,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed after this date and time (UTC, like every DateTime; Text to date converts local times).") LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = "With a pattern, only files last changed before this date and time (UTC, like every DateTime; Text to date converts local times).") LocalDateTime before,
            @Input(name = "parallel", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Several files at once",
                    description = "Move up to four files at the same time; faster for many files on a network share.") boolean parallel) {
        return transferMany(ctx, source, target, overwrite, true, keepBoth, pattern, sub, min, max, after, before, parallel);
    }

    public Map<String, Object> move(ActionContext ctx, String source, String target, boolean overwrite, boolean keepBoth) {
        return move(ctx, source, target, overwrite, keepBoth, null, false, null, null, null, null, false);
    }

    /** Move with the signature before parallel was added. */
    public Map<String, Object> move(ActionContext ctx, String source, String target, boolean overwrite, boolean keepBoth, String pattern, boolean sub,
            Long min, Long max, LocalDateTime after, LocalDateTime before) {
        return move(ctx, source, target, overwrite, keepBoth, pattern, sub, min, max, after, before, false);
    }

    /**
     * Every pair (source file, target path), copied or moved in order or several at once. With keep_both and several at
     * once, free names are chosen first, so two files never pick the same "report (2).pdf".
     */
    private static List<Object> transferAll(ActionContext ctx, List<Path[]> pairs, boolean overwrite, boolean move, boolean keepBoth, boolean parallel) {
        if (parallel && keepBoth && !overwrite) {
            Set<Path> taken = new HashSet<>();
            for (Path[] pr : pairs) {
                Path t = pr[1];
                if (Files.exists(t) || taken.contains(t)) {
                    String n = t.getFileName().toString();
                    int dot = n.lastIndexOf('.');
                    String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
                    for (int i = 2; ; i++) { Path c = t.resolveSibling(base + " (" + i + ")" + ext); if (!Files.exists(c) && !taken.contains(c)) { t = c; break; } }
                }
                taken.add(t);
                pr[1] = t;
            }
        }
        return forEach(ctx, pairs, parallel, pr -> transfer(pr[0], pr[1], overwrite, move, keepBoth).toString());
    }

    /** One file, files chosen by name rules, or every file of a folder matching a glob (given as pattern, or as a wildcard in the source name) and the limits. */
    private static Map<String, Object> transferMany(ActionContext ctx, String source, String target, boolean overwrite, boolean move, boolean keepBoth,
            String pattern, boolean sub, Long min, Long max, LocalDateTime after, LocalDateTime before, boolean parallel) {
        List<Path> chosen = ctx.selectedFiles("source");
        if (chosen != null) {
            Path to = ctx.resolvePath(target);
            if (Files.isRegularFile(to)) throw new ActionException("FILE.BAD_TARGET", "The target " + to + " is a file", "With name rules choosing several files, the target must be a folder.");
            Path base = commonFolder(chosen);
            Filter limits = filter(min, max, null, null);
            List<Path[]> pairs = new ArrayList<>();
            for (Path from : chosen)
                if (limits.test(from) && modifiedBetween(from, after, before)) pairs.add(new Path[]{from, to.resolve(base.relativize(from.toAbsolutePath().normalize()).toString())});
            List<Object> done = transferAll(ctx, pairs, overwrite, move, keepBoth, parallel);
            return Out.of("path", to.toString(), "count", BigDecimal.valueOf(done.size()), "files", done);
        }
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
        List<Path[]> pairs = new ArrayList<>();
        for (String f : files(dir, glob, sub, filter(min, max, null, null))) {
            Path from = Path.of(f);
            if (modifiedBetween(from, after, before)) pairs.add(new Path[]{from, to.resolve(dir.relativize(from))});
        }
        List<Object> done = transferAll(ctx, pairs, overwrite, move, keepBoth, parallel);
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

    // ---------- temporary files and Base64 (gap G07, 3 Nov 2026) ----------
    private static final String TEMP_SESSION = "ooa.file.temporary";
    private static final long BASE64_LIMIT = 50L * 1024 * 1024;

    /** Temporary files this run made with delete_at_end; deleted when the run ends, however it ends. */
    static final class TemporaryFiles implements AutoCloseable {
        final List<Path> files = new ArrayList<>();
        @Override public void close() {
            for (Path p : files) try { Files.deleteIfExists(p); } catch (IOException ignored) { /* still in use: the OS temp clean-up takes it */ }
            files.clear();
        }
    }

    @Action(id = "create_temp_file", name = "Create temporary file", category = "Files", capabilities = "filesystem",
            description = "Create an empty file with a unique name in the temporary folder (or another folder), for working data an agent needs only for a while.",
            panel = "extension, delete_at_end | folder, prefix")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the new file.")
    public Map<String, Object> createTempFile(ActionContext ctx,
            @Input(name = "extension", type = OoaType.STRING, required = false, defaultValue = "\".tmp\"", example = ".csv", description = "File extension, e.g. .csv or .xlsx.") String extension,
            @Input(name = "delete_at_end", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Delete the file when the run ends (also when it fails); turn off to keep it.") boolean deleteAtEnd,
            @Input(name = "folder", type = OoaType.FILE, required = false, description = "Folder to create it in; the system's temporary folder when empty.") String folder,
            @Input(name = "prefix", type = OoaType.STRING, required = false, defaultValue = "\"ooa-\"", description = "Start of the file name.") String prefix) {
        String ext = extension == null || extension.isBlank() ? ".tmp" : extension.strip().startsWith(".") ? extension.strip() : "." + extension.strip();
        String pre = prefix == null ? "ooa-" : prefix;
        if ((ext + pre).matches(".*[\\\\/:*?\"<>|\\x00-\\x1f].*"))
            throw new ActionException("FILE.BAD_NAME", "The extension or prefix holds a character a file name cannot have", "Leave out \\ / : * ? \" < > |.");
        try {
            Path dir = folder == null || folder.isBlank() ? Path.of(System.getProperty("java.io.tmpdir")) : ctx.resolvePath(folder);
            Files.createDirectories(dir);
            Path p = Files.createTempFile(dir, pre, ext).toAbsolutePath().normalize();
            if (deleteAtEnd) {
                Object o = ctx.sessions().get(TEMP_SESSION);
                TemporaryFiles t = o instanceof TemporaryFiles x ? x : new TemporaryFiles();
                ctx.sessions().put(TEMP_SESSION, t);
                t.files.add(p);
            }
            return Out.of("path", p.toString());
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot create a temporary file: " + e.getMessage(), "Check the folder exists and can be written.", true); }
    }

    @Action(id = "to_base64", name = "File to Base64", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Read a file (a PDF, an image, any file) as Base64 text, for web services that take attachments as text.",
            panel = "path")
    @Output(name = "base64", type = OoaType.STRING, description = "The file's contents as Base64 text.")
    @Output(name = "size_bytes", type = OoaType.NUMBER, description = "Size of the file.")
    public Map<String, Object> toBase64(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File to read; up to 50 MB.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the file path.");
        try {
            long size = Files.size(p);
            if (size > BASE64_LIMIT) throw new ActionException("FILE.TOO_LARGE", p + " is " + size / (1024 * 1024) + " MB; Base64 text is limited to 50 MB of file",
                    "Send large files as files (for example with a REST upload) rather than as text.");
            return Out.of("base64", Base64.getEncoder().encodeToString(Files.readAllBytes(p)), "size_bytes", BigDecimal.valueOf(size));
        } catch (IOException e) { throw io("read", p, e); }
    }

    @Action(id = "from_base64", name = "Base64 to file", category = "Files", capabilities = "filesystem",
            description = "Write Base64 text (for example a PDF or image returned by a web service) to a file.",
            panel = "base64, path, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the written file.")
    @Output(name = "size_bytes", type = OoaType.NUMBER, description = "Size of the written file.")
    public Map<String, Object> fromBase64(ActionContext ctx,
            @Input(name = "base64", type = OoaType.STRING, description = "Base64 text; a data: prefix such as data:application/pdf;base64, is allowed.") String base64,
            @Input(name = "path", type = OoaType.FILE, description = "File to write; its folder is created when missing.") String path,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace the file when it exists; when off, stop with FILE.EXISTS.") boolean overwrite) {
        String text = base64 == null ? "" : base64.strip();
        if (text.startsWith("data:") && text.contains(",")) text = text.substring(text.indexOf(',') + 1);
        byte[] bytes;
        try {
            text = text.replaceAll("\\s", "");
            bytes = text.contains("-") || text.contains("_") ? Base64.getUrlDecoder().decode(text) : Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new ActionException("FILE.BAD_BASE64", "The text is not Base64: " + e.getMessage(), "Pass the Base64 text exactly as the web service returned it.");
        }
        Path p = ctx.resolvePath(path);
        if (Files.exists(p) && !overwrite) throw new ActionException("FILE.EXISTS", "File already exists: " + p, "Turn on overwrite or choose another path.");
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) { throw io("write", p, e); }
        return Out.of("path", p.toAbsolutePath().toString(), "size_bytes", BigDecimal.valueOf(bytes.length));
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

    /** Zip with the signature before add_to_existing and only_newer were added. */
    public Map<String, Object> zip(ActionContext ctx, String source, String target, String pattern, String compression, boolean overwrite, boolean deleteOriginals, Object password) {
        return zip(ctx, source, target, pattern, compression, overwrite, deleteOriginals, password, false, false);
    }

    /** Zip times are stored to 2 seconds (DOS) or 1 second (extended field), so a file counts as newer only beyond that. */
    static boolean newerThanEntry(Path f, ZipEntry e) throws IOException {
        java.nio.file.attribute.FileTime et = e.getLastModifiedTime();
        long entryMs = et != null ? et.toMillis() : e.getTime();
        return Files.getLastModifiedTime(f).toMillis() > entryMs + 2000;
    }

    private static void putFile(ZipOutputStream zos, String name, Path f) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setLastModifiedTime(Files.getLastModifiedTime(f));  // keep the file's date, so only_newer can compare later
        zos.putNextEntry(ze);
        Files.copy(f, zos);
        zos.closeEntry();
    }

    @Action(id = "zip", name = "Zip", category = "Archives", capabilities = "filesystem",
            description = "Compress files or a folder into a .zip archive, or add them to an existing one, optionally protected with a password (AES-256, opened by 7-Zip, WinZip and most tools).",
            panel = "source, pattern, target, [Protect with a password: password], delete_originals, compression | overwrite, add_to_existing, only_newer")
    @Output(name = "path", type = OoaType.FILE, description = "Created archive.")
    @Output(name = "added", type = OoaType.NUMBER, description = "Number of files written into the archive (new or replaced).")
    public Map<String, Object> zip(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "File or folder to compress, or name rules choosing the files.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Archive path, e.g. out/report.zip.") String target,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf", description = "Only add files whose names match this glob pattern; all files when empty.") String pattern,
            @Input(name = "compression", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "fast", "fastest", "store"},
                    description = "normal: default size and speed; fast and fastest trade size for speed; store adds files uncompressed.") String compression,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing archive; when false an existing archive stops the step.") boolean overwrite,
            @Input(name = "delete_originals", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete the files that were added once the archive is complete.") boolean deleteOriginals,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Encrypt every file with this password (AES-256). Windows Explorer cannot open such archives; 7-Zip, WinZip and most tools can.") Object password,
            @Input(name = "add_to_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "When the archive already exists, keep what is in it and add the files (overwrite is then ignored); a file whose name is already in it replaces that entry. Not with a password.") boolean addToExisting,
            @Input(name = "only_newer", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "add_to_existing=true",
                    description = "When adding to an existing archive, replace an entry only when the file is newer than it (by more than 2 seconds, the precision of zip dates); new names are always added.") boolean onlyNewer) {
        Path s = ctx.resolvePath(source), t = ctx.resolvePath(target);
        String pw = password instanceof SecureString ss ? ss.reveal() : password == null ? null : String.valueOf(password);
        if (pw != null && pw.isEmpty()) throw new ActionException("ZIP.BAD_PASSWORD", "The password is empty", "Pick a credential with a value, or leave the password out.");
        if (onlyNewer && !addToExisting) throw new ActionException("ZIP.BAD_OPTION", "only_newer works only when adding to an existing archive", "Turn on add_to_existing, or turn off only_newer.");
        if (addToExisting && pw != null) throw new ActionException("ZIP.BAD_OPTION", "Files cannot be added to an existing archive with a password", "Leave the password out, or write a new archive.");
        List<Path> chosen = ctx.selectedFiles("source");
        if (chosen == null && !Files.exists(s)) throw new ActionException("FILE.NOT_FOUND", "Not found: " + s, "Check the source path.");
        if (!overwrite && !addToExisting && Files.exists(t)) throw new ActionException("FILE.EXISTS", "Archive already exists: " + t, "Set overwrite to true or choose another name.");
        int level = switch (compression == null || compression.isBlank() ? "normal" : compression) {
            case "fast" -> 3; case "fastest" -> 1; case "store" -> 0; case "normal" -> java.util.zip.Deflater.DEFAULT_COMPRESSION;
            default -> throw new ActionException("ZIP.BAD_COMPRESSION", "Unknown compression '" + compression + "'", "Use normal, fast, fastest or store.");
        };
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern));
        List<Path> added = new ArrayList<>();
        try {
            if (t.getParent() != null) Files.createDirectories(t.getParent());
            Path abs = t.toAbsolutePath().normalize();
            // the files to add: those the name rules chose (kept below the folder they share), or the file or folder given
            Path base;
            List<Path> candidates;
            if (chosen != null) { base = commonFolder(chosen); candidates = chosen.stream().map(f -> f.toAbsolutePath().normalize()).sorted().toList(); }
            else {
                base = Files.isDirectory(s) ? s : s.getParent();
                try (Stream<Path> walk = Files.walk(s)) { candidates = walk.filter(Files::isRegularFile).sorted().toList(); }
            }
            List<Path> files = candidates.stream().filter(f -> m.matches(f.getFileName())).filter(f -> !f.toAbsolutePath().normalize().equals(abs)).toList();  // never add the archive to itself
            if (pw != null) {
                List<ZipCrypt.Item> items = new ArrayList<>();
                for (Path f : files) { items.add(new ZipCrypt.Item(base.relativize(f).toString().replace('\\', '/'), f)); added.add(f); }
                ZipCrypt.writeAes(t, items, level == java.util.zip.Deflater.DEFAULT_COMPRESSION ? 6 : level, pw);
                if (deleteOriginals) for (Path f : added) Files.deleteIfExists(f);
                return Map.of("path", t.toString(), "added", BigDecimal.valueOf(added.size()));
            }
            Map<String, Path> wanted = new LinkedHashMap<>();
            for (Path f : files) wanted.put(base.relativize(f).toString().replace('\\', '/'), f);
            if (addToExisting && Files.isRegularFile(t)) {
                if (ZipCrypt.anyEncrypted(t)) throw new ActionException("ZIP.BAD_OPTION", "The archive " + t + " is password-protected; files cannot be added to it", "Write a new archive instead.");
                Path tmp = Files.createTempFile(abs.getParent(), abs.getFileName().toString(), ".tmp");
                try {
                    try (ZipFile old = new ZipFile(t.toFile()); ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                        zos.setLevel(level);
                        for (ZipEntry e : Collections.list(old.entries())) {
                            Path f = wanted.remove(e.getName());
                            if (f != null && (!onlyNewer || newerThanEntry(f, e))) { putFile(zos, e.getName(), f); added.add(f); continue; }
                            ZipEntry keep = new ZipEntry(e.getName());  // entries kept as they are, in their order
                            if (e.getLastModifiedTime() != null) keep.setLastModifiedTime(e.getLastModifiedTime()); else keep.setTime(e.getTime());
                            if (e.getComment() != null) keep.setComment(e.getComment());
                            zos.putNextEntry(keep);
                            try (java.io.InputStream in = old.getInputStream(e)) { in.transferTo(zos); }
                            zos.closeEntry();
                        }
                        for (var w : wanted.entrySet()) { putFile(zos, w.getKey(), w.getValue()); added.add(w.getValue()); }
                    }
                    Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(tmp); }
            } else {
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(t))) {
                    zos.setLevel(level);
                    for (var w : wanted.entrySet()) { putFile(zos, w.getKey(), w.getValue()); added.add(w.getValue()); }
                }
            }
            if (deleteOriginals) for (Path f : added) Files.deleteIfExists(f);
        } catch (ZipException e) {
            throw new ActionException("ZIP.CORRUPT", "Cannot read the existing archive " + t + ": " + e.getMessage(), "Check it is a valid .zip file, or turn off add_to_existing to replace it.");
        } catch (IOException e) { throw io("zip", s, e); }
        return Map.of("path", t.toString(), "added", BigDecimal.valueOf(added.size()));
    }

    public Map<String, Object> unzip(ActionContext ctx, String archive, String folder, boolean overwrite) { return unzip(ctx, archive, folder, overwrite, null); }

    @Action(id = "unzip", name = "Unzip", category = "Archives", capabilities = "filesystem",
            description = "Extract a .zip archive into a folder, also a password-protected one (AES or the older ZipCrypto); entries that would escape the folder are rejected.",
            panel = "archive, folder, [The archive has a password: password], overwrite")
    @Output(name = "files", type = OoaType.LIST, description = "Extracted file paths.")
    public Map<String, Object> unzip(ActionContext ctx,
            @Input(name = "archive", type = OoaType.FILE, description = "Archive path.") String archive,
            @Input(name = "folder", type = OoaType.FILE, description = "Destination folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", newStepDefault = "false", description = "Replace files that already exist; when false they are kept and skipped.") boolean overwrite,
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
            @Input(name = "folder", type = OoaType.FILE, required = false, visibleWhen = "files=", description = "Print the files in this folder that match pattern (or the files name rules choose), after any files in the list.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", example = "*.pdf", visibleWhen = "files=", description = "With folder, glob pattern of the files to print.") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "files=", description = "With folder, also print matching files in sub-folders.") boolean sub,
            @Input(name = "modified_within_days", type = OoaType.NUMBER, required = false, visibleWhen = "files=", min = 0, description = "With folder, only files changed in the last this many days.") Integer within) {
        List<Object> all = new ArrayList<>(files == null ? List.of() : files);
        List<Path> chosen = ctx.selectedFiles("folder");
        Filter recent = filter(null, null, within, null);
        if (chosen != null) chosen.stream().filter(recent::test).forEach(f -> all.add(f.toString()));
        else if (folder != null && !folder.isBlank()) all.addAll(files(ctx.resolvePath(folder), pattern, sub, recent));
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
