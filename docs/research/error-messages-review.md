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
| Page | Loads that fail silently (`.catch(() => {})`), so a panel stays empty with no reason | 47 | Batch E2: each says why it is empty (minor) |
| Command | Toolkit commands raising `ActionException` without a suggested fix | ~42 of 574 | Batch E3 |
| Usage / server | Server errors (`OoaException`) without a suggested fix | 909 of 1,412 (control-room 444, cli 99, runner 73, low-code 59, agent 34, document automation 33, others) | Batches E4 to E7, by module |
| Usage / server | Plain `RuntimeException` / `IllegalStateException` / `IllegalArgumentException` thrown | 160 | Batch E8: give each a code and a fix, or show it as an internal error with the log reference |
| All | No single place that lists every error code with its meaning and fix | — | Batch E9: an error catalogue, generated from the code, in the help |

## Batches

- **E1** Studio: show the fix everywhere (one helper, as the console now does). **Done 13 Nov 2026**: `errorText(e)` for any failure and `runErrorText(err)` for a run's or check's error, in Studio (agent editor, AI agent editor, create, connections, conditions, pickers, triggers, object repository, variables, run panel, check-in) and Phone notifications.
- **E2** Silent failures: every empty panel says why (minor notice), every page-level failure is major with Retry.
- **E3** Commands: the 42 toolkit errors without a fix.
- **E4** control-room: 444 errors without a fix (largest; split by area: users and roles, agents and repository, runs, schedules and triggers, settings).
- **E5** cli and runner: 172.
- **E6** low-code, document automation, insights, governance: 133.
- **E7** agent, sdk, packages and the other modules: the rest.
- **E8** the 160 plain Java exceptions.
- **E9** the error catalogue and the major/minor notice component.

Each batch is built, tested and pushed on its own, as the command review was.
