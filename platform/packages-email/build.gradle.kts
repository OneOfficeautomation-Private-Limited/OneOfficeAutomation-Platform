// Email: SMTP sending and IMAP reading (Eclipse Angus Mail, the Jakarta Mail implementation).
dependencies {
    api(project(":packages"))
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    // in-memory SMTP/IMAP server: proves the read status of emails is kept (EmailReadStatusTest)
    testImplementation("com.icegreen:greenmail:2.1.2")
}
