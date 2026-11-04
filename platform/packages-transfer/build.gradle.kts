// File transfer: FTP/FTPS (Apache Commons Net) and SFTP (JSch, maintained fork).
dependencies {
    api(project(":packages"))
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    // in-process servers for the sync and raw-command tests
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")
    testImplementation("org.apache.ftpserver:ftpserver-core:1.2.0")
}
