package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365OptionsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** G5 (AA parity, 22 Nov 2026), SaaS toolkits against a fake server: Slack channels by type and conversations by members, Box folder details, GitHub pull requests by source branch. */
class SaasParityG5Test {
    final SecureString tok = new SecureString("tok", "t");

    @SuppressWarnings("unchecked")
    @Test void slackChannelsConversationsAndLoops() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /conversations.list", c -> ok("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"ops\",\"is_private\":false},{\"id\":\"G2\",\"name\":\"hr\",\"is_private\":true}]}"))
             .on("POST /users.conversations", c -> ok("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"ops\"}]}"))
             .on("POST /conversations.open", c -> ok("{\"ok\":true,\"channel\":{\"id\":\"D9\"}}"))
             .on("POST /conversations.history", c -> ok("{\"ok\":true,\"messages\":[{\"ts\":\"1.1\",\"user\":\"U1\",\"text\":\"hi\"}],\"has_more\":false}"));
            var s = new SlackPackage();
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            Map<String, Object> r = s.listChannels(ctx, tok, List.of("public_channel", "private_channel"), false, 100, f.url());
            assertEquals(new java.math.BigDecimal(2), r.get("count"));
            assertTrue(new String(f.last("POST", "/conversations.list").body()).contains("public_channel%2Cprivate_channel"), new String(f.last("POST", "/conversations.list").body()));
            assertEquals("SLACK.BAD_INPUT", assertThrows(ActionException.class, () -> s.listChannels(ctx, tok, List.of("dm"), false, 10, f.url())).code());
            assertEquals(new java.math.BigDecimal(1), s.userConversations(ctx, tok, "U7", null, false, 100, f.url()).get("count"));
            assertTrue(new String(f.last("POST", "/users.conversations").body()).contains("user=U7"));
            assertEquals("D9", s.openConversation(ctx, tok, List.of("U1", "U2"), f.url()).get("channel_id"));
            List<Object> chans = new ArrayList<>();
            s.eachChannel(ctx, tok, null, false, 10, f.url()).forEach(chans::add);
            assertEquals("ops", ((Map<String, Object>) chans.get(0)).get("name"));
            List<Object> msgs = new ArrayList<>();
            s.eachMessage(ctx, tok, "C1", "2026-11-01", null, 50, null, f.url()).forEach(msgs::add);
            assertEquals("hi", ((Map<String, Object>) msgs.get(0)).get("text"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void boxFolderAndGitHubSourceBranch() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /2.0/folders/123", c -> ok("{\"id\":\"123\",\"name\":\"Invoices\",\"size\":2048,\"item_collection\":{\"total_count\":7},\"parent\":{\"id\":\"0\",\"name\":\"All Files\"}}"))
             .on("GET /repos/acme/app/pulls", c -> ok("[]"));
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            Map<String, Object> info = (Map<String, Object>) new BoxPackage().folderInfo(ctx, "123", tok, null, f.url()).get("info");
            assertEquals(7, ((Number) info.get("item_count")).intValue());
            assertEquals("All Files", info.get("parent_name"));
            new GitHubPackage().listPullRequests(ctx, f.url(), tok, "acme", "app", "open", "main", 10, "feature/x");
            assertTrue(q(f.last("GET", "/repos/acme/app/pulls")).contains("head=acme:feature/x"), q(f.last("GET", "/repos/acme/app/pulls")));
        }
    }
}
