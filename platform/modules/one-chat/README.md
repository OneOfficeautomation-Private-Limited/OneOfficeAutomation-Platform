# One Chat module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 2.
Version: `OneChatModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `OneChatModule.java` |
| What it serves | /api/chat; R3 approvals (task stage chat_approval); run-ended listener; settings chat_* |
| Its classes | Chat.java, ChatModel.java (engine: the conversation library) |
| Screens | `platform/ui/src/modules/one-chat` |

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :one-chat:test`.
