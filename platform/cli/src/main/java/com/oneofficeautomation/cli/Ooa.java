package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * OOA CLI (Java). Commands:
 *   ooa manifest [--summary]          print package manifests (what Studio renders)
 *   ooa validate FLOW...              validate Flows against schema and manifests
 *   ooa run FLOW [-i key=value]...    run a Flow file
 *   ooa conformance DIR               run the language-neutral conformance suite
 *   ooa studio [--workspace DIR]      start the Studio preview on http://localhost:8710
 * picocli and GraalVM native image replace this hand-rolled parser in a later slice.
 */
public final class Ooa {
    private Ooa() {}

    static JsonCodec codec() {
        try { return (JsonCodec) Class.forName("com.oneofficeautomation.adapters.jackson.JacksonJsonCodec").getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException | LinkageError e) { return JsonCodec.LITE; }
    }

    static FlowSchemaValidator schemaValidator() {
        try {
            return (FlowSchemaValidator) Class.forName("com.oneofficeautomation.adapters.jackson.NetworkntFlowSchemaValidator")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("warning: JSON Schema adapter not on classpath; running semantic validation only");
            return FlowSchemaValidator.NONE;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(1); }
        int code;
        try {
            code = switch (args[0]) {
                case "manifest" -> manifest(Arrays.asList(args).contains("--summary"));
                case "validate" -> validate(Arrays.copyOfRange(args, 1, args.length));
                case "run" -> run(Arrays.copyOfRange(args, 1, args.length));
                case "conformance" -> conformance(Path.of(args.length > 1 ? args[1] : "../contracts/conformance/cases"));
                case "studio" -> studio(Arrays.copyOfRange(args, 1, args.length));
                default -> { usage(); yield 1; }
            };
        } catch (OoaException e) {
            System.err.println("error: " + e);
            code = 2;
        }
        System.exit(code);
    }

    static void usage() {
        System.err.println("usage: ooa manifest [--summary] | validate FLOW... | run FLOW [-i key=value]... | conformance [DIR]"
                + " | studio [--workspace DIR] [--port N]");
    }

    static int studio(String[] a) throws Exception {
        Path workspace = Path.of(System.getProperty("user.home"), "OOA Workspace");
        int port = 8710;
        for (int i = 0; i < a.length - 1; i++) {
            if (a[i].equals("--workspace")) workspace = Path.of(a[++i]);
            else if (a[i].equals("--port")) port = Integer.parseInt(a[++i]);
        }
        new StudioServer(workspace, port, Path.of("../examples/refund")).start();
        Thread.currentThread().join();
        return 0;
    }

    static int manifest(boolean summary) {
        List<Map<String, Object>> ms = FirstPartyPackages.registry().allManifests();
        if (summary) for (Map<String, Object> m : ms) {
            List<Object> ids = new ArrayList<>();
            for (Object a : (List<?>) m.get("actions")) ids.add(((Map<?, ?>) a).get("id"));
            System.out.println(m.get("id") + "@" + m.get("version") + ": " + ids);
        }
        else System.out.println(codec().write(ms, true));
        return 0;
    }

    @SuppressWarnings("unchecked")
    static int validate(String[] files) throws Exception {
        FlowValidator v = new FlowValidator(FirstPartyPackages.registry(), schemaValidator());
        int bad = 0;
        for (String f : files) {
            List<Map<String, Object>> problems = v.validate((Map<String, Object>) codec().parse(Files.readString(Path.of(f))));
            System.out.println(f + ": " + (problems.isEmpty() ? "OK" : problems.size() + " problem(s)"));
            for (Map<String, Object> p : problems) {
                bad++;
                Object loc = p.get("stepId") != null ? p.get("stepId") : p.get("path");
                System.out.println("  [" + loc + "] " + p.get("message") + (p.get("suggestedFix") != null ? "  -> " + p.get("suggestedFix") : ""));
            }
        }
        return bad == 0 ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        JsonCodec json = codec();
        Map<String, Object> flow = (Map<String, Object>) json.parse(Files.readString(Path.of(a[0])));
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (int i = 1; i < a.length; i++) {
            if ((a[i].equals("-i") || a[i].equals("--input")) && i + 1 < a.length) {
                String kv = a[++i];
                int eq = kv.indexOf('=');
                if (eq < 0) throw new OoaException("CLI.BAD_INPUT", "--input expects key=value, got '" + kv + "'");
                String raw = kv.substring(eq + 1);
                Object val;
                try { val = json.parse(raw); } catch (RuntimeException e) { val = raw; }
                inputs.put(kv.substring(0, eq), val);
            }
        }
        Runner runner = Runner.builder(FirstPartyPackages.registry()).sink(new EventSink.ConsoleSink(false))
                .schemaValidator(schemaValidator()).workDir(Path.of(".")).build();
        RunResult r = runner.run(flow, inputs);
        System.out.println(json.write(r.toMap(), true));
        return r.ok() ? 0 : 2;
    }

    @SuppressWarnings("unchecked")
    static int conformance(Path dir) throws Exception {
        PackageRegistry reg = FirstPartyPackages.registry();
        CredentialVault vault = new CredentialVault.InMemory().put("test", "app", "password", "s3cr3t");
        List<Path> cases;
        try (Stream<Path> s = Files.list(dir)) { cases = s.filter(p -> p.toString().endsWith(".json")).sorted().toList(); }
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Path c : cases) {
            Map<String, Object> tc = (Map<String, Object>) JsonLite.parse(Files.readString(c));
            Path work = Files.createTempDirectory("ooa-conf");
            Runner runner = Runner.builder(reg).service("vault", vault).workDir(work).schemaValidator(schemaValidator()).build();
            RunResult r = runner.run((Map<String, Object>) tc.get("flow"), (Map<String, Object>) tc.get("inputs"));
            Map<String, Object> exp = (Map<String, Object>) tc.get("expect");
            List<String> why = new ArrayList<>();
            if (!Objects.equals(exp.get("status"), r.status())) why.add("status " + r.status() + " != " + exp.get("status") + " " + r.error());
            if (exp.containsKey("outputs")) {
                Object got = norm(JsonLite.parse(JsonLite.write(r.toMap().get("outputs"))));
                if (!Objects.equals(got, norm(exp.get("outputs")))) why.add("outputs " + JsonLite.write(got) + " != " + JsonLite.write(exp.get("outputs")));
            }
            if (exp.containsKey("errorCode") && (r.error() == null || !exp.get("errorCode").equals(r.error().get("code"))))
                why.add("errorCode " + (r.error() == null ? null : r.error().get("code")) + " != " + exp.get("errorCode"));
            if (exp.containsKey("errorStepId") && (r.error() == null || !exp.get("errorStepId").equals(r.error().get("stepId"))))
                why.add("errorStepId " + (r.error() == null ? null : r.error().get("stepId")) + " != " + exp.get("errorStepId"));
            String name = c.getFileName().toString().replace(".json", "");
            if (why.isEmpty()) { passed++; System.out.println("PASS " + name); }
            else { failures.add(name); System.out.println("FAIL " + name + ": " + String.join("; ", why)); }
        }
        System.out.println(passed + "/" + cases.size() + " conformance cases passed");
        return failures.isEmpty() ? 0 : 1;
    }

    /** Numbers compare by value (2.20 == 2.2). */
    static Object norm(Object v) {
        if (v instanceof BigDecimal d) return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        if (v instanceof Map<?, ?> m) { Map<Object, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put(k, norm(x))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(norm(x)); return o; }
        return v;
    }
}
