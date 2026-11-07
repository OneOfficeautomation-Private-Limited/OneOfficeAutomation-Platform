package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.ConsolePlugin;

/**
 * Fills AI > Document Automation with the Document Automation pages (served at /documents/ by One CommandCenter, with
 * the DocFlow service behind them through DocFlowProxy, the document-automation module): the console's page then shows Installed and opens them.
 */
public final class DocumentAutomationPlugin implements ConsolePlugin {
    public String id() { return "document-automation"; }
    public String name() { return "Document Automation"; }
    public String description() {
        return "Processes that read documents such as invoices and forms, check their fields, send them to people for review "
                + "and start agents with the results; documents come from an email inbox, an upload or an agent.";
    }
    public String path() { return "/documents/"; }
}
