package com.oneofficeautomation.packages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A VT100/ANSI character screen: feed it what the host sends and read back rows of text and the cursor.
 * Handles the control sequences full-screen programs and shells use (cursor moves, erase, insert/delete,
 * scroll regions, the alternate screen); colours and other character attributes are ignored.
 * Rows and columns are 0-based here.
 */
final class VtScreen {
    final int rows, cols;
    private char[][] cells, saved;
    private int row, col, top, bottom, savedRow, savedCol;
    private boolean wrapNext;
    /** 0 text, 1 after ESC, 2 inside CSI, 3 inside a string (OSC, DCS...), 4 one more character to skip, 5 ESC inside a string. */
    private int state;
    private final StringBuilder params = new StringBuilder();
    private final StringBuilder replies = new StringBuilder();

    VtScreen(int rows, int cols) {
        this.rows = rows; this.cols = cols;
        cells = blank();
        bottom = rows - 1;
    }

    private char[][] blank() {
        char[][] c = new char[rows][cols];
        for (char[] r : c) Arrays.fill(r, ' ');
        return c;
    }

    synchronized void feed(CharSequence s) { for (int i = 0; i < s.length(); i++) put(s.charAt(i)); }

    /** Answers the host asked for (cursor position, device attributes), to be sent back; empty when none. */
    synchronized String takeReplies() { String r = replies.toString(); replies.setLength(0); return r; }

    synchronized List<String> lines() {
        List<String> out = new ArrayList<>(rows);
        for (char[] r : cells) out.add(new String(r));
        return out;
    }

    synchronized int[] cursor() { return new int[]{row, Math.min(col, cols - 1)}; }

    /** Up to len characters from a position, continuing onto the next rows. */
    synchronized String text(int r, int c, int len) {
        StringBuilder b = new StringBuilder();
        for (int p = r * cols + c; p < rows * cols && b.length() < len; p++) b.append(cells[p / cols][p % cols]);
        return b.toString();
    }

    private void put(char c) {
        switch (state) {
            case 0 -> ground(c);
            case 1 -> escape(c);
            case 2 -> {
                if (c == 27) { state = 1; return; }
                if (c < 0x20) { ground(c); return; }
                if (c <= 0x3f) { params.append(c); return; }
                state = 0;
                csi(c);
            }
            case 3 -> { if (c == 7 || c == 0x9c) state = 0; else if (c == 27) state = 5; }
            case 4 -> state = 0;
            case 5 -> state = c == '\\' ? 0 : 3;
            default -> state = 0;
        }
    }

    private void ground(char c) {
        switch (c) {
            case 27 -> state = 1;
            case 0x9b -> { state = 2; params.setLength(0); }
            case '\r' -> { col = 0; wrapNext = false; }
            case '\n', 11, 12 -> { lineFeed(); wrapNext = false; }
            case '\b' -> { if (col > 0) col--; wrapNext = false; }
            case '\t' -> col = Math.min(cols - 1, (col / 8 + 1) * 8);
            default -> { if (c >= 32 && c != 127 && (c < 0x80 || c > 0x9f)) print(c); }
        }
    }

    private void print(char c) {
        if (wrapNext) { col = 0; lineFeed(); wrapNext = false; }
        cells[row][col] = c;
        if (col == cols - 1) wrapNext = true; else col++;
    }

    private void lineFeed() { if (row == bottom) scrollUp(top, bottom, 1); else if (row < rows - 1) row++; }

    private void reverseIndex() { if (row == top) scrollDown(top, bottom, 1); else if (row > 0) row--; }

    private void scrollUp(int from, int to, int n) {
        for (int i = 0; i < n; i++) {
            for (int r = from; r < to; r++) cells[r] = cells[r + 1];
            cells[to] = blankRow();
        }
    }

    private void scrollDown(int from, int to, int n) {
        for (int i = 0; i < n; i++) {
            for (int r = to; r > from; r--) cells[r] = cells[r - 1];
            cells[from] = blankRow();
        }
    }

    private char[] blankRow() { char[] r = new char[cols]; Arrays.fill(r, ' '); return r; }

    private void escape(char c) {
        state = 0;
        switch (c) {
            case '[' -> { state = 2; params.setLength(0); }
            case ']', 'P', '^', '_', 'X' -> state = 3;
            case '(', ')', '*', '+', '#', ' ', '%' -> state = 4;
            case '7' -> { savedRow = row; savedCol = col; }
            case '8' -> { row = savedRow; col = savedCol; wrapNext = false; }
            case 'D' -> lineFeed();
            case 'M' -> reverseIndex();
            case 'E' -> { col = 0; lineFeed(); }
            case 'c' -> { cells = blank(); row = col = top = 0; bottom = rows - 1; wrapNext = false; }
            default -> { }
        }
    }

    private int[] numbers() {
        String p = params.toString().replaceAll("^[?>=<!]+", "");
        if (p.isEmpty()) return new int[0];
        String[] parts = p.split("[;:]", -1);
        int[] n = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { n[i] = parts[i].isEmpty() ? 0 : Math.min(9999, Integer.parseInt(parts[i].replaceAll("\\D", ""))); }
            catch (NumberFormatException e) { n[i] = 0; }
        }
        return n;
    }

    private static int arg(int[] n, int i, int dflt) { return i < n.length && n[i] > 0 ? n[i] : dflt; }

    private void csi(char f) {
        boolean priv = params.length() > 0 && "?>=<".indexOf(params.charAt(0)) >= 0;
        int[] n = numbers();
        int a = arg(n, 0, 1);
        int mode = n.length > 0 ? n[0] : 0;
        wrapNext = false;
        switch (f) {
            case 'A' -> row = Math.max(row >= top ? top : 0, row - a);
            case 'B', 'e' -> row = Math.min(row <= bottom ? bottom : rows - 1, row + a);
            case 'C', 'a' -> col = Math.min(cols - 1, col + a);
            case 'D' -> col = Math.max(0, Math.min(col, cols - 1) - a);
            case 'E' -> { row = Math.min(rows - 1, row + a); col = 0; }
            case 'F' -> { row = Math.max(0, row - a); col = 0; }
            case 'G', '`' -> col = clamp(a - 1, cols);
            case 'd' -> row = clamp(a - 1, rows);
            case 'H', 'f' -> { row = clamp(arg(n, 0, 1) - 1, rows); col = clamp(arg(n, 1, 1) - 1, cols); }
            case 'J' -> {
                int at = row * cols + Math.min(col, cols - 1);
                if (mode == 0) erase(at, rows * cols);
                else if (mode == 1) erase(0, at + 1);
                else erase(0, rows * cols);
            }
            case 'K' -> {
                int c0 = Math.min(col, cols - 1);
                if (mode == 0) Arrays.fill(cells[row], c0, cols, ' ');
                else if (mode == 1) Arrays.fill(cells[row], 0, c0 + 1, ' ');
                else Arrays.fill(cells[row], ' ');
            }
            case 'L' -> { if (row >= top && row <= bottom) scrollDown(row, bottom, Math.min(a, bottom - row + 1)); }
            case 'M' -> { if (row >= top && row <= bottom) scrollUp(row, bottom, Math.min(a, bottom - row + 1)); }
            case 'P' -> {
                int c0 = Math.min(col, cols - 1), k = Math.min(a, cols - c0);
                System.arraycopy(cells[row], c0 + k, cells[row], c0, cols - c0 - k);
                Arrays.fill(cells[row], cols - k, cols, ' ');
            }
            case '@' -> {
                int c0 = Math.min(col, cols - 1), k = Math.min(a, cols - c0);
                System.arraycopy(cells[row], c0, cells[row], c0 + k, cols - c0 - k);
                Arrays.fill(cells[row], c0, c0 + k, ' ');
            }
            case 'X' -> { int c0 = Math.min(col, cols - 1); Arrays.fill(cells[row], c0, Math.min(cols, c0 + a), ' '); }
            case 'S' -> { if (!priv) scrollUp(top, bottom, Math.min(a, bottom - top + 1)); }
            case 'T' -> { if (!priv && n.length <= 1) scrollDown(top, bottom, Math.min(a, bottom - top + 1)); }
            case 'r' -> {
                if (priv) return;
                int t = arg(n, 0, 1) - 1, b = arg(n, 1, rows) - 1;
                if (t < b && b < rows) { top = t; bottom = b; } else { top = 0; bottom = rows - 1; }
                row = 0; col = 0;
            }
            case 's' -> { if (!priv) { savedRow = row; savedCol = col; } }
            case 'u' -> { if (!priv) { row = savedRow; col = savedCol; } }
            case 'n' -> {
                if (mode == 6) replies.append("\033[").append(row + 1).append(';').append(Math.min(col, cols - 1) + 1).append('R');
                else if (mode == 5) replies.append("\033[0n");
            }
            case 'c' -> { if (!priv && mode == 0) replies.append("\033[?1;2c"); }
            case 'h', 'l' -> {
                if (!priv) return;
                for (int m : n) if (m == 47 || m == 1047 || m == 1049) alternate(f == 'h', m == 1049);
            }
            default -> { }
        }
    }

    private void alternate(boolean on, boolean cursorToo) {
        if (on && saved == null) {
            saved = cells; cells = blank();
            if (cursorToo) { savedRow = row; savedCol = col; }
        } else if (!on && saved != null) {
            cells = saved; saved = null;
            if (cursorToo) { row = savedRow; col = savedCol; }
        }
    }

    private void erase(int from, int to) { for (int p = from; p < to; p++) cells[p / cols][p % cols] = ' '; }

    private static int clamp(int v, int size) { return Math.max(0, Math.min(size - 1, v)); }
}
