dependencies {
    api(project(":runner"))
    testRuntimeOnly("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.hsqldb:hsqldb:2.7.3")   // stored procedures with OUT parameters, which H2 lacks
}
