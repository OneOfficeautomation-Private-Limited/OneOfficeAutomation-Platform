package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.bouncycastle.bcpg.*;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.*;

import java.io.*;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * OpenPGP without GnuPG (P1, command review batch 8), with Bouncy Castle: create keys, import, encrypt, decrypt, sign
 * and verify, with the same inputs, outputs and error codes as the GnuPG way. Used when GnuPG is not installed on the
 * runner. Keys live in a key folder like GnuPG's keyring (pubring.asc and secring.asc): the step's gnupg_home, or
 * .ooa/pgp-keys in the runner account's home. Keys made are the kind GnuPG makes (Ed25519 with a Curve25519
 * encryption subkey, or RSA), so files and keys go both ways between this and GnuPG.
 */
final class PgpBuiltIn {
    private PgpBuiltIn() {}

    static final java.security.Provider BC = new BouncyCastleProvider();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JcaKeyFingerprintCalculator FP = new JcaKeyFingerprintCalculator();

    // ------------------------------------------------------------------ key folder

    /** The key folder: gnupg_home when given, otherwise one per runner account (as GnuPG's own keyring is). */
    static Path keyFolder(ActionContext ctx, String home) {
        Path p = home != null && !home.isBlank() ? ctx.resolvePath(home)
                : Path.of(System.getProperty("ooa.pgp.keys", Path.of(System.getProperty("user.home"), ".ooa", "pgp-keys").toString()));
        try { Files.createDirectories(p); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot create the key folder " + p + ": " + e.getMessage(), "Check that the runner's account may create the key folder."); }
        return p;
    }

    record Keys(PGPPublicKeyRingCollection pub, PGPSecretKeyRingCollection sec) {}

    static Keys load(Path folder) {
        try {
            PGPPublicKeyRingCollection pub = new PGPPublicKeyRingCollection(List.of());
            PGPSecretKeyRingCollection sec = new PGPSecretKeyRingCollection(List.of());
            Path p = folder.resolve("pubring.asc"), s = folder.resolve("secring.asc");
            if (Files.isRegularFile(p)) try (InputStream in = PGPUtil.getDecoderStream(Files.newInputStream(p))) { pub = new PGPPublicKeyRingCollection(in, FP); }
            if (Files.isRegularFile(s)) try (InputStream in = PGPUtil.getDecoderStream(Files.newInputStream(s))) { sec = new PGPSecretKeyRingCollection(in, FP); }
            return new Keys(pub, sec);
        } catch (IOException | PGPException e) {
            throw new ActionException("PGP.KEYRING_FAILED", "Cannot read the keys in " + folder + ": " + e.getMessage(), "Check the key folder holds keys written by this toolkit.");
        }
    }

    static void save(Path folder, Keys k) {
        try {
            write(folder.resolve("pubring.asc"), k.pub().getEncoded());
            write(folder.resolve("secring.asc"), k.sec().getEncoded());
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write the keys in " + folder + ": " + e.getMessage(), "Check that the runner's account may write the key folder."); }
    }

    private static void write(Path file, byte[] encoded) {
        try (OutputStream o = new ArmoredOutputStream(Files.newOutputStream(file))) { o.write(encoded); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + file + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it."); }
    }

    /** Public and secret key rings read from a key file (armored or binary, one or several keys). */
    static Keys readKeyFile(Path file) {
        List<PGPPublicKeyRing> pubs = new ArrayList<>();
        List<PGPSecretKeyRing> secs = new ArrayList<>();
        try (InputStream in = PGPUtil.getDecoderStream(new BufferedInputStream(Files.newInputStream(file)))) {
            JcaPGPObjectFactory f = new JcaPGPObjectFactory(in);
            for (Object o; (o = nextOrNull(f)) != null; ) {
                if (o instanceof PGPPublicKeyRing r) pubs.add(r);
                else if (o instanceof PGPSecretKeyRing r) { secs.add(r); pubs.add(publicOf(r)); }
            }
            return new Keys(new PGPPublicKeyRingCollection(pubs), new PGPSecretKeyRingCollection(secs));
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + file, "Check the path.");
        } catch (IOException | RuntimeException e) {
            throw new ActionException("PGP.IMPORT_FAILED", "No key was read from " + file.getFileName() + ": " + e.getMessage(), "Check the file is an OpenPGP key.");
        }
    }

    private static Object nextOrNull(JcaPGPObjectFactory f) throws IOException {
        try { return f.nextObject(); } catch (IOException e) { if (String.valueOf(e.getMessage()).contains("unknown object")) return null; throw e; }
    }

    static PGPPublicKeyRing publicOf(PGPSecretKeyRing r) {
        List<PGPPublicKey> keys = new ArrayList<>();
        for (Iterator<PGPPublicKey> it = r.getPublicKeys(); it.hasNext(); ) keys.add(it.next());
        return new PGPPublicKeyRing(keys);
    }

    static String fingerprint(PGPPublicKey k) { return HexFormat.of().withUpperCase().formatHex(k.getFingerprint()); }

    static String userIdOf(PGPPublicKey master) {
        Iterator<String> ids = master.getUserIDs();
        return ids.hasNext() ? ids.next() : "";
    }

    /** True when a key ring is the one a recipient or key text names: fingerprint, key id (long or short, 0x allowed) or text in a user id. */
    static boolean matches(PGPPublicKeyRing ring, String wanted) {
        String w = wanted.strip();
        String hex = w.replaceFirst("(?i)^0x", "").replace(" ", "").toUpperCase(Locale.ROOT);
        for (Iterator<PGPPublicKey> it = ring.getPublicKeys(); it.hasNext(); ) {
            PGPPublicKey k = it.next();
            String fpr = fingerprint(k);
            if (hex.matches("[0-9A-F]{8,40}") && fpr.endsWith(hex)) return true;
        }
        String low = w.toLowerCase(Locale.ROOT);
        for (Iterator<String> ids = ring.getPublicKey().getUserIDs(); ids.hasNext(); ) if (ids.next().toLowerCase(Locale.ROOT).contains(low)) return true;
        return false;
    }

    // ------------------------------------------------------------------ create and import

    static Map<String, Object> createKeys(ActionContext ctx, String uid, String algorithm, String expires, SecureString pass, String publicFile, String privateFile, String home) {
        try {
            Date now = new Date();
            PGPKeyPair primary, sub;
            String a = algorithm == null || algorithm.isBlank() ? "ed25519" : algorithm;
            if (a.equals("ed25519")) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519", BC);
                primary = new JcaPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, g.generateKeyPair(), now);
                KeyPairGenerator x = KeyPairGenerator.getInstance("X25519", BC);
                sub = new JcaPGPKeyPair(PublicKeyAlgorithmTags.ECDH, x.generateKeyPair(), now);
            } else {
                int bits = a.equals("rsa4096") ? 4096 : 3072;
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", BC);
                g.initialize(bits, RANDOM);
                KeyPair kp = g.generateKeyPair(), kp2 = g.generateKeyPair();
                primary = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, kp, now);
                sub = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, kp2, now);
            }
            long life = lifetimeSeconds(expires, now);
            PGPSignatureSubpacketGenerator ph = new PGPSignatureSubpacketGenerator();
            ph.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
            ph.setPreferredSymmetricAlgorithms(false, new int[]{SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_192, SymmetricKeyAlgorithmTags.AES_128});
            ph.setPreferredHashAlgorithms(false, new int[]{HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA256});
            ph.setPreferredCompressionAlgorithms(false, new int[]{CompressionAlgorithmTags.ZLIB, CompressionAlgorithmTags.BZIP2, CompressionAlgorithmTags.ZIP, CompressionAlgorithmTags.UNCOMPRESSED});
            ph.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);
            if (life > 0) ph.setKeyExpirationTime(false, life);
            PGPSignatureSubpacketGenerator sh = new PGPSignatureSubpacketGenerator();
            sh.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);
            if (life > 0) sh.setKeyExpirationTime(false, life);
            PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder().setProvider(BC).build().get(HashAlgorithmTags.SHA1);
            PGPDigestCalculator sha256 = new JcaPGPDigestCalculatorProviderBuilder().setProvider(BC).build().get(HashAlgorithmTags.SHA256);
            var encryptor = pass == null ? null
                    : new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha256, 0x90).setProvider(BC).setSecureRandom(RANDOM).build(pass.reveal().toCharArray());
            PGPKeyRingGenerator gen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, primary, uid, sha1, ph.generate(), null,
                    new JcaPGPContentSignerBuilder(primary.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BC), encryptor);
            gen.addSubKey(sub, sh.generate(), null);
            PGPSecretKeyRing secRing = gen.generateSecretKeyRing();
            PGPPublicKeyRing pubRing = gen.generatePublicKeyRing();
            Path folder = keyFolder(ctx, home);
            Keys k = load(folder);
            save(folder, new Keys(PGPPublicKeyRingCollection.addPublicKeyRing(k.pub(), pubRing), PGPSecretKeyRingCollection.addSecretKeyRing(k.sec(), secRing)));
            String pub = "", priv = "";
            if (publicFile != null && !publicFile.isBlank()) { Path f = out(ctx, publicFile); write(f, pubRing.getEncoded()); pub = f.toString(); }
            if (privateFile != null && !privateFile.isBlank()) { Path f = out(ctx, privateFile); write(f, secRing.getEncoded()); priv = f.toString(); }
            String fpr = fingerprint(pubRing.getPublicKey());
            ctx.info("Created PGP key " + fpr + " for " + uid + " (built-in OpenPGP; GnuPG is not installed)");
            return com.oneofficeautomation.packages.Out.of("fingerprint", fpr, "user_id", uid, "public_key_file", pub, "private_key_file", priv);
        } catch (ActionException e) { throw e;
        } catch (Exception e) {
            throw new ActionException("PGP.KEYGEN_FAILED", "Key generation failed: " + e.getMessage(), "Try again; check the key folder is writable.");
        }
    }

    /** Seconds from now until the expiry GnuPG's --quick-gen-key would read (1y, 6m, 90d, 2w, 30, a date, or never); 0 for never. */
    static long lifetimeSeconds(String exp, Date now) {
        String e = exp == null || exp.isBlank() ? "2y" : exp.strip();
        if (e.equals("never") || e.equals("0")) return 0;
        if (e.matches("\\d{4}-\\d{2}-\\d{2}")) return Math.max(1, LocalDate.parse(e).atStartOfDay().toEpochSecond(ZoneOffset.UTC) - now.getTime() / 1000);
        long n = Long.parseLong(e.replaceAll("[dwmy]$", ""));
        return switch (e.charAt(e.length() - 1)) {
            case 'w' -> n * 7 * 86400; case 'm' -> n * 30 * 86400; case 'y' -> n * 365 * 86400; default -> n * 86400;
        };
    }

    static Map<String, Object> importKey(ActionContext ctx, Path file, SecureString pass, String home) {
        Keys read = readKeyFile(file);
        List<Object> fprs = new ArrayList<>();
        Path folder = keyFolder(ctx, home);
        Keys k = load(folder);
        PGPPublicKeyRingCollection pub = k.pub();
        PGPSecretKeyRingCollection sec = k.sec();
        try {
            for (Iterator<PGPSecretKeyRing> it = read.sec().getKeyRings(); it.hasNext(); ) {
                PGPSecretKeyRing r = it.next();
                if (pass != null) privateKey(r.getSecretKey(), pass);   // a wrong passphrase stops the import, as in GnuPG
                if (sec.contains(r.getPublicKey().getKeyID())) sec = PGPSecretKeyRingCollection.removeSecretKeyRing(sec, sec.getSecretKeyRing(r.getPublicKey().getKeyID()));
                sec = PGPSecretKeyRingCollection.addSecretKeyRing(sec, r);
            }
            for (Iterator<PGPPublicKeyRing> it = read.pub().getKeyRings(); it.hasNext(); ) {
                PGPPublicKeyRing r = it.next();
                if (pub.contains(r.getPublicKey().getKeyID())) pub = PGPPublicKeyRingCollection.removePublicKeyRing(pub, pub.getPublicKeyRing(r.getPublicKey().getKeyID()));
                pub = PGPPublicKeyRingCollection.addPublicKeyRing(pub, r);
                fprs.add(fingerprint(r.getPublicKey()));
            }
        } catch (IllegalArgumentException e) { throw new ActionException("PGP.IMPORT_FAILED", "The key could not be added: " + e.getMessage(), "Check the file is an OpenPGP key."); }
        if (fprs.isEmpty()) throw new ActionException("PGP.IMPORT_FAILED", "No key was imported from " + file.getFileName(), "Check the file is an OpenPGP key.");
        save(folder, new Keys(pub, sec));
        return Map.of("fingerprints", fprs);
    }

    // ------------------------------------------------------------------ encrypt and decrypt

    private static final Map<String, Integer> CIPHERS = Map.ofEntries(
            Map.entry("AES", SymmetricKeyAlgorithmTags.AES_128), Map.entry("AES192", SymmetricKeyAlgorithmTags.AES_192), Map.entry("AES256", SymmetricKeyAlgorithmTags.AES_256),
            Map.entry("CAMELLIA128", SymmetricKeyAlgorithmTags.CAMELLIA_128), Map.entry("CAMELLIA192", SymmetricKeyAlgorithmTags.CAMELLIA_192),
            Map.entry("CAMELLIA256", SymmetricKeyAlgorithmTags.CAMELLIA_256), Map.entry("TWOFISH", SymmetricKeyAlgorithmTags.TWOFISH),
            Map.entry("CAST5", SymmetricKeyAlgorithmTags.CAST5), Map.entry("3DES", SymmetricKeyAlgorithmTags.TRIPLE_DES), Map.entry("BLOWFISH", SymmetricKeyAlgorithmTags.BLOWFISH));
    private static final Map<String, Integer> COMPRESSIONS = Map.of("none", CompressionAlgorithmTags.UNCOMPRESSED, "zip", CompressionAlgorithmTags.ZIP,
            "zlib", CompressionAlgorithmTags.ZLIB, "bzip2", CompressionAlgorithmTags.BZIP2);

    /** The key of a ring that can encrypt: an encryption subkey first, as GnuPG picks. */
    static PGPPublicKey encryptionKey(PGPPublicKeyRing ring) {
        PGPPublicKey master = null;
        for (Iterator<PGPPublicKey> it = ring.getPublicKeys(); it.hasNext(); ) {
            PGPPublicKey k = it.next();
            if (!k.isEncryptionKey() || k.hasRevocation()) continue;
            if (!k.isMasterKey()) return k;
            master = k;
        }
        return master;
    }

    static void encrypt(ActionContext ctx, Path in, Path out, List<String> recipients, String publicKeyFile, String home, boolean armor,
                        String gpgCipher, String gpgCompression, SecureString passphrase) {
        List<PGPPublicKey> keys = new ArrayList<>();
        if (!recipients.isEmpty()) {
            Keys k = load(keyFolder(ctx, home));
            for (String r : recipients) {
                PGPPublicKey found = null;
                for (Iterator<PGPPublicKeyRing> it = k.pub().getKeyRings(); it.hasNext() && found == null; ) { PGPPublicKeyRing ring = it.next(); if (matches(ring, r)) found = encryptionKey(ring); }
                if (found == null) throw new ActionException("PGP.ENCRYPT_FAILED", "No public key for '" + r + "' in the key folder", "Import the recipient's public key first.");
                keys.add(found);
            }
        }
        if (publicKeyFile != null && !publicKeyFile.isBlank()) {
            Keys f = readKeyFile(ctx.resolvePath(publicKeyFile));
            for (Iterator<PGPPublicKeyRing> it = f.pub().getKeyRings(); it.hasNext(); ) { PGPPublicKey e = encryptionKey(it.next()); if (e != null) keys.add(e); }
        }
        if (keys.isEmpty() && passphrase == null) throw new ActionException("PGP.NO_RECIPIENT", "Give at least one recipient or a passphrase", "Fill in recipients, public_key_file or passphrase.");
        int cipher = gpgCipher == null ? SymmetricKeyAlgorithmTags.AES_256 : CIPHERS.getOrDefault(gpgCipher, SymmetricKeyAlgorithmTags.AES_256);
        int compression = gpgCompression == null ? CompressionAlgorithmTags.ZLIB : COMPRESSIONS.getOrDefault(gpgCompression, CompressionAlgorithmTags.ZLIB);
        try {
            PGPEncryptedDataGenerator g = new PGPEncryptedDataGenerator(new JcePGPDataEncryptorBuilder(cipher).setWithIntegrityPacket(true).setSecureRandom(RANDOM).setProvider(BC));
            for (PGPPublicKey k : keys) g.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(k).setProvider(BC).setSecureRandom(RANDOM));
            if (passphrase != null) g.addMethod(new JcePBEKeyEncryptionMethodGenerator(passphrase.reveal().toCharArray()).setProvider(BC).setSecureRandom(RANDOM));
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (OutputStream file = Files.newOutputStream(out); OutputStream target = armor ? new ArmoredOutputStream(file) : file;
                 OutputStream enc = g.open(target, new byte[1 << 16])) {
                PGPCompressedDataGenerator cg = compression == CompressionAlgorithmTags.UNCOMPRESSED ? null : new PGPCompressedDataGenerator(compression);
                OutputStream body = cg == null ? enc : cg.open(enc);
                PGPLiteralDataGenerator lg = new PGPLiteralDataGenerator();
                try (OutputStream lit = lg.open(body, PGPLiteralData.BINARY, in.getFileName().toString(), new Date(Files.getLastModifiedTime(in).toMillis()), new byte[1 << 16]);
                     InputStream src = Files.newInputStream(in)) { src.transferTo(lit); }
                if (cg != null) cg.close();
            }
        } catch (ActionException e) { throw e;
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + in, "Check the path.");
        } catch (Exception e) { throw new ActionException("PGP.ENCRYPT_FAILED", "Encryption failed: " + e.getMessage(), "Check the key and that the target folder is writable."); }
    }

    static PGPPrivateKey privateKey(PGPSecretKey key, SecureString pass) {
        try {
            return key.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder(new JcaPGPDigestCalculatorProviderBuilder().setProvider(BC).build())
                    .setProvider(BC).build(pass == null ? new char[0] : pass.reveal().toCharArray()));
        } catch (PGPException e) {
            throw new ActionException("PGP.BAD_PASSPHRASE", "Wrong passphrase", "Check the passphrase in the vault.");
        }
    }

    static void decrypt(ActionContext ctx, Path in, Path out, SecureString pass, String privateKeyFile, String home) {
        PGPSecretKeyRingCollection secrets = privateKeyFile != null && !privateKeyFile.isBlank() ? readKeyFile(ctx.resolvePath(privateKeyFile)).sec() : load(keyFolder(ctx, home)).sec();
        try (InputStream raw = PGPUtil.getDecoderStream(new BufferedInputStream(Files.newInputStream(in)))) {
            JcaPGPObjectFactory f = new JcaPGPObjectFactory(raw);
            Object o = f.nextObject();
            PGPEncryptedDataList list = o instanceof PGPEncryptedDataList l ? l : (PGPEncryptedDataList) f.nextObject();
            InputStream clear = null;
            PGPEncryptedData used = null;
            boolean sawKey = false;
            for (PGPEncryptedData ed : list) {
                if (ed instanceof PGPPublicKeyEncryptedData pk) {
                    PGPSecretKey sk = secrets.getSecretKey(pk.getKeyID());
                    if (sk == null) continue;
                    sawKey = true;
                    clear = pk.getDataStream(new JcePublicKeyDataDecryptorFactoryBuilder().setProvider(BC).build(privateKey(sk, pass)));
                    used = pk;
                    break;
                }
            }
            if (clear == null && pass != null)
                for (PGPEncryptedData ed : list)
                    if (ed instanceof PGPPBEEncryptedData pbe) {
                        try { clear = pbe.getDataStream(new JcePBEDataDecryptorFactoryBuilder(new JcaPGPDigestCalculatorProviderBuilder().setProvider(BC).build()).setProvider(BC).build(pass.reveal().toCharArray())); }
                        catch (PGPException e) { throw new ActionException("PGP.BAD_PASSPHRASE", "Wrong passphrase", "Check the passphrase in the vault."); }
                        used = pbe;
                        break;
                    }
            if (clear == null) throw new ActionException(sawKey ? "PGP.BAD_PASSPHRASE" : "PGP.DECRYPT_FAILED",
                    sawKey ? "Wrong passphrase" : "Decryption failed: no private key in the key folder can open this file",
                    sawKey ? "Check the passphrase in the vault." : "Import the matching private key first.");
            Object msg = new JcaPGPObjectFactory(clear).nextObject();
            if (msg instanceof PGPCompressedData cd) msg = new JcaPGPObjectFactory(cd.getDataStream()).nextObject();
            if (msg instanceof PGPOnePassSignatureList) msg = new JcaPGPObjectFactory(clear).nextObject();
            if (!(msg instanceof PGPLiteralData lit)) throw new ActionException("PGP.DECRYPT_FAILED", "Decryption failed: the file holds no data", "Check the file is OpenPGP-encrypted data.");
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (InputStream data = lit.getInputStream(); OutputStream o2 = Files.newOutputStream(out)) { data.transferTo(o2); }
            if (used.isIntegrityProtected() && !used.verify()) {
                Files.deleteIfExists(out);
                throw new ActionException("PGP.DECRYPT_FAILED", "Decryption failed: the file was changed after it was encrypted", "Ask the sender for the file again.");
            }
        } catch (ActionException e) { throw e;
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + in, "Check the path.");
        } catch (Exception e) { throw new ActionException("PGP.DECRYPT_FAILED", "Decryption failed: " + e.getMessage(), "Check the file is OpenPGP-encrypted and the private key matches."); }
    }

    // ------------------------------------------------------------------ sign and verify

    static void sign(ActionContext ctx, Path in, Path sigFile, String key, SecureString pass, String home) {
        PGPSecretKeyRingCollection secrets = load(keyFolder(ctx, home)).sec();
        PGPSecretKey signer = null;
        for (Iterator<PGPSecretKeyRing> it = secrets.getKeyRings(); it.hasNext() && signer == null; ) {
            PGPSecretKeyRing r = it.next();
            if (key != null && !key.isBlank() && !matches(publicOf(r), key)) continue;
            if (r.getSecretKey().isSigningKey()) signer = r.getSecretKey();
        }
        if (signer == null) throw new ActionException("PGP.SIGN_FAILED", "Signing failed: " + (key == null || key.isBlank() ? "the key folder has no signing key" : "no signing key for '" + key + "'"),
                "Create or import a key with its private part first.");
        try {
            PGPSignatureGenerator g = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(signer.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BC));
            g.init(PGPSignature.BINARY_DOCUMENT, privateKey(signer, pass));
            PGPSignatureSubpacketGenerator sp = new PGPSignatureSubpacketGenerator();
            sp.setIssuerFingerprint(false, signer);
            String uid = userIdOf(signer.getPublicKey());
            if (!uid.isEmpty()) sp.setSignerUserID(false, uid);
            g.setHashedSubpackets(sp.generate());
            try (InputStream src = Files.newInputStream(in)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = src.read(buf)) > 0; ) g.update(buf, 0, n);
            }
            Files.createDirectories(sigFile.toAbsolutePath().getParent());
            try (OutputStream o = new BCPGOutputStream(Files.newOutputStream(sigFile))) { g.generate().encode(o); }
        } catch (ActionException e) { throw e;
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + in, "Check the path.");
        } catch (Exception e) { throw new ActionException("PGP.SIGN_FAILED", "Signing failed: " + e.getMessage(), "Check the key and passphrase."); }
    }

    static Map<String, Object> verify(ActionContext ctx, Path in, Path sigFile, String home) {
        PGPPublicKeyRingCollection pubs = load(keyFolder(ctx, home)).pub();
        try (InputStream raw = PGPUtil.getDecoderStream(new BufferedInputStream(Files.newInputStream(sigFile)))) {
            JcaPGPObjectFactory f = new JcaPGPObjectFactory(raw);
            Object o = f.nextObject();
            if (o instanceof PGPCompressedData cd) o = new JcaPGPObjectFactory(cd.getDataStream()).nextObject();
            if (!(o instanceof PGPSignatureList list) || list.isEmpty()) return no();
            PGPSignature sig = list.get(0);
            PGPPublicKey key = pubs.getPublicKey(sig.getKeyID());
            if (key == null) return no();
            sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BC), key);
            try (InputStream src = Files.newInputStream(in)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = src.read(buf)) > 0; ) sig.update(buf, 0, n);
            }
            if (!sig.verify()) return no();
            PGPPublicKeyRing ring = pubs.getPublicKeyRing(sig.getKeyID());
            return com.oneofficeautomation.packages.Out.of("valid", true, "signer", userIdOf(ring.getPublicKey()), "fingerprint", fingerprint(key));
        } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "File not found: " + e.getFile(), "Check the path.");
        } catch (Exception e) { return no(); }
    }

    private static Map<String, Object> no() { return com.oneofficeautomation.packages.Out.of("valid", false, "signer", "", "fingerprint", ""); }

    private static Path out(ActionContext ctx, String p) {
        Path f = ctx.resolvePath(p);
        try { if (f.getParent() != null) Files.createDirectories(f.getParent()); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it."); }
        return f;
    }
}
