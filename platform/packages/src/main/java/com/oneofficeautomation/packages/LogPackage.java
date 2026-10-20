package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@OoaPackage(id = "ooa.log", name = "Log to file", version = "1.0.0", description = "Write timestamped lines to a log file or to the run log in Command Center; secrets are masked automatically.")
public final class LogPackage {
    private static final Object LOCK = new Object();

    @Action(id = "write", name = "Log to file", category = "Logging", capabilities = "filesystem",
            description = "Append (or overwrite with) a line in a text log file, with optional timestamp and level.")
    public Map<String, Object> write(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Log file path, e.g. logs/invoices.log.") String path,
            @Input(name = "message", type = OoaType.STRING, description = "Text to log; ${...} interpolation allowed.") String message,
            @Input(name = "level", type = OoaType.STRING, required = false, defaultValue = "\"INFO\"", enumValues = {"DEBUG", "INFO", "WARN", "ERROR"}, description = "Level label.") String level,
            @Input(name = "timestamp", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Start the line with the date and time.") boolean ts,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace the file instead of appending.") boolean overwrite) {
        Path p = ctx.resolvePath(path);
        String line = (ts ? OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " " : "") + level + " " + ctx.redact(message) + System.lineSeparator();
        synchronized (LOCK) {
            try {
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                if (overwrite) Files.writeString(p, line, StandardCharsets.UTF_8);
                else Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write log " + p + ": " + e.getMessage(), "Check the folder exists and is writable.", true); }
        }
        return Map.of();
    }

    static String render(Object v) {
        if (v instanceof SecureString) return SecureString.MASK;
        return com.oneofficeautomation.runner.Values.text(com.oneofficeautomation.runner.Values.toJsonable(v));
    }

    @Action(id = "log_variables", name = "Log variables to file", category = "Logging", capabilities = "filesystem",
            description = "Write one name=value line per variable to a log file, to record the state of a run; credentials are written as ****.")
    @Output(name = "lines", type = OoaType.NUMBER, description = "Number of variable lines written.")
    public Map<String, Object> logVariables(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Log file path, e.g. logs/state.log.") String path,
            @Input(name = "variables", type = OoaType.DICTIONARY, description = "Names and values to write, e.g. {\"invoice\": \"$invoice\", \"total\": \"$total\"}.") Map<String, Object> variables,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Heading line written before the variables.") String title,
            @Input(name = "timestamp", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Start the heading with the date and time.") boolean ts,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace the file instead of appending.") boolean overwrite) {
        Path p = ctx.resolvePath(path);
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        String head = (ts ? OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : "") + (title == null || title.isBlank() ? "" : (ts ? " " : "") + title);
        if (!head.isBlank()) sb.append(ctx.redact(head)).append(nl);
        int n = 0;
        if (variables != null) for (var e : variables.entrySet()) {
            String value = render(e.getValue()).replace("\r", "\\r").replace("\n", "\\n");
            sb.append(e.getKey()).append('=').append(ctx.redact(value)).append(nl);
            n++;
        }
        synchronized (LOCK) {
            try {
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                if (overwrite) Files.writeString(p, sb, StandardCharsets.UTF_8);
                else Files.writeString(p, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { throw new ActionException("LOG.IO_ERROR", "Cannot write log " + p + ": " + e.getMessage(), "Check the folder exists and is writable.", true); }
        }
        return Map.of("lines", java.math.BigDecimal.valueOf(n));
    }

    static String level(String level) {
        return switch (level == null ? "INFO" : level.toUpperCase(Locale.ROOT)) {
            case "DEBUG" -> "debug";
            case "INFO" -> "info";
            case "WARN", "WARNING" -> "warning";
            case "ERROR" -> "error";
            default -> throw new ActionException("LOG.BAD_LEVEL", "Unknown level '" + level + "'", "Use DEBUG, INFO, WARN or ERROR.");
        };
    }

    @Action(id = "log_to_command_center", name = "Log to Command Center", category = "Logging",
            description = "Add a message, with optional details, to this run's log so it appears in Command Center with the run's activity.")
    public Map<String, Object> logToCommandCenter(ActionContext ctx,
            @Input(name = "message", type = OoaType.STRING, description = "Text to log; ${...} interpolation allowed.") String message,
            @Input(name = "level", type = OoaType.STRING, required = false, defaultValue = "\"INFO\"", enumValues = {"DEBUG", "INFO", "WARN", "ERROR"}, description = "Level of the log entry.") String level,
            @Input(name = "details", type = OoaType.DICTIONARY, required = false, description = "Extra name and value pairs added to the entry as JSON.") Map<String, Object> details) {
        String lvl = level(level);
        String text = message == null ? "" : message;
        if (details != null && !details.isEmpty()) text += " " + JsonLite.write(com.oneofficeautomation.runner.Values.toJsonable(details));
        ctx.log(lvl, text);  // ActionContext masks every secret of the run before the line leaves the action
        return Map.of();
    }
}
