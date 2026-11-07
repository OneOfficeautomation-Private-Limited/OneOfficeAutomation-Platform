package com.oneofficeautomation.cli;

import com.oneofficeautomation.modules.docauto.DocFlowConfig;
import com.oneofficeautomation.controlroom.Vault;

import java.nio.file.Path;
import java.util.Map;

/**
 * ooa docflow configure --url URL --token-env VAR [--data DIR]: records where Document Automation's DocFlow service is
 * and its service token (encrypted with the vault master key) in DIR/config/ooa-docflow.properties. The Windows
 * installer runs it after installing DocFlow (Install-OOA.ps1 -DocFlow); Settings > Document Automation overrides it.
 */
final class DocFlowCommand {
    private DocFlowCommand() { }

    static int configure(Map<String, String> o) {
        Path data = Ooa.dataDir(o);
        String url = o.getOrDefault("url", "http://127.0.0.1:8085").trim();
        if (!url.matches("(?i)https?://[^\s/?#]+/?")) { System.err.println("--url is DocFlow's address, for example http://127.0.0.1:8085"); return 1; }
        String var = o.get("token-env");
        String token = var == null ? null : System.getenv(var);
        if (token == null || token.isBlank()) { System.err.println("--token-env names the environment variable that holds DocFlow's service token (DOCAUTO_API_TOKEN), and it must be set."); return 1; }
        DocFlowConfig.save(data, new Vault(data), url, token.trim());
        System.out.println("Document Automation: DocFlow at " + url + ", token stored in " + DocFlowConfig.file(data) + " (encrypted with the vault master key).");
        System.out.println("One CommandCenter uses it from its next request; Settings > Document Automation overrides it.");
        return 0;
    }
}
