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
            @Input(name = "port", type = OoaType.NUMBER, required = false, description = "Port; 22 for SFTP, 21 for FTP/FTPS when empty.") Integer port,
            @Input(name = "user", type = OoaType.STRING, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "private_key", type = OoaType.FILE, required = false, description = "SFTP private key file (instead of a password).") String key,
            @Input(name = "key_passphrase", type = OoaType.CREDENTIAL, required = false, description = "Private key passphrase.") Object keyPass,
            @Input(name = "host_key_fingerprint", type = OoaType.STRING, required = false, description = "Expected SFTP server fingerprint (SHA256:...); strongly recommended.") String fingerprint,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        String pw = password instanceof SecureString s ? s.reveal() : null;
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
                s.connect(20000);
                String actual = s.getHostKey().getFingerPrint(j);
                if (fingerprint != null && !fingerprint.isBlank()) {
                    if (!fingerprint.trim().equalsIgnoreCase(actual)) { s.disconnect(); throw new ActionException("TRANSFER.HOST_KEY_MISMATCH",
                            "Server fingerprint " + actual + " does not match the expected one", "Possible man-in-the-middle attack; confirm the fingerprint with the server owner."); }
                } else ctx.warn("SFTP host key not pinned (server fingerprint " + actual + "); set host_key_fingerprint to verify the server.");
                ChannelSftp ch = (ChannelSftp) s.openChannel("sftp");
                ch.connect(20000);
                c = new Sftp(s, ch);
            } else {
                FTPClient f = protocol.equals("ftps") ? new FTPSClient(false) : new FTPClient();
                f.setConnectTimeout(20000);
                f.connect(host, port == null ? 21 : port);
                if (!FTPReply.isPositiveCompletion(f.getReplyCode())) { f.disconnect(); throw new IOException("Server refused the connection: " + f.getReplyString()); }
                if (!f.login(user, pw == null ? "" : pw)) { f.disconnect(); throw new ActionException("TRANSFER.AUTH_FAILED", "FTP sign-in failed", "Check the user and password."); }
                if (f instanceof FTPSClient s) { s.execPBSZ(0); s.execPROT("P"); }
                f.enterLocalPassiveMode();
                f.setFileType(FTP.BINARY_FILE_TYPE);
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
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        try { return Map.of("table", Values.table(List.of("name", "is_folder", "size", "modified"), conn(ctx, session).list(dir))); }
        catch (Exception e) { throw fail(e, "Listing " + dir); }
    }

    @Action(id = "download", name = "Download", category = "Files", timeoutSeconds = 3600, capabilities = {"network", "filesystem"}, description = "Download a remote file.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Remote file.") String remote,
            @Input(name = "local_path", type = OoaType.FILE, description = "Where to save it.") String local,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        Path l = ctx.resolvePath(local);
        try { if (l.getParent() != null) Files.createDirectories(l.getParent()); conn(ctx, session).download(remote, l); }
        catch (Exception e) { throw fail(e, "Downloading " + remote); }
        return Map.of();
    }

    @Action(id = "upload", name = "Upload", category = "Files", timeoutSeconds = 3600, capabilities = {"network", "filesystem"}, description = "Upload a local file.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "local_path", type = OoaType.FILE, description = "File to upload.") String local,
            @Input(name = "remote_path", type = OoaType.STRING, description = "Remote path including the file name.") String remote,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        Path l = ctx.resolvePath(local);
        if (!Files.isRegularFile(l)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + l, "Check the path.");
        try { conn(ctx, session).upload(l, remote); } catch (Exception e) { throw fail(e, "Uploading to " + remote); }
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

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the connection.")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"transfer\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof Conn c) c.close();
        return Map.of();
    }
}
