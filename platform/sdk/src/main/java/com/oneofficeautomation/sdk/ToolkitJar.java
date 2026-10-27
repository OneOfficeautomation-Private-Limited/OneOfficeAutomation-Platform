package com.oneofficeautomation.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A custom toolkit packed as a jar (K6). The jar holds the toolkit's classes and META-INF/ooa-toolkit.json, which names the
 * toolkit class and carries the publisher's Ed25519 signature over PackageSigning's digest (the manifest and the class
 * bytes). The jar is loaded in a class loader of its own, so versions of the same toolkit sit side by side.
 */
public final class ToolkitJar {
    private ToolkitJar() {}

    public static final String ENTRY = "META-INF/ooa-toolkit.json";

    /** A toolkit class read from its jar, with the signature the jar carries; install it with PackageRegistry.install. */
    public record Loaded(Class<?> cls, PackageSigning.Signature signature, String sha256) {}

    public static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The descriptor in the jar (class, publisher, digest, signature), or an error saying the jar is not a signed toolkit. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> descriptor(byte[] jar) {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; )
                if (e.getName().equals(ENTRY)) return (Map<String, Object>) JsonLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new OoaException("PKG.BAD_JAR", "The file is not a toolkit jar: " + e.getMessage(), "Build the toolkit with the OOA SDK and sign it with ooa toolkit sign.");
        }
        throw new OoaException("PKG.UNSIGNED", "The jar has no " + ENTRY, "Sign it first: ooa toolkit sign --jar <file> --class <toolkit class> --key <private key file>.");
    }

    /** Loads the toolkit class from the jar file in a class loader of its own (the SDK comes from the parent). */
    public static Loaded load(Path jarFile, ClassLoader parent) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(jarFile); } catch (IOException e) { throw new OoaException("PKG.BAD_JAR", "Cannot read " + jarFile + ": " + e.getMessage()); }
        Map<String, Object> d = descriptor(bytes);
        String cls = String.valueOf(d.get("class"));
        try {
            URLClassLoader loader = new URLClassLoader("ooa-toolkit-" + sha256(bytes).substring(0, 12), new URL[]{jarFile.toUri().toURL()}, parent);
            Class<?> c = Class.forName(cls, true, loader);
            if (c.getClassLoader() != loader) throw new OoaException("PKG.BAD_JAR", "The class " + cls + " is not in the jar", "Pack the toolkit classes into the jar.");
            PackageSigning.Signature sig = d.get("signature") == null ? null
                : new PackageSigning.Signature(String.valueOf(d.get("publisher")), String.valueOf(d.get("digest")), String.valueOf(d.get("signature")));
            return new Loaded(c, sig, sha256(bytes));
        } catch (ClassNotFoundException | LinkageError | IOException e) {
            throw new OoaException("PKG.BAD_JAR", "Cannot load " + cls + " from the jar: " + e, "Check the class name and that the jar has everything the toolkit needs.");
        }
    }

    /** Signs the toolkit class in the jar with the publisher's key and writes the signed jar to out (the jar itself when out is null). */
    public static PackageSigning.Signature sign(Path jarFile, String className, PackageSigning.PublisherKey key, Path out) throws IOException {
        byte[] bytes = Files.readAllBytes(jarFile);
        Path tmp = Files.createTempFile("ooa-sign", ".jar");
        try {
            Files.write(tmp, bytes);
            PackageSigning.Signature sig;
            try (URLClassLoader loader = new URLClassLoader(new URL[]{tmp.toUri().toURL()}, ToolkitJar.class.getClassLoader())) {
                sig = PackageSigning.sign(Class.forName(className, true, loader), key);
            } catch (ClassNotFoundException e) {
                throw new OoaException("PKG.BAD_JAR", "The class " + className + " is not in the jar");
            }
            Map<String, Object> d = new LinkedHashMap<>(Map.of("class", className));
            d.putAll(sig.toMap());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(bytes)); ZipOutputStream zo = new ZipOutputStream(buf)) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    if (e.getName().equals(ENTRY)) continue;
                    zo.putNextEntry(new ZipEntry(e.getName()));
                    in.transferTo(zo);
                    zo.closeEntry();
                }
                zo.putNextEntry(new ZipEntry(ENTRY));
                zo.write(JsonLite.write(d).getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
            Files.write(out == null ? jarFile : out, buf.toByteArray());
            return sig;
        } finally { Files.deleteIfExists(tmp); }
    }

    /** A publisher's public key as Base64 (X.509), the form the Command Center's trusted publishers take. */
    public static String encode(PublicKey key) { return Base64.getEncoder().encodeToString(key.getEncoded()); }

    public static PublicKey publicKey(String b64) {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim()))); }
        catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new OoaException("PKG.BAD_KEY", "Not an Ed25519 public key", "Paste the public key file ooa toolkit keygen wrote (one Base64 line).");
        }
    }

    /** Writes a new publisher key pair: name.key (private, keep it safe) and name.pub (give it to Command Center admins). */
    public static PackageSigning.PublisherKey keygen(String publisher, Path dir) throws IOException {
        PackageSigning.PublisherKey k = PackageSigning.PublisherKey.generate(publisher);
        Files.createDirectories(dir);
        Path priv = dir.resolve(publisher + ".key");
        if (Files.exists(priv)) throw new OoaException("PKG.KEY_EXISTS", priv + " already exists", "Keep the key you have; a new key means admins must trust it again.");
        Files.writeString(priv, publisher + "\n" + Base64.getEncoder().encodeToString(k.privateKey().getEncoded()) + "\n" + encode(k.publicKey()) + "\n");
        Files.writeString(dir.resolve(publisher + ".pub"), encode(k.publicKey()) + "\n");
        return k;
    }

    /** Reads a key file keygen wrote. */
    public static PackageSigning.PublisherKey readKey(Path file) throws IOException {
        List<String> l = Files.readAllLines(file).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (l.size() < 3) throw new OoaException("PKG.BAD_KEY", file + " is not a publisher key file");
        try {
            PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(l.get(1))));
            return new PackageSigning.PublisherKey(l.get(0), pk, publicKey(l.get(2)));
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new OoaException("PKG.BAD_KEY", file + " is not a publisher key file"); }
    }
}
