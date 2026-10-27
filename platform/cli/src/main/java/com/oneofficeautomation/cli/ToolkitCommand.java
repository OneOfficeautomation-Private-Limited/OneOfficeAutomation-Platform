package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.PackageIntrospector;
import com.oneofficeautomation.sdk.PackageSigning;
import com.oneofficeautomation.sdk.ToolkitJar;

import java.nio.file.*;
import java.util.*;

/**
 * {@code ooa toolkit}: for developers of custom toolkits (K6).
 * <pre>
 * ooa toolkit keygen --publisher NAME [--out DIR]   a publisher key pair: NAME.key (keep it safe) and NAME.pub (for Command Center admins)
 * ooa toolkit sign --jar FILE --class NAME --key FILE [--out FILE]   sign the toolkit class in the jar
 * ooa toolkit inspect --jar FILE                    the toolkit in a signed jar: id, version, publisher, actions
 * </pre>
 * Upload the signed jar in Command Center > Toolkits after an admin has trusted the publisher's .pub key.
 */
final class ToolkitCommand {
    private ToolkitCommand() { }

    static int run(String verb, Map<String, String> o) throws Exception {
        switch (verb) {
            case "keygen" -> {
                String pub = o.get("publisher");
                if (pub == null || !pub.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")) { System.err.println("Give --publisher NAME (letters, digits, . _ -)"); return 1; }
                Path dir = Path.of(o.getOrDefault("out", "."));
                ToolkitJar.keygen(pub, dir);
                System.out.println("Wrote " + dir.resolve(pub + ".key") + " (private: keep it out of source control)");
                System.out.println("  and " + dir.resolve(pub + ".pub") + " (give it to the Command Center admins: Toolkits > Publishers)");
                return 0;
            }
            case "sign" -> {
                PackageSigning.PublisherKey key = ToolkitJar.readKey(Path.of(o.get("key")));
                Path out = o.get("out") == null ? null : Path.of(o.get("out"));
                PackageSigning.Signature sig = ToolkitJar.sign(Path.of(o.get("jar")), o.get("class"), key, out);
                System.out.println("Signed as " + sig.publisher() + " (digest " + sig.digest().substring(0, 16) + "...): " + (out == null ? o.get("jar") : out));
                return 0;
            }
            case "inspect" -> {
                ToolkitJar.Loaded l = ToolkitJar.load(Path.of(o.get("jar")).toAbsolutePath(), ToolkitCommand.class.getClassLoader());
                var info = PackageIntrospector.inspect(l.cls());
                System.out.println(info.meta().id() + " " + info.meta().version() + "  (" + l.cls().getName() + ")");
                System.out.println("  publisher: " + (l.signature() == null ? "(not signed)" : l.signature().publisher()));
                System.out.println("  signature matches the code: " + (l.signature() != null && PackageSigning.digest(l.cls()).equals(l.signature().digest())));
                System.out.println("  actions:   " + String.join(", ", new TreeSet<>(info.actions().keySet())));
                System.out.println("  sha256:    " + l.sha256());
                return 0;
            }
            default -> throw new IllegalArgumentException("Unknown toolkit command " + verb);
        }
    }
}
