package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/**
 * Password-protected .zip archives with JDK crypto only: writes WinZip AES-256 (AE-2) entries, which 7-Zip, WinZip and
 * libarchive open; reads AES (128, 192, 256 bit) and the older ZipCrypto entries, and plain stored or deflated ones.
 */
final class ZipCrypt {
    private ZipCrypt() {}

    /** A file to add and its name inside the archive (forward slashes). */
    record Item(String name, Path file) {}

    private static final int MAX_ENTRY = 512 * 1024 * 1024;   // one entry is compressed and encrypted in memory

    // ---------------------------------------------------------------- writing (AE-2, AES-256)

    static void writeAes(Path target, List<Item> items, int level, String password) throws IOException {
        if (items.size() > 65_535) throw new ActionException("ZIP.TOO_LARGE", "More than 65,535 files for one encrypted archive", "Split the files over several archives.");
        SecureRandom rnd = new SecureRandom();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(target))) {
            CountingStream out = new CountingStream(raw);
            for (Item it : items) {
                long size = Files.size(it.file());
                if (size > MAX_ENTRY) throw new ActionException("ZIP.TOO_LARGE", it.name() + " is larger than 512 MB", "Encrypt very large files one by one, or zip them without a password.");
                byte[] plain = Files.readAllBytes(it.file());
                int method = level == 0 ? 0 : 8;
                byte[] packed = method == 0 ? plain : deflate(plain, level);
                byte[] salt = new byte[16];
                rnd.nextBytes(salt);
                byte[][] keys = derive(password, salt, 32);
                byte[] cipher = ctr(keys[0], packed);
                byte[] mac = hmac(keys[1], cipher);
                long compSize = 16L + 2 + cipher.length + 10;
                if (compSize > 0xFFFFFFFFL) throw new ActionException("ZIP.TOO_LARGE", it.name() + " is too large", "Use a smaller file.");
                byte[] name = it.name().getBytes(StandardCharsets.UTF_8);
                byte[] extra = aesExtra(method);
                int[] dos = dosTime(Files.getLastModifiedTime(it.file()).toMillis());
                long offset = out.count;
                ByteBuffer h = le(30 + name.length + extra.length);
                h.putInt(0x04034b50).putShort((short) 51).putShort((short) 0x0801).putShort((short) 99).putShort((short) dos[0]).putShort((short) dos[1])
                        .putInt(0).putInt((int) compSize).putInt((int) plain.length).putShort((short) name.length).putShort((short) extra.length).put(name).put(extra);
                out.write(h.array());
                out.write(salt);
                out.write(keys[2]);
                out.write(cipher);
                out.write(mac, 0, 10);
                ByteBuffer c = le(46 + name.length + extra.length);
                c.putInt(0x02014b50).putShort((short) 51).putShort((short) 51).putShort((short) 0x0801).putShort((short) 99).putShort((short) dos[0]).putShort((short) dos[1])
                        .putInt(0).putInt((int) compSize).putInt((int) plain.length).putShort((short) name.length).putShort((short) extra.length).putShort((short) 0)
                        .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) offset).put(name).put(extra);
                central.write(c.array());
                if (out.count > 0xFFFFFFFFL) throw new ActionException("ZIP.TOO_LARGE", "The archive grew past 4 GB", "Split the files over several archives.");
            }
            long cdStart = out.count;
            out.write(central.toByteArray());
            ByteBuffer e = le(22);
            e.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) items.size()).putShort((short) items.size())
                    .putInt(central.size()).putInt((int) cdStart).putShort((short) 0);
            out.write(e.array());
        }
    }

    private static byte[] aesExtra(int method) {
        ByteBuffer x = le(11);
        x.putShort((short) 0x9901).putShort((short) 7).putShort((short) 2).put((byte) 'A').put((byte) 'E').put((byte) 3).putShort((short) method);
        return x.array();
    }

    private static byte[] deflate(byte[] data, int level) {
        Deflater d = new Deflater(level, true);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buf = new byte[65536];
            while (!d.finished()) o.write(buf, 0, d.deflate(buf));
            return o.toByteArray();
        } finally { d.end(); }
    }

    // ---------------------------------------------------------------- reading

    /** Whether any entry of the archive is encrypted. */
    static boolean anyEncrypted(Path archive) throws IOException {
        for (Central c : centralDirectory(archive)) if ((c.flags & 1) != 0) return true;
        return false;
    }

    /** Extracts every entry under dest; a password opens AES and ZipCrypto entries. Returns the extracted files. */
    static List<Object> extract(Path archive, Path dest, boolean overwrite, String password) throws IOException {
        List<Object> done = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            for (Central c : centralDirectory(archive)) {
                Path target = dest.resolve(c.name).normalize();
                if (!target.startsWith(dest))
                    throw new ActionException("ZIP.UNSAFE_ENTRY", "Archive entry '" + c.name + "' points outside the destination", "The archive may be malicious; do not extract it.");
                if (c.name.endsWith("/")) { Files.createDirectories(target); continue; }
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                if (!overwrite && Files.exists(target)) continue;
                if (c.compSize > MAX_ENTRY) throw new ActionException("ZIP.TOO_LARGE", c.name + " is larger than 512 MB packed", "Extract it with another tool.");
                ByteBuffer lh = le(30);
                read(ch, lh, c.offset);
                if (lh.getInt(0) != 0x04034b50) throw corrupt(c.name);
                long dataAt = c.offset + 30 + (lh.getShort(26) & 0xFFFF) + (lh.getShort(28) & 0xFFFF);
                ByteBuffer data = ByteBuffer.allocate((int) c.compSize);
                read(ch, data, dataAt);
                byte[] content = open(c, data.array(), password);
                Files.write(target, content);
                done.add(target.toString());
            }
        }
        return done;
    }

    private static byte[] open(Central c, byte[] data, String password) throws IOException {
        int method = c.method;
        byte[] packed = data;
        boolean checkCrc = true;
        if ((c.flags & 1) != 0) {
            if (password == null) throw new ActionException("ZIP.PASSWORD_REQUIRED", "The archive is password-protected (" + c.name + ")", "Give the password from the vault.");
            if (method == 99) {
                if (c.aesStrength < 1 || c.aesStrength > 3) throw new ActionException("ZIP.UNSUPPORTED", "Unknown AES strength in " + c.name, "Extract it with 7-Zip.");
                int keyLen = 8 + c.aesStrength * 8, saltLen = keyLen / 2;
                if (data.length < saltLen + 2 + 10) throw corrupt(c.name);
                byte[][] keys = derive(password, Arrays.copyOfRange(data, 0, saltLen), keyLen);
                if (!MessageDigest.isEqual(keys[2], Arrays.copyOfRange(data, saltLen, saltLen + 2)))
                    throw new ActionException("ZIP.WRONG_PASSWORD", "The password does not open " + c.name, "Check the password stored in the vault.");
                byte[] cipher = Arrays.copyOfRange(data, saltLen + 2, data.length - 10);
                if (!MessageDigest.isEqual(Arrays.copyOf(hmac(keys[1], cipher), 10), Arrays.copyOfRange(data, data.length - 10, data.length))) throw corrupt(c.name);
                packed = ctr(keys[0], cipher);
                method = c.aesMethod;
                checkCrc = c.aesVersion == 1;   // AE-2 leaves the CRC at 0 and relies on the authentication code
            } else {
                packed = zipCrypto(c, data, password);
            }
        }
        byte[] plain = switch (method) {
            case 0 -> packed;
            case 8 -> inflate(packed, c);
            default -> throw new ActionException("ZIP.UNSUPPORTED", c.name + " uses compression method " + method, "Extract it with 7-Zip.");
        };
        if (checkCrc) {
            CRC32 crc = new CRC32();
            crc.update(plain);
            if ((int) crc.getValue() != c.crc)
                throw (c.flags & 1) != 0 && c.method != 99
                        ? new ActionException("ZIP.WRONG_PASSWORD", "The password does not open " + c.name, "Check the password stored in the vault.")
                        : corrupt(c.name);
        }
        return plain;
    }

    private static byte[] inflate(byte[] packed, Central c) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(packed);
            ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(Math.max(c.size, 64), MAX_ENTRY));
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                o.write(buf, 0, n);
                if (o.size() > MAX_ENTRY) throw new ActionException("ZIP.TOO_LARGE", c.name + " unpacks to more than 512 MB", "Extract it with another tool.");
            }
            return o.toByteArray();
        } catch (DataFormatException e) {
            throw (c.flags & 1) != 0 && c.method != 99
                    ? new ActionException("ZIP.WRONG_PASSWORD", "The password does not open " + c.name, "Check the password stored in the vault.")
                    : corrupt(c.name);
        } finally { inf.end(); }
    }

    // ZipCrypto (traditional PKWARE encryption), read only
    private static byte[] zipCrypto(Central c, byte[] data, String password) {
        if (data.length < 12) throw corrupt(c.name);
        int[] k = {0x12345678, 0x23456789, 0x34567890};
        for (byte b : password.getBytes(StandardCharsets.UTF_8)) update(k, b);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            int t = (k[2] | 2) & 0xFFFF;
            byte p = (byte) (data[i] ^ ((t * (t ^ 1)) >>> 8));
            update(k, p);
            out[i] = p;
        }
        int check = out[11] & 0xFF;
        int want = (c.flags & 8) != 0 ? (c.time >>> 8) & 0xFF : (c.crc >>> 24) & 0xFF;
        if (check != want) throw new ActionException("ZIP.WRONG_PASSWORD", "The password does not open " + c.name, "Check the password stored in the vault.");
        return Arrays.copyOfRange(out, 12, out.length);
    }

    private static void update(int[] k, byte b) {
        k[0] = crc(k[0], b);
        k[1] = (k[1] + (k[0] & 0xFF)) * 134775813 + 1;
        k[2] = crc(k[2], (byte) (k[1] >>> 24));
    }

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int j = 0; j < 8; j++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            CRC_TABLE[n] = c;
        }
    }

    private static int crc(int crc, byte b) { return (crc >>> 8) ^ CRC_TABLE[(crc ^ b) & 0xFF]; }

    // ---------------------------------------------------------------- central directory

    private static final class Central {
        String name; int flags, method, time, crc, aesVersion, aesStrength, aesMethod; long compSize, size, offset;
    }

    private static List<Central> centralDirectory(Path archive) throws IOException {
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            long len = ch.size();
            int tail = (int) Math.min(len, 65_557);
            ByteBuffer t = le(tail);
            read(ch, t, len - tail);
            int at = -1;
            for (int i = tail - 22; i >= 0; i--) if (t.getInt(i) == 0x06054b50) { at = i; break; }
            if (at < 0) throw new ActionException("ZIP.CORRUPT", archive.getFileName() + " is not a zip archive", "Check the file.");
            int count = t.getShort(at + 10) & 0xFFFF;
            long cdSize = t.getInt(at + 12) & 0xFFFFFFFFL, cdOffset = t.getInt(at + 16) & 0xFFFFFFFFL;
            if (cdOffset == 0xFFFFFFFFL || count == 0xFFFF) throw new ActionException("ZIP.UNSUPPORTED", archive.getFileName() + " is a ZIP64 archive", "Extract it with 7-Zip.");
            if (cdOffset + cdSize > len) throw new ActionException("ZIP.CORRUPT", archive.getFileName() + " is damaged", "Check the file.");
            ByteBuffer cd = le((int) cdSize);
            read(ch, cd, cdOffset);
            List<Central> list = new ArrayList<>();
            int p = 0;
            for (int i = 0; i < count; i++) {
                if (cd.getInt(p) != 0x02014b50) throw new ActionException("ZIP.CORRUPT", archive.getFileName() + " is damaged", "Check the file.");
                Central c = new Central();
                c.flags = cd.getShort(p + 8) & 0xFFFF;
                c.method = cd.getShort(p + 10) & 0xFFFF;
                c.time = cd.getShort(p + 12) & 0xFFFF;
                c.crc = cd.getInt(p + 16);
                c.compSize = cd.getInt(p + 20) & 0xFFFFFFFFL;
                c.size = cd.getInt(p + 24) & 0xFFFFFFFFL;
                int nl = cd.getShort(p + 28) & 0xFFFF, xl = cd.getShort(p + 30) & 0xFFFF, cl = cd.getShort(p + 32) & 0xFFFF;
                c.offset = cd.getInt(p + 42) & 0xFFFFFFFFL;
                byte[] nb = new byte[nl];
                cd.get(p + 46, nb);
                c.name = new String(nb, (c.flags & 0x800) != 0 ? StandardCharsets.UTF_8 : cp437());
                for (int x = p + 46 + nl; x + 4 <= p + 46 + nl + xl; ) {
                    int id = cd.getShort(x) & 0xFFFF, sz = cd.getShort(x + 2) & 0xFFFF;
                    if (id == 0x9901 && sz >= 7) {
                        c.aesVersion = cd.getShort(x + 4) & 0xFFFF;
                        c.aesStrength = cd.get(x + 8) & 0xFF;
                        c.aesMethod = cd.getShort(x + 9) & 0xFFFF;
                    }
                    x += 4 + sz;
                }
                list.add(c);
                p += 46 + nl + xl + cl;
            }
            return list;
        }
    }

    private static Charset cp437() {
        try { return Charset.forName("IBM437"); } catch (RuntimeException e) { return StandardCharsets.ISO_8859_1; }
    }

    // ---------------------------------------------------------------- crypto helpers

    /** PBKDF2-HMAC-SHA1 (1000 rounds) into the AES key, the HMAC key and the 2-byte password check. */
    private static byte[][] derive(String password, byte[] salt, int keyLen) {
        try {
            byte[] all = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1000, (keyLen * 2 + 2) * 8)).getEncoded();
            return new byte[][]{Arrays.copyOfRange(all, 0, keyLen), Arrays.copyOfRange(all, keyLen, keyLen * 2), Arrays.copyOfRange(all, keyLen * 2, keyLen * 2 + 2)};
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** AES in counter mode with WinZip's little-endian counter starting at 1; the same call encrypts and decrypts. */
    private static byte[] ctr(byte[] key, byte[] in) {
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] out = new byte[in.length], counter = new byte[16], stream = new byte[16];
            long n = 0;
            for (int i = 0; i < in.length; i += 16) {
                n++;
                for (int b = 0; b < 8; b++) counter[b] = (byte) (n >>> (8 * b));
                aes.doFinal(counter, 0, 16, stream, 0);
                for (int j = 0; j < 16 && i + j < in.length; j++) out[i + j] = (byte) (in[i + j] ^ stream[j]);
            }
            return out;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA1");
            m.init(new SecretKeySpec(key, "HmacSHA1"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- small helpers

    private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

    private static void read(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n < 0) throw new EOFException("Unexpected end of archive");
        }
    }

    private static ActionException corrupt(String name) {
        return new ActionException("ZIP.CORRUPT", "The archive entry " + name + " is damaged", "Check the file, or get a new copy.");
    }

    /** DOS time and date of a moment in local time. */
    private static int[] dosTime(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
        if (t.getYear() < 1980) t = LocalDateTime.of(1980, 1, 1, 0, 0);
        return new int[]{(t.getHour() << 11) | (t.getMinute() << 5) | (t.getSecond() / 2), ((t.getYear() - 1980) << 9) | (t.getMonthValue() << 5) | t.getDayOfMonth()};
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;
        CountingStream(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
