package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * {@code ooa browser-connector install | status | uninstall}: sets up the browser connector for this Windows user.
 * Install writes the native-messaging host (a small launcher for {@code ooa native-host} and its manifest) under
 * %LOCALAPPDATA%\OneOfficeAutomation\browser-connector and registers it for Edge and Chrome in HKCU; the extension
 * itself is loaded once from the install's browser-extension folder (Load unpacked), or by enterprise policy.
 */
public final class BrowserConnector {
    /** Fixed by the "key" in the extension manifest, so the host can accept only this extension. */
    public static final String EXTENSION_ID = "fnceeakhokkekgdehkobdpdehpfnemah";
    /** The extension's id in Firefox (manifest.firefox.json, browser_specific_settings). */
    public static final String FIREFOX_EXTENSION_ID = "browser-connector@oneofficeautomation.com";
    public static final String HOST_NAME = "com.oneofficeautomation.bridge";
    static final List<String> REG_KEYS = List.of("HKCU\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\" + HOST_NAME,
            "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\" + HOST_NAME);
    /** Firefox finds native-messaging hosts here; its host manifest names the extension by id, not by origin. */
    static final String FIREFOX_REG_KEY = "HKCU\\Software\\Mozilla\\NativeMessagingHosts\\" + HOST_NAME;

    private BrowserConnector() {}

    /** install, status or uninstall; home is the install folder, or null for this program's. */
    static int run(String cmd, Path home) throws IOException, InterruptedException {
        if (home == null) home = installHome();
        return switch (cmd) {
            case "install" -> install(home);
            case "uninstall" -> uninstall();
            case "status" -> status(home);
            default -> throw new IllegalArgumentException("Unknown connector command " + cmd);
        };
    }

    /** The install folder (the one with bin\ and lib\), worked out from where this class was loaded. */
    static Path installHome() {
        try {
            Path p = Path.of(BrowserConnector.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (p.toString().endsWith(".jar") && p.getParent() != null && p.getParent().getParent() != null) return p.getParent().getParent();
        } catch (URISyntaxException | RuntimeException ignored) { }
        return Path.of("cli", "build", "install", "ooa").toAbsolutePath();
    }

    static Path hostDir() {
        String local = System.getenv("LOCALAPPDATA");
        return (local != null ? Path.of(local) : Path.of(System.getProperty("user.home"), "AppData", "Local")).resolve("OneOfficeAutomation").resolve("browser-connector");
    }

    /** The native-messaging manifest: which program the browser starts and which extension may talk to it. */
    static String hostManifest(Path launcher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", HOST_NAME);
        m.put("description", "ONEOFFICEAUTOMATION browser connector");
        m.put("path", launcher.toAbsolutePath().toString());
        m.put("type", "stdio");
        m.put("allowed_origins", List.of("chrome-extension://" + EXTENSION_ID + "/"));
        return JsonLite.write(m);
    }

    /** The same for Firefox, which allows extensions by id. */
    static String firefoxHostManifest(Path launcher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", HOST_NAME);
        m.put("description", "ONEOFFICEAUTOMATION browser connector");
        m.put("path", launcher.toAbsolutePath().toString());
        m.put("type", "stdio");
        m.put("allowed_extensions", List.of(FIREFOX_EXTENSION_ID));
        return JsonLite.write(m);
    }

    static int install(Path home) throws IOException, InterruptedException {
        Path ooa = home.resolve("bin").resolve("ooa.bat");
        Path ext = home.resolve("browser-extension");
        if (!Files.isRegularFile(ooa)) { System.err.println("error: no ONEOFFICEAUTOMATION install at " + home + " (give it with --home)"); return 2; }
        Path dir = hostDir();
        Files.createDirectories(dir);
        Path launcher = dir.resolve("ooa-browser-host.bat");
        // The browser reads the protocol from this program's output, so nothing else may be printed.
        Files.writeString(launcher, "@echo off\r\ncall \"" + ooa.toAbsolutePath() + "\" native-host %*\r\n", StandardCharsets.UTF_8);
        Path manifest = dir.resolve(HOST_NAME + ".json");
        Files.writeString(manifest, hostManifest(launcher), StandardCharsets.UTF_8);
        Path firefox = dir.resolve(HOST_NAME + ".firefox.json");
        Files.writeString(firefox, firefoxHostManifest(launcher), StandardCharsets.UTF_8);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            for (String key : REG_KEYS) reg("add", key, "/ve", "/t", "REG_SZ", "/d", manifest.toAbsolutePath().toString(), "/f");
            reg("add", FIREFOX_REG_KEY, "/ve", "/t", "REG_SZ", "/d", firefox.toAbsolutePath().toString(), "/f");
        }
        System.out.println("Browser connector installed for " + System.getProperty("user.name") + ".");
        System.out.println("  Host:      " + manifest);
        System.out.println("  Extension: " + ext);
        System.out.println();
        System.out.println("Load the extension once in each browser:");
        System.out.println("  Edge:   open edge://extensions, turn on Developer mode, choose Load unpacked and pick the Extension folder above.");
        System.out.println("  Chrome: open chrome://extensions, turn on Developer mode, choose Load unpacked and pick the same folder.");
        System.out.println("  Firefox: open about:debugging#/runtime/this-firefox, choose Load Temporary Add-on and pick manifest.json in");
        System.out.println("           " + home.resolve("browser-extension-firefox") + " (until Firefox restarts; a signed add-on stays).");
        System.out.println("           Then allow it on all websites: about:addons > OOA Connector > Permissions.");
        System.out.println("The extension's icon shows Connected when it can reach this computer's agents.");
        return 0;
    }

    static int uninstall() throws IOException, InterruptedException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (String key : REG_KEYS) reg("delete", key, "/f");
            reg("delete", FIREFOX_REG_KEY, "/f");
        }
        Path dir = hostDir();
        if (Files.isDirectory(dir)) try (var s = Files.list(dir)) { for (Path p : s.toList()) Files.deleteIfExists(p); }
        Files.deleteIfExists(dir);
        System.out.println("Browser connector removed (remove the extension in the browser's extensions page).");
        return 0;
    }

    static int status(Path home) throws IOException {
        Path manifest = hostDir().resolve(HOST_NAME + ".json");
        System.out.println("Host manifest: " + (Files.isRegularFile(manifest) ? manifest : "not installed (run: ooa browser-connector install)"));
        System.out.println("Extension folder: " + home.resolve("browser-extension"));
        Path bridge = Path.of(System.getProperty("user.home"), ".ooa", "bridge");
        int n = 0;
        if (Files.isDirectory(bridge)) try (var s = Files.list(bridge)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                Object o = JsonLite.parse(Files.readString(f));
                if (!(o instanceof Map<?, ?> m)) continue;
                long pid = ((Number) m.get("pid")).longValue();
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                System.out.println("Connected browser: " + m.get("browser") + " (extension " + m.get("extension_version") + ", host pid " + pid + (alive ? ")" : ", not running)"));
                if (alive) n++;
            }
        }
        if (n == 0) System.out.println("No browser is connected now (open Edge, Chrome or Firefox with the extension loaded).");
        return 0;
    }

    private static void reg(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("reg"));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0 && !args[0].equals("delete")) throw new IOException("reg " + args[0] + " " + args[1] + " failed: " + outText);
    }
}
