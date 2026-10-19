rootProject.name = "oneofficeautomation-platform"

include("sdk", "runner", "packages", "adapters-jackson", "cli")

// Optional command modules, listed in gradle.properties (ooa.optionalModules).
val optionalModules = (providers.gradleProperty("ooa.optionalModules").orNull ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
optionalModules.forEach { include(it) }
