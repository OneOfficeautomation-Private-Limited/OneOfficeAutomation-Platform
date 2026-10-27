// Windows window management through the Win32 API (JNA).
dependencies {
    api(project(":packages"))
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

// The agent loaded into Java applications (Java Attach API) for the Java applications toolkit: compiled for Java 8,
// the oldest Java those applications may run on, and packed as a jar inside this module's resources.
val javaAgent by sourceSets.creating
tasks.named<JavaCompile>("compileJavaAgentJava") {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")   // no warning that Java 8 is old
}
val javaAgentJar by tasks.registering(Jar::class) {
    from(javaAgent.output)
    archiveFileName.set("ooa-java-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("java-agent/com/oneofficeautomation/packages/windows"))
    manifest { attributes("Agent-Class" to "com.oneofficeautomation.javaagent.OoaJavaAgent") }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("java-agent")) }
tasks.named("processResources") { dependsOn(javaAgentJar) }
