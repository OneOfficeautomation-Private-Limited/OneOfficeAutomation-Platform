package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Prompt placeholders: $name$, as Studio writes variables, and the older ${name} are filled the same way. */
class PromptPlaceholdersTest {

    @Test void fillsBothForms() {
        Map<String, Object> v = Map.of("text", "the parcel came late", "sentences", 2);
        assertEquals("Summarise in 2 sentences: the parcel came late", LlmClient.interpolate("Summarise in $sentences$ sentences: $text$", v));
        assertEquals("Summarise in 2 sentences: the parcel came late", LlmClient.interpolate("Summarise in ${sentences} sentences: ${text}", v));
        assertEquals("2 / the parcel came late", LlmClient.interpolate("$sentences$ / ${text}", v), "the two forms mix");
    }

    @Test void leavesOtherDollarsAndBracesAlone() {
        Map<String, Object> v = Map.of("total", "120");
        assertEquals("Costs $5 or $ 7, total 120", LlmClient.interpolate("Costs $5 or $ 7, total $total$", v));
        assertEquals("Price $9.99$ stays", LlmClient.interpolate("Price $9.99$ stays", v), "not a name: kept as written");
        assertEquals("Answer as {\"total\": \"...\"}", LlmClient.interpolate("Answer as {\"total\": \"...\"}", v));
        assertEquals("missing: [] []", LlmClient.interpolate("missing: [$none$] [${none}]", v), "no value: empty, in both forms");
    }

    @Test void refusesASecretInEitherForm() {
        Map<String, Object> v = Map.of("key", new SecureString("sk-1", "k"));
        assertEquals("AI.SECRET_IN_PROMPT", assertThrows(ActionException.class, () -> LlmClient.interpolate("Use $key$", v)).code());
        assertEquals("AI.SECRET_IN_PROMPT", assertThrows(ActionException.class, () -> LlmClient.interpolate("Use ${key}", v)).code());
    }
}
