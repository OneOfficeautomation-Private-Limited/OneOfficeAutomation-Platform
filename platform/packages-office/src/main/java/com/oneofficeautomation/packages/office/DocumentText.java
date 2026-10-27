package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.swing.text.rtf.RTFEditorKit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The text of Word (.doc, .docx, .rtf) and PowerPoint (.ppt, .pptx) files without opening a session (X8): the old
 * binary formats through POI's HWPF and HSLF, RTF through the JDK. Also used by Knowledge Q&A to index such files.
 */
public final class DocumentText {
    private DocumentText() {}

    static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
    }

    /** The text of a document or presentation; notes of slides when notes is true. */
    public static String text(Path p, boolean notes) {
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        try (InputStream in = Files.newInputStream(p)) {
            return switch (ext(p)) {
                case "doc" -> { try (HWPFDocument d = new HWPFDocument(in); WordExtractor x = new WordExtractor(d)) { yield x.getText(); } }
                case "docx", "docm" -> { try (XWPFDocument d = new XWPFDocument(in); XWPFWordExtractor x = new XWPFWordExtractor(d)) { yield x.getText(); } }
                case "rtf" -> {
                    RTFEditorKit kit = new RTFEditorKit();
                    var doc = kit.createDefaultDocument();
                    kit.read(in, doc, 0);
                    yield doc.getText(0, doc.getLength());
                }
                case "ppt" -> { try (HSLFSlideShow s = new HSLFSlideShow(in); SlideShowExtractor<?, ?> x = new SlideShowExtractor<>(s)) { x.setNotesByDefault(notes); yield x.getText(); } }
                case "pptx", "pptm" -> { try (XMLSlideShow s = new XMLSlideShow(in); SlideShowExtractor<?, ?> x = new SlideShowExtractor<>(s)) { x.setNotesByDefault(notes); yield x.getText(); } }
                default -> throw new ActionException("OFFICE.UNSUPPORTED", "Cannot read the text of " + p.getFileName(), "Use .doc, .docx, .rtf, .ppt or .pptx.");
            };
        } catch (ActionException e) { throw e;
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new ActionException("OFFICE.ENCRYPTED", p.getFileName() + " is password protected", "Open .docx and .pptx files with their password in Open document; old .doc and .ppt files must be saved without one.");
        } catch (IOException | RuntimeException | javax.swing.text.BadLocationException e) {
            throw new ActionException("OFFICE.UNREADABLE", "Cannot read " + p.getFileName() + ": " + e.getMessage(), "Check the file is not damaged and has the right extension.");
        }
    }
}
