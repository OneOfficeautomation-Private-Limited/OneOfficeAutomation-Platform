package com.oneofficeautomation.packages;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AWS Signature Version 4 request signing with JDK crypto only (SHA-256 and HmacSHA256).
 * Used for the SageMaker runtime; the secret key never leaves this class except as an HMAC result.
 */
final class AwsSigV4 {
    private AwsSigV4() {}

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Returns the headers to add to the request: x-amz-date and Authorization. {@code headers} are the headers that
     * will be sent and signed (host is added from the URL when missing).
     */
    static Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] payload,
                                    String accessKeyId, String secretKey, String region, String service, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        TreeMap<String, String> h = new TreeMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
        h.putIfAbsent("host", hostHeader(uri));
        h.put("x-amz-date", amzDate);
        String signedHeaders = String.join(";", h.keySet());
        String canonical = canonicalRequest(method, uri, h, signedHeaders, hex(sha256(payload == null ? new byte[0] : payload)));
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String toSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        String signature = hex(hmac(signingKey(secretKey, date, region, service), toSign));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("x-amz-date", amzDate);
        out.put("Authorization", ALGORITHM + " Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    /** Host header as the JDK HttpClient sends it: host, plus :port when the URL names a non-default port. */
    static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean dflt = port == -1 || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443) || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
        return uri.getHost() + (dflt ? "" : ":" + port);
    }

    static String canonicalRequest(String method, URI uri, SortedMap<String, String> headers, String signedHeaders, String payloadHash) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.toUpperCase(Locale.ROOT)).append('\n');
        sb.append(canonicalPath(uri.getRawPath())).append('\n');
        sb.append(canonicalQuery(uri.getRawQuery())).append('\n');
        for (var e : headers.entrySet()) sb.append(e.getKey()).append(':').append(e.getValue().strip().replaceAll("\\s+", " ")).append('\n');
        sb.append('\n').append(signedHeaders).append('\n').append(payloadHash);
        return sb.toString();
    }

    /** Each segment of the (already URL-encoded) path is encoded once more, as AWS requires for services other than S3. */
    static String canonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        StringJoiner j = new StringJoiner("/");
        for (String seg : rawPath.split("/", -1)) j.add(uriEncode(seg));
        String p = j.toString();
        return p.startsWith("/") ? p : "/" + p;
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        List<String[]> params = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? part : part.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            params.add(new String[]{uriEncode(k), uriEncode(v)});
        }
        params.sort(Comparator.<String[], String>comparing(a -> a[0]).thenComparing(a -> a[1]));
        StringJoiner j = new StringJoiner("&");
        for (String[] p : params) j.add(p[0] + "=" + p[1]);
        return j.toString();
    }

    /** RFC 3986 encoding: unreserved characters stay, everything else becomes %XX (upper-case hex). */
    static String uriEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') sb.append((char) c);
            else sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
        return sb.toString();
    }

    static byte[] signingKey(String secretKey, String date, String region, String service) {
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, service);
        return hmac(k, "aws4_request");
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("HmacSHA256 unavailable", e); }
    }

    static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
}
