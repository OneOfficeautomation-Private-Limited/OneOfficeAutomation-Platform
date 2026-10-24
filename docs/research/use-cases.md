# Use cases: One Agent Studio compared with Automation Anywhere

Twenty-six automations: fifteen everyday ones, five larger web processes on demo applications (W6-W10) and six on real public websites (P1-P6), built as real agents in `examples/use-cases/` (open them in Studio from the
**Use cases** folder of the workspace) and run as tests by `CommandSamplesTest`
(`platform/cli/src/test/resources/command-samples/20-use-cases.json`, 16 runs: W3 is run with and without results;
`21-big-web-processes.json` for W6-W10; `22-public-websites.json` for P1-P6).

How the comparison was made: each agent was built and run in One Agent Studio. The AA column describes how the same
task is built from AA's actions (from the action-by-action comparison in `command-comparison.md`), in our own words.
On 2026-10-24 the same 26 cases were also built as bots in the user's AA Community Edition (folder **Bots\Use cases**)
and run on the user's Bot Agent; see [Built and run in AA](#built-and-run-in-aa).

Status on 2026-10-24: **27 of 27 runs pass** (P1-P6 against the live sites). The desktop cases (D1-D5) run with `OOA_DESKTOP_SAMPLES=1` because they
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

## Larger web processes

Each one follows a whole business process through a demo web application with several screens (`site/`).

| # | Process | Our steps | How AA does it | Result |
| --- | --- | --- | --- | --- |
| W6 | Invoice approval: sign in, work through a paged queue, open each invoice, apply rules (no PO: reject; above the limit: escalate; else approve), comment, report | 31 | Credential Vault, Recorder captures for sign-in, Loop While with a capture-based "object exists" condition, captures to read the detail fields, If / Else if rules, captures for comment and buttons, Data table Insert row, Write to file | pass: 7 decided (4 approved, 2 rejected, 1 escalated), the portal's own counters agree |
| W7 | Supplier onboarding wizard: three steps per supplier from a CSV, the portal's validation messages turned into business errors, Excel report | 38 | Loop over CSV rows, Recorder captures per wizard step, If object exists for the error text, Throw, Error handler Catch with the error message, Excel Create workbook, Write from data table | pass: 2 onboarded, 2 refused with the portal's reason |
| W8 | Price comparison: every product in three shops with different layouts (a table, product cards, a list with a data attribute), normalise prices, keep the cheapest, compare with last week | 47 | Nested loops, one set of captures per shop layout (three recordings), Get property for the price, String Replace (regex), Number from string, Data table Search, Write to file | pass: 3 products, 2 price changes, 203.29 total, chair cheapest in Shop C |
| W9 | Leave requests: read balances on one tab, review each pending request in a pop-up, approve while days are left (balance updated) or decline with a note, check the dashboard | 34 | Captures for tabs, Extract table, Loop While a Review button exists, captures inside the modal dialog, Data table Search and Set value, If, captures for the dashboard counters | pass: 2 approved, 3 declined, Ann has 5 days left, dashboard matches |
| W10 | Order fulfilment: create orders with a varying number of product lines, wait until the shop has processed each, download the invoice, read its total and check it | 35 | JSON parse, Loop over orders and over lines, Recorder captures with an index for the n-th line (or a variable in the XPath), Loop While with Wait/Delay for the status, Download the file, Read from file, String Extract text, If | pass: 2 orders, 2 invoices verified, 384.50 in total |

What these show beyond the smaller cases:

- **Selectors with variables** (`#lines .line:nth-child(${k}) select.product`, `#${decision}`) replace one recording per
  element; AA needs a capture per line or an XPath built in a variable.
- **Queue processing** ("while a Review button exists, open the first one") works without counting rows first.
- **One agent, several page layouts**: an If per shop keeps three different extraction methods in one loop.
- **Business errors from the page**: the portal's validation text becomes the message of a BUSINESS.REFUSED error that a
  Catch writes into the report, so one bad record does not stop the batch.
- **Waiting for back-end processing** with a short poll loop instead of a fixed long delay.

Found while building them: **Navigate** did not accept a local file path the way Open browser now does; it does now.

## Real public websites

These run against live sites that are made for automation practice or open to scraping. Nothing is bought, no
account is created and nothing real changes (Sauce Demo's demo user is printed on its own sign-in page; Quotes to
Scrape accepts any user name). The sites can change; their tests are skipped when a site cannot be reached.

| # | Site and process | Our steps | How AA does it | Result on 2026-10-24 |
| --- | --- | --- | --- | --- |
| P1 | **rpachallenge.com**: download the challenge Excel, start, fill the form for each of the 10 rows while its fields move every round | 19 | Download file, Excel Open/Get all cells, Loop for each row, Recorder captures that must use a stable attribute (not position) for each field, Click Submit, Get property of the result | 100% (70 of 70 fields) in about 1.4 s |
| P2 | **saucedemo.com**: sign in, sort by price, add the three cheapest products, check out, compare the shop's item total with the prices seen, finish, sign out | 31 | Captures for sign-in, sort list, Loop with a capture per Add button (or the first one each time), Extract text of the totals, String to number, If, captures for checkout and sign-out | 3 items, item total 33.97 = prices seen, order completed |
| P3 | **books.toscrape.com**: open a category, read every page, visit all 32 books for product code and stock, Excel report with a sheet per star rating | 27 | Loop While Next exists, Extract table (or a capture per field per book), Loop over books with Go to URL, Get property per detail field, Data table, Excel with two sheets | 32 books, 290 in stock, 5 rating groups |
| P4 | **demoqa.com** web table: delete the Legal records, add three employees from CSV through the pop-up form, search one and raise the salary, check the table | 32 | Captures for the table buttons and the modal form, Loop over CSV rows, a capture on the row's Edit/Delete icon built from a variable, Extract table | 5 records, salary 58,000 |
| P5 | **quotes.toscrape.com**: sign in, read all 10 JavaScript-rendered pages (100 quotes), rank authors, visit the top three authors' pages for birth details | 33 | Captures for sign-in, Loop While Next exists with Wait for the rendered list, Extract data per quote (a capture per field), Data table Group by, Go to URL per author, Get property | 100 quotes, Albert Einstein first, born March 14, 1879 |
| P6 | **en.wikipedia.org**: read the population list, open the ten largest countries' articles, read capital and currency from each information box, Excel report | 24 | Extract table, Loop with Go to URL, captures or XPath per information-box row, Excel | 10 countries, India's capital New Delhi |

What the real sites added:

- **Scripts that read a whole page at once.** Run JavaScript returns a list of records (every book, quote or country
  on the page) in one step; AA reads them through a capture per field or an Extract table that fits only real tables.
- **Stable selectors on moving pages.** The RPA Challenge moves its fields every round; the agent finds them by an
  attribute, as AA's recorder must be told to do.
- **XPath with variables** for the row of a named employee or an information-box line.
- **Real-site behaviour shows up.** Wikipedia's country links point to demographics articles, so the agent addresses
  the country article by name; DemoQA forgets records added in the same visit once one is deleted, so the agent deletes
  first. Both are written in the agents' descriptions.
- **Polite pacing** between page visits on Wikipedia.

## Built and run in AA

All 26 cases exist as Task Bots in AA Community Edition (**Bots\Use cases**). 24 were run on the user's Bot Agent on
2026-10-24 and give the same results as our agents; O2 and O5 are built but need secrets to run. Each AA bot writes its
result to `Use cases/output/aa-<case>-result.txt` (and its CSV or Excel output next to it, prefixed `aa-`).

| # | AA bot result | Same as ours |
| --- | --- | --- |
| P1 | 100% (70 of 70 fields) in 5.9 s (ours 1.4 s) | yes |
| P2 | Prices seen 33.97 = shop item total; order completed; signed out | yes |
| P3 | 32 books, 290 in stock; Excel with a By rating sheet (5 ratings, by Excel formulas) | yes |
| P4 | Legal deleted, 3 added, Kumar's salary 58,000, 5 records | yes |
| P5 | 100 quotes; Albert Einstein first, born March 14, 1879 | yes |
| P6 | 10 countries; India's capital New Delhi | yes |
| W1 | CONF-ACME-250-EXPRESS | yes |
| W2 | 7 products, 554.10 | yes |
| W3 | laptop: 2 found, first Laptop Pro 14; printer: no results | yes |
| W4 | 3 registered, REG-2 in D3 | yes |
| W5 | 5 checked, 2 differences (SO-502, SO-505) | yes |
| W6 | 7 decided: 4 approved, 2 rejected, 1 escalated; portal counters agree | yes |
| W7 | 2 onboarded, 2 refused with the portal's message | yes |
| W8 | 3 products, 2 changes, 203.29, chair cheapest in Shop C | yes |
| W9 | 2 approved, 3 declined, Ann has 5 days left, dashboard matches | yes |
| W10 | 2 orders, 2 invoices verified, 384.50 | yes |
| D1 | 3 orders saved (urgent box set for Globex) | yes |
| D2 | 4 rows, 3 open | yes |
| D3 | Resized, minimised, restored, closed | yes (AA cannot read the size back) |
| D4 | Note identical after the clipboard and the field | yes |
| D5 | Pop-up "Amount must be a number" read and closed; then saved | yes |
| O1 | 3 customers, 2,605.50 | yes |
| O2 | Built, not run: needs a mailbox (credential inputs) | - |
| O3 | 2 letters with customer and total filled in | yes |
| O4 | Live API: 10 users into SQLite, 10 cities (our test uses a 4-user mock) | yes |
| O5 | Built, not run: needs an OpenAI API key (credential input) | - |

How the AA bots are built:

- **Web pages are driven with Run JavaScript** (click, set value with input events, read text, wait for an element).
  Recorder objects made without the recorder failed in ways a recording would avoid: false "not found" after a page
  re-renders, a fallback that clicked the wrong item, an "object exists" condition that was always false. Normal AA
  practice is to record each object; the scripts keep the bots repeatable here.
- **Desktop steps use Recorder objects** (UI Automation), found by the control's ID or Name; these worked first time.
- **Everything else uses AA's own actions**: CSV/TXT, Excel advanced, Data table, Dictionary, List, String, Number, Loop,
  If, Error handler (Try/Catch/Finally, Throw), Log to file, Window, Clipboard, PDF, Word, REST, JSON, Database (SQLite),
  Generative AI, Email.
- W1-W10 open the demo pages from `http://127.0.0.1:8781/`: AA's Chrome extension has no access to `file://` pages
  unless that is switched on in Chrome. Start a local web server on the `site` folder before running them, for
  example `python -m http.server 8781 --directory "<workspace>\Use cases\site"`.

What building them in AA showed (in addition to the differences below):

- **Browser choice matters.** On this PC AA could not link an Edge window to its tab (the title holds the profile name
  and an invisible character), so the bots use Chrome.
- **Run JavaScript takes one expression** and no variables in its script box; a script that needs values is written to
  a file with Log to file first and run from that file. Ours passes values to scripts directly.
- **Stale windows confuse AA.** A window left open by an earlier run with the same title is picked up instead of the new
  one; the bots close their window in Finally. Leftover browser processes (even headless ones) with the AA extension
  also answer AA's lookups.
- **Excel**: a workbook opened with "contains header" refuses writes to row 1; looping over rows with Excel's own row
  loop broke later browser steps, so the bots read the sheet into a table and close Excel first. Text file actions read
  only `.txt` files (W10 copies the JSON to `.txt`).
- **No group-by**: counts and sums per key use a Dictionary loop (O1, P5); ours has Aggregate.
- **Password boxes** take only Credential Vault values; Sauce Demo's public demo password is set by script.
- **Runs are tied to the editor page**: navigating away or pressing Run twice cancels a running bot.
- **Speed**: the same steps take longer in AA (P1 5.9 s against 1.4 s; P2 about 40 s).

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
  (from PowerShell); `OOA_SAMPLES=21-big-web` runs W6-W10 and `OOA_SAMPLES=22-public` runs P1-P6 (needs internet).
- To change them: edit `examples/use-cases/tools/generate.py` (W6-W10: `generate_big_web.py`; P1-P6: `generate_public_web.py`) and run it; `tools/make-office-files.flow.json`
  (`ooa run`) makes the Excel and Word data files.
