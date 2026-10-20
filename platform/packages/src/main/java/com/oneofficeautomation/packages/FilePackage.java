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

    // ---------- Path parts ----------

    @Action(id = "get_name", name = "Get file name", category = "Paths", idempotent = true,
            description = "Return the last part of a path, with and without its extension; the file does not need to exist.")
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
            description = "Return the folder that contains a file or folder, as a full path.")
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
            description = "Create a shortcut that opens a file: a .lnk file on Windows, a .desktop file or symbolic link elsewhere.")
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
            description = "Create a symbolic link that points to a file; on Windows this needs Developer Mode or administrator rights.")
    @Output(name = "path", type = OoaType.FILE, description = "Created link.")
    public Map<String, Object> createSymbolicLink(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File the link points to.") String path,
            @Input(name = "link", type = OoaType.FILE, description = "Link to create.") String link,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace an existing file link (never a real folder).") boolean overwrite) {
        return Map.of("path", symbolicLink(ctx, "FILE", path, link, overwrite, false).toString());
    }

    @Action(id = "print_files", name = "Print files", category = "Files", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Print several files, in order, on a named printer or with each file's default application.")
    @Output(name = "printed", type = OoaType.NUMBER, description = "Number of files sent to the printer.")
    public Map<String, Object> printFiles(ActionContext ctx,
            @Input(name = "files", type = OoaType.LIST, description = "File paths to print.") List<Object> files,
            @Input(name = "printer", type = OoaType.STRING, required = false, description = "Printer name; the default printer when empty.") String printer,
            @Input(name = "copies", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Copies of each file.") int copies) {
        if (files == null || files.isEmpty()) throw new ActionException("FILE.NO_FILES", "Give at least one file to print", "Fill in the files list.");
        List<String> paths = new ArrayList<>();
        for (Object o : files) {
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
