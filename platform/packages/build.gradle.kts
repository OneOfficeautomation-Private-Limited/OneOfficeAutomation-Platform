dependencies {
    api(project(":runner"))
    implementation("com.github.mwiede:jsch:0.2.20")   // Terminal: VT hosts over SSH
    // Database: every database_type Connect offers has its driver (SQL Server, PostgreSQL and H2 come with control-room).
    // MariaDB's driver (LGPL) also serves MySQL; Oracle's is under Oracle's free-use terms; SQLite and UCanAccess are Apache 2.0.
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.11")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.9.0.25.07")
    runtimeOnly("io.github.spannm:ucanaccess:5.1.8")
    testImplementation("org.apache.sshd:sshd-core:2.14.0")   // in-process SSH host for the VT terminal tests
    testRuntimeOnly("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.hsqldb:hsqldb:2.7.3")   // stored procedures with OUT parameters, which H2 lacks
}
