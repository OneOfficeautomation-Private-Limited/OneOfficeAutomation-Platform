package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@OoaPackage(id = "ooa.folder", name = "Folder", version = "1.0.0", description = "Create, list, copy and delete folders.")
public final class FolderPackage {

    @Action(id = "create", name = "Create folder", category = "Folders", idempotent = true, capabilities = "filesystem", description = "Create a folder and any missing parents.")
    public Map<String, Object> create(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path) {
        Path p = ctx.resolvePath(path);
        try { Files.createDirectories(p); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot create " + p + ": " + e.getMessage()); }
        return Map.of();
    }

    @Action(id = "delete", name = "Delete folder", category = "Folders", capabilities = "filesystem",
            description = "Delete a folder. Non-empty folders are deleted only when recursive is true.")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete everything inside.") boolean recursive) {
        Path p = ctx.resolvePath(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) return Map.of();
        if (p.getParent() == null || p.equals(ctx.workDir().toAbsolutePath().normalize()))
            throw new ActionException("FOLDER.PROTECTED", "Refusing to delete " + p, "Point to a specific sub-folder.");
        try {
            if (!recursive) {
                try (Stream<Path> s = Files.list(p)) {
                    if (s.findAny().isPresent()) throw new ActionException("FOLDER.NOT_EMPTY", "Folder is not empty: " + p, "Set recursive to true to delete its contents.");
                }
                Files.delete(p);
            } else try (Stream<Path> w = Files.walk(p)) {
                for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot delete " + p + ": " + e.getMessage(), null, true); }
        return Map.of();
    }

    @Action(id = "list_subfolders", name = "List subfolders", category = "Folders", idempotent = true, capabilities = "filesystem", description = "List the folders directly inside a folder.")
    @Output(name = "folders", type = OoaType.LIST, description = "Sorted folder paths.")
    public Map<String, Object> listSubfolders(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Folder path.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isDirectory(p)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + p, "Check the folder path.");
        try (Stream<Path> s = Files.list(p)) {
            return Map.of("folders", new ArrayList<Object>(s.filter(Files::isDirectory).map(Path::toString).sorted().toList()));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
    }

    @Action(id = "copy", name = "Copy folder", category = "Folders", capabilities = "filesystem", description = "Copy a folder and its contents.")
    public Map<String, Object> copy(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source folder.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Target folder.") String target) {
        Path s = ctx.resolvePath(source), t = ctx.resolvePath(target);
        if (!Files.isDirectory(s)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + s, "Check the source path.");
        try (Stream<Path> w = Files.walk(s)) {
            for (Path x : w.toList()) {
                Path dst = t.resolve(s.relativize(x).toString());
                if (Files.isDirectory(x)) Files.createDirectories(dst); else Files.copy(x, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot copy " + s + ": " + e.getMessage(), null, true); }
        return Map.of();
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
