package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.List;

/** The cloud-data toolkits (platform/toolkits/cloud-data), found by FirstPartyPackages with ServiceLoader. */
public final class CloudDataToolkits implements ToolkitGroup {
    public String id() { return "cloud-data"; }

    public List<Class<?>> toolkits() {
        return List.of(Ec2Package.class, AwsAiPackage.class, DatabricksPackage.class, SnowflakePackage.class, KafkaPackage.class);
    }
}
