package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static com.oneofficeautomation.packages.SlackOptionsTest.form;
import static com.oneofficeautomation.packages.SlackOptionsTest.sl;
import static org.junit.jupiter.api.Assertions.*;

/** Slack options added from the gap list: post_message to a channel given by name. */
class SlackMoreOptionsTest {

    static Fake slack() throws Exception {
        Fake f = new Fake();
        f.on("POST /api/chat.postMessage", c -> ok("{\"ok\":true,\"ts\":\"1700.1\",\"channel\":\"C2\"}"))
         .on("POST /api/conversations.list", c -> form(c).contains("cursor=p2")
                 ? ok("{\"ok\":true,\"channels\":[{\"id\":\"C2\",\"name\":\"ops-alerts\"}],\"response_metadata\":{\"next_cursor\":\"\"}}")
                 : ok("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"p2\"}}"));
        return f;
    }

    @Test void postMessageResolvesChannelName() throws Exception {
        try (Fake f = slack()) {
            Path w = tmp();
            Map<String, Object> o = sl(f, w, "post_message", Map.of("channel", "#Ops-Alerts", "text", "Disk full"), "ts");
            assertEquals("1700.1", o.get("ts"));
            Map<String, Object> body = f.last("POST", "/api/chat.postMessage").json();
            assertEquals("C2", body.get("channel"), "name looked up on the second page of conversations.list");
            assertEquals("Disk full", body.get("text"));
            assertTrue(form(f.last("POST", "/api/conversations.list")).contains("types=public_channel,private_channel"));

            int lists = (int) f.calls.stream().filter(c -> c.path().equals("/api/conversations.list")).count();
            sl(f, w, "post_message", Map.of("channel", "C9", "text", "x"), "ts");
            assertEquals("C9", f.last("POST", "/api/chat.postMessage").json().get("channel"), "ids are used as given");
            assertEquals(lists, f.calls.stream().filter(c -> c.path().equals("/api/conversations.list")).count(), "no lookup for an id");
        }
    }

    @Test void postMessageUnknownOrEmptyChannelName() throws Exception {
        try (Fake f = slack()) {
            SlackPackage pkg = new SlackPackage();
            var ctx = RestToolkitTestSupport.ctx(tmp());
            RestToolkitTestSupport.fails("SLACK.CHANNEL_NOT_FOUND", () -> pkg.post(ctx, RestToolkitTestSupport.TOKEN, "#nope", "x", null, false, null, null, null, true, null, null, null, f.url() + "/api"));
            RestToolkitTestSupport.fails("SLACK.BAD_CHANNEL", () -> pkg.post(ctx, RestToolkitTestSupport.TOKEN, "#", "x", null, false, null, null, null, true, null, null, null, f.url() + "/api"));
            assertNull(f.last("POST", "/api/chat.postMessage"), "nothing posted");
        }
    }
}
