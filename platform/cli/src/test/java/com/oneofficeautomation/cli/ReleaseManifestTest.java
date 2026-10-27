package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Signed release manifests (S6): the vendor signs the files' hashes, customers check a download against them. */
class ReleaseManifestTest {

    @Test @SuppressWarnings("unchecked")
    void aSignedManifestFindsChangedAndMissingFilesAndRefusesForgedOnes() throws Exception {
        Path dist = Files.createTempDirectory("ooa-release");
        Files.writeString(dist.resolve("ooa-customer-0.2.0.zip"), "zip bytes");
        Files.writeString(dist.resolve("ooa-0.2.0.msi"), "msi bytes");
        Files.writeString(dist.resolve("sbom.cdx.json"), "{}");
        Files.writeString(dist.resolve("notes.md"), "not a release file");
        Map<String, String> kp = License.newKeyPair();
        String text = ReleaseCommand.sign(dist, "0.2.0", License.privateKey(kp.get("private")));
        var pub = License.publicKey(kp.get("public"));

        List<String> ok = ReleaseCommand.verify(text, pub, dist);
        assertTrue(ok.get(0).contains("release 0.2.0"), ok.get(0));
        assertEquals(3, ok.stream().filter(l -> l.startsWith("PASS")).count(), ok.toString());
        assertTrue(ok.stream().noneMatch(l -> l.contains("notes.md")), "only release files are listed");

        // a changed file fails, a missing one is reported
        Files.writeString(dist.resolve("ooa-0.2.0.msi"), "msi bytes, changed");
        Files.delete(dist.resolve("sbom.cdx.json"));
        List<String> bad = ReleaseCommand.verify(text, pub, dist);
        assertTrue(bad.contains("FAIL     ooa-0.2.0.msi (changed: do not install it)"), bad.toString());
        assertTrue(bad.contains("MISSING  sbom.cdx.json"), bad.toString());

        // a manifest whose list was edited, or signed with another key, is refused as a whole
        Map<String, Object> m = (Map<String, Object>) JsonLite.parse(text);
        String body = new String(Base64.getDecoder().decode((String) m.get("payload")), StandardCharsets.UTF_8).replace("zip bytes", "x");
        m.put("payload", Base64.getEncoder().encodeToString(body.replaceFirst("\"sha256\":\"[0-9a-f]+\"", "\"sha256\":\"00\"").getBytes(StandardCharsets.UTF_8)));
        assertEquals("RELEASE.BAD_SIGNATURE", assertThrows(OoaException.class, () -> ReleaseCommand.verify(JsonLite.write(m), pub, dist)).code());
        var other = License.publicKey(License.newKeyPair().get("public"));
        assertEquals("RELEASE.BAD_SIGNATURE", assertThrows(OoaException.class, () -> ReleaseCommand.verify(text, other, dist)).code());
        assertEquals("RELEASE.BAD_MANIFEST", assertThrows(OoaException.class, () -> ReleaseCommand.verify("{\"format\":\"x\"}", pub, dist)).code());
    }

    @Test void aManifestCannotPointOutsideItsFolder() throws Exception {
        Path dist = Files.createTempDirectory("ooa-release-2");
        Map<String, String> kp = License.newKeyPair();
        // sign a manifest by hand that names a file outside the folder
        String body = "{\"product\":\"ONEOFFICEAUTOMATION\",\"version\":\"1\",\"created\":\"now\",\"files\":[{\"name\":\"../secret.txt\",\"size\":1,\"sha256\":\"00\"}]}";
        var sig = java.security.Signature.getInstance("Ed25519");
        sig.initSign(License.privateKey(kp.get("private")));
        sig.update(body.getBytes(StandardCharsets.UTF_8));
        String text = JsonLite.write(Map.of("format", "ooa-release-1", "payload", Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)),
            "signature", Base64.getEncoder().encodeToString(sig.sign())));
        List<String> r = ReleaseCommand.verify(text, License.publicKey(kp.get("public")), dist);
        assertTrue(r.get(1).startsWith("FAIL") && r.get(1).contains("not a plain file name"), r.toString());
    }
}
