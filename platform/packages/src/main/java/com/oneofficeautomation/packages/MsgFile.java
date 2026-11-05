package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * An email saved as an Outlook .msg file (an OLE compound file of MAPI properties). The mail actions fill a
 * {@link Message}; the file itself is written by the Office module (Apache POI), found through {@link ServiceLoader}.
 */
public final class MsgFile {
    private MsgFile() {}

    /** A person on the message. */
    public record Address(String name, String email) {
        public String label() { return name == null || name.isBlank() ? email : name; }
    }

    /** A file attached to the message; contentId is set for pictures shown in the HTML body. */
    public record Attachment(String name, String contentType, byte[] data, String contentId, boolean inline) {}

    /** What is saved: headers, the text and HTML bodies and the attachments. */
    public static final class Message {
        public String subject = "", bodyText, bodyHtml, messageId, importance = "normal";
        public Address from;
        public final List<Address> to = new ArrayList<>(), cc = new ArrayList<>(), bcc = new ArrayList<>();
        public Instant sent, received;
        public boolean read = true;
        public final List<Attachment> attachments = new ArrayList<>();
    }

    /** Writes .msg files; provided by the Office module. */
    public interface Writer {
        void write(Message message, Path target) throws java.io.IOException;
    }

    /** Saves the message as a .msg file. */
    public static void write(Message m, Path target) {
        Writer w = null;
        for (Writer x : ServiceLoader.load(Writer.class, MsgFile.class.getClassLoader())) { w = x; break; }
        if (w == null) throw new ActionException("EMAIL.MSG_UNAVAILABLE", "Saving .msg files needs the Office module", "Install the Office package, or save the email as .eml.");
        try {
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            w.write(m, target);
        } catch (java.io.IOException e) {
            throw new ActionException("FILE.IO_ERROR", "Cannot write " + target + ": " + e.getMessage(), "Check the folder exists and is writable.", true);
        }
    }
}
