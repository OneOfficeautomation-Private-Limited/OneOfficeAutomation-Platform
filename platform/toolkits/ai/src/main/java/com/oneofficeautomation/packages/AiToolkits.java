package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.List;

/** The ai toolkits (platform/toolkits/ai), found by FirstPartyPackages with ServiceLoader. */
public final class AiToolkits implements ToolkitGroup {
    public String id() { return "ai"; }

    public List<Class<?>> toolkits() {
        return List.of(AiAgentPackage.class, AiSkillsPackage.class, AiScreenPackage.class, KnowledgePackage.class, MiningPackage.class);
    }
}
