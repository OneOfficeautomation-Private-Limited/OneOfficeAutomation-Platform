package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.DbConfig;
import com.oneofficeautomation.controlroom.Vault;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where DocFlow is, as the Windows installer records it ({@code ooa docflow configure}), in
 * {@code <data>/config/ooa-docflow.properties}:
 * <pre>
 * url=http://127.0.0.1:8085     DocFlow's address
 * token=enc:...                 the service token, encrypted with the vault master key (or env:VARIABLE)
 * </pre>
 * On premises it is used only while Settings > Document Automation and OOA_DOCFLOW_URL / OOA_DOCFLOW_TOKEN are empty,
 * so an administrator can still point the Command Center elsewhere. A Windows service does not see environment
 * variables set after Windows started, which is why the installer writes this file instead.
 */
public final class DocFlowConfig {
    private static final String BOUND = "ooa-docflow:token";

    private DocFlowConfig() { }

    public static Path file(Path data) { return data.resolve("config").resolve("ooa-docflow.properties"); }

    public static Properties load(Path data) {
        Properties p = new Properties();
        Path f = data == null ? null : file(data);
        if (f == null || !Files.isRegularFile(f)) return p;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    /** DocFlow's address from the file ("" when there is none). */
    public static String url(Path data) { return load(data).getProperty("url", "").trim(); }

    /** The service token from the file, decrypted ("" when there is none). */
    public static String token(Path data, Vault vault) {
        String v = load(data).getProperty("token", "").trim();
        if (v.isEmpty()) return "";
        if (v.startsWith("env:")) { String e = System.getenv(v.substring(4)); return e == null ? "" : e.trim(); }
        if (v.startsWith("enc:")) return vault.decrypt(v.substring(4), BOUND);
        return "";   // never kept in clear text
    }

    /** Writes the address and the token (encrypted with the vault key), keeping any other lines of the file. */
    public static void save(Path data, Vault vault, String url, String token) {
        Path f = file(data);
        try { Files.createDirectories(f.getParent()); } catch (IOException e) { throw new IllegalStateException("Cannot create " + f.getParent() + ": " + e.getMessage(), e); }
        if (!Files.exists(f)) {
            try { Files.writeString(f, "# Document Automation: where DocFlow is (ooa docflow configure). Settings > Document Automation overrides it.\n", StandardCharsets.UTF_8); }
            catch (IOException e) { throw new IllegalStateException("Cannot write " + f + ": " + e.getMessage(), e); }
        }
        DbConfig.setValue(f, "url", url);
        DbConfig.setValue(f, "token", "enc:" + vault.encrypt(token, BOUND));
    }
}
