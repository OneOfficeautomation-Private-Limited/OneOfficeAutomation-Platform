package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@OoaPackage(id = "ooa.folder", name = "Folder", version = "1.0.0", description = "Create, list, copy and delete folders.")
public final class FolderPackage {

    @Action(id = "create", name = "Create folder", category = "Folders", idempotent = true, capabilities = "filesystem", description = "Create a folder and any missing parents.",
            panel = "path, fail_if_exists")
    public Map<String, Object> create(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path,
            @Input(name = "fail_if_exists", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop when the folder already exists instead of continuing.") boolean failIfExists) {
        Path p = ctx.resolvePath(path);
        if (failIfExists && Files.exists(p)) throw new ActionException("FOLDER.EXISTS", "Folder already exists: " + p, "Choose another name or turn off fail_if_exists.");
        try { Files.createDirectories(p); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot create " + p + ": " + e.getMessage()); }
        return Map.of();
    }

    private static final String D_LARGER = "Only when the folder holds more than this many KB in all its files, at any depth (1 KB = 1024 bytes).";
    private static final String D_SMALLER = "Only when the folder holds less than this many KB in all its files, at any depth.";
    private static final String D_AFTER = "Only when the folder itself was last changed after this date and time (local time); adding or removing an item directly inside changes it.";
    private static final String D_BEFORE = "Only when the folder itself was last changed before this date and time (local time).";

    /**
     * The optional conditions of Delete folder and Rename folder. Every limit given must hold; size is the total of all
     * files below the folder, date is the folder's own last-change time. A range that no folder can meet (larger than
     * at least smaller than, or after not before before) is refused rather than silently doing nothing.
     */
    static void checkConditions(java.math.BigDecimal largerKb, java.math.BigDecimal smallerKb, java.time.LocalDateTime after, java.time.LocalDateTime before) {
        if (largerKb != null && largerKb.signum() < 0 || smallerKb != null && smallerKb.signum() < 0)
            throw new ActionException("FOLDER.BAD_OPTION", "Sizes cannot be negative", "Use 0 or more KB.");
        if (largerKb != null && smallerKb != null && largerKb.compareTo(smallerKb) >= 0)
            throw new ActionException("FOLDER.BAD_OPTION", "larger_than_kb " + largerKb.toPlainString() + " is not below smaller_than_kb " + smallerKb.toPlainString() + ", so no folder can match",
                    "Give a size range whose lower limit is below its upper limit, or fill in only one of them.");
        if (after != null && before != null && !after.isBefore(before))
            throw new ActionException("FOLDER.BAD_OPTION", "modified_after " + after + " is not before modified_before " + before + ", so no folder can match",
                    "Give a date range whose start is before its end, or fill in only one of them.");
    }

    /** True when the folder meets every condition given (none given: true). */
    static boolean meetsConditions(Path dir, java.math.BigDecimal largerKb, java.math.BigDecimal smallerKb, java.time.LocalDateTime after, java.time.LocalDateTime before) throws IOException {
        if ((after != null || before != null) && !FilePackage.modifiedBetween(dir, after, before)) return false;
        if (largerKb == null && smallerKb == null) return true;
        long bytes = 0;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path x : (Iterable<Path>) w::iterator) if (Files.isRegularFile(x)) bytes += Files.size(x);
        }
        java.math.BigDecimal kb = java.math.BigDecimal.valueOf(bytes).divide(java.math.BigDecimal.valueOf(1024));
        return (largerKb == null || kb.compareTo(largerKb) > 0) && (smallerKb == null || kb.compareTo(smallerKb) < 0);
    }

    /** Delete folder with the signature before the size and date conditions were added. */
    public Map<String, Object> delete(ActionContext ctx, String path, boolean recursive) { return delete(ctx, path, recursive, null, null, null, null); }

    @Action(id = "delete", name = "Delete folder", category = "Folders", capabilities = "filesystem",
            description = "Delete a folder, optionally only when its total size or its last-change date meets limits. Non-empty folders are deleted only when recursive is true.",
            panel = "path, recursive, [Only when the size fits: larger_than_kb, smaller_than_kb], [Only when the date fits: modified_after, modified_before]")
    @Output(name = "deleted", type = OoaType.BOOLEAN, description = "True when the folder was deleted; false when it did not exist or did not meet the limits.")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete everything inside.") boolean recursive,
            @Input(name = "larger_than_kb", type = OoaType.NUMBER, required = false, min = 0, description = D_LARGER) java.math.BigDecimal largerKb,
            @Input(name = "smaller_than_kb", type = OoaType.NUMBER, required = false, min = 0, description = D_SMALLER) java.math.BigDecimal smallerKb,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = D_AFTER) java.time.LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) java.time.LocalDateTime before) {
        checkConditions(largerKb, smallerKb, after, before);
        Path p = ctx.resolvePath(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) return Map.of("deleted", false);
        if (p.getParent() == null || p.equals(ctx.workDir().toAbsolutePath().normalize()))
            throw new ActionException("FOLDER.PROTECTED", "Refusing to delete " + p, "Point to a specific sub-folder.");
        try {
            if (!meetsConditions(p, largerKb, smallerKb, after, before)) return Map.of("deleted", false);
            if (!recursive) {
                try (Stream<Path> s = Files.list(p)) {
                    if (s.findAny().isPresent()) throw new ActionException("FOLDER.NOT_EMPTY", "Folder is not empty: " + p, "Set recursive to true to delete its contents.");
                }
                Files.delete(p);
            } else try (Stream<Path> w = Files.walk(p)) {
                for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot delete " + p + ": " + e.getMessage(), null, true); }
        return Map.of("deleted", true);
    }

    @Action(id = "list_subfolders", name = "List subfolders", category = "Folders", idempotent = true, capabilities = "filesystem", description = "List the folders directly inside a folder.",
            panel = "path")
    @Output(name = "folders", type = OoaType.LIST, description = "Sorted folder paths.")
    public Map<String, Object> listSubfolders(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isDirectory(p)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + p, "Check the folder path.");
        try (Stream<Path> s = Files.list(p)) {
            return Map.of("folders", new ArrayList<Object>(s.filter(Files::isDirectory).map(Path::toString).sorted().toList()));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
    }

    @LoopIterator(id = "subfolders", name = "Each folder in folder", description = "Yield the path of each folder inside a folder, optionally with all folders below it.")
    public Iterable<Object> subfolders(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also yield folders inside the sub-folders, at any depth.") boolean deep,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob pattern for the folder names, e.g. 2026-*.") String pattern) {
        Path p = ctx.resolvePath(path);
        if (!Files.isDirectory(p)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + p, "Check the folder path.");
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern));
        try (Stream<Path> s = deep ? Files.walk(p) : Files.list(p)) {
            return new ArrayList<>(s.filter(x -> !x.equals(p) && Files.isDirectory(x) && m.matches(x.getFileName())).map(Path::toString).sorted().toList());
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
    }

    @Action(id = "copy", name = "Copy folder", category = "Folders", capabilities = "filesystem", description = "Copy a folder and its contents, optionally only some files by name, size or date.",
            panel = "source, target, overwrite, [Filter by size: larger_than_kb, smaller_than_kb], [Filter by date: modified_after, modified_before] | pattern, only_newer")
    @Output(name = "copied", type = OoaType.NUMBER, description = "Number of files copied.")
    public Map<String, Object> copy(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source folder.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Target folder.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace files that already exist in the target; when false they are kept.") boolean overwrite,
            @Input(name = "pattern", type = OoaType.STRING, required = false, example = "*.pdf", description = "Only copy files whose names match this glob pattern; all files when empty.") String pattern,
            @Input(name = "only_newer", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "overwrite=true", description = "Copy a file only when it is newer than the one already in the target.") boolean onlyNewer,
            @Input(name = "larger_than_kb", type = OoaType.NUMBER, required = false, min = 0, description = "Only copy files larger than this many KB (1 KB = 1024 bytes).") java.math.BigDecimal largerKb,
            @Input(name = "smaller_than_kb", type = OoaType.NUMBER, required = false, min = 0, description = "Only copy files smaller than this many KB.") java.math.BigDecimal smallerKb,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = "Only copy files last changed after this date and time (local time).") java.time.LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = "Only copy files last changed before this date and time (local time).") java.time.LocalDateTime before) {
        Path s = ctx.resolvePath(source), t = ctx.resolvePath(target);
        if (!Files.isDirectory(s)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + s, "Check the source path.");
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern));
        int n = 0;
        try (Stream<Path> w = Files.walk(s)) {
            for (Path x : w.toList()) {
                Path dst = t.resolve(s.relativize(x).toString());
                if (Files.isDirectory(x)) { Files.createDirectories(dst); continue; }
                if (!m.matches(x.getFileName()) || !FilePackage.modifiedBetween(x, after, before)) continue;
                if (largerKb != null || smallerKb != null) {
                    java.math.BigDecimal kb = java.math.BigDecimal.valueOf(Files.size(x)).divide(java.math.BigDecimal.valueOf(1024));
                    if (largerKb != null && kb.compareTo(largerKb) <= 0 || smallerKb != null && kb.compareTo(smallerKb) >= 0) continue;
                }
                if (Files.exists(dst)) {
                    if (!overwrite) continue;
                    if (onlyNewer && Files.getLastModifiedTime(x).compareTo(Files.getLastModifiedTime(dst)) <= 0) continue;
                }
                if (onlyNewer) Files.copy(x, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);  // keep times so later runs compare correctly
                else Files.copy(x, dst, StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot copy " + s + ": " + e.getMessage(), null, true); }
        return Map.of("copied", java.math.BigDecimal.valueOf(n));
    }

    public Map<String, Object> copy(ActionContext ctx, String source, String target, boolean overwrite, String pattern, boolean onlyNewer) {
        return copy(ctx, source, target, overwrite, pattern, onlyNewer, null, null, null, null);
    }

    @Action(id = "rename_folder", name = "Rename folder", category = "Folders", capabilities = "filesystem",
            description = "Give a folder a new name in the same parent folder, optionally only when its total size or its last-change date meets limits.",
            panel = "path, new_name, [Only when the size fits: larger_than_kb, smaller_than_kb], [Only when the date fits: modified_after, modified_before]")
    @Output(name = "path", type = OoaType.FILE, description = "New full path of the folder; the unchanged path when it did not meet the limits.")
    @Output(name = "renamed", type = OoaType.BOOLEAN, description = "True when the folder was renamed; false when it did not meet the limits.")
    public Map<String, Object> renameFolder(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Folder to rename.") String path,
            @Input(name = "new_name", type = OoaType.STRING, example = "Archive 2026", description = "New name only, without any path.") String newName,
            @Input(name = "larger_than_kb", type = OoaType.NUMBER, required = false, min = 0, description = D_LARGER) java.math.BigDecimal largerKb,
            @Input(name = "smaller_than_kb", type = OoaType.NUMBER, required = false, min = 0, description = D_SMALLER) java.math.BigDecimal smallerKb,
            @Input(name = "modified_after", type = OoaType.DATETIME, required = false, description = D_AFTER) java.time.LocalDateTime after,
            @Input(name = "modified_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) java.time.LocalDateTime before) {
        checkConditions(largerKb, smallerKb, after, before);
        Path p = ctx.resolvePath(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) throw new ActionException("FOLDER.NOT_FOUND", "Folder not found: " + p, "Check the folder path.");
        if (newName == null || newName.isBlank() || newName.contains("/") || newName.contains("\\") || newName.equals(".") || newName.equals("..")
                || newName.matches(".*[\\x00-\\x1f<>:\"|?*].*"))
            throw new ActionException("FOLDER.BAD_NAME", "Invalid folder name '" + newName + "'", "Give only the new name, without slashes or the characters < > : \" | ? *.");
        if (p.getParent() == null || p.equals(ctx.workDir().toAbsolutePath().normalize()))
            throw new ActionException("FOLDER.PROTECTED", "Refusing to rename " + p, "Point to a specific sub-folder.");
        try { if (!meetsConditions(p, largerKb, smallerKb, after, before)) return Map.of("path", p.toString(), "renamed", false); }
        catch (IOException e) { throw new ActionException("FOLDER.IO_ERROR", "Cannot read the size of " + p + ": " + e.getMessage(), "Close any program using the folder and try again.", true); }
        Path target = p.resolveSibling(newName.strip());
        if (Files.exists(target) && !target.getFileName().toString().equalsIgnoreCase(p.getFileName().toString()))
            throw new ActionException("FOLDER.EXISTS", "A file or folder named '" + newName + "' already exists in " + p.getParent(), "Choose another name.");
        try { Files.move(p, target); }
        catch (IOException e) { throw new ActionException("FOLDER.IO_ERROR", "Cannot rename " + p + ": " + e.getMessage(), "Close any program using the folder and try again.", true); }
        return Map.of("path", target.toString(), "renamed", true);
    }

    /** Rename folder with the signature before the size and date conditions were added. */
    public Map<String, Object> renameFolder(ActionContext ctx, String path, String newName) { return renameFolder(ctx, path, newName, null, null, null, null); }

    @Action(id = "open_folder", name = "Open folder", category = "Folders", capabilities = {"desktop", "filesystem"},
            description = "Show a folder in the file manager (File Explorer on Windows).",
            panel = "path")
    public Map<String, Object> openFolder(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder to show.") String path) {
        Path p = ctx.resolvePath(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) throw new ActionException("FOLDER.NOT_FOUND", "Folder not found: " + p, "Check the folder path.");
        DesktopSupport.requireDesktop();
        List<String> cmd = openCommand(p);
        try { new ProcessBuilder(cmd).start(); }  // file managers keep running or return odd exit codes, so do not wait
        catch (IOException e) { throw new ActionException("FOLDER.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Check a file manager is installed on the runner."); }
        return Map.of();
    }

    static List<String> openCommand(Path folder) {
        if (ProcessRunner.WINDOWS) return List.of("explorer.exe", folder.toString());
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return List.of("open", folder.toString());
        return List.of("xdg-open", folder.toString());
    }

    @Action(id = "create_shortcut", name = "Create folder shortcut", category = "Folders", capabilities = "filesystem",
            description = "Create a shortcut that opens a folder: a .lnk file on Windows, a .desktop file or symbolic link elsewhere.",
            panel = "path, shortcut | comment, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Created shortcut.")
    public Map<String, Object> createShortcut(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Folder the shortcut opens.") String path,
            @Input(name = "shortcut", type = OoaType.FILE, description = "Shortcut to create; .lnk is added on Windows when missing.") String shortcut,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Tooltip text shown for the shortcut.") String comment,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing shortcut.") boolean overwrite) {
        return Map.of("path", FilePackage.shortcut(ctx, "FOLDER", path, shortcut, null, null, comment, overwrite, true).toString());
    }

    @Action(id = "create_symbolic_link", name = "Create folder symbolic link", category = "Folders", capabilities = "filesystem",
            description = "Create a symbolic link that points to a folder; on Windows this needs Developer Mode or administrator rights.",
            panel = "path, link | overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Created link.")
    public Map<String, Object> createSymbolicLink(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Folder the link points to.") String path,
            @Input(name = "link", type = OoaType.FILE, description = "Link to create.") String link,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing link (never a real folder).") boolean overwrite) {
        return Map.of("path", FilePackage.symbolicLink(ctx, "FOLDER", path, link, overwrite, true).toString());
    }

    @Condition(id = "exists", name = "Folder exists", description = "True when the folder exists.")
    public boolean exists(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path) {
        return Files.isDirectory(ctx.resolvePath(path));
    }

    @Condition(id = "is_empty", name = "Folder is empty", description = "True when the folder exists and contains nothing.")
    public boolean isEmpty(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isDirectory(p)) return false;
        try (Stream<Path> s = Files.list(p)) { return s.findAny().isEmpty(); } catch (IOException e) { return false; }
    }
}
