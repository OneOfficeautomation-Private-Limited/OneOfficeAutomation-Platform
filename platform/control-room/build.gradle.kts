// Control Room: central web console and API (users, repository, devices, runs, schedules, triggers, vault, audit).
// Storage is JDBC: embedded H2 by default (no setup), PostgreSQL in production (set --db-url).
dependencies {
    implementation(project(":packages"))
    // work items from Excel workbooks
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    runtimeOnly("com.h2database:h2:2.2.224")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    testRuntimeOnly("com.h2database:h2:2.2.224")
}
