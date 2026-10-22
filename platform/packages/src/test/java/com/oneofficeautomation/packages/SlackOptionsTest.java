package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365OptionsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Slack toolkit after comparing it action by action with other RPA products, against a fake Slack Web API. */
class SlackOptionsTest {

    static Map<String, Object> sl(Fake f, Path w, String action, Map<String, Object> args, String... outs) {
        return act("ooa.slack", "bot_token", "base_url", f.url() + "/api", w, action, args, outs);
    }

    static String form(Call c) { return URLDecoder.decode(c.text(), StandardCharsets.UTF_8); }

    static Fake slack() throws Exception {
        Fake f = new Fake();
        f.on("POST /api/chat.postMessage", c -> ok("{\"ok\":true,\"ts\":\"1700.1\",\"channel\":\"C1\"}"))
         .on("POST /api/chat.update", c -> ok("{\"ok\":true}"))
         .on("POST /api/files.getUploadURLExternal", c -> ok("{\"ok\":true,\"upload_url\":\"" + f.url() + "/upload/" + (form(c).contains("a.txt") ? "U1" : "U2") + "\",\"file_id\":\""
                 + (form(c).contains("a.txt") ? "F1" : "F2") + "\"}"))
         .on("POST /upload/.*", c -> ok("OK - " + c.body().length))
         .on("POST /api/files.completeUploadExternal", c -> ok("{\"ok\":true,\"files\":[]}"))
         .on("POST /api/users.lookupByEmail", c -> ok("{\"ok\":true,\"user\":{\"id\":\"U111\"}}"))
         .on("POST /api/conversations.open", c -> ok("{\"ok\":true,\"channel\":{\"id\":\"G77\"}}"))
         .on("POST /api/conversations.replies", c -> ok("{\"ok\":true,\"messages\":[{\"ts\":\"1.0\",\"text\":\"root\"},{\"ts\":\"1.1\",\"text\":\"reply\"}],\"has_more\":false}"))
         .on("POST /api/auth.teams.list", c -> ok("{\"ok\":true,\"teams\":[{\"id\":\"T1\",\"name\":\"Acme HQ\"},{\"id\":\"T2\",\"name\":\"Acme Labs\",\"domain\":\"acmelabs\"}]}"))
         .on("POST /api/team.info", c -> ok("{\"ok\":true,\"team\":{\"id\":\"" + (form(c).contains("team=T2") ? "T2" : "T1") + "\",\"name\":\"x\"}}"));
        return f;
    }

    @Test void postMessageBlocksBroadcastPreviewsSenderAndFiles() throws Exception {
        try (Fake f = slack()) {
            Path w = tmp();
            Files.writeString(w.resolve("a.txt"), "hello");
            Files.writeString(w.resolve("b.csv"), "1,2");
            Map<String, Object> o = sl(f, w, "post_message", Map.ofEntries(Map.entry("channel", "C1"), Map.entry("text", "Report ready"), Map.entry("thread_ts", "1699.9"),
                    Map.entry("reply_broadcast", true), Map.entry("blocks", "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"*Done*\"}}]"),
                    Map.entry("unfurl_links", false), Map.entry("mrkdwn", false), Map.entry("username", "Report agent"), Map.entry("icon_emoji", ":robot_face:"),
                    Map.entry("files", List.of("a.txt", "b.csv"))), "ts");
            assertEquals("1700.1", o.get("ts"));
            Map<String, Object> body = f.last("POST", "/api/chat.postMessage").json();
            assertEquals(true, body.get("reply_broadcast"));
            assertEquals("section", m(l(body.get("blocks")).get(0)).get("type"));
            assertEquals(false, body.get("unfurl_links"));
            assertFalse(body.containsKey("unfurl_media"), "left to Slack when empty");
            assertEquals(false, body.get("mrkdwn"));
            assertEquals("Report agent", body.get("username"));
            assertEquals("hello", f.last("POST", "/upload/U1").text());
            assertNull(f.last("POST", "/upload/U1").auth(), "the upload address is pre-signed; the token is not sent there");
            assertTrue(form(f.last("POST", "/api/files.getUploadURLExternal")).contains("length=3"));
            Map<String, Object> done = f.last("POST", "/api/files.completeUploadExternal").json();
            assertEquals("C1", done.get("channel_id"));
            assertEquals("1699.9", done.get("thread_ts"));
            assertEquals(List.of("F1", "F2"), l(done.get("files")).stream().map(x -> m(x).get("id")).toList());

            sl(f, w, "post_message", Map.of("channel", "C1", "text", "plain"));
            assertEquals(Map.of("channel", "C1", "text", "plain"), f.last("POST", "/api/chat.postMessage").json(), "no options, same request as before");
            assertEquals("SLACK.BAD_BLOCKS", err("ooa.slack", "bot_token", "base_url", f.url() + "/api", "post_message", Map.of("channel", "C1", "text", "x", "blocks", "{\"type\":1}")).get("code"));

            sl(f, w, "update_message", Map.of("channel", "C1", "ts", "1700.1", "text", "v2", "blocks", List.of(Map.of("type", "divider"))));
            assertEquals(List.of(Map.of("type", "divider")), f.last("POST", "/api/chat.update").json().get("blocks"));
        }
    }

    @Test void groupDirectMessageUploadThreadHistoryAndWorkspaces() throws Exception {
        try (Fake f = slack()) {
            Path w = tmp();
            Files.writeString(w.resolve("a.txt"), "hi");
            Map<String, Object> o = sl(f, w, "send_direct_message", Map.of("user", "ann@x.com; U222", "text", "Hi both", "thread_ts", "5.5", "unfurl_media", false), "ts", "channel");
            assertEquals("G77", o.get("channel"));
            assertEquals("U111,U222", f.last("POST", "/api/conversations.open").json().get("users"), "several people open a group conversation");
            Map<String, Object> dm = f.last("POST", "/api/chat.postMessage").json();
            assertEquals("5.5", dm.get("thread_ts"));
            assertEquals(false, dm.get("unfurl_media"));

            o = sl(f, w, "upload_file", Map.of("channel", "C9", "files", List.of("a.txt"), "comment", "Logs attached"), "file_ids");
            assertEquals(List.of("F1"), o.get("file_ids"));
            assertEquals("Logs attached", f.last("POST", "/api/files.completeUploadExternal").json().get("initial_comment"));

            o = sl(f, w, "get_conversation_history", Map.of("channel", "C1", "thread_ts", "1.0", "inclusive", true), "count");
            assertEquals(2, ((Number) o.get("count")).intValue());
            String args = form(f.last("POST", "/api/conversations.replies"));
            assertTrue(args.contains("ts=1.0") && args.contains("inclusive=true"), args);

            assertEquals("T2", sl(f, w, "get_workspace_id_by_name", Map.of("name", "ACMELABS"), "workspace_id").get("workspace_id"), "matched by domain");
            assertEquals("T1", sl(f, w, "get_workspace_id_by_name", Map.of("name", "acme hq"), "workspace_id").get("workspace_id"));
            assertEquals("SLACK.WORKSPACE_NOT_FOUND", err("ooa.slack", "bot_token", "base_url", f.url() + "/api", "get_workspace_id_by_name", Map.of("name", "Globex")).get("code"));
            o = sl(f, w, "get_workspace_details", Map.of("workspace_id", "T2"), "workspace");
            assertEquals("T2", m(o.get("workspace")).get("id"));
        }
    }
}
