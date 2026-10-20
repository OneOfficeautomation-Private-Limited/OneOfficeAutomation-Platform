package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Agent Studio's custom connector endpoints: import (JSON and YAML), list, get and delete. */
class StudioConnectorTest {
    static final String SPEC_JSON = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Weather Desk\",\"version\":\"1\"},"
            + "\"servers\":[{\"url\":\"https://weather.example.test\"}],"
            + "\"paths\":{\"/forecast/{city}\":{\"get\":{\"operationId\":\"forecast\",\"parameters\":[{\"name\":\"city\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}],"
            + "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

    static final String SPEC_YAML = """
        openapi: 3.0.1
        info:
          title: Fleet Tracker
          version: "2"
        servers:
          - url: https://fleet.example.test/api
        components:
          securitySchemes:
            key:
              type: apiKey
              in: header
              name: X-Fleet-Key
        security:
          - key: []
        paths:
          /vehicles:
            get:
              operationId: listVehicles
              parameters:
                - name: page
                  in: query
                  schema:
                    type: integer
                    default: 1
              responses:
                "200":
                  description: ok
        """;

    @SuppressWarnings("unchecked")
    @Test void importListGetAndDeleteConnectors() throws Exception {
        Path ws = Files.createTempDirectory("ooa-connectors");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(ws, port, Path.of("/nonexistent")).start();
        String base = "http://127.0.0.1:" + port;

        assertEquals(List.of(), call(base, "GET", "/api/connectors", null).get("connectors"));
        Map<String, Object> imp = call(base, "POST", "/api/connectors/import", Map.of("text", SPEC_JSON));
        assertEquals(200, ((Number) imp.get("_status")).intValue(), String.valueOf(imp));
        assertEquals("connectors/weather_desk.connector.json", imp.get("path"));
        assertTrue(Files.isRegularFile(ws.resolve("connectors/weather_desk.connector.json")));

        Map<String, Object> yaml = call(base, "POST", "/api/connectors/import", Map.of("text", SPEC_YAML, "name", "fleet"));
        assertEquals(200, ((Number) yaml.get("_status")).intValue(), "YAML is read where a YAML parser ships: " + yaml);
        Map<String, Object> fleet = (Map<String, Object>) yaml.get("connector");
        assertEquals("https://fleet.example.test/api", fleet.get("server"));
        Map<String, Object> op = (Map<String, Object>) ((List<Object>) fleet.get("operations")).get(0);
        assertEquals("listVehicles", op.get("id"));
        assertEquals(1, ((Number) ((Map<String, Object>) ((List<Object>) op.get("parameters")).get(0)).get("default")).intValue());

        List<Object> list = (List<Object>) call(base, "GET", "/api/connectors", null).get("connectors");
        assertEquals(List.of("fleet", "weather_desk"), list.stream().map(o -> ((Map<String, Object>) o).get("name")).toList());

        Map<String, Object> got = (Map<String, Object>) call(base, "GET", "/api/connectors/weather_desk", null).get("connector");
        assertEquals("Weather Desk", got.get("title"));

        Map<String, Object> dup = call(base, "POST", "/api/connectors/import", Map.of("text", SPEC_JSON, "overwrite", false));
        assertEquals(409, ((Number) dup.get("_status")).intValue());
        Map<String, Object> bad = call(base, "POST", "/api/connectors/import", Map.of("text", "{\"hello\":1}"));
        assertEquals("CONNECTOR.UNKNOWN_FORMAT", ((Map<String, Object>) bad.get("error")).get("code"));
        assertEquals(400, ((Number) call(base, "GET", "/api/connectors/..%2Fsecrets", null).get("_status")).intValue(), "names cannot leave the folder");

        assertEquals("weather_desk", call(base, "DELETE", "/api/connectors/weather_desk", null).get("deleted"));
        assertEquals(404, ((Number) call(base, "GET", "/api/connectors/weather_desk", null).get("_status")).intValue());
        assertEquals(404, ((Number) call(base, "DELETE", "/api/connectors/weather_desk", null).get("_status")).intValue());
    }

    static Map<String, Object> call(String base, String method, String path, Object body) throws Exception {
        return StudioControlRoomTest.call(base, method, path, body, null);
    }
}
