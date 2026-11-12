# Error messages review (improvement list 12 Nov 2026, item 11)

Goal: whenever something fails or does not respond, say **what** failed, **why** (as far as we know), and **what to do**,
at every level: the tool (server, services), a page or sub-option, a command (a step in an agent), and usage (a run, a
trigger, a schedule). This is the first pass: what we found, what is fixed now, and the batches for the rest.

## Major and minor (the notification style)

| Kind | When | How it shows | Stays until |
|---|---|---|---|
| **Major** | The tool or a service is down, a page cannot load its data, a run failed, a sign-in ended | Red bar at the top of the page (`role="alert"`), the code, what happened, what to do, and Retry where retrying can help | Fixed, or closed by the person |
| **Minor** | One action did not work (a save, one row of a bulk action), a value is wrong, something is slow | Inline next to the control, or an amber notice that closes by itself after 8 s | The next action, or 8 s |
| **Info** | Done, nothing to do | The existing green notice | 4 s |

Every message is `What happened. What to do.` (both in our own words), with the code in brackets for support, for example
`One CommandCenter is not answering. Check that it is running and that this computer can reach it, then try again. (NET.UNREACHABLE)`.

## Found in the first pass (13 Nov 2026)

| Level | Finding | Count | State |
|---|---|---|---|
| Tool | A request that could not reach the server showed the browser's text ("Failed to fetch") | every page | **Fixed**: `NET.UNREACHABLE` with what to do |
| Tool | A proxy or gateway page (HTML) instead of data showed a JSON parse error | every page | **Fixed**: `HTTP.NOT_JSON` / `HTTP.502` with what to do |
| Tool | An error status without the API's own error showed only "Request failed" or the status text | every page | **Fixed**: 401, 403, 404, 408, 413, 429, 502, 503, 504 and other 5xx each say what to do |
| Tool | Document Automation said only "not reachable" | 1 | **Fixed** (item 10): "not set up" apart from "not answering", each with where to fix it |
| Page | Console pages showed `e.message` only; the server's suggested fix was dropped | 42 pages | **Fixed**: the message now carries the fix (`withFix`), once |
| Page | Studio screens showed `e.error.message` and dropped the fix (or showed "Error: ..." from `String(e)`) | 14 places, plus 6 run and check results | **Fixed** (E1): one helper, `errorText` / `runErrorText` |
| Page | Loads that failed silently (`.catch(() => {})`), so a list or panel stayed empty with no reason | 47 found; 35 were real loads | **Fixed** (E2): a minor notice says what could not be loaded and why; the other 12 stay quiet on purpose (browser storage, clipboard, full screen, sign-out, polling every 0.8 s while recording, the alerts badge, a runner file that may not exist yet) |
| Command | Command errors (`ActionException`) without a suggested fix | 383 found when counted properly (toolkits 87, plus 40 that passed `null`; core packages, browser, e-mail, office, PDF, transfer and Windows 296) | **Fixed** (E3): every command error now carries a fix. The 127 in toolkits have their own; the rest get the fix for their code (`CommandFixes`), until E3b writes their own |
| Usage / server | Server errors (`OoaException`) without a suggested fix | 909 of 1,412 (control-room 444, cli 99, runner 73, low-code 59, agent 34, document automation 33, others) | control-room **fixed** (E4): 244 have their own fix, the 207 input checks (CR.BAD_INPUT) and any other error without one get the fix for their code where the API answers (`ServerFixes`). The rest: E5 to E7 |
| Usage / server | Plain `RuntimeException` / `IllegalStateException` / `IllegalArgumentException` thrown | 160 | Batch E8: give each a code and a fix, or show it as an internal error with the log reference |
| All | No single place that lists every error code with its meaning and fix | — | Batch E9: an error catalogue, generated from the code, in the help |

## Batches

- **E1** Studio: show the fix everywhere (one helper, as the console now does). **Done 13 Nov 2026**: `errorText(e)` for any failure and `runErrorText(err)` for a run's or check's error, in Studio (agent editor, AI agent editor, create, connections, conditions, pickers, triggers, object repository, variables, run panel, check-in) and Phone notifications.
- **E2** Silent failures: every empty panel says why (minor notice). **Done 13 Nov 2026**: `reportMinor(what, e)` and `<MinorNotices />` (amber line, closes after 8 s, held under the pointer, the same text once) in the console and in Studio; 35 loads in 19 files.
- **E2b** Every page-level failure as major with Retry. **Done 13 Nov 2026**: `PageError` with Retry beside the bar on the 54 alerts that stand in for a page's data (console pages and the modules' pages); Retry mounts the page again, so it loads again (`PageRetry` in the console). Alerts for a failed save or a dialog keep the plain bar.
- **E3** Commands. **Done 13 Nov 2026**: `CommandFixes` in the SDK gives any command error without a fix the one for its code (CANCELLED, IO_ERROR, NOT_FOUND, WINDOWS_ONLY, BAD_*, NO_*, *_FAILED and others); the 127 toolkit errors have their own fix; `CommandFixesTest` fails when a toolkit error leaves its fix out.
- **E3b** Their own fix for the errors in the core packages. **Done 13 Nov 2026**: 296 without a fix and 115 passing `null` (browser, LLM client, data store, generative AI, work queues, terminal, PDF, Windows, e-mail, file transfer and the rest) have their own; `CommandFixesTest` covers the core packages.
- **E4** control-room. **Done 13 Nov 2026**: 442 found plus 9 that passed `null`; the 244 that are not input checks have their own fix (sign-in and SSO, vault and external vaults, database, TLS, licence, schedules, runs and waits, queues, repository, templates, tenants and regions, backups); every API error now leaves the server with a fix (`ServerFixes` where errors are sent, which also covers the modules' routes); `ServerFixesTest` fails when a new error other than an input check leaves its fix out.
- **E5** cli and runner. **Done 13 Nov 2026**: 148 without a fix and 6 passing `null` (Studio's server, workspace, triggers, Control Room link, database and region commands, release signing, the runner and expressions) have their own; `CommandFixesTest` covers cli and runner too.
- **E6** the modules. **Done 13 Nov 2026**: every module error that is not an input check has its own fix (knowledge bases, skills, ideas, Document Automation, production approval, dashboards, API tasks, Git, MCP, apps and data store, chat, workflows, pool scaling); input checks get the API's fallback.
- **E7** the runner service (agent), SDK, vendor tools, conversation and the JSON adapter. **Done 13 Nov 2026**: their errors have their own fix. `CommandFixesTest` now guards every module and service (159 fixes in E6 and E7).
- **E8** the 160 plain Java exceptions. **Done 13 Nov 2026**: most are internal checks people never see (missing algorithms, module wiring, CLI switches); what reaches people now says what to do: a toolkit's plain IllegalArgumentException becomes ACTION.INPUT_INVALID with its message and "correct the step's input" (Excel sheet names, hotkeys, key-press triggers, title patterns); any other plain exception in a step is ACTION.UNEXPECTED with "run again, then report it"; One CommandCenter's internal errors carry a reference (ERR-...) that is printed in the server log beside the details, and say to give it to an administrator; Excel's sheet-name checks are EXCEL.BAD_SHEET with their own fixes.
- **E9** the error catalogue and the major/minor notice component. **Done 13 Nov 2026**: [docs/reference/error-codes.md](../reference/error-codes.md) lists 1,623 codes in 150 areas with what each means and what to do, generated from the code (`docs/research/tools/make-error-catalogue.py`) and linked from the user guide; `CommandFixesTest` fails when a code is missing from it. The notices are E2 (minor, amber) and E2b (major, red with Retry).

Each batch is built, tested and pushed on its own, as the command review was.
