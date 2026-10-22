package com.oneofficeautomation.cli;

import com.oneofficeautomation.runner.EventSink;
import com.oneofficeautomation.runner.RunEvent;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The run window shown on the desktop while an agent started from Agent Studio runs: always on top of the
 * applications the agent works in, without taking the keyboard focus from them. It shows the agent, the line
 * being run (its line number, as on the canvas) with its action, the elapsed time and progress, and
 * Pause/Resume and Stop. It closes a few seconds after the run ends.
 */
final class RunWindow {
    private final JWindow win;
    private final JLabel status = new JLabel("Starting"), line = new JLabel(" "), detail = new JLabel(" ");
    private final JButton pauseBtn = new JButton("Pause"), stopBtn = new JButton("Stop");
    private final javax.swing.Timer timer;
    private final Map<String, String> numbers;
    private final int total;
    private final long started = System.currentTimeMillis();
    private int seen;

    /** True when a desktop window can be shown (not headless, and not switched off with OOA_NO_RUN_WINDOW). */
    static boolean available() {
        return !GraphicsEnvironment.isHeadless() && System.getenv("OOA_NO_RUN_WINDOW") == null;
    }

    /**
     * Opens the window for a run. done tells whether the run has finished; names gives an action's display name.
     */
    static RunWindow open(String agent, Map<String, Object> flow, EventSink.ListSink sink, AtomicBoolean pause, AtomicBoolean cancel,
                          java.util.function.BooleanSupplier done, Function<String, String> names) {
        RunWindow[] w = new RunWindow[1];
        try { SwingUtilities.invokeAndWait(() -> w[0] = new RunWindow(agent, flow, sink, pause, cancel, done, names)); }
        catch (Exception e) { return null; }
        return w[0];
    }

    private RunWindow(String agent, Map<String, Object> flow, EventSink.ListSink sink, AtomicBoolean pause, AtomicBoolean cancel,
                      java.util.function.BooleanSupplier done, Function<String, String> names) {
        numbers = new LinkedHashMap<>();
        number(list(flow, "steps"), "");
        total = numbers.size();
        win = new JWindow();
        win.setAlwaysOnTop(true);
        win.setFocusableWindowState(false);   // the agent's own windows keep the keyboard
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(new Color(47, 51, 54));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(10, 123, 187), 2), BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        JLabel title = new JLabel(agent);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        for (JLabel l : List.of(title, status, line, detail)) l.setForeground(Color.WHITE);
        detail.setForeground(new Color(190, 198, 204));
        JPanel text = new JPanel(new GridLayout(0, 1, 0, 2));
        text.setOpaque(false);
        text.add(title); text.add(status); text.add(line); text.add(detail);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        pauseBtn.addActionListener(e -> { pause.set(!pause.get()); pauseBtn.setText(pause.get() ? "Resume" : "Pause"); });
        stopBtn.addActionListener(e -> { cancel.set(true); pause.set(false); stopBtn.setEnabled(false); status.setText("Stopping…"); });
        buttons.add(pauseBtn); buttons.add(stopBtn);
        p.add(text, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        win.setContentPane(p);
        win.setSize(380, 130);
        Rectangle wa = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        win.setLocation(wa.x + wa.width - 390, wa.y + wa.height - 140);
        win.setVisible(true);
        timer = new javax.swing.Timer(300, e -> update(sink, pause, done, names));
        timer.start();
    }

    private void update(EventSink.ListSink sink, AtomicBoolean pause, java.util.function.BooleanSupplier done, Function<String, String> names) {
        List<RunEvent> evs = sink.events();
        int finished = 0;
        String failed = null;
        for (RunEvent e : evs) {
            if (e.type().equals("step.finished")) finished++;
            if (e.type().equals("step.failed")) failed = e.data().get("code") + ": " + e.data().get("message");
        }
        for (int i = Math.max(0, seen); i < evs.size(); i++) {
            RunEvent e = evs.get(i);
            if (e.type().equals("step.started") && e.stepId() != null) {
                String num = numbers.getOrDefault(e.stepId(), "?");
                Object action = e.data().get("action");
                line.setText("Line " + num + ": " + (action == null ? "" : names.apply(String.valueOf(action))));
            }
        }
        seen = evs.size();
        long secs = (System.currentTimeMillis() - started) / 1000;
        String time = secs >= 60 ? secs / 60 + " min " + secs % 60 + " s" : secs + " s";
        detail.setText(Math.min(finished, total) + " of " + total + " lines · " + time);
        if (done.getAsBoolean()) {
            status.setText(failed != null ? "Failed" : "Finished");
            if (failed != null) detail.setText(failed.length() > 70 ? failed.substring(0, 70) + "…" : failed);
            pauseBtn.setEnabled(false); stopBtn.setEnabled(false);
            timer.stop();
            javax.swing.Timer close = new javax.swing.Timer(failed != null ? 8000 : 3000, ev -> win.dispose());
            close.setRepeats(false);
            close.start();
        } else status.setText(pause.get() ? "Paused" : "Running");
    }

    /** Line numbers as on the canvas: every step counted from the top (1, 2, 3 ...), nested steps included. */
    @SuppressWarnings("unchecked")
    private void number(List<Object> steps, String unused) {
        for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk(steps)) numbers.put(String.valueOf(s.get("id")), String.valueOf(numbers.size() + 1));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> m, String k) { return m != null && m.get(k) instanceof List<?> l ? (List<Object>) l : List.of(); }
}
