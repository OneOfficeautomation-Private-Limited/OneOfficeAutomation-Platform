package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@OoaPackage(id = "ooa.crypto", name = "Hash, encryption and encoding", version = "1.0.0", description = "Hashes, HMAC signatures, AES-256 encryption of text and files, and Base64 encoding.")
public final class CryptoPackage {

    private static String algo(String a) {
        return switch (a == null ? "SHA-256" : a) { case "SHA-1" -> "SHA-1"; case "SHA-512" -> "SHA-512"; case "SHA-384" -> "SHA-384"; case "SHA3-256" -> "SHA3-256"; case "MD5" -> "MD5"; default -> "SHA-256"; };
    }

    /** Digest as lower-case hex (default), upper-case HEX or Base64. */
    static String format(byte[] digest, String outputFormat) {
        return switch (outputFormat == null || outputFormat.isBlank() ? "hex" : outputFormat) {
            case "hex" -> HexFormat.of().formatHex(digest);
            case "hex_upper" -> HexFormat.of().withUpperCase().formatHex(digest);
            case "base64" -> Base64.getEncoder().encodeToString(digest);
            default -> throw new ActionException("CRYPTO.BAD_FORMAT", "Unknown output format '" + outputFormat + "'", "Use hex, hex_upper or base64.");
        };
    }

    private static final String D_FORMAT = "How the result is written: hex (lower case), hex_upper or base64.";
    private static final String D_ENC = "Character encoding used to turn the text into bytes, e.g. utf-8, utf-16le, windows-1252.";

    @Action(id = "hash_text", name = "Hash text", category = "Hash", idempotent = true, description = "Return the hex hash of a text (UTF-8).")
    @Output(name = "hash", type = OoaType.STRING, description = "Lower-case hex digest.")
    public Map<String, Object> hashText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to hash.") String text,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"SHA-256\"", enumValues = {"SHA-256", "SHA-384", "SHA-512", "SHA3-256", "SHA-1", "MD5"},
                    description = "Hash algorithm; SHA-1 and MD5 only for legacy checks.") String a,
            @Input(name = "output_format", type = OoaType.STRING, required = false, defaultValue = "\"hex\"", enumValues = {"hex", "hex_upper", "base64"}, description = D_FORMAT) String fmt,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = D_ENC) String enc) throws NoSuchAlgorithmException {
        return Map.of("hash", format(MessageDigest.getInstance(algo(a)).digest(text.getBytes(FilePackage.charset(enc))), fmt));
    }

    @Action(id = "hash_file", name = "Hash file", category = "Hash", idempotent = true, capabilities = "filesystem", description = "Return the hex hash of a file, e.g. to verify a download.")
    @Output(name = "hash", type = OoaType.STRING, description = "Lower-case hex digest.")
    public Map<String, Object> hashFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"SHA-256\"", enumValues = {"SHA-256", "SHA-384", "SHA-512", "SHA3-256", "SHA-1", "MD5"},
                    description = "Hash algorithm.") String a,
            @Input(name = "output_format", type = OoaType.STRING, required = false, defaultValue = "\"hex\"", enumValues = {"hex", "hex_upper", "base64"}, description = D_FORMAT) String fmt) throws NoSuchAlgorithmException {
        Path p = ctx.resolvePath(path);
        MessageDigest md = MessageDigest.getInstance(algo(a));
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("hash", format(md.digest(), fmt));
    }

    @Action(id = "hmac", name = "HMAC signature", category = "Hash", idempotent = true,
            description = "Compute an HMAC-SHA256 signature with a secret key from the vault, e.g. to sign or verify webhooks.")
    @Output(name = "signature", type = OoaType.STRING, description = "Hex signature.")
    public Map<String, Object> hmac(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Message.") String text,
            @Input(name = "key", type = OoaType.CREDENTIAL, description = "Secret key from the vault.") SecureString key,
            @Input(name = "algorithm", type = OoaType.STRING, required = false, defaultValue = "\"SHA-256\"", enumValues = {"SHA-256", "SHA-384", "SHA-512", "SHA-1"}, description = "Hash used inside the HMAC.") String a,
            @Input(name = "output_format", type = OoaType.STRING, required = false, defaultValue = "\"hex\"", enumValues = {"hex", "hex_upper", "base64"}, description = D_FORMAT) String fmt,
            @Input(name = "key_is_base64", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The key is Base64 text to decode into bytes (as some services issue it).") boolean keyBase64) throws GeneralSecurityException {
        String alg = switch (a == null || a.isBlank() ? "SHA-256" : a) {
            case "SHA-256" -> "HmacSHA256"; case "SHA-384" -> "HmacSHA384"; case "SHA-512" -> "HmacSHA512"; case "SHA-1" -> "HmacSHA1";
            default -> throw new ActionException("CRYPTO.BAD_ALGORITHM", "Unknown HMAC algorithm '" + a + "'", "Use SHA-256, SHA-384, SHA-512 or SHA-1.");
        };
        byte[] k;
        try { k = keyBase64 ? Base64.getDecoder().decode(key.reveal().strip()) : key.reveal().getBytes(StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new ActionException("CRYPTO.BAD_KEY", "The key is not valid Base64", "Turn off key_is_base64 or check the key."); }
        Mac mac = Mac.getInstance(alg);
        mac.init(new SecretKeySpec(k, alg));
        return Map.of("signature", format(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)), fmt));
    }

    @Action(id = "base64_encode", name = "Base64 encode", category = "Encoding", idempotent = true, description = "Encode text as Base64.")
    @Output(name = "result", type = OoaType.STRING, description = "Base64 text.")
    public Map<String, Object> encode(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to encode.") String text,
            @Input(name = "url_safe", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Use the URL-safe alphabet (- and _ instead of + and /) without padding.") boolean urlSafe,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = D_ENC) String enc) {
        byte[] b = text.getBytes(FilePackage.charset(enc));
        return Map.of("result", urlSafe ? Base64.getUrlEncoder().withoutPadding().encodeToString(b) : Base64.getEncoder().encodeToString(b));
    }

    @Action(id = "base64_decode", name = "Base64 decode", category = "Encoding", idempotent = true, description = "Decode Base64 into text.")
    @Output(name = "result", type = OoaType.STRING, description = "Decoded text.")
    public Map<String, Object> decode(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Base64 text (standard or URL-safe).") String text,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Character encoding of the decoded text.") String enc) {
        String t = text.trim().replaceAll("\\s", "");
        try { return Map.of("result", new String(t.contains("-") || t.contains("_") ? Base64.getUrlDecoder().decode(t) : Base64.getDecoder().decode(t), FilePackage.charset(enc))); }
        catch (IllegalArgumentException e) { throw new ActionException("ENCODING.INVALID", "Not valid Base64: " + e.getMessage(), "Check the input is Base64 text."); }
    }

    private static final byte[] MAGIC = "OOA1".getBytes(StandardCharsets.US_ASCII);

    /** AES-256-GCM with a key derived from the passphrase (PBKDF2-SHA256, 210,000 rounds, random salt and IV). */
    static byte[] aes(boolean encrypt, byte[] data, char[] passphrase) throws GeneralSecurityException {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        byte[] salt = new byte[16], iv = new byte[12];
        int off = 0;
        if (encrypt) { rnd.nextBytes(salt); rnd.nextBytes(iv); }
        else {
            if (data.length < 4 + 16 + 12 + 16 || !Arrays.equals(Arrays.copyOf(data, 4), MAGIC))
                throw new ActionException("CRYPTO.NOT_ENCRYPTED", "The data was not encrypted by this toolkit", "Use data produced by Encrypt text or Encrypt file.");
            System.arraycopy(data, 4, salt, 0, 16); System.arraycopy(data, 20, iv, 0, 12); off = 32;
        }
        SecretKeySpec key = new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(passphrase, salt, 210_000, 256)).getEncoded(), "AES");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        if (!encrypt) {
            try { return c.doFinal(data, off, data.length - off); }
            catch (javax.crypto.AEADBadTagException e) { throw new ActionException("CRYPTO.WRONG_KEY", "Decryption failed: wrong passphrase or the data was changed", "Check the passphrase in the vault."); }
        }
        byte[] ct = c.doFinal(data);
        byte[] out = new byte[32 + ct.length];
        System.arraycopy(MAGIC, 0, out, 0, 4); System.arraycopy(salt, 0, out, 4, 16); System.arraycopy(iv, 0, out, 20, 12); System.arraycopy(ct, 0, out, 32, ct.length);
        return out;
    }

    @Action(id = "encrypt_text", name = "Encrypt text", category = "Encryption", description = "Encrypt text with AES-256 using a passphrase from the vault; returns Base64.")
    @Output(name = "result", type = OoaType.STRING, description = "Encrypted text (Base64).")
    public Map<String, Object> encryptText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to encrypt.") String text,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, description = "Passphrase from the vault.") SecureString key) throws GeneralSecurityException {
        return Map.of("result", Base64.getEncoder().encodeToString(aes(true, text.getBytes(StandardCharsets.UTF_8), key.reveal().toCharArray())));
    }

    @Action(id = "decrypt_text", name = "Decrypt text", category = "Encryption", description = "Decrypt text produced by Encrypt text.")
    @Output(name = "result", type = OoaType.CREDENTIAL, description = "Decrypted text, kept as a Credential so it is never logged.")
    public Map<String, Object> decryptText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Encrypted Base64 text.") String text,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, description = "Passphrase from the vault.") SecureString key) throws GeneralSecurityException {
        byte[] data;
        try { data = Base64.getDecoder().decode(text.trim()); } catch (IllegalArgumentException e) { throw new ActionException("CRYPTO.NOT_ENCRYPTED", "Not valid Base64 text"); }
        return Map.of("result", new SecureString(new String(aes(false, data, key.reveal().toCharArray()), StandardCharsets.UTF_8), "decrypted"));
    }

    @Action(id = "encrypt_file", name = "Encrypt file", category = "Encryption", capabilities = "filesystem", description = "Encrypt a file with AES-256 into a new file.")
    public Map<String, Object> encryptFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to encrypt.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Encrypted file to create.") String target,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, description = "Passphrase from the vault.") SecureString key,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing target file.") boolean overwrite) throws GeneralSecurityException {
        return fileOp(ctx, true, path, target, key, overwrite);
    }

    @Action(id = "decrypt_file", name = "Decrypt file", category = "Encryption", capabilities = "filesystem", description = "Decrypt a file produced by Encrypt file.")
    public Map<String, Object> decryptFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Encrypted file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Decrypted file to create.") String target,
            @Input(name = "passphrase", type = OoaType.CREDENTIAL, description = "Passphrase from the vault.") SecureString key,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing target file.") boolean overwrite) throws GeneralSecurityException {
        return fileOp(ctx, false, path, target, key, overwrite);
    }

    private Map<String, Object> fileOp(ActionContext ctx, boolean enc, String path, String target, SecureString key, boolean overwrite) throws GeneralSecurityException {
        Path in = ctx.resolvePath(path), out = ctx.resolvePath(target);
        if (!overwrite && Files.exists(out)) throw new ActionException("FILE.EXISTS", "File already exists: " + out, "Set overwrite to true or choose another name.");
        try {
            byte[] r = aes(enc, Files.readAllBytes(in), key.reveal().toCharArray());
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, r);
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + in, "Check the path.");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of();
    }

    // ---------- RSA (PEM keys: PKCS#8 private, X.509 public) ----------
    static java.security.PublicKey publicKey(Path p) throws GeneralSecurityException, IOException {
        String pem = Files.readString(p).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    static java.security.PrivateKey privateKey(Path p) throws GeneralSecurityException, IOException {
        String text = Files.readString(p);
        if (text.contains("ENCRYPTED") || text.contains("BEGIN RSA PRIVATE KEY"))
            throw new ActionException("CRYPTO.KEY_FORMAT", "Use an unencrypted PKCS#8 private key (BEGIN PRIVATE KEY)", "Convert with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem; store the file in a protected folder.");
        String pem = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private static Cipher oaep() throws GeneralSecurityException { return Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"); }

    @Action(id = "rsa_generate_keys", name = "Generate RSA key pair", category = "RSA", capabilities = "filesystem", description = "Create public.pem and private.pem (RSA 3072-bit) in a folder; keep the private key protected.")
    @Output(name = "public_key", type = OoaType.FILE, description = "Public key path.")
    @Output(name = "private_key", type = OoaType.FILE, description = "Private key path.")
    public Map<String, Object> rsaGenerate(ActionContext ctx, @Input(name = "folder", type = OoaType.FILE, description = "Folder for the key files.") String folder) throws GeneralSecurityException {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(3072);
        java.security.KeyPair kp = g.generateKeyPair();
        Path dir = ctx.resolvePath(folder), pub = dir.resolve("public.pem"), priv = dir.resolve("private.pem");
        Base64.Encoder enc = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        try {
            Files.createDirectories(dir);
            if (Files.exists(priv)) throw new ActionException("CRYPTO.KEY_EXISTS", "private.pem already exists in " + dir, "Choose another folder; existing keys are never overwritten.");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n" + enc.encodeToString(kp.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
            Files.writeString(priv, "-----BEGIN PRIVATE KEY-----\n" + enc.encodeToString(kp.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        return Map.of("public_key", pub.toString(), "private_key", priv.toString());
    }

    @Action(id = "rsa_encrypt", name = "RSA encrypt text", category = "RSA", description = "Encrypt a short text (up to about 300 characters) with a public key (RSA-OAEP SHA-256); returns Base64.")
    @Output(name = "result", type = OoaType.STRING, description = "Encrypted Base64 text.")
    public Map<String, Object> rsaEncrypt(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to encrypt.") String text,
            @Input(name = "public_key", type = OoaType.FILE, description = "Public key PEM file.") String key) throws GeneralSecurityException {
        try {
            Cipher c = oaep(); c.init(Cipher.ENCRYPT_MODE, publicKey(ctx.resolvePath(key)));
            return Map.of("result", Base64.getEncoder().encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8))));
        } catch (javax.crypto.IllegalBlockSizeException e) { throw new ActionException("CRYPTO.TOO_LONG", "Text is too long for RSA", "Encrypt the text with AES and the AES passphrase with RSA.");
        } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read key: " + e.getMessage(), "Check the key path."); }
    }

    @Action(id = "rsa_decrypt", name = "RSA decrypt text", category = "RSA", description = "Decrypt Base64 text encrypted with the matching public key.")
    @Output(name = "result", type = OoaType.CREDENTIAL, description = "Decrypted text, kept as a Credential.")
    public Map<String, Object> rsaDecrypt(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Encrypted Base64 text.") String text,
            @Input(name = "private_key", type = OoaType.FILE, description = "Private key PEM file (PKCS#8).") String key) throws GeneralSecurityException {
        try {
            Cipher c = oaep(); c.init(Cipher.DECRYPT_MODE, privateKey(ctx.resolvePath(key)));
            return Map.of("result", new SecureString(new String(c.doFinal(Base64.getDecoder().decode(text.trim())), StandardCharsets.UTF_8), "rsa"));
        } catch (javax.crypto.BadPaddingException e) { throw new ActionException("CRYPTO.WRONG_KEY", "Decryption failed: wrong key or damaged data", null);
        } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read key: " + e.getMessage(), "Check the key path."); }
    }

    @Action(id = "rsa_sign", name = "RSA sign", category = "RSA", description = "Sign text with a private key (SHA256withRSA); returns a Base64 signature.")
    @Output(name = "signature", type = OoaType.STRING, description = "Base64 signature.")
    public Map<String, Object> rsaSign(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to sign.") String text,
            @Input(name = "private_key", type = OoaType.FILE, description = "Private key PEM file (PKCS#8).") String key) throws GeneralSecurityException {
        try {
            java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
            s.initSign(privateKey(ctx.resolvePath(key))); s.update(text.getBytes(StandardCharsets.UTF_8));
            return Map.of("signature", Base64.getEncoder().encodeToString(s.sign()));
        } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read key: " + e.getMessage(), "Check the key path."); }
    }

    @Action(id = "rsa_verify", name = "RSA verify", category = "RSA", idempotent = true, description = "Check an RSA signature with the public key; returns the result instead of failing.")
    @Output(name = "valid", type = OoaType.BOOLEAN, description = "True when the signature matches.")
    public Map<String, Object> rsaVerify(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Signed text.") String text,
            @Input(name = "signature", type = OoaType.STRING, description = "Base64 signature.") String sig,
            @Input(name = "public_key", type = OoaType.FILE, description = "Public key PEM file.") String key) throws GeneralSecurityException {
        try {
            java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
            s.initVerify(publicKey(ctx.resolvePath(key))); s.update(text.getBytes(StandardCharsets.UTF_8));
            return Map.of("valid", s.verify(Base64.getDecoder().decode(sig.trim())));
        } catch (IllegalArgumentException | java.security.SignatureException e) { return Map.of("valid", false);
        } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read key: " + e.getMessage(), "Check the key path."); }
    }
}
