# Build brief: close the AA option gaps for one family of toolkits

You are building part of an approved release of OOA (OneOfficeAutomation), an RPA platform modelled on Automation
Anywhere 360 (AA). The user approved building EVERY gap in the option-by-option comparison, including large ones,
keeping every feature we have that AA lacks, and simplifying the logic and UI so people understand the commands.

## Where to work (git)
- Work in your own clone or worktree. First: `git fetch origin studio/aa-parity-2026-11` then
  `git checkout -B studio/aa-parity-<FAMILY> origin/studio/aa-parity-2026-11` (FAMILY given in your prompt).
- Commit small, one toolkit (or one theme) per commit, message in plain English, ending with the line
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push your branch (`git push -u origin <branch>`);
  if push fails for sign-in, keep committing and say so at the end. Never merge, never open a PR, never touch master.
- Java sources are LF or CRLF: never `sed -i` Java files (it rewrites line endings); use the Edit tool or a Python
  edit that keeps newlines. Heredocs in Bash collapse `\\`: write files with backslashes via Write/Edit or a script file.

## Your gap list
Your group's section of `gap-list.md` in this folder.
Rows are condensed; when a row is unclear, read AA's definition of the command: in AA 360's editor (read-only,
never save anything there), on docs.automationanywhere.com (the package's page and its "package updates" release
notes), or in the offline AA 360 docs library (RPA-Docs-AA-BluePrism.zip: AA360-index.tsv lists every topic with its
line in AA360-docs.md).
Describe AA in our own words; never copy AA label text into the product.

## Shared pieces already built (use them, do not rebuild)
- `@Input(newStepDefault = "false")`: a safer default for NEW steps only (Studio writes it into new steps); keep
  `defaultValue` as today so saved agents behave the same. Use it wherever AA's default is safer (overwrite off, etc.).
  The user decided: defaults change for new steps only.
- `@Input(tone = "danger"|"add"|"plain")`: colour of a ticked Yes/No (worked out from the name when empty).
- `@Input(acceptsCredential = true)` on a Text input: it may take a vault reference, read just in time and masked.
- Files by name rules: a File input may receive `{"$files": {...}}` (sdk `FileSelection`). The runner passes the first
  match as the value; a command that works on MANY files must read `ctx.selectedFiles("<input>")` (null = exact path)
  and act on all of them. Use this instead of adding new wildcard/regex inputs; keep old `pattern` inputs working for
  saved agents (they stay; Studio shows the rule mode for new steps). Studio offers name rules on File inputs whose
  name does not look like a target/output (see StepForm choosesExistingFiles).
- `ctx.outputPath(givenPath, fileName)`: optional save path; empty = a temp file in the run folder; a folder = that
  folder + fileName. Use it for AA's "file variable / assign file" gaps (make the save path optional, return the path).
- `ctx.loopItem()`: the current item of the innermost loop; use it so steps inside "Each email" etc. act on the
  current item when their id is left empty.
- Sessions: the run's `ctx.sessions()` map, `ctx.session(name, Kind.class)`, `ctx.sessionOrOnly(...)`. New session
  commands follow existing ones (Excel Open/Close, Database Connect/Disconnect): a `session` Text input with the
  toolkit's default name, plus on the Start/Open/Connect step a Boolean `share` ("Agents this one runs may use it",
  default false) that calls `ctx.shareSession(name)`. Later steps get an optional `session` input; when given they use
  the session, otherwise they work as today (one-step use keeps working). Close on End/Disconnect.
- Conditions: the runner already supports `waitSeconds` on any condition (Studio adds "keep checking"), and
  `ignoreCase`, `ignoreLineBreaks`, `includeTime` on value checks: do not add per-condition wait inputs.
- Panel layout: `@Action(panel = "a, b, [Tick label: c, d], (Choice: Label = x / Label = y) | more1, more2")` puts
  fields in AA's order with ticks/choices that reveal fields and "More options" after `|`. Keep AA's order, our labels.

## Rules
- Keep every existing input, output and command working (old names via `formerNames`). Add, do not remove.
- Simplify: one clear field beats three overlapping ones; plain-English descriptions (one sentence); labels short.
- No AA name, wording or screenshots in the product. Our own labels.
- Security: credentials only in Credential inputs or acceptsCredential; never log secrets; validate inputs; no
  shell injection (use argument lists).
- Tests: add or extend JUnit tests next to the toolkit (packages*/src/test or toolkits/*/src/test) for every new
  command/option; run only your modules' tests with `--tests` filters (other helpers build in parallel on this PC):
  e.g. `./gradlew :packages:test --tests "*Csv*" -q`. Never run the full suite. Don't start servers.
- Do NOT edit: platform/ui/** (except the files your prompt says you own), docs/reference/** (generated later),
  platform/ui/src/studio/formerNames.ts, aaNames.ts, docs/research/command-comparison.data.json, .claude/launch.json.
  If a gap needs a Studio UI change you do not own, build the runtime/toolkit side and list the UI need in your report.
- Large items (size L): build them fully on the toolkit/runtime side; for a Studio UI part you do not own, add the
  server/route or manifest data it needs and describe the UI in your report.

## Report (your final reply)
1. Branch name and commits (hash + one line).
2. Per gap row: Done / Done differently (why) / Not done (why). Keep it short.
3. UI needs for the Studio owner (exact field/command names and what the UI should show).
4. Tests added and their results.
