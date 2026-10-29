import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

plugins { application }

val optionalModules = ((findProperty("ooa.optionalModules") as String?) ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

dependencies {
    implementation(project(":packages"))
    implementation(project(":control-room"))
    implementation(project(":agent"))
    // the command line: commands, options, help and shell completion
    implementation("info.picocli:picocli:4.7.6")
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

// The new Studio screens (docs/architecture/12-ui-redesign.md): platform/ui, TypeScript and React, built by npm into
// the resources and served by Studio at /next/. uiInstall installs the locked packages (npm ci, no install scripts);
// uiBuild type-checks, tests, checks licences and the audit, then builds. Customers get only the built files.
val uiDir = rootProject.file("ui")
val uiOut = layout.buildDirectory.dir("generated/ui")
val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

val uiInstall by tasks.registering(Exec::class) {
    description = "Installs the new Studio's npm packages exactly as locked (npm ci)."
    workingDir = uiDir
    inputs.files(File(uiDir, "package.json"), File(uiDir, "package-lock.json"), File(uiDir, ".npmrc"))
    outputs.dir(File(uiDir, "node_modules"))
    commandLine(npm, "ci", "--no-fund")
}

val uiBuild by tasks.registering(Exec::class) {
    description = "Checks and builds the new Studio (platform/ui) into the CLI's resources."
    dependsOn(uiInstall)
    workingDir = uiDir
    inputs.dir(File(uiDir, "src"))
    inputs.files(File(uiDir, "index.html"), File(uiDir, "vite.config.ts"), File(uiDir, "tsconfig.json"), File(uiDir, "package-lock.json"), File(uiDir, "tools"))
    outputs.dir(uiOut)
    commandLine(npm, "run", "check")
}

sourceSets.main { resources.srcDir(files(uiOut).builtBy(uiBuild)) }

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

// Load and soak (Q5): gradlew :cli:loadTest [-Pooa.load.runners=100 -Pooa.load.items=2000 -Pooa.load.audit=100000]
tasks.register<Test>("loadTest") {
    description = "Many runners, a long work queue and a large audit trail against one Command Center."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    for (k in listOf("ooa.load.runners", "ooa.load.items", "ooa.load.audit")) project.findProperty(k)?.let { systemProperty(k, it) }
}

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
// customer and setup packages as sbom.cdx.json. The npm packages built into the new Studio (platform/ui, not its
// build tools) are listed too, with their npm address and the lock file's SHA-512.
val sbom by tasks.registering {
    val out = layout.buildDirectory.file("sbom/sbom.cdx.json")
    val runtime = configurations.named("runtimeClasspath")
    val uiLock = File(uiDir, "package-lock.json")
    inputs.files(runtime, uiLock)
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
        val lock = groovy.json.JsonSlurper().parse(uiLock) as Map<*, *>
        for ((path, info) in (lock["packages"] as Map<*, *>).entries.sortedBy { it.key.toString() }) {
            val p = info as Map<*, *>
            if (path.toString().isEmpty() || p["dev"] == true) continue   // the project itself, and build tools that do not ship
            val name = path.toString().substringAfterLast("node_modules/")
            val sha512 = Base64.getDecoder().decode(p["integrity"].toString().removePrefix("sha512-")).joinToString("") { "%02x".format(it) }
            components.add(mapOf("type" to "library", "name" to name, "version" to p["version"].toString(),
                "purl" to "pkg:npm/${name.replace("@", "%40")}@${p["version"]}", "hashes" to listOf(mapOf("alg" to "SHA-512", "content" to sha512))))
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

// The native executable (Q13; gradlew :cli:nativeExecutable): the commands that need no Command Center (validate,
// run, manifest, conformance, license show, release verify), compiled ahead of time by GraalVM so they start at once
// and need no Java runtime. It needs GraalVM for JDK 21: GRAALVM_HOME, or JAVA_HOME pointing at it. The nativeImage
// source set holds what only that build uses: the reflection feature and the resources to keep.
val nativeImage by sourceSets.creating
dependencies { "nativeImageCompileOnly"("org.graalvm.sdk:graal-sdk:23.1.2") }
nativeImage.compileClasspath += sourceSets.main.get().output + configurations.named("runtimeClasspath").get()

val nativeExecutable by tasks.registering(Exec::class) {
    description = "Builds the native executable build/native/ooa with GraalVM native-image."
    group = "distribution"
    val out = layout.buildDirectory.dir("native")
    // the vendor's license tools stay out, as in the customer distribution
    val classpath = files(tasks.named("jar"), nativeImage.output) +
        configurations.named("runtimeClasspath").get().filter { !it.name.startsWith("vendor-tools") }
    inputs.files(classpath)
    outputs.dir(out)
    doFirst {
        val graal = System.getenv("GRAALVM_HOME") ?: System.getenv("JAVA_HOME")
            ?: throw GradleException("Set GRAALVM_HOME to GraalVM for JDK 21 (https://www.graalvm.org/downloads/)")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val tool = File(graal, "bin/native-image" + if (windows) ".cmd" else "")
        if (!tool.exists()) throw GradleException("No native-image in $graal: point GRAALVM_HOME at GraalVM for JDK 21")
        out.get().asFile.mkdirs()
        // an argument file: the class path is longer than a Windows command line may be
        val argFile = temporaryDir.resolve("native-image.args")
        val q = { v: String -> "\"" + v.replace("\\", "\\\\") + "\"" }
        // all charsets: the 5250 and 3270 terminals read EBCDIC code pages (IBM037 and the like)
        argFile.writeText(listOf("-cp", classpath.asPath, "-o", out.get().file("ooa").asFile.path, "--no-fallback",
            "-march=compatibility", "-H:+AddAllCharsets", "com.oneofficeautomation.cli.OoaNative").joinToString("\n") { q(it) })
        executable = tool.path
        args("@" + argFile.path)
    }
}

// The Firefox add-on as one file for Mozilla to sign (gradlew :cli:firefoxAddon): build/distributions/ooa-connector-firefox-VERSION.xpi,
// with manifest.json at its root. A signed copy installs for good; the unsigned one only as a temporary add-on.
val firefoxAddon by tasks.registering(Zip::class) {
    archiveFileName.set(provider { "ooa-connector-firefox-" + groovy.json.JsonSlurper().parse(rootProject.file("browser-extension/manifest.firefox.json")).let { (it as Map<*, *>)["version"] } + ".xpi" })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(rootProject.file("browser-extension")) { exclude("manifest.json", "manifest.firefox.json") }
    from(rootProject.file("browser-extension/manifest.firefox.json")) { rename { "manifest.json" } }
    from(rootProject.file("packages-browser/src/main/resources/com/oneofficeautomation/packages/browser/ooa-page.js"))
}

// The browser connector extension ships in the install (browser-extension\), with the shared page script.
distributions {
    main {
        contents {
            from(rootProject.file("browser-extension")) { into("browser-extension"); exclude("manifest.firefox.json") }
            from(rootProject.file("packages-browser/src/main/resources/com/oneofficeautomation/packages/browser/ooa-page.js")) { into("browser-extension") }
            // the same extension for Firefox, with its own manifest
            from(rootProject.file("browser-extension")) { into("browser-extension-firefox"); exclude("manifest.json", "manifest.firefox.json") }
            from(rootProject.file("browser-extension/manifest.firefox.json")) { into("browser-extension-firefox"); rename { "manifest.json" } }
            from(rootProject.file("packages-browser/src/main/resources/com/oneofficeautomation/packages/browser/ooa-page.js")) { into("browser-extension-firefox") }
            from(nativeLibs) { into("lib"); rename { "mssql-jdbc_auth-12.8.1.x64.dll" } }
        }
    }
    // What customers install (gradlew :cli:customerDistZip): the same product without the vendor's license tools, so a
    // new install runs the 60-day evaluation and can only check license files, never make them.
    create("customer") {
        distributionBaseName.set("ooa-customer")
        contents {
            // its own start scripts first (their class path leaves the vendor tools out); the main ones are then skipped
            from(customerStartScripts) { into("bin") }
            with(main.get().contents)
            exclude("**/vendor-tools-*.jar")
            from(rootProject.file("customer"))
            from(rootProject.file("linux")) { into("linux") }
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
