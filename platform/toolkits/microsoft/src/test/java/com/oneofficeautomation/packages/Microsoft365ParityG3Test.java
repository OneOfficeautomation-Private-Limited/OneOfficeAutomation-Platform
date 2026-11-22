package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365GapsTest.accepted;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 (AA parity, 21 Nov 2026), OneDrive and Outlook on Microsoft 365, against a fake Graph: a download with no local
 * path, an upload into a folder, one's own permissions, a forward with an HTML note, and a missing attachment skipped.
 */
class Microsoft365ParityG3Test {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");

    @Test void downloadWithoutAPathAndUploadIntoAFolder() throws Exception {
        try (Fake f = new Fake()) {
            f.rx("GET", ".*/root:/Reports/q3\\.csv:/content", c -> new Object[]{200, "a,b\n".getBytes()});
            f.rx("PUT", ".*/root:/Inbox/rates\\.csv:/content", c -> ok("{\"id\":\"x\",\"name\":\"rates.csv\",\"size\":4}"));
            Path w = tmp();
            ActionContext ctx = Microsoft365GapsTest.ctx(w);
            String saved = String.valueOf(pkg.download(ctx, tok, "Reports/q3.csv", null, false, "me/drive", f.base()).get("path"));
            assertTrue(saved.endsWith("q3.csv") && saved.contains(".ooa-temp"), saved);
            assertEquals("a,b\n", Files.readString(Path.of(saved)));
            Files.writeString(w.resolve("rates.csv"), "EUR1");
            pkg.upload(ctx, tok, "rates.csv", "Inbox/", "rename", "me/drive", f.base(), 10);
            assertFalse(f.all("PUT", ".*/root:/Inbox/rates\\.csv:/content").isEmpty(), "into the folder, keeping the name");
        }
    }

    @Test void ownPermissionsWhenNoEmailIsGiven() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/me", c -> ok("{\"mail\":\"agent@acme.com\"}"));
            f.rx("GET", ".*/permissions", c -> ok("{\"value\":[{\"roles\":[\"write\"],\"grantedToV2\":{\"user\":{\"email\":\"agent@acme.com\"}}}]}"));
            Map<String, Object> r = pkg.checkPermissions(Microsoft365GapsTest.ctx(tmp()), tok, "Reports/q3.csv", null, "me/drive", f.base());
            assertEquals(true, r.get("has_access"));
            assertEquals(List.of("write"), r.get("roles"));
        }
    }

    @Test void forwardWithAnHtmlNoteAndAMissingAttachmentSkipped() throws Exception {
        try (Fake f = new Fake()) {
            String me = "/v1.0/me";
            f.at("POST", me + "/messages/m1/createForward", c -> ok("{\"id\":\"d1\"}"));
            f.at("GET", me + "/messages/d1", c -> ok("{\"body\":{\"contentType\":\"html\",\"content\":\"<html><body><p>original</p></body></html>\"}}"));
            f.at("PATCH", me + "/messages/d1", c -> ok("{}"));
            f.at("POST", me + "/messages/d1/send", c -> accepted());
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            pkg.forwardMail(ctx, tok, "m1", "x@y.com", "<b>Please approve</b>", null, null, List.of("missing.pdf"), null, null, "me", f.base(), true, "skip");
            String body = String.valueOf(((Map<?, ?>) f.last("PATCH", me + "/messages/d1").json().get("body")).get("content"));
            assertTrue(body.contains("<body><b>Please approve</b><br><p>original</p>"), body);
            assertTrue(f.all("POST", me + "/messages/d1/attachments").isEmpty(), "the missing file is left out");
            assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class,
                    () -> pkg.forwardMail(ctx, tok, "m1", "x@y.com", "hi", null, null, List.of("missing.pdf"), null, null, "me", f.base(), false, "fail")).code());
        }
    }
}
