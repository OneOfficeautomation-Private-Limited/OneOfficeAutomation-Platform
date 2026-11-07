// Office packages: Excel without Microsoft Office installed (Apache POI), as in architecture section 5.
dependencies {
    api(project(":packages"))
    // Excel files in OneDrive and SharePoint go through the Microsoft 365 toolkit
    implementation(project(":toolkits-microsoft"))
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // legacy .doc and .ppt (HWPF, HSLF)
    implementation("org.apache.poi:poi-scratchpad:5.3.0")
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.23.1")
}
