// Control Room: central web console and API (users, repository, devices, runs, schedules, triggers, vault, audit).
// Storage is JDBC: embedded H2 by default (no setup); SQL Server or PostgreSQL in production (config/ooa-db.properties).
dependencies {
    implementation(project(":packages"))
    // work items from Excel workbooks
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // Git sync of the agent repository (pure Java: the server needs no git installed)
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    runtimeOnly("com.h2database:h2:2.2.224")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")
    testRuntimeOnly("com.h2database:h2:2.2.224")
}
