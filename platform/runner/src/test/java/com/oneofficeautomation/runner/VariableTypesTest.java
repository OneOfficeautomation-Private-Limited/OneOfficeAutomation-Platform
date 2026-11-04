package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Gap G22: the Time, TimeSpan, Image and Binary variable types, end to end. Existing types must behave as before. */
class VariableTypesTest {
    /** A 3x2 red PNG. */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEklEQVR4nGP4z8AAQVDqPwMDAEHSBfsl0XwmAAAAAElFTkSuQmCC";

    @OoaPackage(id = "acme.pics", name = "Pictures", version = "1.0.0", description = "Picture fields for tests.", publisher = "acme")
    public static final class Pics {
        @Action(id = "size", name = "Size", description = "Bytes in a picture file.")
        @Output(name = "bytes", type = OoaType.NUMBER, description = "File size.")
        @Output(name = "path", type = OoaType.STRING, description = "The path the action was given.")
        public Map<String, Object> size(ActionContext ctx, @Input(name = "image", type = OoaType.FILE, description = "Picture.") String image) throws Exception {
            return Map.of("bytes", Files.size(Path.of(image)), "path", image);
        }

        @Action(id = "read", name = "Read", description = "Reads a file.")
        @Output(name = "bytes", type = OoaType.NUMBER, description = "File size.")
        public Map<String, Object> read(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "A file.") String path) throws Exception {
            return Map.of("bytes", Files.size(Path.of(path)));
        }

        @Action(id = "snap", name = "Snap", description = "Returns an Image, a Time and a TimeSpan.")
        @Output(name = "img", type = OoaType.IMAGE, description = "Picture.")
        @Output(name = "at", type = OoaType.TIME, description = "A time.")
        @Output(name = "took", type = OoaType.TIMESPAN, description = "A length of time.")
        @Output(name = "raw", type = OoaType.BINARY, description = "Bytes.")
        public Map<String, Object> snap(ActionContext ctx) {
            return Map.of("img", ImageValue.read(Base64.getDecoder().decode(PNG)), "at", LocalTime.of(8, 0), "took", Duration.ofSeconds(90),
                    "raw", new BinaryValue(new byte[]{1, 2, 3}, null));
        }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation");
        PackageSigning.PublisherKey acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Pics.class, PackageSigning.sign(Pics.class, acme));
        return r;
    }

    static Object ev(String src, Map<String, Object> vars) { return Expressions.evaluate(src, vars::get); }

    static OoaException fails(Runnable r) { return assertThrows(OoaException.class, r::run); }

    // ---------- coercion ----------
    @Test
    void timeFromText() {
        assertEquals(LocalTime.of(14, 30), Values.coerce("14:30", "Time", "t"));
        assertEquals(LocalTime.of(14, 30, 5), Values.coerce("14:30:05", "Time", "t"));
        assertEquals(LocalTime.of(9, 5), Values.coerce("9:05", "Time", "t"));
        assertEquals(LocalTime.of(14, 30), Values.coerce("2:30 PM", "Time", "t"));
        assertEquals(LocalTime.of(0, 0), Values.coerce("12:00 am", "Time", "t"));
        assertEquals(LocalTime.of(14, 30, 5, 500_000_000), Values.coerce("14:30:05.5", "Time", "t"));
        assertEquals(LocalTime.of(7, 45), Values.coerce("2026-10-18T07:45:00", "Time", "t"));
        assertEquals(LocalTime.of(3, 4, 5), Values.coerce(LocalDateTime.of(2026, 1, 2, 3, 4, 5), "Time", "t"));
        for (Object bad : List.of("25:00", "13:00 PM", "soon", BigDecimal.ONE, List.of()))
            assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(bad, "Time", "t")).code(), String.valueOf(bad));
    }

    @Test
    void timeSpanFromText() {
        Map<String, Long> cases = new LinkedHashMap<>();
        cases.put("2h 15m", 8100L); cases.put("1d 3h", 97200L); cases.put("PT2H15M", 8100L); cases.put("pt2h15m", 8100L); cases.put("P1DT3H", 97200L);
        cases.put("01:30:00", 5400L); cases.put("01:30", 5400L); cases.put("1.02:03:04", 93784L); cases.put("90", 90L); cases.put("-PT30M", -1800L);
        cases.put("-2h", -7200L); cases.put("1.5 hours", 5400L); cases.put("1h, 30 minutes and 5s", 5405L); cases.put("2w", 1209600L); cases.put("1h30m", 5400L);
        cases.forEach((text, secs) -> assertEquals(Duration.ofSeconds(secs), Values.coerce(text, "TimeSpan", "s"), text));
        assertEquals(Duration.ofMillis(1500), Values.coerce(new BigDecimal("1.5"), "TimeSpan", "s"));
        assertEquals(Duration.ofMillis(250), Values.coerce("250ms", "TimeSpan", "s"));
        for (Object bad : List.of("2 parsecs", "10:75", "P", "abc", true, ""))
            assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(bad, "TimeSpan", "s")).code(), String.valueOf(bad));
    }

    @Test
    void binaryFromEverySource(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.pdf");
        Files.write(f, new byte[]{0, 1, (byte) 255});
        BinaryValue fromFile = (BinaryValue) Values.coerce(f.toString(), "Binary", "b");
        assertArrayEquals(new byte[]{0, 1, (byte) 255}, fromFile.bytes());
        assertEquals("application/pdf", fromFile.mime());
        assertEquals(fromFile.bytes().length, ((BinaryValue) Values.coerce("a.pdf", "Binary", "b", dir)).length(), "relative to the run's folder");
        assertArrayEquals(new byte[]{0, 1, (byte) 255}, ((BinaryValue) Values.coerce(List.of(BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal(255)), "Binary", "b")).bytes());
        assertEquals(new BinaryValue("hi".getBytes(), "text/plain"), Values.coerce("data:text/plain;base64,aGk=", "Binary", "b"));
        assertEquals(new BinaryValue("hi".getBytes(), null), Values.coerce("aGk=", "Binary", "b"));
        assertEquals(new BinaryValue("hi".getBytes(), "text/plain"), Values.coerce(Map.of("$binary", "aGk=", "mime", "text/plain"), "Binary", "b"));
        assertEquals("image/png", ((BinaryValue) Values.coerce(Values.coerce(Map.of("$binary", PNG), "Image", "i"), "Binary", "b")).mime());
        assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(List.of(BigDecimal.ONE, new BigDecimal(256)), "Binary", "b")).code());
        assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(dir.resolve("missing.bin").toString(), "Binary", "b")).code());
        assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(Map.of("$binary", "***"), "Binary", "b")).code());
        assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(BigDecimal.TEN, "Binary", "b")).code());
    }

    @Test
    void imageFromFileBinaryAndOtherFormats(@TempDir Path dir) throws Exception {
        byte[] png = Base64.getDecoder().decode(PNG);
        Path f = dir.resolve("red.png");
        Files.write(f, png);
        ImageValue img = (ImageValue) Values.coerce(f.toString(), "Image", "i");
        assertEquals(3, img.width()); assertEquals(2, img.height());
        assertArrayEquals(png, img.bytes(), "PNG bytes are kept as they are");
        assertEquals(img, Values.coerce(new BinaryValue(png, null), "Image", "i"));
        // a JPEG is read and stored as PNG
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(5, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "jpg", jpg);
        ImageValue fromJpeg = (ImageValue) Values.coerce(new BinaryValue(jpg.toByteArray(), "image/jpeg"), "Image", "i");
        assertEquals(5, fromJpeg.width());
        assertEquals("image/png", fromJpeg.mime());
        assertTrue(fromJpeg.bytes()[1] == 'P');
        assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(new BinaryValue("not a picture".getBytes(), null), "Image", "i")).code());
        // an Image or Binary is not a file path
        assertEquals("VAR.TYPE_MISMATCH", fails(() -> Values.coerce(img, "File", "f")).code());
        assertEquals(new BinaryValue(png, "image/png"), Values.coerce(img, "Binary", "b"));
    }

    // ---------- JSON forms and display ----------
    @Test
    void jsonFormsRoundTrip() {
        assertEquals("14:30:00", Values.toJsonable(LocalTime.of(14, 30)));
        assertEquals("14:30:05.25", Values.toJsonable(LocalTime.of(14, 30, 5, 250_000_000)));
        assertEquals("PT2H15M", Values.toJsonable(Duration.ofMinutes(135)));
        assertEquals("PT0S", Values.toJsonable(Duration.ZERO));
        assertEquals("-PT30M", Values.toJsonable(Duration.ofMinutes(-30)));
        assertEquals("PT26H", Values.toJsonable(Values.coerce("1d 2h", "TimeSpan", "s")));
        for (String span : List.of("-1d 2h", "1.5s", "PT0S", "90m"))
            assertEquals(Values.coerce(span, "TimeSpan", "s"), Values.coerce(Values.toJsonable(Values.coerce(span, "TimeSpan", "s")), "TimeSpan", "s"), span);
        LocalTime t = LocalTime.of(23, 59, 59);
        assertEquals(t, Values.coerce(Values.toJsonable(t), "Time", "t"));
        BinaryValue b = new BinaryValue("hi".getBytes(), "text/plain");
        assertEquals(Map.of("$binary", "aGk=", "mime", "text/plain"), Values.toJsonable(b));
        assertEquals(b, Values.coerce(JsonLite.parse(JsonLite.write(Values.toJsonable(b))), "Binary", "b"));
        ImageValue img = (ImageValue) Values.coerce(Map.of("$binary", PNG), "Image", "i");
        @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) Values.toJsonable(img);
        assertEquals(PNG, j.get("$binary")); assertEquals("image/png", j.get("mime"));
        assertEquals(0, new BigDecimal(3).compareTo((BigDecimal) j.get("width"))); assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) j.get("height")));
        assertEquals(img, Values.coerce(JsonLite.parse(JsonLite.write(j)), "Image", "i"));
    }

    @Test
    void displayNeverShowsBytes() {
        ImageValue img = (ImageValue) Values.coerce(Map.of("$binary", PNG), "Image", "i");
        assertEquals("\"Image 3x2\"", Values.safeRepr(img));
        assertEquals("[\"Binary 2.0 KB\"]", Values.safeRepr(List.of(new BinaryValue(new byte[2048], null))));
        assertEquals("{\"pic\":\"Image 3x2\"}", Values.safeRepr(Map.of("pic", img)));
        assertEquals("Image 3x2", Values.text(img));
        assertEquals("Binary 1 byte", Values.text(new BinaryValue(new byte[1], null)));
        assertEquals("Image 3x2", Values.coerce(img, "String", "s"));
        assertEquals("14:30:00", Values.text(LocalTime.of(14, 30)));
        assertEquals("PT1H", Values.coerce(Duration.ofHours(1), "String", "s"));
        assertEquals("Time", Values.typeName(LocalTime.NOON)); assertEquals("TimeSpan", Values.typeName(Duration.ZERO));
        assertEquals("Image", Values.typeName(img)); assertEquals("Binary", Values.typeName(img.toBinary()));
    }

    @Test
    void existingTypesUnchanged() {
        // the display form is the JSON form for every older type; old operators keep their results and errors
        Object old = new LinkedHashMap<>(Map.of("a", List.of(BigDecimal.ONE, "x", true), "d", LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertEquals(Values.toJsonable(old), Values.toDisplay(old));
        assertEquals("ab", ev("'a' + 'b'", Map.of()));
        assertEquals(new BigDecimal("3"), ev("1 + 2", Map.of()));
        Map<String, Object> d = Map.of("d", LocalDateTime.of(2026, 1, 1, 0, 0), "e", LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThrows(OoaException.class, () -> ev("$e - $d", d), "DateTime - DateTime stays an error: use timeBetween()");
        assertThrows(OoaException.class, () -> ev("$d + 1", d));
        assertEquals(true, ev("$e > $d", d));
        assertEquals(LocalDateTime.of(2026, 1, 2, 0, 0), ev("dateAdd($d, 1)", d));
        assertEquals(new BigDecimal("3"), ev("len('abc')", Map.of()));
        assertEquals("2026-10-18", Values.coerce("2026-10-18", "File", "f"));
    }

    // ---------- expressions ----------
    @Test
    void operators() {
        Map<String, Object> v = new HashMap<>();
        v.put("t", LocalTime.of(14, 30)); v.put("s", Duration.ofMinutes(135)); v.put("d", LocalDateTime.of(2026, 10, 18, 9, 0));
        assertEquals(LocalTime.of(16, 45), ev("$t + $s", v));
        assertEquals(LocalTime.of(16, 45), ev("$s + $t", v));
        assertEquals(LocalTime.of(23, 30), ev("$t - timeSpan('15h')", v), "a time of day wraps round midnight");
        assertEquals(LocalDateTime.of(2026, 10, 18, 11, 15), ev("$d + $s", v));
        assertEquals(LocalDateTime.of(2026, 10, 18, 6, 45), ev("$d - $s", v));
        assertEquals(Duration.ofMinutes(270), ev("$s + $s", v));
        assertEquals(Duration.ZERO, ev("$s - $s", v));
        assertEquals(Duration.ofMinutes(270), ev("$s * 2", v));
        assertEquals(Duration.ofMinutes(270), ev("2 * $s", v));
        assertEquals(Duration.ofMinutes(45), ev("$s / 3", v));
        assertEquals(0, new BigDecimal(3).compareTo((BigDecimal) ev("$s / timeSpan('45m')", v)));
        assertEquals(Duration.ofMinutes(-135), ev("-$s", v));
        assertEquals(true, ev("$t > '12:00'", v));
        assertEquals(true, ev("$t == '14:30:00'", v));
        assertEquals(true, ev("$t == time('2:30 PM')", v));
        assertEquals(true, ev("$s > timeSpan('2h')", v));
        assertEquals(true, ev("$s == 'PT2H15M'", v));
        assertEquals(true, ev("$s < '3h'", v));
        assertEquals("at 14:30:00 for PT2H15M", ev("'at ' + $t + ' for ' + $s", v));
        assertEquals("EXPR.DIV_ZERO", fails(() -> ev("$s / 0", v)).code());
        for (String bad : List.of("$t - $t", "$s % 2", "$t * 2", "$d + $t", "$s * $s"))
            assertEquals("EXPR.INVALID", fails(() -> ev(bad, v)).code(), bad);
        assertFalse(Expressions.truthy(Duration.ZERO));
        assertTrue(Expressions.truthy(Duration.ofSeconds(1)));
        assertFalse(Expressions.truthy(new BinaryValue(new byte[0], null)));
        assertTrue(Expressions.truthy(LocalTime.MIDNIGHT));
    }

    @Test
    void functions() {
        Map<String, Object> v = new HashMap<>();
        v.put("s", Duration.ofMinutes(135)); v.put("d", LocalDateTime.of(2026, 10, 18, 9, 0));
        v.put("b", new BinaryValue("hi".getBytes(), null)); v.put("img", Values.coerce(Map.of("$binary", PNG), "Image", "i"));
        assertEquals(LocalTime.of(9, 5), ev("time(9, 5)", v));
        assertEquals(LocalTime.of(9, 5, 7), ev("time(9, 5, 7)", v));
        assertEquals(LocalTime.of(14, 30), ev("time('2:30 PM')", v));
        assertEquals(LocalTime.of(9, 0), ev("time($d)", v));
        assertEquals("EXPR.INVALID", fails(() -> ev("time(9.5, 0)", v)).code());
        assertEquals(Duration.ofMinutes(90), ev("timeSpan(90, 'minutes')", v));
        assertEquals(Duration.ofMinutes(90), ev("timeSpan('1h 30m')", v));
        assertEquals(Duration.ofSeconds(45), ev("timeSpan(45)", v));
        assertEquals("EXPR.INVALID", fails(() -> ev("timeSpan(1, 'months')", v)).code());
        assertEquals(0, new BigDecimal(8100).compareTo((BigDecimal) ev("totalSeconds($s)", v)));
        assertEquals(0, new BigDecimal(135).compareTo((BigDecimal) ev("totalMinutes($s)", v)));
        assertEquals(0, new BigDecimal("2.25").compareTo((BigDecimal) ev("totalHours($s)", v)));
        assertEquals(0, new BigDecimal("1.5").compareTo((BigDecimal) ev("totalDays('36h')", v)));
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) ev("totalSeconds('500ms')", v)));
        assertEquals(Duration.ofMinutes(90), ev("timeBetween('2026-10-18T09:00:00', '2026-10-18T10:30:00')", v));
        assertEquals(Duration.ofHours(-1), ev("timeBetween(time('09:00'), time('08:00'))", v));
        assertEquals(LocalDateTime.of(2026, 10, 18, 11, 15), ev("dateAdd($d, $s)", v));
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), ev("dateAdd($d, 1, 'days')", v));
        assertEquals("EXPR.INVALID", fails(() -> ev("dateAdd($d, $s, 'days')", v)).code());
        assertEquals(0, new BigDecimal(3).compareTo((BigDecimal) ev("imageWidth($img)", v)));
        assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) ev("imageHeight($img)", v)));
        assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) ev("binaryLength($b)", v)));
        assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) ev("len($b)", v)));
        assertEquals("aGk=", ev("toBase64($b)", v));
        assertEquals("aGk=", ev("toBase64('hi')", v));
        assertEquals(PNG, ev("toBase64($img)", v));
        assertEquals(new BinaryValue("hi".getBytes(), null), ev("fromBase64('aGk=')", v));
        assertEquals("text/plain", ((BinaryValue) ev("fromBase64('aGk=', 'text/plain')", v)).mime());
        assertEquals("EXPR.INVALID", fails(() -> ev("fromBase64('***')", v)).code());
    }

    // ---------- validator ----------
    static Map<String, Object> flowWith(Map<String, Object> variable, List<Object> steps) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", "TaskBot"); f.put("id", "t"); f.put("name", "t"); f.put("variables", List.of(variable)); f.put("steps", steps);
        return f;
    }

    static String problems(Map<String, Object> variable) {
        return String.valueOf(new FlowValidator(registry(), null).validate(flowWith(variable, List.of())));
    }

    @Test
    void validatorChecksDefaults() {
        assertEquals("[]", problems(Map.of("name", "t", "type", "Time", "default", "14:30")));
        assertEquals("[]", problems(Map.of("name", "s", "type", "TimeSpan", "default", "2h 15m")));
        assertEquals("[]", problems(Map.of("name", "i", "type", "Image", "default", "pictures/logo.png")));
        assertEquals("[]", problems(Map.of("name", "b", "type", "Binary", "default", Map.of("$binary", "aGk="))));
        assertTrue(problems(Map.of("name", "t", "type", "Time", "default", "half past")).contains("is not a Time"));
        assertTrue(problems(Map.of("name", "s", "type", "TimeSpan", "default", "a while")).contains("is not a TimeSpan"));
        assertTrue(problems(Map.of("name", "b", "type", "Binary", "default", Map.of("$binary", "!!"))).contains("file path or"));
        assertTrue(problems(Map.of("name", "i", "type", "Image", "default", BigDecimal.ONE)).contains("file path or"));
        assertTrue(problems(Map.of("name", "x", "type", "Duration")).contains("Unknown type"));
    }

    // ---------- runs ----------
    @Test
    void runCoercesDefaultsInputsAndOutputs(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("red.png"), Base64.getDecoder().decode(PNG));
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "t"); flow.put("name", "t");
        flow.put("variables", List.of(
                Map.of("name", "start", "type", "Time", "direction", "input"),
                Map.of("name", "span", "type", "TimeSpan", "direction", "in_out", "default", "45m"),
                Map.of("name", "end", "type", "Time", "direction", "output"),
                Map.of("name", "pic", "type", "Image", "direction", "output", "default", "red.png"),
                Map.of("name", "bytes", "type", "Number", "direction", "output"),
                Map.of("name", "snap", "type", "Image", "direction", "output"),
                Map.of("name", "at", "type", "Any", "direction", "output"),
                Map.of("name", "took", "type", "Any", "direction", "output"),
                Map.of("name", "raw", "type", "Binary", "direction", "output")));
        flow.put("steps", List.of(
                Map.of("id", "a", "action", "core/set", "args", Map.of("variable", "end", "value", Map.of("expr", "$start + $span"))),
                Map.of("id", "b", "action", "acme.pics/size", "args", Map.of("image", "$pic"), "out", Map.of("bytes", "bytes")),
                Map.of("id", "c", "action", "acme.pics/snap", "out", Map.of("img", "snap", "at", "at", "took", "took", "raw", "raw"))));
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).workDir(dir).sink(sink).build().run(flow, Map.of("start", "9:15 AM"));
        assertTrue(r.ok(), String.valueOf(r.error()));
        @SuppressWarnings("unchecked") Map<String, Object> out = (Map<String, Object>) r.toMap().get("outputs");
        assertEquals("10:00:00", out.get("end"));
        assertEquals("PT45M", out.get("span"));
        assertEquals(0, BigDecimal.valueOf(Base64.getDecoder().decode(PNG).length).compareTo((BigDecimal) out.get("bytes")), "the Image went to the picture field as a file");
        assertEquals(PNG, ((Map<?, ?>) out.get("pic")).get("$binary"));
        assertEquals(PNG, ((Map<?, ?>) out.get("snap")).get("$binary"));
        assertEquals("08:00:00", out.get("at"), "a Time an action returns is kept as a Time");
        assertEquals("PT1M30S", out.get("took"));
        assertEquals("AQID", ((Map<?, ?>) out.get("raw")).get("$binary"));
        String events = JsonLite.write(sink.events().stream().filter(e -> e.type().equals("variable.set")).map(RunEvent::toMap).toList());
        assertTrue(events.contains("Image 3x2"), events);
        assertFalse(events.contains(PNG.substring(0, 30)), "variable.set events never carry the picture's bytes");
    }

    @Test
    void anImageIsRefusedByAFieldThatIsNotAPicture(@TempDir Path dir) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "t"); flow.put("name", "t");
        flow.put("variables", List.of(Map.of("name", "pic", "type", "Image", "default", Map.of("$binary", PNG))));
        flow.put("steps", List.of(Map.of("id", "a", "action", "acme.pics/read", "args", Map.of("path", "$pic"))));
        RunResult r = Runner.builder(registry()).workDir(dir).build().run(flow, Map.of());
        assertFalse(r.ok());
        assertEquals("VAR.TYPE_MISMATCH", r.error().get("code"));
    }

    @Test
    void pictureTempFilesAreDeletedWhenTheRunEnds(@TempDir Path dir) throws Exception {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "t"); flow.put("name", "t");
        flow.put("variables", List.of(Map.of("name", "pic", "type", "Image", "default", Map.of("$binary", PNG)),
                Map.of("name", "where", "type", "String", "direction", "output")));
        flow.put("steps", List.of(Map.of("id", "a", "action", "acme.pics/size", "args", Map.of("image", "$pic"), "out", Map.of("path", "where"))));
        RunResult r = Runner.builder(registry()).workDir(dir).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        Path used = Path.of(String.valueOf(r.outputs().get("where")));
        assertTrue(used.getFileName().toString().startsWith("ooa-image-"));
        assertFalse(Files.exists(used), "the temporary picture file is deleted when the run ends");
    }

    @Test
    void schemaTypes() {
        assertEquals("time", SchemaTypes.forType(OoaType.TIME).get("format"));
        assertEquals("duration", SchemaTypes.forType(OoaType.TIMESPAN).get("format"));
        assertEquals(List.of("$binary"), SchemaTypes.forType(OoaType.IMAGE).get("required"));
        assertEquals(OoaType.BINARY, OoaType.fromFlowName("Binary"));
    }
}
