# Validation report: Part 1 (Studio editing, conditions, variables, live runs, Capture, search)

Date: 19 October 2026. Scope: everything delivered in this version. Part 2 (Control Room, Bot Agent, public
repository with check-in/check-out) gets its own report.

## Automated results

| Suite | Result |
| --- | --- |
| Java unit and integration tests (SDK, Runner, packages, connectors, AI, OCR, PGP, queues, Studio) | 68 passed, 0 failed |
| Conformance cases shared with the Python reference | 15 of 15 passed |
| Browser tests against a real Studio server (`cli/src/test/ui/studio.ui.test.js`) | 96 passed, 0 failed |
| Architecture rules (`ArchitectureTest`, runs in every build) | 5 of 5 passed |

## Module-level checks (enforced by ArchitectureTest)

- The SDK depends on nothing else; the Runner knows no packages and no Studio code.
- Built-in packages never depend on Studio or on optional modules.
- Optional modules (Office, Email, PDF, Browser, File transfer, Windows) depend only on the SDK, Runner and built-in
  packages, never on each other; Studio loads them by name, so removing one from `gradle.properties` never breaks a build.
- Every command, condition and loop iterator has a display name, a description and documented inputs; ids are
  snake_case; first-party package ids start with `ooa.`; package ids are unique.

## Enterprise checks

- **Security:** no shell-based process execution anywhere (argument lists only); all XML through the XXE-hardened parser;
  packages never print to the console, so every log line passes through secret masking; Studio listens on 127.0.0.1 only
  and rejects cross-site requests; webhook secrets are compared in constant time; built-in variables are read-only and
  cannot be overwritten or used as output targets.
- **Data integrity:** bot, trigger and work-queue files are written atomically (temporary file, then swap); undo history
  is bounded (100 steps); paste always assigns new unique step ids.
- **Resource limits:** request bodies capped at 5 MB; at most four concurrent Studio runs; finished live runs expire after
  10 minutes; search is capped at 100 results and does not follow links.
- **Run control:** pause and stop also reach child bots and parallel branches; stopping a paused run ends it cleanly.

## Issues found during validation and fixed in this version

1. Trigger definitions were saved non-atomically: now atomic.
2. The Variables panel cached global values from its first opening: it now reloads every time.
3. The built-in variables endpoint returned the literal text "$sys": now evaluated through a real step.
4. Saving a newly created bot file was not atomic: now atomic.

## Known limitations (tracked)

- The Python reference implementation does not yet know `$sys` and `$global`; they are Java-only until it is updated, so no
  conformance case uses them.
- Optional modules (including the Capture picker, element actions and the web recorder) compile only on a machine with
  Maven access; they were written against stable library APIs but are first compiled and tested on your laptop.
- Desktop (Windows UI Automation), Java and SAP recording, and email/window/hotkey triggers need the Bot Agent (Part 2).
