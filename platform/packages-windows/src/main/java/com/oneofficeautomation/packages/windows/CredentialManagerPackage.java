package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.*;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Generic credentials in the Windows Credential Manager of the account the agent runs as, through advapi32
 * (CredReadW, CredWriteW, CredDeleteW, CredEnumerateW) called with JNA, the same way the Window toolkit calls user32.
 * Secrets come back as Credential values, so they are masked in logs and never written to run output.
 */
@OoaPackage(id = "ooa.credential_manager", name = "Windows Credential Manager", version = "1.0.0",
        description = "Read, list, save and remove generic credentials in the Windows Credential Manager of the agent's user account.")
public final class CredentialManagerPackage {
    static final String P = "CREDMAN";
    static final int CRED_TYPE_GENERIC = 1;
    static final int CRED_PERSIST_SESSION = 1, CRED_PERSIST_LOCAL_MACHINE = 2, CRED_PERSIST_ENTERPRISE = 3;
    static final int ERROR_NOT_FOUND = 1168, ERROR_NO_SUCH_LOGON_SESSION = 1312, ERROR_INVALID_PARAMETER = 87, ERROR_BAD_USERNAME = 2202;
    static final int CRED_MAX_BLOB = 5 * 512;

    /** CREDENTIALW. String fields are raw pointers so the same structure serves reads (Windows memory) and writes (our Memory). */
    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh", "CredentialBlobSize",
            "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName"})
    public static class CREDENTIAL extends Structure {
        public int Flags, Type;
        public Pointer TargetName, Comment;
        public int LastWrittenLow, LastWrittenHigh;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist, AttributeCount;
        public Pointer Attributes, TargetAlias, UserName;

        public CREDENTIAL() { super(); }
        public CREDENTIAL(Pointer p) { super(p); read(); }
    }

    interface Advapi32Cred extends StdCallLibrary {
        Advapi32Cred INSTANCE = Native.load("advapi32", Advapi32Cred.class, W32APIOptions.UNICODE_OPTIONS);
        boolean CredReadW(WString target, int type, int flags, PointerByReference credential);
        boolean CredWriteW(CREDENTIAL credential, int flags);
        boolean CredDeleteW(WString target, int type, int flags);
        boolean CredEnumerateW(WString filter, int flags, IntByReference count, PointerByReference credentials);
        void CredFree(Pointer buffer);
    }

    static void windows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new ActionException("SYSTEM.WINDOWS_ONLY", "Credential Manager actions work on Windows only", "Run this agent on a Windows runner.");
    }

    static Advapi32Cred api() {
        windows();
        return Advapi32Cred.INSTANCE;
    }

    static String target(String t) {
        if (t == null || t.isBlank()) throw new ActionException(P + ".NO_TARGET", "Give the credential's target name", "For example MyApp/service-account.");
        if (t.length() > 32767) throw new ActionException(P + ".BAD_TARGET", "The target name is too long");
        return t;
    }

    static ActionException lastError(String what, String target) {
        int code = Native.getLastError();
        return switch (code) {
            case ERROR_NOT_FOUND -> new ActionException(P + ".NOT_FOUND", "No generic credential named '" + target + "' in Credential Manager",
                    "Check the target name, or list targets to see what is stored for this account.");
            case ERROR_NO_SUCH_LOGON_SESSION -> new ActionException(P + ".NO_PROFILE", "Credential Manager is not available for this logon session",
                    "Run the agent under a user account with a loaded profile.");
            case ERROR_BAD_USERNAME, ERROR_INVALID_PARAMETER -> new ActionException(P + ".INVALID", "Windows rejected the credential for '" + target + "' (error " + code + ")",
                    "Check the target name and user name.");
            default -> new ActionException(P + ".FAILED", what + " failed for '" + target + "' (Windows error " + code + ")", null, true);
        };
    }

    static String str(Pointer p) { return p == null ? null : p.getWideString(0); }

    static LocalDateTime lastWritten(CREDENTIAL c) {
        long ft = ((long) c.LastWrittenHigh << 32) | (c.LastWrittenLow & 0xffffffffL);
        if (ft == 0) return null;
        long millis = ft / 10_000 - 11_644_473_600_000L;  // FILETIME counts 100 ns since 1601-01-01
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).withNano(0);
    }

    /** Blob to text: UTF-16LE when it looks like it (what Windows tools store), otherwise UTF-8; "auto" decides. */
    static String decode(byte[] blob, String encoding) {
        String enc = encoding == null || encoding.isBlank() ? "auto" : encoding;
        boolean utf16 = switch (enc) {
            case "utf16" -> true;
            case "utf8" -> false;
            case "auto" -> blob.length % 2 == 0 && blob.length > 0 && looksUtf16(blob);
            default -> throw new ActionException(P + ".BAD_ENCODING", "Unknown encoding '" + encoding + "'", "Use auto, utf16 or utf8.");
        };
        return new String(blob, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
    }

    static boolean looksUtf16(byte[] b) {
        int zeros = 0;
        for (int i = 1; i < b.length; i += 2) if (b[i] == 0) zeros++;
        return zeros * 2 >= b.length / 2;  // most high bytes are zero: Latin text stored as UTF-16LE
    }

    static byte[] encode(String secret, String encoding) {
        String enc = encoding == null || encoding.isBlank() || encoding.equals("auto") ? "utf16" : encoding;
        return switch (enc) {
            case "utf16" -> secret.getBytes(StandardCharsets.UTF_16LE);
            case "utf8" -> secret.getBytes(StandardCharsets.UTF_8);
            default -> throw new ActionException(P + ".BAD_ENCODING", "Unknown encoding '" + encoding + "'", "Use utf16 or utf8.");
        };
    }

    static Memory wide(String s) {
        Memory m = new Memory((s.length() + 1L) * 2);
        m.setWideString(0, s);
        return m;
    }

    @Action(id = "read_credential", name = "Read credential", category = "Credential Manager", idempotent = true, capabilities = "desktop",
            description = "Read a generic credential by its target name; the secret comes back as a Credential that is masked everywhere.")
    @Output(name = "username", type = OoaType.STRING, description = "User name stored with the credential (may be empty).")
    @Output(name = "password", type = OoaType.CREDENTIAL, description = "The stored secret.")
    @Output(name = "comment", type = OoaType.STRING, description = "Comment stored with the credential.")
    @Output(name = "last_written", type = OoaType.DATETIME, description = "When the credential was last changed.")
    public Map<String, Object> readCredential(ActionContext ctx,
            @Input(name = "target", type = OoaType.STRING, example = "MyApp/service-account", description = "Target name as shown in Credential Manager.") String target,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"auto\"", enumValues = {"auto", "utf16", "utf8"},
                    description = "How the secret is stored. auto: detects the UTF-16 used by Windows and most tools, otherwise UTF-8.") String encoding) {
        String t = target(target);
        PointerByReference ref = new PointerByReference();
        if (!api().CredReadW(new WString(t), CRED_TYPE_GENERIC, 0, ref)) throw lastError("Reading the credential", t);
        Pointer p = ref.getValue();
        try {
            CREDENTIAL c = new CREDENTIAL(p);
            byte[] blob = c.CredentialBlobSize > 0 && c.CredentialBlob != null ? c.CredentialBlob.getByteArray(0, c.CredentialBlobSize) : new byte[0];
            String secret = decode(blob, encoding);
            Arrays.fill(blob, (byte) 0);
            ctx.info("Read Windows credential '" + t + "'");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("username", Objects.requireNonNullElse(str(c.UserName), ""));
            out.put("password", new SecureString(secret, "wincred:" + t));
            out.put("comment", Objects.requireNonNullElse(str(c.Comment), ""));
            out.put("last_written", lastWritten(c));
            return out;
        } finally {
            api().CredFree(p);
        }
    }

    @Action(id = "list_targets", name = "List credential targets", category = "Credential Manager", idempotent = true, capabilities = "desktop",
            description = "List the target names of generic credentials, optionally filtered with a * wildcard pattern. Secrets are not returned.")
    @Output(name = "targets", type = OoaType.LIST, description = "Target names, sorted.")
    @Output(name = "credentials", type = OoaType.TABLE, description = "Columns: target, username, comment, persist, last_written.")
    public Map<String, Object> listTargets(ActionContext ctx,
            @Input(name = "filter", type = OoaType.STRING, required = false, example = "MyApp/*", description = "Pattern ending in *, e.g. MyApp/*; empty lists all.") String filter) {
        if (filter != null && !filter.isBlank() && filter.indexOf('*') >= 0 && !filter.endsWith("*"))
            throw new ActionException(P + ".BAD_FILTER", "The filter may only have * at the end", "For example MyApp/*.");
        IntByReference count = new IntByReference();
        PointerByReference ref = new PointerByReference();
        WString f = filter == null || filter.isBlank() ? null : new WString(filter);
        List<Object[]> found = new ArrayList<>();
        if (!api().CredEnumerateW(f, 0, count, ref)) {
            if (Native.getLastError() != ERROR_NOT_FOUND) throw lastError("Listing credentials", filter == null ? "*" : filter);
        } else {
            Pointer arr = ref.getValue();
            try {
                for (Pointer p : arr.getPointerArray(0, count.getValue())) {
                    CREDENTIAL c = new CREDENTIAL(p);
                    if (c.Type != CRED_TYPE_GENERIC) continue;
                    String persist = switch (c.Persist) { case CRED_PERSIST_SESSION -> "session"; case CRED_PERSIST_ENTERPRISE -> "enterprise"; default -> "local_machine"; };
                    found.add(new Object[]{str(c.TargetName), Objects.requireNonNullElse(str(c.UserName), ""), Objects.requireNonNullElse(str(c.Comment), ""), persist, lastWritten(c)});
                }
            } finally {
                api().CredFree(arr);
            }
        }
        found.sort(Comparator.comparing(o -> String.valueOf(o[0]), String.CASE_INSENSITIVE_ORDER));
        List<Object> targets = new ArrayList<>(), rows = new ArrayList<>();
        for (Object[] o : found) { targets.add(o[0]); rows.add(new ArrayList<>(Arrays.asList(o))); }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("columns", List.of("target", "username", "comment", "persist", "last_written"));
        table.put("rows", rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targets", targets);
        out.put("credentials", table);
        return out;
    }

    @Action(id = "write_credential", name = "Save credential", category = "Credential Manager", capabilities = "desktop",
            description = "Create or replace a generic credential for the agent's user account.")
    public Map<String, Object> writeCredential(ActionContext ctx,
            @Input(name = "target", type = OoaType.STRING, example = "MyApp/service-account", description = "Target name to store under.") String target,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "Secret to store, from the vault or another Credential variable.") Object password,
            @Input(name = "username", type = OoaType.STRING, required = false, description = "User name to store with it.") String username,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Optional note shown in Credential Manager.") String comment,
            @Input(name = "persist", type = OoaType.STRING, required = false, defaultValue = "\"local_machine\"", enumValues = {"session", "local_machine", "enterprise"},
                    description = "session: until logoff. local_machine: this computer. enterprise: roams with the user profile.") String persist,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf16\"", enumValues = {"utf16", "utf8"},
                    description = "How to store the secret; utf16 is what Windows tools expect.") String encoding) {
        String t = target(target);
        if (!(password instanceof SecureString s))
            throw new ActionException(P + ".NO_SECRET", "The secret must be a Credential value", "Map password to a Credential variable or vault entry.");
        int mode = switch (persist == null || persist.isBlank() ? "local_machine" : persist) {
            case "session" -> CRED_PERSIST_SESSION;
            case "local_machine" -> CRED_PERSIST_LOCAL_MACHINE;
            case "enterprise" -> CRED_PERSIST_ENTERPRISE;
            default -> throw new ActionException(P + ".BAD_PERSIST", "Unknown persist value '" + persist + "'", "Use session, local_machine or enterprise.");
        };
        byte[] blob = encode(s.reveal(), encoding);
        if (blob.length > CRED_MAX_BLOB) throw new ActionException(P + ".TOO_LONG", "The secret is longer than Credential Manager allows (" + CRED_MAX_BLOB + " bytes)");
        int blobSize = blob.length;
        Memory blobMem = new Memory(Math.max(1, blobSize));
        blobMem.write(0, blob, 0, blobSize);
        Arrays.fill(blob, (byte) 0);
        Memory tm = wide(t);
        Memory um = username == null || username.isEmpty() ? null : wide(username);
        Memory cm = comment == null || comment.isEmpty() ? null : wide(comment);
        try {
            CREDENTIAL c = new CREDENTIAL();
            c.Type = CRED_TYPE_GENERIC;
            c.TargetName = tm;
            c.UserName = um;
            c.Comment = cm;
            c.CredentialBlobSize = blobSize;
            c.CredentialBlob = blobSize == 0 ? null : blobMem;
            c.Persist = mode;
            if (!api().CredWriteW(c, 0)) throw lastError("Saving the credential", t);
        } finally {
            blobMem.clear();
        }
        ctx.info("Saved Windows credential '" + t + "'");
        return Map.of();
    }

    @Action(id = "delete_credential", name = "Remove credential", category = "Credential Manager", capabilities = "desktop",
            description = "Remove a generic credential from Credential Manager.")
    @Output(name = "deleted", type = OoaType.BOOLEAN, description = "True when a credential was removed; false when none existed and missing_ok is on.")
    public Map<String, Object> deleteCredential(ActionContext ctx,
            @Input(name = "target", type = OoaType.STRING, description = "Target name to remove.") String target,
            @Input(name = "missing_ok", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Succeed quietly when there is nothing to remove.") boolean missingOk) {
        String t = target(target);
        if (!api().CredDeleteW(new WString(t), CRED_TYPE_GENERIC, 0)) {
            ActionException e = lastError("Removing the credential", t);
            if (missingOk && e.code().equals(P + ".NOT_FOUND")) return Map.of("deleted", false);
            throw e;
        }
        ctx.info("Removed Windows credential '" + t + "'");
        return Map.of("deleted", true);
    }

    @Condition(id = "credential_exists", name = "Credential exists", description = "True when a generic credential with this target name is stored for the agent's account.")
    public boolean credentialExists(ActionContext ctx, @Input(name = "target", type = OoaType.STRING, description = "Target name.") String target) {
        String t = target(target);
        PointerByReference ref = new PointerByReference();
        if (api().CredReadW(new WString(t), CRED_TYPE_GENERIC, 0, ref)) { api().CredFree(ref.getValue()); return true; }
        ActionException e = lastError("Reading the credential", t);
        if (e.code().equals(P + ".NOT_FOUND")) return false;
        throw e;
    }
}
