dependencies {
    api(project(":runner"))
    implementation("com.github.mwiede:jsch:0.2.20")   // Terminal: VT hosts over SSH
    testImplementation("org.apache.sshd:sshd-core:2.14.0")   // in-process SSH host for the VT terminal tests
    testRuntimeOnly("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.hsqldb:hsqldb:2.7.3")   // stored procedures with OUT parameters, which H2 lacks
}
