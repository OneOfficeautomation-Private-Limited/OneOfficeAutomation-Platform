package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G64: topics found in an inbox, and each message routed to a queue. */
class CommsPackageTest {
    @TempDir Path w;

    static final String INBOX = """
            id,subject,body
            m1,Invoice 4411 overdue,"Our invoice 4411 is overdue, please arrange payment of the invoice.
            > old quoted text about delivery tracking parcel"
            m2,Payment reminder,The invoice payment for March is still open. Invoice attached.
            m3,Invoice question,Can you confirm the invoice amount and payment date?
            m4,Parcel not delivered,My parcel has not been delivered. The tracking shows the courier lost the parcel.
            m5,Where is my delivery,Delivery is late and the tracking number does not work for the parcel.
            m6,Damaged parcel delivery,The courier delivery arrived but the parcel tracking says returned.
            m7,Password reset,I cannot log in; the password reset link has expired. Please reset my password.
            m8,Account locked,My account is locked after a password reset. Login fails.
            m9,Login problem,Login page rejects my password; account access needed.
            """;

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Object t) { return (List<List<Object>>) ((Map<String, Object>) t).get("rows"); }

    @Test void findsTheTopicsOfAnInbox() throws Exception {
        Files.writeString(w.resolve("inbox.csv"), INBOX);
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> r = new CommsPackage().discover(ctx, "inbox.csv", null, "subject,body", "id", 3, 2);
        assertEquals(9, r.get("messages"));
        List<List<Object>> topics = rows(r.get("topics"));
        assertEquals(3, topics.size(), topics.toString());
        Map<String, String> topicOf = new HashMap<>();
        for (List<Object> a : rows(r.get("assignments"))) topicOf.put((String) a.get(0), (String) a.get(2));
        assertEquals(topicOf.get("m1"), topicOf.get("m2"));
        assertEquals(topicOf.get("m1"), topicOf.get("m3"));
        assertEquals(topicOf.get("m4"), topicOf.get("m5"));
        assertEquals(topicOf.get("m4"), topicOf.get("m6"));
        assertEquals(topicOf.get("m7"), topicOf.get("m8"));
        assertEquals(topicOf.get("m7"), topicOf.get("m9"));
        assertEquals(3, new HashSet<>(topicOf.values()).size(), topicOf.toString());
        assertTrue(topicOf.get("m1").contains("invoice"), topicOf.toString());
        assertTrue(topicOf.get("m4").contains("parcel"), topicOf.toString());
        assertEquals(3, ((Map<?, ?>) r.get("rules")).size());
        assertEquals(r.get("assignments"), new CommsPackage().discover(ctx, "inbox.csv", null, "subject,body", "id", 3, 2).get("assignments"), "the same seed, the same topics");
        assertEquals("COMMS.NO_COLUMN", assertThrows(ActionException.class, () -> new CommsPackage().discover(ctx, "inbox.csv", null, "text", "id", 3, 2)).code());
    }

    @Test void quotedRepliesAndSignaturesAreLeftOut() {
        assertEquals("Please pay.\n", CommsPackage.clean("Please pay.\n> earlier text\n--\nJane Doe\nACME"));
        assertEquals("Thanks\n", CommsPackage.clean("Thanks\nOn Mon, 2 Nov 2026 at 10:00, Bob <b@x.com> wrote:\nold"));
    }

    @Test @SuppressWarnings("unchecked")
    void routesEachMessageToTheBestQueue() throws Exception {
        Files.writeString(w.resolve("inbox.csv"), INBOX + "m10,Hello,Nothing in particular here.\n");
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("Finance", List.of("invoice", "payment"));
        rules.put("Logistics", List.of("parcel", "delivery", "tracking number"));
        rules.put("IT", "password, login, account locked");
        Map<String, Object> r = new CommsPackage().route(ctx, "inbox.csv", null, rules, "subject,body", "id", "subject", "Unsorted", 1);
        Map<String, Object> q = new HashMap<>();
        for (List<Object> row : rows(r.get("routed"))) q.put((String) row.get(0), row.get(1));
        assertEquals("Finance", q.get("m1"));
        assertEquals("Finance", q.get("m3"));
        assertEquals("Logistics", q.get("m5"));
        assertEquals("IT", q.get("m8"));
        assertEquals("Unsorted", q.get("m10"));
        assertEquals(Map.of("Finance", 3, "Logistics", 3, "IT", 3, "Unsorted", 1), new HashMap<>((Map<String, Object>) r.get("counts")));
        List<Object> m5 = rows(r.get("routed")).get(4);
        assertTrue(((String) m5.get(3)).contains("tracking number"), m5.toString());
        Files.writeString(w.resolve("nosubject.csv"), "id,body\nx1,Invoices are due\n");
        assertEquals("Finance", rows(new CommsPackage().route(ctx, "nosubject.csv", null, rules, "body", "id", "subject", "Unsorted", 1).get("routed")).get(0).get(1), "plural matches; no subject column is fine");
        assertEquals("COMMS.NO_RULES", assertThrows(ActionException.class, () -> new CommsPackage().route(ctx, "inbox.csv", null, Map.of(), "body", "id", null, "U", 1)).code());
    }
}
