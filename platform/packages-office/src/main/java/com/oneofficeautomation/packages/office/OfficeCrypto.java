package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.*;
import java.nio.file.*;
import java.security.GeneralSecurityException;

/** Password handling shared by the Office toolkits: open encrypted OOXML files and write them encrypted again. */
final class OfficeCrypto {
    private OfficeCrypto() {}

    /** Text of a password input given as a Credential or plain text; null when empty. */
    static String secret(Object password) {
        if (password == null) return null;
        String s = password instanceof SecureString ss ? ss.reveal() : Values.text(password);
        return s.isEmpty() ? null : s;
    }

    /** True when the file is an encrypted OOXML package (stored inside an OLE2 container). */
    static boolean encrypted(Path p) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
            if (FileMagic.valueOf(in) != FileMagic.OLE2) return false;
            try (POIFSFileSystem fs = new POIFSFileSystem(Files.newInputStream(p))) { return fs.getRoot().hasEntry("EncryptionInfo"); }
        } catch (IOException e) { return false; }
    }

    /** Opens the plain OOXML bytes of a file, decrypting it with the password when it is encrypted. */
    static InputStream open(Path p, String password, String prefix) throws IOException {
        if (!encrypted(p)) return Files.newInputStream(p);
        if (password == null)
            throw new ActionException(prefix + ".PASSWORD_REQUIRED", "The file " + p.getFileName() + " is password-protected", "Set the password input from the credential vault.");
        try (POIFSFileSystem fs = new POIFSFileSystem(Files.newInputStream(p))) {
            Decryptor d = Decryptor.getInstance(new EncryptionInfo(fs));
            if (!d.verifyPassword(password))
                throw new ActionException(prefix + ".WRONG_PASSWORD", "The password for " + p.getFileName() + " is wrong", "Check the password stored in the credential vault.");
            return new ByteArrayInputStream(d.getDataStream(fs).readAllBytes());
        } catch (GeneralSecurityException e) {
            throw new ActionException(prefix + ".WRONG_PASSWORD", "Cannot decrypt " + p.getFileName() + ": " + e.getMessage(), "Check the password stored in the credential vault.");
        }
    }

    /** Writes plain OOXML bytes to the target, encrypted with agile (AES) encryption when a password is given. */
    static void write(Path target, byte[] ooxml, String password) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        if (password == null) Files.write(tmp, ooxml);
        else try (POIFSFileSystem fs = new POIFSFileSystem()) {
            Encryptor enc = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
            enc.confirmPassword(password);
            try (OutputStream os = enc.getDataStream(fs)) { os.write(ooxml); }
            try (OutputStream out = Files.newOutputStream(tmp)) { fs.writeFilesystem(out); }
        } catch (GeneralSecurityException e) { Files.deleteIfExists(tmp); throw new IOException("Encryption failed: " + e.getMessage(), e); }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    interface Writer { void write(OutputStream out) throws IOException; }

    static byte[] bytes(Writer w) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        w.write(b);
        return b.toByteArray();
    }
}
