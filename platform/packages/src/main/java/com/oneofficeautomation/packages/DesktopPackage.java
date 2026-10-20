package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.JFileChooser;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mouse, keyboard, screen capture, clipboard and attended dialogs (message box, prompts).
 * Key names: ENTER, TAB, ESC, BACKSPACE, DELETE, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, SPACE, F1..F12,
 * combined with + (e.g. CTRL+S, ALT+F4, CTRL+SHIFT+ESC).
 */
@OoaPackage(id = "ooa.desktop", name = "Mouse, keyboard and screen", version = "1.0.0",
        description = "Click, type, press keys, capture the screen, use the clipboard and show dialogs on the desktop.")
public final class DesktopPackage {
    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        Object[][] k = {{"ENTER", KeyEvent.VK_ENTER}, {"TAB", KeyEvent.VK_TAB}, {"ESC", KeyEvent.VK_ESCAPE}, {"ESCAPE", KeyEvent.VK_ESCAPE},
                {"BACKSPACE", KeyEvent.VK_BACK_SPACE}, {"DELETE", KeyEvent.VK_DELETE}, {"INSERT", KeyEvent.VK_INSERT}, {"UP", KeyEvent.VK_UP},
                {"DOWN", KeyEvent.VK_DOWN}, {"LEFT", KeyEvent.VK_LEFT}, {"RIGHT", KeyEvent.VK_RIGHT}, {"HOME", KeyEvent.VK_HOME}, {"END", KeyEvent.VK_END},
                {"PAGE_UP", KeyEvent.VK_PAGE_UP}, {"PAGE_DOWN", KeyEvent.VK_PAGE_DOWN}, {"SPACE", KeyEvent.VK_SPACE}, {"CTRL", KeyEvent.VK_CONTROL},
                {"ALT", KeyEvent.VK_ALT}, {"SHIFT", KeyEvent.VK_SHIFT}, {"WIN", KeyEvent.VK_WINDOWS}, {"PRINTSCREEN", KeyEvent.VK_PRINTSCREEN}};
        for (Object[] e : k) KEYS.put((String) e[0], (Integer) e[1]);
        for (int i = 1; i <= 12; i++) KEYS.put("F" + i, KeyEvent.VK_F1 + i - 1);
        for (char c = 'A'; c <= 'Z'; c++) KEYS.put(String.valueOf(c), KeyEvent.VK_A + (c - 'A'));
        for (char c = '0'; c <= '9'; c++) KEYS.put(String.valueOf(c), KeyEvent.VK_0 + (c - '0'));
    }

    /** Parses "CTRL+SHIFT+ESC" into key codes; unknown names are rejected with a list of valid ones. */
    static List<Integer> parseKeys(String combo) {
        List<Integer> codes = new ArrayList<>();
        for (String part : combo.toUpperCase(Locale.ROOT).split("\\+")) {
            Integer c = KEYS.get(part.trim());
            if (c == null) throw new ActionException("DESKTOP.BAD_KEY", "Unknown key '" + part.trim() + "' in '" + combo + "'",
                    "Use names like ENTER, TAB, ESC, F5, CTRL+S, ALT+F4.");
            codes.add(c);
        }
        return codes;
    }

    private static int button(String b) {
        return switch (b) { case "right" -> InputEvent.BUTTON3_DOWN_MASK; case "middle" -> InputEvent.BUTTON2_DOWN_MASK; default -> InputEvent.BUTTON1_DOWN_MASK; };
    }

    @Action(id = "mouse_click", name = "Mouse click", category = "Mouse", capabilities = "desktop", description = "Click at screen coordinates.")
    public Map<String, Object> click(ActionContext ctx,
            @Input(name = "x", type = OoaType.NUMBER, description = "Horizontal position in pixels from the left.") int x,
            @Input(name = "y", type = OoaType.NUMBER, description = "Vertical position in pixels from the top.") int y,
            @Input(name = "button", type = OoaType.STRING, required = false, defaultValue = "\"left\"", enumValues = {"left", "right", "middle"}, description = "Mouse button.") String b,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl) {
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        r.mouseMove(x, y);
        for (int i = 0; i < (dbl ? 2 : 1); i++) { r.mousePress(button(b)); r.mouseRelease(button(b)); r.delay(40); }
        return Map.of();
    }

    @Action(id = "mouse_move", name = "Mouse move", category = "Mouse", capabilities = "desktop", description = "Move the pointer to screen coordinates.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "x", type = OoaType.NUMBER, description = "Horizontal position.") int x,
            @Input(name = "y", type = OoaType.NUMBER, description = "Vertical position.") int y) {
        DesktopSupport.robot().mouseMove(x, y);
        return Map.of();
    }

    @Action(id = "mouse_scroll", name = "Mouse scroll", category = "Mouse", capabilities = "desktop", description = "Scroll the mouse wheel; positive scrolls down.")
    public Map<String, Object> scroll(ActionContext ctx, @Input(name = "amount", type = OoaType.NUMBER, description = "Wheel notches; negative scrolls up.") int amount) {
        UiGuard.before(ctx);
        DesktopSupport.robot().mouseWheel(amount);
        return Map.of();
    }

    @Action(id = "type_text", name = "Type text", category = "Keyboard", capabilities = "desktop",
            description = "Type text into the active window (pasted via the clipboard by default, which handles any language).")
    public Map<String, Object> typeText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to type.") String text,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"paste\"", enumValues = {"paste", "keystrokes"},
                    description = "paste uses the clipboard (restored afterwards); keystrokes types letters and digits one by one.") String method,
            @Input(name = "delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "20", description = "Delay between keystrokes.") int delay) {
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        if ("keystrokes".equals(method)) {
            for (char c : text.toCharArray()) {
                if (c == '\n') { r.keyPress(KeyEvent.VK_ENTER); r.keyRelease(KeyEvent.VK_ENTER); continue; }
                int code = KeyEvent.getExtendedKeyCodeForChar(c);
                if (code == KeyEvent.VK_UNDEFINED) throw new ActionException("DESKTOP.UNTYPABLE", "Cannot type '" + c + "' as a keystroke", "Use method paste for this text.");
                boolean shift = Character.isUpperCase(c);
                if (shift) r.keyPress(KeyEvent.VK_SHIFT);
                r.keyPress(code); r.keyRelease(code);
                if (shift) r.keyRelease(KeyEvent.VK_SHIFT);
                r.delay(delay);
            }
            return Map.of();
        }
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = null;
        try { previous = cb.getContents(null); } catch (IllegalStateException ignored) { }
        cb.setContents(new StringSelection(text), null);
        int mod = ProcessRunner.WINDOWS || !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? KeyEvent.VK_CONTROL : KeyEvent.VK_META;
        r.keyPress(mod); r.keyPress(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_V); r.keyRelease(mod);
        r.delay(150);
        if (previous != null) try { cb.setContents(previous, null); } catch (IllegalStateException ignored) { }
        return Map.of();
    }

    @Action(id = "press_keys", name = "Press keys", category = "Keyboard", capabilities = "desktop",
            description = "Press a key or key combination, e.g. ENTER, CTRL+S or ALT+F4; repeat if needed.")
    public Map<String, Object> pressKeys(ActionContext ctx,
            @Input(name = "keys", type = OoaType.STRING, example = "CTRL+S", description = "Key or combination joined with +.") String keys,
            @Input(name = "times", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many times to press.") int times) {
        List<Integer> codes = parseKeys(keys);  // validated before touching the desktop
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        for (int t = 0; t < Math.max(1, times); t++) {
            for (int c : codes) r.keyPress(c);
            for (int i = codes.size() - 1; i >= 0; i--) r.keyRelease(codes.get(i));
            r.delay(40);
        }
        return Map.of();
    }

    @Action(id = "capture_screen", name = "Capture screen", category = "Screen", capabilities = "desktop",
            description = "Save a screenshot of the whole screen or a region as a PNG file.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved image path.")
    public Map<String, Object> capture(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PNG file to save.") String path,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Region left (whole screen when empty).") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Region top.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Region width.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Region height.") Integer h) {
        Robot r = DesktopSupport.robot();
        Rectangle area = x == null || y == null || w == null || h == null ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()) : new Rectangle(x, y, w, h);
        Path p = ctx.resolvePath(path);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            ImageIO.write(r.createScreenCapture(area), "png", p.toFile());
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    static BufferedImage screen() { return DesktopSupport.robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())); }

    @Action(id = "clipboard_get", name = "Get clipboard text", category = "Clipboard", capabilities = "desktop", description = "Read text from the clipboard.")
    @Output(name = "text", type = OoaType.STRING, description = "Clipboard text, empty when it holds no text.")
    public Map<String, Object> clipboardGet(ActionContext ctx) {
        DesktopSupport.requireDesktop();
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            return Map.of("text", t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor) ? (String) t.getTransferData(DataFlavor.stringFlavor) : "");
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) { throw new ActionException("CLIPBOARD.BUSY", "Clipboard unavailable: " + e.getMessage(), null, true); }
    }

    @Action(id = "clipboard_set", name = "Copy to clipboard", category = "Clipboard", capabilities = "desktop", description = "Put text on the clipboard.")
    public Map<String, Object> clipboardSet(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to copy.") String text) {
        DesktopSupport.requireDesktop();
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
        catch (IllegalStateException e) { throw new ActionException("CLIPBOARD.BUSY", "Clipboard unavailable: " + e.getMessage(), null, true); }
        return Map.of();
    }

    @Action(id = "clipboard_clear", name = "Clear clipboard", category = "Clipboard", capabilities = "desktop", description = "Empty the clipboard.")
    public Map<String, Object> clipboardClear(ActionContext ctx) { return clipboardSet(ctx, ""); }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        try { SwingUtilities.invokeAndWait(() -> { try { ref.set(c.call()); } catch (Exception e) { err.set(e); } }); }
        catch (Exception e) { throw new ActionException("DESKTOP.DIALOG_FAILED", e.getMessage()); }
        if (err.get() != null) throw new ActionException("DESKTOP.DIALOG_FAILED", err.get().getMessage());
        return ref.get();
    }

    private static JDialog dialog(JOptionPane pane, String title, int timeoutSeconds) {
        JDialog d = pane.createDialog(null, title);
        d.setAlwaysOnTop(true);
        d.setModal(true);
        if (timeoutSeconds > 0) { javax.swing.Timer t = new javax.swing.Timer(timeoutSeconds * 1000, e -> d.dispose()); t.setRepeats(false); t.start(); }
        return d;
    }

    @Action(id = "message_box", name = "Message box", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Show a message to the user (attended agents); closes automatically after the timeout.")
    public Map<String, Object> messageBox(ActionContext ctx,
            @Input(name = "message", type = OoaType.STRING, description = "Message text.") String message,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"ONEOFFICEAUTOMATION\"", description = "Window title.") String title,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Close automatically after this many seconds; 0 waits for OK.") int timeout) {
        DesktopSupport.requireDesktop();
        onEdt(() -> { dialog(new JOptionPane(message, JOptionPane.INFORMATION_MESSAGE), title, timeout).setVisible(true); return null; });
        return Map.of();
    }

    @Action(id = "prompt_value", name = "Prompt for value", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user to type a value.")
    @Output(name = "value", type = OoaType.STRING, description = "Entered text.")
    @Output(name = "cancelled", type = OoaType.BOOLEAN, description = "True when the user cancelled or the prompt timed out.")
    public Map<String, Object> promptValue(ActionContext ctx,
            @Input(name = "question", type = OoaType.STRING, description = "Question to show.") String question,
            @Input(name = "default", type = OoaType.STRING, required = false, description = "Pre-filled answer.") String def,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Give up after this many seconds; 0 waits.") int timeout) {
        DesktopSupport.requireDesktop();
        String v = onEdt(() -> {
            JOptionPane pane = new JOptionPane(question, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            pane.setWantsInput(true);
            if (def != null) pane.setInitialSelectionValue(def);
            dialog(pane, "ONEOFFICEAUTOMATION", timeout).setVisible(true);
            Object in = pane.getInputValue();
            return Objects.equals(pane.getValue(), JOptionPane.OK_OPTION) && in != JOptionPane.UNINITIALIZED_VALUE ? String.valueOf(in) : null;
        });
        return Out.of("value", v == null ? "" : v, "cancelled", v == null);
    }

    @Action(id = "prompt_yes_no", name = "Prompt for yes/no", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user a yes/no question.")
    @Output(name = "answer", type = OoaType.BOOLEAN, description = "True for Yes.")
    public Map<String, Object> promptYesNo(ActionContext ctx,
            @Input(name = "question", type = OoaType.STRING, description = "Question to show.") String question,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Answer No after this many seconds; 0 waits.") int timeout) {
        DesktopSupport.requireDesktop();
        boolean yes = onEdt(() -> {
            JOptionPane pane = new JOptionPane(question, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
            dialog(pane, "ONEOFFICEAUTOMATION", timeout).setVisible(true);
            return Objects.equals(pane.getValue(), JOptionPane.YES_OPTION);
        });
        return Map.of("answer", yes);
    }

    @Action(id = "prompt_password", name = "Prompt for password", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Ask the user for a password; the result is a Credential that is never shown or logged.")
    @Output(name = "password", type = OoaType.CREDENTIAL, description = "Entered password as a Credential.")
    public Map<String, Object> promptPassword(ActionContext ctx,
            @Input(name = "question", type = OoaType.STRING, required = false, defaultValue = "\"Enter password\"", description = "Prompt text.") String question) {
        DesktopSupport.requireDesktop();
        char[] pw = onEdt(() -> {
            JPasswordField f = new JPasswordField(24);
            JOptionPane pane = new JOptionPane(new Object[]{question, f}, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            dialog(pane, "ONEOFFICEAUTOMATION", 0).setVisible(true);
            return Objects.equals(pane.getValue(), JOptionPane.OK_OPTION) ? f.getPassword() : null;
        });
        if (pw == null) throw new ActionException("DESKTOP.CANCELLED", "The user cancelled the password prompt");
        SecureString s = new SecureString(new String(pw), "prompt");
        Arrays.fill(pw, '\0');
        return Map.of("password", s);
    }


    private Map<String, Object> pick(String title, boolean folders, String startIn, ActionContext ctx) {
        DesktopSupport.requireDesktop();
        String chosen = onEdt(() -> {
            JFileChooser fc = new JFileChooser(startIn == null ? ctx.workDir().toFile() : ctx.resolvePath(startIn).toFile());
            fc.setDialogTitle(title);
            fc.setFileSelectionMode(folders ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            return fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile().getAbsolutePath() : null;
        });
        return Out.of("path", chosen == null ? "" : chosen, "cancelled", chosen == null);
    }

    @Action(id = "prompt_file", name = "Prompt for file", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user to choose a file.")
    @Output(name = "path", type = OoaType.FILE, description = "Chosen file, empty when cancelled.")
    @Output(name = "cancelled", type = OoaType.BOOLEAN, description = "True when cancelled.")
    public Map<String, Object> promptFile(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"Choose a file\"", description = "Dialog title.") String title,
            @Input(name = "start_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String start) {
        return pick(title, false, start, ctx);
    }

    @Action(id = "prompt_folder", name = "Prompt for folder", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user to choose a folder.")
    @Output(name = "path", type = OoaType.FILE, description = "Chosen folder, empty when cancelled.")
    @Output(name = "cancelled", type = OoaType.BOOLEAN, description = "True when cancelled.")
    public Map<String, Object> promptFolder(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"Choose a folder\"", description = "Dialog title.") String title,
            @Input(name = "start_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String start) {
        return pick(title, true, start, ctx);
    }
}
