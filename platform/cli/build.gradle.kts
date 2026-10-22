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

// The browser connector extension ships in the install (browser-extension\), with the shared page script.
distributions {
    main {
        contents {
            from(rootProject.file("browser-extension")) { into("browser-extension") }
            from(rootProject.file("packages-browser/src/main/resources/com/oneofficeautomation/packages/browser/ooa-page.js")) { into("browser-extension") }
        }
    }
}
