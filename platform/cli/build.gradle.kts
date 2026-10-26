plugins { application }

val optionalModules = ((findProperty("ooa.optionalModules") as String?) ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

dependencies {
    implementation(project(":packages"))
    implementation(project(":control-room"))
    implementation(project(":agent"))
    optionalModules.forEach { runtimeOnly(project(":$it")) }
    runtimeOnly(project(":adapters-jackson"))
    // license issuing: in the vendor build (installDist, run, tests) only; the customer distribution leaves it out
    runtimeOnly(project(":vendor-tools"))
    // Windows services (ooa service install, service-run): talks to the Windows service manager
    implementation("net.java.dev.jna:jna-platform:5.14.0")
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

val customerStartScripts by tasks.registering(CreateStartScripts::class) {
    mainClass.set("com.oneofficeautomation.cli.Ooa")
    applicationName = "ooa"
    outputDir = layout.buildDirectory.dir("customer-scripts").get().asFile
    classpath = files(tasks.named("jar")) + configurations.named("runtimeClasspath").get().filter { !it.name.startsWith("vendor-tools") }
    // one wildcard instead of 60 jar paths, so the Windows command line stays short wherever the product is installed
    doLast {
        val bat = outputDir!!.resolve("ooa.bat")
        bat.writeText(bat.readText().replace(Regex("(?m)^set CLASSPATH=.*$")) { "set CLASSPATH=%APP_HOME%\\lib\\*" }
            // the setup package carries its own Java (runtime\): it is used before any Java installed on the machine
            .replace(Regex("""(?m)^for %%i in \("%APP_HOME%"\) do set APP_HOME=%%~fi\r?$""")) {
                it.value.trimEnd('\r') + "\r\nif exist \"%APP_HOME%\\runtime\\bin\\java.exe\" set \"JAVA_HOME=%APP_HOME%\\runtime\"\r" })
        val sh = outputDir!!.resolve("ooa")
        sh.writeText(sh.readText().replace(Regex("(?m)^CLASSPATH=.*$")) { "CLASSPATH=\$APP_HOME/lib/*" })
    }
}

// The Java runtime that the setup package carries (jlink): every Java module the product may use, without the
// developer tools (compiler, debugger, packagers). About 65 MB; customers then need no Java of their own.
val javaRuntime by tasks.registering {
    val out = layout.buildDirectory.dir("java-runtime")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        delete(dir)
        val bin = File(System.getProperty("java.home"), "bin")
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val skip = Regex("""^jdk\.(jshell|jdeps|jlink|jpackage|javadoc|jconsole|hotspot\.agent|jdi|jdwp\.agent|jstatd|jcmd|editpad|random|jartool|compiler|internal\..*|incubator\..*|graal\..*)$""")
        val list = providers.exec { commandLine(File(bin, "java$exe").path, "--list-modules") }.standardOutput.asText.get()
        val modules = list.lines().map { it.substringBefore('@').trim() }.filter { it.isNotEmpty() && !skip.matches(it) }
        providers.exec {
            commandLine(File(bin, "jlink$exe").path, "--add-modules", modules.joinToString(","), "--strip-debug", "--no-header-files",
                "--no-man-pages", "--compress", "zip-6", "--output", dir.path)
        }.result.get().assertNormalExitValue()
    }
}

// The browser connector extension ships in the install (browser-extension\), with the shared page script.
distributions {
    main {
        contents {
            from(rootProject.file("browser-extension")) { into("browser-extension") }
            from(rootProject.file("packages-browser/src/main/resources/com/oneofficeautomation/packages/browser/ooa-page.js")) { into("browser-extension") }
        }
    }
    // What customers install (gradlew :cli:customerDistZip): the same product without the vendor's license tools, so a
    // new install runs the 30-day evaluation and can only check license files, never make them.
    create("customer") {
        distributionBaseName.set("ooa-customer")
        contents {
            // its own start scripts first (their class path leaves the vendor tools out); the main ones are then skipped
            from(customerStartScripts) { into("bin") }
            with(main.get().contents)
            exclude("**/vendor-tools-*.jar")
            from(rootProject.file("customer"))
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
    // The setup package (gradlew :cli:setupDistZip): the customer distribution plus its own Java runtime and the
    // installer (Install.cmd / Install-OOA.ps1: guided, or silent with parameters for SCCM, Intune or GPO).
    create("setup") {
        distributionBaseName.set("ooa-setup")
        contents {
            with(getByName("customer").contents)
            from(javaRuntime) { into("runtime") }
            from(rootProject.file("customer-setup"))
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
