// Swap-ready adapters: Jackson for Flow JSON, networknt for JSON Schema 2020-12 validation.
dependencies {
    api(project(":runner"))
    implementation(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    testImplementation(project(":packages"))
}

// The bundled schema must equal the contract; refresh it with: gradlew :adapters-jackson:syncFlowSchema
tasks.register<Copy>("syncFlowSchema") {
    from(rootProject.file("../contracts/flow-schema/flow.v1.json"))
    into("src/main/resources")
}
