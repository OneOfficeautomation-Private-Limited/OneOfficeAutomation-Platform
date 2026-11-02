package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.MsgFile;
import org.apache.poi.hpsf.ClassID;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Writes Outlook .msg files ([MS-OXMSG]): a compound file whose root holds the message's MAPI properties
 * (__properties_version1.0 plus a __substg1.0_ stream per string or binary property), one storage per recipient and
 * per attachment, and the named-property storage Outlook expects. Strings are Unicode (PT_UNICODE).
 */
public final class MsgWriter implements MsgFile.Writer {
    static final ClassID MSG_CLSID = new ClassID("{00020D0B-0000-0000-C000-000000000046}");
    static final int PT_LONG = 0x0003, PT_BOOLEAN = 0x000B, PT_SYSTIME = 0x0040, PT_UNICODE = 0x001F, PT_BINARY = 0x0102;
    static final int READ_WRITE = 0x00000006;

    /** Property stream being built for one storage, with the variable-length values written next to it. */
    static final class Props {
        final DirectoryEntry dir;
        final ByteArrayOutputStream table = new ByteArrayOutputStream();
        Props(DirectoryEntry dir, byte[] header) { this.dir = dir; table.writeBytes(header); }

        private void entry(int id, int type, byte[] value8) {
            ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt((id << 16) | type).putInt(READ_WRITE).put(value8);
            table.writeBytes(b.array());
        }

        Props text(int id, String v) throws IOException {
            if (v == null) return this;
            byte[] data = (v + "\0").getBytes(StandardCharsets.UTF_16LE);
            stream(id, PT_UNICODE, data);
            entry(id, PT_UNICODE, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).putInt(0).array());
            return this;
        }

        Props binary(int id, byte[] v) throws IOException {
            if (v == null) return this;
            stream(id, PT_BINARY, v);
            entry(id, PT_BINARY, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(v.length).putInt(0).array());
            return this;
        }

        Props integer(int id, int v) { entry(id, PT_LONG, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(v).putInt(0).array()); return this; }

        Props bool(int id, boolean v) { entry(id, PT_BOOLEAN, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(v ? 1 : 0).putInt(0).array()); return this; }

        Props time(int id, Instant t) {
            if (t == null) return this;
            long filetime = (t.getEpochSecond() + 11_644_473_600L) * 10_000_000L + t.getNano() / 100;
            entry(id, PT_SYSTIME, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(filetime).array());
            return this;
        }

        private void stream(int id, int type, byte[] data) throws IOException {
            dir.createDocument(String.format("__substg1.0_%04X%04X", id, type), new ByteArrayInputStream(data));
        }

        void close() throws IOException { dir.createDocument("__properties_version1.0", new ByteArrayInputStream(table.toByteArray())); }
    }

    @Override public void write(MsgFile.Message m, Path target) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryEntry root = fs.getRoot();
            root.setStorageClsid(MSG_CLSID);
            List<MsgFile.Address> all = new java.util.ArrayList<>();
            all.addAll(m.to); all.addAll(m.cc); all.addAll(m.bcc);
            int recipients = all.size(), attachments = m.attachments.size();

            ByteBuffer head = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            head.putLong(0).putInt(recipients).putInt(attachments).putInt(recipients).putInt(attachments).putLong(0);
            Props p = new Props(root, head.array());
            String subject = m.subject == null ? "" : m.subject;
            String text = m.bodyText != null ? m.bodyText : m.bodyHtml != null ? htmlToText(m.bodyHtml) : "";
            p.text(0x001A, "IPM.Note").text(0x0037, subject).text(0x0E1D, subject).text(0x003D, "").text(0x1000, text);
            if (m.bodyHtml != null) { p.binary(0x1013, m.bodyHtml.getBytes(StandardCharsets.UTF_8)); p.integer(0x3FDE, 65001); }
            if (m.from != null) {
                String name = m.from.label(), email = m.from.email() == null ? "" : m.from.email();
                p.text(0x0C1A, name).text(0x0C1F, email).text(0x0C1E, "SMTP").text(0x5D01, email)
                 .text(0x0042, name).text(0x0065, email).text(0x0064, "SMTP").text(0x5D02, email);
            }
            p.text(0x0E04, display(m.to)).text(0x0E03, display(m.cc)).text(0x0E02, display(m.bcc));
            Instant sent = m.sent != null ? m.sent : m.received, received = m.received != null ? m.received : m.sent;
            p.time(0x0039, sent).time(0x0E06, received).time(0x3007, sent).time(0x3008, received);
            p.integer(0x0E07, (m.read ? 0x1 : 0) | (attachments > 0 ? 0x10 : 0));
            if (m.messageId != null) p.text(0x1035, m.messageId);
            p.integer(0x0017, switch (m.importance == null ? "normal" : m.importance.toLowerCase(Locale.ROOT)) { case "low" -> 0; case "high" -> 2; default -> 1; });
            p.bool(0x0E1B, attachments > 0).integer(0x340D, 0x00040000);
            p.close();

            DirectoryEntry named = root.createDirectory("__nameid_version1.0");
            for (String s : List.of("__substg1.0_00020102", "__substg1.0_00030102", "__substg1.0_00040102")) named.createDocument(s, new ByteArrayInputStream(new byte[0]));

            for (int i = 0; i < recipients; i++) {
                MsgFile.Address a = all.get(i);
                int type = i < m.to.size() ? 1 : i < m.to.size() + m.cc.size() ? 2 : 3;
                Props r = new Props(root.createDirectory(String.format("__recip_version1.0_#%08X", i)), new byte[8]);
                String email = a.email() == null ? "" : a.email();
                r.text(0x3001, a.label()).text(0x5FF6, a.label()).text(0x3003, email).text(0x3002, "SMTP").text(0x39FE, email)
                 .integer(0x0C15, type).integer(0x3900, 0).integer(0x0FFE, 6).integer(0x3000, i);
                r.close();
            }

            for (int i = 0; i < attachments; i++) {
                MsgFile.Attachment a = m.attachments.get(i);
                Props t = new Props(root.createDirectory(String.format("__attach_version1.0_#%08X", i)), new byte[8]);
                String name = a.name() == null || a.name().isBlank() ? "attachment" + (i + 1) : a.name();
                int dot = name.lastIndexOf('.');
                t.integer(0x3705, 1).binary(0x3701, a.data() == null ? new byte[0] : a.data())
                 .text(0x3704, name).text(0x3707, name).text(0x3001, name).text(0x3703, dot < 0 ? "" : name.substring(dot))
                 .text(0x370E, a.contentType() == null ? "application/octet-stream" : a.contentType())
                 .integer(0x0E20, a.data() == null ? 0 : a.data().length).integer(0x370B, -1).integer(0x0FFE, 7).integer(0x0E21, i);
                if (a.contentId() != null && !a.contentId().isBlank()) t.text(0x3712, a.contentId());
                if (a.inline()) t.bool(0x7FFE, true);
                t.close();
            }

            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (OutputStream out = Files.newOutputStream(tmp)) { fs.writeFilesystem(out); }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String display(List<MsgFile.Address> list) {
        StringBuilder b = new StringBuilder();
        for (MsgFile.Address a : list) { if (b.length() > 0) b.append("; "); b.append(a.label()); }
        return b.toString();
    }

    /** Plain text for an HTML-only message, for readers that show PR_BODY. */
    static String htmlToText(String html) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|tr|h[1-6]|li)>", "\n").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }
}
