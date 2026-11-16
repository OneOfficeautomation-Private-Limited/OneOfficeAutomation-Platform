"""A small Java Swing desk app (the HR team's legacy 'OOA QA Desk') that RW02-B6 drives with the Java applications commands.
Built from source with the JDK's javac and jar into <data>/RW02/qa-desk.jar by make_data.py."""
import os
import shutil
import subprocess
import tempfile

SOURCE = r'''import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** OOA QA Desk: records a readiness confirmation per employee (synthetic). Arguments: [output file]. */
public class QaDesk {
    public static void main(String[] args) {
        final String out = args.length > 0 ? args[0] : null;
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("OOA QA Desk");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            JPanel p = new JPanel(new GridLayout(4, 2, 6, 6));
            JTextField who = new JTextField(20);
            who.getAccessibleContext().setAccessibleName("Employee");
            JTextField state = new JTextField(20);
            state.getAccessibleContext().setAccessibleName("Readiness");
            JLabel status = new JLabel("Ready");
            JButton save = new JButton("Confirm");
            save.getAccessibleContext().setAccessibleName("Confirm");
            save.addActionListener(e -> {
                String line = who.getText().trim() + ";" + state.getText().trim();
                if (who.getText().trim().isEmpty()) { status.setText("Error: employee missing"); return; }
                if (out != null) {
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8)) { w.write(line + System.lineSeparator()); }
                    catch (IOException x) { status.setText("Error: " + x.getMessage()); return; }
                }
                status.setText("Confirmed " + who.getText().trim());
                who.setText(""); state.setText("");
            });
            p.add(new JLabel("Employee")); p.add(who);
            p.add(new JLabel("Readiness")); p.add(state);
            p.add(new JLabel("")); p.add(save);
            p.add(new JLabel("Status")); p.add(status);
            f.getContentPane().add(p);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
'''


def build():
    """Returns the jar's bytes, or None when no JDK (javac and jar) is found."""
    home = os.environ.get("JAVA_HOME", "")
    javac = shutil.which("javac") or (os.path.join(home, "bin", "javac.exe") if home else None)
    jar = shutil.which("jar") or (os.path.join(home, "bin", "jar.exe") if home else None)
    if not javac or not jar or not os.path.exists(javac):
        print("qa-desk.jar not built: no JDK found (javac)")
        return None
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, "QaDesk.java"), "w", encoding="utf-8").write(SOURCE)
        subprocess.run([javac, "--release", "17", "-d", d, os.path.join(d, "QaDesk.java")], check=True)
        subprocess.run([jar, "--create", "--file", os.path.join(d, "qa-desk.jar"), "--main-class", "QaDesk", "-C", d, "."], check=True)
        return open(os.path.join(d, "qa-desk.jar"), "rb").read()
