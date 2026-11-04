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
        systemProperty("ooa.contracts", rootProject.file("../contracts").absolutePath)
        systemProperty("ooa.examples", rootProject.file("../examples").absolutePath)
        // -Pooa.test.db=jdbc:sqlserver://localhost:1433;encrypt=false (+ ooa.test.db.user, ooa.test.db.password) runs the tests on SQL Server
        for (k in listOf("ooa.test.db", "ooa.test.db.user", "ooa.test.db.password")) project.findProperty(k)?.let { systemProperty(k, it) }
        // temp files of tests go to build/tmp/<task>-files, emptied before each run and by clean, never to the user's
        // Temp folder (27,215 folders had piled up there by 1 Nov 2026)
        val testTemp = layout.buildDirectory.dir("tmp/$name-files").get().asFile
        systemProperty("java.io.tmpdir", testTemp.absolutePath)
        doFirst { testTemp.deleteRecursively(); testTemp.mkdirs() }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
