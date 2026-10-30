# Starting the next version

This draft version ended on 2026-10-25 at `master` commit `ed4fd07` (PR #6). This page is for the next working
session: what the draft contains, what is still open, and how to run and test it. Start a new session with:

> Read docs/NEXT_VERSION.md, then …

## What this version contains

ONEOFFICEAUTOMATION is One Command Center, Agent Studio, and the One Runner. They are started together on this PC by
`platform/scripts/start-ooa.bat`. The code layout is described in [ONEOFFICEAUTOMATION architecture collection](architecture/README.md), and every
command is listed in [COMMAND_CATALOG.md](COMMAND_CATALOG.md).

This version adds the results of a full comparison with Automation Anywhere (AA) Community Edition:

- **26 use cases built in both products.** The cases are P1–P6 (public websites), W1–W10 (local demo web apps),
  D1–D5 (desktop) and O1–O5 (Office, mail, REST, AI). Ours are in `examples/use-cases/`; AA's are in the AA folder
  **Bots\Use cases**. See [research/use-cases.md](research/use-cases.md).
- **Command-by-command review.** The step tables for every case are in
  [research/use-case-command-review.md](research/use-case-command-review.md).
- **The gap list, with what was fixed.** See [research/use-case-gaps.md](research/use-case-gaps.md). In short:
  - Studio:
    - readable steps;
    - calculations without JSON;
    - a Loop panel that asks for the kind of loop first;
    - Catch and Finally in the palette;
    - groups and text options in the If builder;
    - row editors instead of JSON;
    - plain labels and drop-down words;
    - one element field per web step;
    - *Selectors to objects*;
    - a Required marker on empty required fields.
  - Capture:
    - a better choice of properties (labels, test attributes, no made-up ids, no text containing numbers);
    - object recording (`OOA_RECORD_OBJECTS`).
  - New and extended commands:
    - Get text "if not found", Look up value, Extract list, the Each page loop, Element text condition;
    - waits on conditions, Wait for "any of", click method;
    - database URL builder, SQL rows and mailbox loops, columns into variables;
    - window commands by process id, text and file options.
- **Capture examples.** `examples/use-cases/*-capture.flow.json` are the web cases with captured objects instead of
  selectors.

## Still open (pick from here)

The details are in [research/use-case-gaps.md](research/use-case-gaps.md).

| Item | What is left |
| --- | --- |
| O23 | The use-case samples still use the long forms (Assign for counters, Replace + `toNumber`, Read text + Parse). Switch them to Number Increment, Text to number, Read JSON file and Extract text |
| O25 / F2 | Excel: SharePoint and OneDrive paths |
| O11 | Desktop objects still give the window title in each step; a window chosen once and reused is missing (Window commands now take a process id) |
| Sessions | Excel and Word steps should fill in the open session by themselves, instead of custom session names |
| AA side | 9 of 16 web cases pass with Capture in AA. P2–P6, W4 and W9 fail on AA limits (id-only DOM XPaths, tables, off-screen elements, password boxes). A real recording of the failing elements would finish them |

## Run and test

- **Run:** see [Getting started](user-guide/01-getting-started.md). Start with `platform\scripts\start-ooa.bat`, then go to
  http://localhost:8720 (Command Center) and http://localhost:8720/studio/ (Agent Studio). A forgotten password is
  reset with `.\gradlew.bat :cli:run --args="reset-admin"` while the server is stopped.
- **Build:** `cd platform` then `.\gradlew.bat :cli:installDist`.
- **Tests:** `.\gradlew.bat :runner:test :packages:test :packages-email:test :packages-windows:test :packages-browser:test :cli:test`.
  - Command samples (JSON integration tests) are in `platform/cli/src/test/resources/command-samples/`.
  - Known failure: `ooa.datetime/is_business_hours - 24h window always true` fails at weekends.
- **Studio tests:** `npm run check` in `platform/ui` for the screens; `StudioServerRoutesTest` and the other Studio tests in `:cli:test` for its server. Classic Studio and its jsdom page test were removed on 2026-10-30.

## Things to know

- **Line endings:** files keep their own line endings (many Java and Studio files are CRLF). Check
  `git diff --stat` before committing; a whole file showing as changed means its line endings were rewritten.
- **Studio's screens:** TypeScript and React in `platform/ui` (see `docs/architecture/11-developer-guide.md` 7.2);
  Classic Studio's `studio.js` was removed on 2026-10-30.
- **Java signatures:** new action inputs go at the end of the Java method, with an unannotated overload keeping the
  old signature for existing callers.
- **AA:** the user signs in. Only the use-case bots in **Bots\Use cases** may be changed, and AA behaviour is
  described in our own words. An AA run is tied to the open editor page: navigating, a dialog or resizing the page
  cancels it.
- **W cases:** W1–W10 open the demo pages in `C:\Users\virup\OOA Workspace\Use cases\site`. For AA they need
  `python -m http.server 8781 --bind 127.0.0.1 --directory "<that folder>"`.
