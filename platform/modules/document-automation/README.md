# Document Automation module

The platform's side of Document Automation, a feature module of One CommandCenter
(`docs/architecture/13-modules.md`). Version: `DocumentAutomationModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (what the module adds to One CommandCenter) | `src/main/java/.../docauto/DocumentAutomationModule.java` |
| The DocFlow proxy: screens' API, runner API, DocFlow's callbacks, review tasks, email inboxes, document triggers | `DocFlowProxy.java` |
| Where DocFlow is when Settings are empty (written by the Windows installer) | `DocFlowConfig.java` |
| Tools for AI assistants (documents_*) | `McpDocuments.java` |
| Processes in templates | `DocTemplates.java` |
| The DocFlow release this module is tested with | `src/main/resources/docflow-compat.properties` |
| Screens | `platform/ui/src/modules/document-automation` |
| The DocFlow service itself | the separate `oneoffice-docauto` repository, interface `/api/docs/v1` (`docs/integration/document-automation-contract.md`) |

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest`
and `ModuleMapTest` check that.

```bash
# the module's tests (the live ones run against a real DocFlow with -Pooa.test.docflow=http://127.0.0.1:8085)
./gradlew :document-automation:test
```
