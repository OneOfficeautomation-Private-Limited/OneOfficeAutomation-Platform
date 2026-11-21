# AA-parity release (November 2026): where it stands and how to continue

Work stopped on 21 Nov 2026 when the AI build helpers reached their weekly usage limit. This folder lets a developer
carry on from exactly that point. Nothing here is merged into master; no pull request is open.

## What the release is

1. The 14 Studio, command and run-window defects the user reported on 20 Nov 2026 (list below).
2. Every gap found by comparing all 1,148 AA 360 commands with ours, option by option: 559 gaps in
   [gap-list.md](gap-list.md), grouped G1 to G6. The user approved building all of them, including the large ones.
3. A more polished, denser look on every page in the brand colours (design picked on the design canvas).

## Decisions the user made (do not re-ask)

- Keep every feature we have that AA lacks; simplify fields and screens so commands are easy to understand.
- Safer defaults (for example overwrite off) apply to **new steps only**: use `@Input(newStepDefault = ...)` and keep
  `defaultValue` as it is, so saved agents behave exactly as before.
- Run window: an administrator setting in One CommandCenter (show or hidden, size, start minimised, whether Pause and
  Stop are allowed, whether people may change it per run), a per-run choice in Run now and in Studio's Run menu, the
  window also for runs started from One CommandCenter on a signed-in runner, and a Minimise button on the window.
- A called agent ("Open in new tab", and when debugging into it) opens in a new Studio tab.
- Designs: option A everywhere (run window A, message box A, tick boxes A, Find an agent A, the one file field, the
  session model, the one-line Commands panel, File properties, the short names table).
- Look for every page: deep navy frame #10243f, logo blue #0c66a3 for buttons and selection, logo violet #7c4dd6 for
  AI and highlights only, a thin blue-to-violet line under the top bar, page background #f3f6fa, white cards with a
  soft shadow, 30 px table rows. Check contrast by numbers (text at least 4.5:1, borders 3:1).
- Standing rules: plain English; AA described in our own words and our own labels; never commit `.claude/launch.json`;
  no merge, PR or release without the user's OK; push each finished item.

## Branches (all on GitHub)

| Branch | State |
|---|---|
| `studio/aa-parity-2026-11` | The release branch. Done and tested: shared pieces, items 2-8 and 11-13 (commits below). |
| `studio/aa-parity-core` (G1) | Interruption watching, trigger loop with several handlers, new conditions; last commit is work in progress. |
| `studio/aa-parity-files` (G2) | Many-files commands use name rules, File create and rename, folder create choices; CSV/JSON/XML sessions in progress. |
| `studio/aa-parity-office` (G3) | Excel's safer Create worksheet and option gaps; Word find-only mode in progress. |
| `studio/aa-parity-desktop` (G4) | The new Message box look (OoaDialog) in progress; nothing finished yet. |
| `studio/aa-parity-mail` (G5) | IMAP bug fixed (reading mail no longer marks it read); Connect sessions for web toolkits; e-mail options in progress. |
| `studio/aa-parity-enterprise` (G6) | Sessions for web-API toolkits; Active Directory rows; SAP/BAPI in progress. |

Each family branch starts from `studio/aa-parity-2026-11` at 36a442fa. A commit titled "Work in progress (stopped by
the weekly usage limit...)" holds unfinished edits that may not compile: start there, finish or fix it, and test.

### Done on the release branch

- `1b4f3165` Shared pieces: `@Input newStepDefault`, `tone`, `acceptsCredential`; files chosen by name rules
  (`sdk/FileSelection`, `{"$files": ...}`, `ActionContext.selectedFiles`); "keep checking for N seconds" wraps any
  condition; `ActionContext.loopItem()`, `outputPath()`, `shareSession()`. Items 3 (typed text stays text), 4 (Windows'
  own file dialog, `cli/NativeFileDialog`, route `fs/pick`), 7 (Yes/No as coloured tick boxes), 8 (apps by name through
  Windows' App Paths).
- `36a442fa` Items 5 and 6: short names (12 toolkits, 130 commands; old names stay searchable), one-line Commands
  panel with category sub-headings, letter jump, arrow keys in the search.
- `930ea455` Items 11 and 13: resizable picker dialogs, every folder in Find an agent; folders stay open after closing.
- `b4948a10` Items 2 and 12: File properties from the name at the top left, file state chip, tab menu; Open in new
  tab; debugging follows into the called agent's tab.

## What is left, in order

1. **Item 1, the run window.** Move `cli/RunWindow.java` into the runner module so the device runner can use it;
   design A (dark strip, sizes Small/Medium/Large, a Minimise tab at the screen edge, progress bar, last lines in
   Large, a red edge when a run fails); Studio sends `run_window` and the size again (Classic Studio did; the new
   Studio stopped asking for it on 30 Oct, which is why the window disappeared); the One CommandCenter setting and the
   Run now choice; `agent/RunWorker` shows the window for attended runs.
2. **Finish each family branch** against its section of [gap-list.md](gap-list.md), following
   [build-brief.md](build-brief.md) (how to use the shared pieces, rules, tests). Large items: Interrupt handler
   scope block (G1), PDF extract fields by area (G2), on-premises SharePoint (G3), script sessions, Java apps and a
   terminal window (G4), Google Sheets current cell (G5), native Kafka and a BAPI browser (G6).
3. **Merge** the six family branches into `studio/aa-parity-2026-11`; run the full Java suites and `npm run check` in
   `platform/ui`.
4. **Studio screens the families need**: the rich message-body editor (G5 adds `BodyEditor.tsx`), the PDF area
   picker, the BAPI browser, the condition builder ticks (G1 owns `ConditionEditor.tsx`).
5. **The polish pass** on every page (theme tokens in `platform/ui/src/theme.css`, console and Studio CSS).
6. Regenerate `docs/reference` (`docs/research/tools/make-reference.py`), fix the command map entries the comparison
   found wrong (listed at the end of each group in gap-list.md), and write the item 14 report from the causes below.
7. Check every item on a test server copy (`cli/build/install/ooa`, a free port, `OOA_DOCFLOW_AUTOSTART=false`,
   `--no-browser --no-agent` with scratch data and workspace), then push.

## The user's 14 items

1. Run window sizes and an administrator minimise option (left).
2. Rename a file while editing (done).
3. Message box: numbers typed are text (done); a more professional look (G4, in progress).
4. The real File Explorer for Browse (done).
5. One-line Commands panel, no "Also called" (done).
6. Letter jump to toolkits (done).
7. Yes/No as tick boxes, red/green (done).
8. Open program by app name (done).
9. Start/End session in JSON, XML, CSV/TXT and every toolkit that needs it (families G2, G4, G5, G6).
10. Regular-expression options (the shared name-rule file field: done; families adopt it in their commands).
11. Run agent picker cut off: resizable views (done).
12. Open the called agent in a new tab (done).
13. Folder collapses after closing an agent (done).
14. Why these were missed (report left; causes below).

## Why earlier comparisons missed these (item 14 findings)

- The command map matched AA commands to ours by name and counted that as covered; options, control types and real
  inputs (a number typed in a text field) were never compared.
- AA's file fields are a special type with "specific path or regular expression" built in; the October field-by-field
  review read it as one plain file field.
- "Deliberately different" choices (no sessions for JSON, XML, CSV, scripts and web toolkits) were written into the
  comparison data and never put to the user as decisions.
- Removing Classic Studio compared the server addresses each Studio called, not the fields it sent: the run window
  field was lost.
- Tests used easy inputs: notepad (on the PATH) but never Excel or Edge, words but never digits, a few agents, opening
  but never closing and coming back.
- The in-page folder list was accepted as a browser limit, though Studio's server runs on the same computer.
- A later rule ("Also called" lines) was added without re-checking the short-labels rule.

How to stop it happening again: compare option by option with a check that fails when an AA option has no match; put
every "we do it differently" choice to the user once; when retiring a screen, compare the data it sends; keep a
real-input test set (digits in text fields, Office and Edge by name, 200 agents five folders deep, close and return);
run a Studio usability checklist (keyboard, resizing, kept state) at 1366 and 1920 px.
