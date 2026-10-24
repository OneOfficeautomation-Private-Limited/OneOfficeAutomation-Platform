package com.oneofficeautomation.packages.browser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ids that change between visits are not used to find a captured element; ids that read as words are. */
class GeneratedIdTest {
    @Test
    void madeUpIdsAreRecognised() {
        // rpachallenge.com gives its fields new random ids on every round
        for (String id : List.of("UeWWS", "jQjQk", "9At89", "OCNJj", "47axg", "g7Aeq", "LdhYa", "ember1234", "ctl00123"))
            assertTrue(BrowserPackage.generatedId(id), id);
    }

    @Test
    void wordLikeIdsAreKept() {
        for (String id : List.of("firstName", "userId", "SubmitButton", "user_email", "email", "username", "pageX", "btnSave",
                "login-form", "navbar", "searchBox", "APIKey", "q", "txtFirstName", "lblTotal", "HTML", "okBtn", "UIRoot", "PDFViewer"))
            assertFalse(BrowserPackage.generatedId(id), id);
    }
}
