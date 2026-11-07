package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.List;

/** The enterprise toolkits (platform/toolkits/enterprise), found by FirstPartyPackages with ServiceLoader. */
public final class EnterpriseToolkits implements ToolkitGroup {
    public String id() { return "enterprise"; }

    public List<Class<?>> toolkits() {
        return List.of(SapPackage.class, SapBapiPackage.class, SalesforcePackage.class, ServiceNowPackage.class, WorkdayPackage.class, JiraPackage.class, ZendeskPackage.class, AsanaPackage.class, DocuSignPackage.class, BoxPackage.class, SlackPackage.class, OktaPackage.class);
    }
}
