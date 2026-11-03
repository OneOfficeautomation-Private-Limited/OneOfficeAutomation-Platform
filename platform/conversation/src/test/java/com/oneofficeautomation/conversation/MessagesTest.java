package com.oneofficeautomation.conversation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Every chat text exists in every console language, with the same {} places as English. */
class MessagesTest {

    @Test void everyTextInEveryLanguageKeepsItsPlaces() {
        Map<String, String> en = Messages.all().get("en");
        for (String lang : Messages.LANGUAGES) {
            Map<String, String> table = Messages.all().get(lang);
            assertEquals(en.keySet(), table.keySet(), lang + " has the same texts as English");
            for (var e : en.entrySet()) {
                assertEquals(count(e.getValue()), count(table.get(e.getKey())), lang + ": the {} places differ for " + e.getKey());
                assertFalse(table.get(e.getKey()).isBlank(), lang + ": empty " + e.getKey());
            }
        }
    }

    @Test void languageCodesAndFilling() {
        assertEquals("de", Messages.language("de-DE"));
        assertEquals("en", Messages.language("pt"));
        assertEquals("en", Messages.language(null));
        assertEquals("Meinten Sie A oder B?", Messages.t("de", "did_you_mean", "A", "B"));
        assertEquals("Did you mean A or B?", Messages.t("xx", "did_you_mean", "A", "B"), "unknown languages speak English");
    }

    @Test void yesAndNoInEachLanguage() {
        assertEquals("ja", Conversation.answerWord("Ja!"));
        assertEquals("sí", Conversation.answerWord(" Sí. "));
        assertEquals("はい", Conversation.answerWord("はい。"));
        assertEquals("d'accord", Conversation.answerWord("D'accord !"));
    }

    private static int count(String s) { int n = 0; for (int i = s.indexOf("{}"); i >= 0; i = s.indexOf("{}", i + 2)) n++; return n; }
}
