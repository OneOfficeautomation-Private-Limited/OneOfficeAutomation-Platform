package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** I1 (command review, batch 9): an AI step that sets nothing of its own uses the organisation's default model connection. */
class DefaultModelConnectionTest {
    @Test void aStepWithNothingSetUsesTheDefaultConnection() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"from the default\"}}]}"));
            Path w = tmp();
            Files.writeString(w.resolve("model-connections.json"), "{\"default\":\"company\",\"connections\":[{\"name\":\"company\",\"provider\":\"openai_compatible\","
                    + "\"model\":\"m-1\",\"base_url\":\"" + f.url() + "/v1\",\"api_key\":{\"$credential\":\"ai/company/key\"}}]}");
            var ctx = PackagesTestSupport.ctx(w, Map.of("vault", new CredentialVault.InMemory().put("ai", "company", "key", "k-company")));
            GenAiPackage g = new GenAiPackage();
            // the step leaves connection, model, key and address empty; "openai" is only the field's default value
            assertEquals("from the default", g.prompt(ctx, "Hi", null, "openai", null, null, null, null, 0, 10, false, null, null).get("text"));
            Call call = f.last("POST", "/v1/chat/completions");
            assertEquals("Bearer k-company", call.header("Authorization"));
            assertTrue(call.text().contains("\"m-1\""), call.text());
        }
    }

    @Test void aStepWithItsOwnSettingsKeepsThem() throws Exception {
        Path w = tmp();
        Files.writeString(w.resolve("model-connections.json"), "{\"default\":\"company\",\"connections\":[{\"name\":\"company\",\"provider\":\"openai\",\"model\":\"m-1\"}]}");
        var ctx = PackagesTestSupport.ctx(w, Map.of());
        // a model set on the step, and no key: the step's own settings are used (and fail for want of a key), not the default
        assertEquals("AI.NO_KEY", assertThrows(ActionException.class,
                () -> new GenAiPackage().prompt(ctx, "Hi", null, "openai", null, "gpt-4o", null, null, 0, 10, false, null, null)).code());
    }
}
