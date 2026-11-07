// The devops toolkits, a toolkit group (docs/architecture/13-modules.md, phase 5). FirstPartyPackages finds it with
// ServiceLoader; the classes stay in the Java package com.oneofficeautomation.packages, so they use core's helpers.
// The tests use the core toolkits' test kit (PackagesTest, ConnectorsTest and the other shared helpers).
evaluationDependsOn(":packages")
val coreTestKit = project(":packages").the<SourceSetContainer>()["test"].output
dependencies {
    api(project(":packages"))
    testImplementation(coreTestKit)
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
    testRuntimeOnly("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.hsqldb:hsqldb:2.7.3")
}
