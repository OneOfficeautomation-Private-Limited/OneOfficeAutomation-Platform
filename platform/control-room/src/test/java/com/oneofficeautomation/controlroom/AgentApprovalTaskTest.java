package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G44: an AI agent asks for an approval in the Task inbox and reads its answer; another run cannot read it. */
class AgentApprovalTaskTest {
    @Test void aRunReadsTheAnswerToItsOwnTaskOnly() throws Exception {
        Path data = Files.createTempDirectory("ooa-g44");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            ControlRoom.User sue = RepositoryLifecycleTest.person(cr, admin, "sue", "bot_creator");
            Map<String, Object> run = Map.of("id", "run_a", "bot_path", "Sales/Agent.flow.json", "requested_by", "admin");
            String task = (String) cr.runWaits.createTask(run, Map.of("kind", "approval", "title", "AI agent: allow mcp:delete_customer?", "assignee", "user:sue",
                    "data", Map.of("tool", "mcp:delete_customer", "number", "C-7")), "agent:r1 for admin").get("task_id");
            assertEquals("open", cr.runWaits.taskStatus(run, Map.of("task_id", task)).get("status"));
            assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.runWaits.taskStatus(Map.of("id", "run_b"), Map.of("task_id", task))).code(),
                    "another run cannot read it");
            cr.workflows.complete(sue, task, "rejected", null, "Not this customer", null);
            Map<String, Object> st = cr.runWaits.taskStatus(run, Map.of("task_id", task));
            assertEquals("completed", st.get("status"));
            assertEquals("rejected", st.get("outcome"));
            assertEquals("Not this customer", st.get("comment"));
        } finally { db.close(); }
    }
}
