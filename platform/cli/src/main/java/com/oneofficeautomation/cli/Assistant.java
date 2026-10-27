package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code ooa assistant}: the attended desktop app (R13). It sits in the notification area and, as the signed-in
 * person, starts the agents they may run on their own default runner (asking for their inputs), counts the tasks
 * workflows give them, and tells them when a task arrives or one of their runs finishes. It signs in with the person's
 * API key from the Command Center (Your API keys); on Windows the key is kept protected for this Windows user (DPAPI).
 * <pre>
 * ooa assistant [--url https://ooa.company.com]      start (asks for the address and key the first time)
 * ooa assistant --sign-out                           forget the address and key
 * </pre>
 */
final class Assistant {
    private Assistant() { }

    static Path settingsFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("ONEOFFICEAUTOMATION").resolve("assistant.json");
    }

    // ---------- the key, protected for this Windows user ----------

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Encrypts with DPAPI for the current Windows user (PowerShell; the secret goes in the environment, not the command line). */
    static String protect(String secret) { return WINDOWS ? dpapi("Protect", secret) : "plain:" + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)); }

    static String unprotect(String stored) {
        if (stored.startsWith("plain:")) return new String(Base64.getDecoder().decode(stored.substring(6)), StandardCharsets.UTF_8);
        return dpapi("Unprotect", stored);
    }

    private static String dpapi(String op, String value) {
        String script = op.equals("Protect")
            ? "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($env:OOA_A), $null, 'CurrentUser'))"
            : "[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($env:OOA_A), $null, 'CurrentUser'))";
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", "Add-Type -AssemblyName System.Security; " + script);
        pb.environment().put("OOA_A", value);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0 || out.isEmpty()) throw new OoaException("ASSISTANT.KEY", "Cannot " + op.toLowerCase(Locale.ROOT) + " the API key for this Windows user");
            return out;
        } catch (IOException e) { throw new OoaException("ASSISTANT.KEY", "Cannot run PowerShell: " + e.getMessage());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("ASSISTANT.KEY", "Interrupted"); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadSettings() {
        try { return (Map<String, Object>) JsonLite.parse(Files.readString(settingsFile())); }
        catch (IOException | RuntimeException e) { return new LinkedHashMap<>(); }
    }

    static void saveSettings(String url, String apiKey) throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), JsonLite.write(Map.of("url", url, "key", protect(apiKey))));
    }

    // ---------- start ----------

    static int run(Map<String, String> o) throws Exception {
        if (o.containsKey("sign-out")) { Files.deleteIfExists(settingsFile()); System.out.println("Signed out: " + settingsFile() + " removed."); return 0; }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            System.err.println("The assistant needs a desktop with a notification area (Windows). Runs for this person can still be started from One Command Center.");
            return 1;
        }
        Map<String, Object> s = loadSettings();
        String url = o.getOrDefault("url", (String) s.get("url"));
        String key = s.get("key") == null ? null : unprotect(String.valueOf(s.get("key")));
        if (url == null || key == null || o.containsKey("url")) {
            String[] got = askSignIn(url);
            if (got == null) return 1;
            url = got[0]; key = got[1];
        }
        AssistantClient c = new AssistantClient(url, key, null);
        String me;
        try { me = c.username(); }
        catch (OoaException e) { JOptionPane.showMessageDialog(null, e.getMessage(), "ONEOFFICEAUTOMATION", JOptionPane.ERROR_MESSAGE); return 1; }
        saveSettings(url, key);
        new Tray(c, me).start();
        Thread.currentThread().join();   // the tray keeps the program running until Exit
        return 0;
    }

    static String[] askSignIn(String url) {
        JTextField address = new JTextField(url == null ? "https://" : url, 30);
        JPasswordField key = new JPasswordField(30);
        Object[] form = {"Command Center address", address, "Your API key (One Command Center > your name > API keys)", key};
        if (JOptionPane.showConfirmDialog(null, form, "Sign in to ONEOFFICEAUTOMATION", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
        return new String[]{address.getText().strip(), new String(key.getPassword()).strip()};
    }

    // ---------- the tray ----------

    static final class Tray {
        final AssistantClient c;
        final String me;
        final AssistantClient.Watch watch = new AssistantClient.Watch();
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-assistant"); t.setDaemon(true); return t; });
        TrayIcon icon;
        final Menu agents = new Menu("Run an agent");
        final MenuItem inbox = new MenuItem("Task inbox");
        String lastPage = "activity";

        Tray(AssistantClient c, String me) { this.c = c; this.me = me; }

        void start() throws AWTException {
            PopupMenu menu = new PopupMenu();
            MenuItem who = new MenuItem("Signed in as " + me);
            who.setEnabled(false);
            menu.add(who);
            menu.addSeparator();
            menu.add(agents);
            inbox.addActionListener(e -> open("inbox"));
            menu.add(inbox);
            MenuItem runs = new MenuItem("My runs");
            runs.addActionListener(e -> open("activity"));
            menu.add(runs);
            MenuItem cc = new MenuItem("Open One Command Center");
            cc.addActionListener(e -> open(""));
            menu.add(cc);
            MenuItem refresh = new MenuItem("Refresh");
            refresh.addActionListener(e -> timer.execute(this::poll));
            menu.add(refresh);
            menu.addSeparator();
            MenuItem signOut = new MenuItem("Sign out");
            signOut.addActionListener(e -> { try { Files.deleteIfExists(settingsFile()); } catch (IOException ignored) { } System.exit(0); });
            menu.add(signOut);
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> System.exit(0));
            menu.add(exit);
            icon = new TrayIcon(image(), "ONEOFFICEAUTOMATION assistant", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> open(lastPage));   // clicking a notice (or the icon) opens what it was about
            SystemTray.getSystemTray().add(icon);
            timer.scheduleWithFixedDelay(this::poll, 0, 30, TimeUnit.SECONDS);
            timer.scheduleWithFixedDelay(this::loadAgents, 0, 5, TimeUnit.MINUTES);
        }

        static Image image() {
            try (var in = Assistant.class.getResourceAsStream("/studio/logo-mark.png")) {
                if (in != null) return javax.imageio.ImageIO.read(in);
            } catch (IOException ignored) { }
            BufferedImage b = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.setColor(new Color(0x2563eb)); g.fillOval(0, 0, 16, 16); g.dispose();
            return b;
        }

        void open(String page) {
            try { Desktop.getDesktop().browse(URI.create(c.base() + "/" + (page.isEmpty() ? "" : "#" + page))); }
            catch (IOException | UnsupportedOperationException e) { notice("Cannot open the browser", e.getMessage(), TrayIcon.MessageType.WARNING); }
        }

        void notice(String title, String text, TrayIcon.MessageType type) { if (icon != null) icon.displayMessage(title, text, type); }

        void poll() {
            try {
                List<Map<String, Object>> tasks = c.openTasks();
                EventQueue.invokeLater(() -> inbox.setLabel(tasks.isEmpty() ? "Task inbox" : "Task inbox (" + tasks.size() + ")"));
                for (AssistantClient.Notice n : watch.update(tasks, c.myRuns(me))) {
                    lastPage = n.page();
                    notice(n.title(), n.text(), n.title().equals("Agent failed") ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
                }
                if (icon != null) icon.setToolTip("ONEOFFICEAUTOMATION assistant" + (tasks.isEmpty() ? "" : ": " + tasks.size() + " task(s) waiting"));
            } catch (RuntimeException e) {
                if (icon != null) icon.setToolTip("ONEOFFICEAUTOMATION assistant: " + e.getMessage());
            }
        }

        void loadAgents() {
            try {
                List<Map<String, Object>> list = c.agents();
                EventQueue.invokeLater(() -> {
                    agents.removeAll();
                    if (list.isEmpty()) { MenuItem none = new MenuItem("(no agents you may run)"); none.setEnabled(false); agents.add(none); }
                    for (Map<String, Object> b : list) {
                        String path = String.valueOf(b.get("path"));
                        MenuItem item = new MenuItem(path.replaceAll("\\.flow\\.json$", ""));
                        item.addActionListener(e -> runAgent(path));
                        agents.add(item);
                    }
                });
            } catch (RuntimeException ignored) { /* tried again in five minutes or on Refresh */ }
        }

        /** Asks for the agent's inputs (credentials as vault references), then starts it on this person's default runner. */
        void runAgent(String path) {
            new Thread(() -> {
                try {
                    Map<String, Object> inputs = new LinkedHashMap<>();
                    for (Map<String, Object> v : c.inputs(path)) {
                        String name = String.valueOf(v.get("name"));
                        boolean credential = "Credential".equals(v.get("type"));
                        String prompt = name + (v.get("description") == null ? "" : " (" + v.get("description") + ")")
                            + (credential ? "\nA vault reference: folder/credential/attribute" : "");
                        Object def = v.get("default");
                        String answer = JOptionPane.showInputDialog(null, prompt, def == null || credential ? "" : String.valueOf(def));
                        if (answer == null) return;   // cancelled
                        if (answer.isBlank()) { if (Boolean.TRUE.equals(v.get("required"))) { notice("Not started", name + " is required", TrayIcon.MessageType.WARNING); return; } continue; }
                        inputs.put(name, credential ? Map.of("$credential", answer.strip()) : value(String.valueOf(v.get("type")), answer));
                    }
                    String run = c.run(path, inputs, me);
                    lastPage = "activity";
                    notice("Agent started", path.replaceAll("\\.flow\\.json$", "") + " (" + run + ")", TrayIcon.MessageType.INFO);
                } catch (RuntimeException e) {
                    notice("Not started", e.getMessage(), TrayIcon.MessageType.ERROR);
                }
            }, "ooa-assistant-run").start();
        }
    }

    /** An input typed as text, as the variable's type wants it. */
    static Object value(String type, String text) {
        String t = text.strip();
        return switch (type) {
            case "Number" -> { try { yield new java.math.BigDecimal(t); } catch (NumberFormatException e) { throw new OoaException("ASSISTANT.BAD_INPUT", "'" + t + "' is not a number"); } }
            case "Boolean" -> Set.of("true", "yes", "1").contains(t.toLowerCase(Locale.ROOT));
            case "List", "Dictionary", "Record", "Table" -> JsonLite.parse(t);
            default -> text;
        };
    }
}
