// File transfer: FTP/FTPS (Apache Commons Net) and SFTP (JSch, maintained fork).
dependencies {
    api(project(":packages"))
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
}
