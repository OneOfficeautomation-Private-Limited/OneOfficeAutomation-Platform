package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Interactive forms: the test plays the person at the form window, through the window's own local address. */
class FormsPackageTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    final FormsPackage forms = new FormsPackage();

    @BeforeAll static void noWindows() { FormWindow.openWindows = false; }

    static Path form(Path dir) throws Exception {
        Path f = dir.resolve("Leave.form.json");
        Files.writeString(f, """
            {"id":"leave","name":"Leave request","submitLabel":"Send","fields":[
              {"id":"name","type":"text","label":"Your name","required":true},
              {"id":"days","type":"number","label":"Days","min":1,"max":30,"default":"1"},
              {"id":"email","type":"email","label":"Email"},
              {"id":"kind","type":"dropdown","label":"Kind","options":["Holiday","Sick"]},
              {"id":"urgent","type":"checkbox","label":"Urgent"},
              {"id":"lookup","type":"button","label":"Look up my balance"},
              {"id":"lines","type":"area","label":"Balance"}]}""");
        return f;
    }

    static HttpResponse<String> person(String address, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(address + "/event")).header("Content-Type", "application/json").header("X-OOA-Form", "1")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> state(String address) throws Exception {
        return (Map<String, Object>) JsonLite.parse(HTTP.send(HttpRequest.newBuilder(URI.create(address + "/state?since=0")).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test @SuppressWarnings("unchecked")
    void displayWaitsForSubmitAndReturnsTypedAnswers() throws Exception {
        Path dir = Files.createTempDirectory("ooa-forms");
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Map<String, Object>> shown = pool.submit(() -> forms.display(ctx, form(dir).toString(), "", "", Map.of("name", "Ann"), "until_done", 20, true, 560, 640));
        String address = null;
        for (int i = 0; i < 100 && address == null; i++) {
            Object s = ctx.sessions().get("form:leave");
            if (s instanceof FormWindow.Session fs) address = fs.address();
            else Thread.sleep(30);
        }
        assertNotNull(address, "the form opened");
        assertTrue(address.startsWith("http://127.0.0.1:"), "served to this computer only");
        HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(address)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("X-OOA-Form"), "the window page");
        assertEquals("Ann", ((Map<String, Object>) state(address).get("values")).get("name"), "values given to Display are filled in");
        // a page from elsewhere cannot answer: the event needs the form's own header
        assertEquals(403, HTTP.send(HttpRequest.newBuilder(URI.create(address + "/event")).POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"submit\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        person(address, "{\"type\":\"submit\",\"values\":{\"name\":\"Ann Lee\",\"days\":\"5\",\"urgent\":true,\"kind\":\"Sick\"}}");
        Map<String, Object> r = shown.get(10, TimeUnit.SECONDS);
        assertEquals(true, r.get("submitted"));
        assertEquals("submit", r.get("button"));
        Map<String, Object> v = (Map<String, Object>) r.get("values");
        assertEquals("Ann Lee", v.get("name"));
        assertEquals(new BigDecimal("5"), v.get("days"), "numbers come back as Number");
        assertEquals(true, v.get("urgent"));
        assertNull(ctx.sessions().get("form:leave"), "a submitted form is closed");
        assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(address)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "its address no longer works");
        pool.shutdownNow();
    }

    @Test @SuppressWarnings("unchecked")
    void anOpenFormIsChangedByTheAgentAndRaisesEvents() throws Exception {
        Path dir = Files.createTempDirectory("ooa-forms");
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        Map<String, Object> d = forms.display(ctx, form(dir).toString(), "req", "Leave", null, "no", 900, true, 560, 640);
        String address = (String) d.get("address");
        assertEquals("req", d.get("name"));
        assertEquals(new BigDecimal("1"), ((Map<String, Object>) d.get("values")).get("days"), "designer default");
        // the person presses a button; the agent reacts and fills the dynamic area
        person(address, "{\"type\":\"button\",\"element\":\"lookup\",\"values\":{\"name\":\"Bo\"}}");
        Map<String, Object> e = forms.waitForEvent(ctx, "req", "any", 5, false);
        assertEquals("button", e.get("event"));
        assertEquals("lookup", e.get("element"));
        assertEquals("Bo", ((Map<String, Object>) e.get("values")).get("name"));
        forms.dynamicArea(ctx, "req", "lines", List.of(Map.of("id", "line_1", "type", "paragraph", "text", "12 days left"), Map.of("id", "confirm", "type", "checkbox", "label", "I checked my balance", "required", true)));
        forms.changeLabel(ctx, "req", "lookup", "Look up again");
        forms.disable(ctx, "req", "email");
        forms.highlight(ctx, "req", "days", "#d97706");
        forms.assign(ctx, "req", "kind", List.of("Holiday", "Sick", "Unpaid"), "Unpaid");
        forms.set(ctx, "req", "days", new BigDecimal("40"));
        forms.changeTitle(ctx, "req", "Leave request (checked)");
        Map<String, Object> st = state(address);
        Map<String, Object> els = (Map<String, Object>) st.get("elements");
        assertEquals("Look up again", ((Map<String, Object>) els.get("lookup")).get("label"));
        assertEquals(true, ((Map<String, Object>) els.get("email")).get("disabled"));
        assertEquals("Leave request (checked)", st.get("title"));
        assertEquals("Unpaid", ((Map<String, Object>) st.get("values")).get("kind"));
        // validation sees the new field, the limits and the new options
        Map<String, Object> val = forms.validate(ctx, "req", true);
        assertEquals(false, val.get("valid"));
        Map<String, Object> errs = (Map<String, Object>) val.get("errors");
        assertEquals("At most 30", errs.get("days"));
        assertEquals("Required", errs.get("confirm"));
        assertFalse(errs.containsKey("email"), "a disabled field is not checked");
        assertEquals("At most 30", ((Map<String, Object>) state(address).get("errors")).get("days"), "the problems are shown on the form");
        // a change event, then reset and get
        person(address, "{\"type\":\"change\",\"element\":\"days\",\"values\":{\"days\":\"3\",\"confirm\":true}}");
        Map<String, Object> ch = forms.waitForEvent(ctx, "req", "change", 5, false);
        assertEquals("days", ch.get("element"));
        assertEquals(new BigDecimal("3"), forms.get(ctx, "req", "days").get("value"));
        assertEquals(true, forms.validate(ctx, "req", false).get("valid"));
        forms.reset(ctx, "req");
        assertEquals(new BigDecimal("1"), forms.get(ctx, "req", "days").get("value"));
        assertEquals(true, forms.waitForEvent(ctx, "req", "any", 0.3, false).get("timed_out"));
        assertEquals("FORMS.TIMEOUT", assertThrows(ActionException.class, () -> forms.waitForEvent(ctx, "req", "any", 0.3, true)).code());
        assertEquals("FORMS.ELEMENT_NOT_FOUND", assertThrows(ActionException.class, () -> forms.hide(ctx, "req", "nope")).code());
        assertEquals("FORMS.NOT_A_FIELD", assertThrows(ActionException.class, () -> forms.set(ctx, "req", "lookup", "x")).code());
        forms.close(ctx, "req");
        assertEquals("FORMS.NOT_OPEN", assertThrows(ActionException.class, () -> forms.get(ctx, "req", "")).code());
        assertTrue((Boolean) state0(address), "the window learns the form is closed");
    }

    static Object state0(String address) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(address + "/state")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 404;
    }

    @Test void closingTheWindowEndsTheWait() throws Exception {
        Path dir = Files.createTempDirectory("ooa-forms");
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        Map<String, Object> d = forms.display(ctx, form(dir).toString(), "w", "", null, "no", 900, true, 560, 640);
        person((String) d.get("address"), "{\"type\":\"close\"}");
        assertEquals("close", forms.waitForEvent(ctx, "w", "button", 5, false).get("event"), "closing always ends the wait");
        assertEquals("FORMS.BAD_FORM", assertThrows(ActionException.class, () -> {
            Path bad = Files.writeString(dir.resolve("x.form.json"), "{\"name\":\"no fields\"}");
            forms.display(ctx, bad.toString(), "", "", null, "no", 900, true, 560, 640);
        }).code());
    }
}
