package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@OoaPackage(id = "ooa.log", name = "Log to file", version = "1.0.0", description = "Write timestamped lines to a log file; secrets are masked automatically.")
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
}
