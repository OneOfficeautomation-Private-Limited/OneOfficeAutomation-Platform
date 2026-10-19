package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.nio.file.*;
import java.util.*;

/**
 * OpenPGP encryption and signing with GnuPG (install Gpg4win on Windows). Passphrases go to gpg through standard
 * input, never on the command line. Use gnupg_home to keep a bot's keys separate from the user's keyring.
 */
@OoaPackage(id = "ooa.pgp", name = "PGP", version = "1.0.0", description = "Import keys, encrypt, decrypt, sign and verify files with OpenPGP (GnuPG).")
public final class PgpPackage {

    static String exe() {
        String env = System.getenv("OOA_GPG");
        if (env != null && !env.isBlank()) return env;
        if (ProcessRunner.WINDOWS) for (String p : List.of("C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe", "C:\\Program Files\\GnuPG\\bin\\gpg.exe"))
            if (Files.isRegularFile(Path.of(p))) return p;
        return "gpg";
    }

    private static ProcessRunner.Result gpg(ActionContext ctx, String home, List<String> args, String stdin) {
        List<String> cmd = new ArrayList<>(List.of(exe(), "--batch", "--yes", "--no-tty", "--status-fd", "1"));
        if (home != null && !home.isBlank()) {
            Path h = ctx.resolvePath(home);
            try { Files.createDirectories(h); } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
            cmd.addAll(List.of("--homedir", h.toString()));
        }
        cmd.addAll(args);
        ProcessRunner.Result r;
        try { r = ProcessRunner.run(ctx, cmd, ctx.workDir(), stdin, 300); }
        catch (ActionException e) {
            if ("PROCESS.NOT_FOUND".equals(e.code())) throw new ActionException("PGP.NOT_INSTALLED", "GnuPG is not installed on this runner", "Install Gpg4win (Windows) or GnuPG, or set OOA_GPG.");
            throw e;
        }
        return r;
    }

    private static String file(ActionContext ctx, String p) {
        Path f = ctx.resolvePath(p);
        if (!Files.isRegularFile(f)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + f, "Check the path.");
        return f.toString();
    }

    private static String out(ActionContext ctx, String p) {
        Path f = ctx.resolvePath(p);
        try { if (f.getParent() != null) Files.createDirectories(f.getParent()); } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        return f.toString();
    }

    private static final String D_HOME = "Separate GnuPG key folder for this agent; the runner user's keyring when empty.";

    @Action(id = "import_key", name = "Import key", category = "Keys", capabilities = "filesystem", description = "Import a public or private key file (.asc or .gpg).")
    @Output(name = "fingerprints", type = OoaType.LIST, description = "Fingerprints of imported keys.")
    public Map<String, Object> importKey(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Key file.") String path,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, required = false, description = "Passphrase, when importing a protected private key.") Object pass,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home) {
        List<String> args = new ArrayList<>();
        String stdin = null;
        if (pass instanceof SecureString s) { args.addAll(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0")); stdin = s.reveal() + "\n"; }
        args.addAll(List.of("--import", file(ctx, path)));
        ProcessRunner.Result r = gpg(ctx, home, args, stdin);
        Set<Object> fprs = new LinkedHashSet<>();
        for (String line : r.stdout().split("\n")) if (line.startsWith("[GNUPG:] IMPORT_OK ")) { String[] p = line.trim().split(" "); fprs.add(p[p.length - 1]); }
        if (fprs.isEmpty()) throw new ActionException("PGP.IMPORT_FAILED", "No key was imported: " + ApplicationPackage.tail(r.stderr()), "Check the file is an OpenPGP key.");
        return Map.of("fingerprints", new ArrayList<>(fprs));
    }

    @Action(id = "encrypt", name = "Encrypt file", category = "Encryption", timeoutSeconds = 300, capabilities = "filesystem", description = "Encrypt a file for one or more recipients' public keys.")
    public Map<String, Object> encrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to encrypt.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Encrypted file to create, e.g. report.csv.pgp.") String target,
            @Input(name = "recipients", description = "Recipient key ids, fingerprints or email addresses (text or List).") Object recipients,
            @Input(name = "armor", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "ASCII-armored output (text) instead of binary.") boolean armor,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home) {
        List<String> args = new ArrayList<>(List.of("--trust-model", "always"));
        List<String> rc = HttpJson.addressList(recipients);
        if (rc.isEmpty()) throw new ActionException("PGP.NO_RECIPIENT", "Give at least one recipient");
        for (String r : rc) { if (r.startsWith("-")) throw new ActionException("PGP.BAD_RECIPIENT", "Invalid recipient '" + r + "'"); args.addAll(List.of("-r", r)); }
        if (armor) args.add("--armor");
        args.addAll(List.of("-o", out(ctx, target), "-e", file(ctx, path)));
        ProcessRunner.Result r = gpg(ctx, home, args, null);
        if (r.exitCode() != 0) throw new ActionException("PGP.ENCRYPT_FAILED", "Encryption failed: " + ApplicationPackage.tail(r.stderr()), "Import the recipient's public key first.");
        return Map.of();
    }

    @Action(id = "decrypt", name = "Decrypt file", category = "Encryption", timeoutSeconds = 300, capabilities = "filesystem", description = "Decrypt a file with your private key.")
    public Map<String, Object> decrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Encrypted file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Decrypted file to create.") String target,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, required = false, description = "Private key passphrase from the vault.") Object pass,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home) {
        List<String> args = new ArrayList<>(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0", "-o", out(ctx, target), "-d", file(ctx, path)));
        ProcessRunner.Result r = gpg(ctx, home, args, pass instanceof SecureString s ? s.reveal() + "\n" : "\n");
        if (r.exitCode() != 0) {
            boolean badPass = r.stdout().contains("BAD_PASSPHRASE") || r.stderr().contains("Bad passphrase");
            throw new ActionException(badPass ? "PGP.BAD_PASSPHRASE" : "PGP.DECRYPT_FAILED", badPass ? "Wrong passphrase" : "Decryption failed: " + ApplicationPackage.tail(r.stderr()),
                    badPass ? "Check the passphrase in the vault." : "Import the matching private key first.");
        }
        return Map.of();
    }

    @Action(id = "sign", name = "Sign file", category = "Signing", timeoutSeconds = 300, capabilities = "filesystem", description = "Create a detached signature (.sig) proving the file came from you.")
    public Map<String, Object> sign(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to sign.") String path,
            @Input(name = "signature", type = OoaType.FILE, description = "Signature file to create, e.g. report.csv.sig.") String sig,
            @Input(name = "key", type = OoaType.STRING, required = false, description = "Signing key id or email; the default key when empty.") String key,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, required = false, description = "Private key passphrase from the vault.") Object pass,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home) {
        List<String> args = new ArrayList<>(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0"));
        if (key != null && !key.isBlank()) { if (key.startsWith("-")) throw new ActionException("PGP.BAD_KEY", "Invalid key '" + key + "'"); args.addAll(List.of("-u", key)); }
        args.addAll(List.of("-o", out(ctx, sig), "--detach-sign", file(ctx, path)));
        ProcessRunner.Result r = gpg(ctx, home, args, pass instanceof SecureString s ? s.reveal() + "\n" : "\n");
        if (r.exitCode() != 0) throw new ActionException("PGP.SIGN_FAILED", "Signing failed: " + ApplicationPackage.tail(r.stderr()), "Check the key and passphrase.");
        return Map.of();
    }

    @Action(id = "verify", name = "Verify signature", category = "Signing", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Check a detached signature; returns the result instead of failing.")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when the signature is good.")
    @Output(name = "signer", type = OoaType.STRING, description = "Signer's user id when valid.")
    @Output(name = "fingerprint", type = OoaType.STRING, description = "Signing key fingerprint when valid.")
    public Map<String, Object> verify(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Signed file.") String path,
            @Input(name = "signature", type = OoaType.FILE, description = "Signature file.") String sig,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home) {
        ProcessRunner.Result r = gpg(ctx, home, List.of("--verify", file(ctx, sig), file(ctx, path)), null);
        String signer = null, fpr = null;
        for (String line : r.stdout().split("\n")) {
            if (line.startsWith("[GNUPG:] GOODSIG ")) signer = line.substring(17).replaceFirst("^\\S+\\s+", "").trim();
            if (line.startsWith("[GNUPG:] VALIDSIG ")) fpr = line.split(" ")[2];
        }
        boolean ok = r.exitCode() == 0 && signer != null;
        return Out.of("valid", ok, "signer", ok ? signer : "", "fingerprint", ok ? fpr : "");
    }

}
