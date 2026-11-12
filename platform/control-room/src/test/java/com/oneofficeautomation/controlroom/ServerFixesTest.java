package com.oneofficeautomation.controlroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Error batch E4 (13 Nov 2026): every error One CommandCenter sends says what to do. Its errors carry their own fix,
 * except input checks (CR.BAD_INPUT), whose message names the value to correct; those, and any error that still comes
 * without one, get the fix for their code where the API answers.
 */
class ServerFixesTest {
    static final Pattern NEW = Pattern.compile("new OoaException\\(");

    @Test
    void everyErrorButAnInputCheckCarriesItsOwnFix() throws IOException {
        Path src = Files.isDirectory(Path.of("src/main/java")) ? Path.of("src/main/java") : Path.of("control-room/src/main/java");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String s = Files.readString(p);
                Matcher m = NEW.matcher(s);
                while (m.find()) {
                    List<String> args = args(s, m.end());
                    boolean noFix = args.size() < 3 || args.get(2).strip().equals("null");
                    if (noFix && !args.get(0).strip().equals("\"CR.BAD_INPUT\""))
                        missing.add(src.relativize(p) + ":" + s.substring(0, m.start()).split("\n", -1).length + " " + args.get(0).strip());
                }
            }
        }
        assertTrue(missing.isEmpty(), "Errors without a suggested fix (give each a third argument saying what to do):\n" + String.join("\n", missing));
    }

    @Test
    void anErrorWithoutAFixGetsTheOneForItsCode() {
        assertEquals("Correct the value the message names, then try again.", ServerFixes.forCode("CR.BAD_INPUT"));
        assertTrue(ServerFixes.forCode("CR.FORBIDDEN").contains("permission"));
        assertTrue(ServerFixes.forCode("SAAS.BAD_SEATS").startsWith("Correct the value"));
        for (String code : List.of("", "X", "MODULE.SOMETHING_ELSE")) assertFalse(ServerFixes.forCode(code).isBlank(), code);
    }

    static List<String> args(String s, int from) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = from;
        char inString = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString != 0) {
                if (c == '\\') { i++; continue; }
                if (c == inString) inString = 0;
            } else if (c == '"' || c == '\'') inString = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) { out.add(s.substring(start, i)); return out; }
                depth--;
            } else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        return out;
    }
}
