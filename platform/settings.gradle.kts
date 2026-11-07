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
include("low-code")
project(":low-code").projectDir = file("modules/low-code")
include("one-chat")
project(":one-chat").projectDir = file("modules/one-chat")
include("testing")
project(":testing").projectDir = file("modules/testing")
include("governance")
project(":governance").projectDir = file("modules/governance")
include("insights")
project(":insights").projectDir = file("modules/insights")
include("integration")
project(":integration").projectDir = file("modules/integration")
include("runner-management")
project(":runner-management").projectDir = file("modules/runner-management")
include("orchestration")
project(":orchestration").projectDir = file("modules/orchestration")
