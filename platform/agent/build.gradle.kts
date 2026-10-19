// Bot Agent: registers a device with Control Room, picks up jobs and runs each bot in its own worker process.
// Talks to Control Room over HTTP only (no compile-time dependency on the control-room module).
dependencies {
    implementation(project(":packages"))
}
