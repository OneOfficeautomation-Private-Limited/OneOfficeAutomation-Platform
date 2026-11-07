package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.util.Map;

/**
 * Document Automation, the first feature module moved out of One CommandCenter's kernel (docs/architecture/13-modules.md).
 * It is the platform's side of Document Automation; the DocFlow service itself is the separate oneoffice-docauto
 * repository, reached through its versioned interface (/api/docs/v1, docs/integration/document-automation-contract.md).
 *
 * <p>What it adds, all through its {@link ModuleContext}:
 * <ul>
 *   <li>/api/docflow/... for the Document Automation screens (DocFlowProxy.forward), checked per person and folder;</li>
 *   <li>/api/agent/docflow/documents, an agent's "Send document to process";</li>
 *   <li>/api/docflow-callback/start-bot and /events, DocFlow calling back with the service token;</li>
 *   <li>clock jobs: review tasks in the Task inbox (every 20 s) and the processes' email inboxes (every 30 s);</li>
 *   <li>the settings docflow_url, docflow_token_ref and mcp_documents;</li>
 *   <li>the task kind document_review, the "On Document Automation" trigger kinds, the documents_ tools for AI
 *       assistants, and processes in templates.</li>
 * </ul>
 * Its table docflow_mail is in the kernel's frozen baseline (version 33); new tables go in {@link #schema()}.
 */
public final class DocumentAutomationModule implements CommandCenterModule {
    /** The module's own version: it changes when the module changes. */
    public static final String VERSION = "1.0.0";

    public String id() { return "document-automation"; }
    public String name() { return "Document Automation"; }
    public String version() { return VERSION; }

    public void start(ModuleContext ctx) {
        ctx.defineSetting("docflow_url", "", (v, saas) -> {
            // in the SaaS service the region's operator says where DocFlow is (OOA_DOCFLOW_URL), not the tenant
            if (saas && !v.isEmpty()) throw notInSaas();
            if (!v.isEmpty() && !v.matches("(?i)https?://[^\\s/?#]+/?"))
                throw new OoaException("CR.BAD_INPUT", "The Document Automation service is a web address such as http://127.0.0.1:8085");
        });
        ctx.defineSetting("docflow_token_ref", "", (v, saas) -> {
            if (saas && !v.isEmpty()) throw notInSaas();
            if (!v.isEmpty() && v.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "The Document Automation token is a vault reference: vault folder/credential/attribute");
        });
        // Settings > AI assistants (MCP) > Document Automation tools: 1 offers the documents_ tools, 0 does not
        ctx.defineSetting("mcp_documents", 1, (v, saas) -> {
            if (!v.equals("0") && !v.equals("1")) throw new OoaException("CR.BAD_INPUT", "Setting 'mcp_documents' must be from 0 to 1");
        });

        DocFlowProxy docflow = new DocFlowProxy(ctx);
        ctx.route("docflow", (ex, u, method, route, ip) -> docflow.forward(ex, u, method, route));
        ctx.agentRoute("docflow/documents", docflow::fromBot);
        ctx.callbackRoute("docflow-callback/start-bot", docflow::startBot);
        ctx.callbackRoute("docflow-callback/events", docflow::event);
        ctx.every(Duration.ofSeconds(10), Duration.ofSeconds(20), docflow::syncReviews);
        ctx.every(Duration.ofSeconds(15), Duration.ofSeconds(30), docflow::pollMail);

        // a document review task shows only to people who may see its process's folder, and closes on the review screen
        ctx.taskKind(DocFlowProxy.TASK_KIND, new ModuleContext.TaskKind() {
            public boolean visible(User u, Map<String, Object> data, Map<String, Map<String, String>> folders) {
                String folder = DocFlowProxy.folderKey(data.get("folder"));
                return folder.isEmpty() || ctx.atLeast(ctx.folderAccess(u.username(), u.role(), folder, folders), "view");
            }
            public String completeElsewhere() { return "Open the document to review it: approve or reject it on the review screen"; }
        });
        for (Map.Entry<String, String> e : DocFlowProxy.EVENT_LABELS.entrySet()) ctx.triggerKind(e.getKey(), e.getValue(), DocFlowProxy::triggerConfig);
        ctx.mcpTools(new McpDocuments(ctx, docflow));
        ctx.templatePart(new DocTemplates(ctx, docflow));
    }

    private static OoaException notInSaas() {
        return new OoaException("SAAS.NOT_AVAILABLE", "Choosing the Document Automation service is not available in the SaaS service", "Use a runner in your own network for it.");
    }
}
