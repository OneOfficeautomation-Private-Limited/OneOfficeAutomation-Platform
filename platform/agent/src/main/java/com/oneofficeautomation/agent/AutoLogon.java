package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Windows signs a runner's user in by itself after a restart (gap G49), so an unattended runner that needs a desktop
 * has one: `ooa agent autologon` on the runner, from an administrator's prompt. The password is kept as Windows'
 * protected LSA secret DefaultPassword (as Microsoft's Autologon tool does), never as plain text in the registry, and
 * it never reaches One CommandCenter. With the runner started at sign-in (`ooa agent install-task`) and its After
 * unattended runs set to lock the screen, the machine comes back signed in and working after each restart.
 * Unlocking a locked session needs a Windows credential provider and is not done here.
 */
public final class AutoLogon {
    private AutoLogon() { }

    static final String WINLOGON = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon";
    static final String SECRET = "DefaultPassword";

    /** The parts of advapi32 for LSA private data, which jna-platform does not map. */
    interface Lsa extends StdCallLibrary {
        Lsa I = DesktopSession.WINDOWS ? Native.load("advapi32", Lsa.class, W32APIOptions.UNICODE_OPTIONS) : null;
        int LsaOpenPolicy(Pointer systemName, LsaObjectAttributes attrs, int access, PointerByReference handle);
        int LsaStorePrivateData(Pointer policy, LsaUnicodeString key, LsaUnicodeString data);
        int LsaRetrievePrivateData(Pointer policy, LsaUnicodeString key, PointerByReference data);
        int LsaFreeMemory(Pointer buffer);
        int LsaClose(Pointer policy);
        int LsaNtStatusToWinError(int status);
    }

    @Structure.FieldOrder({"Length", "RootDirectory", "ObjectName", "Attributes", "SecurityDescriptor", "SecurityQualityOfService"})
    public static class LsaObjectAttributes extends Structure {
        public int Length; public Pointer RootDirectory; public Pointer ObjectName; public int Attributes; public Pointer SecurityDescriptor; public Pointer SecurityQualityOfService;
        public LsaObjectAttributes() { Length = size(); }
    }

    @Structure.FieldOrder({"Length", "MaximumLength", "Buffer"})
    public static class LsaUnicodeString extends Structure {
        public short Length; public short MaximumLength; public Pointer Buffer;
        public LsaUnicodeString() { }
        LsaUnicodeString(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
            com.sun.jna.Memory m = new com.sun.jna.Memory(b.length + 2L);
            m.clear();
            m.write(0, b, 0, b.length);
            Buffer = m; Length = (short) b.length; MaximumLength = (short) (b.length + 2);
        }
    }

    private static final int POLICY_CREATE_SECRET = 0x20, POLICY_GET_PRIVATE_INFORMATION = 0x4;

    private static Pointer open(int access) {
        PointerByReference h = new PointerByReference();
        int st = Lsa.I.LsaOpenPolicy(null, new LsaObjectAttributes(), access, h);
        if (st != 0) throw denied(Lsa.I.LsaNtStatusToWinError(st));
        return h.getValue();
    }

    private static OoaException denied(int winError) {
        return winError == 5 ? new OoaException("AGENT.NOT_ADMIN", "Windows refused: run this from a Command Prompt or PowerShell opened as administrator")
                : new OoaException("AGENT.AUTOLOGON_FAILED", "Windows refused the change (error " + winError + ")");
    }

    /** Stores the password as the LSA secret, or removes it (password null). */
    static void storeSecret(String password) {
        Pointer p = open(POLICY_CREATE_SECRET);
        try {
            int st = Lsa.I.LsaStorePrivateData(p, new LsaUnicodeString(SECRET), password == null ? null : new LsaUnicodeString(password));
            if (st != 0 && !(password == null && Lsa.I.LsaNtStatusToWinError(st) == 2)) throw denied(Lsa.I.LsaNtStatusToWinError(st));
        } finally { Lsa.I.LsaClose(p); }
    }

    /** Whether the LSA secret is there; its value is never read out. */
    static boolean hasSecret() {
        Pointer p;
        try { p = open(POLICY_GET_PRIVATE_INFORMATION); } catch (OoaException e) { return false; }
        try {
            PointerByReference data = new PointerByReference();
            int st = Lsa.I.LsaRetrievePrivateData(p, new LsaUnicodeString(SECRET), data);
            if (st != 0) return false;
            if (data.getValue() != null) Lsa.I.LsaFreeMemory(data.getValue());
            return true;
        } finally { Lsa.I.LsaClose(p); }
    }

    /** The user name as Windows wants it: NAME, or DOMAIN\NAME split into domain and name; names with odd characters refused. */
    static String[] account(String user, String domain) {
        if (user == null || user.isBlank()) throw new OoaException("AGENT.BAD_INPUT", "Give the Windows user with --user");
        String u = user.strip(), d = domain == null ? "" : domain.strip();
        int slash = u.indexOf('\\');
        if (slash > 0) { d = u.substring(0, slash); u = u.substring(slash + 1); }
        if (!u.matches("[^\\\\/\\[\\]:;|=,+*?<>\"@]{1,104}") || !d.matches("[A-Za-z0-9.\\-]{0,255}"))
            throw new OoaException("AGENT.BAD_INPUT", "Not a Windows account name: " + user);
        return new String[]{d, u};
    }

    /** ooa agent autologon: --status, --off, or --user NAME [--domain D] --password-env VAR. */
    public static int run(Map<String, String> o, java.io.PrintStream out) {
        if (!DesktopSession.WINDOWS) throw new OoaException("SYSTEM.WINDOWS_ONLY", "Automatic sign-in is for Windows runners");
        if (o.containsKey("status")) {
            String on = read("AutoAdminLogon"), user = read("DefaultUserName"), dom = read("DefaultDomainName");
            boolean plain = read(SECRET) != null;
            out.println("Automatic sign-in: " + ("1".equals(on) ? "on for " + (dom == null || dom.isBlank() ? "" : dom + "\\") + user : "off")
                    + (hasSecret() ? "; the password is kept as a protected secret" : "") + (plain ? "; WARNING: a plain-text DefaultPassword is in the registry (run --off, then set it up again)" : ""));
            return 0;
        }
        if (o.containsKey("off")) {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, "AutoAdminLogon", "0");
            if (read(SECRET) != null) Advapi32Util.registryDeleteValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, SECRET);
            storeSecret(null);
            out.println("Automatic sign-in is off and the stored password is removed.");
            return 0;
        }
        String[] acc = account(o.get("user"), o.get("domain"));
        String var = o.get("password-env");
        if (var == null || var.isBlank()) throw new OoaException("AGENT.BAD_INPUT", "Give the name of an environment variable holding the password with --password-env",
                "The password is never typed on the command line, where it would stay in the history.");
        String pw = System.getenv(var);
        if (pw == null || pw.isEmpty()) throw new OoaException("AGENT.BAD_INPUT", "The environment variable " + var + " is empty");
        try {
            storeSecret(pw);
        } catch (com.sun.jna.platform.win32.Win32Exception e) { throw denied(e.getErrorCode()); }
        try {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, "DefaultUserName", acc[1]);
            Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, "DefaultDomainName", acc[0]);
            if (read(SECRET) != null) Advapi32Util.registryDeleteValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, SECRET);   // never plain text
            Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, "AutoAdminLogon", "1");
        } catch (com.sun.jna.platform.win32.Win32Exception e) { throw denied(e.getErrorCode()); }
        out.println("Windows will sign in as " + (acc[0].isEmpty() ? "" : acc[0] + "\\") + acc[1] + " after each restart. "
                + "Start the runner at sign-in (ooa agent install-task) and lock the screen after unattended runs (One CommandCenter: Runners).");
        return 0;
    }

    private static String read(String name) {
        try { return Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, name) ? Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, WINLOGON, name) : null; }
        catch (RuntimeException e) { return null; }
    }
}
