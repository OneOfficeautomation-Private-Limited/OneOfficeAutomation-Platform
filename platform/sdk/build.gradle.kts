// Package SDK: no third-party dependencies.

// The product version, written into BuildInfo.java at build time from the version in the root build file, so the
// Command Center, the runner and the ooa program all report (and compare) the same release.
val buildInfoDir = layout.buildDirectory.dir("generated/sources/buildinfo/java/main")
val generateBuildInfo by tasks.registering {
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(buildInfoDir)
    doLast {
        val f = buildInfoDir.get().file("com/oneofficeautomation/sdk/BuildInfo.java").asFile
        f.parentFile.mkdirs()
        f.writeText("""package com.oneofficeautomation.sdk;

/** Made by the build (sdk/build.gradle.kts) from the product version; do not edit. */
public final class BuildInfo {
    private BuildInfo() { }

    /** The release of this build, e.g. 0.2.0. */
    public static final String VERSION = "$version";
}
""")
    }
}

extensions.configure<SourceSetContainer> {
    named("main") { java.srcDir(generateBuildInfo) }
}
