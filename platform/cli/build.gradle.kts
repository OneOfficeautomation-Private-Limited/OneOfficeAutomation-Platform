import java.security.MessageDigest
import java.time.Instant

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

// Windows sign-in to SQL Server (integrated security, also for a gMSA service account) needs the driver's native
// library next to the jars; the start scripts and services put lib\ on java.library.path.
val nativeLibs by configurations.creating
dependencies { nativeLibs("com.microsoft.sqlserver:mssql-jdbc_auth:12.8.1.x64@dll") }

/** Adds lib\ to java.library.path in a generated ooa.bat (for the SQL Server driver's Windows sign-in library). */
fun libraryPath(bat: File) = bat.writeText(bat.readText().replace(Regex("(?m)^set DEFAULT_JVM_OPTS=(.*?)\r?$")) {
    "set DEFAULT_JVM_OPTS=" + it.groupValues[1] + " \"-Djava.library.path=%APP_HOME%\\lib\"\r" })

tasks.named<CreateStartScripts>("startScripts") { doLast { libraryPath(outputDir!!.resolve("ooa.bat")) } }

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
        libraryPath(bat)
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

// The software bill of materials (gradlew :cli:sbom): every third-party library the customer distribution ships, as
// CycloneDX 1.5 JSON with its Maven coordinates and SHA-256, for the customer's vulnerability scanning. It goes into the
// customer and setup packages as sbom.cdx.json.
val sbom by tasks.registering {
    val out = layout.buildDirectory.file("sbom/sbom.cdx.json")
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    outputs.file(out)
    doLast {
        val components = mutableListOf<Map<String, Any>>()
        for (a in runtime.get().incoming.artifacts.artifacts.sortedBy { it.id.componentIdentifier.displayName }) {
            val id = a.id.componentIdentifier
            if (id !is org.gradle.api.artifacts.component.ModuleComponentIdentifier) continue   // our own modules are the product itself
            val sha = MessageDigest.getInstance("SHA-256").digest(a.file.readBytes()).joinToString("") { "%02x".format(it) }
            components.add(mapOf("type" to "library", "group" to id.group, "name" to id.module, "version" to id.version,
                "purl" to "pkg:maven/${id.group}/${id.module}@${id.version}", "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha))))
        }
        val bom = mapOf("bomFormat" to "CycloneDX", "specVersion" to "1.5", "version" to 1,
            "metadata" to mapOf("timestamp" to Instant.now().toString(),
                "component" to mapOf("type" to "application", "name" to "ONEOFFICEAUTOMATION", "version" to project.version.toString(),
                    "supplier" to mapOf("name" to "ONEOFFICEAUTOMATION"))),
            "components" to components)
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(bom)))
    }
}

// The browser connector extension ships in the install (browser-extension\), with the shared page script.
distributions {
    main {
        contents {
            from(rootProject.file("browser-extension")) { into("browser-extension") }
            from(rootProject.file("packages-browser/src/main/resources/com/oneofficeautomation/packages/browser/ooa-page.js")) { into("browser-extension") }
            from(nativeLibs) { into("lib"); rename { "mssql-jdbc_auth-12.8.1.x64.dll" } }
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
            from(sbom)
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
