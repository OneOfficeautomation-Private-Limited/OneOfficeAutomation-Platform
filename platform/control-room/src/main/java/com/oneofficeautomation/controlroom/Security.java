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

    /** Passwords people commonly choose; refused whatever the letters' case and with digits or symbols around them. */
    private static final java.util.Set<String> COMMON = java.util.Set.of("password", "passw0rd", "welcome", "qwerty", "qwertyuiop", "letmein", "admin", "administrator",
        "iloveyou", "monkey", "dragon", "football", "baseball", "sunshine", "princess", "trustno1", "abc123", "changeme", "secret", "master", "login",
        "starwars", "whatever", "shadow", "superman", "michael", "computer", "automation", "oneoffice", "ooa");

    /** Password policy with the default minimum length (12). */
    public static String passwordProblem(String password, String username) { return passwordProblem(password, username, 12); }

    /**
     * Password policy: at least minLength characters (12 to 64, Settings: password_min_length), letters and digits, at most
     * 128 characters, no character four times in a row, not the user name, and not a common password with
     * digits or symbols added.
     */
    public static String passwordProblem(String password, String username, int minLength) {
        int min = Math.max(12, Math.min(64, minLength));
        if (password == null || password.length() < min) return "Passwords need at least " + min + " characters.";
        if (password.length() > 128) return "Passwords have at most 128 characters.";
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) return "Passwords need both letters and digits.";
        if (password.equalsIgnoreCase(username)) return "The password cannot be the user name.";
        if (password.matches(".*(.)\\1{3,}.*")) return "The same character cannot appear four times in a row.";
        String core = password.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (COMMON.contains(core)) return "That password is too common; choose another.";
        return null;
    }
}
