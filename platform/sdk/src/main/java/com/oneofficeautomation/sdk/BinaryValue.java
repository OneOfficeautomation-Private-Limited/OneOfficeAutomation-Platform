package com.oneofficeautomation.sdk;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Binary value: bytes held in memory (a file's contents, a download, a decoded attachment) with a media type.
 * Immutable: the bytes are copied in and out. Its JSON form is {"$binary": Base64, "mime": type}; its text form is a
 * short summary such as "Binary 1.2 KB", so logs and events never carry the bytes.
 */
public final class BinaryValue {
    public static final String OCTET_STREAM = "application/octet-stream";

    private final byte[] bytes;
    private final String mime;

    public BinaryValue(byte[] bytes, String mime) {
        this.bytes = bytes == null ? new byte[0] : bytes.clone();
        this.mime = mime == null || mime.isBlank() ? OCTET_STREAM : mime.strip();
    }

    public byte[] bytes() { return bytes.clone(); }

    public int length() { return bytes.length; }

    public String mime() { return mime; }

    public String base64() { return Base64.getEncoder().encodeToString(bytes); }

    /** {"$binary": Base64, "mime": type}: the form outputs and JSON files carry, read back by a Binary variable. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$binary", base64());
        m.put("mime", mime);
        return m;
    }

    /** A size people read: 512 bytes, 1.2 KB, 3.4 MB. */
    public static String size(long n) {
        if (n < 1024) return n + (n == 1 ? " byte" : " bytes");
        if (n < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
    }

    @Override public String toString() { return "Binary " + size(bytes.length); }

    @Override public boolean equals(Object o) {
        return o instanceof BinaryValue b && Arrays.equals(bytes, b.bytes) && mime.equals(b.mime);
    }

    @Override public int hashCode() { return 31 * Arrays.hashCode(bytes) + mime.hashCode(); }
}
