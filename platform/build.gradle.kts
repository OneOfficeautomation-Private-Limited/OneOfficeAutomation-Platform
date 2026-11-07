// ONEOFFICEAUTOMATION platform (Java 21). Modules follow architecture section 15.
subprojects {
    apply(plugin = "java-library")
    group = "com.oneofficeautomation"
    version = "0.2.0"

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        // load and soak tests (@Tag("load")) run only through gradlew :cli:loadTest
        useJUnitPlatform { if (name != "loadTest") excludeTags("load") else includeTags("load") }
        // the shared contracts and examples: their contents are inputs (so a cached result is never reused after they
        // change), their place on this computer is not (so results are reused from another checkout or CI runner)
        val contracts = rootProject.file("../contracts")
        val examples = rootProject.file("../examples")
        inputs.files(fileTree(contracts)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contracts")
        inputs.files(fileTree(examples)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("examples")
        jvmArgumentProviders.add(LocalPaths(listOf("ooa.contracts" to contracts, "ooa.examples" to examples)))
        // -Pooa.test.db=jdbc:sqlserver://localhost:1433;encrypt=false (+ ooa.test.db.user, ooa.test.db.password) runs the tests on SQL Server
        for (k in listOf("ooa.test.db", "ooa.test.db.user", "ooa.test.db.password")) project.findProperty(k)?.let { systemProperty(k, it) }
        // -Pooa.test.docflow=http://127.0.0.1:8085 (+ .token, .port, .samples) runs DocFlowLiveTest against a real DocFlow
        for (k in listOf("ooa.test.docflow", "ooa.test.docflow.token", "ooa.test.docflow.port", "ooa.test.docflow.samples")) project.findProperty(k)?.let { systemProperty(k, it) }
        // temp files of tests go to build/tmp/<task>-files, emptied before each run and by clean, never to the user's
        // Temp folder (27,215 folders had piled up there by 1 Nov 2026)
        val testTemp = layout.buildDirectory.dir("tmp/$name-files").get().asFile
        jvmArgumentProviders.add(LocalPaths(listOf("java.io.tmpdir" to testTemp)))
        doFirst { testTemp.deleteRecursively(); testTemp.mkdirs() }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

/**
 * System properties that are folders on this computer: passed to the test JVM but not part of the build cache key
 * (docs/architecture/13-modules.md, phase 7), so a test result is reused across checkouts and CI runners. What is in
 * those folders is declared as an input where it matters.
 */
class LocalPaths(@get:Internal val paths: List<Pair<String, File>>) : CommandLineArgumentProvider {
    override fun asArguments() = paths.map { (k, f) -> "-D$k=" + f.absolutePath }
}
