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

---

# Validation report: Part 2 (Control Room, Bot Agent, public repository)

## Automated results

| Suite | Result |
| --- | --- |
| All Java tests (Part 1 suites plus the new ones below) | 71 passed, 0 failed |
| Control Room + Bot Agent end to end (`ControlRoomE2ETest`: real HTTP server, real H2 database, real agent with worker processes) | passed |
| Studio with Control Room, two developers (`StudioControlRoomTest`) | passed |
| Control Room console in a browser engine (`control-room/src/test/ui/console.ui.test.js`, against `ooa control-room`) | 21 passed, 0 failed |
| Studio browser tests (now including the Control Room section) | 99 passed, 0 failed |
| Architecture rules (now 6, including Control Room and Agent boundaries and parameterised SQL) | 6 of 6 passed |
| Conformance cases | 15 of 15 passed |

## What the end-to-end tests prove

- **Accounts:** one-time admin password; forced change at first sign-in (nothing else works until then); password policy;
  lockout after five failures; disabled users and expired sessions refused.
- **Access control:** each API call checks a permission; viewers cannot check in; only admins set production versions,
  register devices or read the audit log; only locker owners change credentials.
- **Repository:** invalid bots are refused at check-in with the problem list; updates need a check-out lock; a second user is
  told who holds it; bot ids are unique; versions are kept; a checked-out copy round-trips to the same repository path.
- **Devices and runs:** single-use registration keys; device secrets stored only as hashes; runs claimed atomically; each bot
  runs in its own worker process; live events stored; stop reaches the worker; lost devices fail their runs after 2 minutes.
- **Vault:** AES-256-GCM bound to each credential's name; plain secrets refused as run inputs; values never returned by the
  API or shown in the console; a bot gets a value only while running and only if its requester is a locker consumer; the
  secret never appears in run data; every read is audited.
- **Automation:** schedules fire once per due time (guarded update); webhook secrets compared in constant time; file triggers
  fire on the agent's device and pass the file path.
- **Audit:** hash-chained records; editing one past record is detected by Verify integrity.
- **Web security:** HttpOnly SameSite=Strict session cookie; a custom header required on every change (CSRF); strict
  content-security policy (no inline scripts or styles); clickjacking blocked; all data rendered as text (HTML in data is
  shown, never run).

## Module-level checks

- Control Room depends on neither Studio nor the Bot Agent; the agent talks to Control Room only over HTTP; the Runner is the
  same in Studio, on agents and in Control Room validation (all enforced by ArchitectureTest).
- All SQL is constant text with placeholders; values are never concatenated into statements (enforced).
- Portable SQL: tested on H2 (PostgreSQL mode); PostgreSQL is selected with `--db-url`. During testing a reserved word
  (`value`) was found that H2 rejects and was renamed, which is exactly what the real-database test exists to catch.

## Issues found and fixed during Part 2 validation

1. Column name `value` is reserved in H2: renamed to `json_value`.
2. Studio returned 400 for Control Room conflicts: it now passes 409 (locked), 403 and 404 through.
3. Studio webhooks with a wrong secret now return 403 instead of 400.

## Known limitations (tracked)

- PostgreSQL is supported by configuration but was not tested in this environment (no PostgreSQL server here); run the
  end-to-end test against PostgreSQL before production.
- Work queues are still per device (workspace files); central Control Room queues with SLAs come next.
- Control Room serves plain HTTP; use an HTTPS reverse proxy (IIS, nginx) when other machines connect.
- The agent runs as a scheduled task in the signed-in user's session; unattended auto-login and a Windows service wrapper
  are part of the installer phase.
- Process Composer / Co-Pilot (forms and human tasks) and the Bot Store remain planned.
