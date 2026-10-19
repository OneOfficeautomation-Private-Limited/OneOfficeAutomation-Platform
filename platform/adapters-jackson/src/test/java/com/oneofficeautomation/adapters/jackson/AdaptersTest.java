package com.oneofficeautomation.adapters.jackson;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.FlowValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AdaptersTest {
    final JacksonJsonCodec json = new JacksonJsonCodec();

    @Test void numbersAreDecimalAndOrderIsKept() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) json.parse("{\"b\": 0.1, \"a\": 3}");
        assertEquals(List.of("b", "a"), new ArrayList<>(m.keySet()));
        assertEquals(new BigDecimal("0.1"), m.get("b"));
        assertEquals(BigDecimal.valueOf(3), m.get("a"));
        assertEquals("{\"b\":0.1,\"a\":3}", json.write(m, false));
    }

    @Test void bundledSchemaMatchesContract() throws Exception {
        Path contract = Path.of(System.getProperty("ooa.contracts"), "flow-schema", "flow.v1.json");
        try (var in = getClass().getResourceAsStream("/flow.v1.json")) {
            assertEquals(json.parse(Files.readString(contract)), json.parse(new String(in.readAllBytes())),
                    "Run: gradlew :adapters-jackson:syncFlowSchema");
        }
    }

    @Test void schemaRejectsMalformedFlow() {
        var problems = new NetworkntFlowSchemaValidator().validate(Map.of("kind", "Robot", "id", "x"));
        assertFalse(problems.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test void everyExampleFlowValidates() throws Exception {
        FlowValidator v = new FlowValidator(FirstPartyPackages.registry(), new NetworkntFlowSchemaValidator());
        List<Path> flows;
        try (Stream<Path> s = Files.walk(Path.of(System.getProperty("ooa.examples")))) {
            flows = s.filter(p -> p.toString().endsWith(".flow.json")).filter(p -> p.toString().contains("refund")).toList();
        }
        assertFalse(flows.isEmpty());
        for (Path f : flows) assertEquals(List.of(), v.validate((Map<String, Object>) json.parse(Files.readString(f))), f.toString());
    }
}
