# Use cases: One Agent Studio compared with Automation Anywhere

Fifteen everyday automations, built as real agents in `examples/use-cases/` (open them in Studio from the
**Use cases** folder of the workspace) and run as tests by `CommandSamplesTest`
(`platform/cli/src/test/resources/command-samples/20-use-cases.json`, 16 runs: W3 is run with and without results).

How the comparison was made: each agent was built and run in One Agent Studio. The AA column describes how the same
task is built from AA's actions (from the action-by-action comparison in `command-comparison.md`), in our own words.
Nothing was built or saved in AA (AA access rule: open forms and cancel, never save).

Status on 2026-10-23: **16 of 16 runs pass**. The desktop cases (D1-D5) run with `OOA_DESKTOP_SAMPLES=1` because they
open a window on the screen.

| # | Use case | Our steps | How AA does it | Result |
| --- | --- | --- | --- | --- |
| W1 | Portal sign-in and order entry | 11 | Browser Open, Recorder captures (set text, click, select), Wait for condition, capture Get property into a variable, Close | pass: confirmation `CONF-ACME-250-EXPRESS` |
| W2 | Paged product list to CSV | 13 | Loop While, Recorder Extract table, Data table Join/Merge, If object exists (capture), Click Next, Data table Write to file | pass: 7 products, 554.10 total |
| W3 | Search with waits, branch, error screenshot | 13 | Error handler Try/Catch/Finally, Wait for condition, If object exists, Extract table, Screen capture in Catch | pass: 2 found / no-results branch |
| W4 | Excel rows into a web form, number written back | 18 | Excel advanced Open, Get all cells, Loop for each row, Recorder set text/select/click, Get property, Excel Set cell, Save as, Close | pass: 3 registered, REG-2 in D3 |
| W5 | Reconcile web orders with a CSV export | 14 | Extract table, CSV/TXT Read, Loop for each row, Data table Search, If, Data table Insert row, Write to file | pass: 2 differences reported |
| D1 | Orders typed into a desktop application | 15 | Application Open program, Window Wait, Loop over CSV rows, Recorder captures on desktop controls (set text, select item, check, click), Get property | pass: 3 orders saved |
| D2 | Desktop grid read into a table | 7 | Recorder capture of the grid with Get table, Data table Filter, Write to file | pass: 4 rows, 3 open |
| D3 | Arrange and close application windows | 11 | Window Resize, Minimise, Maximise/restore, Get window details (Window package), Close | pass: 700 x 560, closed |
| D4 | Text handed over through the clipboard | 10 | Clipboard Assign/Copy to, Read from file, Recorder Set text, Get property, If equal | pass: identical |
| D5 | Validation pop-up read and closed | 13 | Click Save, Wait for window, capture Get property on the pop-up, capture Click OK, correct the field | pass: pop-up text read, order saved |
| O1 | Overdue invoices per customer to Excel | 9 | CSV/TXT Read, Data table Filter (twice), Group by / Sum via loops or SQL on the table, Excel Create workbook, Write from data table | pass: 3 customers, 2,605.50 |
| O2 | Invoice emails: attachments saved, replies sent | 8 | Email Connect, Loop for each email in mailbox (filter), Save attachments, Reply, Disconnect | pass: 2 emails, 2 files |
| O3 | Letters from PDF invoices and a Word template | 13 | PDF Extract text, String Find/Extract text, Word Open template, Replace text, Save as, Close | pass: 2 letters |
| O4 | REST API users into a database, summary by SQL | 10 | REST Web Service Get, JSON parse/loop, Database Connect, Insert/Update, Read from (SQL), Disconnect | pass: 4 users, 3 cities |
| O5 | Support tickets sorted by an AI model | 9 | Generative AI package (per provider) prompt or classify, Loop, If/Else if, Data table Insert row, Write to file | pass: 2 billing, 1 technical, 1 other |

## Differences found while building them

What is simpler in One Agent Studio:

- **One action per element step.** Web steps take a CSS selector or a captured object directly; AA records a capture for
  each element and then picks the action inside it.
- **Typed results.** Extract table gives a Table, numbers come back as Number (CSV detect types, form answers), so
  sums and comparisons need no conversion steps.
- **Data table tools.** Aggregate (group by with sums and counts) and Merge replace the loops AA needs for a summary.
- **Saved connections.** Mail, REST and connector steps can take their address and sign-in from a named connection
  (new); AA repeats them per package session.
- **Error kinds in Catch.** One Catch for `NOT_FOUND` or `TIMEOUT` covers every toolkit (new); AA lists exceptions
  per package.
- **Finally only when wanted.** A Try starts with Try and Catch; Finally is added when needed (D1-D5 use it to close
  the application even when a step fails).

Defects and gaps the use cases found (all fixed and covered by these tests):

| Found by | Problem | Fix |
| --- | --- | --- |
| W5, O5 | Add row took only a Dictionary; a list of values in column order (as in AA) was refused | Add row takes a Dictionary or a List in column order |
| W5, O5 | The type error said "Variable 'values' expects Dictionary" for a step input | It now says "Input 'values' ..." and how to fix it |
| D2 | Desktop Get table on a Windows Forms grid returned Column1..6 with the headers as a data row | The header row becomes the column names and row headers are dropped |
| D5 | Controls of an owned pop-up window (validation box, Save As) were not found | Owned dialogs are listed and searched as windows of their own |
| W1-W5 | Open browser could not open a local HTML file by path | A file path (absolute or in the workspace) opens as its file: address |
| D1 | Demo application: a label with an accessible name reads as that name | Fixed in the demo application (and noted for capture: Name is what UI Automation reads) |

## Running them

- In Studio: open **Use cases** in the Explorer, pick an agent and press Run. Web cases open Edge; D1-D5 start the
  demo application `apps/order-entry.ps1`; O2 needs a mailbox, O4 reads a public test API by default, O5 needs an AI
  connection or API key. Results go to `Use cases/output/`.
- As tests: `OOA_SAMPLES=20-use-cases OOA_DESKTOP_SAMPLES=1 gradlew.bat :cli:test --tests *CommandSamplesTest`
  (from PowerShell).
- To change them: edit `examples/use-cases/tools/generate.py` and run it; `tools/make-office-files.flow.json`
  (`ooa run`) makes the Excel and Word data files.
