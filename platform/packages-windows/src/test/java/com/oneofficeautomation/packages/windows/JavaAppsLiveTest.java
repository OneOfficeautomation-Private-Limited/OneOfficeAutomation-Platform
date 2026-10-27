package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X6: a Swing application in its own JVM, reached through the Java Attach API and its accessibility information: a
 * text field filled, a button pressed, the label it changes read back. The application is closed at the end.
 */
class JavaAppsLiveTest {

    static final String APP = """
        import javax.swing.*;
        import java.awt.FlowLayout;
        public class OoaSwingTestApp {
            public static void main(String[] a) throws Exception {
                SwingUtilities.invokeAndWait(() -> {
                    JFrame f = new JFrame("OOA Swing test");
                    f.setLayout(new FlowLayout());
                    JTextField customer = new JTextField(12);
                    customer.getAccessibleContext().setAccessibleName("Customer");
                    JLabel status = new JLabel("Ready");
                    JButton save = new JButton("Save");
                    save.addActionListener(e -> status.setText("Saved: " + customer.getText()));
                    f.add(customer); f.add(save); f.add(status);
                    f.setSize(360, 120); f.setLocation(40, 40);
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setVisible(true);
                });
            }
        }
        """;

    @SuppressWarnings("unchecked")
    @Test void swingApplicationThroughItsAccessibility() throws Exception {
        if (GraphicsEnvironment.isHeadless()) { System.out.println("    (no desktop; skipped)"); return; }
        Path dir = Files.createTempDirectory("ooa-swing");
        Files.writeString(dir.resolve("OoaSwingTestApp.java"), APP);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), dir.resolve("OoaSwingTestApp.java").toString()));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process app = new ProcessBuilder(java.toString(), "-cp", dir.toString(), "OoaSwingTestApp").redirectErrorStream(true).start();
        ActionContext c = new ActionContext("run", "s", "default", dir, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
        try {
            JavaAppsPackage p = new JavaAppsPackage();
            Map<String, Object> field = p.objectAction(c, "OOA Swing test", "Customer", "text", 0, null, "set_text", "Acme Ltd", "OoaSwingTestApp", 30);
            assertEquals("Customer", ((Map<String, Object>) field.get("object")).get("name"));
            p.objectAction(c, "OOA Swing test", "Save", "push button", 0, null, "do_action", null, "OoaSwingTestApp", 5);
            long end = System.currentTimeMillis() + 5000;
            Object label;
            do { label = p.objectAction(c, "OOA Swing test", "Saved*", "label", 0, null, "get_text", null, "OoaSwingTestApp", 2).get("result"); }
            while (!"Saved: Acme Ltd".equals(label) && System.currentTimeMillis() < end);
            assertEquals("Saved: Acme Ltd", label);

            List<Object> objects = (List<Object>) p.findObjects(c, "OOA Swing test", null, null, "OoaSwingTestApp", 100, 0).get("objects");
            assertTrue(objects.stream().anyMatch(o -> "push button".equals(((Map<String, Object>) o).get("role"))), objects.toString());
            List<Object> apps = (List<Object>) p.listApps(c, "OoaSwingTestApp").get("apps");
            assertEquals(List.of("OOA Swing test"), ((Map<String, Object>) apps.get(0)).get("windows"));
            assertEquals("JAVA_APPS.OBJECT_NOT_FOUND", assertThrows(ActionException.class,
                () -> p.objectAction(c, "OOA Swing test", "No such", "push button", 0, null, "do_action", null, "OoaSwingTestApp", 0)).code());
        } finally {
            app.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
