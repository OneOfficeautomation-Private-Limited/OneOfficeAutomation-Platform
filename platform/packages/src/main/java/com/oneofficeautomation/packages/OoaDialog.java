package com.oneofficeautomation.packages;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicFileChooserUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The look of the attended dialogs (design A, picked 21 Nov 2026): a white card with a 4 px top edge in the icon's
 * colour, a round icon and the bold title on one line, the message as text that can be selected and copied (in a scroll
 * box after N lines), the buttons on the right with the main one filled blue and the others white with a blue border,
 * Enter for the main button and Esc for cancel or no, and "Closes in N s" at the bottom left when the dialog closes by
 * itself. Message box and every prompt (value, password, yes/no, file, folder) share it. Plain Swing, no extra library.
 * The static helpers hold the layout rules and are unit-tested without a desktop.
 */
final class OoaDialog {
    private OoaDialog() {}

    static final int MIN_WIDTH = 360, MAX_WIDTH = 560, PAD = 20, ICON = 22;
    /** The title steps had as their default before design A: it counts as "not set". */
    static final String LEGACY_TITLE = "ONEOFFICEAUTOMATION";
    static final Color BLUE = new Color(0x2f6fde), BLUE_DARK = new Color(0x1d4fae), BLUE_TINT = new Color(0xeef3fd),
            TEXT = new Color(0x1f2430), MUTED = new Color(0x4b5565), LINE = new Color(0xc9cfd8);

    // ---------- layout rules (unit-tested) ----------

    /** Colour of the top edge and the round icon: blue for information and question, amber, red, grey for none. */
    static Color toneColor(String icon) {
        return switch (icon == null ? "information" : icon) {
            case "warning" -> new Color(0xb45309);
            case "error" -> new Color(0xb42318);
            case "none" -> new Color(0x4b5565);
            default -> BLUE;
        };
    }

    /** The sign drawn in the round icon; empty for none (no icon is drawn). */
    static String glyph(String icon) {
        return switch (icon == null ? "information" : icon) {
            case "warning" -> "!";
            case "error" -> "×";
            case "question" -> "?";
            case "none" -> "";
            default -> "i";
        };
    }

    /** True when the title is empty or the old default, so the agent's name is used instead. */
    static boolean unset(String title, String defaultTitle) {
        return title == null || title.isBlank() || LEGACY_TITLE.equals(title.strip()) || (defaultTitle != null && defaultTitle.equals(title.strip()));
    }

    /** The window's title: the step's title when set, else the agent's name, else the product's name. */
    static String windowTitle(String title, String agentName, String defaultTitle) {
        if (!unset(title, defaultTitle)) return title.strip();
        if (agentName != null && !agentName.isBlank()) return agentName.strip();
        return title != null && !title.isBlank() && !LEGACY_TITLE.equals(title.strip()) ? title.strip() : "OneOfficeAutomation";
    }

    /** The bold title in the card: the step's title, else the agent's name, else the product's name. */
    static String heading(String title, String agentName) {
        if (title != null && !title.isBlank() && !LEGACY_TITLE.equals(title.strip())) return title.strip();
        return agentName != null && !agentName.isBlank() ? agentName.strip() : "OneOfficeAutomation";
    }

    /** Card width for text whose longest line is this many pixels wide: 360 to 560 px, growing with the text. */
    static int cardWidth(int longestLinePx) { return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, longestLinePx + 2 * PAD + 4)); }

    /** The countdown at the bottom left while a dialog that closes by itself is open. */
    static String countdown(int secondsLeft) { return "Closes in " + Math.max(0, secondsLeft) + " s"; }

    /** Number of lines in a message (an empty message has none). */
    static int lines(String message) { return message == null || message.isEmpty() ? 0 : message.split("\r?\n", -1).length; }

    /** Long messages are shown in a scroll box once they have more lines than the limit (0 never scrolls). */
    static boolean scrolls(String message, int scrollAfterLines) { return scrollAfterLines > 0 && lines(message) > scrollAfterLines; }

    /** The answers of a button set, the main one first. */
    static List<String> answers(String buttons) {
        return switch (buttons == null ? "ok" : buttons) {
            case "ok_cancel" -> List.of("ok", "cancel");
            case "yes_no" -> List.of("yes", "no");
            case "yes_no_cancel" -> List.of("yes", "no", "cancel");
            default -> List.of("ok");
        };
    }

    /** What Esc answers: cancel when there is a Cancel button, no when there is a No button, else closed. */
    static String escapeAnswer(String buttons) {
        List<String> a = answers(buttons);
        return a.contains("cancel") ? "cancel" : a.contains("no") ? "no" : "closed";
    }

    /** A button's words. */
    static String label(String answer) {
        return switch (answer) {
            case "ok" -> "OK";
            case "cancel" -> "Cancel";
            case "yes" -> "Yes";
            case "no" -> "No";
            default -> Character.toUpperCase(answer.charAt(0)) + answer.substring(1);
        };
    }

    /** Width in pixels of the widest line of the text. */
    static int longestLine(String text, FontMetrics fm) {
        int w = 0;
        if (text != null) for (String line : text.split("\r?\n", -1)) w = Math.max(w, fm.stringWidth(line.replace("\t", "    ")));
        return w;
    }

    // ---------- the dialog ----------

    /** What the person did: answer is a button's name, "closed" (window closed) or "timeout" (closed by itself). */
    record Result(String answer, String text, char[] secret, List<File> files) {
        boolean timedOut() { return "timeout".equals(answer); }
    }

    /** What to show. Built with the setters below, shown with {@link #show()} on the event thread. */
    static final class Spec {
        String windowTitle = "OneOfficeAutomation", heading = "", message = "", icon = "information", buttons = "ok";
        int scrollAfter, timeout;
        boolean onTop = true;
        /** null, "text" or "password": a field under the message. */
        String input;
        String initial;
        /** A file or folder list shown in the card, with its own buttons hidden. */
        JFileChooser chooser;
        /** Words on the main button instead of OK (e.g. Choose). */
        String mainLabel;

        Spec title(String windowTitle, String heading) { this.windowTitle = windowTitle; this.heading = heading; return this; }
        Spec message(String m) { this.message = m == null ? "" : m; return this; }
        Spec icon(String i) { this.icon = i; return this; }
        Spec buttons(String b) { this.buttons = b; return this; }
        Spec scrollAfter(int n) { this.scrollAfter = Math.max(0, n); return this; }
        Spec timeout(int s) { this.timeout = Math.max(0, s); return this; }
        Spec onTop(boolean t) { this.onTop = t; return this; }
        Spec input(String kind, String initial) { this.input = kind; this.initial = initial; return this; }
        Spec chooser(JFileChooser c, String mainLabel) { this.chooser = c; this.mainLabel = mainLabel; return this; }

        /** Opens the dialog, waits until it closes and returns what the person did. Call on the event thread. */
        Result show() { return OoaDialog.show(this); }
    }

    static Spec spec() { return new Spec(); }

    private static Result show(Spec s) {
        JDialog d = new JDialog((Frame) null, s.windowTitle, true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.setAlwaysOnTop(s.onTop);
        String[] answer = {"closed"};

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        Font body = base.deriveFont(Font.PLAIN, 13f), bold = base.deriveFont(Font.BOLD, 14f), small = base.deriveFont(Font.PLAIN, 12f);
        FontMetrics fm = d.getFontMetrics(body);
        Color tone = toneColor(s.icon);
        boolean hasIcon = !glyph(s.icon).isEmpty();

        int headingPx = d.getFontMetrics(bold).stringWidth(s.heading) + (hasIcon ? ICON + 10 : 0);
        int width = s.chooser != null ? MAX_WIDTH + 80 : cardWidth(Math.max(longestLine(s.message, fm), headingPx));
        int inner = width - 2 * PAD;

        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(4, 0, 0, 0, tone),
                BorderFactory.createEmptyBorder(16, PAD, 16, PAD)));

        // round icon and bold title on one line
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        if (hasIcon) head.add(new RoundIcon(tone, glyph(s.icon), base), BorderLayout.WEST);
        JLabel h = new JLabel(s.heading);
        h.setFont(bold);
        h.setForeground(TEXT);
        head.add(h, BorderLayout.CENTER);
        card.add(head, BorderLayout.NORTH);

        // the message (selectable) and the answer field, file list or nothing
        JPanel mid = new JPanel();
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        mid.setOpaque(false);
        boolean scroll = scrolls(s.message, s.scrollAfter);
        if (!s.message.isEmpty()) {
            JTextArea area = messageArea(s.message, body);
            JComponent msg;
            if (scroll) {
                area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                JScrollPane sp = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                sp.setBorder(BorderFactory.createLineBorder(LINE));
                sp.setPreferredSize(new Dimension(inner, s.scrollAfter * fm.getHeight() + 10));
                sp.getVerticalScrollBar().setUnitIncrement(fm.getHeight());
                msg = sp;
            } else {
                area.setSize(inner, Short.MAX_VALUE);
                area.setPreferredSize(new Dimension(inner, area.getPreferredSize().height));
                msg = area;
            }
            msg.setAlignmentX(Component.LEFT_ALIGNMENT);
            mid.add(msg);
        }
        JTextField field = null;
        if (s.input != null) {
            field = "password".equals(s.input) ? new JPasswordField(s.initial == null ? "" : s.initial) : new JTextField(s.initial == null ? "" : s.initial);
            field.setFont(body);
            field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(5, 8, 5, 8)));
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.selectAll();
            mid.add(Box.createVerticalStrut(10));
            mid.add(field);
        }
        if (s.chooser != null) {
            s.chooser.setControlButtonsAreShown(false);
            s.chooser.setPreferredSize(new Dimension(inner, 330));
            s.chooser.setAlignmentX(Component.LEFT_ALIGNMENT);
            whitePanels(s.chooser);
            s.chooser.addActionListener(e -> {
                if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) { answer[0] = "ok"; d.dispose(); }
                else if (JFileChooser.CANCEL_SELECTION.equals(e.getActionCommand())) { answer[0] = "cancel"; d.dispose(); }
            });
            if (!s.message.isEmpty()) mid.add(Box.createVerticalStrut(8));
            mid.add(s.chooser);
        }
        card.add(mid, BorderLayout.CENTER);

        // countdown on the left, buttons on the right
        JPanel foot = new JPanel(new BorderLayout(12, 0));
        foot.setOpaque(false);
        JLabel count = new JLabel(s.timeout > 0 ? countdown(s.timeout) : "");
        count.setFont(small);
        count.setForeground(MUTED);
        if (s.timeout > 0) {   // room for the longest countdown, so the text never gets cut as it counts down
            FontMetrics cm = d.getFontMetrics(small);
            count.setPreferredSize(new Dimension(cm.stringWidth(countdown(s.timeout * 10 + 9)) + 8, cm.getHeight()));
        }
        foot.add(count, BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        List<String> answers = answers(s.buttons);
        DialogButton main = null;
        for (int i = 0; i < answers.size(); i++) {
            String a = answers.get(i);
            DialogButton b = new DialogButton(i == 0 && s.mainLabel != null ? s.mainLabel : label(a), i == 0, base);
            if (i == 0 && s.chooser != null) b.addActionListener(e -> approve(s.chooser));
            else b.addActionListener(e -> { answer[0] = a; d.dispose(); });
            btns.add(b);
            if (i == 0) main = b;
        }
        foot.add(btns, BorderLayout.EAST);
        card.add(foot, BorderLayout.SOUTH);

        d.setContentPane(card);
        if (s.chooser == null) d.getRootPane().setDefaultButton(main);
        String esc = escapeAnswer(s.buttons);
        d.getRootPane().registerKeyboardAction(e -> { answer[0] = esc; d.dispose(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        javax.swing.Timer timer = null;
        if (s.timeout > 0) {
            int[] left = {s.timeout};
            timer = new javax.swing.Timer(1000, e -> {
                left[0]--;
                count.setText(countdown(left[0]));
                if (left[0] <= 0) { answer[0] = "timeout"; d.dispose(); }
            });
            timer.start();
        }
        d.pack();
        d.setResizable(scroll || s.chooser != null);
        d.setLocationRelativeTo(null);
        Component first = field != null ? field : s.chooser != null ? s.chooser : main;
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { d.toFront(); if (first != null) first.requestFocusInWindow(); }
        });
        d.setVisible(true);   // modal: returns when the dialog closes
        if (timer != null) timer.stop();

        String text = null;
        char[] secret = null;
        boolean ok = "ok".equals(answer[0]);
        if (field instanceof JPasswordField p) { secret = ok ? p.getPassword() : null; p.setText(""); }
        else if (field != null) text = ok ? field.getText() : null;
        List<File> files = new ArrayList<>();
        if (s.chooser != null && ok) {
            File[] all = s.chooser.isMultiSelectionEnabled() ? s.chooser.getSelectedFiles() : new File[]{s.chooser.getSelectedFile()};
            if ((all == null || all.length == 0) && s.chooser.getSelectedFile() != null) all = new File[]{s.chooser.getSelectedFile()};
            if (all != null) for (File f : all) if (f != null) files.add(f);
        }
        return new Result(answer[0], text, secret, files);
    }

    /** The file list's own panels take the card's white, so its labels do not sit on grey bands. */
    private static void whitePanels(Container c) {
        c.setBackground(Color.WHITE);
        for (Component k : c.getComponents()) if (k instanceof JPanel p) whitePanels(p);
    }

    /** Presses the list's own approve action, so a name typed in its file name box counts as when Open is pressed. */
    private static void approve(JFileChooser fc) {
        if (fc.getUI() instanceof BasicFileChooserUI ui) ui.getApproveSelectionAction().actionPerformed(new ActionEvent(fc, ActionEvent.ACTION_PERFORMED, "approve"));
        else fc.approveSelection();
    }

    /** The message as text that can be selected and copied, but not changed; Enter still presses the main button. */
    static JTextArea messageArea(String message, Font font) {
        JTextArea a = new JTextArea(message);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(font);
        a.setForeground(TEXT);
        a.setBackground(Color.WHITE);
        a.setBorder(BorderFactory.createEmptyBorder());
        a.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "none");
        a.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        a.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> { if (a.getSelectedText() == null) a.selectAll(); a.copy(); });
        JMenuItem all = new JMenuItem("Select all");
        all.addActionListener(e -> a.selectAll());
        menu.add(copy);
        menu.add(all);
        a.setComponentPopupMenu(menu);
        return a;
    }

    /** A filled circle in the icon's colour with a white sign. */
    static final class RoundIcon extends JComponent {
        private final Color color;
        private final String sign;
        RoundIcon(Color color, String sign, Font base) {
            this.color = color; this.sign = sign;
            setFont(base.deriveFont(Font.BOLD, 14f));
            setPreferredSize(new Dimension(ICON, ICON));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y0 = (getHeight() - ICON) / 2;
            g2.setColor(color);
            g2.fillOval(0, y0, ICON, ICON);
            g2.setColor(Color.WHITE);
            FontMetrics m = g2.getFontMetrics();
            g2.drawString(sign, (ICON - m.stringWidth(sign)) / 2f, y0 + (ICON - m.getHeight()) / 2f + m.getAscent());
            g2.dispose();
        }
    }

    /** The main button is filled blue with white words; the others are white with a blue border and blue words. */
    static final class DialogButton extends JButton {
        final boolean main;
        DialogButton(String text, boolean main, Font base) {
            super(text);
            this.main = main;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setForeground(main ? Color.WHITE : BLUE);
            setFont(base.deriveFont(Font.BOLD, 13f));
            setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override public Dimension getPreferredSize() {
            Dimension p = super.getPreferredSize();
            return new Dimension(Math.max(84, p.width), Math.max(32, p.height));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean pressed = getModel().isPressed(), over = getModel().isRollover();
            g2.setColor(main ? (pressed || over ? BLUE_DARK : BLUE) : (pressed || over ? BLUE_TINT : Color.WHITE));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
            if (!main) { g2.setColor(BLUE); g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8); }
            if (isFocusOwner()) {   // keyboard focus ring
                g2.setStroke(new BasicStroke(1.5f));
                g2.setColor(main ? Color.WHITE : BLUE);
                g2.drawRoundRect(3, 3, w - 7, h - 7, 6, 6);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
