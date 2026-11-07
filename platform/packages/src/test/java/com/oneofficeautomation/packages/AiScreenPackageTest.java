package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G57: a control found from a plain description by a vision model, its place turned into screen pixels, then clicked. */
class AiScreenPackageTest {
    @TempDir Path w;
    final AiScreenPackage.Screen real = AiScreenPackage.screen;
    @AfterEach void restore() { AiScreenPackage.screen = real; }

    @Test void findsClicksAndRefusesWeakAnswers() throws Exception {
        List<int[]> clicks = new ArrayList<>();
        List<String> typed = new ArrayList<>();
        AiScreenPackage.screen = new AiScreenPackage.Screen() {
            public BufferedImage capture() { return new BufferedImage(3200, 1800, BufferedImage.TYPE_INT_RGB); }   // made smaller to 1600 for the model
            public void click(int x, int y, boolean dbl, boolean right) { clicks.add(new int[]{x, y, dbl ? 2 : 1, right ? 1 : 0}); }
            public void type(ActionContext ctx, String text) { typed.add(text); }
        };
        String[] answer = {"{\"found\": true, \"left\": 0.5, \"top\": 0.25, \"width\": 0.1, \"height\": 0.05, \"confidence\": 0.9, \"label\": \"Submit button\", \"text\": \"Submit\"}"};
        List<String> bodies = new ArrayList<>();
        try (ConnectorsTest.Fake llm = new ConnectorsTest.Fake()) {
            llm.on("POST /v1/chat/completions", c -> { bodies.add(c.text());
                return ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":" + JsonLite.write(answer[0]) + "}}]}"); });
            ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
            SecureString key = new SecureString("sk", "k");
            AiScreenPackage p = new AiScreenPackage();
            Map<String, Object> f = p.find(ctx, "the Submit button", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.6);
            assertEquals(true, f.get("found"));
            assertEquals(1760, f.get("x"), "the middle of the box in screen pixels: (0.5 + 0.05) * 3200");
            assertEquals(495, f.get("y"));
            assertEquals(Map.of("left", 1600, "top", 450, "width", 320, "height", 90), f.get("box"));
            assertTrue(bodies.get(0).contains("Find: the Submit button") && bodies.get(0).contains("data:image/png;base64,"), "the picture goes with the description");

            Map<String, Object> c = p.click(ctx, "the Submit button", "double", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.7);
            assertEquals("Submit button", c.get("label"));
            assertArrayEquals(new int[]{1760, 495, 2, 0}, clicks.get(0));
            assertEquals(Map.of("text", "Submit", "found", true), p.read(ctx, "the Submit button", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.6));
            p.type(ctx, "the Submit button", "INV-77", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.7);
            assertEquals(List.of("INV-77"), typed);
            assertEquals(2, clicks.size(), "it clicks into the field first");
            clicks.clear();

            // not sure enough, outside the picture, or not found: nothing is clicked
            answer[0] = "{\"found\": true, \"left\": 0.5, \"top\": 0.25, \"width\": 0.1, \"height\": 0.05, \"confidence\": 0.4}";
            assertEquals("VISION.NOT_FOUND", assertThrows(ActionException.class, () -> p.click(ctx, "x", "single", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.7)).code());
            answer[0] = "{\"found\": true, \"left\": 0.95, \"top\": 0.25, \"width\": 0.2, \"height\": 0.05, \"confidence\": 0.99}";
            assertEquals(false, p.find(ctx, "x", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.6).get("found"));
            answer[0] = "{\"found\": false, \"confidence\": 0.9}";
            assertEquals(-1, p.find(ctx, "x", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.6).get("x"));
            answer[0] = "I think it is near the top";
            assertEquals("VISION.BAD_ANSWER", assertThrows(ActionException.class, () -> p.find(ctx, "x", null, "openai_compatible", "vision-1", key, llm.url() + "/v1", 0.6)).code());
            assertEquals(0, clicks.size(), "no click for a weak or missing answer");
        }
    }
}
