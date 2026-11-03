package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.EventSink;
import com.oneofficeautomation.runner.RunEvent;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Add log fields and Remove log fields (gap G16): every log line between them carries the fields. */
class LogFieldsTest {

    @SuppressWarnings("unchecked")
    @Test void fieldsGoOnEveryLogLineUntilRemoved() throws Exception {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"inv","type":"String"},{"name":"left","type":"Dictionary","direction":"output"}],
             "steps":[
               {"id":"s","action":"core/set","args":{"variable":"inv","value":"INV-7"}},
               {"id":"a","action":"core/log","args":{"message":"before"}},
               {"id":"b","action":"ooa.log/add_log_fields","args":{"fields":{"invoice":"$inv","customer":"Acme"}}},
               {"id":"c","action":"core/log","args":{"message":"working"}},
               {"id":"d","action":"ooa.log/log_to_command_center","args":{"message":"from an action","level":"WARN"}},
               {"id":"e","action":"ooa.log/add_log_fields","args":{"fields":{"invoice":"INV-8"}}},
               {"id":"f","action":"core/log","args":{"message":"next invoice"}},
               {"id":"g","action":"ooa.log/remove_log_fields","args":{"names":["customer"]},"out":{"fields":"left"}},
               {"id":"h","action":"core/log","args":{"message":"one field"}},
               {"id":"i","action":"ooa.log/remove_log_fields","args":{}},
               {"id":"j","action":"core/log","args":{"message":"after"}}]}""");
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(Files.createTempDirectory("ooa-logf")).sink(sink).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        Map<String, Object> byMessage = new LinkedHashMap<>();
        for (RunEvent e : sink.ofType("log")) byMessage.put(String.valueOf(e.data().get("message")), e.data().get("fields"));
        assertNull(byMessage.get("before"), "no fields before Add log fields: the event is as before");
        assertEquals(Map.of("invoice", "INV-7", "customer", "Acme"), byMessage.get("working"));
        assertEquals(Map.of("invoice", "INV-7", "customer", "Acme"), byMessage.get("from an action"), "lines logged by actions carry them too");
        assertEquals(Map.of("invoice", "INV-8", "customer", "Acme"), byMessage.get("next invoice"), "adding a name again changes its value");
        assertEquals(Map.of("invoice", "INV-8"), byMessage.get("one field"));
        assertEquals(Map.of("invoice", "INV-8"), r.outputs().get("left"));
        assertNull(byMessage.get("after"), "all removed");
    }

    @SuppressWarnings("unchecked")
    @Test void badNamesAndEmptyInputAreRefused() throws Exception {
        for (String args : List.of("{\"fields\":{\"1st\":\"x\"}}", "{\"fields\":{\"has space\":\"x\"}}")) {
            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
                {"kind":"TaskBot","id":"x","name":"x","steps":[{"id":"a","action":"ooa.log/add_log_fields","args":%s}]}""".formatted(args));
            RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(Files.createTempDirectory("ooa-logf")).build().run(flow, Map.of());
            assertEquals("LOG.BAD_FIELD", r.error().get("code"), args);
        }
    }
}
