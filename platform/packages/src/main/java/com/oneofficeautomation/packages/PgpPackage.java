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

    /** Is there a gpg this runner can use (for tests and checks; actions report why not)? */
    static boolean usable() {
        try { exe(); return true; } catch (ActionException e) { return false; }
    }

    static String exe() {
        String env = System.getenv("OOA_GPG");
        if (env != null && !env.isBlank()) return env;
        if (!ProcessRunner.WINDOWS) return "gpg";
        for (String p : List.of("C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe", "C:\\Program Files\\GnuPG\\bin\\gpg.exe"))
            if (Files.isRegularFile(Path.of(p))) return p;
        // a gpg on PATH, but not the one that comes with Git for Windows (MSYS): it reads C:\ paths as relative and fails
        boolean msysOnly = false;
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            try {
                Path c = Path.of(dir.strip().replace("\"", ""), "gpg.exe");
                if (!Files.isRegularFile(c)) continue;
                if (Files.exists(c.resolveSibling("msys-2.0.dll")) || msys(c)) { msysOnly = true; continue; }
                return c.toString();
            } catch (java.nio.file.InvalidPathException ignored) { /* not a folder */ }
        }
        // nothing usable found on PATH: Windows may still find a gpg elsewhere, so ask the one it would run
        if (msysOnly || msys(Path.of("gpg"))) throw new ActionException("PGP.NOT_INSTALLED", "Only the gpg that comes with Git for Windows was found; it cannot work with Windows paths",
                "Install Gpg4win, or set OOA_GPG to the full path of a Windows gpg.exe.");
        return "gpg";
    }

    private static final Map<Path, Boolean> MSYS = new java.util.concurrent.ConcurrentHashMap<>();

    /** True for an MSYS/Cygwin build of gpg (its home folder prints as /c/Users/...), whose DLL may live elsewhere. */
    static boolean msys(Path gpg) {
        return MSYS.computeIfAbsent(gpg, g -> {
            try {
                Process p = new ProcessBuilder(g.toString(), "--version").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor();
                return java.util.regex.Pattern.compile("(?m)^Home: /").matcher(out).find();
            } catch (Exception e) { return false; }
        });
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

    @Action(id = "import_key", name = "Import key", category = "Keys", capabilities = "filesystem", description = "Import a public or private key file (.asc or .gpg).",
            panel = "path, [The key has a passphrase: passphrase] | gnupg_home")
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

    public Map<String, Object> encrypt(ActionContext ctx, String path, String target, Object recipients, boolean armor, String home, String publicKeyFile, boolean overwrite) {
        return encrypt(ctx, path, target, recipients, armor, home, publicKeyFile, overwrite, null, null, null);
    }

    /** gpg --cipher-algo names, by the names offered in the panel. */
    private static final Map<String, String> CIPHERS = Map.ofEntries(
            Map.entry("aes128", "AES"), Map.entry("aes192", "AES192"), Map.entry("aes256", "AES256"),
            Map.entry("camellia128", "CAMELLIA128"), Map.entry("camellia192", "CAMELLIA192"), Map.entry("camellia256", "CAMELLIA256"),
            Map.entry("twofish", "TWOFISH"), Map.entry("cast5", "CAST5"), Map.entry("3des", "3DES"), Map.entry("blowfish", "BLOWFISH"));

    /** gpg --compress-algo names. */
    private static final Map<String, String> COMPRESSIONS = Map.of("none", "none", "zip", "zip", "zlib", "zlib", "bzip2", "bzip2");

    /** The gpg name for a cipher, or null for the default (the recipients' preferences, or AES-128 for a passphrase). */
    static String cipher(String cipher) {
        if (cipher == null || cipher.isBlank() || cipher.equalsIgnoreCase("default")) return null;
        String g = CIPHERS.get(cipher.strip().toLowerCase(Locale.ROOT).replace("-", ""));
        if (g == null) throw new ActionException("PGP.BAD_CIPHER", "Unknown cipher '" + cipher + "'", "Use AES256, AES192, AES128, CAMELLIA256, TWOFISH or leave it empty for the default.");
        return g;
    }

    /** The gpg name for a compression, or null for gpg's default. */
    static String compression(String compression) {
        if (compression == null || compression.isBlank() || compression.equalsIgnoreCase("default")) return null;
        String g = COMPRESSIONS.get(compression.strip().toLowerCase(Locale.ROOT));
        if (g == null) throw new ActionException("PGP.BAD_COMPRESSION", "Unknown compression '" + compression + "'", "Use none, zip, zlib or bzip2, or leave it empty for the default.");
        return g;
    }

    @Action(id = "encrypt", name = "Encrypt file", category = "Encryption", timeoutSeconds = 300, capabilities = "filesystem",
            description = "Encrypt a file for one or more recipients' public keys, or with a passphrase only (symmetric), choosing the cipher and compression.",
            panel = "(Encrypt for: Keys in the keyring = recipients / A public key file = public_key_file / A passphrase only = passphrase), path, target, overwrite, armor | cipher, compression, gnupg_home")
    public Map<String, Object> encrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to encrypt.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Encrypted file to create, e.g. report.csv.pgp.") String target,
            @Input(name = "recipients", required = false, description = "Recipient key ids, fingerprints or email addresses (text or List); may be empty when public_key_file is given.") Object recipients,
            @Input(name = "armor", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "ASCII-armored output (text) instead of binary.") boolean armor,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home,
            @Input(name = "public_key_file", type = OoaType.FILE, required = false, description = "Recipient's public key file (.asc or .gpg) to encrypt for, without importing it into a keyring first.") String publicKeyFile,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing target file.") boolean overwrite,
            @Input(name = "cipher", type = OoaType.STRING, required = false, defaultValue = "\"default\"",
                    enumValues = {"default", "AES256", "AES192", "AES128", "CAMELLIA256", "CAMELLIA192", "CAMELLIA128", "TWOFISH", "CAST5", "3DES", "BLOWFISH"},
                    description = "Symmetric cipher for the data; default lets GnuPG choose from the recipients' key preferences (AES-128 for a passphrase). Older ciphers (CAST5, 3DES, BLOWFISH) only for old partners.") String cipher,
            @Input(name = "compression", type = OoaType.STRING, required = false, defaultValue = "\"default\"", enumValues = {"default", "none", "zip", "zlib", "bzip2"},
                    description = "Compression before encryption; none for files that are already compressed, such as .zip or .jpg.") String compression,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, required = false,
                    description = "Passphrase that can decrypt the file. Alone it gives passphrase-only (symmetric) encryption; with recipients either the keys or the passphrase can decrypt.") Object passphrase) {
        String cipherName = cipher(cipher), compressName = compression(compression);
        if (passphrase != null && !(passphrase instanceof SecureString))
            throw new ActionException("PGP.BAD_PASSPHRASE_INPUT", "The passphrase must come from the credential vault", "Pick a credential for passphrase.");
        if (passphrase instanceof SecureString s && s.reveal().isEmpty())
            throw new ActionException("PGP.EMPTY_PASSPHRASE", "The passphrase is empty", "Store a non-empty passphrase in the vault.");
        if (!overwrite && Files.exists(ctx.resolvePath(target))) throw new ActionException("FILE.EXISTS", "File already exists: " + ctx.resolvePath(target), "Set overwrite to true or choose another name.");
        KeyHome kh = keyHome(ctx, home, publicKeyFile, null);
        try {
            return encryptWith(ctx, path, target, recipients, armor, kh, cipherName, compressName, (SecureString) passphrase);
        } finally { kh.close(ctx); }
    }

    /** A keyring for one step: the given home, or a temporary one when a key file is used without a home. */
    record KeyHome(String home, List<String> fingerprints, Path temp) {
        void close(ActionContext ctx) {
            if (temp == null) return;
            try { ProcessRunner.run(ctx, List.of(gpgconf(), "--homedir", temp.toString(), "--kill", "gpg-agent"), null, null, 20); } catch (RuntimeException ignored) { }
            try (java.util.stream.Stream<Path> w = Files.walk(temp)) { for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
            catch (java.io.IOException ignored) { }
        }
    }

    static String gpgconf() {
        String gpg = exe();
        Path p = Path.of(gpg);
        if (p.getParent() != null) {
            Path c = p.resolveSibling(ProcessRunner.WINDOWS ? "gpgconf.exe" : "gpgconf");
            if (Files.isRegularFile(c)) return c.toString();
        }
        return "gpgconf";
    }

    private KeyHome keyHome(ActionContext ctx, String home, String keyFile, Object pass) {
        if (keyFile == null || keyFile.isBlank()) return new KeyHome(home, List.of(), null);
        Path temp = null;
        String h = home;
        if (h == null || h.isBlank()) {
            try { temp = Files.createTempDirectory("ooa-gpg-"); } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
            h = temp.toString();
        }
        try {
            @SuppressWarnings("unchecked") List<Object> f = (List<Object>) importKey(ctx, keyFile, pass, h).get("fingerprints");
            List<String> fprs = new ArrayList<>();
            for (Object o : f) fprs.add(String.valueOf(o));
            return new KeyHome(h, fprs, temp);
        } catch (RuntimeException e) {
            new KeyHome(h, List.of(), temp).close(ctx);
            throw e;
        }
    }

    private Map<String, Object> encryptWith(ActionContext ctx, String path, String target, Object recipients, boolean armor, KeyHome kh,
                                            String cipher, String compression, SecureString passphrase) {
        String home = kh.home();
        List<String> args = new ArrayList<>(List.of("--trust-model", "always"));
        List<String> rc = new ArrayList<>(recipients == null ? List.of() : HttpJson.addressList(recipients));
        rc.addAll(kh.fingerprints());
        if (rc.isEmpty() && passphrase == null) throw new ActionException("PGP.NO_RECIPIENT", "Give at least one recipient or a passphrase", "Fill in recipients, public_key_file or passphrase.");
        for (String r : rc) { if (r.startsWith("-")) throw new ActionException("PGP.BAD_RECIPIENT", "Invalid recipient '" + r + "'"); args.addAll(List.of("-r", r)); }
        if (armor) args.add("--armor");
        if (cipher != null) args.addAll(List.of("--cipher-algo", cipher));
        if (compression != null) args.addAll(List.of("--compress-algo", compression));
        String stdin = null;
        // The passphrase goes through standard input and is not kept in gpg-agent's cache.
        if (passphrase != null) { args.addAll(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0", "--no-symkey-cache")); stdin = passphrase.reveal() + "\n"; }
        args.addAll(List.of("-o", out(ctx, target)));
        if (!rc.isEmpty()) args.add("-e");
        if (passphrase != null) args.add("-c");
        args.add(file(ctx, path));
        ProcessRunner.Result r = gpg(ctx, home, args, stdin);
        if (r.exitCode() != 0) throw new ActionException("PGP.ENCRYPT_FAILED", "Encryption failed: " + ApplicationPackage.tail(r.stderr()),
                rc.isEmpty() ? "Check GnuPG is installed and the target folder is writable." : "Import the recipient's public key first.");
        return Map.of();
    }

    @Action(id = "decrypt", name = "Decrypt file", category = "Encryption", timeoutSeconds = 300, capabilities = "filesystem", description = "Decrypt a file with your private key.",
            panel = "[Use a private key file: private_key_file], passphrase, path, target, overwrite | gnupg_home")
    public Map<String, Object> decrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Encrypted file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Decrypted file to create.") String target,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, required = false, description = "Private key passphrase from the vault.") Object pass,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home,
            @Input(name = "private_key_file", type = OoaType.FILE, required = false, description = "Private key file (.asc or .gpg) to decrypt with, without importing it into a keyring first.") String privateKeyFile,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing target file.") boolean overwrite) {
        if (!overwrite && Files.exists(ctx.resolvePath(target))) throw new ActionException("FILE.EXISTS", "File already exists: " + ctx.resolvePath(target), "Set overwrite to true or choose another name.");
        KeyHome kh = keyHome(ctx, home, privateKeyFile, pass);
        try { return decryptWith(ctx, path, target, pass, kh.home()); } finally { kh.close(ctx); }
    }

    private Map<String, Object> decryptWith(ActionContext ctx, String path, String target, Object pass, String home) {
        List<String> args = new ArrayList<>(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0", "-o", out(ctx, target), "-d", file(ctx, path)));
        ProcessRunner.Result r = gpg(ctx, home, args, pass instanceof SecureString s ? s.reveal() + "\n" : "\n");
        if (r.exitCode() != 0) {
            boolean badPass = r.stdout().contains("BAD_PASSPHRASE") || r.stderr().contains("Bad passphrase");
            throw new ActionException(badPass ? "PGP.BAD_PASSPHRASE" : "PGP.DECRYPT_FAILED", badPass ? "Wrong passphrase" : "Decryption failed: " + ApplicationPackage.tail(r.stderr()),
                    badPass ? "Check the passphrase in the vault." : "Import the matching private key first.");
        }
        return Map.of();
    }

    @Action(id = "sign", name = "Sign file", category = "Signing", timeoutSeconds = 300, capabilities = "filesystem", description = "Create a detached signature (.sig) proving the file came from you.",
            panel = "path, signature, (Signing key: The default key = / A specific key = key), passphrase | gnupg_home")
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
            description = "Check a detached signature; returns the result instead of failing.",
            panel = "path, signature | gnupg_home")
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

    /** gpg algorithm names for {primary (sign, certify), encryption subkey}. */
    static String[] algorithms(String algorithm) {
        return switch (algorithm == null ? "ed25519" : algorithm) {
            case "ed25519" -> new String[]{"ed25519", "cv25519"};
            case "rsa3072" -> new String[]{"rsa3072", "rsa3072"};
            case "rsa4096" -> new String[]{"rsa4096", "rsa4096"};
            default -> throw new ActionException("PGP.BAD_ALGORITHM", "Unknown algorithm '" + algorithm + "'", "Use ed25519, rsa3072 or rsa4096.");
        };
    }

    /** User id "Name (comment) <email>", rejecting text that gpg could read as options or that would break the user id. */
    static String userId(String name, String email, String comment) {
        if (name == null || name.isBlank() || name.strip().startsWith("-") || name.matches(".*[<>()\\x00-\\x1f].*") || name.length() > 200)
            throw new ActionException("PGP.BAD_NAME", "Invalid key owner name '" + name + "'", "Give a plain name, e.g. Finance Agent.");
        if (email == null || !email.strip().matches("^[^\\s<>()@]+@[^\\s<>()@]+\\.[^\\s<>()@]+$"))
            throw new ActionException("PGP.BAD_EMAIL", "Invalid email address '" + email + "'", "Give an address such as finance-agent@example.com.");
        String c = comment == null || comment.isBlank() ? "" : comment.strip();
        if (c.matches(".*[<>()\\x00-\\x1f].*")) throw new ActionException("PGP.BAD_COMMENT", "The comment may not contain < > ( ) or control characters");
        return name.strip() + (c.isEmpty() ? "" : " (" + c + ")") + " <" + email.strip() + ">";
    }

    static String expiry(String expires) {
        String e = expires == null || expires.isBlank() ? "2y" : expires.strip();
        if (!e.matches("^(0|never|\\d{1,4}[dwmy]?|\\d{4}-\\d{2}-\\d{2})$"))
            throw new ActionException("PGP.BAD_EXPIRY", "Invalid expiry '" + expires + "'", "Use e.g. 1y, 6m, 90d, a date like 2028-12-31, or never.");
        return e.equals("0") ? "never" : e;
    }

    @Action(id = "create_keys", name = "Create key pair", category = "Keys", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Generate a new OpenPGP key pair (signing key plus encryption subkey) and optionally export it to armored key files.",
            panel = "public_key_file, private_key_file, algorithm, passphrase, name, email | comment, expires, gnupg_home")
    @Output(name = "fingerprint", type = OoaType.STRING, description = "Fingerprint of the new key.")
    @Output(name = "user_id", type = OoaType.STRING, description = "User id stored in the key, e.g. Finance Agent <agent@example.com>.")
    @Output(name = "public_key_file", type = OoaType.FILE, description = "Exported public key, or empty.")
    @Output(name = "private_key_file", type = OoaType.FILE, description = "Exported private key, or empty.")
    public Map<String, Object> createKeys(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, example = "Finance Agent", description = "Key owner name.") String name,
            @Input(name = "email", type = OoaType.STRING, example = "finance-agent@example.com", description = "Key owner email address.") String email,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, required = false, description = "Passphrase that protects the private key; strongly recommended.") Object pass,
            @Input(name = "comment", type = OoaType.STRING, required = false, description = "Optional comment stored in the user id.") String comment,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"ed25519\"", enumValues = {"ed25519", "rsa3072", "rsa4096"},
                    description = "Key type: ed25519 (modern, small) or RSA for older systems.") String algorithm,
            @Input(name = "expires", type = OoaType.STRING, required = false, defaultValue = "\"2y\"", description = "Lifetime, e.g. 1y, 6m, 90d, a date like 2028-12-31, or never.") String expires,
            @Input(name = "public_key_file", type = OoaType.FILE, required = false, description = "Where to save the armored public key (.asc) to share with partners.") String publicFile,
            @Input(name = "private_key_file", type = OoaType.FILE, required = false, description = "Where to save the armored private key (.asc) as a backup; keep it secret.") String privateFile,
            @Input(name = "gnupg_home", type = OoaType.FILE, required = false, description = D_HOME) String home) {
        String uid = userId(name, email, comment);
        String[] algo = algorithms(algorithm);
        String exp = expiry(expires);
        String stdin = (pass instanceof SecureString s ? s.reveal() : "") + "\n";
        if (!(pass instanceof SecureString)) ctx.warn("Creating a PGP key without a passphrase; anyone who gets the key file can use it.");
        List<String> loop = List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0");
        List<String> gen = new ArrayList<>(loop);
        gen.addAll(List.of("--quick-gen-key", uid, algo[0], "sign,cert", exp));
        ProcessRunner.Result r = gpg(ctx, home, gen, stdin);
        String fpr = null;
        for (String line : r.stdout().split("\n")) if (line.startsWith("[GNUPG:] KEY_CREATED ")) { String[] p = line.trim().split(" "); fpr = p[p.length - 1]; }
        if (r.exitCode() != 0 || fpr == null)
            throw new ActionException("PGP.KEYGEN_FAILED", "Key generation failed: " + ApplicationPackage.tail(r.stderr()), "Check GnuPG 2.1 or later is installed and the key folder is writable.");
        List<String> sub = new ArrayList<>(loop);
        sub.addAll(List.of("--quick-add-key", fpr, algo[1], "encr", exp));
        r = gpg(ctx, home, sub, stdin);
        if (r.exitCode() != 0)
            throw new ActionException("PGP.KEYGEN_FAILED", "Adding the encryption subkey failed: " + ApplicationPackage.tail(r.stderr()), "Check the passphrase and GnuPG version.");
        String pub = "", priv = "";
        if (publicFile != null && !publicFile.isBlank()) {
            pub = out(ctx, publicFile);
            r = gpg(ctx, home, List.of("--armor", "-o", pub, "--export", fpr), null);
            if (r.exitCode() != 0) throw new ActionException("PGP.EXPORT_FAILED", "Exporting the public key failed: " + ApplicationPackage.tail(r.stderr()), null);
        }
        if (privateFile != null && !privateFile.isBlank()) {
            priv = out(ctx, privateFile);
            List<String> ex = new ArrayList<>(loop);
            ex.addAll(List.of("--armor", "-o", priv, "--export-secret-keys", fpr));
            r = gpg(ctx, home, ex, stdin);
            if (r.exitCode() != 0) throw new ActionException("PGP.EXPORT_FAILED", "Exporting the private key failed: " + ApplicationPackage.tail(r.stderr()), "Check the passphrase.");
        }
        ctx.info("Created PGP key " + fpr + " for " + uid);
        return Out.of("fingerprint", fpr, "user_id", uid, "public_key_file", pub, "private_key_file", priv);
    }

}
