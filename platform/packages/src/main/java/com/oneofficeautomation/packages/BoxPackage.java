package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Box Content API (2.0): folders, files, search, shared links and collaborations. Sign-in with an OAuth access token. */
@OoaPackage(id = "ooa.box", name = "Box", version = "1.0.0",
        description = "Work with Box folders and files: upload, download, copy, move, search, share links and invite collaborators.")
public final class BoxPackage {
    static final String P = "BOX";
    private static final Pattern ID = Pattern.compile("^\\d{1,30}$");
    private static final String D_URL = "Box API address. Leave the default unless your administrator gives you another.";
    private static final String D_UPLOAD = "Box upload address. Leave the default unless your administrator gives you another.";
    private static final String D_TOKEN = "Box access token from the vault.";
    private static final String D_AS_USER = "Act on behalf of this Box user id (needs an admin or service account token). Leave empty to act as yourself.";
    private static final String DEF_API = "\"https://api.box.com\"";
    private static final String DEF_UPLOAD = "\"https://upload.box.com\"";
    private static final List<String> ITEM_COLS = List.of("id", "type", "name", "size", "modified_at", "parent_id");
    private static final String ITEM_FIELDS = "id,type,name,size,modified_at,parent";

    private static String api(String base) { return base.replaceAll("/+$", "") + "/2.0"; }
    private static String uploadApi(String base) { return base.replaceAll("/+$", "") + "/api/2.0"; }

    private static String id(String kind, String v) {
        if (v == null || !ID.matcher(v.trim()).matches())
            throw new ActionException(P + ".BAD_ID", "Invalid " + kind + " id '" + v + "'", "Box ids are numbers; open the item in Box and copy the number at the end of the address. The root folder is 0.");
        return v.trim();
    }

    private static String itemPath(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "file" -> "files";
            case "folder" -> "folders";
            default -> throw new ActionException(P + ".BAD_ITEM_TYPE", "Item type must be file or folder, not '" + type + "'", "Choose file or folder.");
        };
    }

    private static HttpJson.Req req(String method, String url, Object token, String asUser) {
        HttpJson.Req r = HttpJson.req(P, method, url).bearer(token);
        if (asUser != null && !asUser.isBlank()) r.header("As-User", id("user", asUser));
        return r;
    }

    private static List<Object> itemRow(Map<?, ?> m) {
        Object parent = m.get("parent") instanceof Map<?, ?> p ? p.get("id") : null;
        return new ArrayList<>(Arrays.asList(m.get("id"), m.get("type"), m.get("name"), m.get("size"), m.get("modified_at"), parent));
    }

    /** Offset paging used by folder listings and search. */
    private static List<Object> offsetPages(ActionContext ctx, String url, int max, Object token, String asUser) {
        List<Object> rows = new ArrayList<>();
        int offset = 0;
        while (rows.size() < max) {
            int limit = Math.min(Math.min(max - rows.size(), 200), 1000);
            String sep = url.contains("?") ? "&" : "?";
            Map<String, Object> r = req("GET", url + sep + "limit=" + limit + "&offset=" + offset, token, asUser).timeout(120).send(ctx).map();
            List<?> entries = (List<?>) r.getOrDefault("entries", List.of());
            for (Object o : entries) { rows.add(itemRow((Map<?, ?>) o)); if (rows.size() >= max) break; }
            int total = r.get("total_count") instanceof BigDecimal t ? t.intValue() : 0;
            offset += entries.size();
            if (entries.isEmpty() || offset >= total) break;
        }
        return rows;
    }

    // ----- folders -----

    @Action(id = "create_folder", name = "Create folder", category = "Folders", capabilities = "network",
            description = "Create a folder inside another folder and return its id.",
            panel = "access_token, parent_folder_id, name | reuse_existing, as_user, base_url")
    @Output(name = "folder_id", type = OoaType.STRING, description = "Id of the new (or existing) folder.")
    public Map<String, Object> createFolder(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Name of the new folder.") String name,
            @Input(name = "parent_folder_id", type = OoaType.STRING, required = false, defaultValue = "\"0\"", description = "Folder to create it in. 0 is the top level of All Files.") String parent,
            @Input(name = "reuse_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "If a folder with this name is already there, return its id instead of failing.") boolean reuse,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        try {
            Map<String, Object> r = req("POST", api(base) + "/folders", token, asUser).json(Map.of("name", name, "parent", Map.of("id", id("folder", parent)))).send(ctx).map();
            return Out.of("folder_id", r.get("id"));
        } catch (ActionException e) {
            if (reuse && isConflict(e)) {
                for (Object o : offsetPages(ctx, api(base) + "/folders/" + parent + "/items?fields=" + ITEM_FIELDS, 100_000, token, asUser)) {
                    List<?> row = (List<?>) o;
                    if ("folder".equals(row.get(1)) && name.equalsIgnoreCase(String.valueOf(row.get(2)))) return Out.of("folder_id", row.get(0));
                }
            }
            if (isConflict(e))
                throw new ActionException(P + ".ALREADY_EXISTS", "An item named '" + name + "' is already in folder " + parent, "Pick another name or turn on reuse_existing.");
            throw e;
        }
    }

    @Action(id = "list_folder", name = "List folder items", category = "Folders", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the files, folders and web links inside a folder.",
            panel = "access_token, folder_id | max_results, as_user, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, type, name, size, modified_at, parent_id.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items returned.")
    public Map<String, Object> listFolder(ActionContext ctx,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, defaultValue = "\"0\"", description = "Folder to look in. 0 is the top level of All Files.") String folder,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most items to return.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        List<Object> rows = offsetPages(ctx, api(base) + "/folders/" + id("folder", folder) + "/items?fields=" + ITEM_FIELDS, max, token, asUser);
        return Out.of("table", Values.table(ITEM_COLS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "delete_folder", name = "Delete folder", category = "Folders", capabilities = "network",
            description = "Move a folder to the Box trash. A folder that still has content is only removed when you allow it.",
            panel = "access_token, folder_id, include_contents | as_user, base_url")
    public Map<String, Object> deleteFolder(ActionContext ctx,
            @Input(name = "folder_id", type = OoaType.STRING, description = "Folder to delete.") String folder,
            @Input(name = "include_contents", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete everything inside the folder.") boolean recursive,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        String fid = id("folder", folder);
        if (fid.equals("0")) throw new ActionException(P + ".BAD_ID", "The top-level folder cannot be deleted", "Choose a sub-folder.");
        req("DELETE", api(base) + "/folders/" + fid + "?recursive=" + recursive, token, asUser).send(ctx);
        return Map.of();
    }

    // ----- files -----

    @Action(id = "upload_file", name = "Upload file", category = "Files", timeoutSeconds = 1800, capabilities = {"network", "filesystem"},
            description = "Upload a local file to a Box folder. Can replace the existing file of the same name as a new version.",
            panel = "access_token, folder_id, local_path, file_name, replace_existing | as_user, upload_url, base_url")
    @Output(name = "file_id", type = OoaType.STRING, description = "Id of the uploaded file.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "local_path", type = OoaType.FILE, description = "File on this machine to upload.") String path,
            @Input(name = "folder_id", type = OoaType.STRING, required = false, defaultValue = "\"0\"", description = "Box folder to upload into. 0 is the top level of All Files.") String folder,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = "Name to give the file in Box. Leave empty to keep the local name.") String fileName,
            @Input(name = "replace_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "If a file of this name is already there, upload as its new version instead of failing.") boolean replace,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "upload_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_UPLOAD, description = D_UPLOAD) String uploadBase,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        Path p = ctx.resolvePath(path);
        byte[] data = HttpJson.readFile(p);
        String name = fileName == null || fileName.isBlank() ? p.getFileName().toString() : fileName;
        Map<String, Object> attrMap = new LinkedHashMap<>();
        attrMap.put("name", name);
        attrMap.put("parent", Map.of("id", id("folder", folder)));
        String attrs = JsonLite.write(attrMap);
        String fid = id("folder", folder);
        try {
            return Out.of("file_id", firstEntryId(sendUpload(ctx, uploadApi(uploadBase) + "/files/content", attrs, name, data, token, asUser)));
        } catch (ActionException e) {
            if (!isConflict(e)) throw e;
            if (!replace) throw new ActionException(P + ".ALREADY_EXISTS", "A file named '" + name + "' is already in folder " + fid, "Turn on replace_existing to upload a new version.");
            Object existing = null;
            for (Object o : offsetPages(ctx, api(base) + "/folders/" + fid + "/items?fields=" + ITEM_FIELDS, 100_000, token, asUser)) {
                List<?> row = (List<?>) o;
                if ("file".equals(row.get(1)) && name.equalsIgnoreCase(String.valueOf(row.get(2)))) { existing = row.get(0); break; }
            }
            if (existing == null) throw e;
            String vattrs = JsonLite.write(Map.of("name", name));
            return Out.of("file_id", firstEntryId(sendUpload(ctx, uploadApi(uploadBase) + "/files/" + existing + "/content", vattrs, name, data, token, asUser)));
        }
    }

    private static Map<String, Object> sendUpload(ActionContext ctx, String url, String attrs, String name, byte[] data, Object token, String asUser) {
        Object[] mp = HttpJson.multipart("form-data", List.of(
                new Object[]{"Content-Disposition: form-data; name=\"attributes\"\r\nContent-Type: application/json", attrs.getBytes(java.nio.charset.StandardCharsets.UTF_8)},
                new Object[]{"Content-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "") + "\"\r\nContent-Type: application/octet-stream", data}));
        return req("POST", url, token, asUser).bytes((byte[]) mp[0], (String) mp[1]).timeout(1800).send(ctx).map();
    }

    private static Object firstEntryId(Map<String, Object> r) {
        return r.get("entries") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m ? m.get("id") : r.get("id");
    }

    private static boolean isConflict(ActionException e) {
        return e.details() != null && BigDecimal.valueOf(409).equals(e.details().get("status"));
    }

    @Action(id = "download_file", name = "Download file", category = "Files", timeoutSeconds = 1800, capabilities = {"network", "filesystem"},
            description = "Save a Box file to this machine.",
            panel = "access_token, file_id, save_as, overwrite | as_user, base_url")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the saved file.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "file_id", type = OoaType.STRING, description = "Box file to download.") String file,
            @Input(name = "save_as", type = OoaType.FILE, description = "Where to save it, e.g. downloads/invoice.pdf. Missing folders are created.") String saveAs,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a local file that is already at save_as; when off, the step fails instead.") boolean overwrite) {
        String fid = id("file", file);
        Path out = ctx.resolvePath(saveAs);
        if (!overwrite && Files.exists(out))
            throw new ActionException(P + ".FILE_EXISTS", "A file already exists at " + out, "Choose another save_as path or turn on overwrite.");
        byte[] data = req("GET", api(base) + "/files/" + fid + "/content", token, asUser).binaryResponse().timeout(1800).send(ctx).bytes();
        HttpJson.writeFile(out, data);
        return Out.of("path", out.toString());
    }

    /** Earlier signature, kept for callers written before the overwrite option. */
    public Map<String, Object> download(ActionContext ctx, String file, String saveAs, Object token, String asUser, String base) {
        return download(ctx, file, saveAs, token, asUser, base, true);
    }

    @Action(id = "copy_file", name = "Copy file", category = "Files", capabilities = "network",
            description = "Copy a file into a folder, optionally under a new name. Can replace a file of the same name there by adding the copy as its new version.",
            panel = "access_token, file_id, target_folder_id, new_name, overwrite | as_user, upload_url, base_url")
    @Output(name = "file_id", type = OoaType.STRING, description = "Id of the copy.")
    public Map<String, Object> copy(ActionContext ctx,
            @Input(name = "file_id", type = OoaType.STRING, description = "File to copy.") String file,
            @Input(name = "target_folder_id", type = OoaType.STRING, description = "Folder that receives the copy.") String folder,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "Name for the copy. Leave empty to keep the same name.") String newName,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "If a file of the same name is already in the target folder, upload the copy as a new version of that file instead of failing.") boolean overwrite,
            @Input(name = "upload_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_UPLOAD, description = D_UPLOAD) String uploadBase) {
        String fid = id("file", file), target = id("folder", folder);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", Map.of("id", target));
        if (newName != null && !newName.isBlank()) body.put("name", newName);
        try {
            return Out.of("file_id", req("POST", api(base) + "/files/" + fid + "/copy", token, asUser).json(body).send(ctx).map().get("id"));
        } catch (ActionException e) {
            if (!isConflict(e)) throw e;
            String name = newName != null && !newName.isBlank() ? newName : fileName(ctx, fid, token, asUser, base);
            if (!overwrite) throw new ActionException(P + ".ALREADY_EXISTS", "A file named '" + name + "' is already in folder " + target, "Turn on overwrite to add the copy as a new version.");
            Object existing = fileInFolder(ctx, target, name, token, asUser, base);
            if (existing == null) throw e;
            if (fid.equals(Values.text(existing))) return Out.of("file_id", existing);
            byte[] data = req("GET", api(base) + "/files/" + fid + "/content", token, asUser).binaryResponse().timeout(1800).send(ctx).bytes();
            String vattrs = JsonLite.write(Map.of("name", name));
            String up = uploadBase == null || uploadBase.isBlank() ? "https://upload.box.com" : uploadBase;
            return Out.of("file_id", firstEntryId(sendUpload(ctx, uploadApi(up) + "/files/" + existing + "/content", vattrs, name, data, token, asUser)));
        }
    }

    /** Earlier signature, kept for callers written before the overwrite option. */
    public Map<String, Object> copy(ActionContext ctx, String file, String folder, String newName, Object token, String asUser, String base) {
        return copy(ctx, file, folder, newName, token, asUser, base, false, "https://upload.box.com");
    }

    private static String fileName(ActionContext ctx, String fid, Object token, String asUser, String base) {
        return Values.text(req("GET", api(base) + "/files/" + fid + "?fields=name", token, asUser).send(ctx).map().get("name"));
    }

    /** Id of the file with this name (any case) directly in a folder, or null. */
    private static Object fileInFolder(ActionContext ctx, String folder, String name, Object token, String asUser, String base) {
        for (Object o : offsetPages(ctx, api(base) + "/folders/" + folder + "/items?fields=" + ITEM_FIELDS, 100_000, token, asUser)) {
            List<?> row = (List<?>) o;
            if ("file".equals(row.get(1)) && name.equalsIgnoreCase(String.valueOf(row.get(2)))) return row.get(0);
        }
        return null;
    }

    @Action(id = "move_file", name = "Move file", category = "Files", capabilities = "network",
            description = "Move a file to another folder. Can replace a file of the same name there, which then goes to the Box trash.",
            panel = "access_token, file_id, target_folder_id, overwrite | as_user, base_url")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "file_id", type = OoaType.STRING, description = "File to move.") String file,
            @Input(name = "target_folder_id", type = OoaType.STRING, description = "Folder to move it into.") String folder,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "If a file of the same name is already in the target folder, move that file to the trash and then move this one.") boolean overwrite) {
        String fid = id("file", file), target = id("folder", folder);
        try {
            req("PUT", api(base) + "/files/" + fid, token, asUser).json(Map.of("parent", Map.of("id", target))).send(ctx);
        } catch (ActionException e) {
            if (!isConflict(e)) throw e;
            String name = fileName(ctx, fid, token, asUser, base);
            if (!overwrite) throw new ActionException(P + ".ALREADY_EXISTS", "A file named '" + name + "' is already in folder " + target, "Turn on overwrite to replace it.");
            Object existing = fileInFolder(ctx, target, name, token, asUser, base);
            if (existing == null || fid.equals(Values.text(existing))) throw e;
            req("DELETE", api(base) + "/files/" + existing, token, asUser).send(ctx);
            req("PUT", api(base) + "/files/" + fid, token, asUser).json(Map.of("parent", Map.of("id", target))).send(ctx);
        }
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the overwrite option. */
    public Map<String, Object> move(ActionContext ctx, String file, String folder, Object token, String asUser, String base) {
        return move(ctx, file, folder, token, asUser, base, false);
    }

    @Action(id = "rename_file", name = "Rename file", category = "Files", capabilities = "network", description = "Give a file a new name in the same folder.",
            panel = "access_token, file_id, new_name | as_user, base_url")
    public Map<String, Object> rename(ActionContext ctx,
            @Input(name = "file_id", type = OoaType.STRING, description = "File to rename.") String file,
            @Input(name = "new_name", type = OoaType.STRING, description = "New file name including the extension, e.g. Q4-report.xlsx.") String newName,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        req("PUT", api(base) + "/files/" + id("file", file), token, asUser).json(Map.of("name", newName)).send(ctx);
        return Map.of();
    }

    @Action(id = "delete_file", name = "Delete file", category = "Files", capabilities = "network", description = "Move a file to the Box trash, or delete it for good.",
            panel = "access_token, file_id | permanently, as_user, base_url")
    public Map<String, Object> deleteFile(ActionContext ctx,
            @Input(name = "file_id", type = OoaType.STRING, description = "File to delete.") String file,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base,
            @Input(name = "permanently", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Also remove the file from the trash, so it cannot be restored.") boolean permanently) {
        String fid = id("file", file);
        req("DELETE", api(base) + "/files/" + fid, token, asUser).send(ctx);
        if (permanently) req("DELETE", api(base) + "/files/" + fid + "/trash", token, asUser).send(ctx);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the permanently option. */
    public Map<String, Object> deleteFile(ActionContext ctx, String file, Object token, String asUser, String base) {
        return deleteFile(ctx, file, token, asUser, base, false);
    }

    @Action(id = "get_file_info", name = "Get file info", category = "Files", idempotent = true, capabilities = "network", description = "Read a file's name, size, dates, owner and folder.",
            panel = "access_token, file_id | as_user, base_url")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "id, name, size, created_at, modified_at, sha1, parent_id, parent_name, owner, shared_link.")
    public Map<String, Object> info(ActionContext ctx,
            @Input(name = "file_id", type = OoaType.STRING, description = "File to look up.") String file,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        Map<String, Object> r = req("GET", api(base) + "/files/" + id("file", file) + "?fields=id,name,size,created_at,modified_at,sha1,parent,owned_by,shared_link", token, asUser).send(ctx).map();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", r.get("id"));
        o.put("name", r.get("name"));
        o.put("size", r.get("size"));
        o.put("created_at", r.get("created_at"));
        o.put("modified_at", r.get("modified_at"));
        o.put("sha1", r.get("sha1"));
        o.put("parent_id", r.get("parent") instanceof Map<?, ?> p ? p.get("id") : null);
        o.put("parent_name", r.get("parent") instanceof Map<?, ?> p ? p.get("name") : null);
        o.put("owner", r.get("owned_by") instanceof Map<?, ?> ob ? ob.get("login") : null);
        o.put("shared_link", r.get("shared_link") instanceof Map<?, ?> sl ? sl.get("url") : null);
        return Out.of("info", o);
    }

    // ----- search -----

    @Action(id = "search", name = "Search", category = "Search", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Find files and folders by name, content or description.",
            panel = "access_token, query, file_extensions, in_folder_id, item_type | max_results, as_user, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, type, name, size, modified_at, parent_id.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of results.")
    public Map<String, Object> search(ActionContext ctx,
            @Input(name = "query", type = OoaType.STRING, example = "invoice 2026", description = "Words to look for.") String query,
            @Input(name = "item_type", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "file", "folder"}, description = "Only files, only folders, or both.") String type,
            @Input(name = "in_folder_id", type = OoaType.STRING, required = false, description = "Only search inside this folder and its sub-folders.") String folder,
            @Input(name = "file_extensions", type = OoaType.STRING, required = false, example = "pdf,docx", description = "Only these file types, separated by commas.") String ext,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "200", description = "Most results to return.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("query", query);
        if (type != null && !type.equals("any")) q.put("type", type);
        if (folder != null && !folder.isBlank()) q.put("ancestor_folder_ids", id("folder", folder));
        if (ext != null && !ext.isBlank()) q.put("file_extensions", ext.replace(" ", "").replace(".", ""));
        q.put("fields", ITEM_FIELDS);
        List<Object> rows = offsetPages(ctx, api(base) + "/search?" + HttpJson.query(q), max, token, asUser);
        return Out.of("table", Values.table(ITEM_COLS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    // ----- sharing -----

    @Action(id = "create_shared_link", name = "Create shared link", category = "Sharing", capabilities = "network",
            description = "Create or change the shared link of a file or folder and return the link.",
            panel = "access_token, item_type, item_id, access, allow_download, [Set an end date: expires_at] | as_user, base_url")
    @Output(name = "url", type = OoaType.STRING, description = "Shared link to the item.")
    @Output(name = "download_url", type = OoaType.STRING, description = "Direct download link (files only, when downloads are allowed).")
    public Map<String, Object> sharedLink(ActionContext ctx,
            @Input(name = "item_type", type = OoaType.STRING, enumValues = {"file", "folder"}, description = "Whether the item is a file or a folder.") String type,
            @Input(name = "item_id", type = OoaType.STRING, description = "Id of the file or folder.") String item,
            @Input(name = "access", type = OoaType.STRING, required = false, defaultValue = "\"company\"", enumValues = {"open", "company", "collaborators"},
                    description = "Who can use the link: open (anyone with the link), company (people in your organization) or collaborators (invited people only).") String access,
            @Input(name = "allow_download", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Let people with the link download the item.") boolean download,
            @Input(name = "expires_at", type = OoaType.DATETIME, required = false, description = "When the link stops working (UTC). Leave empty for no end date.") Object expires,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("access", access);
        if (!"collaborators".equals(access)) link.put("permissions", Map.of("can_download", download));
        if (expires != null && !Values.text(expires).isBlank()) link.put("unshared_at", Values.toDateTime(expires).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
        Map<String, Object> r = req("PUT", api(base) + "/" + itemPath(type) + "/" + id(type, item) + "?fields=shared_link", token, asUser).json(Map.of("shared_link", link)).send(ctx).map();
        Map<?, ?> sl = r.get("shared_link") instanceof Map<?, ?> m ? m : Map.of();
        return Out.of("url", sl.get("url"), "download_url", sl.get("download_url"));
    }

    @Action(id = "remove_shared_link", name = "Remove shared link", category = "Sharing", capabilities = "network", description = "Turn off the shared link of a file or folder.",
            panel = "access_token, item_type, item_id | as_user, base_url")
    public Map<String, Object> removeSharedLink(ActionContext ctx,
            @Input(name = "item_type", type = OoaType.STRING, enumValues = {"file", "folder"}, description = "Whether the item is a file or a folder.") String type,
            @Input(name = "item_id", type = OoaType.STRING, description = "Id of the file or folder.") String item,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        Map<String, Object> body = new HashMap<>();
        body.put("shared_link", null);
        req("PUT", api(base) + "/" + itemPath(type) + "/" + id(type, item) + "?fields=shared_link", token, asUser).json(body).send(ctx);
        return Map.of();
    }

    @Action(id = "add_collaborator", name = "Invite collaborator", category = "Sharing", capabilities = "network",
            description = "Give a person or group access to a file or folder with a chosen role.",
            panel = "access_token, item_type, item_id, (Invite: A person = email / A group = group_id), role, notify | as_user, base_url")
    @Output(name = "collaboration_id", type = OoaType.STRING, description = "Id of the new collaboration, used to remove it later.")
    public Map<String, Object> addCollaborator(ActionContext ctx,
            @Input(name = "item_type", type = OoaType.STRING, enumValues = {"file", "folder"}, description = "Whether the item is a file or a folder.") String type,
            @Input(name = "item_id", type = OoaType.STRING, description = "Id of the file or folder.") String item,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Email address of the person to invite.") String email,
            @Input(name = "group_id", type = OoaType.STRING, required = false, description = "Box group id to invite instead of a person.") String group,
            @Input(name = "role", type = OoaType.STRING, required = false, defaultValue = "\"viewer\"",
                    enumValues = {"editor", "viewer", "previewer", "uploader", "previewer uploader", "viewer uploader", "co-owner"}, description = "What they may do with the item.") String role,
            @Input(name = "notify", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send them an email about the invitation.") boolean notify,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        Map<String, Object> who;
        if (group != null && !group.isBlank()) who = Map.of("type", "group", "id", id("group", group));
        else if (email != null && !email.isBlank()) who = Map.of("type", "user", "login", email.trim());
        else throw new ActionException(P + ".NO_COLLABORATOR", "Give an email address or a group id", "Fill email or group_id.");
        itemPath(type);
        Map<String, Object> body = Map.of("item", Map.of("type", type.toLowerCase(Locale.ROOT), "id", id(type, item)), "accessible_by", who, "role", role);
        return Out.of("collaboration_id", req("POST", api(base) + "/collaborations?notify=" + notify, token, asUser).json(body).send(ctx).map().get("id"));
    }

    @Action(id = "list_collaborators", name = "List collaborators", category = "Sharing", idempotent = true, capabilities = "network",
            description = "Return who has been invited to a file or folder and their role.",
            panel = "access_token, item_type, item_id | as_user, base_url")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: collaboration_id, name, login, type, role, status.")
    public Map<String, Object> listCollaborators(ActionContext ctx,
            @Input(name = "item_type", type = OoaType.STRING, enumValues = {"file", "folder"}, description = "Whether the item is a file or a folder.") String type,
            @Input(name = "item_id", type = OoaType.STRING, description = "Id of the file or folder.") String item,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        List<Object> rows = new ArrayList<>();
        String marker = null;
        do {
            String url = api(base) + "/" + itemPath(type) + "/" + id(type, item) + "/collaborations?limit=100" + (marker == null ? "" : "&marker=" + HttpJson.enc(marker));
            Map<String, Object> r = req("GET", url, token, asUser).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("entries", List.of())) {
                Map<?, ?> c = (Map<?, ?>) o;
                Map<?, ?> who = c.get("accessible_by") instanceof Map<?, ?> m ? m : Map.of();
                rows.add(new ArrayList<>(Arrays.asList(c.get("id"), who.get("name"), who.get("login"), who.get("type"), c.get("role"), c.get("status"))));
            }
            Object next = r.get("next_marker");
            marker = next == null || Values.text(next).isBlank() ? null : Values.text(next);
        } while (marker != null);
        return Out.of("table", Values.table(List.of("collaboration_id", "name", "login", "type", "role", "status"), rows));
    }

    @Action(id = "remove_collaborator", name = "Remove collaborator", category = "Sharing", capabilities = "network",
            description = "Take away someone's access that was given by an invitation.",
            panel = "access_token, collaboration_id | as_user, base_url")
    public Map<String, Object> removeCollaborator(ActionContext ctx,
            @Input(name = "collaboration_id", type = OoaType.STRING, description = "Id from Invite collaborator or List collaborators.") String collab,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "as_user", type = OoaType.STRING, required = false, description = D_AS_USER) String asUser,
            @Input(name = "base_url", type = OoaType.STRING, required = false, pattern = "^https?://", defaultValue = DEF_API, description = D_URL) String base) {
        req("DELETE", api(base) + "/collaborations/" + id("collaboration", collab), token, asUser).send(ctx);
        return Map.of();
    }
}
