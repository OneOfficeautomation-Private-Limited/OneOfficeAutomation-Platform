package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.List;

/** The google toolkits (platform/toolkits/google), found by FirstPartyPackages with ServiceLoader. */
public final class GoogleToolkits implements ToolkitGroup {
    public String id() { return "google"; }

    public List<Class<?>> toolkits() {
        return List.of(GooglePackage.class);
    }
}
