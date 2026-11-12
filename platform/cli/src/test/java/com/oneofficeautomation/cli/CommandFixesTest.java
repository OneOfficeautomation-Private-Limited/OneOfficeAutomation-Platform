package com.oneofficeautomation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.CommandFixes;
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
 * Error batches E3 and E3b (13 Nov 2026): every command error says what to do. Toolkit and core package errors carry
 * their own fix (this test keeps new ones from leaving it out); any other command's error without one gets the fix for its code.
 */
class CommandFixesTest {
    static final Path ROOT = Files.isDirectory(Path.of("sdk")) ? Path.of(".") : Path.of("..");
    static final Pattern NEW = Pattern.compile("new (ActionException|OoaException)\\(");

    @Test
    void everyCommandErrorCarriesItsOwnFix() throws IOException {
        List<String> missing = new ArrayList<>();
        // the toolkits (E3), the core packages (E3b) and the CLI, Studio and runner (E5), 13 Nov 2026
        for (String root : List.of("toolkits", "packages", "packages-browser", "packages-email", "packages-office", "packages-pdf", "packages-transfer", "packages-windows", "cli", "runner"))
        try (Stream<Path> files = Files.walk(ROOT.resolve(root))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java") && f.toString().replace('\\', '/').contains("/src/main/")).toList()) {
                String s = Files.readString(p);
                Matcher m = NEW.matcher(s);
                while (m.find()) {
                    List<String> args = args(s, m.end());
                    if (args.size() < 3 || args.get(2).strip().equals("null"))
                        missing.add(ROOT.relativize(p) + ":" + (s.substring(0, m.start()).split("\n", -1).length) + " " + args.get(0).strip());
                }
            }
        }
        assertTrue(missing.isEmpty(), "Command errors without a suggested fix (give each a third argument saying what to do):\n" + String.join("\n", missing));
    }

    @Test
    void anyOtherCommandErrorGetsTheFixForItsCode() {
        assertEquals(CommandFixes.forCode("EXCEL.IO_ERROR"), new ActionException("EXCEL.IO_ERROR", "Cannot read a.xlsx").suggestedFix());
        assertTrue(new ActionException("RUN.CANCELLED", "Stopped").suggestedFix().startsWith("The run was stopped"));
        assertTrue(new ActionException("PDF.BAD_PAGE", "Page 0 does not exist", null).suggestedFix().contains("step's settings"));
        assertTrue(new ActionException("MAIL.NO_RECIPIENT", "No one to send to").suggestedFix().startsWith("Fill in"));
        assertEquals("Use 1 to 5.", new ActionException("X.BAD_LEVEL", "Level 9", "Use 1 to 5.").suggestedFix());   // its own fix wins
        for (String code : List.of("A", "A.B", "", "X.SOMETHING_ELSE")) assertTrue(!CommandFixes.forCode(code).isBlank(), code);
    }

    /** The arguments of a call whose opening parenthesis ends at {@code from}: commas at the top level, outside strings. */
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
