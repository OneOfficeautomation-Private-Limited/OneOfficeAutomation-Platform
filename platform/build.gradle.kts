// ONEOFFICEAUTOMATION platform (Java 21). Modules follow architecture section 15.
subprojects {
    apply(plugin = "java-library")
    group = "com.oneofficeautomation"
    version = "0.1.0"

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
        useJUnitPlatform()
        systemProperty("ooa.contracts", rootProject.file("../contracts").absolutePath)
        systemProperty("ooa.examples", rootProject.file("../examples").absolutePath)
        // -Pooa.test.db=jdbc:sqlserver://localhost:1433;encrypt=false (+ ooa.test.db.user, ooa.test.db.password) runs the tests on SQL Server
        for (k in listOf("ooa.test.db", "ooa.test.db.user", "ooa.test.db.password")) project.findProperty(k)?.let { systemProperty(k, it) }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
