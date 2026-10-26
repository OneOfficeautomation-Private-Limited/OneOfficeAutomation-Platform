// Bot Agent: registers a device with Control Room, picks up jobs and runs each bot in its own worker process.
// Talks to Control Room over HTTP only (no compile-time dependency on the control-room module).
dependencies {
    implementation(project(":packages"))
    implementation("net.java.dev.jna:jna-platform:5.14.0")   // the screen state, lock and sign-out (Windows)
}
