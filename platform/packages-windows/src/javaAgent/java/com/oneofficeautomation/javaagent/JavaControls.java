package com.oneofficeautomation.javaagent;

import javax.accessibility.*;
import javax.swing.*;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * G4 (AA parity, 22 Nov 2026): the control actions of the desktop toolkit for Java (Swing and AWT) applications:
 * states, selections and counts, tables and their cells, trees, menus by path, sliders, scrolling, mouse clicks, and
 * the control at a point on the screen (for Pick). Runs inside the application (Java 8 or later); Swing models are
 * changed on the event thread. Positions count from 1, as on screen.
 */
final class JavaControls {
    private JavaControls() {}

    /** Runs an action on the event thread; null when the action is not one of these. */
    static String act(final Accessible a, final AccessibleContext ac, final String action, final String value, final String row, final String col, final String kind) throws Exception {
        final String[] out = {null};
        final Exception[] err = {null};
        Runnable r = new Runnable() {
            public void run() {
                try { out[0] = onEdt(a, ac, action, value, num(row), num(col), kind == null || kind.isEmpty() ? "left" : kind); }
                catch (Exception e) { err[0] = e; }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
        if (err[0] != null) throw err[0];
        return out[0];
    }

    static int num(String s) { return s == null || s.trim().isEmpty() ? 0 : Integer.parseInt(s.trim()); }

    static String onEdt(Accessible a, AccessibleContext ac, String action, String value, int row, int col, String kind) throws Exception {
        Component c = a instanceof Component ? (Component) a : null;
        if (action.equals("get_status")) {
            if (c instanceof AbstractButton) return OoaJavaAgent.q(((AbstractButton) c).isSelected() ? (c instanceof JRadioButton ? "selected" : "checked") : (c instanceof JRadioButton ? "deselected" : "unchecked"));
            if (c instanceof Checkbox) return OoaJavaAgent.q(((Checkbox) c).getState() ? "checked" : "unchecked");
            AccessibleStateSet st = ac.getAccessibleStateSet();
            return OoaJavaAgent.q(st != null && st.contains(AccessibleState.CHECKED) ? "checked" : "unchecked");
        }
        if (action.equals("check") || action.equals("uncheck") || action.equals("select")) {
            boolean want = !action.equals("uncheck");
            if (c instanceof AbstractButton) { if (((AbstractButton) c).isSelected() != want) ((AbstractButton) c).doClick(); return "true"; }
            if (c instanceof Checkbox) { ((Checkbox) c).setState(want); return "true"; }
            throw new IllegalArgumentException("The control cannot be " + (want ? "checked" : "unchecked"));
        }
        if (action.equals("toggle")) {
            if (c instanceof AbstractButton) { ((AbstractButton) c).doClick(); return String.valueOf(((AbstractButton) c).isSelected()); }
            if (c instanceof Checkbox) { ((Checkbox) c).setState(!((Checkbox) c).getState()); return String.valueOf(((Checkbox) c).getState()); }
            throw new IllegalArgumentException("The control cannot be toggled");
        }
        if (action.equals("click")) { click(component(c), null, kind); return "true"; }
        if (action.equals("select_item")) {
            if (c instanceof JComboBox) { JComboBox<?> b = (JComboBox<?>) c; b.setSelectedIndex(index(items(b.getModel()), value, row)); return "true"; }
            if (c instanceof JList) { JList<?> l = (JList<?>) c; int i = index(items(l.getModel()), value, row); l.setSelectedIndex(i); l.ensureIndexIsVisible(i); return "true"; }
            if (c instanceof JTabbedPane) { JTabbedPane t = (JTabbedPane) c; List<String> titles = new ArrayList<String>(); for (int i = 0; i < t.getTabCount(); i++) titles.add(t.getTitleAt(i)); t.setSelectedIndex(index(titles, value, row)); return "true"; }
            if (c instanceof Choice) { Choice ch = (Choice) c; List<String> it = new ArrayList<String>(); for (int i = 0; i < ch.getItemCount(); i++) it.add(ch.getItem(i)); ch.select(index(it, value, row)); return "true"; }
            if (c instanceof JTree) { JTree t = (JTree) c; TreePath p = treePath(t, value); t.setSelectionPath(p); t.scrollPathToVisible(p); return "true"; }
            throw new IllegalArgumentException("The control has no items to select");
        }
        if (action.equals("get_selected")) {
            if (c instanceof JComboBox) return OoaJavaAgent.q(String.valueOf(((JComboBox<?>) c).getSelectedItem()));
            if (c instanceof JList) { StringBuilder sb = new StringBuilder(); for (Object o : ((JList<?>) c).getSelectedValuesList()) { if (sb.length() > 0) sb.append(", "); sb.append(o); } return OoaJavaAgent.q(sb.toString()); }
            if (c instanceof JTabbedPane) { JTabbedPane t = (JTabbedPane) c; return OoaJavaAgent.q(t.getSelectedIndex() < 0 ? "" : t.getTitleAt(t.getSelectedIndex())); }
            if (c instanceof JTree) { TreePath p = ((JTree) c).getSelectionPath(); return OoaJavaAgent.q(p == null ? "" : String.valueOf(p.getLastPathComponent())); }
            if (c instanceof Choice) return OoaJavaAgent.q(((Choice) c).getSelectedItem());
            throw new IllegalArgumentException("The control has no selection");
        }
        if (action.equals("get_selected_index")) {
            if (c instanceof JComboBox) return String.valueOf(((JComboBox<?>) c).getSelectedIndex() + 1);
            if (c instanceof JList) return String.valueOf(((JList<?>) c).getSelectedIndex() + 1);
            if (c instanceof JTabbedPane) return String.valueOf(((JTabbedPane) c).getSelectedIndex() + 1);
            if (c instanceof Choice) return String.valueOf(((Choice) c).getSelectedIndex() + 1);
            if (c instanceof JTable) return String.valueOf(((JTable) c).getSelectedRow() + 1);
            throw new IllegalArgumentException("The control has no selection");
        }
        if (action.equals("get_total_items") || action.equals("get_list_items")) {
            List<String> it;
            if (c instanceof JComboBox) it = items(((JComboBox<?>) c).getModel());
            else if (c instanceof JList) it = items(((JList<?>) c).getModel());
            else if (c instanceof JTabbedPane) { it = new ArrayList<String>(); JTabbedPane t = (JTabbedPane) c; for (int i = 0; i < t.getTabCount(); i++) it.add(t.getTitleAt(i)); }
            else if (c instanceof Choice) { it = new ArrayList<String>(); Choice ch = (Choice) c; for (int i = 0; i < ch.getItemCount(); i++) it.add(ch.getItem(i)); }
            else if (c instanceof JTree) { it = new ArrayList<String>(); JTree t = (JTree) c; for (int i = 0; i < t.getRowCount(); i++) it.add(String.valueOf(t.getPathForRow(i).getLastPathComponent())); }
            else throw new IllegalArgumentException("The control has no items");
            if (action.equals("get_total_items")) return String.valueOf(it.size());
            return list(it);
        }
        if (c instanceof JTable) {
            JTable t = (JTable) c;
            if (action.equals("get_table")) {
                StringBuilder sb = new StringBuilder("{\"columns\":[");
                for (int k = 0; k < t.getColumnCount(); k++) { if (k > 0) sb.append(','); sb.append(OoaJavaAgent.q(t.getColumnName(k))); }
                sb.append("],\"rows\":[");
                for (int r = 0; r < t.getRowCount(); r++) {
                    if (r > 0) sb.append(',');
                    sb.append('[');
                    for (int k = 0; k < t.getColumnCount(); k++) { if (k > 0) sb.append(','); Object v = t.getValueAt(r, k); sb.append(OoaJavaAgent.q(v == null ? "" : String.valueOf(v))); }
                    sb.append(']');
                }
                return sb.append("]}").toString();
            }
            if (action.equals("get_total_rows")) return String.valueOf(t.getRowCount());
            if (action.equals("get_total_columns")) return String.valueOf(t.getColumnCount());
            if (action.equals("get_cell_index_by_text")) {
                for (int pass = 0; pass < 2; pass++)
                    for (int r = 0; r < t.getRowCount(); r++)
                        for (int k = 0; k < t.getColumnCount(); k++) {
                            Object v = t.getValueAt(r, k);
                            String s = v == null ? "" : String.valueOf(v).trim();
                            if (pass == 0 ? s.equals(value.trim()) : s.toLowerCase().contains(value.trim().toLowerCase())) return "{\"row\":" + (r + 1) + ",\"column\":" + (k + 1) + "}";
                        }
                return "{\"row\":0,\"column\":0}";
            }
            if (action.equals("get_cell_text") || action.equals("set_cell_text") || action.equals("click_cell")) {
                if (row < 1 || col < 1 || row > t.getRowCount() || col > t.getColumnCount())
                    throw new IllegalArgumentException("The table has " + t.getRowCount() + " row(s) and " + t.getColumnCount() + " column(s); there is no cell at row " + row + ", column " + col);
                if (action.equals("get_cell_text")) { Object v = t.getValueAt(row - 1, col - 1); return OoaJavaAgent.q(v == null ? "" : String.valueOf(v)); }
                if (action.equals("set_cell_text")) { t.setValueAt(value, row - 1, col - 1); return "true"; }
                Rectangle cell = t.getCellRect(row - 1, col - 1, true);
                t.scrollRectToVisible(cell);
                click(t, cell, kind);
                return "true";
            }
        }
        if (action.equals("expand") || action.equals("collapse")) {
            boolean open = action.equals("expand");
            if (c instanceof JComboBox) { if (open) ((JComboBox<?>) c).showPopup(); else ((JComboBox<?>) c).hidePopup(); return "true"; }
            if (c instanceof JTree) {
                JTree t = (JTree) c;
                TreePath p = value == null || value.trim().isEmpty() ? t.getSelectionPath() : treePath(t, value);
                if (p == null) throw new IllegalArgumentException("Give the tree node's path, e.g. Root > Reports");
                if (open) t.expandPath(p); else t.collapsePath(p);
                return "true";
            }
            AccessibleAction aa = ac.getAccessibleAction();
            if (aa != null && aa.getAccessibleActionCount() > 0) { aa.doAccessibleAction(0); return "true"; }
            throw new IllegalArgumentException("The control cannot be expanded or collapsed");
        }
        if (action.equals("select_path")) {
            String[] parts = value.split(">");
            if (c instanceof JTree) { TreePath p = treePath((JTree) c, value); ((JTree) c).setSelectionPath(p); return "true"; }
            MenuElement scope = c instanceof JMenuBar ? (JMenuBar) c : c instanceof JMenu ? (JMenu) c : c instanceof JPopupMenu ? (JPopupMenu) c : null;
            if (scope == null) {
                Window w = c == null ? null : SwingUtilities.getWindowAncestor(c);
                if (w instanceof JFrame) scope = ((JFrame) w).getJMenuBar();
            }
            if (scope == null) throw new IllegalArgumentException("Pick a menu bar or menu, or a control of a window that has one");
            int first = scope instanceof JMenu && parts.length > 1 && parts[0].trim().equalsIgnoreCase(((JMenu) scope).getText()) ? 1 : 0;   // the path may start with the menu picked
            for (int i = first; i < parts.length; i++) {
                String want = parts[i].trim();
                JMenuItem hit = null;
                for (MenuElement m : (scope instanceof JMenu ? ((JMenu) scope).getPopupMenu() : scope).getSubElements())
                    if (m instanceof JMenuItem && want.equalsIgnoreCase(((JMenuItem) m).getText())) { hit = (JMenuItem) m; break; }
                if (hit == null) throw new IllegalArgumentException("No menu item named '" + want + "'");
                if (i == parts.length - 1 || !(hit instanceof JMenu)) { hit.doClick(); return "true"; }
                scope = hit;
            }
            return "true";
        }
        if (action.equals("set_position")) {
            int v = (int) Math.round(Double.parseDouble(value.trim()));
            if (c instanceof JSlider) { ((JSlider) c).setValue(v); return String.valueOf(((JSlider) c).getValue()); }
            if (c instanceof JScrollBar) { ((JScrollBar) c).setValue(v); return String.valueOf(((JScrollBar) c).getValue()); }
            if (c instanceof JProgressBar) { ((JProgressBar) c).setValue(v); return String.valueOf(((JProgressBar) c).getValue()); }
            if (c instanceof JSpinner) { ((JSpinner) c).setValue(v); return String.valueOf(((JSpinner) c).getValue()); }
            if (c instanceof Scrollbar) { ((Scrollbar) c).setValue(v); return String.valueOf(((Scrollbar) c).getValue()); }
            throw new IllegalArgumentException("The control has no position to set");
        }
        if (action.equals("scroll")) {
            JScrollPane sp = c instanceof JScrollPane ? (JScrollPane) c : c == null ? null : (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, c);
            if (sp == null) throw new IllegalArgumentException("The control does not scroll");
            String dir = value == null || value.isEmpty() ? "down" : value;
            JScrollBar bar = dir.equals("left") || dir.equals("right") ? sp.getHorizontalScrollBar() : sp.getVerticalScrollBar();
            int sign = dir.equals("up") || dir.equals("left") ? -1 : 1;
            int step = "page".equals(kind) ? bar.getBlockIncrement(sign) : bar.getUnitIncrement(sign);
            bar.setValue(bar.getValue() + sign * Math.max(1, step) * Math.max(1, row));
            return "true";
        }
        if (action.equals("get_children_values")) {
            StringBuilder sb = new StringBuilder("[");
            int n = ac.getAccessibleChildrenCount();
            for (int i = 0; i < n; i++) {
                Accessible ch = ac.getAccessibleChild(i);
                AccessibleContext cc = ch == null ? null : ch.getAccessibleContext();
                if (cc == null) continue;
                if (sb.length() > 1) sb.append(',');
                String v = "";
                try { v = OoaJavaAgent.text(ch, cc); } catch (RuntimeException ignored) { }
                if (ch instanceof AbstractButton && ch instanceof JToggleButton) v = String.valueOf(((AbstractButton) ch).isSelected());
                sb.append("{\"name\":").append(OoaJavaAgent.q(cc.getAccessibleName() == null ? "" : cc.getAccessibleName())).append(",\"value\":").append(OoaJavaAgent.q(v)).append('}');
            }
            return sb.append(']').toString();
        }
        return null;
    }

    static Component component(Component c) {
        if (c == null) throw new IllegalArgumentException("The control has no place on the screen");
        return c;
    }

    /** A real mouse click (left, right or double) at the middle of the control, or of a part of it. */
    static void click(Component c, Rectangle part, String kind) throws Exception {
        Point at = c.getLocationOnScreen();
        Rectangle r = part != null ? part : new Rectangle(0, 0, c.getWidth(), c.getHeight());
        Robot robot = new Robot();
        robot.mouseMove(at.x + r.x + r.width / 2, at.y + r.y + r.height / 2);
        int button = "right".equals(kind) ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        int times = "double".equals(kind) ? 2 : 1;
        for (int i = 0; i < times; i++) { robot.mousePress(button); robot.mouseRelease(button); }
    }

    static List<String> items(ListModel<?> m) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < m.getSize(); i++) out.add(String.valueOf(m.getElementAt(i)));
        return out;
    }

    /** The item named by value (ignoring case), else the position given (1 = first). */
    static int index(List<String> items, String value, int position) {
        if (value != null && !value.trim().isEmpty()) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).equalsIgnoreCase(value.trim())) return i;
            throw new IllegalArgumentException("No item named '" + value + "' among " + items);
        }
        if (position < 1 || position > items.size()) throw new IllegalArgumentException("There are " + items.size() + " item(s); there is no item " + position);
        return position - 1;
    }

    /** A tree node by its names from the root, e.g. Root > Reports (the root may be left out). */
    static TreePath treePath(JTree t, String path) {
        TreeModel m = t.getModel();
        Object node = m.getRoot();
        List<Object> nodes = new ArrayList<Object>();
        nodes.add(node);
        String[] parts = path.split(">");
        int start = parts.length > 0 && parts[0].trim().equalsIgnoreCase(String.valueOf(node)) ? 1 : 0;
        for (int i = start; i < parts.length; i++) {
            Object next = null;
            for (int k = 0; k < m.getChildCount(node); k++) if (String.valueOf(m.getChild(node, k)).equalsIgnoreCase(parts[i].trim())) { next = m.getChild(node, k); break; }
            if (next == null) throw new IllegalArgumentException("No tree node named '" + parts[i].trim() + "'");
            nodes.add(next);
            node = next;
        }
        return new TreePath(nodes.toArray());
    }

    static String list(List<String> it) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : it) { if (sb.length() > 1) sb.append(','); sb.append(OoaJavaAgent.q(s)); }
        return sb.append(']').toString();
    }

    /** The control under a screen point in one of the windows, with its path, for Pick (null when none). */
    static String at(List<Window> ws, int x, int y) {
        for (int w = 0; w < ws.size(); w++) {
            Window win = ws.get(w);
            if (!win.isShowing()) continue;
            Point o = win.getLocationOnScreen();
            if (!new Rectangle(o.x, o.y, win.getWidth(), win.getHeight()).contains(x, y)) continue;
            Component deepest = SwingUtilities.getDeepestComponentAt(win, x - o.x, y - o.y);
            if (deepest == null) continue;
            String path = find(win.getAccessibleContext(), String.valueOf(w), deepest, 0);
            for (Component up = deepest; path == null && up != null && up != win; up = up.getParent()) path = find(win.getAccessibleContext(), String.valueOf(w), up, 0);
            if (path == null) continue;
            Accessible a = OoaJavaAgent.byPath(ws, path);
            String props = OoaJavaAgent.props(a, a.getAccessibleContext(), path);
            return props.substring(0, props.length() - 1) + ",\"window\":" + OoaJavaAgent.q(OoaJavaAgent.title(win)) + "}";
        }
        return null;
    }

    static String find(AccessibleContext ac, String path, Component target, int depth) {
        if (ac == null || depth > 40) return null;
        int n = ac.getAccessibleChildrenCount();
        for (int i = 0; i < n; i++) {
            Accessible ch = ac.getAccessibleChild(i);
            if (ch == target) return path + "." + i;
            String p = ch == null ? null : find(ch.getAccessibleContext(), path + "." + i, target, depth + 1);
            if (p != null) return p;
        }
        return null;
    }
}
