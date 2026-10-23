package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only where Tesseract / GnuPG are installed; otherwise reports a skip. */
class OcrPgpTest {
    static boolean has(String exe) {
        try { return new ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0; } catch (Exception e) { return false; }
    }

    @Test void ocrReadsAndLocatesText() throws Exception {
        if (!has(OcrPackage.exe())) { System.out.println("    (tesseract not installed; skipped)"); return; }
        Path w = tmp();
        BufferedImage img = new BufferedImage(900, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 900, 260);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        g.drawString("INVOICE INV-2026", 40, 70);
        g.drawString("Total due: 129.99 GBP", 40, 150);
        g.drawString("Approve Payment", 480, 230);
        g.dispose();
        ImageIO.write(img, "png", w.resolve("inv.png").toFile());
        Map<String, Object> o = run(w, "[{\"name\":\"t\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"f\",\"type\":\"Boolean\",\"direction\":\"output\"},"
                + "{\"name\":\"x\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"y\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"none\",\"type\":\"Boolean\",\"direction\":\"output\"}]",
                "[{\"id\":\"r\",\"action\":\"ooa.ocr/read_image\",\"args\":{\"path\":\"inv.png\"},\"out\":{\"text\":\"t\"}},"
                + "{\"id\":\"f\",\"action\":\"ooa.ocr/find_text_in_image\",\"args\":{\"path\":\"inv.png\",\"text\":\"approve payment\"},\"out\":{\"found\":\"f\",\"x\":\"x\",\"y\":\"y\"}},"
                + "{\"id\":\"n\",\"action\":\"ooa.ocr/find_text_in_image\",\"args\":{\"path\":\"inv.png\",\"text\":\"reject\"},\"out\":{\"found\":\"none\"}}]", Map.of());
        String text = (String) o.get("t");
        assertTrue(text.contains("INV-2026") && text.contains("129.99"), text);
        assertEquals(true, o.get("f"));
        int x = ((BigDecimal) o.get("x")).intValue(), y = ((BigDecimal) o.get("y")).intValue();
        assertTrue(x > 480 && x < 900 && y > 190 && y < 245, "centre of 'Approve Payment' at " + x + "," + y);
        assertEquals(false, o.get("none"));
        RunResult bad = runRaw(w, "[]", "[{\"id\":\"b\",\"action\":\"ooa.ocr/read_image\",\"args\":{\"path\":\"inv.png\",\"language\":\"eng; rm -rf\"}}]");
        assertEquals("OCR.BAD_LANGUAGE", bad.error().get("code"), "language is validated before Tesseract runs");
    }

    @SuppressWarnings("unchecked")
    @Test void pgpEncryptDecryptSignVerify() throws Exception {
        if (!PgpPackage.usable() || !has(PgpPackage.exe())) { System.out.println("    (gpg not installed; skipped)"); return; }
        Path w = tmp();
        Path home = w.resolve("keys");
        Files.createDirectories(home);
        Process p = new ProcessBuilder(PgpPackage.exe(), "--homedir", home.toString(), "--batch", "--pinentry-mode", "loopback", "--passphrase", "bot-pass",
                "--quick-gen-key", "OOA Bot <bot@ooa.test>", "ed25519", "sign", "1d").redirectErrorStream(true).start();
        assertEquals(0, p.waitFor(), new String(p.getInputStream().readAllBytes()));
        String fpr = new String(new ProcessBuilder(PgpPackage.exe(), "--homedir", home.toString(), "--list-keys", "--with-colons").start().getInputStream().readAllBytes())
                .lines().filter(l -> l.startsWith("fpr:")).findFirst().orElseThrow().split(":")[9];
        Process sub = new ProcessBuilder(PgpPackage.exe(), "--homedir", home.toString(), "--batch", "--pinentry-mode", "loopback", "--passphrase", "bot-pass",
                "--quick-add-key", fpr, "cv25519", "encr", "1d").redirectErrorStream(true).start();
        assertEquals(0, sub.waitFor());
        Files.writeString(w.resolve("payroll.csv"), "name,amount\nAnn,5000\n");
        Map<String, Object> vars = Map.of("pp", Map.of("$credential", "test/pgp/pass"));
        Map<String, Object> flow = (Map<String, Object>) com.oneofficeautomation.sdk.JsonLite.parse(
                "{\"kind\":\"TaskBot\",\"id\":\"p\",\"name\":\"p\",\"variables\":[{\"name\":\"pp\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true},"
                + "{\"name\":\"valid\",\"type\":\"Boolean\",\"direction\":\"output\"},{\"name\":\"signer\",\"type\":\"String\",\"direction\":\"output\"}],\"steps\":["
                + "{\"id\":\"e\",\"action\":\"ooa.pgp/encrypt\",\"args\":{\"path\":\"payroll.csv\",\"target\":\"out/payroll.csv.pgp\",\"recipients\":\"bot@ooa.test\",\"gnupg_home\":\"keys\"}},"
                + "{\"id\":\"d\",\"action\":\"ooa.pgp/decrypt\",\"args\":{\"path\":\"out/payroll.csv.pgp\",\"target\":\"out/back.csv\",\"passphrase\":\"$pp\",\"gnupg_home\":\"keys\"}},"
                + "{\"id\":\"s\",\"action\":\"ooa.pgp/sign\",\"args\":{\"path\":\"payroll.csv\",\"signature\":\"out/payroll.csv.sig\",\"passphrase\":\"$pp\",\"gnupg_home\":\"keys\"}},"
                + "{\"id\":\"v\",\"action\":\"ooa.pgp/verify\",\"args\":{\"path\":\"payroll.csv\",\"signature\":\"out/payroll.csv.sig\",\"gnupg_home\":\"keys\"},\"out\":{\"valid\":\"valid\",\"signer\":\"signer\"}}]}");
        var vault = new com.oneofficeautomation.runner.CredentialVault.InMemory().put("test", "pgp", "pass", "bot-pass");
        RunResult r = com.oneofficeautomation.runner.Runner.builder(REG).workDir(w).service("vault", vault).build().run(flow, vars);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertFalse(Files.readString(w.resolve("out/payroll.csv.pgp"), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Ann,5000"), "ciphertext");
        assertEquals("name,amount\nAnn,5000\n", Files.readString(w.resolve("out/back.csv")));
        assertEquals(true, r.outputs().get("valid"));
        assertTrue(String.valueOf(r.outputs().get("signer")).contains("bot@ooa.test"));
        Files.writeString(w.resolve("payroll.csv"), "name,amount\nAnn,9000\n");  // tampered after signing
        Map<String, Object> tampered = run(w, "[{\"name\":\"valid\",\"type\":\"Boolean\",\"direction\":\"output\"}]",
                "[{\"id\":\"v\",\"action\":\"ooa.pgp/verify\",\"args\":{\"path\":\"payroll.csv\",\"signature\":\"out/payroll.csv.sig\",\"gnupg_home\":\"keys\"},\"out\":{\"valid\":\"valid\"}}]", Map.of());
        assertEquals(false, tampered.get("valid"), "modified file fails verification");
        new ProcessBuilder("gpgconf", "--homedir", home.toString(), "--kill", "gpg-agent").start().waitFor();
        var wrongVault = new com.oneofficeautomation.runner.CredentialVault.InMemory().put("test", "pgp", "pass", "WRONG");
        RunResult bad = com.oneofficeautomation.runner.Runner.builder(REG).workDir(w).service("vault", wrongVault).build().run(
                (Map<String, Object>) com.oneofficeautomation.sdk.JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"p\",\"name\":\"p\",\"variables\":[{\"name\":\"pp\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true}],"
                        + "\"steps\":[{\"id\":\"d\",\"action\":\"ooa.pgp/decrypt\",\"args\":{\"path\":\"out/payroll.csv.pgp\",\"target\":\"out/x.csv\",\"passphrase\":\"$pp\",\"gnupg_home\":\"keys\"}}]}"), vars);
        assertEquals("PGP.BAD_PASSPHRASE", bad.error().get("code"));
        new ProcessBuilder("gpgconf", "--homedir", home.toString(), "--kill", "gpg-agent").start().waitFor();
    }
}
