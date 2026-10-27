package com.oneofficeautomation.cli.nativeimage;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.util.List;

/**
 * Tells GraalVM, while it builds the native executable, what the program finds by reflection: the toolkits (their
 * commands are read from annotations and called through Method.invoke) and the JSON adapters loaded by name. The
 * built-in toolkits can be called; the optional ones (Office, email, PDF, browser, Windows) can only be read, so
 * 'validate' and 'manifest' know their commands without their libraries being compiled in.
 */
public final class OoaNativeFeature implements Feature {

    static final List<String> ADAPTERS = List.of("com.oneofficeautomation.adapters.jackson.JacksonJsonCodec",
        "com.oneofficeautomation.adapters.jackson.NetworkntFlowSchemaValidator");

    @Override public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (Class<?> c : FirstPartyPackages.ALL) {
            RuntimeReflection.register(c);
            RuntimeReflection.register(c.getDeclaredConstructors());
            RuntimeReflection.register(c.getMethods());
        }
        for (String name : FirstPartyPackages.OPTIONAL) {
            Class<?> c = access.findClassByName(name);
            if (c == null) continue;   // its module is not in this build
            RuntimeReflection.register(c);
            RuntimeReflection.register(c.getDeclaredConstructors());
            RuntimeReflection.registerAllMethods(c);
        }
        for (String name : ADAPTERS) {
            Class<?> c = access.findClassByName(name);
            if (c == null) continue;
            RuntimeReflection.register(c);
            RuntimeReflection.register(c.getDeclaredConstructors());
        }
    }
}
