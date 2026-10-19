// Office packages: Excel without Microsoft Office installed (Apache POI), as in architecture section 5.
dependencies {
    api(project(":packages"))
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.23.1")
}
