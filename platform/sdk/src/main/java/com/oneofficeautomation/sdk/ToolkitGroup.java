package com.oneofficeautomation.sdk;

import java.util.List;

/**
 * A group of first-party toolkits in its own module (platform/toolkits/<group>, docs/architecture/13-modules.md,
 * phase 5), found with {@link java.util.ServiceLoader}: a group lists its class in
 * {@code META-INF/services/com.oneofficeautomation.sdk.ToolkitGroup}. FirstPartyPackages installs the core toolkits,
 * then every group on the class path, so adding or changing a toolkit touches only its group.
 */
public interface ToolkitGroup {
    /** A short id such as {@code microsoft}; groups are installed in the order of their ids. */
    String id();

    /** The toolkit classes (annotated with {@code @OoaPackage}) of the group, in the order they are listed. */
    List<Class<?>> toolkits();
}
