package com.oneofficeautomation.packages.transfer;

import com.jcraft.jsch.*;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.commons.net.ftp.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** FTP, FTPS and SFTP: list, upload, download, rename, delete and create folders. */
@OoaPackage(id = "ooa.file_transfer", name = "FTP / SFTP", version = "1.0.0", description = "Connect to FTP, FTPS or SFTP servers to list, upload, download, rename and delete files.")
public final class FileTransferPackage {

    interface Conn extends AutoCloseable {
        List<Object> list(String dir) throws Exception;
        void download(String remote, Path local) throws Exception;
        void upload(Path local, String remote) throws Exception;
        void delete(String remote) throws Exception;
        void rename(String from, String to) throws Exception;
        void mkdir(String dir) throws Exception;
        void rmdir(String dir) throws Exception;
        void cd(String dir) throws Exception;
        String pwd() throws Exception;
        @Override void close();
    }

    static final class Ftp implements Conn {
        final FTPClient c;
        Ftp(FTPClient c) { this.c = c; }
        private void check(boolean ok, String what) throws IOException { if (!ok) throw new IOException(what + " failed: " + c.getReplyString().trim()); }
        public List<Object> list(String dir) throws IOException {
            List<Object> rows = new ArrayList<>();
            for (FTPFile f : c.listFiles(dir)) {
                if (f == null || f.getName().equals(".") || f.getName().equals("..")) continue;
                rows.add(new ArrayList<>(Arrays.asList(f.getName(), f.isDirectory(), BigDecimal.valueOf(f.getSize()),
                        f.getTimestamp() == null ? null : LocalDateTime.ofInstant(f.getTimestamp().toInstant(), ZoneOffset.UTC))));
            }
            return rows;
        }
        public void download(String r, Path l) throws IOException { try (OutputStream o = Files.newOutputStream(l)) { check(c.retrieveFile(r, o), "Download of " + r); } }
        public void upload(Path l, String r) throws IOException { try (InputStream i = Files.newInputStream(l)) { check(c.storeFile(r, i), "Upload to " + r); } }
        public void delete(String r) throws IOException { check(c.deleteFile(r), "Delete of " + r); }
        public void rename(String f, String t) throws IOException { check(c.rename(f, t), "Rename of " + f); }
        public void mkdir(String d) throws IOException { check(c.makeDirectory(d), "Create folder " + d); }
        public void rmdir(String d) throws IOException { check(c.removeDirectory(d), "Delete folder " + d); }
        public void cd(String d) throws IOException { check(c.changeWorkingDirectory(d), "Change folder to " + d); }
        public String pwd() throws IOException { String p = c.printWorkingDirectory(); if (p == null) throw new IOException("Cannot read the current folder: " + c.getReplyString().trim()); return p; }
        public void close() { try { c.logout(); } catch (IOException ignored) { } try { c.disconnect(); } catch (IOException ignored) { } }
    }

    static final class Sftp implements Conn {
        final com.jcraft.jsch.Session s; final ChannelSftp ch;
        Sftp(com.jcraft.jsch.Session s, ChannelSftp ch) { this.s = s; this.ch = ch; }
        public List<Object> list(String dir) throws SftpException {
            List<Object> rows = new ArrayList<>();
            for (Object o : ch.ls(dir == null || dir.isBlank() ? "." : dir)) {
                ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
                if (e.getFilename().equals(".") || e.getFilename().equals("..")) continue;
                SftpATTRS a = e.getAttrs();
                rows.add(new ArrayList<>(Arrays.asList(e.getFilename(), a.isDir(), BigDecimal.valueOf(a.getSize()), LocalDateTime.ofEpochSecond(a.getMTime(), 0, ZoneOffset.UTC))));
            }
            return rows;
        }
        public void download(String r, Path l) throws SftpException { ch.get(r, l.toString()); }
        public void upload(Path l, String r) throws SftpException { ch.put(l.toString(), r); }
        public void delete(String r) throws SftpException { ch.rm(r); }
        public void rename(String f, String t) throws SftpException { ch.rename(f, t); }
        public void mkdir(String d) throws SftpException { ch.mkdir(d); }
        public void rmdir(String d) throws SftpException { ch.rmdir(d); }
        public void cd(String d) throws SftpException { ch.cd(d); }
        public String pwd() throws SftpException { return ch.pwd(); }
        public void close() { ch.disconnect(); s.disconnect(); }
    }

    private static Conn conn(ActionContext ctx, String name) { return ctx.session(name, Conn.class); }

    private static ActionException fail(Exception e, String what) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof JSchException && m.contains("Auth")) return new ActionException("TRANSFER.AUTH_FAILED", "Sign-in failed: " + m, "Check the user, password or private key.");
        if (e instanceof SftpException se && se.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return new ActionException("TRANSFER.NOT_FOUND", what + ": no such file or folder", "Check the remote path.");
        if (e instanceof SftpException se && se.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) return new ActionException("TRANSFER.ACCESS_DENIED", what + ": permission denied", null);
        if (e instanceof IOException && !(e instanceof FileNotFoundException)) return new ActionException("TRANSFER.FAILED", what + ": " + m, "Check the connection and paths.", true);
        return new ActionException("TRANSFER.FAILED", what + ": " + m, null);
    }

    @Action(id = "connect", name = "Connect", category = "Connection", timeoutSeconds = 120, capabilities = "network", description = "Connect and sign in to an FTP, FTPS or SFTP server.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "protocol", type = OoaType.STRING, enumValues = {"sftp", "ftps", "ftp"}, description = "sftp (SSH), ftps (FTP over TLS) or plain ftp.") String protocol,
            @Input(name = "host", type = OoaType.STRING, description = "Server name or address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, min = 1, max = 65535, description = "Port; 22 for SFTP, 21 for FTP/FTPS when empty.") Integer port,
            @Input(name = "user", type = OoaType.STRING, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "private_key", type = OoaType.FILE, required = false, visibleWhen = "protocol=sftp", description = "SFTP private key file (instead of a password).") String key,
            @Input(name = "key_passphrase", type = OoaType.CREDENTIAL, required = false, visibleWhen = "protocol=sftp", description = "Private key passphrase.") Object keyPass,
            @Input(name = "host_key_fingerprint", type = OoaType.STRING, required = false, visibleWhen = "protocol=sftp", description = "Expected SFTP server fingerprint (SHA256:...); strongly recommended.") String fingerprint,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session,
            @Input(name = "passive_mode", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "protocol=ftp|ftps", description = "FTP/FTPS: the client opens the data connections (works through most firewalls); false uses active mode.") boolean passive,
            @Input(name = "implicit_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "protocol=ftps", description = "FTPS: use implicit TLS (usually port 990) instead of explicit TLS after connecting.") boolean implicit,
            @Input(name = "transfer_mode", type = OoaType.STRING, required = false, defaultValue = "\"binary\"", enumValues = {"binary", "ascii"},
                    visibleWhen = "protocol=ftp|ftps", description = "FTP/FTPS: binary copies files byte for byte; ascii converts line endings of text files.") String mode,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "20", description = "How long to wait when connecting and for each server reply.") int timeout) {
        String pw = password instanceof SecureString s ? s.reveal() : null;
        String tm = mode == null || mode.isBlank() ? "binary" : mode.strip().toLowerCase(Locale.ROOT);
        if (!tm.equals("binary") && !tm.equals("ascii")) throw new ActionException("TRANSFER.BAD_MODE", "Unknown transfer_mode '" + mode + "'", "Use binary or ascii.");
        if (implicit && !protocol.equals("ftps")) throw new ActionException("TRANSFER.BAD_OPTION", "implicit_tls applies to ftps only", "Set protocol to ftps or turn off implicit_tls.");
        int ms = (int) Math.max(1000, Math.min(600_000L, timeout * 1000L));
        try {
            Conn c;
            if (protocol.equals("sftp")) {
                JSch j = new JSch();
                if (key != null && !key.isBlank()) {
                    if (keyPass instanceof SecureString kp) j.addIdentity(ctx.resolvePath(key).toString(), kp.reveal()); else j.addIdentity(ctx.resolvePath(key).toString());
                }
                com.jcraft.jsch.Session s = j.getSession(user, host, port == null ? 22 : port);
                if (pw != null) s.setPassword(pw);
                s.setConfig("StrictHostKeyChecking", "no");
                s.setTimeout(ms);
                s.connect(ms);
                String actual = s.getHostKey().getFingerPrint(j);
                if (fingerprint != null && !fingerprint.isBlank()) {
                    if (!fingerprint.trim().equalsIgnoreCase(actual)) { s.disconnect(); throw new ActionException("TRANSFER.HOST_KEY_MISMATCH",
                            "Server fingerprint " + actual + " does not match the expected one", "Possible man-in-the-middle attack; confirm the fingerprint with the server owner."); }
                } else ctx.warn("SFTP host key not pinned (server fingerprint " + actual + "); set host_key_fingerprint to verify the server.");
                ChannelSftp ch = (ChannelSftp) s.openChannel("sftp");
                ch.connect(ms);
                c = new Sftp(s, ch);
            } else {
                FTPClient f = protocol.equals("ftps") ? new FTPSClient(implicit) : new FTPClient();
                f.setConnectTimeout(ms);
                f.setDefaultTimeout(ms);
                f.connect(host, port == null ? (implicit ? 990 : 21) : port);
                if (!FTPReply.isPositiveCompletion(f.getReplyCode())) { f.disconnect(); throw new IOException("Server refused the connection: " + f.getReplyString()); }
                if (!f.login(user, pw == null ? "" : pw)) { f.disconnect(); throw new ActionException("TRANSFER.AUTH_FAILED", "FTP sign-in failed", "Check the user and password."); }
                if (f instanceof FTPSClient s) { s.execPBSZ(0); s.execPROT("P"); }
                if (passive) f.enterLocalPassiveMode(); else f.enterLocalActiveMode();
                f.setFileType(tm.equals("ascii") ? FTP.ASCII_FILE_TYPE : FTP.BINARY_FILE_TYPE);
                if (protocol.equals("ftp")) ctx.warn("Plain FTP sends passwords unencrypted; prefer SFTP or FTPS.");
                c = new Ftp(f);
            }
            ctx.sessions().put(session, c);
        } catch (ActionException e) { throw e;
        } catch (Exception e) { throw fail(e, "Connecting to " + host); }
        return Map.of();
    }

    @Action(id = "list", name = "List files", category = "Files", idempotent = true, timeoutSeconds = 300, capabilities = "network", description = "List a remote folder.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: name, is_folder, size, modified.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\".\"", description = "Remote folder.") String dir,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", example = "*.csv", description = "Only names matching this wildcard (* and ?).") String pattern,
            @Input(name = "kind", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "files", "folders"}, description = "List files, folders or both.") String kind) {
        java.util.function.Predicate<String> m = wildcard(pattern);
        String k = kind == null || kind.isBlank() ? "all" : kind;
        try {
            List<Object> rows = new ArrayList<>();
            for (Object o : conn(ctx, session).list(dir)) {
                List<?> r = (List<?>) o;
                boolean folder = Boolean.TRUE.equals(r.get(1));
                if (!m.test(String.valueOf(r.get(0))) || (k.equals("files") && folder) || (k.equals("folders") && !folder)) continue;
                rows.add(o);
            }
            return Map.of("table", Values.table(List.of("name", "is_folder", "size", "modified"), rows));
        }
        catch (Exception e) { throw fail(e, "Listing " + dir); }
    }

    @Action(id = "download", name = "Download", category = "Files", timeoutSeconds = 3600, capabilities = {"network", "filesystem"}, description = "Download a remote file.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Remote file.") String remote,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it.") String local,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing local file; when false an existing file stops the step.") boolean overwrite) {
        Path l = ctx.resolvePath(local);
        if (!overwrite && Files.exists(l)) throw new ActionException("FILE.EXISTS", "Local file already exists: " + l, "Set overwrite to true or choose another path.");
        try { if (l.getParent() != null) Files.createDirectories(l.getParent()); conn(ctx, session).download(remote, l); }
        catch (Exception e) { throw fail(e, "Downloading " + remote); }
        return Map.of();
    }

    @Action(id = "upload", name = "Upload", category = "Files", timeoutSeconds = 3600, capabilities = {"network", "filesystem"}, description = "Upload a local file.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "local_path", type = OoaType.FILE, description = "File to upload.") String local,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Remote path including the file name.") String remote,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing remote file; when false an existing file stops the step.") boolean overwrite) {
        Path l = ctx.resolvePath(local);
        if (!Files.isRegularFile(l)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + l, "Check the path.");
        Conn cn = conn(ctx, session);
        if (!overwrite && remoteRow(cn, remote) != null) throw new ActionException("TRANSFER.EXISTS", "Remote file already exists: " + remote, "Set overwrite to true or choose another path.");
        try { cn.upload(l, remote); } catch (Exception e) { throw fail(e, "Uploading to " + remote); }
        return Map.of();
    }

    @Action(id = "delete", name = "Delete", category = "Files", timeoutSeconds = 120, capabilities = "network", description = "Delete a remote file.")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Remote file.") String remote,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        try { conn(ctx, session).delete(remote); } catch (Exception e) { throw fail(e, "Deleting " + remote); }
        return Map.of();
    }

    @Action(id = "rename", name = "Rename or move", category = "Files", timeoutSeconds = 120, capabilities = "network", description = "Rename or move a remote file.")
    public Map<String, Object> rename(ActionContext ctx,
            @Input(name = "from", type = OoaType.STRING, description = "Current path.") String from,
            @Input(name = "to", type = OoaType.STRING, description = "New path.") String to,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        try { conn(ctx, session).rename(from, to); } catch (Exception e) { throw fail(e, "Renaming " + from); }
        return Map.of();
    }

    @Action(id = "create_folder", name = "Create folder", category = "Files", timeoutSeconds = 120, capabilities = "network", description = "Create a remote folder.")
    public Map<String, Object> mkdir(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, description = "Remote folder.") String dir,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        try { conn(ctx, session).mkdir(dir); } catch (Exception e) { throw fail(e, "Creating " + dir); }
        return Map.of();
    }

    // ---------- Working folder and whole folders ----------

    static String join(String dir, String name) {
        if (dir == null || dir.isBlank() || dir.equals(".")) return name;
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static final int MAX_DEPTH = 64;

    /** Remote entry names that would write outside the target folder are refused. */
    static String safeEntry(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains("\0") || name.contains(":"))
            throw new ActionException("TRANSFER.UNSAFE_NAME", "The server listed an unsafe file name '" + name + "'", "The folder was not copied; check the server.");
        return name;
    }

    /** Wildcard match for remote names: * any text, ? one character; case-insensitive. Empty or * matches everything. */
    static java.util.function.Predicate<String> wildcard(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.strip().equals("*")) return n -> true;
        StringBuilder re = new StringBuilder();
        for (char ch : pattern.strip().toCharArray()) re.append(ch == '*' ? ".*" : ch == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(ch)));
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(re.toString(), java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        return n -> n != null && p.matcher(n).matches();
    }

    static String parentOf(String remote) {
        int i = remote.lastIndexOf('/');
        return i < 0 ? "." : i == 0 ? "/" : remote.substring(0, i);
    }

    /** Listing row {name, is_folder, size, modified} of one remote path, or null when it does not exist. */
    static List<?> remoteRow(Conn c, String remote) {
        String name = remote.substring(remote.lastIndexOf('/') + 1);
        try {
            for (Object o : c.list(parentOf(remote))) if (((List<?>) o).get(0).equals(name)) return (List<?>) o;
        } catch (Exception e) { return null; }
        return null;
    }

    static boolean newer(Object remoteModified, Path local) {
        if (!(remoteModified instanceof LocalDateTime t) || !Files.exists(local)) return true;
        try { return t.toInstant(ZoneOffset.UTC).isAfter(Files.getLastModifiedTime(local).toInstant()); } catch (IOException e) { return true; }
    }

    @Action(id = "delete_files", name = "Delete files", category = "Files", timeoutSeconds = 3600, capabilities = "network",
            description = "Delete the files in a remote folder whose names match a wildcard such as *.tmp.")
    @Output(name = "deleted", type = OoaType.LIST, description = "Remote paths of the deleted files.")
    public Map<String, Object> deleteFiles(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, description = "Remote folder.") String dir,
            @Input(name = "pattern", type = OoaType.STRING, example = "*.tmp", description = "Wildcard of the files to delete (* and ?); * deletes every file.") String pattern,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        if (pattern == null || pattern.isBlank()) throw new ActionException("TRANSFER.NO_PATTERN", "Give the wildcard of the files to delete", "Use * to delete every file in the folder.");
        java.util.function.Predicate<String> m = wildcard(pattern);
        Conn c = conn(ctx, session);
        List<Object> done = new ArrayList<>();
        try {
            for (Object o : c.list(dir)) {
                ctx.checkCancelled();
                List<?> row = (List<?>) o;
                String name = safeEntry(String.valueOf(row.get(0)));
                if (Boolean.TRUE.equals(row.get(1)) || !m.test(name)) continue;
                c.delete(join(dir, name));
                done.add(join(dir, name));
            }
        } catch (ActionException e) { throw e;
        } catch (Exception e) { throw fail(e, "Deleting files in " + dir); }
        return Map.of("deleted", done);
    }

    @Action(id = "change_folder", name = "Change folder", category = "Folders", timeoutSeconds = 120, capabilities = "network",
            description = "Change the session's current remote folder; later relative paths start from it.")
    @Output(name = "folder", type = OoaType.STRING, description = "Current remote folder after the change.")
    public Map<String, Object> changeFolder(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, example = "/outgoing/invoices", description = "Remote folder, absolute or relative to the current one.") String dir,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        Conn c = conn(ctx, session);
        try { c.cd(dir); return Map.of("folder", c.pwd()); } catch (Exception e) { throw fail(e, "Changing folder to " + dir); }
    }

    @Action(id = "delete_folder", name = "Delete folder", category = "Folders", timeoutSeconds = 3600, capabilities = "network",
            description = "Delete a remote folder; a folder with contents is deleted only when recursive is true.")
    @Output(name = "deleted_files", type = OoaType.NUMBER, description = "Files deleted inside the folder.")
    public Map<String, Object> deleteFolder(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, description = "Remote folder to delete.") String dir,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete everything inside.") boolean recursive,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        String d = dir == null ? "" : dir.strip();
        if (d.isEmpty() || d.equals(".") || d.equals("/") || d.equals("..") || d.equals("~") || d.matches("/*\\.?/*"))
            throw new ActionException("TRANSFER.PROTECTED", "Refusing to delete '" + dir + "'", "Point to a specific sub-folder.");
        Conn c = conn(ctx, session);
        int[] files = {0};
        try {
            if (!recursive && !c.list(d).isEmpty())
                throw new ActionException("TRANSFER.NOT_EMPTY", "Remote folder is not empty: " + d, "Set recursive to true to delete its contents.");
            removeTree(ctx, c, d, files, 0);
        } catch (ActionException e) { throw e;
        } catch (Exception e) { throw fail(e, "Deleting folder " + d); }
        return Map.of("deleted_files", BigDecimal.valueOf(files[0]));
    }

    private static void removeTree(ActionContext ctx, Conn c, String dir, int[] files, int depth) throws Exception {
        if (depth > MAX_DEPTH) throw new ActionException("TRANSFER.TOO_DEEP", "Folder nesting deeper than " + MAX_DEPTH + " levels under " + dir, null);
        for (Object o : c.list(dir)) {
            ctx.checkCancelled();
            List<?> row = (List<?>) o;
            String child = join(dir, safeEntry(String.valueOf(row.get(0))));
            if (Boolean.TRUE.equals(row.get(1))) removeTree(ctx, c, child, files, depth + 1);
            else { c.delete(child); files[0]++; }
        }
        c.rmdir(dir);
    }

    @Action(id = "upload_folder", name = "Upload folder", category = "Folders", timeoutSeconds = 7200, capabilities = {"network", "filesystem"},
            description = "Upload a local folder with all its files and sub-folders, creating remote folders as needed.")
    @Output(name = "files", type = OoaType.LIST, description = "Remote paths of the uploaded files.")
    public Map<String, Object> uploadFolder(ActionContext ctx,
            @Input(name = "local_folder", type = OoaType.FILE, description = "Local folder to upload.") String local,
            @Input(name = "remote_folder", type = OoaType.STRING, description = "Remote folder that receives the contents.") String remote,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", example = "*.csv", description = "Only files whose names match this wildcard (* and ?).") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also copy sub-folders and their files.") boolean sub,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace files that already exist; when false they are skipped.") boolean overwrite,
            @Input(name = "only_newer", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Copy a file only when it is newer than the existing copy (by modified time).") boolean onlyNewer) {
        Copy opts = new Copy(wildcard(pattern), sub, overwrite, onlyNewer);
        Path l = ctx.resolvePath(local);
        if (!Files.isDirectory(l)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + l, "Check the local folder path.");
        Conn c = conn(ctx, session);
        List<Object> done = new ArrayList<>();
        try { uploadTree(ctx, c, l, remote, done, 0, opts); }
        catch (ActionException e) { throw e;
        } catch (Exception e) { throw fail(e, "Uploading folder " + l); }
        return Map.of("files", done);
    }

    private static void ensureFolder(Conn c, String dir) {
        try { c.mkdir(dir); } catch (Exception ignored) { }  // usually "already exists"; a real problem shows on the first upload
    }

    /** Options of Upload folder and Download folder. */
    record Copy(java.util.function.Predicate<String> match, boolean subfolders, boolean overwrite, boolean onlyNewer) {
        static final Copy ALL = new Copy(n -> true, true, true, false);
    }

    private static void uploadTree(ActionContext ctx, Conn c, Path local, String remote, List<Object> done, int depth, Copy o) throws Exception {
        if (depth > MAX_DEPTH) throw new ActionException("TRANSFER.TOO_DEEP", "Folder nesting deeper than " + MAX_DEPTH + " levels under " + local, null);
        ensureFolder(c, remote);
        Map<String, Object> existing = new HashMap<>();  // remote name -> modified time
        if (!o.overwrite() || o.onlyNewer()) for (Object row : c.list(remote)) existing.put(String.valueOf(((List<?>) row).get(0)), ((List<?>) row).get(3));
        List<Path> children;
        try (java.util.stream.Stream<Path> s = Files.list(local)) { children = s.sorted().toList(); }
        for (Path p : children) {
            ctx.checkCancelled();
            String name = p.getFileName().toString(), target = join(remote, name);
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { if (o.subfolders()) uploadTree(ctx, c, p, target, done, depth + 1, o); continue; }
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !o.match().test(name)) continue;
            if (existing.containsKey(name)) {
                if (!o.overwrite()) continue;
                if (o.onlyNewer() && existing.get(name) instanceof LocalDateTime t && !Files.getLastModifiedTime(p).toInstant().isAfter(t.toInstant(ZoneOffset.UTC))) continue;
            }
            c.upload(p, target);
            done.add(target);
        }
    }

    @Action(id = "download_folder", name = "Download folder", category = "Folders", timeoutSeconds = 7200, capabilities = {"network", "filesystem"},
            description = "Download a remote folder with all its files and sub-folders into a local folder.")
    @Output(name = "files", type = OoaType.LIST, description = "Local paths of the downloaded files.")
    public Map<String, Object> downloadFolder(ActionContext ctx,
            @Input(name = "remote_folder", type = OoaType.STRING, description = "Remote folder to download.") String remote,
            @Input(name = "local_folder", type = OoaType.FILE, description = "Local folder that receives the contents.") String local,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", example = "*.csv", description = "Only files whose names match this wildcard (* and ?).") String pattern,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also copy sub-folders and their files.") boolean sub,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace files that already exist; when false they are skipped.") boolean overwrite,
            @Input(name = "only_newer", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Copy a file only when it is newer than the existing copy (by modified time).") boolean onlyNewer) {
        Copy opts = new Copy(wildcard(pattern), sub, overwrite, onlyNewer);
        Path l = ctx.resolvePath(local).toAbsolutePath().normalize();
        Conn c = conn(ctx, session);
        List<Object> done = new ArrayList<>();
        try { downloadTree(ctx, c, remote, l, done, 0, opts); }
        catch (ActionException e) { throw e;
        } catch (Exception e) { throw fail(e, "Downloading folder " + remote); }
        return Map.of("files", done);
    }

    private static void downloadTree(ActionContext ctx, Conn c, String remote, Path local, List<Object> done, int depth, Copy o) throws Exception {
        if (depth > MAX_DEPTH) throw new ActionException("TRANSFER.TOO_DEEP", "Folder nesting deeper than " + MAX_DEPTH + " levels under " + remote, null);
        Files.createDirectories(local);
        for (Object entry : c.list(remote)) {
            ctx.checkCancelled();
            List<?> row = (List<?>) entry;
            String name = safeEntry(String.valueOf(row.get(0)));
            Path target = local.resolve(name).normalize();
            if (!target.startsWith(local)) throw new ActionException("TRANSFER.UNSAFE_NAME", "The server listed an unsafe file name '" + name + "'", null);
            if (Boolean.TRUE.equals(row.get(1))) { if (o.subfolders()) downloadTree(ctx, c, join(remote, name), target, done, depth + 1, o); continue; }
            if (!o.match().test(name)) continue;
            if (Files.exists(target) && (!o.overwrite() || (o.onlyNewer() && !newer(row.get(3), target)))) continue;
            c.download(join(remote, name), target);
            done.add(target.toString());
        }
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the connection.")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof Conn c) c.close();
        return Map.of();
    }
}
