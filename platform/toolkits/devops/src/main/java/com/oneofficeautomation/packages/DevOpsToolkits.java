package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.List;

/** The devops toolkits (platform/toolkits/devops), found by FirstPartyPackages with ServiceLoader. */
public final class DevOpsToolkits implements ToolkitGroup {
    public String id() { return "devops"; }

    public List<Class<?>> toolkits() {
        return List.of(GitHubPackage.class, AzureDevOpsPackage.class);
    }
}
