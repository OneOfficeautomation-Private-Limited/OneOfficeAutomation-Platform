package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.List;

/** The microsoft toolkits (platform/toolkits/microsoft), found by FirstPartyPackages with ServiceLoader. */
public final class MicrosoftToolkits implements ToolkitGroup {
    public String id() { return "microsoft"; }

    public List<Class<?>> toolkits() {
        return List.of(Microsoft365Package.class, ActiveDirectoryPackage.class, AzureVmPackage.class, ExchangePackage.class);
    }
}
