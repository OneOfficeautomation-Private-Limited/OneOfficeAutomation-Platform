package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.BinaryValue;
import com.oneofficeautomation.sdk.ImageValue;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.OoaType;
import com.oneofficeautomation.sdk.SecureString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime value model shared with the Python reference: String, BigDecimal (Number), Boolean, null,
 * List, Map (Dictionary/Record), Table = {"columns": [...], "rows": [[...]]}, LocalDateTime (UTC),
 * SecureString (Credential), LocalTime (Time), Duration (TimeSpan), ImageValue (Image) and BinaryValue (Binary).
 * Numbers are always decimal, never double.
 */
public final class Values {
    private Values() {}

    public static BigDecimal toNumber(Object v) {
        if (v == null) throw new OoaException("VAR.EMPTY", "A value used as a number is empty",
                "A variable used here has no value yet (an input that was not given is empty): give it a value before this step, or set a default in the variable.");
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (v instanceof Integer || v instanceof Long || v instanceof Short) return BigDecimal.valueOf(((Number) v).longValue());
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) {
            try { return new BigDecimal(s.trim().replace(",", "")); } catch (NumberFormatException ignored) { }
        }
        throw new OoaException("VAR.INVALID", "Cannot convert " + typeName(v) + " value " + safeRepr(v) + " to Number",
                "Check the value is numeric, e.g. 42 or 19.99.");
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "String";
        if (v instanceof BigDecimal) return "Number";
        if (v instanceof Boolean) return "Boolean";
        if (v instanceof List) return "List";
        if (v instanceof Map) return "Dictionary";
        if (v instanceof SecureString) return "Credential";
        if (v instanceof LocalDateTime) return "DateTime";
        if (v instanceof LocalTime) return "Time";
        if (v instanceof Duration) return "TimeSpan";
        if (v instanceof ImageValue) return "Image";
        if (v instanceof BinaryValue) return "Binary";
        return v.getClass().getSimpleName();
    }

    /** Text form used by interpolation and String coercion. Image and Binary give a summary, never their bytes. */
    public static String text(Object v) {
        if (v == null) return "";
        if (v instanceof SecureString) throw secureLeak("text");
        if (v instanceof BigDecimal d) {
            return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0 ? d.stripTrailingZeros().toBigInteger().toString() : d.toPlainString();
        }
        if (v instanceof Map || v instanceof List) return JsonLite.write(toJsonable(v));
        if (v instanceof LocalDateTime t) return t.toString();
        if (v instanceof LocalTime t) return formatTime(t);
        if (v instanceof Duration d) return formatTimeSpan(d);
        return v.toString();
    }

    public static OoaException secureLeak(String where) {
        return new OoaException("VAR.SECURE_LEAK", "A Credential value cannot be used in " + where,
                "Pass the Credential variable directly to an action input that accepts credentials.");
    }

    public static Object coerce(Object value, String typeName, String varName) { return coerce(value, typeName, varName, null); }

    /**
     * Converts a value to a declared type. {@code baseDir} is where a relative file path given to an Image or Binary
     * variable is read from (the run's working folder); null means the process's current folder.
     */
    @SuppressWarnings("unchecked")
    public static Object coerce(Object value, String typeName, String varName, Path baseDir) {
        OoaType t = OoaType.fromFlowName(typeName);
        if (value == null || t == OoaType.ANY) return value;
        switch (t) {
            case STRING:
                if (value instanceof SecureString)
                    throw new OoaException("VAR.SECURE_LEAK", "Credential cannot be assigned to String variable '" + varName + "'",
                            "Keep secrets in Credential variables.");
                return text(value);
            case NUMBER: return toNumber(value);
            case BOOLEAN:
                if (value instanceof Boolean) return value;
                if (value instanceof String s) {
                    String l = s.trim().toLowerCase(Locale.ROOT);
                    if (Set.of("true", "yes", "1").contains(l)) return true;
                    if (Set.of("false", "no", "0").contains(l)) return false;
                }
                if (value instanceof BigDecimal d) return d.signum() != 0;
                break;
            case DATETIME: return toDateTime(value);
            case LIST:
                if (value instanceof List) return value;
                break;
            case DICTIONARY: case RECORD:
                if (value instanceof Map) return value;
                break;
            case TABLE:
                if (value instanceof Map<?, ?> m && m.containsKey("columns") && m.containsKey("rows")) return value;
                if (value instanceof List<?> l && l.stream().allMatch(r -> r instanceof Map)) {
                    LinkedHashSet<String> cols = new LinkedHashSet<>();
                    for (Object r : l) for (Object k : ((Map<?, ?>) r).keySet()) cols.add(String.valueOf(k));
                    List<Object> rows = new ArrayList<>();
                    for (Object r : l) { List<Object> row = new ArrayList<>(); for (String c : cols) row.add(((Map<String, Object>) r).get(c)); rows.add(row); }
                    return table(new ArrayList<>(cols), rows);
                }
                break;
            case FILE:
                if (value instanceof ImageValue || value instanceof BinaryValue)
                    throw new OoaException("VAR.TYPE_MISMATCH", "'" + varName + "' expects a file path, got " + typeName(value) + " (" + value + ")",
                            "Give a file path. An Image can be given directly to the picture fields of the Image commands.");
                return text(value);
            case CREDENTIAL:
                if (value instanceof SecureString) return value;
                throw new OoaException("VAR.SECURE_REQUIRED", "Variable '" + varName + "' needs a Credential reference, not a plain value",
                        "Pass a vault reference like {\"$credential\": \"locker/name/attribute\"}.");
            case TIME: return toTime(value);
            case TIMESPAN: return toTimeSpan(value);
            case IMAGE: return toImage(value, baseDir);
            case BINARY: return toBinary(value, baseDir);
            default: return value;
        }
        throw new OoaException("VAR.TYPE_MISMATCH", "Variable '" + varName + "' expects " + t.flowName() + ", got " + typeName(value),
                "Provide a " + t.flowName() + " value for '" + varName + "'.");
    }

    public static LocalDateTime toDateTime(Object v) {
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof LocalDate d) return d.atStartOfDay();
        if (v instanceof String s) {
            String x = s.trim();
            try {
                if (x.length() == 10) return LocalDate.parse(x).atStartOfDay();
                if (x.endsWith("Z") || x.matches(".*[+-]\\d{2}:\\d{2}$"))
                    return OffsetDateTime.parse(x.replace(" ", "T")).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                return LocalDateTime.parse(x.replace(" ", "T"));
            } catch (DateTimeParseException ignored) { }
        }
        throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read " + safeRepr(v) + " as a DateTime",
                "Use ISO format, e.g. 2026-10-18 or 2026-10-18T14:30:00.");
    }

    // ---------- Time and TimeSpan ----------
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?$");
    private static final Pattern AM_PM = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?(?::(\\d{2}))?\\s*([ap])\\.?\\s*m\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPAN_CLOCK = Pattern.compile("^(?:(\\d+)\\.)?(\\d+):(\\d{1,2})(?::(\\d{1,2})(?:\\.(\\d{1,9}))?)?$");
    private static final Pattern SPAN_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)");
    private static final Map<String, Long> SPAN_UNITS = new HashMap<>();
    static {
        for (String u : List.of("w", "wk", "wks", "week", "weeks")) SPAN_UNITS.put(u, 604_800_000_000_000L);
        for (String u : List.of("d", "day", "days")) SPAN_UNITS.put(u, 86_400_000_000_000L);
        for (String u : List.of("h", "hr", "hrs", "hour", "hours")) SPAN_UNITS.put(u, 3_600_000_000_000L);
        for (String u : List.of("m", "min", "mins", "minute", "minutes")) SPAN_UNITS.put(u, 60_000_000_000L);
        for (String u : List.of("s", "sec", "secs", "second", "seconds")) SPAN_UNITS.put(u, 1_000_000_000L);
        for (String u : List.of("ms", "millisecond", "milliseconds")) SPAN_UNITS.put(u, 1_000_000L);
    }

    /** A time of day as text: always hours, minutes and seconds (14:30:00), with a fraction only when there is one. */
    public static String formatTime(LocalTime t) { return t.format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME); }

    /** A TimeSpan in ISO 8601: PT2H15M, PT0S, PT1.5S; a negative one starts with a minus sign (-PT30M). */
    public static String formatTimeSpan(Duration d) { return d.isNegative() ? "-" + d.negated() : d.toString(); }

    /** Time from 14:30, 14:30:05, 9:05, 2:30 PM, a DateTime (its time of day) or ISO date-time text. */
    public static LocalTime toTime(Object v) {
        if (v instanceof LocalTime t) return t;
        if (v instanceof LocalDateTime dt) return dt.toLocalTime();
        if (v instanceof String s) {
            String x = s.strip();
            Matcher m = CLOCK.matcher(x);
            try {
                if (m.matches()) {
                    int nanos = m.group(4) == null ? 0 : Integer.parseInt((m.group(4) + "00000000").substring(0, 9));
                    return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), nanos);
                }
                m = AM_PM.matcher(x);
                if (m.matches()) {
                    int h = Integer.parseInt(m.group(1));
                    if (h < 1 || h > 12) throw new DateTimeException("hour " + h);
                    h = h % 12 + (m.group(4).equalsIgnoreCase("p") ? 12 : 0);
                    return LocalTime.of(h, m.group(2) == null ? 0 : Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
                }
                if (x.length() > 10) return toDateTime(x).toLocalTime();
            } catch (DateTimeException | OoaException ignored) { }
        }
        throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read " + safeRepr(v) + " as a Time",
                "Use a time of day such as 14:30, 14:30:05 or 2:30 PM.");
    }

    /**
     * TimeSpan from ISO 8601 (PT2H15M, P1DT3H, -PT30M), a clock (01:30:00, 1.02:03:04 for days), units (2h 15m,
     * 1d 3h, 90s, 1.5 hours) or a number of seconds. A leading minus makes it negative.
     */
    public static Duration toTimeSpan(Object v) {
        if (v instanceof Duration d) return d;
        try {
            if (v instanceof BigDecimal || (v instanceof Number && !(v instanceof BigDecimal))) return seconds(toNumber(v));
            if (v instanceof String s) {
                String x = s.strip().toLowerCase(Locale.ROOT);
                boolean neg = x.startsWith("-");
                if (neg || x.startsWith("+")) x = x.substring(1).strip();
                Duration d = null;
                if (x.startsWith("p")) d = Duration.parse(x.toUpperCase(Locale.ROOT));
                else if (x.matches("\\d+(\\.\\d+)?")) d = seconds(new BigDecimal(x));
                else {
                    Matcher m = SPAN_CLOCK.matcher(x);
                    if (m.matches()) {
                        long mins = Long.parseLong(m.group(3)), secs = m.group(4) == null ? 0 : Long.parseLong(m.group(4));
                        if (mins < 60 && secs < 60) {
                            d = Duration.ofDays(m.group(1) == null ? 0 : Long.parseLong(m.group(1))).plusHours(Long.parseLong(m.group(2)))
                                    .plusMinutes(mins).plusSeconds(secs)
                                    .plusNanos(m.group(5) == null ? 0 : Long.parseLong((m.group(5) + "00000000").substring(0, 9)));
                        }
                    } else {
                        String rest = x.replace(",", " ").replaceAll("\\band\\b", " ");
                        Matcher p = SPAN_PART.matcher(rest);
                        BigDecimal nanos = BigDecimal.ZERO;
                        int at = 0;
                        boolean any = false;
                        while (p.find()) {
                            if (!rest.substring(at, p.start()).isBlank()) { any = false; break; }
                            Long unit = SPAN_UNITS.get(p.group(2));
                            if (unit == null) { any = false; break; }
                            nanos = nanos.add(new BigDecimal(p.group(1)).multiply(BigDecimal.valueOf(unit)));
                            at = p.end();
                            any = true;
                        }
                        if (any && rest.substring(at).isBlank()) d = Duration.ofNanos(nanos.setScale(0, RoundingMode.HALF_UP).longValueExact());
                    }
                }
                if (d != null) return neg ? d.negated() : d;
            }
        } catch (DateTimeException | ArithmeticException | NumberFormatException ignored) { }
        throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read " + safeRepr(v) + " as a TimeSpan",
                "Use a length of time such as 2h 15m, 1d 3h, 01:30:00, PT2H15M or a number of seconds.");
    }

    private static Duration seconds(BigDecimal n) {
        return Duration.ofNanos(n.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /** A TimeSpan's length in seconds, exactly (fractions of a second included). */
    public static BigDecimal totalSeconds(Duration d) {
        return new BigDecimal(BigInteger.valueOf(d.getSeconds()).multiply(BigInteger.valueOf(1_000_000_000L)).add(BigInteger.valueOf(d.getNano())), 9)
                .stripTrailingZeros();
    }

    // ---------- Image and Binary ----------
    /** The most bytes an Image or Binary variable reads from one file (larger files stay files). */
    static final long MAX_BYTES = 256L * 1024 * 1024;

    /** Binary from a Binary or Image, {"$binary": Base64, "mime"}, a data: URI, a file path, Base64 text or a list of byte values (0 to 255). */
    public static BinaryValue toBinary(Object v, Path baseDir) {
        if (v instanceof BinaryValue b) return b;
        if (v instanceof ImageValue i) return i.toBinary();
        if (v instanceof byte[] b) return new BinaryValue(b, null);
        if (v instanceof List<?> l) {
            byte[] out = new byte[l.size()];
            for (int k = 0; k < out.length; k++) {
                BigDecimal n = l.get(k) == null ? null : toNumberOrNull(l.get(k));
                if (n == null || n.stripTrailingZeros().scale() > 0 || n.compareTo(BigDecimal.valueOf(-128)) < 0 || n.compareTo(BigDecimal.valueOf(255)) > 0)
                    throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read a list as Binary: item " + (k + 1) + " (" + safeRepr(l.get(k)) + ") is not a byte value",
                            "Give whole numbers from 0 to 255.");
                out[k] = (byte) n.intValue();
            }
            return new BinaryValue(out, null);
        }
        Object[] read = bytesOf(v, baseDir, "Binary");
        return new BinaryValue((byte[]) read[0], (String) read[1]);
    }

    /** Image from an Image, a Binary holding a picture, {"$binary": Base64}, a data: URI, a picture file's path or Base64 text. */
    public static ImageValue toImage(Object v, Path baseDir) {
        if (v instanceof ImageValue i) return i;
        if (v instanceof java.awt.image.BufferedImage b) return ImageValue.of(b);
        byte[] bytes;
        if (v instanceof BinaryValue b) bytes = b.bytes();
        else if (v instanceof byte[] b) bytes = b;
        else bytes = (byte[]) bytesOf(v, baseDir, "Image")[0];
        try { return ImageValue.read(bytes); }
        catch (IllegalArgumentException e) {
            throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read " + (v instanceof String s && s.length() < 300 ? "'" + s + "'" : "a " + typeName(v) + " value") + " as an Image: " + e.getMessage(),
                    "Give a PNG, JPEG, BMP or GIF file path, or a Binary value holding one.");
        }
    }

    private static BigDecimal toNumberOrNull(Object v) {
        try { return toNumber(v); } catch (OoaException e) { return null; }
    }

    /** {bytes, mime} from {"$binary": ...}, a data: URI, an existing file or Base64 text; otherwise VAR.TYPE_MISMATCH. */
    private static Object[] bytesOf(Object v, Path baseDir, String type) {
        String fix = "Give a file path, Base64 text, a data: URI or {\"$binary\": \"<Base64>\", \"mime\": \"...\"}.";
        if (v instanceof Map<?, ?> m && m.get("$binary") instanceof String b64) {
            byte[] b = base64(b64);
            if (b == null) throw new OoaException("VAR.TYPE_MISMATCH", "The $binary text is not valid Base64", fix);
            return new Object[]{b, m.get("mime") instanceof String mime ? mime : null};
        }
        if (v instanceof String s) {
            String x = s.strip();
            Matcher d = DATA_URI.matcher(x);
            if (d.matches()) {
                byte[] b = base64(d.group(2));
                if (b != null) return new Object[]{b, d.group(1).isEmpty() ? null : d.group(1)};
            }
            Path p = null;
            try { p = baseDir == null ? Path.of(x) : baseDir.resolve(x); } catch (java.nio.file.InvalidPathException ignored) { }
            if (p != null && !x.isEmpty() && Files.isRegularFile(p)) {
                try {
                    if (Files.size(p) > MAX_BYTES)
                        throw new OoaException("VAR.TOO_LARGE", "File " + p + " is " + BinaryValue.size(Files.size(p)) + ": too large to hold in a " + type + " variable",
                                "Keep large files as files (a File variable) and pass their path.");
                    return new Object[]{Files.readAllBytes(p), mimeOf(p)};
                } catch (java.io.IOException e) {
                    throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read file " + p + " into a " + type + " variable: " + e.getMessage(), fix);
                }
            }
            byte[] b = x.isEmpty() ? null : base64(x);
            if (b != null) return new Object[]{b, null};
            throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read " + (x.length() < 300 ? "'" + x + "'" : "the text") + " as " + type
                    + ": it is not an existing file" + (p != null ? " (" + p + ")" : "") + " nor Base64 text", fix);
        }
        throw new OoaException("VAR.TYPE_MISMATCH", "Cannot read a " + typeName(v) + " value as " + type, fix);
    }

    private static final Pattern DATA_URI = Pattern.compile("^data:([^;,]*)(?:;[^,]*)?;base64,(.*)$", Pattern.DOTALL);

    /** Strict Base64 (standard alphabet, whitespace ignored); null when the text is not Base64. */
    public static byte[] base64(String s) {
        String x = s.replaceAll("\\s+", "");
        if (x.isEmpty() || x.length() % 4 != 0 || !x.matches("[A-Za-z0-9+/]*={0,2}")) return null;
        try { return Base64.getDecoder().decode(x); } catch (IllegalArgumentException e) { return null; }
    }

    static String mimeOf(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "gif" -> "image/gif"; case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf"; case "txt", "log" -> "text/plain"; case "csv" -> "text/csv"; case "json" -> "application/json";
            case "xml" -> "application/xml"; case "html", "htm" -> "text/html"; case "zip" -> "application/zip";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> BinaryValue.OCTET_STREAM;
        };
    }

    public static Map<String, Object> table(List<String> columns, List<Object> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("columns", new ArrayList<>(columns));
        t.put("rows", rows);
        return t;
    }

    /** Equality with decimal semantics (1.0 == 1) used by ==, contains and in. */
    public static boolean looseEquals(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!looseEquals(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) return false;
            for (Object k : ma.keySet()) if (!looseEquals(ma.get(k), mb.get(k))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    /** Serialisable form for events, outputs and tool results. Secrets stay masked. */
    public static Object toJsonable(Object v) { return jsonable(v, false); }

    /**
     * Like {@link #toJsonable} but an Image or Binary becomes its summary ("Image 640x480", "Binary 1.2 KB"), so events,
     * logs and the debugger never carry the bytes. The same as toJsonable for every other value.
     */
    public static Object toDisplay(Object v) { return jsonable(v, true); }

    private static Object jsonable(Object v, boolean display) {
        if (v instanceof SecureString) return SecureString.MASK;
        if (v instanceof LocalDateTime t) return t.toString();
        if (v instanceof LocalTime t) return formatTime(t);
        if (v instanceof Duration d) return formatTimeSpan(d);
        if (v instanceof ImageValue i) return display ? i.toString() : i.toJson();
        if (v instanceof BinaryValue b) return display ? b.toString() : b.toJson();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, x) -> o.put(String.valueOf(k), jsonable(x, display)));
            return o;
        }
        if (v instanceof Collection<?> c) { List<Object> o = new ArrayList<>(); for (Object x : c) o.add(jsonable(x, display)); return o; }
        if (v instanceof Number n && !(v instanceof BigDecimal)) return new BigDecimal(n.toString());
        return v;
    }

    public static String safeRepr(Object v) {
        String s = JsonLite.write(toDisplay(v));
        return s.length() <= 200 ? s : s.substring(0, 197) + "...";
    }

    /** Deep copy of JSON-like data (defaults must never be shared between runs). */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put((String) k, deepCopy(x))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(deepCopy(x)); return o; }
        return v;
    }
}
