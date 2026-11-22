package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** G6 rows for the AI agent: goal from a file, more steps for new steps, a workspace folder and credentials handed to tools. */
class AiAgentG6Test {
    static final SecureString KEY = new SecureString("sk-test", "k");
    static final SecureString ERP = new SecureString("erp-Secret-991", "erp");

    @TempDir Path w;

    @SuppressWarnings("unchecked")
    static final Map<String, Object> EXPORT = (Map<String, Object>) JsonLite.parse("""
        {"kind":"ApiTask","id":"export_report","name":"Export report","description":"Export the ERP report to a file.",
         "variables":[{"name":"report","type":"File","direction":"input","required":true},
                      {"name":"erp_password","type":"Credential","direction":"input","required":true},
                      {"name":"workspace_folder","type":"String","direction":"input"},
                      {"name":"rows","type":"Number","direction":"output"}],
         "steps":[]}""");

    Map<String, Object> agent(ActionContext ctx, String goal, String goalFile, String llm, String workspace, Map<String, Object> credentials, List<Object> knowledge) {
        return new AiAgentPackage().run(ctx, goal, "Be accurate.", List.of("export_report"), null, "openai", null, "m", KEY, llm + "/v1",
                6, 60000, false, null, null, null, null, null, false, 0, 0, null, 600, "off", "medium", null, null, 600, knowledge, null, null, null,
                goalFile, workspace, credentials);
    }

    static String toolCall(String args) {
        return "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"export_report\",\"arguments\":"
                + JsonLite.write(args) + "}}]}}]}";
    }

    @SuppressWarnings("unchecked")
    @Test void goalFromAFileWorkspaceAndCredentialsByReference() throws Exception {
        Path ws = Files.createDirectories(w.resolve("Work/claims"));
        Files.write(w.resolve("goal.txt"), ("﻿Export the March report " + "é").getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> invoked = new ArrayList<>();
        AutomationRepository repo = (id, v) -> { if (id.equals("export_report")) return EXPORT; throw new OoaException("REPO.NOT_FOUND", id); };
        BotInvoker inv = (id, in) -> { invoked.add(new LinkedHashMap<>(in)); return null; };
        List<String> sent = new ArrayList<>();
        int[] turn = {0};
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> {
                sent.add(c.text());
                turn[0]++;
                if (turn[0] == 1) return ok(toolCall("{\"report\":\"../../escape.txt\"}"));
                if (turn[0] == 2) return ok(toolCall("{\"report\":\"out/march.xlsx\"}"));
                return ok("{\"choices\":[{\"message\":{\"content\":\"Done.\"}}]}");
            });
            BotInvoker counting = (id, in) -> { inv.invoke(id, in); return new RunResult("r1", id, "success", Map.of("rows", 3), null, 1, "h", 1); };
            var ctx = PackagesTestSupport.ctx(w, Map.of("repository", repo, "bot_invoker", counting));
            Map<String, Object> r = agent(ctx, null, "goal.txt", f.url(), "Work/claims", Map.of("erp_password", ERP), null);
            assertEquals("completed", r.get("status"), String.valueOf(r));

            Map<String, Object> first = (Map<String, Object>) JsonLite.parse(sent.get(0));
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) first.get("messages");
            assertEquals("Export the March report é", msgs.get(1).get("content"), "goal read from the file as UTF-8, without the byte-order mark");
            Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((List<?>) first.get("tools")).get(0)).get("function")).get("parameters");
            assertEquals(Set.of("report"), ((Map<?, ?>) params.get("properties")).keySet(), "the model sees neither the credential nor the folder input");

            assertEquals(1, invoked.size(), "the path outside the folder was refused, the one inside ran");
            assertTrue(sent.get(1).contains("outside the folder"), "the model is told why");
            Map<String, Object> in = invoked.get(0);
            assertEquals(ws.resolve("out/march.xlsx").toAbsolutePath().normalize().toString(), in.get("report"));
            assertEquals(ws.toAbsolutePath().normalize().toString(), in.get("workspace_folder"));
            assertSame(ERP, in.get("erp_password"), "the credential is handed over as it is");
            assertTrue(sent.stream().noneMatch(s -> s.contains(ERP.reveal())), "the secret never reaches the model");
            assertTrue(String.valueOf(r.get("trace")).contains("path outside the workspace folder"));
        }
    }

    @Test void inputChecks() throws Exception {
        AutomationRepository repo = (id, v) -> { if (id.equals("export_report")) return EXPORT; throw new OoaException("REPO.NOT_FOUND", id); };
        var ctx = PackagesTestSupport.ctx(w, Map.of("repository", repo, "bot_invoker", (BotInvoker) (id, in) -> null));
        Files.createDirectories(w.resolve("ws"));
        Files.writeString(w.resolve("g.txt"), "goal");
        assertEquals("AGENT.TOOL_NEEDS_SECRET", assertThrows(ActionException.class, () -> agent(ctx, "Go", null, "http://127.0.0.1:1", null, null, null)).code());
        assertEquals("AGENT.BAD_OPTION", assertThrows(ActionException.class, () -> agent(ctx, "Go", "g.txt", "http://127.0.0.1:1", null, null, null)).code());
        assertEquals("AGENT.NO_GOAL", assertThrows(ActionException.class, () -> agent(ctx, null, null, "http://127.0.0.1:1", null, null, null)).code());
        assertEquals("AGENT.GOAL_NOT_FOUND", assertThrows(ActionException.class, () -> agent(ctx, null, "missing.txt", "http://127.0.0.1:1", null, null, null)).code());
        assertEquals("AGENT.WORKSPACE_NOT_FOUND", assertThrows(ActionException.class, () -> agent(ctx, "Go", null, "http://127.0.0.1:1", "nope", Map.of("erp_password", ERP), null)).code());
        assertEquals("AGENT.BAD_OPTION", assertThrows(ActionException.class, () -> agent(ctx, "Go", null, "http://127.0.0.1:1", null, Map.of("erp_password", "plain text"), null)).code());
        assertEquals("AGENT.OUTSIDE_WORKSPACE", assertThrows(ActionException.class, () -> agent(ctx, "Go", null, "http://127.0.0.1:1", "ws", Map.of("erp_password", ERP), List.of("other/k.kidx"))).code());
        // a vault reference works like a Credential
        var vault = new CredentialVault.InMemory().put("erp", "main", "password", "from-vault");
        var vctx = PackagesTestSupport.ctx(w, Map.of("vault", vault));
        assertEquals("from-vault", AiAgentPackage.credentials(vctx, Map.of("erp_password", Map.of("$credential", "erp/main/password"))).get("erp_password").reveal());
    }

    @Test void maxStepsStartsAt25ForNewStepsAndThePanelHoldsTheNewInputs() throws Exception {
        Method run = null;
        for (Method m : AiAgentPackage.class.getMethods()) if (m.isAnnotationPresent(Action.class)) run = m;
        assertNotNull(run);
        for (var anns : run.getParameterAnnotations()) for (var a : anns) if (a instanceof Input in && in.name().equals("max_steps")) {
            assertEquals("8", in.defaultValue(), "steps saved before keep running as they did");
            assertEquals("25", in.newStepDefault());
            assertTrue(in.description().contains("25"));
        }
        PackageIntrospector.inspect(AiAgentPackage.class);   // every input placed once in the panel
        assertTrue(run.getAnnotation(Action.class).panel().contains("(Goal: Typed here = goal / From a file = goal_file)"));
    }
}
