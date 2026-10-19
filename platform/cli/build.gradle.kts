plugins { application }

val optionalModules = ((findProperty("ooa.optionalModules") as String?) ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

dependencies {
    implementation(project(":packages"))
    implementation(project(":control-room"))
    implementation(project(":agent"))
    optionalModules.forEach { runtimeOnly(project(":$it")) }
    runtimeOnly(project(":adapters-jackson"))
    runtimeOnly("ch.qos.logback:logback-classic:1.5.8")
    testRuntimeOnly(project(":adapters-jackson"))
}

application {
    mainClass.set("com.oneofficeautomation.cli.Ooa")
    applicationName = "ooa"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
