package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The developer's workspace: folders, sub-folders, automation items and data files. Item types follow the file name:
 * bots (*.flow.json), forms (*.form.json), processes (*.process.json), document automations (*.doc.json),
 * AI Skills (ai-skills/*.json, where the AI Skills package reads them) and scripts (*.py, *.ps1, *.vbs, *.js).
 * Every path is relative to the root and is checked to stay inside it; hidden entries (such as the
 * .trash folder) are never listed or addressable. Delete moves items to .trash so nothing is lost by accident.
 * In Control Center this becomes the private/shared repository with check-in, versions and permissions.
 */
final class Workspace {
    static final String BOT_SUFFIX = ".flow.json";
    static final String SKILLS_FOLDER = "ai-skills";
    private static final long MAX_ITEM = 2L * 1024 * 1024;
    /** Text files Studio can open and save in its editors (bots have their own endpoint). */
    private static final List<String> EDITABLE = List.of(".form.json", ".process.json", ".doc.json", ".py", ".ps1", ".vbs", ".js", ".json",
            ".txt", ".csv", ".xml", ".md", ".sql", ".html", ".ini", ".yaml", ".yml");
    private static final Pattern KIND = Pattern.compile("\"kind\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9 _().&-][A-Za-z0-9 _().&-]{0,79}$");
    private static final Pattern NAME_WITH_EXT = Pattern.compile("^[A-Za-z0-9 _().&-][A-Za-z0-9 _.()&-]{0,99}$");

    final Path root;

    Workspace(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    /** Resolve a workspace-relative path, refusing anything outside the root or hidden. */
    Path resolve(String rel) {
        String r = rel == null ? "" : rel.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        Path p = root.resolve(r).normalize();
        if (!p.startsWith(root)) throw new OoaException("WORKSPACE.OUTSIDE", "Path is outside the workspace: " + rel);
        for (Path part : root.relativize(p)) if (part.toString().startsWith("."))
            throw new OoaException("WORKSPACE.HIDDEN", "Hidden paths cannot be used: " + rel);
        return p;
    }

    String rel(Path p) { return root.relativize(p).toString().replace('\\', '/'); }

    static String checkName(String name, boolean allowExtension) {
        String n = name == null ? "" : name.trim();
        if (!(allowExtension ? NAME_WITH_EXT : NAME).matcher(n).matches() || n.contains(".."))
            throw new OoaException("WORKSPACE.BAD_NAME", "Invalid name '" + name + "'",
                    "Use letters, digits, spaces and - _ ( ) &; up to 80 characters.");
        return n;
    }

    Map<String, Object> tree() throws IOException { return node(root); }

    private Map<String, Object> node(Path p) throws IOException {
        Map<String, Object> n = new LinkedHashMap<>();
        boolean dir = Files.isDirectory(p);
        String name = p.equals(root) ? "Workspace" : p.getFileName().toString();
        n.put("name", name);
        n.put("path", p.equals(root) ? "" : rel(p));
        String type = dir ? "folder" : typeOf(p);
        n.put("type", type);
        if (!dir) {
            n.put("modified", Files.getLastModifiedTime(p).toInstant().toString());
            if (type.equals("bot")) n.put("kind", botKind(p));
        }
        if (dir) {
            List<Map<String, Object>> kids = new ArrayList<>();
            try (Stream<Path> s = Files.list(p)) {
                List<Path> entries = s.filter(x -> !x.getFileName().toString().startsWith(".")).sorted(
                        Comparator.comparing((Path x) -> !Files.isDirectory(x)).thenComparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
                for (Path x : entries) kids.add(node(x));
            }
            n.put("children", kids);
        } else n.put("size", BigDecimal.valueOf(Files.size(p)));
        return n;
    }

    /** Item type of a file: bot, form, process, document, skill, script or file. */
    String typeOf(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(BOT_SUFFIX)) return "bot";
        if (n.endsWith(".form.json")) return "form";
        if (n.endsWith(".process.json")) return "process";
        if (n.endsWith(".doc.json")) return "document";
        if (n.endsWith(".json") && p.getParent() != null && p.getParent().equals(root.resolve(SKILLS_FOLDER))) return "skill";
        if (n.endsWith(".py") || n.endsWith(".ps1") || n.endsWith(".vbs") || n.endsWith(".js")) return "script";
        return "file";
    }

    /** TaskBot or ApiTask, read from the start of the file so the tree stays fast. */
    private static String botKind(Path p) {
        try (var in = Files.newInputStream(p)) {
            var m = KIND.matcher(new String(in.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "TaskBot";
        } catch (IOException e) { return "TaskBot"; }
    }

    static boolean editable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return !n.endsWith(BOT_SUFFIX) && EDITABLE.stream().anyMatch(n::endsWith);
    }

    /** Creates a form, process, document automation, AI Skill or script with starter content; returns its path. */
    String createItem(String parent, String name, String type, JsonWriter writer) throws IOException {
        String base = checkName(name, false);
        Path dir;
        String file;
        Object content;
        String id = base.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
        switch (type) {
            case "form" -> {
                dir = resolve(parent); file = base + ".form.json";
                content = ordered("$schema", "https://schemas.oneofficeautomation.com/form/v1.json", "id", id, "name", base, "description", "",
                        "fields", List.of(ordered("id", "full_name", "type", "text", "label", "Full name", "required", true),
                                ordered("id", "email", "type", "email", "label", "Email address", "required", true)),
                        "submitLabel", "Submit");
            }
            case "process" -> {
                dir = resolve(parent); file = base + ".process.json";
                content = ordered("$schema", "https://schemas.oneofficeautomation.com/process/v1.json", "id", id, "name", base, "description", "", "stages", new ArrayList<>());
            }
            case "document" -> {
                dir = resolve(parent); file = base + ".doc.json";
                content = ordered("$schema", "https://schemas.oneofficeautomation.com/document/v1.json", "id", id, "name", base, "description", "",
                        "accept", List.of("pdf", "txt"), "provider", "openai", "model", "gpt-4o-mini",
                        "fields", List.of(ordered("name", "document_number", "description", "The document or invoice number", "type", "String", "required", true),
                                ordered("name", "total", "description", "Total amount as a number", "type", "Number", "required", false)));
            }
            case "skill" -> {
                // The AI Skills package reads skills from the workspace's ai-skills folder, so skills always live there.
                dir = root.resolve(SKILLS_FOLDER);
                Files.createDirectories(dir);
                String skill = base.trim().replaceAll("[^A-Za-z0-9_-]+", "_");
                file = skill + ".json";
                content = ordered("description", "", "system", "You are a careful assistant for business documents.", "prompt", "Summarise this text in three sentences:\n${text}",
                        "provider", "openai", "model", "gpt-4o-mini", "output", "text", "temperature", 0, "mask_pii", true);
            }
            case "script-python", "script-powershell", "script-vbscript", "script-javascript" -> {
                dir = resolve(parent);
                String lang = type.substring(7);
                file = base + switch (lang) { case "python" -> ".py"; case "powershell" -> ".ps1"; case "vbscript" -> ".vbs"; default -> ".js"; };
                content = switch (lang) {
                    case "python" -> "# Python script run by the Run Python command.\n# The step's 'input' arrives on standard input; print JSON to return values in the 'json' output.\nimport sys, json\n\ndata = sys.stdin.read()\nprint(json.dumps({\"received\": data}))\n";
                    case "powershell" -> "# PowerShell script run by the Run PowerShell command.\n# The step's 'input' arrives on standard input; write JSON to return values in the 'json' output.\n$data = [Console]::In.ReadToEnd()\n@{ received = $data } | ConvertTo-Json\n";
                    case "vbscript" -> "' VBScript run by the Run VBScript command (Windows only).\nWScript.Echo \"Hello from VBScript\"\n";
                    default -> "// JavaScript run by the Run JavaScript command (Node.js).\n// The step's 'input' arrives on standard input; print JSON to return values in the 'json' output.\nlet data = \"\";\nprocess.stdin.on(\"data\", (c) => (data += c));\nprocess.stdin.on(\"end\", () => console.log(JSON.stringify({ received: data })));\n";
                };
            }
            default -> throw new OoaException("WORKSPACE.BAD_TYPE", "Unknown item type '" + type + "'", "Use form, process, document, skill or script-python/powershell/vbscript/javascript.");
        }
        if (!Files.isDirectory(dir)) throw new OoaException("WORKSPACE.NOT_FOUND", "Folder not found: " + parent);
        Path p = dir.resolve(file);
        if (Files.exists(p)) throw new OoaException("WORKSPACE.EXISTS", "'" + file + "' already exists here", "Choose another name.");
        atomicWrite(p, content instanceof String text ? text : writer.write(content));
        return rel(p);
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    String readItem(String path) throws IOException {
        Path p = resolve(path);
        if (!Files.isRegularFile(p)) throw new OoaException("WORKSPACE.NOT_FOUND", "Not found: " + path);
        if (!editable(p.getFileName().toString())) throw new OoaException("WORKSPACE.NOT_EDITABLE", "Studio cannot open this kind of file: " + path, "Open it with another program.");
        if (Files.size(p) > MAX_ITEM) throw new OoaException("WORKSPACE.TOO_LARGE", "The file is larger than 2 MB: " + path);
        return Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Saves a text item atomically; JSON items must stay valid JSON objects. */
    String writeItem(String path, String text, java.util.function.Function<String, Object> parseJson) throws IOException {
        Path p = resolve(path);
        if (!editable(p.getFileName().toString())) throw new OoaException("WORKSPACE.NOT_EDITABLE", "Studio cannot save this kind of file: " + path);
        if (p.getParent() == null || !Files.isDirectory(p.getParent())) throw new OoaException("WORKSPACE.NOT_FOUND", "Folder not found for " + path);
        if (text == null) throw new OoaException("WORKSPACE.BAD_CONTENT", "Nothing to save");
        if (text.length() > MAX_ITEM) throw new OoaException("WORKSPACE.TOO_LARGE", "The content is larger than 2 MB");
        if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            Object v;
            try { v = parseJson.apply(text); } catch (RuntimeException e) { throw new OoaException("WORKSPACE.BAD_JSON", "Not valid JSON: " + e.getMessage(), "Fix the JSON, or use the designer tab."); }
            if (!(v instanceof Map)) throw new OoaException("WORKSPACE.BAD_JSON", "The file must contain a JSON object");
        }
        atomicWrite(p, text);
        return rel(p);
    }

    private static void atomicWrite(Path p, String text) throws IOException {
        Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
        Files.writeString(tmp, text, java.nio.charset.StandardCharsets.UTF_8);
        try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
    }

    String createFolder(String parent, String name) throws IOException {
        Path dir = resolve(parent);
        if (!Files.isDirectory(dir)) throw new OoaException("WORKSPACE.NOT_FOUND", "Folder not found: " + parent);
        Path p = dir.resolve(checkName(name, false));
        if (Files.exists(p)) throw new OoaException("WORKSPACE.EXISTS", "'" + name + "' already exists here", "Choose another name.");
        Files.createDirectory(p);
        return rel(p);
    }

    String createBot(String parent, String name, String kind, JsonWriter writer) throws IOException {
        String base = checkName(name.endsWith(BOT_SUFFIX) ? name.substring(0, name.length() - BOT_SUFFIX.length()) : name, false);
        Path dir = resolve(parent);
        if (!Files.isDirectory(dir)) throw new OoaException("WORKSPACE.NOT_FOUND", "Folder not found: " + parent);
        Path p = dir.resolve(base + BOT_SUFFIX);
        if (Files.exists(p)) throw new OoaException("WORKSPACE.EXISTS", "A bot named '" + base + "' already exists here", "Choose another name.");
        String id = base.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("$schema", "https://schemas.oneofficeautomation.com/flow/v1.json");
        flow.put("kind", "ApiTask".equals(kind) ? "ApiTask" : "TaskBot");
        flow.put("id", id);
        flow.put("name", base);
        flow.put("description", "");
        flow.put("variables", new ArrayList<>());
        flow.put("steps", new ArrayList<>());
        // Atomic save: write a temporary file, then swap it in, so a crash never leaves a half-written bot.
        Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
        Files.writeString(tmp, writer.write(flow));
        try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
        return rel(p);
    }

    String rename(String path, String newName) throws IOException {
        Path p = resolve(path);
        if (p.equals(root)) throw new OoaException("WORKSPACE.ROOT", "The workspace itself cannot be renamed");
        if (!Files.exists(p)) throw new OoaException("WORKSPACE.NOT_FOUND", "Not found: " + path);
        String n = newName.trim();
        // Keep the item type: "Invoice" for a form stays "Invoice.form.json"; a file keeps its extension unless a new one is typed.
        if (!Files.isDirectory(p)) {
            String old = p.getFileName().toString(), lower = old.toLowerCase(Locale.ROOT);
            String suffix = Stream.of(BOT_SUFFIX, ".form.json", ".process.json", ".doc.json").filter(lower::endsWith).findFirst()
                    .orElse(old.lastIndexOf('.') > 0 ? old.substring(old.lastIndexOf('.')) : "");
            boolean typedExtension = suffix.length() > 0 && !Stream.of(BOT_SUFFIX, ".form.json", ".process.json", ".doc.json").anyMatch(suffix::equals) && n.matches(".*\\.[A-Za-z0-9]{1,8}$");
            if (!suffix.isEmpty() && !n.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT)) && !typedExtension) n = n + suffix;
        }
        Path target = p.resolveSibling(checkName(n, true));
        if (Files.exists(target)) throw new OoaException("WORKSPACE.EXISTS", "'" + n + "' already exists here", "Choose another name.");
        Files.move(p, target);
        return rel(target);
    }

    String move(String path, String toFolder) throws IOException {
        Path p = resolve(path), dest = resolve(toFolder);
        if (p.equals(root)) throw new OoaException("WORKSPACE.ROOT", "The workspace itself cannot be moved");
        if (!Files.isDirectory(dest)) throw new OoaException("WORKSPACE.NOT_FOUND", "Folder not found: " + toFolder);
        if (dest.startsWith(p)) throw new OoaException("WORKSPACE.INTO_ITSELF", "A folder cannot be moved into itself");
        Path target = dest.resolve(p.getFileName());
        if (target.equals(p)) return rel(p);
        if (Files.exists(target)) throw new OoaException("WORKSPACE.EXISTS", "'" + p.getFileName() + "' already exists in the destination", "Rename one of them first.");
        Files.move(p, target);
        return rel(target);
    }

    /** Moves the item to .trash/<timestamp>_<name>; restore by moving it back in File Explorer. */
    String delete(String path) throws IOException {
        Path p = resolve(path);
        if (p.equals(root)) throw new OoaException("WORKSPACE.ROOT", "The workspace itself cannot be deleted");
        if (!Files.exists(p)) throw new OoaException("WORKSPACE.NOT_FOUND", "Not found: " + path);
        Path trash = root.resolve(".trash");
        Files.createDirectories(trash);
        Path target = trash.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + p.getFileName());
        Files.move(p, target);
        return rel(target);
    }

    /** First start: copy the example bots and data so there is something to open. */
    void seedIfEmpty(Path examples) throws IOException {
        try (Stream<Path> s = Files.list(root)) { if (s.findAny().isPresent()) return; }
        if (examples == null || !Files.isDirectory(examples)) return;
        Path bots = examples.resolve("bots"), data = examples.resolve("data");
        Path target = Files.createDirectories(root.resolve("Examples").resolve("Refunds"));
        Files.createDirectories(root.resolve("My Bots"));
        if (Files.isDirectory(bots)) try (Stream<Path> s = Files.list(bots)) {
            for (Path b : s.filter(x -> x.toString().endsWith(BOT_SUFFIX)).toList()) Files.copy(b, target.resolve(b.getFileName()));
        }
        if (Files.isDirectory(data)) {
            Path d = Files.createDirectories(root.resolve("data"));
            try (Stream<Path> s = Files.list(data)) {
                for (Path f : s.filter(x -> !x.getFileName().toString().equals("refunds.json")).toList()) Files.copy(f, d.resolve(f.getFileName()));
            }
        }
    }

    interface JsonWriter { String write(Object value); }
}
