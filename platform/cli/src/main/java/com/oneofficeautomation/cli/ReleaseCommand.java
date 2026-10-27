package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Signed release manifests (S6). The vendor signs a list of the release's files (name, size, SHA-256) with the same
 * Ed25519 key that signs licenses; customers check a download against it with the public key built into the product:
 * <pre>
 *   ooa release verify ooa-0.2.0.manifest.json [--dir DOWNLOADS]     everyone: are these the vendor's files, unchanged?
 *   ooa release sign --dir DIST --version 0.2.0 --key private.key [--out FILE]   vendor build only
 * </pre>
 * The manifest is {format: ooa-release-1, payload: Base64 of the JSON list, signature}; nothing outside the payload is
 * trusted.
 */
final class ReleaseCommand {
    private ReleaseCommand() { }

    static final String FORMAT = "ooa-release-1";

    /** ooa release verify MANIFEST [--dir DIR]: the folder defaults to the manifest's. */
    static int verify(String manifestFile, String dirOption) throws Exception {
        Path manifest = Path.of(manifestFile);
        if (!Files.isRegularFile(manifest)) { System.err.println("No manifest at " + manifest.toAbsolutePath()); return 2; }
        Path dir = dirOption != null ? Path.of(dirOption) : manifest.toAbsolutePath().getParent();
        try {
            List<String> lines = verify(Files.readString(manifest), License.publicKey(License.VENDOR_PUBLIC_KEY), dir);
            lines.forEach(System.out::println);
            return lines.stream().anyMatch(l -> l.startsWith("FAIL")) ? 2 : 0;
        } catch (OoaException e) { System.err.println(e.getMessage()); return 2; }
    }

    /** ooa release sign --dir DIR --version V --key private.key [--out FILE] (vendor build). */
    static int sign(Map<String, String> o) throws Exception {
        Path dir = Path.of(o.get("dir"));
        String text = sign(dir, o.get("version"), License.privateKey(Files.readString(Path.of(o.get("key")))));
        Path out = Path.of(o.getOrDefault("out", dir.resolve("ooa-" + o.get("version") + ".manifest.json").toString()));
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("Signed manifest written: " + out);
        return 0;
    }

    /** The signed manifest of every release file in dir (zip, msi, json, txt; not manifests themselves). */
    static String sign(Path dir, String version, PrivateKey key) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (!Files.isRegularFile(p) || n.endsWith(".manifest.json") || !n.matches("(?i).+\\.(zip|msi|json|txt|cer)")) continue;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", n); f.put("size", Files.size(p)); f.put("sha256", sha256(p));
                files.add(f);
            }
        }
        if (files.isEmpty()) throw new OoaException("RELEASE.EMPTY", "No release files (.zip, .msi, .json, .txt) in " + dir);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product", "ONEOFFICEAUTOMATION");
        body.put("version", version);
        body.put("created", Instant.now().toString());
        body.put("files", files);
        byte[] payload = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(payload);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", FORMAT);
            m.put("payload", Base64.getEncoder().encodeToString(payload));
            m.put("signature", Base64.getEncoder().encodeToString(sig.sign()));
            return JsonLite.write(m);
        } catch (GeneralSecurityException e) { throw new OoaException("RELEASE.SIGN_FAILED", "Signing failed: " + e.getMessage()); }
    }

    /**
     * Checks the signature, then each listed file that is present in dir: PASS, FAIL (changed) or MISSING lines. A bad
     * signature throws: then nothing in the manifest can be trusted.
     */
    @SuppressWarnings("unchecked")
    static List<String> verify(String text, PublicKey key, Path dir) throws IOException {
        Map<String, Object> body = readManifest(text, key);
        List<String> out = new ArrayList<>();
        out.add("Signed by ONEOFFICEAUTOMATION: release " + body.get("version") + " of " + body.get("created"));
        for (Map<String, Object> f : (List<Map<String, Object>>) body.get("files")) {
            String name = String.valueOf(f.get("name"));
            if (name.contains("/") || name.contains("\\") || name.contains("..")) { out.add("FAIL     " + name + " (not a plain file name)"); continue; }
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) { out.add("MISSING  " + name); continue; }
            out.add(sha256(file).equalsIgnoreCase(String.valueOf(f.get("sha256"))) && Files.size(file) == ((Number) f.get("size")).longValue()
                ? "PASS     " + name : "FAIL     " + name + " (changed: do not install it)");
        }
        return out;
    }

    /** The signed content of a manifest (product, version, created, files), or an exception when its signature is not valid. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readManifest(String text, PublicKey key) {
        Map<String, Object> m;
        try { m = (Map<String, Object>) JsonLite.parse(text.trim()); } catch (RuntimeException e) { throw new OoaException("RELEASE.BAD_MANIFEST", "Not a ONEOFFICEAUTOMATION release manifest"); }
        if (!FORMAT.equals(m.get("format")) || !(m.get("payload") instanceof String p) || !(m.get("signature") instanceof String s))
            throw new OoaException("RELEASE.BAD_MANIFEST", "Not a ONEOFFICEAUTOMATION release manifest");
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(p);
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(payload);
            if (!v.verify(Base64.getDecoder().decode(s))) throw new OoaException("RELEASE.BAD_SIGNATURE", "The manifest's signature is not valid: it was changed or not made by ONEOFFICEAUTOMATION");
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("RELEASE.BAD_SIGNATURE", "The manifest's signature is not valid: it was changed or not made by ONEOFFICEAUTOMATION"); }
        return (Map<String, Object>) JsonLite.parse(new String(payload, StandardCharsets.UTF_8));
    }

    static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
