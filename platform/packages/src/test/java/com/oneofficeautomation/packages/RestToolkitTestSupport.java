package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.EventSink;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Shared helpers for the SaaS and data toolkit tests (Okta, Box, DocuSign, Snowflake, Databricks, Kafka). */
final class RestToolkitTestSupport {
    private RestToolkitTestSupport() {}

    static final SecureString TOKEN = new SecureString("tok-123", "test/api/token");

    static ActionContext ctx(Path work) { return PackagesTestSupport.ctx(work, Map.of()); }

    /** Runs a one-step agent through the Runner with a vault token in variable "tok"; returns the result and all event text. */
    record FlowRun(RunResult result, String events) {}

    @SuppressWarnings("unchecked")
    static FlowRun flow(Path work, String outVars, String steps) {
        String vars = "[" + ConnectorsTest.TOKEN_VAR + (outVars.isEmpty() ? "" : "," + outVars) + "]";
        Map<String, Object> f = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + vars + ",\"steps\":" + steps + "}");
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(PackagesTest.REG).workDir(work).sink(sink)
                .service("vault", new CredentialVault.InMemory().put("test", "api", "token", "tok-123")).build().run(f, ConnectorsTest.TOKEN);
        return new FlowRun(r, sink.events().toString());
    }

    static ActionException fails(String code, Executable e) {
        ActionException ex = assertThrows(ActionException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
        return ex;
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Object table) { return (List<List<Object>>) ((Map<String, Object>) table).get("rows"); }

    @SuppressWarnings("unchecked")
    static List<String> cols(Object table) { return (List<String>) ((Map<String, Object>) table).get("columns"); }

    static Object[] status(int s, String body) { return new Object[]{s, body}; }
}
