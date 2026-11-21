package com.oneofficeautomation.packages;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * G4 (AA parity, 22 Nov 2026): Show terminal opens a window that follows a terminal session's screen (3270, 5250 or
 * VT) as the agent works, refreshed a few times a second, for a person watching an attended run. It only shows the
 * screen; the agent keeps the keyboard. Hide terminal, the end of the session or closing the window stops it.
 */
final class TerminalViewer {
    private static final Map<TerminalPackage.Session, TerminalViewer> OPEN = new WeakHashMap<>();

    final JFrame frame;
    private final JTextArea area;
    private final Timer timer;

    private TerminalViewer(TerminalPackage.Session session, String title) {
        frame = new JFrame(title);
        area = new JTextArea(24, 80);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setBackground(new Color(0x10, 0x14, 0x18));
        area.setForeground(new Color(0x7d, 0xe0, 0x8a));
        area.setCaretColor(area.getForeground());
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        frame.add(new JScrollPane(area));
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        timer = new Timer(300, e -> refresh(session));
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { timer.stop(); synchronized (OPEN) { OPEN.remove(session); } }
        });
        refresh(session);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        timer.start();
    }

    private void refresh(TerminalPackage.Session session) {
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                List<String> lines = session.cmd("Ascii()");
                return String.join("\n", lines);
            }
            @Override protected void done() {
                try { String t = get(); if (!t.equals(area.getText())) area.setText(t); }
                catch (Exception ended) { area.append("\n[the session has ended]"); timer.stop(); }
            }
        }.execute();
    }

    static boolean show(TerminalPackage.Session session, String title) {
        if (GraphicsEnvironment.isHeadless()) return false;
        synchronized (OPEN) {
            TerminalViewer v = OPEN.get(session);
            if (v != null) { SwingUtilities.invokeLater(() -> { v.frame.toFront(); v.frame.setState(Frame.NORMAL); }); return true; }
        }
        try {
            SwingUtilities.invokeAndWait(() -> { TerminalViewer v = new TerminalViewer(session, title); synchronized (OPEN) { OPEN.put(session, v); } });
        } catch (Exception e) { return false; }
        return true;
    }

    static boolean hide(TerminalPackage.Session session) {
        TerminalViewer v;
        synchronized (OPEN) { v = OPEN.remove(session); }
        if (v == null) return false;
        SwingUtilities.invokeLater(v.frame::dispose);
        return true;
    }

    static boolean isShown(TerminalPackage.Session session) { synchronized (OPEN) { return OPEN.containsKey(session); } }
}
