// one-chat, a feature module of One CommandCenter (docs/architecture/13-modules.md). It uses the kernel (control-room)
// only through its module contract, com.oneofficeautomation.controlroom.module.
// The tests start a whole One CommandCenter with the module on its class path, with the kernel's test kit.
evaluationDependsOn(":control-room")
val kernelTestKit = project(":control-room").the<SourceSetContainer>()["test"].output
dependencies {
    implementation(project(":control-room"))
    implementation(project(":sdk"))
    // the chat engine and its conversation rules (this module provides its ChatPlatform)
    implementation(project(":conversation"))
    // the AI gateway the chat model calls through (toolkits and runner layers below the kernel)
    implementation(project(":packages"))
    implementation(project(":runner"))
    testImplementation(kernelTestKit)
    testImplementation(project(":packages"))
    testImplementation(project(":runner"))
    testRuntimeOnly("com.h2database:h2:2.2.224")
}
