package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 (AA parity, 22 Nov 2026): the desktop control actions against a real Windows Forms window that PowerShell shows
 * (a grid, check box, radio buttons, combo box, slider, tree, menu, tool bar and a long list): states, grid cells by
 * position and by text, selections and counts, a menu path, slider position, scrolling, a tool bar button, all text,
 * names and values of the children, the focused control and the text at a point. The window is closed afterwards.
 */
class DesktopControlsG4LiveTest {
    static final String TITLE = "OOA G4 controls " + UUID.randomUUID().toString().substring(0, 6);

    static final String FORM = """
            Add-Type -AssemblyName System.Windows.Forms
            $f = New-Object Windows.Forms.Form; $f.Text = '%s'; $f.Width = 700; $f.Height = 520; $f.TopMost = $true
            $menu = New-Object Windows.Forms.MenuStrip
            $file = New-Object Windows.Forms.ToolStripMenuItem('File'); $save = New-Object Windows.Forms.ToolStripMenuItem('Save')
            $save.add_Click({ $f.Tag = 'saved'; $status.Text = 'saved' }); [void]$file.DropDownItems.Add($save); [void]$menu.Items.Add($file)
            $bar = New-Object Windows.Forms.ToolStrip; $bar.Dock = 'Bottom'; $bar.AccessibleName = 'Tools'
            foreach ($n in 'New','Open','Print') { $b = New-Object Windows.Forms.ToolStripButton($n); $b.add_Click({ param($s) $status.Text = 'tool ' + $s.Text }); [void]$bar.Items.Add($b) }
            $grid = New-Object Windows.Forms.DataGridView; $grid.Left = 10; $grid.Top = 40; $grid.Width = 330; $grid.Height = 120; $grid.AccessibleName = 'Orders'
            [void]$grid.Columns.Add('Id','Id'); [void]$grid.Columns.Add('Note','Note'); $grid.AllowUserToAddRows = $false
            [void]$grid.Rows.Add('INV-7','old'); [void]$grid.Rows.Add('INV-8','')
            $cb = New-Object Windows.Forms.CheckBox; $cb.Text = 'Urgent'; $cb.Left = 360; $cb.Top = 40; $cb.Checked = $true
            $r1 = New-Object Windows.Forms.RadioButton; $r1.Text = 'Air'; $r1.Left = 360; $r1.Top = 70; $r1.Checked = $true
            $r2 = New-Object Windows.Forms.RadioButton; $r2.Text = 'Sea'; $r2.Left = 360; $r2.Top = 95
            $combo = New-Object Windows.Forms.ComboBox; $combo.Left = 360; $combo.Top = 125; $combo.DropDownStyle = 'DropDownList'; $combo.AccessibleName = 'Region'
            [void]$combo.Items.AddRange(@('EU','US','APAC')); $combo.SelectedIndex = 1
            $slider = New-Object Windows.Forms.TrackBar; $slider.Left = 360; $slider.Top = 160; $slider.Minimum = 0; $slider.Maximum = 10; $slider.AccessibleName = 'Volume'
            $tree = New-Object Windows.Forms.TreeView; $tree.Left = 10; $tree.Top = 170; $tree.Width = 160; $tree.Height = 120; $tree.AccessibleName = 'Folders'
            $root = $tree.Nodes.Add('Root'); [void]$root.Nodes.Add('Reports'); [void]$root.Nodes.Add('Archive')
            $list = New-Object Windows.Forms.ListBox; $list.Left = 190; $list.Top = 170; $list.Width = 150; $list.Height = 80; $list.AccessibleName = 'Items'
            1..60 | ForEach-Object { [void]$list.Items.Add('Item ' + $_) }
            $status = New-Object Windows.Forms.Label; $status.Left = 360; $status.Top = 220; $status.Width = 200; $status.Text = 'ready'; $status.Name = 'status'
            $f.Controls.AddRange(@($grid, $cb, $r1, $r2, $combo, $slider, $tree, $list, $status, $bar, $menu)); $f.MainMenuStrip = $menu
            [void]$f.ShowDialog()
            """;

    static Map<String, Object> obj(String... props) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "desktop");
        o.put("window", Map.of("title", TITLE, "title_match", "equals"));
        List<Object> rules = new ArrayList<>();
        for (int i = 0; i < props.length; i += 2) rules.add(DesktopObjectTest.prop(props[i], props[i + 1], "equals", true));
        o.put("properties", rules);
        o.put("index", 0);
        return o;
    }

    static Object run(DesktopUiPackage p, ActionContext c, Map<String, Object> o, String action, Object text, String item, Integer itemIndex, Integer row, Integer column, String find, String clickType) {
        return p.objectAction(c, o, action, clickType == null ? "left" : clickType, "auto", 0, 0, text, true, "auto", 0, null, item, itemIndex, 10, "error", false,
                row, column, find, "down", "page", 3, true).get("result");
    }

    @SuppressWarnings("unchecked")
    @Test void desktopControlsOfG4() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || GraphicsEnvironment.isHeadless()) {
            System.out.println("    (no Windows desktop session; skipped)");
            return;
        }
        Path script = Files.createTempFile("ooa-g4form", ".ps1");
        Files.writeString(script, FORM.formatted(TITLE), StandardCharsets.UTF_8);
        Map<String, Object> sessions = new HashMap<>();
        ActionContext c = new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-g4-live"), sessions, new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
        Process app = new ProcessBuilder(List.of("powershell", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-File", script.toString())).start();
        DesktopUiPackage p = new DesktopUiPackage();
        try {
            assertEquals(true, p.waitForObject(c, obj("ControlType", "CheckBox", "Name", "Urgent"), "appear", 60, true).get("found"));

            // check box and radio states; a radio selected
            assertEquals("checked", run(p, c, obj("ControlType", "CheckBox", "Name", "Urgent"), "get_status", null, null, null, null, null, null, null));
            assertEquals("deselected", run(p, c, obj("ControlType", "RadioButton", "Name", "Sea"), "get_status", null, null, null, null, null, null, null));
            run(p, c, obj("ControlType", "RadioButton", "Name", "Sea"), "select", null, null, null, null, null, null, null);
            assertEquals("selected", run(p, c, obj("ControlType", "RadioButton", "Name", "Sea"), "get_status", null, null, null, null, null, null, null));

            // the grid: size, a cell by position, a cell found by its text, set and read back
            Map<String, Object> grid = obj("ControlType", "Table", "Name", "Orders");
            assertEquals(new BigDecimal(2), run(p, c, grid, "get_total_rows", null, null, null, null, null, null, null));
            assertEquals(new BigDecimal(2), run(p, c, grid, "get_total_columns", null, null, null, null, null, null, null));
            assertEquals("INV-8", run(p, c, grid, "get_cell_text", null, null, null, 2, 1, null, null));
            Map<String, Object> at = (Map<String, Object>) run(p, c, grid, "get_cell_index_by_text", null, null, null, null, null, "INV-8", null);
            assertEquals(new BigDecimal(2), at.get("row"));
            run(p, c, grid, "set_cell_text", "paid", null, null, 2, 2, null, null);
            assertEquals("paid", run(p, c, grid, "get_cell_text", null, null, null, 2, 2, null, null));
            assertEquals("DESKTOP_UI.ITEM_NOT_FOUND", assertThrows(ActionException.class, () -> run(p, c, grid, "click_cell_by_text", null, null, null, null, null, "INV-99", null)).code());

            // the combo box: selected text, index from 1, count; select by index from 1
            Map<String, Object> combo = obj("ControlType", "ComboBox", "Name", "Region");
            assertEquals("US", run(p, c, combo, "get_selected", null, null, null, null, null, null, null));
            assertEquals(new BigDecimal(3), run(p, c, combo, "get_total_items", null, null, null, null, null, null, null));
            run(p, c, combo, "select_item", null, null, 3, null, null, null, null);
            assertEquals("APAC", run(p, c, combo, "get_selected", null, null, null, null, null, null, null), "3 is the third item counting from 1");

            // the slider's position; the long list scrolled a page at a time
            assertEquals(7.0, ((Number) run(p, c, obj("ControlType", "Slider", "Name", "Volume"), "set_position", "7", null, null, null, null, null, null)).doubleValue(), 0.01);
            assertEquals(true, run(p, c, obj("ControlType", "List", "Name", "Items"), "scroll", null, null, null, null, null, null, null));

            // the menu by its path, a tool bar button by its position
            run(p, c, obj("ControlType", "MenuBar"), "select_path", "File > Save", null, null, null, null, null, null);
            Map<String, Object> status = obj("AutomationId", "status");
            long end = System.currentTimeMillis() + 3000;
            while (!"saved".equals(run(p, c, status, "get_text", null, null, null, null, null, null, null)) && System.currentTimeMillis() < end) Thread.sleep(200);
            assertEquals("saved", run(p, c, status, "get_text", null, null, null, null, null, null, null), "File > Save ran");
            assertTrue(String.valueOf(run(p, c, obj("ControlType", "Window", "Name", TITLE), "get_all_text", null, null, null, null, null, null, null)).contains("saved"), "all the window's text");
            assertEquals("Open", run(p, c, obj("ControlType", "ToolBar", "Name", "Tools"), "click_toolbar_button", null, null, 2, null, null, null, null));

            // children's names and values, the focused control, the text at a point
            List<Object> kids = (List<Object>) run(p, c, obj("ControlType", "Window", "Name", TITLE), "get_children_values", null, null, null, null, null, null, null);
            assertTrue(kids.stream().anyMatch(k -> "Urgent".equals(((Map<String, Object>) k).get("name"))), String.valueOf(kids));
            assertNotNull(run(p, c, grid, "get_focused", null, null, null, null, null, null, null));
            Map<String, Object> xy = (Map<String, Object>) run(p, c, status, "get_coordinates", null, null, null, null, null, null, null);
            String atPoint = String.valueOf(p.textAtPoint(c, ((BigDecimal) xy.get("center_x")).intValue(), ((BigDecimal) xy.get("center_y")).intValue()).get("text"));
            assertTrue(atPoint.startsWith("tool") || atPoint.equals("saved"), atPoint);
        } finally {
            app.descendants().forEach(ProcessHandle::destroy);
            app.destroy();
            if (sessions.get(DesktopUiPackage.SESSION) instanceof AutoCloseable b) try { b.close(); } catch (Exception ignored) { }
            Files.deleteIfExists(script);
        }
    }
}
