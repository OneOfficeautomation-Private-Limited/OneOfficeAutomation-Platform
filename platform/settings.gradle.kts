rootProject.name = "oneofficeautomation-platform"

include("sdk", "runner", "packages", "adapters-jackson", "conversation", "control-room", "agent", "cli", "vendor-tools")

// Feature modules of One CommandCenter (docs/architecture/13-modules.md), each in platform/modules/<name>
include("document-automation")
project(":document-automation").projectDir = file("modules/document-automation")

// Optional command modules, listed in gradle.properties (ooa.optionalModules).
val optionalModules = (providers.gradleProperty("ooa.optionalModules").orNull ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
optionalModules.forEach { include(it) }
include("coe")
project(":coe").projectDir = file("modules/coe")
include("ai-services")
project(":ai-services").projectDir = file("modules/ai-services")
