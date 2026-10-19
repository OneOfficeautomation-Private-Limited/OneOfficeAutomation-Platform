// Control Room: central web console and API (users, repository, devices, runs, schedules, triggers, vault, audit).
// Storage is JDBC: embedded H2 by default (no setup), PostgreSQL in production (set --db-url).
dependencies {
    implementation(project(":packages"))
    runtimeOnly("com.h2database:h2:2.2.224")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    testRuntimeOnly("com.h2database:h2:2.2.224")
}
