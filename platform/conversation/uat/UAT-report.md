# One Chat UAT report

Run 2026-11-04 01:46 on a fresh Command Center (installed build, embedded database, port 8732), driven only through its HTTP API by people with their own sign-ins and a scripted runner. **38 passed, 0 failed.**

| Area | Check | Result | Detail |
|---|---|---|---|
| Deploy | fresh Command Center starts and prints a first password | PASS | port 8732 |
| Setup | admin, jane (bot_runner), val (viewer) and aria (second admin) sign in | PASS |  |
| Setup | four agents checked in to production (three with chat, one with the older ui.chat) | PASS |  |
| Schema | ooa validate accepts a valid chat block | PASS | exit 0 |
| Schema | ooa validate rejects risk R9 and an unknown field | PASS | exit 1; SLF4J(I): Connected with provider of type [ch.qos.logback.classic.spi.LogbackServiceProvider] C:\OOA\one-chat-uat\run\bad.flow.json: 2 problem(s) [null] $.chat.risk: does not have a value in the enume |
| Runner | with no runner set, an R0 request says so and starts nothing | PASS | no_runner: No runner is online to run AP aging report right now. Try again later. |
| Setup | a runner registers and is set as the chat runner | PASS | dev_9b7de145fbda4c1c |
| R0 | a read-only agent starts at once | PASS | Started AP aging report (run run_8c2e887975a74a28). I'll tell you here when it finishes. |
| R0 | the runner receives the chat's run; the optional input is left to its default | PASS | run run_8c2e887975a74a28, inputs: {} |
| Run updates | when the run ends, the chat says so with its results | PASS | AP aging report (run run_8c2e887975a74a28) finished. Results: overdue_inr = 1840000, note = ok. |
| R2 | a missing input is asked for with the agent's own prompt and its choices | PASS | Which vendor should I process? (Acme, Globex) |
| R2 | the next missing input uses its description | PASS | What should I use for the earliest invoice date? |
| R2 | with every input known, a summary waits for yes | PASS | I'll run Process vendor invoices with vendor = Globex, from = 2026-11-04. Shall I go ahead? (yes/no) |
| R2 | yes starts the run | PASS | Started Process vendor invoices (run run_920922ff17d34a14). I'll tell you here when it finishes. |
| R2 | a second yes does not start it again | PASS | not_understood: Sorry, I didn't understand that. Ask for an agent by its name, for example "AP aging report", or ask "what are my runs?". |
| Run updates | a failed run is reported with its reason | PASS | Process vendor invoices (run run_920922ff17d34a14) failed: Vendor Globex is locked in SAP (yes run run_920922ff17d34a14, runner got run_920922ff17d34a14, run status failed) |
| R2 | no cancels a proposal | PASS | OK, I won't run Process vendor invoices. |
| Rules mode | an unrelated request is not understood and suggests examples | PASS | Sorry, I didn't understand that. Ask for an agent by its name, for example "AP aging report", or ask "what are my runs?". |
| Rules mode | an alias reaches its agent | PASS | question: Which vendor should I process? (Acme, Globex) |
| Rules mode | an older agent with ui.chat still works | PASS | Started Old style report (run run_453c2df255444bc5). I'll tell you here when it finishes. |
| Permissions | a viewer cannot start agents from chat | PASS | not_understood: Sorry, I didn't understand that. Ask for an agent by its name, or ask "what are my runs?". |
| R3 | after yes, an R3 agent waits for approval and starts nothing | PASS | I've asked for approval (task task_13ab6ba16bb749d8). Vendor payment run runs once someone other than you approves it in the Task inbox; I'll tell you here. |
| R3 | the requester cannot approve their own request, even as admin | PASS | 403 CR.FORBIDDEN |
| R3 | the approval is in the second admin's Task inbox | PASS |  |
| R3 | a second person's approval starts the run and the requester's chat is told | PASS | aria approved Vendor payment run; it started as run run_a4d6432f9ab141d3. |
| R3 | a rejection is told to the requester with the comment | PASS | aria rejected Payment run: not this week |
| Languages | German console: the question is in German (agent prompt kept) | PASS | Which vendor should I process? (Acme, Globex) |
| Languages | German summary, and heute is understood as today | PASS | Ich führe Process vendor invoices aus mit vendor = Acme, from = 2026-11-04. Soll ich fortfahren? (ja/nein) |
| Languages | nein cancels | PASS | In Ordnung, ich führe Process vendor invoices nicht aus. |
| Languages | French: summary in French, oui starts the run | PASS | Je vais exécuter Process vendor invoices avec vendor = Acme, from = 2026-11-04. Je continue ? (oui/non) / Process vendor invoices a démarré (exécution run_6efc11eeffa14b7f). Je vous préviens ici quand elle se termine. |
| Languages | Japanese: summary in Japanese, iie cancels | PASS | Process vendor invoices を次の内容で実行します: vendor = Acme, from = 2026-11-04。 実行してよろしいですか？ (はい/いいえ) / 了解しました。Process vendor invoices は実行しません。 |
| Languages | Spanish: not understood in Spanish | PASS | Lo siento, no lo he entendido. Pida un agente por su nombre, por ejemplo «AP aging report», o pregunte «what are my runs?». |
| Settings | an unknown role in chat mode per role is refused | PASS | Each line is role=mode, with an existing role and a mode of deterministic, llm, hybrid |
| Settings | an unknown mode is refused | PASS | Each line is role=mode, with an existing role and a mode of deterministic, llm, hybrid |
| Settings | bot_runner gets the model mode (no model connection here, so it says so); admin keeps rules mode | PASS | jane: not_understood: I couldn't get an answer from the AI model (is not available: No One Model Connection is available to you). Try again, or ask for an agent by its name. / admin: started |
| Restart | a chat half-way through continues after the Command Center restarts | PASS | I'll run Process vendor invoices with vendor = Acme, from = 2026-11-04. Shall I go ahead? (yes/no) |
| Restart | earlier chats and their run updates are still there | PASS | 3 messages |
| Audit | chat runs are recorded with source chat | PASS | total 6 |
