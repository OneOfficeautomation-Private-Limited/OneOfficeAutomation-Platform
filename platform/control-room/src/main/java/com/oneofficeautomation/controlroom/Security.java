package com.oneofficeautomation.controlroom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Password hashing (PBKDF2-HMAC-SHA256, 210,000 iterations, random salt), random tokens and SHA-256. */
public final class Security {
    private Security() {}
    static final SecureRandom RANDOM = new SecureRandom();
    static final int ITERATIONS = 210_000;

    public static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String hashPassword(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
    }

    public static boolean verifyPassword(char[] password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2-sha256")) return false;
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, iterations, 256)).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean constantEquals(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Password policy: at least 12 characters with letters and digits; not the username. */
    public static String passwordProblem(String password, String username) {
        if (password == null || password.length() < 12) return "Passwords need at least 12 characters.";
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) return "Passwords need both letters and digits.";
        if (password.equalsIgnoreCase(username)) return "The password cannot be the user name.";
        return null;
    }
}
