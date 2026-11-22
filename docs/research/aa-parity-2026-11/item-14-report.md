# Item 14: why these gaps were missed, and what now stops it

Report for the AA-parity release (November 2026). Item 14 of the user's 20 Nov list asked why the defects and missing
options were not found by the earlier comparisons. This page gives the causes, what each one cost, and the checks now
in place so the same kind of miss is caught by a test or a review step, not by the user.

## What went wrong

| # | Cause | What it let through | Fix now in place |
|---|---|---|---|
| 1 | The command map counted an AA command as covered when one of ours had a similar name. Options, control types and the real values people type were never compared. | 559 option-level gaps (G1-G6), for example a number typed into a text field, a missing "if the file exists" choice, no session where AA has one. | The option-by-option comparison in [gap-list.md](gap-list.md) is now the reference. Every row was built or recorded as "not needed" with a reason. |
| 2 | AA's file fields have "a specific path or a pattern" built in. The October field review treated them as one plain file field. | Fields that could not pick a file by pattern. | The shared name-rule file field (item 10), adopted by the families. |
| 3 | Choices like "no sessions for JSON, XML, CSV, scripts and web toolkits" were written into the comparison data as deliberate, but never put to the user. | Missing Start/End session in many toolkits (item 9). | Sessions added in G2, G4, G5 and G6 (JSON, XML, CSV, script hosts, Excel, Sheets, SharePoint, Microsoft 365, Salesforce, ServiceNow, Okta, Workday, Databricks, Snowflake, Kafka). Rule: every "we do it differently" choice goes to the user once. |
| 4 | When Classic Studio was removed, the review compared the server addresses each Studio called, not the fields it sent. | The run window stopped appearing (item 1). | Item 1 rebuilt. Rule: when retiring a screen, compare the data it sends, field by field. |
| 5 | Tests used easy inputs: Notepad but never Excel or Edge by name; words but never digits; a few agents; opening but never closing and coming back. | Items 3, 8, 11, 12, 13. | Live tests against real Edge, WinForms, Swing, an FTP server, Tesseract and the script interpreters. A real-input test set is part of the release check (step 7). |
| 6 | The in-page folder list was accepted as a browser limit, although Studio's server runs on the same computer. | Browse showed a home-made list instead of File Explorer (item 4). | The real File Explorer is used. |
| 7 | A later rule ("Also called" lines) was added without re-checking the earlier short-labels rule. | A crowded Commands panel (item 5). | One-line Commands panel. Rule: a new UI rule is checked against the existing ones. |

## Found during this build (same causes, caught before release)

- **A wrong separator in a field rule.** Two fields showed only for one choice because they used a comma where the
  rule syntax needs `|`. Both were fixed. The panel check now has a case for it (see below).
- **Two branches added a step with the same id.** Two families each added a "Script succeeded" condition, with
  different meanings. The toolkit loader refused the duplicate, and the merge test run caught it before anything was
  pushed. The newer one is now "Last script step succeeded".
- **Overlapping edits to the same condition.** "Desktop object exists" gained a state choice in one family and a
  wait time in another. The merge keeps both.

## Checks that now run on every change

1. The architecture test reads every command's panel, and fails when an input is missing or placed twice, or a choice
   label cannot be parsed.
2. The error catalogue test fails when a command raises a code that is not documented.
3. New-step defaults are kept apart from saved behaviour (`newStepDefault`), so a safer default never changes an agent
   that already exists.
4. Old method signatures stay as overloads, so saved agents and callers keep working.
5. The full Java test suite and the UI type check run on the merged release branch before it is pushed.

## Still to do (not part of this release)

- Compare future AA releases option by option with a script that fails when an AA option has no match on our side,
  rather than reading the comparison by hand.
- Add a Studio usability checklist (keyboard only, resizing, kept state) at 1366 px and 1920 px to the release check.
