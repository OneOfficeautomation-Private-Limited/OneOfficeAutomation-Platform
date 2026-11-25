package com.oneofficeautomation.runner;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The run window (item 1, 21 Nov 2026, design A): a dark strip at the bottom right of the screen while an agent runs,
 * on top of the applications the agent works in without taking the keyboard from them. It shows the agent, the line
 * being run (its number as on the canvas) with its action, a progress bar, the time, and Pause/Resume and Stop when the
 * administrator allows them. Three sizes: Small (one line and the bar), Medium (also the line's action and the time),
 * Large (also the last lines of the run). Minimise turns it into a small tab at the screen edge; a click brings it back.
 * A failed run gets a red edge and stays a little longer. Used by OneAgentStudio and by runners for One CommandCenter
 * runs (agent RunWorker), so both show the same window.
 */
public final class RunWindow {
    /** How much the window shows. */
    public enum Size {
        SMALL(360, 68), MEDIUM(420, 122), LARGE(520, 250);
        final int width, height;
        Size(int w, int h) { width = w; height = h; }
        /** "small", "medium" or "large" (any case); anything else is Medium. */
        public static Size of(Object v) {
            String s = v == null ? "" : String.valueOf(v).trim().toUpperCase(Locale.ROOT);
            for (Size z : values()) if (z.name().equals(s)) return z;
            return MEDIUM;
        }
    }

    /** How the window opens: its size, whether it starts as the tab, whether Pause and Stop are offered. */
    public record Options(Size size, boolean startMinimised, boolean controls) {
        public static final Options DEFAULT = new Options(Size.MEDIUM, false, true);
        /** From a job's or a request's run_window: true, or {size, minimised, controls}; null or false: no window. */
        public static Options from(Object v) {
            if (v == null || Boolean.FALSE.equals(v)) return null;
            if (v instanceof Map<?, ?> m) {
                if (Boolean.FALSE.equals(m.get("show"))) return null;
                return new Options(Size.of(m.get("size")), Boolean.TRUE.equals(m.get("minimised")), !Boolean.FALSE.equals(m.get("controls")));
            }
            if (v instanceof String s && !s.isBlank()) return s.equalsIgnoreCase("hidden") || s.equalsIgnoreCase("false") ? null : new Options(Size.of(s), false, true);
            return DEFAULT;
        }
    }

    /**
     * True when a desktop window can be shown: not headless, a display to show it on, and not switched off with
     * OOA_NO_RUN_WINDOW. Runners start the run's worker with java.awt.headless=false, so on Linux without a display
     * isHeadless() alone still says yes; the display variables are checked too.
     */
    public static boolean available() {
        return !GraphicsEnvironment.isHeadless() && System.getenv("OOA_NO_RUN_WINDOW") == null
                && hasDisplay(System.getProperty("os.name", ""), System.getenv());
    }

    /** Windows and macOS always have a desktop to draw on; other systems need DISPLAY (X11) or WAYLAND_DISPLAY. */
    static boolean hasDisplay(String osName, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows") || os.startsWith("mac")) return true;
        return !isBlank(env.get("DISPLAY")) || !isBlank(env.get("WAYLAND_DISPLAY"));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Command display names from the installed toolkits ("ooa.excel/open" to "Open"), for the window's line text. */
    public static Function<String, String> names(List<Map<String, Object>> manifests) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> m : manifests)
            if (m.get("actions") instanceof List<?> acts) for (Object a : acts)
                if (a instanceof Map<?, ?> am) names.put(m.get("id") + "/" + am.get("id"), String.valueOf(am.get("name")));
        return a -> names.getOrDefault(a, a);
    }

    /**
     * Opens the window for a run; null when it cannot be shown. done tells whether the run has finished; names gives a
     * command's display name.
     */
    public static RunWindow open(String agent, Map<String, Object> flow, EventSink.ListSink sink, AtomicBoolean pause, AtomicBoolean cancel,
                                 BooleanSupplier done, Function<String, String> names, Options options) {
        if (!available()) return null;
        RunWindow[] w = new RunWindow[1];
        // AWT that cannot start throws errors (AWTError, HeadlessException, a missing native library), not only
        // exceptions: the run carries on without the window
        try { SwingUtilities.invokeAndWait(() -> w[0] = new RunWindow(agent, flow, sink, pause, cancel, done, names, options == null ? Options.DEFAULT : options)); }
        catch (Throwable e) { return null; }
        return w[0];
    }

    // ------------------------------------------------------------------ what the window shows (no Swing: tested alone)

    /** The run as the window shows it, worked out from the run's events. */
    static final class Progress {
        final Map<String, String> numbers = new LinkedHashMap<>();
        final int total;
        int seen;
        /** Lines that have run at least once: a line repeated by a loop counts once, so the bar never runs ahead. */
        final Set<String> finished = new HashSet<>();
        String line = "Starting", failed, ended;
        final Deque<String> last = new ArrayDeque<>();
        static final int LAST_LINES = 7;

        Progress(Map<String, Object> flow) {
            number(list(flow, "steps"), "");
            for (var sub : Steps.subflows(flow).entrySet()) number(sub.getValue(), " of subflow " + sub.getKey());
            total = numbers.size();
            number(Steps.onErrorSteps(flow), "");   // the error handler's lines follow; they run only after an error
        }

        /** Reads the events not seen yet. */
        void read(List<RunEvent> events, Function<String, String> names) {
            for (int i = seen; i < events.size(); i++) {
                RunEvent e = events.get(i);
                String num = e.stepId() == null ? null : numbers.getOrDefault(e.stepId(), "?");
                Object action = e.data().get("action");
                String what = action == null ? "" : names.apply(String.valueOf(action));
                switch (e.type()) {
                    case "step.started" -> { if (num != null) line = "Line " + num + ": " + what; }
                    case "step.finished" -> { if (e.stepId() != null) finished.add(e.stepId()); if (num != null) add("✓ " + num + "  " + what); }
                    case "step.failed" -> {
                        failed = e.data().get("code") + ": " + e.data().get("message");
                        add("✗ " + (num == null ? "" : num + "  ") + what + " — " + e.data().get("message"));
                    }
                    case "log" -> add("• " + e.data().get("message"));
                    case "run.finished" -> ended = String.valueOf(e.data().get("status"));
                    default -> { }
                }
            }
            seen = events.size();
        }

        private void add(String s) {
            last.addLast(s.length() > 90 ? s.substring(0, 89) + "…" : s);
            while (last.size() > LAST_LINES) last.removeFirst();
        }

        int done() {
            int n = 0, i = 0;
            for (String id : numbers.keySet()) { if (i++ >= total) break; if (finished.contains(id)) n++; }   // the error handler's lines are not counted
            return n;
        }

        /** "Finished", "Failed" or "Stopped" once the run is over. */
        String outcome() {
            if (ended != null && (ended.equals("cancelled") || ended.equals("canceled"))) return "Stopped";
            return failed != null || "failed".equals(ended) ? "Failed" : "Finished";
        }

        /** Line numbers as on the canvas: every step counted from the top, nested ones included; a subflow from 1 again. */
        private void number(List<Object> steps, String suffix) {
            int n = 0;
            for (Map<String, Object> s : Steps.walk(steps)) numbers.put(String.valueOf(s.get("id")), ++n + suffix);
        }
    }

    static String time(long secs) { return secs >= 3600 ? secs / 3600 + " h " + secs % 3600 / 60 + " min" : secs >= 60 ? secs / 60 + " min " + secs % 60 + " s" : secs + " s"; }

    // ------------------------------------------------------------------ the window

    // design A: deep navy strip, logo blue for the bar and the edge, red edge when the run failed
    static final Color NAVY = new Color(0x10243f), EDGE = new Color(0x0c66a3), BAR = new Color(0x4da3e0), TRACK = new Color(0x26405f),
            TEXT = Color.WHITE, MUTED = new Color(0xc3cede), FAIL = new Color(0xe05252), FAIL_TEXT = new Color(0xff8a8a), BUTTON = new Color(0x1d3a5f), BUTTON_HOVER = new Color(0x2a4c78);

    private final JWindow win, tab;
    private final JLabel status = new JLabel("Running"), line = new JLabel(" "), detail = new JLabel(" "), tabText = new JLabel();
    private final JTextArea lastLines = new JTextArea();
    private final ProgressBar bar = new ProgressBar();
    private final JButton pauseBtn, stopBtn;
    private final JPanel body;
    private JPanel middle;
    private final String agent;
    private final javax.swing.Timer timer;
    private final Progress progress;
    private final Options options;
    private final long started = System.currentTimeMillis();
    private final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);

    /**
     * Waits until the window has shown how the run ended and closed (a few seconds), at most maxMillis: a runner's
     * worker process ends right after the run, which would otherwise take the window with it.
     */
    public void waitUntilClosed(long maxMillis) {
        try { closed.await(maxMillis, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private RunWindow(String agent, Map<String, Object> flow, EventSink.ListSink sink, AtomicBoolean pause, AtomicBoolean cancel,
                      BooleanSupplier done, Function<String, String> names, Options options) {
        this.options = options;
        this.agent = agent;
        progress = new Progress(flow);
        Font base = UIManager.getFont("Label.font") == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : UIManager.getFont("Label.font");
        win = new JWindow();
        win.setAlwaysOnTop(true);
        win.setFocusableWindowState(false);   // the agent's own windows keep the keyboard
        body = new JPanel(new BorderLayout(0, 6));
        body.setBackground(NAVY);
        edge(EDGE);

        JLabel title = new JLabel(agent);
        title.setFont(base.deriveFont(Font.BOLD, 13f));
        title.setForeground(TEXT);
        status.setFont(base.deriveFont(Font.BOLD, 11f));
        status.setForeground(MUTED);
        pauseBtn = button("Pause", "Pause the run after the line running now");
        stopBtn = button("Stop", "Stop the run after the line running now");
        JButton minBtn = button("–", "Minimise: keep a small tab at the screen edge");
        pauseBtn.addActionListener(e -> { pause.set(!pause.get()); pauseBtn.setText(pause.get() ? "Resume" : "Pause"); });
        stopBtn.addActionListener(e -> { cancel.set(true); pause.set(false); stopBtn.setEnabled(false); pauseBtn.setEnabled(false); status.setText("Stopping…"); });
        minBtn.addActionListener(e -> minimise(true));

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JPanel names2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        names2.setOpaque(false);
        names2.add(title); names2.add(status);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        if (options.controls()) { buttons.add(pauseBtn); buttons.add(stopBtn); }
        buttons.add(minBtn);
        head.add(names2, BorderLayout.CENTER);
        head.add(buttons, BorderLayout.EAST);

        // the line, the bar and the time stacked tightly (the bar keeps its own height)
        JPanel middle = new JPanel();
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        middle.setOpaque(false);
        for (JLabel l : List.of(line, detail)) { l.setFont(base.deriveFont(12f)); l.setForeground(l == line ? TEXT : MUTED); l.setAlignmentX(0f); }
        bar.setAlignmentX(0f);
        if (options.size() != Size.SMALL) { middle.add(line); middle.add(Box.createVerticalStrut(5)); }
        middle.add(bar);
        if (options.size() != Size.SMALL) { middle.add(Box.createVerticalStrut(5)); middle.add(detail); }
        this.middle = middle;

        body.add(head, BorderLayout.NORTH);
        body.add(middle, BorderLayout.CENTER);
        if (options.size() == Size.LARGE) {
            lastLines.setEditable(false);
            lastLines.setFocusable(false);
            lastLines.setBackground(new Color(0x0b1a2f));
            lastLines.setForeground(MUTED);
            lastLines.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            lastLines.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            lastLines.setRows(Progress.LAST_LINES);
            body.add(lastLines, BorderLayout.SOUTH);
        }
        win.setContentPane(body);
        win.pack();   // as high as what this size shows, at the size's width
        win.setSize(options.size().width, Math.max(win.getHeight(), 56));
        Rectangle wa = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        win.setLocation(wa.x + wa.width - win.getWidth() - 12, wa.y + wa.height - win.getHeight() - 12);

        // the tab at the screen edge while minimised: what runs and how far; a click brings the window back
        tab = new JWindow();
        tab.setAlwaysOnTop(true);
        tab.setFocusableWindowState(false);
        JPanel tp = new JPanel(new BorderLayout());
        tp.setBackground(NAVY);
        tp.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(2, 2, 0, 2, EDGE), BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        tabText.setFont(base.deriveFont(Font.BOLD, 11f));
        tabText.setForeground(TEXT);
        tabText.setToolTipText("Show the run window");
        tp.add(tabText, BorderLayout.CENTER);
        tp.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tp.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { minimise(false); } });
        tab.setContentPane(tp);
        tab.setSize(260, 26);
        tab.setLocation(wa.x + wa.width - 260 - 12, wa.y + wa.height - 26);

        update(sink, pause, done, names);
        if (options.startMinimised()) tab.setVisible(true); else win.setVisible(true);
        timer = new javax.swing.Timer(300, e -> update(sink, pause, done, names));
        timer.start();
    }

    private void edge(Color c) {
        body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 4, 1, 1, c), BorderFactory.createEmptyBorder(8, 12, 8, 10)));
    }

    private void minimise(boolean min) {
        win.setVisible(!min);
        tab.setVisible(min);
    }

    private static JButton button(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setBackground(BUTTON);
        b.setForeground(TEXT);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x3d5d85)), BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (b.isEnabled()) b.setBackground(BUTTON_HOVER); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(BUTTON); }
        });
        return b;
    }

    private void update(EventSink.ListSink sink, AtomicBoolean pause, BooleanSupplier done, Function<String, String> names) {
        progress.read(sink.events(), names);
        String time = time((System.currentTimeMillis() - started) / 1000);
        line.setText(progress.line);
        detail.setText(progress.done() + " of " + progress.total + " lines · " + time);
        bar.set(progress.done(), progress.total, false);
        if (options.size() == Size.LARGE) lastLines.setText(String.join("\n", progress.last));
        boolean over = done.getAsBoolean();
        String state = over ? progress.outcome() : pause.get() ? "Paused" : status.getText().startsWith("Stopping") ? "Stopping…" : "Running";
        status.setText(state);
        String name = agent.length() > 22 ? agent.substring(0, 21) + "…" : agent;
        tabText.setText("▲ " + name + " · " + state + " " + progress.done() + "/" + progress.total);
        if (!over) return;
        timer.stop();
        pauseBtn.setVisible(false); stopBtn.setVisible(false);   // nothing to pause or stop any more
        boolean failed = state.equals("Failed");
        status.setForeground(failed ? FAIL_TEXT : new Color(0x8fd6a8));
        if (failed) {
            edge(FAIL);
            bar.set(progress.done(), progress.total, true);
            String f = progress.failed == null ? "The run failed" : progress.failed;
            detail.setText(f.length() > 80 ? f.substring(0, 79) + "…" : f);
            if (options.size() == Size.SMALL) { middle.add(Box.createVerticalStrut(5)); middle.add(detail); win.setSize(win.getWidth(), win.getHeight() + 22); win.setLocation(win.getX(), win.getY() - 22); }
            minimise(false);   // a failure is shown, even when the window was minimised
        }
        javax.swing.Timer close = new javax.swing.Timer(failed ? 8000 : 3000, ev -> { win.dispose(); tab.dispose(); closed.countDown(); });
        close.setRepeats(false);
        close.start();
    }

    /** A flat bar: logo blue on a dark track, red when the run failed. */
    private static final class ProgressBar extends JComponent {
        private int done, total;
        private boolean failed;
        ProgressBar() { setPreferredSize(new Dimension(10, 8)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 8)); }
        void set(int done, int total, boolean failed) { this.done = done; this.total = total; this.failed = failed; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 6, y = (getHeight() - h) / 2;
            g2.setColor(TRACK);
            g2.fillRoundRect(0, y, getWidth(), h, h, h);
            int w = total <= 0 ? 0 : (int) Math.round(getWidth() * (double) done / total);
            g2.setColor(failed ? FAIL : BAR);
            if (w > 0) g2.fillRoundRect(0, y, Math.max(w, h), h, h, h);
            g2.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> m, String k) { return m != null && m.get(k) instanceof List<?> l ? (List<Object>) l : List.of(); }
}
