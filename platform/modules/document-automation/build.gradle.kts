// Document Automation, a feature module of One CommandCenter (docs/architecture/13-modules.md): the platform's side
// of Document Automation. It uses the kernel (control-room) only through its module contract,
// com.oneofficeautomation.controlroom.module; the kernel finds it with ServiceLoader and never names its classes.
dependencies {
    implementation(project(":control-room"))
    implementation(project(":sdk"))
    // tests start a whole One CommandCenter with the module on its class path
    testImplementation(project(":packages"))
    // its AI-assistant tools are tested through the MCP server, which the integration module serves (tests only)
    testImplementation(project(":integration"))
    testImplementation(project(":runner"))
    testRuntimeOnly("com.h2database:h2:2.2.224")
}
