package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an HTML fragment into Word paragraphs, runs and tables: headings, paragraphs, line breaks, bold/italic/underline/
 * strike, superscript/subscript, colours, sizes and fonts (style attributes and font tags), links, bulleted and numbered
 * lists (nested), tables, block quotes, preformatted text, horizontal rules and pictures (data: URIs or local files).
 * Unknown tags keep their text. A small tolerant parser is used, so fragments need not be well-formed XHTML.
 */
final class HtmlToDocx {
    private HtmlToDocx() {}

    // ------------------------------------------------------------------ parsing

    static final class Node {
        final String tag;  // null for text
        final Map<String, String> attrs = new LinkedHashMap<>();
        final List<Node> kids = new ArrayList<>();
        String text;
        Node parent;
        Node(String tag) { this.tag = tag; }
        String attr(String n) { return attrs.get(n); }
    }

    static final Set<String> VOID = Set.of("br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr");
    static final Set<String> SKIP = Set.of("script", "style", "head", "title", "noscript");
    static final Set<String> CLOSES_P = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "blockquote", "pre", "hr");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9:-]*)((?:\\s+[^\\s=>/]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?)*)\\s*(/?)>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([^\\s=>/]+)(?:\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");

    static Node parse(String html) {
        Node root = new Node("#root"), cur = root;
        String s = html.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?is)<!doctype[^>]*>", "");
        Matcher m = TAG.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) cur = text(cur, s.substring(pos, m.start()));
            pos = m.end();
            String name = m.group(2).toLowerCase(Locale.ROOT);
            if (m.group(1).equals("/")) {
                for (Node n = cur; n != null && n != root; n = n.parent) if (name.equals(n.tag)) { cur = n.parent; break; }
                continue;
            }
            if (SKIP.contains(name)) {  // drop the element and its content
                int end = s.toLowerCase(Locale.ROOT).indexOf("</" + name, pos);
                if (end < 0) break;
                int close = s.indexOf('>', end);
                pos = close < 0 ? s.length() : close + 1;
                m.region(pos, s.length());
                continue;
            }
            if (CLOSES_P.contains(name) || name.equals("li") || name.equals("tr") || name.equals("td") || name.equals("th")) cur = implicitClose(cur, name);
            Node n = new Node(name);
            Matcher a = ATTR.matcher(m.group(3));
            while (a.find()) {
                String v = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5) != null ? a.group(5) : "";
                n.attrs.put(a.group(1).toLowerCase(Locale.ROOT), entities(v));
            }
            n.parent = cur;
            cur.kids.add(n);
            if (!VOID.contains(name) && m.group(4).isEmpty()) cur = n;
        }
        if (pos < s.length()) text(cur, s.substring(pos));
        return root;
    }

    /** HTML closes an open p before a block, an li before the next li, and a cell or row before the next one. */
    private static Node implicitClose(Node cur, String name) {
        for (Node n = cur; n != null && n.tag != null && !n.tag.equals("#root"); n = n.parent) {
            if (n.tag.equals("p") && CLOSES_P.contains(name)) return n.parent;
            if (n.tag.equals("li") && name.equals("li")) return n.parent;
            if ((n.tag.equals("td") || n.tag.equals("th")) && (name.equals("td") || name.equals("th") || name.equals("tr"))) return name.equals("tr") ? closeTo(n, "tr") : n.parent;
            if (n.tag.equals("tr") && name.equals("tr")) return n.parent;
            if (Set.of("ul", "ol", "table", "td", "th", "div", "blockquote").contains(n.tag)) break;
        }
        return cur;
    }

    private static Node closeTo(Node n, String tag) {
        for (Node x = n; x != null; x = x.parent) if (tag.equals(x.tag)) return x.parent;
        return n.parent;
    }

    private static Node text(Node cur, String raw) {
        Node t = new Node(null);
        t.text = entities(raw);
        t.parent = cur;
        cur.kids.add(t);
        return cur;
    }

    static final Map<String, String> NAMED = Map.ofEntries(Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", "\u00A0"), Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("euro", "€"), Map.entry("pound", "£"),
            Map.entry("mdash", "—"), Map.entry("ndash", "–"), Map.entry("hellip", "…"), Map.entry("bull", "•"), Map.entry("laquo", "«"), Map.entry("raquo", "»"),
            Map.entry("trade", "™"), Map.entry("deg", "°"), Map.entry("times", "×"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"), Map.entry("rdquo", "”"), Map.entry("ldquo", "“"));

    static String entities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else r = NAMED.getOrDefault(e.toLowerCase(Locale.ROOT), m.group());
            } catch (RuntimeException ex) { r = m.group(); }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ------------------------------------------------------------------ writing

    /** Where new blocks go: a new paragraph or table after the previous block. */
    interface Place {
        XWPFParagraph paragraph();
        XWPFTable table(int rows, int cols);
    }

    /** Character formatting while walking inline elements. */
    record Style(boolean bold, boolean italic, boolean underline, boolean strike, String vertical, String color, Double size, String font, boolean code, String link) {
        static final Style PLAIN = new Style(false, false, false, false, null, null, null, null, false, null);
        Style with(String k, Object v) {
            return new Style(k.equals("b") ? (Boolean) v : bold, k.equals("i") ? (Boolean) v : italic, k.equals("u") ? (Boolean) v : underline,
                    k.equals("s") ? (Boolean) v : strike, k.equals("va") ? (String) v : vertical, k.equals("color") ? (String) v : color,
                    k.equals("size") ? (Double) v : size, k.equals("font") ? (String) v : font, k.equals("code") ? (Boolean) v : code, k.equals("link") ? (String) v : link);
        }
    }

    static final class Writer {
        final XWPFDocument doc;
        final Function<String, Path> files;
        Place place;
        XWPFParagraph para;  // the paragraph inline content goes into; null = start a new one
        boolean pre;
        final Deque<String> lists = new ArrayDeque<>();
        final Map<String, BigInteger> numIds = new HashMap<>();
        int paragraphs, tables;

        Writer(XWPFDocument doc, Place place, Function<String, Path> files) { this.doc = doc; this.place = place; this.files = files; }

        XWPFParagraph current() {
            if (para == null) { para = place.paragraph(); paragraphs++; }
            return para;
        }

        void endBlock() {
            if (para != null && !pre && !para.getRuns().isEmpty()) {  // drop the space left by line breaks before the closing tag
                XWPFRun last = para.getRuns().get(para.getRuns().size() - 1);
                String t = last.getText(0);
                if (t != null && t.endsWith(" ") && !(last instanceof XWPFHyperlinkRun)) last.setText(t.stripTrailing(), 0);
            }
            para = null;
        }

        void block(Node n, Style st) {
            for (Node k : n.kids) node(k, st);
        }

        void node(Node n, Style st) {
            if (n.tag == null) { text(n.text, st); return; }
            switch (n.tag) {
                case "br" -> current().createRun().addBreak();
                case "p", "div", "section", "article", "header", "footer", "main", "center", "address", "figure", "figcaption" -> {
                    endBlock();
                    XWPFParagraph p = current();
                    align(p, n);
                    if ("center".equals(n.tag)) p.setAlignment(ParagraphAlignment.CENTER);
                    block(n, inline(n, st));
                    endBlock();
                }
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    endBlock();
                    XWPFParagraph p = current();
                    p.setStyle(WordPackage.paragraphStyle(doc, "Heading" + n.tag.charAt(1)));
                    align(p, n);
                    block(n, inline(n, st));
                    endBlock();
                }
                case "blockquote" -> {
                    endBlock();
                    XWPFParagraph p = current();
                    p.setStyle(WordPackage.paragraphStyle(doc, "Quote"));
                    block(n, inline(n, st));
                    endBlock();
                }
                case "pre" -> {
                    endBlock();
                    current();
                    boolean was = pre;
                    pre = true;
                    block(n, inline(n, st).with("code", true));
                    pre = was;
                    endBlock();
                }
                case "hr" -> { endBlock(); current().setBorderBottom(Borders.SINGLE); endBlock(); }
                case "ul", "ol" -> {
                    endBlock();
                    lists.push(n.tag);
                    for (Node k : n.kids) {
                        if ("li".equals(k.tag)) node(k, st);
                        else if (k.tag != null) node(k, st);  // nested list or stray element
                    }
                    lists.pop();
                    endBlock();
                }
                case "li" -> {
                    endBlock();
                    XWPFParagraph p = current();
                    String kind = lists.isEmpty() ? "ul" : lists.peek();
                    int level = Math.max(0, lists.size() - 1);
                    p.setNumID(numbering(kind));
                    p.setNumILvl(BigInteger.valueOf(Math.min(8, level)));
                    for (Node k : n.kids) {
                        if ("ul".equals(k.tag) || "ol".equals(k.tag)) node(k, st);
                        else { if (para == null) { XWPFParagraph q = current(); q.setNumID(numbering(kind)); q.setNumILvl(BigInteger.valueOf(Math.min(8, level))); } node(k, st); }
                    }
                    endBlock();
                }
                case "table" -> table(n, st);
                case "img" -> image(n);
                case "a" -> {
                    String href = n.attr("href");
                    block(n, inline(n, href == null || href.isBlank() || href.startsWith("#") ? st : st.with("link", href).with("u", true).with("color", st.color == null ? "0563C1" : st.color)));
                }
                default -> block(n, inline(n, st));
            }
        }

        /** Inline formatting of an element (its tag and its style attribute). */
        Style inline(Node n, Style st) {
            Style s = st;
            switch (n.tag) {
                case "b", "strong", "th" -> s = s.with("b", true);
                case "i", "em", "cite", "dfn", "var" -> s = s.with("i", true);
                case "u", "ins" -> s = s.with("u", true);
                case "s", "strike", "del" -> s = s.with("s", true);
                case "sup" -> s = s.with("va", "superscript");
                case "sub" -> s = s.with("va", "subscript");
                case "code", "kbd", "samp", "tt" -> s = s.with("code", true);
                case "font" -> {
                    if (n.attr("color") != null) { String c = colour(n.attr("color")); if (c != null) s = s.with("color", c); }
                    if (n.attr("face") != null) s = s.with("font", n.attr("face").split(",")[0].replace("\"", "").replace("'", "").trim());
                    if (n.attr("size") != null) {
                        try { int z = Integer.parseInt(n.attr("size").trim()); s = s.with("size", (double) new int[]{8, 10, 12, 14, 18, 24, 36}[Math.max(1, Math.min(7, z)) - 1]); }
                        catch (NumberFormatException ignored) { }
                    }
                }
                default -> { }
            }
            String style = n.attr("style");
            if (style != null) for (String decl : style.split(";")) {
                int c = decl.indexOf(':');
                if (c < 0) continue;
                String k = decl.substring(0, c).trim().toLowerCase(Locale.ROOT), v = decl.substring(c + 1).trim();
                String lv = v.toLowerCase(Locale.ROOT);
                switch (k) {
                    case "color" -> { String col = colour(v); if (col != null) s = s.with("color", col); }
                    case "font-weight" -> s = s.with("b", lv.equals("bold") || lv.equals("bolder") || lv.matches("[6-9]00"));
                    case "font-style" -> s = s.with("i", lv.equals("italic") || lv.equals("oblique"));
                    case "text-decoration", "text-decoration-line" -> { if (lv.contains("underline")) s = s.with("u", true); if (lv.contains("line-through")) s = s.with("s", true); if (lv.equals("none")) s = s.with("u", false); }
                    case "font-family" -> s = s.with("font", v.split(",")[0].replace("\"", "").replace("'", "").trim());
                    case "font-size" -> { Double pt = points(lv); if (pt != null) s = s.with("size", pt); }
                    case "vertical-align" -> { if (lv.equals("super")) s = s.with("va", "superscript"); if (lv.equals("sub")) s = s.with("va", "subscript"); }
                    default -> { }
                }
            }
            return s;
        }

        void align(XWPFParagraph p, Node n) {
            String a = n.attr("align");
            String style = n.attr("style");
            if (style != null) { Matcher m = Pattern.compile("(?i)text-align\\s*:\\s*(\\w+)").matcher(style); if (m.find()) a = m.group(1); }
            if (a == null) return;
            switch (a.toLowerCase(Locale.ROOT)) {
                case "center" -> p.setAlignment(ParagraphAlignment.CENTER);
                case "right" -> p.setAlignment(ParagraphAlignment.RIGHT);
                case "justify" -> p.setAlignment(ParagraphAlignment.BOTH);
                default -> p.setAlignment(ParagraphAlignment.LEFT);
            }
        }

        void text(String raw, Style st) {
            String t = pre ? raw : raw.replaceAll("[\\s&&[^\u00A0]]+", " ");
            if (!pre && para == null && t.isBlank()) return;  // whitespace between blocks
            if (!pre && para != null && para.getRuns().isEmpty()) t = t.replaceFirst("^ +", "");
            if (t.isEmpty()) return;
            XWPFParagraph p = current();
            XWPFRun r = st.link != null ? p.createHyperlinkRun(st.link) : p.createRun();
            if (st.bold) r.setBold(true);
            if (st.italic) r.setItalic(true);
            if (st.underline) r.setUnderline(UnderlinePatterns.SINGLE);
            if (st.strike) r.setStrikeThrough(true);
            if (st.vertical != null) r.setVerticalAlignment(st.vertical);
            if (st.color != null) r.setColor(st.color);
            if (st.size != null) r.setFontSize(st.size);
            if (st.code) r.setFontFamily("Consolas"); else if (st.font != null && !st.font.isBlank()) r.setFontFamily(st.font);
            String[] lines = t.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) r.addBreak();
                r.setText(lines[i], i);
            }
        }

        void table(Node n, Style st) {
            endBlock();
            List<List<Node>> rows = new ArrayList<>();
            collectRows(n, rows);
            if (rows.isEmpty()) return;
            int cols = rows.stream().mapToInt(r -> r.stream().mapToInt(c -> span(c)).sum()).max().orElse(1);
            XWPFTable t = place.table(rows.size(), Math.max(1, cols));
            tables++;
            String border = n.attr("border");
            if (border != null && border.trim().equals("0")) t.removeBorders();
            for (int r = 0; r < rows.size(); r++) {
                XWPFTableRow row = t.getRow(r);
                int c = 0;
                for (Node cell : rows.get(r)) {
                    if (c >= row.getTableCells().size()) break;
                    XWPFTableCell tc = row.getCell(c);
                    Place saved = place;
                    XWPFParagraph savedPara = para;
                    boolean[] first = {true};
                    place = new Place() {
                        public XWPFParagraph paragraph() {
                            if (first[0]) { first[0] = false; return tc.getParagraphs().isEmpty() ? tc.addParagraph() : tc.getParagraphs().get(0); }
                            return tc.addParagraph();
                        }
                        public XWPFTable table(int rr, int cc) {
                            XWPFParagraph anchor = paragraph();
                            org.apache.xmlbeans.XmlCursor cur = anchor.getCTP().newCursor();
                            XWPFTable nt = tc.insertNewTbl(cur);
                            cur.close();
                            return shape(nt, rr, cc);
                        }
                    };
                    para = null;
                    Style cs = "th".equals(cell.tag) ? st.with("b", true) : st;
                    block(cell, inline(cell, cs));
                    para = savedPara;
                    place = saved;
                    int span = span(cell);
                    if (span > 1) {
                        if (!tc.getCTTc().isSetTcPr()) tc.getCTTc().addNewTcPr();
                        tc.getCTTc().getTcPr().addNewGridSpan().setVal(BigInteger.valueOf(span));
                        for (int k = 1; k < span && c + 1 < row.getTableCells().size(); k++) row.removeCell(c + 1);
                    }
                    c++;
                }
            }
            endBlock();
        }

        static int span(Node cell) {
            try { return Math.max(1, Math.min(63, Integer.parseInt(String.valueOf(cell.attr("colspan")).trim()))); } catch (NumberFormatException e) { return 1; }
        }

        static void collectRows(Node n, List<List<Node>> rows) {
            for (Node k : n.kids) {
                if (k.tag == null) continue;
                if (k.tag.equals("tr")) {
                    List<Node> cells = new ArrayList<>();
                    for (Node c : k.kids) if ("td".equals(c.tag) || "th".equals(c.tag)) cells.add(c);
                    if (!cells.isEmpty()) rows.add(cells);
                } else if (Set.of("thead", "tbody", "tfoot").contains(k.tag)) collectRows(k, rows);
            }
        }

        void image(Node n) {
            String src = n.attr("src");
            if (src == null || src.isBlank()) return;
            byte[] data;
            String name;
            int type;
            try {
                if (src.startsWith("data:")) {
                    int comma = src.indexOf(',');
                    String meta = src.substring(5, Math.max(5, comma)).toLowerCase(Locale.ROOT);
                    data = meta.contains("base64") ? Base64.getMimeDecoder().decode(src.substring(comma + 1)) : src.substring(comma + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    String ext = meta.contains("png") ? "png" : meta.contains("gif") ? "gif" : meta.contains("bmp") ? "bmp" : "jpeg";
                    name = "image." + ext;
                } else {
                    if (src.matches("(?i)^https?://.*")) { para = current(); text("[" + (n.attr("alt") == null ? src : n.attr("alt")) + "]", Style.PLAIN); return; }
                    Path p = files.apply(src.replaceFirst("^file:/+", "/"));
                    if (!Files.isRegularFile(p)) throw new ActionException("WORD.NOT_FOUND", "Picture not found: " + p, "Use a local file or a data: URI in the img src.");
                    data = Files.readAllBytes(p);
                    name = p.getFileName().toString();
                }
                String low = name.toLowerCase(Locale.ROOT);
                type = low.endsWith(".png") ? Document.PICTURE_TYPE_PNG : low.endsWith(".gif") ? Document.PICTURE_TYPE_GIF : low.endsWith(".bmp") ? Document.PICTURE_TYPE_BMP : Document.PICTURE_TYPE_JPEG;
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
                int w = px(n.attr("width"), bi == null ? 100 : bi.getWidth()), h = px(n.attr("height"), bi == null ? 100 : bi.getHeight());
                if (n.attr("width") != null && n.attr("height") == null && bi != null) h = Math.round(w * (float) bi.getHeight() / bi.getWidth());
                if (n.attr("height") != null && n.attr("width") == null && bi != null) w = Math.round(h * (float) bi.getWidth() / bi.getHeight());
                XWPFRun r = current().createRun();
                r.addPicture(new ByteArrayInputStream(data), type, name, org.apache.poi.util.Units.pixelToEMU(w), org.apache.poi.util.Units.pixelToEMU(h));
            } catch (ActionException e) { throw e;
            } catch (Exception e) { throw new ActionException("WORD.BAD_IMAGE", "Cannot add the picture " + (src.length() > 60 ? src.substring(0, 60) + "..." : src) + ": " + e.getMessage(), "Use PNG, JPEG, GIF or BMP pictures."); }
        }

        static int px(String v, int dflt) {
            if (v == null) return dflt;
            try { return Math.max(1, (int) Double.parseDouble(v.replace("px", "").trim())); } catch (NumberFormatException e) { return dflt; }
        }

        /** A numbering definition for bulleted (ul) or numbered (ol) lists, created once per kind. */
        BigInteger numbering(String kind) {
            return numIds.computeIfAbsent(kind, k -> {
                XWPFNumbering numbering = doc.createNumbering();
                CTAbstractNum abs = CTAbstractNum.Factory.newInstance();
                long next = 0;
                for (XWPFAbstractNum a : numbering.getAbstractNums())
                    if (a.getAbstractNum() != null && a.getAbstractNum().getAbstractNumId() != null) next = Math.max(next, a.getAbstractNum().getAbstractNumId().longValue() + 1);
                abs.setAbstractNumId(BigInteger.valueOf(next));
                String[] bullets = {"•", "o", "▪"};
                for (int lvl = 0; lvl < 9; lvl++) {
                    CTLvl l = abs.addNewLvl();
                    l.setIlvl(BigInteger.valueOf(lvl));
                    l.addNewStart().setVal(BigInteger.ONE);
                    if (k.equals("ol")) {
                        l.addNewNumFmt().setVal(lvl % 3 == 0 ? STNumberFormat.DECIMAL : lvl % 3 == 1 ? STNumberFormat.LOWER_LETTER : STNumberFormat.LOWER_ROMAN);
                        l.addNewLvlText().setVal("%" + (lvl + 1) + ".");
                    } else {
                        l.addNewNumFmt().setVal(STNumberFormat.BULLET);
                        l.addNewLvlText().setVal(bullets[lvl % 3]);
                    }
                    l.addNewLvlJc().setVal(STJc.LEFT);
                    CTInd ind = l.addNewPPr().addNewInd();
                    ind.setLeft(BigInteger.valueOf(720L * (lvl + 1)));
                    ind.setHanging(BigInteger.valueOf(360));
                }
                BigInteger absId = numbering.addAbstractNum(new XWPFAbstractNum(abs, numbering));
                return numbering.addNum(absId);
            });
        }
    }

    /** Gives a table made by insertNewTbl (one row, one cell) the wanted number of rows and columns. */
    static XWPFTable shape(XWPFTable t, int rows, int cols) {
        if (t.getRows().isEmpty()) t.createRow();
        XWPFTableRow first = t.getRow(0);
        while (first.getTableCells().size() < cols) first.addNewTableCell();
        while (t.getRows().size() < rows) t.createRow();
        for (XWPFTableRow r : t.getRows()) while (r.getTableCells().size() < cols) r.addNewTableCell();
        return t;
    }

    static String colour(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        Map<String, String> named = Map.ofEntries(Map.entry("black", "000000"), Map.entry("white", "FFFFFF"), Map.entry("red", "FF0000"), Map.entry("green", "008000"),
                Map.entry("blue", "0000FF"), Map.entry("yellow", "FFFF00"), Map.entry("orange", "FFA500"), Map.entry("purple", "800080"), Map.entry("gray", "808080"),
                Map.entry("grey", "808080"), Map.entry("navy", "000080"), Map.entry("maroon", "800000"), Map.entry("teal", "008080"), Map.entry("silver", "C0C0C0"));
        if (named.containsKey(s)) return named.get(s);
        if (s.matches("#[0-9a-f]{6}")) return s.substring(1).toUpperCase(Locale.ROOT);
        if (s.matches("#[0-9a-f]{3}")) return ("" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3)).toUpperCase(Locale.ROOT);
        Matcher m = Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").matcher(s);
        if (m.find()) return String.format("%02X%02X%02X", Math.min(255, Integer.parseInt(m.group(1))), Math.min(255, Integer.parseInt(m.group(2))), Math.min(255, Integer.parseInt(m.group(3))));
        return null;
    }

    static Double points(String v) {
        Matcher m = Pattern.compile("^([0-9.]+)\\s*(pt|px|em|rem|%)?$").matcher(v.trim());
        if (!m.find()) return switch (v.trim()) { case "small" -> 10.0; case "medium" -> 12.0; case "large" -> 14.0; case "x-large" -> 18.0; case "xx-large" -> 24.0; case "x-small" -> 8.0; default -> null; };
        double n = Double.parseDouble(m.group(1));
        String u = m.group(2) == null ? "px" : m.group(2);
        double pt = switch (u) { case "pt" -> n; case "px" -> n * 0.75; case "em", "rem" -> n * 11; case "%" -> n / 100 * 11; default -> n; };
        return Math.max(1, Math.round(pt * 2) / 2.0);
    }

    /** Writes the HTML at the place; returns {paragraphs, tables} created. */
    static int[] write(XWPFDocument doc, String html, Place place, Function<String, Path> files) {
        Writer w = new Writer(doc, place, files);
        w.block(parse(html), Style.PLAIN);
        return new int[]{w.paragraphs, w.tables};
    }
}
