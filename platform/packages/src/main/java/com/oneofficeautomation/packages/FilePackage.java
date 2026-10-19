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

    @Action(id = "read_text", name = "Read text", category = "Files", idempotent = true, capabilities = "filesystem", description = "Read a text file.")
    @Output(name = "text", type = OoaType.STRING, description = "File content.")
    public Map<String, Object> readText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding.") String enc) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path and permissions.");
        try { return Map.of("text", Files.readString(p, Charset.forName(enc))); } catch (IOException e) { throw io("read", p, e); }
    }

    @Action(id = "write_text", name = "Write text", category = "Files", capabilities = "filesystem", description = "Write text to a file, overwriting or appending.")
    public Map<String, Object> writeText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "text", type = OoaType.STRING, description = "Text to write.") String text,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Append instead of overwrite.") boolean append) {
        Path p = ctx.resolvePath(path);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (append) Files.writeString(p, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else Files.writeString(p, text);
        } catch (IOException e) { throw io("write", p, e); }
        return Map.of();
    }

    @Action(id = "delete", name = "Delete", category = "Files", idempotent = true, capabilities = "filesystem", description = "Delete a file if it exists.")
    public Map<String, Object> delete(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File path.") String path) {
        Path p = ctx.resolvePath(path);
        try { Files.deleteIfExists(p); } catch (IOException e) { throw io("delete", p, e); }
        return Map.of();
    }

    @Action(id = "list", name = "List files", category = "Folders", idempotent = true, capabilities = "filesystem",
            description = "List files in a folder matching a glob pattern.")
    @Output(name = "files", type = OoaType.LIST, description = "Sorted list of file paths.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder path.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob pattern, e.g. *.pdf.") String pattern) {
        return Map.of("files", new ArrayList<Object>(files(ctx.resolvePath(folder), pattern)));
    }

    @Condition(id = "exists", name = "File exists", description = "True when the file exists.")
    public boolean exists(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File path.") String path) {
        return Files.isRegularFile(ctx.resolvePath(path));
    }

    @LoopIterator(id = "files_in_folder", name = "Each file in folder", description = "Yield each file path in a folder.")
    public Iterable<Object> filesInFolder(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder path.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob pattern.") String pattern) {
        return new ArrayList<>(files(ctx.resolvePath(folder), pattern));
    }

    private static List<String> files(Path dir, String glob) {
        if (!Files.isDirectory(dir)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + dir, "Check the folder path.");
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> m.matches(p.getFileName())).map(Path::toString).sorted().toList();
        } catch (IOException e) { throw io("list", dir, e); }
    }

    @Action(id = "copy", name = "Copy", category = "Files", capabilities = "filesystem", description = "Copy a file.")
    public Map<String, Object> copy(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source file.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Target file.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing target.") boolean overwrite) {
        transfer(ctx, source, target, overwrite, false);
        return Map.of();
    }

    @Action(id = "move", name = "Move or rename", category = "Files", capabilities = "filesystem", description = "Move or rename a file.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "Source file.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "New path or name.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing target.") boolean overwrite) {
        transfer(ctx, source, target, overwrite, true);
        return Map.of();
    }

    private static void transfer(ActionContext ctx, String source, String target, boolean overwrite, boolean move) {
        Path s = ctx.resolvePath(source), t = ctx.resolvePath(target);
        if (!Files.isRegularFile(s)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + s, "Check the source path.");
        if (Files.exists(t) && !overwrite) throw new ActionException("FILE.EXISTS", "Target exists: " + t, "Set overwrite to true or choose another name.");
        try {
            if (t.getParent() != null) Files.createDirectories(t.getParent());
            CopyOption[] o = overwrite ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0];
            if (move) Files.move(s, t, o); else Files.copy(s, t, o);
        } catch (IOException e) { throw io(move ? "move" : "copy", s, e); }
    }

    @Action(id = "get_info", name = "Get file info", category = "Files", idempotent = true, capabilities = "filesystem",
            description = "Return a file's name, extension, folder, size and last modified time.")
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

    @Action(id = "zip", name = "Zip", category = "Archives", capabilities = "filesystem", description = "Compress files or a folder into a .zip archive.")
    @Output(name = "path", type = OoaType.FILE, description = "Created archive.")
    public Map<String, Object> zip(ActionContext ctx,
            @Input(name = "source", type = OoaType.FILE, description = "File or folder to compress.") String source,
            @Input(name = "target", type = OoaType.FILE, description = "Archive path, e.g. out/report.zip.") String target) {
        Path s = ctx.resolvePath(source), t = ctx.resolvePath(target);
        if (!Files.exists(s)) throw new ActionException("FILE.NOT_FOUND", "Not found: " + s, "Check the source path.");
        try {
            if (t.getParent() != null) Files.createDirectories(t.getParent());
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(t)); Stream<Path> walk = Files.walk(s)) {
                Path base = Files.isDirectory(s) ? s : s.getParent();
                for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                    zos.putNextEntry(new ZipEntry(base.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, zos);
                    zos.closeEntry();
                }
            }
        } catch (IOException e) { throw io("zip", s, e); }
        return Map.of("path", t.toString());
    }

    @Action(id = "unzip", name = "Unzip", category = "Archives", capabilities = "filesystem",
            description = "Extract a .zip archive into a folder; entries that would escape the folder are rejected.")
    @Output(name = "files", type = OoaType.LIST, description = "Extracted file paths.")
    public Map<String, Object> unzip(ActionContext ctx,
            @Input(name = "archive", type = OoaType.FILE, description = "Archive path.") String archive,
            @Input(name = "folder", type = OoaType.FILE, description = "Destination folder.") String folder) {
        Path a = ctx.resolvePath(archive), dest = ctx.resolvePath(folder).toAbsolutePath().normalize();
        List<Object> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(a))) {
            Files.createDirectories(dest);
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest))  // zip-slip protection
                    throw new ActionException("ZIP.UNSAFE_ENTRY", "Archive entry '" + e.getName() + "' points outside the destination", "The archive may be malicious; do not extract it.");
                if (e.isDirectory()) { Files.createDirectories(target); continue; }
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                out.add(target.toString());
            }
        } catch (NoSuchFileException e) {
            throw new ActionException("FILE.NOT_FOUND", "Archive not found: " + a, "Check the path.");
        } catch (IOException e) { throw io("unzip", a, e); }
        return Map.of("files", out);
    }
}
