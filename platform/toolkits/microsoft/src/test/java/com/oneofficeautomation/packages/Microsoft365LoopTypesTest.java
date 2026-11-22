package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.ok;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** G1 (AA parity, 21 Nov 2026): loop types for teams, channels, channel posts, chats and calendar events, against a fake Graph. */
class Microsoft365LoopTypesTest {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");

    @SuppressWarnings("unchecked")
    static List<Object> field(Iterable<Object> rows, String f) { List<Object> out = new ArrayList<>(); for (Object r : rows) out.add(((Map<String, Object>) r).get(f)); return out; }

    @Test void eachTeamAndEachChannel() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/me/joinedTeams", c -> ok("{\"value\":[{\"id\":\"T1\",\"displayName\":\"Finance\",\"description\":\"\"},{\"id\":\"T2\",\"displayName\":\"Sales\"}]}"));
            f.at("GET", "/v1.0/teams/T1/channels", c -> ok("{\"value\":[{\"id\":\"19:gen\",\"displayName\":\"General\",\"membershipType\":\"standard\"}]}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals(List.of("Finance", "Sales"), field(pkg.eachTeam(ctx, tok, "me", f.base()), "name"));
            assertEquals(List.of("General"), field(pkg.eachChannel(ctx, tok, "T1", f.base()), "name"));
        }
    }

    @Test void eachChatFollowsPagesAndStopsAtMax() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/chats", c -> c.q().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"c3\",\"chatType\":\"group\"}]}")
                    : ok("{\"value\":[{\"id\":\"c1\",\"topic\":\"Close\",\"chatType\":\"group\",\"lastUpdatedDateTime\":\"2026-11-20T08:00:00Z\"},{\"id\":\"c2\",\"chatType\":\"oneOnOne\"}],"
                       + "\"@odata.nextLink\":\"" + b + "/me/chats?$skiptoken=p2\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals(List.of("c1", "c2", "c3"), field(pkg.eachChat(ctx, tok, 50, b), "id"));
            assertEquals(List.of("c1", "c2"), field(pkg.eachChat(ctx, tok, 2, b), "id"));
            assertEquals(List.of("Close", ""), field(pkg.eachChat(ctx, tok, 2, b), "topic"));
        }
    }

    @Test void eachChannelPostAndEachCalendarEvent() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/teams/T1/channels/C1/messages", c -> ok("{\"value\":[{\"id\":\"1\",\"createdDateTime\":\"2026-11-02T09:15:00Z\",\"from\":{\"user\":{\"displayName\":\"Ann\"}},"
                    + "\"body\":{\"contentType\":\"text\",\"content\":\"done\"}}]}"));
            f.at("GET", "/v1.0/me/calendarView", c -> ok("{\"value\":[{\"id\":\"e1\",\"subject\":\"Month end\",\"start\":{\"dateTime\":\"2026-11-30T09:00:00\"},"
                    + "\"end\":{\"dateTime\":\"2026-11-30T10:00:00\"},\"location\":{\"displayName\":\"Room 2\"},\"organizer\":{\"emailAddress\":{\"address\":\"ann@example.com\"}}}]}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals(List.of("done"), field(pkg.eachChannelMessage(ctx, tok, "T1", "C1", null, 100, f.base()), "text"));
            assertEquals(List.of("Month end"), field(pkg.eachEvent(ctx, tok, LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0),
                    null, "UTC", null, "me", 1000, f.base()), "subject"));
        }
    }
}
