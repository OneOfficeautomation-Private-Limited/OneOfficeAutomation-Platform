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
    // in-memory SMTP/IMAP server for the email sample tasks (CommandSamplesTest)
    testImplementation("com.icegreen:greenmail:2.1.2")
    // in-memory LDAP directory for the Active Directory sample tasks
    testImplementation("com.unboundid:unboundid-ldapsdk:7.0.1")
    // in-memory database for the database sample tasks
    testRuntimeOnly("com.h2database:h2:2.2.224")
}

application {
    mainClass.set("com.oneofficeautomation.cli.Ooa")
    applicationName = "ooa"
}

// the sample tasks run whole agents (browser, OCR on screen images, mail and directory servers) in the test JVM
tasks.named<Test>("test") { maxHeapSize = "2g" }

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
