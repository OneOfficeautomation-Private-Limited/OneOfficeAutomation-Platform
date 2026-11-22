package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 (AA parity, 22 Nov 2026): the Java control actions against a real Swing application: radio and check box states,
 * a combo box by text and position, a table's cells (read, find, set), a tree path, a menu path, a slider, scrolling a
 * long list, names and values of the children, and the control at a point (what Pick captures).
 */
class JavaControlsG4LiveTest {
    static final String APP = """
        import javax.swing.*;
        import javax.swing.tree.DefaultMutableTreeNode;
        import java.awt.FlowLayout;
        public class OoaG4SwingApp {
            public static void main(String[] a) throws Exception {
                SwingUtilities.invokeAndWait(() -> {
                    JFrame f = new JFrame("OOA G4 Swing");
                    f.setLayout(new FlowLayout());
                    JMenuBar bar = new JMenuBar(); JMenu file = new JMenu("File"); JMenuItem save = new JMenuItem("Save");
                    JLabel status = new JLabel("ready"); status.getAccessibleContext().setAccessibleName("Status");
                    save.addActionListener(e -> status.setText("saved")); file.add(save); bar.add(file); f.setJMenuBar(bar);
                    JRadioButton air = new JRadioButton("Air", true), sea = new JRadioButton("Sea"); ButtonGroup g = new ButtonGroup(); g.add(air); g.add(sea);
                    JComboBox<String> region = new JComboBox<>(new String[]{"EU", "US", "APAC"}); region.getAccessibleContext().setAccessibleName("Region"); region.setSelectedIndex(1);
                    JTable orders = new JTable(new Object[][]{{"INV-7", "old"}, {"INV-8", ""}}, new Object[]{"Id", "Note"}); orders.getAccessibleContext().setAccessibleName("Orders");
                    DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root"); root.add(new DefaultMutableTreeNode("Reports")); root.add(new DefaultMutableTreeNode("Archive"));
                    JTree tree = new JTree(root); tree.getAccessibleContext().setAccessibleName("Folders");
                    JSlider volume = new JSlider(0, 10, 2); volume.getAccessibleContext().setAccessibleName("Volume");
                    DefaultListModel<String> items = new DefaultListModel<>(); for (int i = 1; i <= 60; i++) items.addElement("Item " + i);
                    JList<String> list = new JList<>(items); list.getAccessibleContext().setAccessibleName("Items");
                    JScrollPane sp = new JScrollPane(list); sp.setPreferredSize(new java.awt.Dimension(120, 80));
                    JCheckBox urgent = new JCheckBox("Urgent", true);
                    f.add(air); f.add(sea); f.add(urgent); f.add(region); f.add(new JScrollPane(orders)); f.add(tree); f.add(volume); f.add(sp); f.add(status);
                    f.setSize(900, 360); f.setLocation(40, 40);
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setVisible(true);
                });
            }
        }
        """;

    static final String W = "OOA G4 Swing", PROC = "OoaG4SwingApp";

    static Object act(JavaAppsPackage p, ActionContext c, String name, String role, String action, String value, Integer row, Integer col, String kind) {
        return p.objectAction(c, W, name, role, 0, null, action, value, PROC, 20, row, col, kind).get("result");
    }

    @SuppressWarnings("unchecked")
    @Test void swingControlsOfG4() throws Exception {
        if (GraphicsEnvironment.isHeadless()) { System.out.println("    (no desktop; skipped)"); return; }
        Path dir = Files.createTempDirectory("ooa-g4swing");
        Files.writeString(dir.resolve("OoaG4SwingApp.java"), APP);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), dir.resolve("OoaG4SwingApp.java").toString()));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process app = new ProcessBuilder(java.toString(), "-cp", dir.toString(), "OoaG4SwingApp").redirectErrorStream(true).start();
        ActionContext c = new ActionContext("run", "s", "default", dir, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
        JavaAppsPackage p = new JavaAppsPackage();
        try {
            assertEquals("deselected", act(p, c, "Sea", "radio button", "get_status", null, null, null, null));
            act(p, c, "Sea", "radio button", "select", null, null, null, null);
            assertEquals("selected", act(p, c, "Sea", "radio button", "get_status", null, null, null, null));
            assertEquals("checked", act(p, c, "Urgent", "check box", "get_status", null, null, null, null));
            act(p, c, "Urgent", "check box", "uncheck", null, null, null, null);
            assertEquals("unchecked", act(p, c, "Urgent", "check box", "get_status", null, null, null, null));

            assertEquals("US", act(p, c, "Region", "combo box", "get_selected", null, null, null, null));
            assertEquals(new BigDecimal(3), act(p, c, "Region", "combo box", "get_total_items", null, null, null, null));
            act(p, c, "Region", "combo box", "select_item", null, 3, null, null);
            assertEquals("APAC", act(p, c, "Region", "combo box", "get_selected", null, null, null, null));
            act(p, c, "Region", "combo box", "select_item", "eu", null, null, null);
            assertEquals(new BigDecimal(1), act(p, c, "Region", "combo box", "get_selected_index", null, null, null, null));

            Map<String, Object> table = (Map<String, Object>) act(p, c, "Orders", "table", "get_table", null, null, null, null);
            assertEquals(List.of("Id", "Note"), table.get("columns"));
            assertEquals("INV-8", act(p, c, "Orders", "table", "get_cell_text", null, 2, 1, null));
            act(p, c, "Orders", "table", "set_cell_text", "paid", 2, 2, null);
            assertEquals("paid", act(p, c, "Orders", "table", "get_cell_text", null, 2, 2, null));
            assertEquals(new BigDecimal(2), ((Map<String, Object>) act(p, c, "Orders", "table", "get_cell_index_by_text", "INV-8", null, null, null)).get("row"));

            act(p, c, "Folders", "tree", "select_path", "Root > Archive", null, null, null);
            assertEquals("Archive", act(p, c, "Folders", "tree", "get_selected", null, null, null, null));

            act(p, c, "File", "menu", "select_path", "File > Save", null, null, null);
            long end = System.currentTimeMillis() + 3000;
            while (!"saved".equals(act(p, c, "Status", "label", "get_text", null, null, null, null)) && System.currentTimeMillis() < end) Thread.sleep(150);
            assertEquals("saved", act(p, c, "Status", "label", "get_text", null, null, null, null));

            assertEquals(new BigDecimal(7), act(p, c, "Volume", "slider", "set_position", "7", null, null, null));
            assertEquals(true, act(p, c, "Items", "list", "scroll", "down", 2, null, "page"));
            assertEquals(new BigDecimal(60), act(p, c, "Items", "list", "get_total_items", null, null, null, null));

            // Pick: the control at a point is found with its window and path
            Map<String, Object> region = (Map<String, Object>) p.objectAction(c, W, "Region", "combo box", 0, null, "get_properties", null, PROC, 10, null, null, "left").get("object");
            String[] b = String.valueOf(region.get("bounds")).split(",");
            Map<String, Object> picked = JavaAppsPackage.objectAt(PROC, Integer.parseInt(b[0]) + 5, Integer.parseInt(b[1]) + 5);
            assertNotNull(picked);
            assertEquals(W, picked.get("window"));
            assertEquals("java", picked.get("technology"));
            assertTrue(String.valueOf(picked.get("path")).startsWith(String.valueOf(region.get("path"))), picked + " vs " + region.get("path"));
        } finally {
            app.descendants().forEach(ProcessHandle::destroy);
            app.destroy();
        }
    }
}
