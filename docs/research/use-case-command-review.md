# Use case by use case: every step, ours against AA

The step-by-step comparison behind [use-case-gaps.md](use-case-gaps.md). It covers all 26 use cases (P1-P6, W1-W10,
D1-D5, O1-O5) built in both products on 2026-10-24. For each case there is a table of steps with three things: our
command and how it is set up, the native AA command and its settings, and which one is simpler. Each case ends with notes
on how the same command is used differently in other cases.

The tables describe Studio **as it was before the fixes of 2026-10-24**. The gap list says what has been fixed since,
for example the `[object Object]` display of calculations and calculations having to be typed as JSON.
All descriptions are in our own words.


## Review A: One Agent Studio against AA, command by command (P1-P6, W1-W3)

Sources: our agents in `examples/use-cases/<id>-*.flow.json`, our action catalogue (`/studio/api/packages`), Studio's
property panel (`studio.js`: `renderProps`, `inputControl`, `conditionEditor`, `textSourceEditor`, `summary`), and the AA
bots and AA command definitions read from the Control Room (read only). Everything below is in our own words.

Note: `studio.js` was being edited while this review ran; the UI points describe the file as read on 2026-10-24.

### How to read the AA column

The AA bots for these cases drive the page with **Run JavaScript** helper scripts (`CLICK "//xpath"`, `SET`, `GET TEXT`,
`check exists` inside a *For n times* loop with *Break*). That is a workaround, not normal AA practice. The AA column
therefore describes the **native AA way**: **Recorder: Capture**. One Capture action means:

1. pick the **Window** (the browser tab, usually `$Browser1$`),
2. press **Capture** and point at the element; AA stores an object with a property list (HTML tag, id, name, DOMXPath,
   path ...), where the developer ticks the properties to match and can put a `$variable$` into a value,
3. pick the **Action**; the list depends on the control type AA detected (TextBox: Set text / Get text / Append text;
   Button: Click; Link and Label: Click or **Get property** only, no Get text; ComboBox: Select item by text / index / key;
   Table: Get table, Get total rows, Get cell text ...),
4. fill the action's own fields (Set text: keystrokes or a credential, time between keystrokes; Get property: property
   name such as `HTML InnerText`),
5. optionally **Set system time out** (Basic: one wait in seconds; Advanced: wait for the browser, wait for the object,
   and stop-or-skip when time runs out),
6. **Save the outcome to a variable** (for Get actions).

To branch on an element, AA uses **If** with a recorder *object exists* check on a captured object (none of the 26 AA bots
uses it, so it is not in the catalogue extract). AA has no separate "wait for element" command: the Capture's own wait,
an object-exists check, or a loop with Delay is used.

In the tables below, "AA: Capture TextBox > Set text" means one Capture action configured as above.

---

### P1 RPA Challenge (rpachallenge.com)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1 | Open the site | **Open browser**: Url; Headless = `$headless`; under More options Engine = `playwright`, Keep open = `false` (4 fields set) | **Browser: Open**: What to open = New window, browser, Link to open (2-3 fields) | Ours needs two More-options fields changed for every unattended web agent (engine, keep open). AA has no headless option | AA (fewer fields) / ours (headless) |
| s2 | Download the challenge Excel | **Download file**: Selector `a[href*='challenge.xlsx']` (or Capture), Folder (required) -> `$xlsx` gets the saved path | **Capture Link > Get property** `href` -> `$sUrl$`, then **Browser: Download file**: URL of file, Save to location (full file path), Overwrite (2 actions, 5 fields) | Ours clicks the link like a person and returns the real file path; AA needs the address first. The AA bot hard-coded the URL | Ours |
| s3-s5 | Read the sheet into a table | **Open workbook** (Path `$xlsx`, Session `challenge`, Header true) + **Get all cells** (Session) -> `$people` + **Close** (Session, Save false) | **Excel advanced: Open** (file path, contains header, read-only) + **Get worksheet as data table** (active sheet, header, read option) + **Close** (save off) | Same 3-step session pattern. Ours asks for the header twice (Open and Get all cells) and the session name typed by hand in three steps | Same |
| s6 | Press Start | **Click element**: selector `xpath=//button[normalize-space()='Start']` | **Capture Button > Click** | Same idea; ours shows a Selector box and a Captured object box side by side | Same |
| s17 | One round per row | **Loop**, Iterator = Each row in a table, Over `$people`, As `row` | **Loop > Iterator > Data Table: For each row in table**, Table variable, Assign the current row to `$rRow$` | Same; our labels "Over" / "As" are terse | Same |
| s16 | Skip blank rows | **If** builder: left `{"expr": "$row[\"First Name\"]"}`, op *is not empty* | **If > String condition**: `$rRow[0]$` Not equal to (empty) | Ours needed an expression because the column name has a space: the builder's left box shows raw JSON | AA |
| s7-s13 | Fill 7 fields that move each round | **Set text** x7: selector `input[ng-reflect-name='labelFirstName']` ...; Text = `{"expr": "toString($row[\"First Name\"])"}` (and `"Last Name "` with a trailing space) | **Capture TextBox > Set text** x7: object matched on the `name`/`ng-reflect-name` property (not position); Keystrokes `$rRow[0]$` ... | AA puts a row column straight into the text (`$rRow[0]$`, numbers are typed as text). Ours needed an expression with `toString()` for each field. In Studio the Text field opens in *Plain text* mode and shows `[object Object]` for an expression value (`textSourceEditor` uses `String(value)`), so these 7 steps cannot be read or edited in the panel | AA |
| s14 | Submit | **Click element** `input[type='submit']` | **Capture Button > Click** | Same | Same |
| s15 | Count rounds | **Assign variable**: rounds = `{"expr": "$rounds + 1"}` (JSON typed in the Value box) | **Number: Increment** (number, by 1, output) | AA has a named action; ours needs JSON `{"expr": ...}` in a text area (typing `$rounds + 1` stays a literal string). We do have `ooa.number/increment`, but it is not the obvious choice | AA |
| s18 | Read the score | **Get text** `.message2` -> `$result_message` | **Capture Label/Client > Get property** `HTML InnerText` -> `$sResult$` | AA has no Get text on a label-type element; the developer must know the property name | Ours |
| s19 | Close | **Close browser** (no fields) | **Browser: Close**: What to close, tab/window variable | Same | Ours |

#### Notes for P1

- **Set text** is used here with an expression, whereas P2, P4, W1 and W3 use a plain `$variable` or `$e.Column`. Three
  different ways to put a row value into a field appear in this set: `{"expr": "toString($row[\"First Name\"])"}` (P1),
  `$e.FirstName` (P4), `${e.Age}` (P4, for a number). The developer cannot tell which one is needed. Root causes: Set
  text does not turn numbers into text by itself, and a column name with spaces cannot be written as `$row.Column`.
- The **Loop over a table** is the plain case here (all rows); P5 and P6 use the same loop with *To index* to take only
  the first N rows.
- The **If** here tests a cell value; in P3/P5/W2 the same If tests an element on the page (*Element exists*), and in
  P5/P6 it tests "variable is empty" to keep the first value.
- Confusing in Studio: the expression steps are unreadable in the panel (`[object Object]`), and on the canvas the If
  reads as an expression rather than "First Name is not empty".

---

### P2 Web shop checkout (saucedemo.com)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1 | Open shop | **Open browser** (Url, Headless, Engine, Keep open) | **Browser: Open** | As P1 | AA |
| s2-s3 | User and password | **Set text** `#user-name` Text = Variable `$shop_user`; `#password` = `$shop_password` (String input) | **Capture TextBox > Set text** (keystrokes `$sUser$`); **Capture Password Text > Set text** with a credential | Both can take a credential (ours: Text comes from *Credential*). The demo uses a plain String here | Same |
| s4 | Sign in | **Click element** `#login-button` | **Capture Button > Click** | Same | Same |
| s5 | Wait for the product list | **Wait for element** `.inventory_list`, State visible | Capture's own wait on the next action, or If object exists in a loop | Ours has a direct "wait for" command. Note: *State* is under **More options** in our panel (see Findings) | Ours |
| s6 | Sort by price | **Select item**: selector `.product_sort_container`, Option `lohi` (value or text) | **Capture ComboBox > Select item by text** ("Price (low to high)") or by key | Ours has one Option box for value or text; AA makes you choose text / index / key | Ours |
| s7 | Read the product list (name + price) | **Run JavaScript** returning a list of `{name, price}` -> `$products` | No native list read for non-table elements: either a Loop *For n times* with a Capture whose DOMXPath uses `[$nI$]` > Get property (one action per field), or Run JavaScript as here | Ours has no non-JavaScript way either (no "extract list / repeated elements" action); JavaScript is a developer skill | Same (both need code) |
| s10 | Take the N cheapest | **Loop** Each item in a list, Over `$products`, As `p`, Index `i`, **To index** = `{"expr": "$items_to_buy - 1"}` | **Loop For n times** = 3 (`$nI$`), or a For-each with a counter, If counter > N, Break | Ours needs JSON typed into a Number box and a zero-based "last index, included" mental model (N-1). AA's "For n times" is clearer | AA |
| s8 | Add to cart | **Click element** `button[data-test^='add-to-cart'] >> nth=0` | **Capture Button > Click**, DOMXPath `(//button[starts-with(@data-test,'add-to-cart')])[1]` | Ours uses Playwright-only `>> nth=0` syntax that a business developer would not know | AA (captured) |
| s9 | Sum expected price | **Assign variable** expected = `{"expr": "$expected + $p.price"}` | **Number: Assign** `$nExpected$ + $nPrice$` | AA's Number: Assign accepts the arithmetic as typed; ours needs `{"expr": ...}` JSON | AA |
| s11-s12 | Open the cart and wait | **Click** `.shopping_cart_link`, **Wait for element** `.cart_list` | **Capture Link > Click** (+ wait in the next Capture) | Same | Same |
| s13-s14 | Count cart items | **Run JavaScript** -> list `$cart`; **Assign** cart_count = `{"expr": "len($cart)"}` | **Capture (cart list) > Get all children ...** or Get total items, or JavaScript | Two steps with code in ours; a "count matching elements" action exists only inside *Capture: element action* (`count`) | AA / same |
| s15-s19 | Checkout form | **Click** `#checkout`, **Set text** x3 (Variable mode), **Click** `#continue` | **Capture** x5 (Button Click, TextBox Set text x3, Button Click) | Same | Same |
| s20-s23 | Read item total and order total as numbers | **Get text** `.summary_subtotal_label` -> `$label`; **Assign** item_total = `{"expr": "toNumber(regexExtract($label, \"[0-9.]+\"))"}` (x2) | **Capture Label > Get property** `HTML InnerText` -> `$sLabel$`; **String: Replace** (remove "Item total: $"); **String: To number** -> `$nTotal$` (3 actions per value) | Ours is 2 steps but needs an expression with two functions; AA is 3 plain steps | Ours (fewer) / AA (no code) |
| s24 | Compare totals | **Assign** total_matches = `{"expr": "round($item_total, 2) == round($expected, 2)"}` | **If Number condition** = ... String Assign "yes"/"NO" in If/Else (4 actions) | Ours is one step but code | Ours |
| s25-s26 | Finish, read confirmation | **Click** `#finish`, **Get text** `.complete-header` | **Capture Button > Click**, **Capture Label > Get property** | As s18 in P1 | Ours |
| s27-s30 | Sign out | **Click** menu, **Wait for element** `#logout_sidebar_link`, **Click**, **Wait for element** `#login-button` | **Capture** x2 with wait settings (menu slides in) | Same | Same |
| s31 | Close | **Close browser** | **Browser: Close** | Same | Ours |

#### Notes for P2

- **Loop over a list** with *Index* and *To index* (first N) here, against P1's loop over all rows. The "first N" pattern
  appears again in P5 and P6 and always needs `{"expr": "$n - 1"}`.
- **Click** on a button that is chosen by position (`>> nth=0`), while P3 clicks a link by its text and P4 has to switch
  to *Capture: element action* with *Click method = javascript* for buttons hidden behind adverts.
- **Assign variable** is used for four different jobs here: counter, sum, length of a list, number from text, and a
  comparison. Each one requires the JSON form `{"expr": ...}` in the Value text area.
- **Get text** reads a label; AA needs *Get property* with the property name for the same element type.
- Confusing in Studio: the *Wait for element* State box sits under More options; `>> nth=0` is not explained anywhere.

---

### P3 Book catalogue crawl (books.toscrape.com)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1 | Open site | **Open browser** | **Browser: Open** | As P1 | AA |
| s2 | Open the category by name | **Click element** selector `xpath=//div[contains(@class,'side_categories')]//a[normalize-space()='${category}']` | **Capture Link > Click**, with the link's text property set to `$sCategory$` | AA's captured object takes a variable in a ticked property (no XPath to write); ours needs a hand-written XPath with `${category}`. Our Capture editor also accepts `$variables` in property values, so the same is possible via Capture | Same (if Capture is used) |
| s10 | Every page | **Loop**, Iterator = While, Condition `$more` is true, Max iterations 60 | **Loop > While** Number condition `$nMore$ = 1` | Same flag pattern. Neither product has a "for each page (follow Next)" loop | Same |
| s3 | Wait for the books | **Wait for element** `article.product_pod` | Capture wait / object exists | As P2 | Ours |
| s4 | Read one page of books | **Run JavaScript** -> list of `{Title, Url, Price, Rating}` | Loop with Capture per field, or JavaScript; AA bot wrote JavaScript output to a tab file | Both need code for a repeated card layout (no "extract repeated items" action) | Same |
| s5-s6 | Add the page to the full table | **Create from records** (Records `$page_books`) -> `$page_table`; **Merge tables** (Tables = JSON list `["$all","$page_table"]`, Columns union) -> `$all` | AA bot: **Log to file** (append) per page, then **CSV/TXT Open/Read/Close** (file round trip). Natively: **Data Table: Merge** | Ours needs 2 steps and a JSON list typed in a text area. An "append rows to table" with a single source box would be 1 step | Ours (no file) |
| s9 | Is there a next page? | **If** builder > *Element exists* (condition args: `selector` = `li.next a`) | **If > recorder object exists** on the captured Next link (with wait seconds) | In our condition editor the fields are named by their internal names (`object`, `selector`, `session`, `window`) and there is **no Capture/Pick button**, so the selector must be typed | AA |
| s7/s8 | Click Next or stop | **Click element** `li.next a`; Else: **Assign** more = false | **Capture Link > Click**; Else **Number: Assign** 0 | Same | Same |
| s17 | Each book page | **Loop** Each row in a table, Over `$all`, As `b` | **For each row in table** | Same | Same |
| s11 | Go to the book | **Go to URL** Url `$b.Url` | **Browser: Open**, What to open = Existing tab, Link `$rB[1]$` | Ours: one field and the column by name; AA: tab + link and column by index | Ours |
| s12-s13 | Read UPC and availability | **Get text** with XPath `//th[normalize-space()='UPC']/following-sibling::td` | **Capture Table > Get cell text by index** (row 1, col 1) or Get cell index by text; or Capture the cell | Ours needs an XPath that finds a cell next to a header; AA's Table control has cell-by-text actions without XPath | AA |
| s14 | Stock number from text | **Assign** stock = `{"expr": "toNumber(coalesce(regexExtract($availability, \"[0-9]+\"), \"0\"))"}` | **String: Replace** (regex `[^0-9]` -> "") + **If** empty -> 0 / Else **String: To number** (4 actions) | Ours one step but three nested functions | Ours (fewer) |
| s15 | Add a report row | **Add row**: Table `$details`, Values = JSON list in column order `["$b.Title","$b.Price",...]` -> `$details` | AA bot: **Log to file** a CSV line (with quote escaping via String: Replace). Natively: Data Table: Insert row / Set value per column | Ours needs a JSON list in the right column order in a text area, and the output must be mapped back to the same table | Ours (no file), but JSON |
| s16 | Total stock | **Assign** `{"expr": "$total_stock + $stock"}` | **Number: Assign** | As P2 | AA |
| s18 | Close | **Close browser** | **Browser: Close** | Same | Same |
| s19 | Count books | **Row count** -> `$books` | **Data Table: Get number of rows** | Same | Same |
| s20-s21 | Most expensive title | **Sort** Price descending -> `$sorted`; **Get value** row 0, column Title | AA bot: running max with Number condition + Assign in the loop (3 actions per row). Natively: Data Table: Sort + get cell | Same with Sort; our Row is zero-based | Same |
| s22 | Summary per star rating | **Group and summarise**: Group by `["Rating"]`, Summaries = JSON `{"Books":"count","Average price":"avg:Price","In stock":"sum:In stock"}` -> table + count | **No group-by command.** AA bot wrote Excel formulas cell by cell (24 **Set cell** actions with COUNTIF/AVERAGEIF/SUMIF), or Dictionary loops | Ours is one step, but the summaries are a JSON object in a mini-language (`avg:Price`) typed by hand | Ours (clearly) |
| s23-s27 | Excel report with two sheets | **Create workbook** (Path, Sheet, Overwrite, Session `report`) + **Write from Data Table** (Table, Session) + **Create worksheet** (Name, Session) + **Write from Data Table** (Table, Sheet, Session) + **Close** (Session, Save true) | **Excel advanced: Create workbook / Open** + **Write from data table** + **Create worksheet** + **Switch to sheet** + **Write from data table** + **Close** (save) | Same length. Ours needs the session name `report` retyped in 5 steps (default is `excel`; the custom name buys nothing here) | Same |

#### Notes for P3

- **Click** on a link found by its visible text (with a variable), against P2's button by position and W1's button by id.
- **Loop While** with a flag (`$more`) and the same If/Else at the end of the body appears in P3, P5 and W2. This
  "follow Next until there is none" pattern is three steps plus a flag variable every time.
- **Add row** here takes a List in column order; P5 and P6 use the same action. With a Dictionary it would be by name,
  but the panel gives no hint of either beyond "Values".
- **Get text** with an XPath that walks from a header cell to its value; P6 does the same in an infobox.
- Confusing in Studio: *Element exists* in the condition editor shows four bare text inputs (`object`, `selector`,
  `session`, `window`) with no Capture button; `Merge tables` wants a JSON list; `Group and summarise` wants JSON with
  `function:column` strings.

---

### P4 Maintain records in a web table (demoqa.com)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1 | Read employees CSV | **Read to table** (CSV): Path -> `$employees` (1 step) | **CSV/TXT: Open** (session, path, header, delimiter, encoding) + **Read** + **Close** (3 actions) | Ours one step | Ours |
| s2-s3 | Open page, wait for rows | **Open browser**; **Wait for element** `table tbody tr` | **Browser: Open**; Capture wait | As before | Same |
| s4-s5 | Rows at the start | **Extract table** `table` -> `$start_table`; **Row count** -> `$rows_before` | **Capture Table > Get total rows** (1 action), or Get table + Data Table: Get number of rows with *select non-empty rows* | The page pads the table with empty rows; AA's row count can skip empty rows, ours cannot (see s30) | AA |
| s9 | Delete every Legal record | **Loop** While, condition *Element exists* `xpath=//tr[td[normalize-space()='Legal']]//*[starts-with(@id,'delete-record')]`, Max 20 | **Loop While** with a recorder object-exists condition on a captured delete icon whose row-text property is set to "Legal"; or For n times + If not exists Break | Same structure; ours repeats the long XPath in the condition and again in the click | Same |
| s6 | Click the first Legal delete icon | **Capture: element action**: selector `xpath=(...)[1]`, Action click, **Click method javascript** | **Capture (icon/Client) > Debugger click** or Click | We had to leave *Click element* for *Capture: element action* to get the javascript click (adverts cover the buttons). AA offers *Debugger click* on the same Capture | AA (one command) |
| s7-s8 | Count, short pause | **Assign** `{"expr": "$deleted + 1"}`; **Delay** 300 ms | **Number: Increment**; **Delay** 300 ms | As P1 | AA |
| s20 | Each employee | **Loop** Each row in a table `$employees` as `e` | **For each row in table** `$rE$` | Same | Same |
| s10-s11 | Open the Add form | **Capture: element action** `#addNewRecordButton` click (javascript); **Wait for element** `#userForm` | **Capture Button > Click**; wait via the next Capture | Same | Same |
| s12-s17 | Fill 6 fields | **Set text** x6: Text `$e.FirstName`, `$e.LastName`, `$e.Email`, `${e.Age}`, `${e.Salary}`, `$e.Department` | **Capture TextBox > Set text** x6 with `$rE[0]$` ... | Ours by column name (better), but numbers need `${...}` (text) instead of `$...`, and Studio shows `$e.FirstName` in *Plain text* mode with the help "Saved in the agent as typed", which is wrong for a variable | Ours (names) / confusing |
| s18-s19 | Submit, wait for the form to close | **Capture: element action** `#submit` click (javascript); **Wait for element** `#userForm` State **hidden** | **Capture Button > Click**; then Delay or a loop until the object no longer exists | Ours has a direct "wait until hidden"; but *State* is under More options | Ours |
| s21 | Search the employee | **Set text** `#searchBox` = `$raise_for` | **Capture TextBox > Set text** | Same | Same |
| s22-s26 | Edit the salary | **Capture: element action** click on `xpath=//tr[td[normalize-space()='${raise_for}']]//*[starts-with(@id,'edit-record')]` (javascript); **Wait for element**; **Set text** `#salary` = `${new_salary}`; click submit; wait hidden | **Capture (edit icon) > Click** with a variable in the row-text property; **Capture TextBox > Set text**; **Capture Button > Click** | Ours needs an XPath with a variable; AA does it via Capture properties | AA |
| s27 | Read the new salary | **Get text** `xpath=//tr[td[normalize-space()='${raise_for}']]/td[5]` | **Capture Table > Get cell text by index** (row found with *Get cell index by text*) | AA's table actions avoid XPath | AA |
| s28 | Clear the search | **Capture: element action** `#searchBox`, Action clear | **Capture TextBox > Set text** with empty keystrokes | Ours has a named Clear | Ours |
| s29-s30 | Final table and row count | **Extract table** -> `$end_table`; **Filter rows** Column Email, Operator *not_empty* -> output **count** to `$rows_after` | **Capture Table > Get table**; **Data Table: Get number of rows** with *select non-empty rows* | Ours uses *Filter rows* only to count non-empty rows: an indirect trick a developer would not find | AA |
| s31-s32 | Close, write CSV | **Close browser**; **Write from table** (Table, Path) | **Browser: Close**; **Data Table: Write to file** (file, create, append/overwrite, row and column delimiter, encoding) | Ours 2 fields | Ours |

#### Notes for P4

- **Click** appears in two commands in the same agent: *Click element* in most cases, but *Capture: element action /
  click* with *Click method = javascript* here. A developer only learns that by failure; the fix (javascript click) is
  not reachable from *Click element*.
- *Capture: element action* is used **without a captured object**, only a selector. Its name says "Capture" but it is
  really "Element action".
- **Set text** takes `$e.Column` here (P1 needed `toString()` expressions, W1 uses `${amount}`).
- **Wait for element** is used with State *hidden* (modal closes) here, *visible* elsewhere; W3 waits for "either of two
  elements" with a CSS list.
- **Loop While** has a page condition here (*Element exists*), while P3/P5/W2 use a Boolean flag.

---

### P5 Quotes and authors (quotes.toscrape.com)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1-s5 | Sign in | **Open browser**; **Set text** `#username`, `#password` (Variable mode); **Click** submit; **Wait for element** `a[href='/logout']` | **Browser: Open**; **Capture TextBox / Password Text > Set text**; **Capture Button > Click** | Same | Same |
| s6 | Remember sign-in | **Assign** signed_in = true | **Boolean: Assign** | Same | Same |
| s7 | Go to the JS-rendered list | **Go to URL** | **Browser: Open** (existing tab) | Ours 1 field | Ours |
| s16 | Every page | **Loop While** `$more` is true, Max 30 | **Loop While** Number `$nMore$ = 1` | As P3 | Same |
| s8-s9 | Read a page of quotes | **Wait for element** `.quote`; **Run JavaScript** -> list of `{Quote, Author, Tags}` | Capture per item in a loop, or JavaScript | As P3 | Same |
| s10-s11 | Append to all quotes | **Create from records** + **Merge tables** (JSON list) | AA bot: Log to file + CSV Open/Read/Close; natively Data Table: Merge | As P3 | Ours |
| s15 | Next page or stop | **If** *Element exists* `li.next a`; **Click**; **Delay 300 ms**; Else **Assign** more = false | **If** recorder object exists; **Capture Link > Click**; Delay | The 300 ms Delay is there because the new page has the same `.quote` elements, so *Wait for element* passes at once. Neither product has "click and wait for the page to change" | Same |
| s17 | Count quotes | **Row count** | Number: Increment inside the row loop (AA bot), or Get number of rows | Same | Same |
| s18 | Quotes per author | **Group and summarise**: Group by `["Author"]`, Summaries `{"Quotes":"count"}` | **No group-by.** AA bot: For each row, **If Dictionary: Check key**, **Dictionary: Get**, **Number: Increment**, Else Assign 1, **Dictionary: Put** (6 actions in a loop), then a **For each key** loop writing a file, then CSV Open/Read/Close and **Change column type** to Number | Ours 1 step against about 15 actions and a temporary file | Ours (clearly) |
| s19 | Rank | **Sort**: Column Quotes, Descending, **Then by** `["Author"]` | **Data Table: Sort** (one column, asc/desc) | Ours has a tie-breaker | Ours |
| s29 | Top N authors | **Loop** Each row in a table `$ranking`, As `a`, **To index** `{"expr": "$top_n - 1"}` | **For each row** + **Number: Increment** + **If** counter > 3 **Break** | Ours fewer steps but JSON in a Number field and zero-based thinking | Ours (fewer) / AA (clearer) |
| s20-s21 | Author page address (slug) | **Replace** x2 (regex `[^A-Za-z0-9]+` -> `-`, then trim dashes) -> `$slug` | **String: Replace** x2 (regex) | Same | Same |
| s22 | Open author page | **Go to URL** `https://quotes.toscrape.com/author/${slug}/` | **Browser: Open** existing tab with `$sSlug$` in the link | Same | Same |
| s23-s24 | Born date and place | **Get text** `.author-born-date`, `.author-born-location` | **Capture Label > Get property** `HTML InnerText` (x2) | As P1 | Ours |
| s25 | Add author row | **Add row**: Values JSON list, one of them `{"expr": "replace($place, \"in \", \"\")"}` | **String: Replace** then **Log to file** / Insert row | Ours mixes an expression object inside a JSON list: hard to write and read | AA (readable) |
| s28 | Keep the first author | **If** `$top_author` is empty; **Assign** x2 | **If String condition** = ""; **String: Assign** x2 | Same (could be a Get value at row 0 after the loop) | Same |
| s30-s33 | Sign out, close, two CSV files | **Go to URL** logout; **Close browser**; **Write from table** x2 | Browser: Open logout; Close; Log to file (AA bot) or Data Table: Write to file | Ours direct | Ours |

#### Notes for P5

- **Loop over a table** with *To index* (first N) here; P6 identical; P1/P3/P4 loop over all rows.
- **Delay** is a fixed pause after a click because the JavaScript page keeps the same elements; P4 and P6 also use a
  short Delay. A "wait for the page or the element's text to change" option on Click would remove these.
- **Replace** with regex is the same in both; ours is the one place where both products are equally simple.
- **Add row** carries an inline expression object inside its list: the most complex value in the nine agents.

---

### P6 Wikipedia country facts

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1-s2 | Open list page, wait for table | **Open browser**; **Wait for element** `table.wikitable` | **Browser: Open**; Capture wait | Same | Same |
| s3-s4 | Read countries | **Run JavaScript** (long script: skip flag links, World row, build article URL) -> list; **Create from records** -> `$countries` | **Capture Table > Get table** gives the text only (no links), so the article address must be built from the name; or JavaScript | Our *Extract table* also returns text only; links need JavaScript in both | Same |
| s20 | Top N countries | **Loop** Each row in a table, **To index** `{"expr": "$top_n - 1"}` | For each row + counter + If + Break | As P5 | Ours (fewer) |
| s5-s6 | Open article, wait for infobox | **Go to URL** `$c.Url`; **Wait for element** `table.infobox`, Timeout 20000 (under More options) | **Browser: Open** existing tab; Capture wait | Same | Ours |
| s7-s12 | Capital and currency when present | **Assign** capital = "" and currency = ""; **If** *Element exists* `xpath=(//table[contains(@class,'infobox')]//tr[th[starts-with(normalize-space(.),'Capital')]]/td//a[...])[1]` then **Get text** with the **same XPath** (and the same pair for Currency): 6 steps, each XPath written twice | **Capture Link > Get property** with *Set system time out > Advanced > When wait time has lapsed = Skip and proceed*; outcome stays empty when missing (2 actions) | Our *Get text* has no "if not found: continue" option (only *Capture: element action* has `if_not_found`), so every optional read costs a reset, an If and a duplicated selector | AA |
| s13 | Add a fact row | **Add row** Values `["$c.Country","$c.Population","$capital","$currency"]` | Log to file (AA bot) / Insert row | As P3 | Ours |
| s14-s15 | Count and total population | **Assign** x2 with `{"expr": ...}` | **Number: Increment**; **String: To number** + **Number: Assign** (AA must convert the text cell first) | Ours keeps numbers typed from the JavaScript result; AA's table cells are text | Ours |
| s18 | First country | **If** empty + **Assign** x2 | **If String condition** + **String: Assign** x2 | Same | Same |
| s19 | Be polite | **Delay** 500 ms | **Delay** 500 ms | Same | Same |
| s21-s24 | Close, Excel report | **Close browser**; **Create workbook** + **Write from Data Table** + **Close** (session `report`) | Browser: Close; AA bot: CSV via Log to file, then Excel Open + Save as + Close; natively Create workbook + Write from data table + Close | Same; session name retyped | Same |

#### Notes for P6

- **Get text** is used for an element that may be missing: the only case in this set where that happens, and it
  doubles the step count. In P1-P5 Get text always finds its element.
- **If** with *Element exists* guards a read here; in P3/P5/W2 it decides whether to click Next.
- **Wait for element** uses a longer timeout (Wikipedia is slow); the field is under More options.
- Confusing in Studio: the long XPath is shown truncated on the canvas in both the If and the Get text, so the two
  copies cannot be compared by eye; a typo in one of them fails silently (the If is false and the value stays empty).

---

### W1 Portal order entry (local demo portal)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1 | Open portal | **Open browser** Url `${folder}/site/portal.html` (a workspace file) | **Browser: Open** `http://127.0.0.1:8781/portal.html` (needs a web server) | Ours opens a local file path directly | Ours |
| s2-s3 | Sign in fields | **Set text** `#user` = `$portal_user`, `#pass` = `$portal_password` | **Capture TextBox > Set text**, **Capture Password Text > Set text** (credential) | Same; ours offers Credential in the Text source switch | Same |
| s4 | Sign in | **Click element** `#login` | **Capture Button > Click** | Same | Same |
| s5 | Wait for the order form | **Wait for element** `#order-form`, visible, Timeout 10000 | Capture wait on the next action (AA bot: XPath with `not(contains(@class,'hidden'))` in a loop) | Ours waits for real visibility; AA's object exists cannot tell hidden from shown without a property check | Ours |
| s6-s7 | Customer and amount | **Set text** `#customer` = `$customer`; `#amount` = `${amount}` (number turned into text) | **Capture TextBox > Set text** x2 | `${amount}` vs `$customer`: the developer must know that a Number needs the `${}` form | AA (no type care) |
| s8 | Order kind | **Select item** `#kind`, Option `$kind` | **Capture ComboBox > Select item by text** | Same; ours one Option box | Ours |
| s9-s10 | Place order, read number | **Click** `#submit`; **Get text** `#confirmation` -> `$confirmation` | **Capture Button > Click**; **Capture Label > Get property** InnerText | As P1 | Ours |
| (AA only) | Fail if no number | (none in ours) | **If** String = "" **Throw** | Ours does not check the confirmation; Get text waits for the element but accepts empty text | AA (bot is stricter) |
| s11 | Close | **Close browser** | **Browser: Close** (in Finally) | Ours has no Try/Finally here; if a step fails, the browser is still closed because Keep open = false | Same |

#### Notes for W1

- **Set text** with a plain variable (cleanest use in the set), except the Number field which needs `${amount}`.
- **Wait for element** with an explicit timeout (10 s) in the More options group; W3 does the same with 8 s.
- **Select item** by visible text from a variable: simpler than AA's three select variants.

---

### W2 Scrape a paged table (local demo site)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s1 | Open page 1 | **Open browser** | **Browser: Open** | Same | Same |
| s8 | Every page | **Loop While** `$more` is true, Max 20 | **Loop While** Number condition | As P3 | Same |
| s2 | Read the page's table | **Extract table** `#products` -> `$page_table` (1 field) | **Capture Table > Get table** -> data table variable | Same idea; ours can be pointed at with Capture too | Same |
| s3 | Append to all rows | **Merge tables** `["$all_products","$page_table"]` | **Data Table: Merge** (AA bot used a temp file) | Ours JSON list | Same |
| s7 | Next page or stop | **If** *Element exists* `#next`; **Click** `#next`; **Wait for element** `#products`; Else **Assign** more = false | If object exists; Capture Link > Click; Else Number: Assign 0 | Same; the Wait after Click passes at once (old table still visible), as in P5 | Same |
| s9 | Close | **Close browser** | **Browser: Close** | Same | Same |
| s10 | Price as number | **Change column type**: Column Price, Type Number | **Data Table: Change column type** (by name/index, type) | Same | Same |
| s11 | Save CSV | **Write from table** | **Data Table: Write to file** | Ours fewer fields | Ours |
| s12-s13 | Count and total | **Row count**; **Sum column** Price -> `$total_value` | Get number of rows; **no sum command**: For each row + String: To number + Number: Assign (AA bot) | Ours one step for the sum | Ours |

#### Notes for W2

- **Extract table** used on a real `<table>`; P3/P5/P6 had to use Run JavaScript because the data was not a table.
- **Loop While** with the flag pattern; identical to P3 and P5. Three agents repeat the same 5-step "next page" block.

---

### W3 Search, wait and branch (local demo site)

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
| --- | --- | --- | --- | --- | --- |
| s13 | Error handling around everything | **Try** with one **Catch** (all errors, *Take a screenshot when the error is caught* ticked) and **Finally** | **Try**, **Catch** (exception, variable), **Finally**: three separate blocks | Ours one block with sections; Catch has a built-in screenshot option | Ours |
| s1 | Open search page | **Open browser** | **Browser: Open** | Same | Same |
| s2 | Search | **Set text** `#q` = `$term`, **Press enter** = true (under More options) | **Capture TextBox > Set text** with keystrokes `$sTerm$[ENTER]` | AA types Enter as part of the keystrokes; ours has a checkbox, hidden under More options | Same |
| s3 | Wait for results **or** the no-results note | **Wait for element** `#results tr, #none` (CSS list = either one), visible, Timeout 8000 | No native "wait for one of two objects": Loop For n times with two object-exists checks and Break (as the AA bot did with a script) | Ours one step (if you know CSS lists) | Ours |
| s9 | Branch | **If** *Element is visible* `#none`; then **Assign** status; Else extract | **If** recorder object exists / **Else If** / **Else** | Same; condition fields shown by internal names in ours | Same |
| s5-s7 | Results | **Extract table** `#results`; **Row count** -> `$found`; **Get value** row 0, column Name | **Capture Table > Get table** + Get number of rows + get first cell (or **Capture Table > Get cell text by index** 1,1) | Same | Same |
| s8 | Status | **Assign** "found" | **String: Assign** | Same | Same |
| catch | Screenshot and status | Catch option *screenshot* ticked **and** a **Take screenshot** step (Path `search-error.png`) + **Assign** status = "error" | **Screen: Capture window** (window, file path, overwrite) + String: Assign | Ours takes two screenshots (the Catch option saves one to the run folder; the step saves another). The Catch help does not say where the option's file goes | Ours (option) |
| finally | Close | **Close browser** | **Browser: Close** | Same | Same |

#### Notes for W3

- **Wait for element** with a CSS selector list that matches either outcome: a neat trick, but undiscoverable; the
  panel has no "wait for any of these" choice.
- **If** uses *Element is visible* here, where P3/P5/P6/W2 use *Element exists*. The difference (hidden elements) is only
  in the descriptions.
- **Set text** with *Press enter*: the only use in the set, and it sits under More options because of the
  "after 3 fields" rule.
- **Take screenshot** duplicated with the Catch's own screenshot option.

---

### Findings across these cases

#### Problems with our commands and UI

| # | Command | Problem | Evidence | Suggested fix | Pri |
| --- | --- | --- | --- | --- | --- |
| F1 | **Set text** (`type_text`) - Text field | An expression value (`{"expr": ...}`) opens in *Plain text* mode and shows `[object Object]`; editing it destroys the value. A dotted reference (`$e.FirstName`) or `${e.Age}` also shows as *Plain text* with the help "Saved in the agent as typed", which is wrong | P1 s7-s13; P4 s12-s17; W1 s7 | Add an *Expression* mode (or show `{expr}` as text), treat any `$name.path` / `${...}` as *Variable*, and let the Variable mode pick a column of a row variable (`$e` > FirstName) | P1 |
| F2 | **Set text** - numbers | Numbers are not typed unless converted (`toString()`, `${}`): three different spellings for the same need | P1 s7-s13, P4 s15-s16, W1 s7 | Convert any number/boolean to text automatically in Set text (AA does) | P1 |
| F3 | **Assign variable** (`core/set`) - Value | Arithmetic must be typed as JSON `{"expr": "$x + 1"}`; typing `$rounds + 1` stays a literal string. Used 15+ times in these 9 agents | P1 s15, P2 s9/s14/s21/s23/s24, P3 s14/s16, P4 s7, P6 s14/s15 | Give Value a *Text / Expression* switch (Expression = no JSON, no braces); offer "Add 1" / "Add value" quick choice, or surface *Number: Increment* in the Assign panel | P1 |
| F4 | **Condition editor** - toolkit conditions (*Element exists*, *Element is visible*) | Fields shown by internal names (`object`, `selector`, `session`, `window`) as bare text inputs; **no Capture/Pick button**, so the element must be typed even though the action steps can capture it | P3 s9, P4 s9, P5 s15, P6 s10/s12, W2 s7, W3 s9 | Reuse `inputControl` for condition args: labels, Capture/Pick next to the selector, window editor; hide `session` | P1 |
| F5 | **Get text** (and Get attribute) | No "if not found" option: an optional element needs reset + If *Element exists* + Get text with the same selector written twice (6 steps for 2 values) | P6 s7-s12 | Add *If not found: error / continue (leave empty)* with a short wait, as *Capture: element action* already has | P1 |
| F6 | **Loop** - first N items | "First N" needs *To index* = `{"expr": "$n - 1"}` typed into a Number box; zero-based and inclusive | P2 s10, P5 s29, P6 s20 | Add *Only the first (count)* field for list/table loops; accept `$n - 1` without JSON; show the limit in the canvas summary ("each row in $ranking, first $top_n") which today omits it | P1 |
| F7 | **Wait for element** - State; **Set text** - Press enter | The generic "optional field with a default after the first 3 goes to More options" rule hides the one choice that matters: *State* (visible/hidden) and *Timeout*, and *Press enter* | P4 s19/s26 (hidden), W1 s5 / W3 s3 / P6 s6 (timeout), W3 s2 (press enter) | Mark key fields per action as always shown (e.g. `x-ooa-primary`), instead of counting fields | P2 |
| F8 | **Click element** vs **Capture: element action** | The javascript click (needed when adverts or overlays cover a button) exists only in *Capture: element action*, so the developer must replace the step; that action is also used with a plain selector although its name says "Capture" | P4 s6/s10/s18/s22/s25 | Add *Click method* (and Force) under More options of *Click element*; rename *Capture: element action* to "Element action" | P2 |
| F9 | Web actions - Selector and Captured object | Both a *Captured object* editor and a *Selector* box are shown for every web step; which one wins is only in the description | All web steps (P1-P6, W1-W3) | Show one "Element" field with Capture as the main path and "Use a selector instead" as a toggle | P2 |
| F10 | **Open browser** - defaults | Every unattended agent changes two More-options fields (Engine `playwright`, Keep open `false`) | P1-P6, W1-W3 s1 | Default Keep open to false when Headless is on or when run from Command Center; pick the engine automatically for headless runs | P2 |
| F11 | **Row count** | No "only non-empty rows"; P4 used *Filter rows* (Email not empty) just to count | P4 s30 | Add *Skip empty rows* to Row count and to Extract table | P2 |
| F12 | **Click** - wait after | After clicking Next on a page that keeps the same elements, *Wait for element* passes at once, so fixed Delays are added | P5 s13, W2 s5, P4 s8 | Add *Wait for: page load / element to change / element to appear* after the click | P2 |
| F13 | Data table steps with JSON | *Merge tables* (JSON list), *Add row* (JSON list in column order, even with inline `{expr}`), *Group and summarise* (JSON with `avg:Price`) | P3 s6/s15/s22, P5 s11/s18/s25, P6 s13 | Row editors: Merge = "Add rows of `$t` to `$all`" (2 table pickers); Add row = one labelled box per column of the target table; Summaries = rows of [new column][function][column] | P2 |
| F14 | Next-page pattern | Same 5 steps and a flag variable in three agents (While $more, If exists, Click, Wait, Else set false) | P3 s10, P5 s16, W2 s8 | Iterator "Each page (click Next until it is gone)" with a Next-element field | P3 |
| F15 | Reading repeated items | No action reads repeated cards (name + price per card) without JavaScript | P2 s7/s13, P3 s4, P5 s9, P6 s3 | "Extract list": pick the repeating item and the fields inside it (by Capture) -> Table | P2 |
| F16 | Selector syntax | Playwright-only syntax (`>> nth=0`, CSS lists `a, b`) in examples with no explanation in the panel | P2 s8, W3 s3 | Offer "which match: first / last / n-th" and "any of these elements" as fields | P3 |
| F17 | **Try / Catch** screenshot | Catch option *Take a screenshot* plus a separate *Take screenshot* step take two pictures; the option's file location is not stated | W3 catch | Say in the help where the screenshot goes (and that the path is in the error details); drop the extra step in the example | P3 |
| F18 | Excel sessions | Custom session name (`report`, `challenge`) retyped in 3-5 steps; header asked in both Open and Get all cells | P1 s3-s5, P3 s23-s27, P6 s22-s24 | Default every Excel step to the last opened workbook; hide Session under More options (it already is for most) and stop using custom names in examples | P3 |
| F19 | Canvas summary | Long XPaths are cut off, so duplicated selectors cannot be compared; If with an expression left side reads as code | P6 s10-s12, P1 s16 | Show the captured object's label (`link "Capital"`) or the step Note; for selectors show the tail (`...td//a[1]`) | P3 |

#### Where ours is clearly better than AA (keep)

- **Group and summarise**: one step where AA needs a Dictionary loop of about 15 actions plus a file round trip (P5 s18),
  or 24 Excel formula cells (P3 s22).
- **Sum column**, **Sort with Then by**, **Get value**: AA has no sum and a single-column sort (W2 s13, P5 s19).
- **CSV read / write in one step** against AA's Open/Read/Close session (P4 s1, s32; W2 s11).
- **Get text on any element**: AA labels and links only offer *Get property* with a property name (P1 s18, P2 s26).
- **Download file by clicking the link**, returning the saved path (P1 s2); AA needs the address first.
- **Go to URL** with one field and row columns by name (`$b.Url`) where AA uses tab + link and `$rB[1]$` (P3 s11).
- **Wait for element** with states (visible, hidden) and a CSS list for "either of two" (P4 s19, W3 s3); AA has no
  direct wait-until-gone.
- **Select item** with one Option box for value or text (P2 s6, W1 s8).
- **Try with a built-in screenshot option** in Catch (W3).
- **Columns by name** in loops (`$e.FirstName`) instead of AA's index (`$rE[0]$`), which breaks when columns move.
- Opening a **local HTML file** directly (W1-W3) without a web server.

## Command-by-command review B: W4 to W10 (One Agent Studio vs AA Community Edition)

Sources: our agents in `examples/use-cases/W4..W10-*.flow.json`, the action catalogue (`packages.json`), Studio UI (`studio.js`: `inputControl`, `renderProps`, `conditionEditor`, `summary`/`loopText`), the AA bots W4..W10 and the AA command catalogue read from the Control Room tab.

How to read the AA column: the AA bots did all web work with "Browser: Run JavaScript" helper scripts plus "For n times" + Break waiting loops. A normal AA developer would instead use **Recorder: Capture** (pick the browser tab, record the object, choose an action such as Click / Set text / Get Text / Get property / Select item by text / Check, and set the Wait settings: wait for browser response and wait for object response, in seconds) and **If: Object exists** for checks. The AA column describes that native approach; the helper-script version is mentioned only where it matters.

Field counts mean "fields the developer has to touch for this case", not every field on the panel.

---

### W4 Register people from Excel in a web form

| # | Step purpose | Ours: command + how it is configured here | AA: native command + how it would be configured | Difference (UI/feature/usage) | Simpler in |
|---|---|---|---|---|---|
| 1 | Open the workbook | Excel / Open: Path `${folder}/data/people.xlsx`, Session `people`, Header true (default). 1 required field (+ session name because two workbooks are not open, the default `excel` would do). | Excel advanced / Open: source = File, file path, "Sheet contains a header", Open in read-write mode; output = Excel session variable (must be created). The AA bot had to open with header **off** so that cell addresses written later line up with sheet rows, and switch header **on** again in the read step. | Ours: header flag on Open is only a default for reads; Set cell always uses real addresses. AA: header setting on the session changes how rows are counted, a known trap. AA needs a session variable created. | Ours |
| 2 | Read the sheet as a table | Excel / Get all cells: Session; out table -> `$people`. 1 field + output. | Excel advanced / Get worksheet as data table: Active worksheet, "Sheet contains a header", Read option (visible text / value), session; output data table variable. 3-4 fields. | Same idea. Ours inherits the header choice from Open; AA asks again. | Ours (slightly) |
| 3 | Open the form page | Browser / Open: Url, Headless `$headless`, Engine `playwright`, Keep open false. Engine and keep_open had to be set explicitly (defaults: engine auto, keep_open **true**). | Browser / Open: What to open = New window, browser, link; then usually Window / Maximize. The AA bot also added a 1 s Delay and a 40x "check exists" loop to wait for the page. | AA has no "wait for page" on Open in practice; Capture's wait settings cover it on the first object. Ours waits for load (timeout_ms 240000). Ours needs 2 non-obvious options (engine, keep_open) to behave like a test run. | Ours |
| 4 | Loop over rows | Loop: Iterator "Each row in a table", Over `$people`, As `person`, Index `i`. `person` (Dictionary) and `i` (Number) had to be declared first in Variables; "As" and "Index" are plain text boxes. | Loop / Loop: Iterator = Data Table: For each row in table, table variable, "Assign the current row to this variable" (Record variable, can be created from the dialog). | AA creates the row variable from the field; ours needs the variable declared first. Ours gives a zero-based index for free; AA needs its own counter (Number: Increment). | Even |
| 5 | Type name and e-mail | Browser / Type text x2: Selector `#name` / `#email`, Text `$person.Name` / `$person.Email`. 2 fields each; the Text field has a credential/variable/plain-text editor. | Recorder / Capture x2: capture the textbox, Action = Set text, keystrokes `$rP{Name}$` (or by index `$rP[0]$`), wait settings. 3-4 fields each, plus the capture itself. | Ours reads columns by header name (`$person.Name`); the AA bot used positions (`$rP[0]$`) because the record was read by index. Both support capture/pick. | Ours |
| 6 | Pick the plan | Browser / Select option: Selector `#plan`, Option `$person.Plan` (value or visible text). | Capture the ComboBox, Action = Select item by text, value. | Equivalent. Ours accepts value or text in one field; AA has separate "by text / by index / by key". | Even |
| 7 | Save | Browser / Click: Selector `#save`. 1 field. | Capture the Button, Action = Click. | Equivalent. | Even |
| 8 | Read the new registration number | Browser / Get text: Selector `#last`; out text -> `$reg_id`. | Capture, Action = Get Text (or Get property), save outcome to variable. The AA bot needed a 300 ms Delay before it. | Ours waits for the element automatically (30 s), but does not wait for the text to *change*: it relies on the page updating synchronously. | Even |
| 9 | Work out the sheet row | Assign variable: Variable `row_no`, Value `{"expr": "$i + 2"}`. The expression has to be typed as **JSON** in the Value box; typing `$i + 2` stores the literal text. | Number / Increment on a counter that starts at 1 (no conversion needed until the cell address). | Ours: zero-based loop index + header row means "+2", and the arithmetic needs the `{"expr":...}` wrapper. AA: one Increment, simple. | AA |
| 10 | Write the number to column D | Excel / Set cell: Cell `D${row_no}`, Value `$reg_id`, Session. | Excel advanced / Set cell: Specific cell `D$nRow.Number:toString$`, value, session. | AA needs the Number->text conversion inside the address; ours interpolates directly. | Ours |
| 11 | Count and remember last id | Assign variable `registered` = `{"expr": "$registered + 1"}`; Assign `last_id` = `$reg_id`. | Number / Increment (source, by 1, output); String / Assign. | We have Number / Increment too, but the sample uses Assign + JSON expression. Increment in both products has 2 fields + output. | AA (for the counter, as written) |
| 12 | Header for the new column | Excel / Set cell `D1` = "Registration". | Same with Set cell. | Equal. | Even |
| 13 | Read back D3 for the check | Excel / Get cell `D3` -> `$check_cell`. | Excel advanced / Get single cell, Specific cell, Read option, session. | Equal; AA adds the read option choice. | Even |
| 14 | Save as output file | Excel / Save with Path (save-as). There is also a separate Excel / Save as action. | Excel advanced / Save as: path, replace existing file, session. | Ours offers two ways to do the same thing (Save with a path, Save as). | Even |
| 15 | Close workbook and browser | Excel / Close (save false); Browser / Close. No Try/Finally in ours. | Excel advanced / Close; Browser / Close (What to close = Window). The AA bot put both in Finally of a Try/Catch. | Ours does not need Finally because a run ends sessions anyway; AA bots need explicit cleanup in Finally to not leave Chrome open. | Ours |

#### Notes for W4
- Loop "Each row in a table" here uses the **Index** output (`i`) to compute a sheet row; in W5/W7/W8 the same loop does not use an index. Mixing zero-based indexes (loop, Data table Get value) with one-based sheet rows is the main mental cost of this case; the `+ 2` is easy to get wrong.
- `type_text` takes a column of the row dictionary (`$person.Name`), the simplest possible form; in W6 it takes a variable built by an If, in W9 an interpolated sentence, in W10 `${line.qty}` (interpolation used only to turn a number into text).
- Confusing in Studio: "As" and "Index" of Loop are free text boxes, so a typo or an undeclared variable is only caught at validation/run time. The Value box of Assign accepts `{"expr": ...}` only as JSON.

---

### W5 Reconcile web orders with a CSV export

| # | Step purpose | Ours | AA native | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Open the order page | Browser / Open (url, headless, engine, keep_open). | Browser / Open (New window, Chrome, link). | As W4. | Ours |
| 2 | Read the web table | Browser / Extract table: Selector `#orders`; out table -> `$web_orders`. 1 field + output. | Capture the Table object, Action = Get table, save outcome to a data table variable. The AA bot instead ran a JavaScript that returned tab-separated text, wrote it with Log to file and read it back with CSV/TXT Open/Read/Close (6 steps). | Native AA is also one command here; ours needs no capture if the table has an id. Ours returns header names from `<th>`. | Even (ours vs native), ours by far vs the bot |
| 3 | Close the browser | Browser / Close. | Browser / Close in Finally. | — | Even |
| 4 | Read the ERP export | CSV / Read: Path; out table. 1 field. | CSV/TXT Open (session, path, header, delimiter, encoding) + Read (session -> table) + Close (session). 3 commands, ~6 fields. | Ours reads in one step with sensible defaults (comma, header, UTF-8). | Ours |
| 5 | Loop over web orders | Loop each row in `$web_orders` as `row`. | Loop: For each row in table -> record. | Equal. | Even |
| 6 | Look up the order in the export | Data table / Find: Table `$expected`, Value `$row.Order`, Column `Order`, Match equals; outputs found -> `$found`, first_row -> `$at`. 3 fields + 2 outputs. | No table lookup in AA: the bot first loops the export into a Dictionary (Dictionary / Put per row), then uses If "Dictionary: Check key" and Dictionary / Get. 1 extra loop + 2 commands. | Ours avoids the Dictionary build loop. But it is still a 3-step pattern (Find, If found, Get value). | Ours |
| 7 | Branch: missing / different | If `$found` is false -> Add row; Else -> Data table / Get value (Table, Row `$at`, Column `Status`) -> `$want`, then If `$row.Status` does not equal `$want` -> Add row. | If Check key / Else; inside, String condition NEQ. Rows written with Log to file lines (the AA bot) or Data Table insert. | Ours: condition builder rows ("Is false", "does not equal") read well. Get value needs the zero-based row from Find. | Ours (slightly) |
| 8 | Collect a difference | Data table / Add row: Table `$differences`, Values `["$row.Order", "$row.Status", "(missing)"]` typed as a JSON list; output table **must be stored back** into `$differences`. | The AA bot appended a CSV line with Log to file (file path + text each time). | Ours: values as JSON in a textarea, and if the output is left on "(do not store)" the row is silently lost (Add row returns a copy). | Even (both awkward) |
| 9 | Count checked orders | Assign `checked` = `{"expr": "$checked + 1"}`. | Number / Increment. | As W4. | AA |
| 10 | Write the report | CSV / Write: Table, Path. 2 fields. | Data Table / Write to file: table, file name, create folders, overwrite/append, row delimiter, column delimiter, encoding (7 fields), or Log to file lines. | Ours writes header + quoting correctly with 2 fields. | Ours |
| 11 | Count mismatches | Data table / Row count -> `$mismatches`. | Data Table / Get number of rows (non-empty / total) or a counter. | Equal. | Even |

#### Notes for W5
- Here the "differences" table is declared with default columns in Variables (table editor), then filled by Add row. In W6/W7/W8 the same pattern repeats; every Add row must map its output back to the same variable.
- Data table / Find is used for an exact lookup by key; W8 and W9 repeat exactly the same 3-4 step lookup (Find, set default, If found, Get value). That pattern deserves a single "Look up value" action.
- Confusing in Studio: the "Values" field of Add row is a JSON textarea ("value, $variable or JSON"); a list of `$variables` must be written with quotes and brackets.

---

### W6 Invoice approval process

| # | Step purpose | Ours | AA native | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Open the portal | Browser / Open. | Browser / Open + Maximize. | As W4. | Ours |
| 2 | Sign in | Type text `#user` = `$portal_user`; Type text `#pass` = `$portal_password`; Click `#login`. | Capture TextBox Set text (keystrokes), Capture Password Text Set text with **credential** option, Capture Button Click. | Both can take a vault credential in the password field (ours: credential/variable/plain-text switch). The sample passes a plain String input, which in a real process should be a Credential. | Even |
| 3 | Wait for the queue | Browser / Wait for: Selector `#queue-view`, State visible. | Capture on the next object with "Wait for object response" seconds; no separate wait command needed. The AA bot used a JS check-exists loop. | Ours has an explicit wait step (clear, but an extra step); AA folds waiting into each Capture. | Even |
| 4 | Filter to Pending | Select option `#status-filter` = "Pending". | Capture ComboBox, Select item by text "Pending". | Equal. | Even |
| 5 | Work until the queue is empty | Loop: Iterator "While a condition is true", Condition = Browser condition "Element exists" with selector `#queue tbody tr`, Max iterations 50. | Loop / While with condition "Object exists" (captured row object) or For n times + If not exists -> Break. | Ours: one loop step. But in the condition editor the element condition shows raw field names (`object`, `selector`, `session`, `window`) as plain text boxes, with no Capture/Pick button. | Ours (feature), AA (UI of the condition) |
| 6 | Open the first invoice | Click `#queue tbody tr:first-child button.open`. | Capture the first row's button (edit its DOMXPath to index 1) or Capture Table "Click on cell by index". | Ours needs CSS knowledge for "first row's button"; AA's table-cell click is a point-and-click option. | AA |
| 7 | Wait for the detail view | Wait for `#detail-view` visible. | Wait settings on the next Capture. | As 3. | Even |
| 8 | Read invoice, supplier, PO | Get text x3 -> `$inv`, `$supplier`, `$po`. | Capture Get Text x3 (or Get property innerText). | Equal; ours 1 field + output each. | Even |
| 9 | Read the amount | Get attribute: Selector `#d-amount`, Attribute `data-value` -> `$amount_text`; then Assign `amount` = `{"expr": "toNumber($amount_text)"}`. | Capture Get property, property name `data-value`; String / To number. | Same number of steps. Ours has a String / Text to number action but the sample uses a JSON expression instead. | Even |
| 10 | Apply the rules | If `$po` is empty -> Assign decision/reason; Else if `$amount` > `$limit` -> Assign x2; Else -> Assign x2. Reason uses interpolation `Above the approval limit of ${limit}`. | If String condition = "" / Else If Number condition GT / Else, String / Assign x2 each; the reason needs `$nLimit.Number:toString$`. | Ours: one If with Else-if branches in its property panel, "is empty" operator, numbers interpolate directly. AA: no "is empty" operator, Number->String conversion inside text. | Ours |
| 11 | Write the comment and decide | Type text `#comment` = `$reason`; Click `#${decision}` (selector built from a variable). | Capture Set text; Capture Click on a button whose id property is edited to `$sDecision$`. | Ours: variable in a selector is just text. AA: possible, but you edit the captured object's property and lose the point-and-click view. | Ours |
| 12 | Back to the queue | Wait for `#queue-view` visible. | Wait settings / Object exists loop. | — | Even |
| 13 | Record the decision | Add row to `$decisions` (JSON list of 5 values, output stored back); Assign `processed` + 1 via JSON expr. | Log to file line (bot) / Data Table insert; Number / Increment. | As W5. | Even |
| 14 | Read portal counters | Get text x3 into **Number** variables `approved`, `rejected`, `escalated`. | Capture Get Text x3 into String variables (+ To number if needed). | Runtime converts the text, but Studio's output dropdown only lists String variables for a String output, so a developer in Studio would see these Number variables flagged "does not match String". | Even |
| 15 | Close browser, write CSV | Browser / Close; CSV / Write `$decisions`. | Browser / Close in Finally; Log to file / Data Table Write to file. | As W5. | Ours |

#### Notes for W6
- Loop is used as **While** with an element condition (in W4/W5 it was "each row"). The canvas summary for it reads "Repeat for while Element exists (#queue tbody tr)", which is ungrammatical.
- `type_text` takes a computed variable (`$reason`), and `click` uses a selector containing a variable (`#${decision}`). Both work, but a developer who used Capture instead of a selector would have to know that captured property values also accept `$variables`.
- The approval rules are where ours is clearly easier: one If block with Else-if branches and readable operators.
- Confusing: three ways to wait (Wait for step, the 30 s built-in timeout of every element action, the While condition); which one to use is not obvious.

---

### W7 Supplier onboarding wizard

| # | Step purpose | Ours | AA native | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Read suppliers | CSV / Read -> `$suppliers`. | CSV/TXT Open + Read + Close. | As W5. | Ours |
| 2 | Open the wizard | Browser / Open. | Browser / Open + Maximize. | — | Ours |
| 3 | Loop suppliers | Loop each row in `$suppliers` as `sup`. | For each row in table. | Equal. | Even |
| 4 | Business-error frame | Try (no fields) with Catch: Errors to catch `BUSINESS.*`, Error message to `$err`. | Error handler Try / Catch: exception type (BotException ...), message variable. | Ours can catch by our own code family (`BUSINESS.*`) and let technical errors fail the run; AA's Catch can only filter by exception type, so a business refusal and a broken selector land in the same Catch. | Ours |
| 5 | Step 1: company, country, VAT | Type text `#company`; Select option `#country`; Type text `#vat`; Click `#next1`. | Capture Set text x2, Capture ComboBox Select item by text, Capture Click. | Equal; columns by name in ours, by index in the AA bot. | Even |
| 6 | Give the page time | Delay: Seconds `300`, Unit milliseconds. | Delay: Regular, 300, Milliseconds. | Ours labels the field "Seconds" even when the unit is milliseconds. | AA (label) |
| 7 | Refused at step 1? | If Browser condition "Element visible" `#error1` -> Get text `#error1` -> `$msg`; Throw: Code `BUSINESS.REFUSED`, Message `$msg`. | If Object exists (captured error box) -> Capture Get Text -> Throw: message, exception type. | Ours: Throw needs a Code (required) and a Message; the code is what makes the Catch filter work. The condition has no "wait up to" option, hence the fixed 300 ms delay before it. | Even |
| 8 | Wait for step 2 | Wait for `#step2` visible. | Capture wait settings. | — | Even |
| 9 | Step 2: IBAN, BIC, next, check error | Type text x2, Click, Delay 300 ms, If element visible `#error2` -> Get text -> Throw. | Same with Capture + Object exists + Throw. | Same block repeated; neither product has a "wizard step" helper. | Even |
| 10 | Step 3: contact, terms, submit | Type text x2; Element action: Selector `#terms`, Action `check`; Click `#submit`; Wait for `#done`. | Capture Set text x2; Capture Checkbox Action = Check; Capture Click. | Ours: "Element action" has 50 actions in its Action list; with a plain selector (no capture) the list is not narrowed to checkbox actions. AA shows only the actions of the captured control type. | AA (UI) |
| 11 | Read supplier number, log success | Get text `#supplier-id` -> `$sid`; Add row (JSON list) -> stored back; Assign onboarded + 1 (JSON expr); Assign last_id; Click `#new`. | Capture Get Text; Log to file line / Data Table insert; Number / Increment; String / Assign; Capture Click. | As W5/W6. | Even |
| 12 | Catch: log refusal and restart | Add row with `$err`; Assign failed + 1; Click `#start-over`. | Same in AA Catch. | Equal. | Even |
| 13 | Close browser | Browser / Close. | Browser / Close in Finally. | — | Even |
| 14 | Results to Excel | Excel / Create workbook (Path, Sheet `Onboarding`, Overwrite true, Session `report`); Excel / Write table (Table, Session); Excel / Close (Save true). 3 steps. | Excel advanced: Open/create + Write from data table + Save/Close. The AA bot wrote a CSV with Log to file and then opened it in Excel and used Save as to get .xlsx (workaround). | Ours: clean 3 steps, but Overwrite defaults to false (a second run fails unless set) and Close must be told Save = true (default false). | Ours |

#### Notes for W7
- Try/Catch is used for **business** errors raised on purpose (Throw `BUSINESS.REFUSED`), unlike W4..W6 where nothing is caught and unlike the AA bots where Try/Catch is only a safety net around everything.
- "Element visible" condition after a fixed delay is fragile: a slower page shows neither error nor step 2 after 300 ms. A wait that accepts "either #error1 or #step2" (as W8 does with a comma selector) would be more robust; we should make that pattern obvious.
- Confusing: Throw needs a Code the developer must invent (no default); Excel Create workbook Overwrite = false and Close Save = false are the wrong defaults for a report written at the end of an agent.

---

### W8 Price comparison across three shops

| # | Step purpose | Ours | AA native | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Read products and last prices | CSV / Read x2; second with Detect types true (LastBest as numbers). | CSV/TXT Open/Read/Close x2 (6 commands); then a Dictionary build loop for last prices (Loop + Dictionary / Put). | Ours: typed values from the file in one option; AA keeps everything text and needs To number later. | Ours |
| 2 | Open the browser | Browser / Open shop-a. | Browser / Open + Maximize. | — | Ours |
| 3 | Loop products | Loop each row in `$products` as `p`; Assign best = 999999, best_shop = "". | For each row; Number / Assign, String / Assign. | Equal. | Even |
| 4 | Loop shops | Loop "Each item in a list" over `$shops` (List variable with default ["Shop A","Shop B","Shop C"]) as `shop`. | For n times (3) with counter; String / Assign of the shop name inside each branch. | Ours: the list of shops is data, not hard-coded in branches. | Ours |
| 5 | Per-shop layout | If `$shop` equals "Shop A" / Else if "Shop B" / Else. | If Number condition nShop = 1 / Else If = 2 / Else. | Equal structure. | Even |
| 6 | Shop A search | Navigate (Url); Type text `#search`; Click `#go`; Wait for `#results tbody tr, #none` visible; If element exists `#results tbody tr` -> Get text `#results tbody tr:first-child td:nth-child(2)` -> `$raw`. | Browser / Open with "Existing tab"; Capture Set text; Capture Click; then a wait for **either** result or "no results" (no native "wait for one of two objects": For n times + two Object exists checks + Break); Capture Table Get cell text by index (row 1, column 2). | Ours: one Wait step with an OR selector (comma) handles both outcomes, but the developer must know CSS comma syntax and nth-child. AA's table "Get cell text by index" is point-and-click. | Ours (wait), AA (cell pick) |
| 7 | Shop B search | Navigate; Type text `input[name=query]` with Press enter true; Wait for `.product, .no-results`; If exists `.product` -> Get text `.product:first-child .price`. | Capture Set text, then Simulate keystrokes [ENTER] or Capture Set text with `[ENTER]` in keystrokes; same either/or wait problem; Capture Get Text. | Ours: Press enter is a checkbox on Type text. | Ours |
| 8 | Shop C search | Navigate; Type text `#find`; Click `#find-btn`; Wait for `#hits li, .empty:not(.hidden)`; If exists `#hits li` -> Get attribute `#hits li:first-child` `data-price`. | Capture Set text; Capture Click; either/or wait loop; Capture Get property `data-price`. | Same. `.empty:not(.hidden)` needs real CSS skill. | Ours (fewer steps), AA (no CSS) |
| 9 | Clean and convert the price | If `$raw` is not empty -> String / Replace (Text, Find `[^0-9,.]`, Replace with "", Regex true) -> `$clean`; String / Replace ("," -> ".") -> `$clean`; Assign price = `{"expr": "toNumber($clean)"}`. | String / Replace (regex) x2; String / To number. | Same 3 steps. Ours has String / Text to number with Locale (`de-DE`) that could replace the second Replace and the expression. | Even |
| 10 | Keep the cheapest | If `$price` < `$best` -> Assign best, best_shop. | Number condition LT -> Number / Assign, String / Assign. | Equal. | Even |
| 11 | Look up last week's price | Data table / Find (Table `$last`, Value `$p.Product`, Column `Product`) -> found, first_row; Assign previous = 0; If found -> Get value (Row `$at`, Column `LastBest`) -> `$previous`. 4 steps. | If Dictionary Check key -> Dictionary Get -> String / To number. (Plus the Dictionary build loop from step 1.) | Ours 4 steps for a lookup with default; AA 3 + the earlier build loop. | Even |
| 12 | Changed? | Assign changed = `{"expr": "$best != $previous"}`; If `$changed` is true -> Assign changes + 1. | If Number condition NEQ -> String / Assign "true" + Increment / Else -> Assign "false". | Ours needs a JSON expression to get a Boolean; AA needs an If/Else with two assigns. | Even |
| 13 | Office chair shop | If `$p.Product` equals "Office chair" -> Assign chair_shop. | String condition EQ -> String / Assign. | Equal. | Even |
| 14 | Report row and totals | Add row (5 values incl. numbers and boolean); Assign total_best (expr), compared (expr). | Number / To string x2 (2 decimals) then Log to file line; Number / Assign with `$nTotal$ + $nBest$`; Increment. | Ours keeps numbers as numbers in the table; AA needs To string before writing text. | Ours |
| 15 | Close, write CSV | Browser / Close; CSV / Write. | Browser / Close in Finally; file already written line by line. | — | Ours |

#### Notes for W8
- Loop is used three ways in one agent: rows of a table (products), items of a List variable (shops), and the If/Else-if chain inside replaces AA's "For n times + index".
- Selectors carry most of the difficulty: comma "either" selectors, `:first-child`, `nth-child(2)`, `:not(.hidden)`. Pick/Capture records one element, it cannot express "first row" or "either A or B", so a non-developer would be stuck on steps 6-8.
- `ooa.browser/navigate` is used to change shop in the same tab (1 field) while AA uses Browser Open with "Existing tab".
- Confusing: price parsing uses two String / Replace steps and a JSON expression although a locale-aware "Text to number" action exists.

---

### W9 Leave requests against remaining balances

| # | Step purpose | Ours | AA native | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Open the leave manager | Browser / Open. | Browser / Open + Maximize. | — | Ours |
| 2 | Read balances | Click `#tab-balances`; Extract table `#balances-table` -> `$balances`; Data table / Change column type (Table, Column `Remaining`, Type Number) -> stored back to `$balances`. | Capture Page Tab / Click; Capture Table Get table; then either loop + String / To number + Dictionary / Put per row (what the AA bot did, after a JS + file + CSV workaround) or Data Table / Change column type. | Ours: 3 steps. Change column type returns a copy, so its output must again be mapped back. | Ours |
| 3 | Go to requests | Click `#tab-requests`. | Capture Click. | Equal. | Even |
| 4 | Each pending request | Loop While, condition element exists `#requests-table button.review`, Max iterations 50. | While Object exists / For n times + Break. | As W6. | Ours |
| 5 | Open the pop-up | Click `#requests-table button.review` (first match); Wait for `#modal` visible. | Capture Click on the first Review button; then wait settings on the next Capture or an Object exists loop (it is an in-page pop-up, so Wait for window does not apply). | Ours clicks the first match implicitly; the AA bot needed `(...)[1]` in the XPath. | Ours |
| 6 | Read employee and days | Get text `#m-employee` -> `$who`; Get text `#m-days` -> `$days_text`; Assign days = `{"expr": "toNumber($days_text)"}`. | Capture Get Text x2; String / To number. | Equal step count; ours uses a JSON expression for the conversion. | Even |
| 7 | Find remaining days | Data table / Find (balances, `$who`, column Employee) -> found, at; Assign left = 0; If found -> Get value (row `$at`, column Remaining) -> `$left`. 4 steps. | Number / Assign 0; If Dictionary Check key -> Dictionary Get. 3 steps (after the Dictionary was built). | Same lookup-with-default pattern as W5/W8. | Even |
| 8 | Enough days? | If `$days` <= `$left`: Data table / Set value (Table, Row `$at`, Column, Value `{"expr": "$left - $days"}`) -> stored back; Type text `#m-reason` = `Approved. ${days} days booked.`; Click `#m-approve`; Assign approved + 1. Else: Type text reason `Not enough leave left (${left} days).`; Click `#m-decline`; Assign declined + 1. | Number condition LTE; Number / Assign (subtract) + Dictionary / Put; Capture Set text with `$nLeft.Number:toString$` inside the sentence; Capture Click; Increment. | Ours: interpolation of numbers in text is direct; Set value needs 4 fields + JSON expr + store back. AA: number-to-text conversion inside every sentence. | Ours |
| 9 | Wait for the pop-up to close | Wait for `#modal` State hidden; Delay 300 ms. | Object exists (not) loop / wait; Delay. | Ours: hidden state is one option of Wait for. | Ours |
| 10 | Check the dashboard | Click `#tab-dashboard`; Get text x3 into Number variables; Assign dashboard_matches = `{"expr": "$d_approved == $approved + 1 && $d_declined == $declined && $d_pending == 0"}`. | Capture Click; Capture Get Text x3; String / To number; three nested Ifs with Else branches and String / Assign "yes"/"no" (12 commands in the AA bot). | Ours: one expression, but only as JSON in the Value box. The condition builder (AND of three comparisons) exists for If but not for Assign. | Ours |
| 11 | Ann's remaining days | Data table / Find (value "Ann") -> at; Get value -> `$ann_left`. | Dictionary / Get key "Ann". | AA's dictionary is one step here. | AA |
| 12 | Close and write balances | Browser / Close; CSV / Write `$balances`. | Browser / Close; Loop over dictionary keys + Log to file lines (the AA bot). | Ours writes the updated table directly. | Ours |

#### Notes for W9
- Data table is used as an **updatable lookup** here (Set value), not just as a report as in W5..W8. Every change returns a new table that must be stored back; forgetting the output mapping silently drops the update.
- `type_text` takes an interpolated sentence (`Approved. ${days} days booked.`), unlike W4's plain column and W6's variable.
- Get text into Number variables again (dashboard counts): works at run time, but Studio's output dropdown does not offer Number variables for a String output.
- Confusing: Data table row numbers are zero-based (`first_row`), while everything a business user sees (Excel rows, "row 1") is one-based.

---

### W10 Order fulfilment with invoice check

| # | Step purpose | Ours | AA native | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Read the orders JSON | File / Read text -> `$orders_json`; JSON / Parse -> `$orders` (List of order dictionaries with a `lines` list each). 2 steps. JSON / Read JSON file would do it in 1. | Text file / Get text reads only .txt files, so the AA bot created folders, copied the .json, renamed it to .txt (5 commands with Try/Catch around each), then parsed the JSON with a JavaScript run in the browser and wrote two tab files to read as tables. Native alternative: JSON Start session (from file) + Get node value / Convert JSON to Table; nested arrays (lines inside orders) are not supported well. | Ours handles nested JSON natively and loops over it as lists and dictionaries. | Ours |
| 2 | Open the order admin | Browser / Open. | Browser / Open + Maximize. | — | Ours |
| 3 | Each order | Loop "Each item in a list" over `$orders` as `o`. | For each row in the orders table (after the workaround). | Ours iterates JSON objects directly. | Ours |
| 4 | New order form | Click `#new-order`; Wait for `#order-form`; Type text `#customer` = `$o.customer`. | Capture Click; wait; Capture Set text. | Equal. | Even |
| 5 | Each product line | Assign k = 0; inner Loop over `$o.lines` as `line`: Click `#add-line`; Assign k = `{"expr": "$k + 1"}`; Select option `#lines .line:nth-child(${k}) select.product` = `$line.product`; Type text `#lines .line:nth-child(${k}) input.qty` = `${line.qty}`. | For each row in the lines table + If line belongs to this order (filter by hand); Capture Click; Increment; Capture ComboBox / TextBox with the object's DOMXPath edited to include `[$nK.Number:toString$]`. | Ours: nested loop over the order's own lines, no filtering. Both need a counter for the n-th line; ours puts it in a CSS selector, AA in an edited XPath. Ours keeps a separate counter `k` rather than the Loop index, because the index is zero-based and nth-child is one-based. | Ours |
| 6 | Create and read the order number | Click `#create`; Wait for `#order-detail`; Get text `#o-number` -> `$order_no`. | Capture Click; wait; Capture Get Text. | Equal. | Even |
| 7 | Wait until status is Ready | Assign status = ""; Loop While `$status` does not equal "Ready", Max iterations 60: Delay 250 ms; Get text `#o-status` -> `$status`. 4 steps. | For n times (60): Delay; Capture Get Text; If equals "Ready" -> Break. | Same shape. We have a "Wait for condition" step (timeout, poll interval) but no browser condition "element text is ...", so it cannot be used here. | Even |
| 8 | Download the invoice | Browser / Download: Selector `#invoice`, Folder `${folder}/output/invoices`, Timeout 15000; out path -> `$invoice_path`. 3 fields. | Browser / Download file needs a URL; the link here is a blob created by the page, so it does not work. Native fallback: Capture Click on the link, then wait in a loop for a new file in Chrome's download folder and find its name. The AA bot fetched the text with JavaScript instead. | Ours clicks, waits for the download and returns the saved path in one step. | Ours |
| 9 | Read the invoice total | File / Read text -> `$invoice_text`; String / Find (Find `Total:\s*[0-9.]+`, Regex true) -> match; String / Replace (Find `^Total:\s*`, Regex) -> `$total_text`; Assign total = `{"expr": "toNumber($total_text)"}`. 4 steps, 2 regexes. | Text file / Get text; String / Replace (regex `[\s\S]*Total:\s*`); String / Trim; String / To number. 4 steps. | Ours has String / Extract text (After "Total:", trim) or Find with a capture group (groups output) that would make this 2 steps without the second regex. | Even (as built) |
| 10 | Check against expected | If `$total` equals `$o.expected_total` -> Assign verified + 1; Else -> Log (Message `Invoice ${order_no}: total ${total} differs ...`, Level warning). | String / To number on the expected value; Number condition EQ -> Increment; Else -> Log to file (path + text). | Ours: JSON numbers compare directly; Log goes to the run log without a file path. | Ours |
| 11 | Totals, back to list | Assign grand_total (expr), created (expr); Click `#back-to-orders`. | Number / Assign with expression, Increment; Capture Click. | As before. | Even |
| 12 | Read the order list and count Ready | Extract table `#orders` -> `$order_list`; Data table / Filter (Table, Column Status, Operator eq, Value "Ready") -> count `$ready_orders`. | Capture Table Get table; loop + String condition + Increment (or Get number of rows after a filter, which AA's Data Table package does not do in one step). | Ours: one Filter step gives the count. | Ours |
| 13 | Close, write CSV | Browser / Close; CSV / Write `$order_list`. | Browser / Close; Log to file. | — | Ours |

#### Notes for W10
- Loop "Each item in a list" is nested (orders, then `$o.lines`), the only case here that loops over JSON structures. The inner loop needs a one-based counter `k` maintained by hand because the loop's Index is zero-based and CSS `nth-child` is one-based.
- `type_text` uses `${line.qty}` (interpolation) to turn a number into text, while every other case uses `$var`; a developer will not know when the braces are needed.
- While loop here compares a variable (`$status` != "Ready") rather than an element condition (W6, W9).
- Confusing: two-step JSON read (Read text + Parse) when "Read JSON file" exists; two regexes where "Extract text" would do.

---

### Findings across these cases

#### Problems in our commands / UI (actionable)

1. **Assign variable: arithmetic and comparisons need `{"expr": ...}` typed as JSON** (P1)
   - Evidence: counters in every case (W4 s11, W5 s11, W6 s25, W7 s27/s31, W8 s43/s44, W9 s18/s21, W10 s25/s28/s29), W4 s9 `$i + 2`, W8 s37 `$best != $previous`, W9 s30 three-part comparison. Typing `$i + 2` in the Value box silently stores the text "$i + 2" (Expressions.resolve only evaluates a `{"expr"}` map).
   - Fix: give Assign a "Value / Calculate" switch (or treat a leading `=` as an expression), show the result type, and warn when a Number/Boolean variable receives text containing operators. Offer the condition builder as a "Set to true/false when ..." mode for Boolean variables.

2. **Table actions return a copy; the output must be mapped back or the change is lost** (P1)
   - Evidence: Add row in W5 s6/s8, W6 s24, W7 s26/s30, W8 s42; Change column type W9 s4; Set value W9 s15. The Outputs dropdown defaults to "(do not store)". `DataTablePackage.addRow` builds a new table.
   - Fix: default the `table` output to the same variable as the `table` input (pre-filled when the step is added), or make these actions update the variable in place. Show "updates $differences" in the step summary.

3. **No one-step lookup in a table** (P2)
   - Evidence: W5 s5/s10/s7, W8 s33-s36, W9 s11-s14 and s31-s32: Find, set default, If found, Get value (3-4 steps every time).
   - Fix: add Data table "Look up value": table, match column, value, return column, value if not found -> one output. (AA needs a Dictionary build loop, so this would make us clearly simpler.)

4. **Element conditions in the condition editor show raw field names and no Capture/Pick** (P1)
   - Evidence: W6 s26, W9 s25 (While element exists), W7 s10/s18 (If element visible), W8 s12/s17/s23. `conditionEditor` prints `object`, `selector`, `session`, `window` as plain text inputs with the internal names.
   - Fix: render package-condition inputs with `inputControl` (labels, Capture/Pick button, window editor); hide `session` and `object` behind More options.

5. **Element conditions have no "wait up to" time, so fixed delays are added before them** (P2)
   - Evidence: W7 s7/s15 (Delay 300 ms before "Element visible #error"), W9 s24.
   - Fix: add an optional "Wait up to (seconds)" to Element exists/visible (AA's Object exists has one), and document the "either A or B" Wait for (comma selector) as a first-class option: "Wait for any of these elements" with a list, returning which one appeared.

6. **No browser condition on element text, so "Wait for condition" cannot replace polling loops** (P2)
   - Evidence: W10 s16-s19 (Assign + While + Delay + Get text to wait for status "Ready").
   - Fix: add condition "Element text is / contains" (selector or captured object, text, match). Then W10's 4 steps become one Wait for condition step with timeout and poll interval.

7. **Loop "As" and "Index" are free text and need pre-declared variables; Index is zero-based** (P2)
   - Evidence: W4 (person, i declared by hand; `row_no = i + 2`), W10 (manual one-based counter `k` because Index is zero-based), all loops.
   - Fix: make As/Index a variable picker with "+ New" that creates the right type (Dictionary for table rows, Number for index). Add "Counter starts at 1" option or a second output "position" (1-based).

8. **Studio output dropdown does not offer Number variables for text outputs** (P2)
   - Evidence: W6 s27-s29 and W9 s27-s29 store Get text into Number variables; the runtime accepts it, but `outputOptions` marks them "does not match String", so the same agent cannot be built in Studio without extra conversion steps.
   - Fix: allow String -> Number/Boolean outputs when the text converts, with a small "converted to number" note; fail at run time with a clear message when it does not.

9. **Delay field is labelled "Seconds" even when Unit = milliseconds** (P3)
   - Evidence: W7 s7/s15, W9 s24, W10 s17 (`"seconds": 300, "unit": "milliseconds"`).
   - Fix: label it "Wait" / "Duration" and put the unit next to it.

10. **Browser Open defaults do not suit unattended/test runs** (P3)
    - Evidence: every case sets `engine: playwright` and `keep_open: false` explicitly (W4 s3 ... W10 s3). Default keep_open = true leaves windows open after a run.
    - Fix: keep the defaults for "attach to my browser" design work, but offer a single "Run mode: watch / unattended" choice that sets engine, headless and keep_open together; hide the three under More options.

11. **Element action lists all 50 actions when no object is captured** (P3)
    - Evidence: W7 s22 (`action: check` on `#terms` by selector).
    - Fix: when a selector is used, infer the control type from the page at Pick time, or group the list (Click / Type / Read / Check / Select / Table / Other) with the common ones first.

12. **Throw requires a Code with no default** (P3)
    - Evidence: W7 s9/s17 (`BUSINESS.REFUSED`).
    - Fix: default the code to `BUSINESS.ERROR` and offer the catch-side families (`BUSINESS.*`) as a dropdown; keep Message as the only required field.

13. **Report-writing defaults are wrong for the common path** (P3)
    - Evidence: W7 s36 Create workbook needs Overwrite true or the second run fails; W7 s38 Close needs Save true; W4 s16/s17 Save with path then Close Save false, while a separate Save as action also exists.
    - Fix: Create workbook: overwrite on by default (or ask "if the file exists: replace / fail"). Close: "Save changes" default true when the workbook was created by the agent. Merge Save-with-path and Save as, or make Save's path field "Save as (optional)".

14. **Samples do not use the simpler actions we already have** (P2, documentation/templates)
    - Evidence: W10 s1-s2 (Read text + Parse instead of JSON / Read JSON file), W10 s22-s24 (Find + Replace regex + expr instead of String / Extract text after "Total:" or Find's `groups` output), W8 s25-s27 and W6 s13/W9 s10 (Replace + `toNumber` expr instead of String / Text to number with a locale), counters via Assign expr instead of Number / Increment.
    - Fix: update the samples, and in Studio suggest the dedicated action when someone types `toNumber(` or `$x + 1` in Assign.

15. **Canvas summary of While/N-times loops is ungrammatical** (P3)
    - Evidence: W6 s26, W9 s25, W10 s19 render as "Repeat for while ..." (`summary` prefixes "Repeat for " to `loopText`, which already starts with "while" or "N times").
    - Fix: "Repeat while ...", "Repeat 3 times", "Repeat for each row in ...".

16. **Selectors for "first row", "n-th line", "either A or B" need CSS knowledge** (P2)
    - Evidence: W6 s7 `tr:first-child button.open`, W8 s10/s11/s15/s21 comma selectors and `nth-child`, W10 s10/s11 `nth-child(${k})`.
    - Fix: add table-aware element actions on extracted/captured tables (Click cell / Get cell text by row and column, as AA's captured Table offers), an "occurrence / index" field on element actions (1 = first match) so Pick + index replaces nth-child, and the "any of these elements" wait from finding 5.

#### Where ours is clearly better than AA (keep)
- **Reading data**: CSV / Read and CSV / Write are one step each with good defaults (AA: Open/Read/Close with sessions, or 7-field Write to file). Detect types gives numbers straight from the file (W8).
- **JSON**: nested JSON parsed into lists and dictionaries and looped directly (W10); AA's Text file command does not read .json and its JSON package does not handle nested arrays well.
- **Browser Download** clicks, waits and returns the saved path in one step, even for page-generated (blob) links (W10); AA's Download file needs a real URL.
- **Extract table** from the page in one step with header names (W5, W9, W10); Data table Filter returns a count in one step (W10).
- **If / Else-if** in one block with readable operators ("is empty", "is greater than"), and numbers interpolate into text without conversion (W6 rules, W9 sentences); AA needs Number:toString everywhere.
- **Catch by error code family** (`BUSINESS.*`) so business refusals are handled separately from technical errors (W7); AA catches by exception type only.
- **Wait for** with states visible / hidden and OR selectors (W6, W8, W9) instead of AA's loop-and-break patterns for "either" conditions.
- **Loop over a List variable** (W8 shops) and **While with an element condition** (W6, W9) in one step.
- **Variables in selectors** are plain text (`#${decision}`), where AA requires editing captured object properties.
- **No session/Finally bookkeeping** needed for browser and Excel cleanup; AA bots need Finally blocks to avoid leaving Chrome open.

## Command-by-command review C: desktop cases D1-D5 and office cases O1-O5

Sources: our agents in `examples/use-cases/<id>-*.flow.json`, our action catalogue (`packages.json`), Studio UI (`studio.js`: `renderProps`, `inputControl`, `conditionEditor`, `elementActionFields`, `captureEditor`, `summary`/`objectLabel`), and the AA bots plus AA's command definitions read from the Control Room (read only).

Field counts below mean the fields the developer actually had to fill in or change for that step. "Main" / "More options" refers to how Studio's property panel splits inputs: required inputs, inputs without a default, and the first three inputs stay in the main list; the rest go under "More options".

General shape of the AA bots (applies to every case, not repeated per table): each AA bot starts with a Comment, wraps the work in Try/Catch, and writes its results with "Log text to file" (one log line per output), because the bots report results through files. Ours declares output variables (`saved`, `open_count`, ...) that the run returns directly, so none of those log steps exist in our agents.

---

### D1 Enter orders in a desktop application

| # | Step purpose | Ours: command + how it is configured here | AA: command + how it is configured | Difference (UI/feature/usage) | Simpler in |
|---|---|---|---|---|---|
| 1 | Start the order-entry app | **Application > Start program**: Program `powershell.exe`; Arguments as a JSON **list** of 6 items (`-NoProfile`, `-ExecutionPolicy`, `Bypass`, `-File`, full path built with `${sys.workspace}/${folder}/...`, `-Out`, path); Window state `normal` (the default, set needlessly); output `process_id` -> `app_pid`. 3 fields, one of them JSON. | **Application > Open program/file**: full path to powershell.exe, Start-in path, Parameters as **one text line**, Timeout 30. 4 fields, no JSON. The window is then defined once as a window variable (`$App1$`). | Our Arguments field is a "List" input, so Studio shows a JSON textarea; AA takes the command line as typed. We return a process id that later steps can use to stop the program; AA returns nothing but binds the window. | AA (arguments entry); ours for the process id |
| 2 | Wait for the main window | **Window > Wait for window**: Title `OOA Order Entry`, Timeout 30. 2 fields. | **Wait > Wait for window**: Open; Window `$App1$`; 30 s; Throw exception = yes. 4 fields. | Same idea. AA picks the window from a variable; ours retypes the title. | Ours (fewer fields) |
| 3 | Make sure the app closes even on error | **Try** with a **Finally** section (no Catch). Finally holds **Application > Stop program** with Process id `$app_pid`, Force `true`. Stop program does not fail if the process has already gone. | **Try / Catch / Finally**; Catch logs the error to a file; Finally holds a nested **Try { Window: Close $App1$ } Catch { comment "already closed" }** because closing an already-closed window throws. | Ours needs no nested Try and no Catch. AA closes by window, ours stops by process id. | Ours |
| 4 | Read the orders file | **CSV > Read to table**: Path `${folder}/data/desktop-orders.csv` (relative to the workspace); output `table` -> `orders`. 1 field. | **CSV/TXT: Open** (session name, full file URL, Contains header, Delimiter comma, Encoding UTF-8) + **CSV/TXT: Read** (session -> `$tO$`) + **CSV/TXT: Close** (session). 3 steps, ~7 fields. | AA needs a session open/read/close; ours is one step. | Ours |
| 5 | Loop over the orders | **Loop**: iterator "Each row in a table", Over `$orders`, As `o` (a Dictionary). | **Loop > Data Table: For each row in table** -> `$rO$`. | Ours addresses cells by column name (`$o.Customer`); AA by position (`$rO[0]$`, `$rO[1]$`...), which breaks silently if columns move. | Ours |
| 6-7 | Type customer and amount | **Desktop object action** x2: Captured object = window title `OOA Order Entry` (match **equals**) + property `AutomationId = txtCustomer` / `txtAmount`; Action **Set text**; Text `$o.Customer` / `${o.Amount}` (via the Credential / Variable / Plain text switch). 3 fields each. | **Recorder > Capture** x2: object TEXTBOX found by **ID** `txtCustomer` / `txtAmount`; action **Set text**, input type "value", delay 0, run in background no, wait 15 s. ~5 fields each. | Equivalent. Our flow mixes `$o.Customer` and `${o.Amount}` for the same kind of value (two syntaxes: the second turns the value into text). | Ours (fewer fields) |
| 8 | Choose the order kind | **Desktop object action**: object `AutomationId = cboKind`; Action **Select item**; Item `$o.Kind`. | **Recorder > Capture**: COMBOBOX by ID; action "Select item by text"; value `$rO[2]$`. | Same. AA offers "by text / by index / by key" as separate actions; ours has Item or Item index fields. | Equal |
| 9 | Tick "urgent" when the row says yes | **If**: condition builder row "Compare two values": `$o.Urgent` equals `yes`; inside, **Desktop object action** `chkUrgent`, Action **Check**. | **If** String condition `$rO[3]$` = "yes"; inside **Recorder Capture** CHECKBOX by ID, action Check. | Same. | Equal |
| 10 | Click Save | **Desktop object action** `btnSave`, Action **Click**, Click method `pattern` (UI Automation press, no mouse). | **Recorder Capture** BUTTON by ID, action Click; then **Delay 300 ms** before reading the status. | AA needed a fixed delay so the status label had time to change; ours has none. | Ours |
| 11 | Read the saved order number | **Desktop object action** `lblStatus`, Action **Get text**; output `result` -> `status_text`. | **Recorder Capture** LABEL by ID, action **Get property**, property name `Name` -> `$sStatus$`. | Ours has one "Get text" that works for labels and text boxes; AA needs Get property with the right property name (Name for a label, Value for a text box). | Ours |
| 12 | Count saved orders | **Assign variable**: Variable `saved`, Value `{"expr": "$saved + 1"}` (must be typed as JSON in the Value textarea). Then **Assign** `last_status` = `$status_text`. | **Number: Increment** `$nSaved$` by 1 (2 fields). | We have no Increment command; counting needs an `{"expr": ...}` JSON object. AA's Increment is self-explanatory. | AA |
| 13 | Report the result | Output variables `saved`, `last_status`. | **Log text to file** (path, text with `$nSaved.Number:toString$`, overwrite). Catch also logs the error. | AA must convert numbers to text (`.Number:toString`) inside the log text. | Ours |

#### Notes for D1
- Desktop objects here are found by **AutomationId only** (AA: by "ID"). The window inside each object uses title match **equals**, while the separate Window commands (steps 2, D3) match **"text contained in the title, case-insensitive"**. The same window title is therefore matched by two different rules in one agent.
- The window title `OOA Order Entry` is repeated in 7 places (wait + 6 objects). AA defines the window once (`$App1$`) and every Capture uses it.
- On the canvas every one of the six desktop steps reads the same: `objectLabel()` builds the label from `ControlType` and `Name`, which these objects do not carry, so each card shows "control   action: set_text ...". The AutomationId that tells them apart is not shown. The same missing ControlType means the Action drop-down cannot put "actions for this control type" first.
- `Set text` with text method `auto` and `Click` with method `pattern` work without the mouse; AA's equivalents are the "run in background" ticks.
- Confusing in our Studio: the counter (`{"expr": ...}`), the Arguments JSON list, and the two variable syntaxes `$x` vs `${x}`.

---

### D2 Read a desktop grid into a table and keep the open orders

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1-2 | Start app with `-Demo`, wait for window | Same as D1 steps 1-2 (Arguments list gets a 7th item `-Demo`). | Same as D1 (Open program/file + Wait for window). | As D1. | As D1 |
| 3 | Always stop the app | Try + Finally + **Stop program** (as D1). | Try/Catch/Finally with nested Try around **Window: Close** (as D1). | As D1. | Ours |
| 4 | Read the grid | **Desktop UI > Get desktop table**: object `AutomationId = gridOrders`; outputs `table` -> `grid`, `row_count` -> `total_rows`. 1 field + 2 outputs. | **Recorder Capture** TABLE by ID, action **Get table** -> `$tGrid$`. | AA's table comes back with the **header row as the first data row** and no column names. Ours has real column names and a row count. | Ours |
| 5 | Keep rows with Status = Open | **Data table > Filter**: Table `$grid`, Column `Status`, Operator `eq`, Value `Open`; outputs `table` -> `open_orders`, `count` -> `open_count`. 4 fields. | **Log text to file** (write a CSV header line by hand) + **Loop** over rows + **If** `$rG[1]$ != "Id"` (skip the header row) + **Number: Increment** rows + **If** `$rG[5]$ = "Open"` + **Increment** open + **Log text to file** append `$rG[1]$,$rG[2]$,...` | AA has no filter command in this bot; it loops, skips the header row by testing a cell, counts by hand, and builds the CSV line by line. | Ours by far |
| 6 | Save as CSV | **CSV > Write from table**: Table `$open_orders`, Path `${folder}/output/open-orders.csv`. 2 fields. | (done by the log lines above) + final **Log text to file** with the counts. | AA's hand-built CSV does not quote values containing commas. | Ours |

#### Notes for D2
- Same desktop command family as D1, but here the action is a dedicated command (**Get desktop table**) rather than `object_action` with action `get_table` (which also exists). Two ways to do the same thing; the dedicated one returns `row_count`, the action does not.
- Filter's Operator drop-down shows the raw codes `eq, ne, gt, ge, lt, le, ...` (`inputControl` prints enum values as they are), while the If condition builder shows "equals", "is less than". Same concept, two vocabularies.

---

### D3 Window handling (place, size, minimise, restore, read details, close)

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1-2 | Start app, wait for window | As D1. | As D1. | As D1. | As D1 |
| 3 | Always stop the app | Try + Finally + Stop program. | Try/Catch/Finally + nested Try around Window: Close. | As D1. | Ours |
| 4 | Place and size the window | **Window > Move and resize window**: Title, X 80, Y 60, Width 700, Height 560. 5 fields; empty values keep the current ones. | **Window: Resize**: Window `$App1$`, Left, Top, Width, Height. 5 fields. | Same. | Equal |
| 5 | Minimise then restore | **Minimise window** (Title) + **Restore window** (Title). | **Window: Minimize** + **Delay 500 ms** + **Window: Restore** + **Delay 300 ms**. | AA needed fixed delays between window state changes. | Ours |
| 6 | Read the window details | **Get window details**: Title; output `window` (Dictionary with title, program, class, position, size, state) -> `win`; then two **Assign** steps `width = $win.width`, `height = $win.height`. | **Window: Activate** + **Get active window title** -> `$sTitle$`. AA has no command that returns size or position. | Ours returns much more, but needs two extra Assign steps to lift the numbers out of the Dictionary. | Ours (feature); could be shorter |
| 7 | Close and confirm it closed | **Close window**: Title, Wait closed seconds 10; output `closed` (Boolean). 2 fields. | **Window: Close** + **If** condition "Window does not exist" (window, wait 10 s) + **String: Assign** "yes" / **Else** **String: Assign** "no". 5 steps. | Ours tells you in one step whether the window really went away. | Ours |

#### Notes for D3
- Here the window is targeted by **title text** only, through the Window toolkit (contains, case-insensitive). In D1/D2/D4/D5 the same window is targeted through each desktop object's own window block (equals). The window commands also accept `alternatives` (title pattern, class name, process name) under More options, but **not the process id** that Start program returned, even though that is the most reliable link.
- Stop program in Finally runs after the window was already closed by step 7; it does not fail because Stop program counts stopped processes instead of throwing. AA had to wrap its Close in a nested Try for the same situation.
- The `Wait closed seconds` label is a direct field-name conversion; "Wait until closed (seconds)" would read better.

---

### D4 Hand text over through the clipboard

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1-3 | Start app, wait, Try/Finally stop | As D1. | As D1. | As D1. | Ours |
| 4 | Read the note file | **File > Read text file**: Path `${folder}/data/note.txt`; output `text` -> `note`. 1 field. | **Text file: Get text**: file URL, encoding "detect automatically" -> `$sNote$`. 2 fields. | Same. AA can detect the encoding; ours defaults to UTF-8. | Equal |
| 5 | Put it on the clipboard | **Desktop > Set clipboard**: Text `$note`. | **Clipboard: Copy to**: Value `$sNote$`. | Same. | Equal |
| 6 | Read the clipboard back | **Desktop > Get clipboard**: Wait seconds 2; output -> `clip`. | **Clipboard: Copy from** -> `$sClip$`. | Ours can wait until text arrives (useful after Ctrl+C). | Ours (feature) |
| 7 | Paste into the Notes field | **Desktop object action** `AutomationId = txtNotes`, **Set text** `$clip`. | **Recorder Capture** TEXTBOX by ID, Set text `$sClip$`. | Same. | Equal |
| 8 | Read the field back | **Desktop object action** `txtNotes`, **Get text** -> `back`. | **Recorder Capture** TEXTBOX, **Get property `Value`** -> `$sBack$`. | AA needs to know the property name (`Value` here, `Name` for a label in D1). Ours uses Get text for both. | Ours |
| 9 | Compare | **Assign** `same` = `{"expr": "$back == $note"}` (Boolean). 1 step, JSON expression. | **If** String condition equals + **String: Assign** "identical" / **Else** **Assign** "DIFFERENT". 4 steps. | Ours is one step but only if you know the `{"expr": ...}` form; AA is longer but all point-and-click. | Ours (shorter), AA (no typing of syntax) |

#### Notes for D4
- "Set text" here enters text through UI Automation (value pattern), so the clipboard is not actually used to paste. If the point were to paste (Ctrl+V), our command would be `set_text` with Text method `type`, or a key-press step; the flow does not test a real paste, and neither does AA's.
- Same object found by ID twice (set and get); in Studio the developer has to capture or copy the object twice because objects are stored inside each step, not in a shared object list (AA also stores per step).

---

### D5 Read and close a validation pop-up

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1-3 | Start app, wait, Try/Finally stop | As D1. | As D1. | As D1. | Ours |
| 4-5 | Enter customer and a wrong amount | **Desktop object action** `txtCustomer` Set text `Wayne Corp` (plain text); `txtAmount` Set text `abc`. | **Recorder Capture** TEXTBOX x2, Set text. | Same. | Equal |
| 6 | Click Save | **Desktop object action** `btnSave`, Click (pattern). | **Recorder Capture** BUTTON Click. | Same. | Equal |
| 7 | Wait for the pop-up | **Window > Wait for window**: Title `Validation`, Timeout 10. | **Wait for window**: Open, Window `$App2$` (a second window variable), 10 s, throw = yes. | AA needs a second window variable defined for the dialog; ours types the title. | Ours |
| 8 | Read the message | **Desktop object action** with window title `Validation` (equals) + `AutomationId = lblMessage`, **Get text** -> `popup_text`. | **Recorder Capture** LABEL by ID, Get property `Name`. | Here our object's window block points at a **different window** (the dialog), not the main form. | Ours |
| 9 | Close the pop-up | **Desktop object action** `btnOk` in window `Validation`, Click. | **Recorder Capture** BUTTON Click. | Same. | Equal |
| 10-12 | Correct the amount, save, read status | **Set text** `99.90`; **Click** `btnSave`; **Get text** `lblStatus` -> `final_status`. | **Set text**; **Click**; **Delay 300 ms**; **Get property Name**. | AA again needs a fixed delay. | Ours |

#### Notes for D5
- The only case where objects belong to **two windows**. In ours each object carries its window title, so the developer must edit the window block of the captured object (Studio's capture fills it from the window under the pointer, which is right if captured live). In AA the dialog needs its own window variable.
- No "If the pop-up appears" branch: both agents assume it always appears. Ours could do this with Wait for window `fail_on_timeout = false` and the `met` output; AA with "Throw exception = no". Ours keeps that option on the main list, which is good.

---

### O1 Overdue invoices per customer to Excel

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Read invoices | **CSV > Read to table**: Path; **Detect types `true`** (under More options) so Amount becomes a number and DueDate a date; output -> `invoices`. | **CSV/TXT Open / Read / Close** (session, path, header, delimiter, encoding). 3 steps. Everything stays text. | AA has no type detection here, which forces the conversions below. | Ours |
| 2 | Keep unpaid | **Data table > Filter**: Column `Status`, Operator `ne`, Value `Paid` -> `unpaid`. | Inside one big loop: **If** String condition `$rI[4]$ != "Paid"`. | | Ours |
| 3 | Keep overdue (due date before report date) | **Filter**: Column `DueDate`, Operator `lt`, Value `$report_date` -> `overdue`. | **String: Replace** "-" with "" on the report date + **String: To number**; per row the same two steps on the due date; **If** Number condition less than. | AA turns dates into numbers (20261023) to compare them. | Ours by far |
| 4 | Total and count per customer | **Data table > Aggregate**: Table `$overdue`, Group by `["Customer"]` (JSON list), Summaries `{"Overdue": "sum:Amount", "Invoices": "count"}` (JSON object with a `function:column` mini-language); outputs -> `summary`, `customers`. 1 step, 2 JSON fields. | Per row: **String: To number** amount, **Number: Assign** 0 twice, **If Dictionary: Check key** then **Dictionary: Get** sum and count, **Number: Assign** sum + amount, **Number: Increment**, **Dictionary: Put** x2. About 11 steps inside the loop, two Dictionaries. | AA has no group-by; grouping is hand-built with Dictionaries. Ours is one step but the developer has to know the JSON shape and the `sum:` syntax. | Ours (much shorter), but ours needs JSON |
| 5 | Sort by overdue amount | **Data table > Sort**: Table `$summary`, Column `Overdue`, Descending `true`; output written back to `summary`. | **Log text to file** header + **Loop over Dictionary keys** writing tab-separated lines (Dictionary Get x2, Number To string) + **CSV/TXT Open/Read/Close** the file back as a table + **Data Table: Change column type** to Number + **Data Table: Sort** descending. | AA has to round-trip through a text file to get a table it can sort. | Ours |
| 6 | Write the Excel report | **Excel > Create workbook**: Path, Sheet `Overdue`, Overwrite `true`, Session `report`; **Write from Data Table**: Table, Session `report`; **Close**: Session `report`, **Save `true`**. 3 steps, session typed 3 times. | Write CSV lines with **Log text to file** in a loop (Number Assign, Assign total, Increment, To string, Log append) + **Excel advanced: Open** the CSV + **Save as** .xlsx (overwrite) + **Close** (save). | AA's Excel advanced has no "write data table" used here, so it opens a CSV and saves it as xlsx. Ours writes the table directly. Our Close defaults to **not saving**; forgetting `Save = true` loses the report. | Ours |
| 7 | Total overdue | **Data table > Sum column**: Column `Overdue` -> `total_overdue`. | Running total with **Number: Assign** `$nTotal$ + $nSum$` in the write loop + **Number: To string** (2 decimals) + Log. | | Ours |

#### Notes for O1
- Same Filter command as D2 but here comparing **dates** (`lt` against a text input `report_date`). It works because Detect types was ticked; that option is under **More options**, and nothing in the Filter step hints that the comparison depends on it.
- Sort and the other table commands return a **new** table; the developer must map the output back to the same variable (`summary` -> `summary`). If the output is left at "(do not store)" the sort silently has no effect.
- The Session field is under More options (advanced) on all three Excel steps, which is good, but this agent used a custom name `report` where the default `excel` would have done; three places to keep in sync.
- AA needed about 45 steps; ours needs 9.

---

### O2 Invoice emails: save attachments and reply (built, not run)

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Make sure the target folder exists | (not needed; Save attachments writes into the target folder) | **Try { Folder: Create } Catch { comment "folder exists" }**. | AA needs a Try just to ignore "already exists". | Ours |
| 2 | Connect to the mailbox | **Email > Connect to mailbox**: Imap host, Port, User, Password (Credential variable), Security, **Smtp host, Smtp port, Smtp security** (kept in the session for replies). 8 fields, all from input variables. | **Email: Connect**: session `mail`, Email server, SSL yes, host, port, IMAP, Authentication Basic, username, password (credential), timeout 120. ~9 fields. | Similar effort. Ours also keeps the outgoing server, so replies need no sign-in of their own. Labels "Imap host" / "Smtp host" read like field names. | Equal |
| 3 | Find unread invoice mails with attachments | **Find emails**: Unread only `true`, Subject contains `Invoice`, Has attachments only `true` -> table `mails`; then **Loop** "each row in a table" as `m`. 2 steps. | **Loop** with iterator **Email: For each mail in mail box** (session, Unread, keep status no, oldest first, folder Inbox, subject Invoice, plain text, local timezone) -> `$dMail$`. 1 step. | AA's iterator does it in one step; ours lists then loops (but the list is a table you can count or filter). AA has no "has attachments" filter. | AA (one step) |
| 4 | Save attachments | **Save attachments**: Id `$m.id`, Target folder `${folder}/output/invoices`; output `files` (List). | **Email: Save attachments**: folder, overwrite. | AA saves the attachments of the current mail of the loop implicitly; ours needs the mail id. | AA (fewer fields) |
| 5 | Count attachments | **Assign** `attachments` = `{"expr": "$attachments + len($files)"}`. | (not counted) | Ours needs an expression with a function call in JSON. | - |
| 6 | Reply | **Reply to email**: Id `$m.id`, Body `Thank you, we received ${m.subject}.` 2 fields; the outgoing server comes from Connect. | **Email: Reply (Deprecated)**: plain text body, Go Green flag, Email server, SMTP host, port, secure yes, requires authentication yes, username, password. ~9 fields, repeated sign-in. | AA's IMAP reply is a deprecated command and needs the SMTP sign-in again in every reply. Ours does not. But in Studio our Reply form still lists Smtp host, User, Password, Access token, From, Cc, Bcc, Reply to in the **main** area (12 visible fields), so it looks as if they are needed. | Ours (fewer fields), but cluttered form |
| 7 | Count processed | **Assign** `processed` = `{"expr": "$processed + 1"}`. | **Number: Increment**. | No Increment in ours. | AA |
| 8 | Disconnect | **Disconnect** (session default). | **Email: Disconnect** session `mail`. | Same. | Equal |

#### Notes for O2
- Same Loop "each row in a table" as D1/O5, here over an email list. The mail is addressed by `$m.id` in every later step, whereas AA's loop gives "current email" context.
- Password is a **Credential** input variable in ours; AA uses credential variables from its Credential Vault. Comparable.
- Confusing in Studio: Connect shows "Imap host" even when the protocol is POP3; Reply shows SMTP sign-in fields although Connect already holds them.

---

### O3 Letters from PDF invoices with a Word template

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Make the letters folder | (not needed; Create document makes the file) | **Try { Folder: Create } Catch { comment }** + a Log line to start the result file. | | Ours |
| 2 | Loop over invoices | **Loop** "each item in a list" over input `$invoices` (`["1001","1002"]`) as `inv`. | **Loop For n times** (2) + **Number: Assign** `1000 + $nI$` + **Number: To string** (0 digits). | Ours loops over the actual invoice numbers; AA computes them. | Ours |
| 3 | Read the PDF text | **PDF > Extract text**: Path `${folder}/data/invoice-${inv}.pdf`; output `text`. 1 field. | **PDF: Extract text**: path, Plain text, All pages, **export to a .txt file**, overwrite -> (returns PDF properties only) + **Text file: Get text** of that .txt file. 2 steps. | AA's PDF command does not return the text; it must go through a text file. | Ours |
| 4 | Pull out the total | **String > Find**: Text `$text`, Find `Total:\s*[0-9.,]+`, Regex `true` -> `match`; **String > Replace**: Find `^Total:\s*`, Replace with empty, Regex `true` -> `total`. 2 steps, 2 regexes. | **String: Replace** `[\s\S]*?Total:\s*` with empty (regex) + **String: Replace** `[\r\n][\s\S]*` with empty + **String: Trim**. 3 steps. | Both need regular expressions. Ours could not use **Extract text** (text after `Total:` and before a marker) because there is no way to say "until the end of the line". Find returns the whole match, not a capture group, so a second step strips the label. | Ours (slightly) |
| 5 | Pull out the customer | Same pair: Find `Customer:\s*[^\r\n]+` + Replace `^Customer:\s*`. | Same three steps. | As above. | Ours (slightly) |
| 6 | New letter from the template | **Word > Create document**: Path `.../letters/letter-${inv}.docx`, Template `.../letter-template.docx`, Overwrite `true`, Session `letter`. 4 fields. | **Word: Open** the template read-only -> session; at the end **Word: Save as** new path, overwrite + **Close** without saving. | Ours creates the new file from the template in one step; AA opens the template and must remember to Save as and not save over it. | Ours |
| 7 | Fill placeholders | **Word > Replace text** x3: Find `{{customer}}` / `{{invoice}}` / `{{total}}`, Replace with the variable, Session `letter`. | **Word: Find and replace text** x3: session, find text, range Full document, replace yes, replacement, occurrence -> count. | One step per placeholder in both. AA asks for range and occurrence each time. | Ours (fewer fields) |
| 8 | Read the letter back | **Word > Read text** (Session) -> `letter_text`. | **Word: Read text** (session, range Full document). | Same. | Equal |
| 9 | Save and close | **Word > Close**: Session `letter`, **Save `true`**. | **Word: Save as** + **Word: Close** (save no). | Our Close defaults to not saving; with Create document that default throws away the filled letter unless the box is set. | Ours (1 step), but risky default |
| 10 | Count | **Assign** `letters` = `{"expr": "$letters + 1"}`. | **Number: Increment** + **Log text to file** per letter. | As before. | AA |

#### Notes for O3
- String Find is used here with **regex** and returns the match; in other cases (none in this group) it is used for plain text positions. Its main output is `match`, but there is no option to return a capture group, which is what the developer actually wants.
- The Word session name `letter` is typed in 6 steps; the default `word` would have worked.
- Confusing in Studio: Word Replace text shows "From paragraph / To paragraph" in the main area (no defaults, so not moved to More options) though they are rarely needed.

---

### O4 REST API users into a database

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Call the API | **REST > Request**: Url `${api_url}/users`, Method `GET`; output `json` -> `users` (a List of records, parsed). 2 fields. | **REST: Get method**: URI, proxy System, No authentication, timeout 60000 ms -> response Dictionary; **Dictionary: Get** key `Body` -> text; **JSON: Start session** with that text; **JSON: Convert JSON to Table** -> table. 4 steps. | Ours parses JSON automatically; AA needs a JSON session and a conversion to a table, and the table loses nested fields. | Ours |
| 2 | Open the database | **Database > Connect**: Jdbc url `$jdbc_url` (an H2 URL), User `sa`. 2 fields, the developer must know JDBC URL syntax. | **Database: Connect**: User defined, SQLite, database file path -> session. AA builds the connection from a type list. | AA's "pick the database type, then fill server/file" is friendlier than typing a JDBC URL. | AA (connect UI) |
| 3 | Create and empty the table | **Database > Insert/update/delete** x2: SQL `CREATE TABLE IF NOT EXISTS ...`, `DELETE FROM api_users`. | **Database: Insert/Update/Delete** x2 (statement, timeout 60, session). | Same. | Equal |
| 4 | Insert each user | **Loop** "each item in a list" over `$users` as `u`; **Insert/update/delete**: SQL `INSERT ... VALUES (?, ?, ?)`, Parameters `["$u.id", "$u.name", "$u.address.city"]` (JSON list). | **Loop** each row of the table + **Number: Assign** id + **Number: To string** + **String: Replace** `'` with `''` (escape name) + **JSON: Get node value** `[$nIdx$].address.city` (nested field via a separate index counter) + **String: Replace** escape city + **Insert/Update/Delete** with values concatenated into the SQL text + **Increment** loaded + **Increment** index. 9 steps per row. | Ours uses parameters (safe, no quoting) and reads nested fields with a path. AA concatenates SQL and escapes quotes by hand, and keeps a second counter to reach nested JSON. | Ours by far |
| 5 | Count loaded | **Assign** `{"expr": "$loaded + 1"}`. | **Number: Increment**. | As before. | AA |
| 6 | Summarise per city | **Database > Read from (query)**: SQL `SELECT city, COUNT(*) AS people ... GROUP BY city ORDER BY ...` -> `by_city`, `row_count` -> `cities`. | **Database: Export to data table** (same SQL) -> table + **Data Table: Get number of rows** + **Data Table: Write to file**. | Ours returns the row count directly. | Ours |
| 7 | Top city | **Data table > Get value**: Row `0`, Column **`CITY`** (upper case, because H2 returns unquoted names in upper case) -> `top_city`. | **Loop** over rows, **String: Assign** first cell, **Loop: Break**. | Ours is one step, but the column name had to be written as `CITY` although the SQL says `city`; a developer will first try `city` and get an error. | Ours (once the name is right) |
| 8 | Close | **Database > Disconnect**. | **Database: Disconnect** + **JSON: End session**. | AA closes two sessions. | Ours |

#### Notes for O4
- Loop here is "each item in a list" over parsed JSON records, versus "each row in a table" in D1/O1/O5. Dotted paths (`$u.address.city`) reach nested data directly.
- Database Execute and Query take Parameters as a JSON list in a textarea; there is no one-row-per-parameter editor.
- Confusing in Studio: label "Jdbc url" and "Sql"; Connect shows Driver class and Properties in the main area (no defaults) though they are rarely needed.

---

### O5 AI ticket triage (built, not run)

| # | Step purpose | Ours | AA | Difference | Simpler in |
|---|---|---|---|---|---|
| 1 | Read tickets | **CSV > Read to table** -> `tickets`. | **CSV/TXT Open / Read / Close**. | As O1. | Ours |
| 2 | Sign in to the AI service | (per step: Provider, Model, Base url, Api key from input variables; a named "Connection" could hold them) | **Generative AI: Authenticate** API key (credential) -> session, once. | AA signs in once; our flow passes 4 settings to every Classify step. Our "Connection" field would remove that but the flow does not use it, and the form shows Provider/Model/Api key/Base url and the AWS fields even when Connection is set. | AA (as built) |
| 3 | Prepare the output table | Table variable `routed` with a default of `{"columns": ["Id","Subject","Team"], "rows": []}` typed as JSON in the variable panel. | **Log text to file** `Id,Subject,Label` header line. | Ours needs a JSON default to give an empty table its columns. | AA (as a hand trick) |
| 4 | Label each ticket | **Loop** each row as `t`; **Generative AI > Classify**: Text `${t.Subject}. ${t.Body}`, Labels `["Billing","Technical","Other"]` (JSON list) + the 4 provider settings -> `label`. | **Generative AI: OpenAI Chat AI**: session, model `gpt-4o-mini` from a list, a hand-written prompt asking for one word, max tokens 5, temperature 0 -> free text; **String: Trim**. | Ours returns exactly one of the given labels (and masks personal data by default). AA returns whatever the model writes, so it needs a prompt, a token limit, and clean-up. | Ours |
| 5 | Route and count | **Add row** `["$t.Id", "$t.Subject", "$label"]` -> back to `routed`; **If** `$label` equals `Billing` / **Else if** equals `Technical` / **Else**, each with an **Assign** `{"expr": "$x + 1"}`. | **If** String condition **Includes** "Billing" / **Else If** Includes "Technical" / **Else**, each with **String: Assign** the clean label + **Number: Increment**; **Log text to file** a CSV line. | AA must test with "Includes" because the answer is free text. Ours compares exactly. Else-if is added from the If's property panel ("+ Else if"), not as a canvas step as in AA. | Ours |
| 6 | Save routed tickets | **CSV > Write from table** `$routed`. | (written line by line above) + final Log with the counts. | | Ours |

#### Notes for O5
- Same If/Else-if/Else as D1's If, but here with branches; in Studio the else-if conditions are edited in an "Else if branches" section of the If step, which is less visible than AA's separate Else If command on the canvas.
- Add row returns a new table, so the output must be mapped back to `routed` every time (same trap as Sort in O1).
- The third column of the table default is named `Team` while its value comes from `$label`; consistent naming between the column list and values is entirely manual because the default is JSON.

---

### Findings across these cases

#### Problems in our commands and Studio (actionable)

| # | Command / UI | Problem | Evidence | Suggested fix | Priority |
|---|---|---|---|---|---|
| 1 | **Assign variable** (no Increment) | Every counter needs `{"expr": "$x + 1"}` typed as JSON in the Value textarea; typing `$x + 1` stores a text value, and `${x + 1}` gives text, not a number. | D1 #12, D2 none, O2 #5/#7, O3 #10, O4 #5, O5 #5 (8 counters); D4 #9 Boolean compare. | Add **Increment / Decrement** command (variable, by = 1). Give Assign's Value a "Value / Expression" switch so expressions are typed plainly without JSON. | P1 |
| 2 | **Excel Close**, **Word Close** | `Save` defaults to **false**, so Create workbook/Create document + write + Close loses the work unless the developer remembers the box. | O1 #6, O3 #9 (both had to set Save = true). | Default `save` to true when the session was created or changed in this run, or ask "Save changes?" with Yes selected; at least make it true for sessions opened by Create. | P1 |
| 3 | Property panel split (main vs More options) | Fields without a default always stay in the main area, so rarely used fields crowd the form while useful ones are hidden. Reply shows 12 fields (SMTP sign-in that Connect already holds); Classify 9 (incl. AWS keys for OpenAI); REST 9 (proxy, client certificate); Database Connect shows Driver class and Properties; CSV Read shows Max rows but hides Detect types. | O2 #6, O5 #4, O4 #1-#2, O1 #1. | Add an explicit `x-ooa-advanced` flag in manifests and use it instead of the positional rule; hide provider-specific fields with `x-ooa-visible-when` (AWS fields only for aws_* providers; SMTP fields of Reply only when no session server). | P1 |
| 4 | Data table commands that return a new table (Sort, Add row, Filter...) | The output must be mapped back to the same variable or the step has no effect; nothing warns. | O1 #5 (`summary` -> `summary`), O5 #5 (`routed` -> `routed`). | When the input is a `$variable`, pre-fill the output with the same variable (as Studio already does for element action outcomes); warn when a Sort/Add row output is not stored. | P1 |
| 5 | Enum drop-downs (Filter operator, etc.) | Show raw codes `eq, ne, lt, not_contains`, while the If builder uses "equals", "is less than". | D2 #5, O1 #2-#3. | Add `x-ooa-enum-labels` (or reuse the OPS labels) so all drop-downs show plain words. | P2 |
| 6 | Field labels generated from names | "Jdbc url", "Sql", "Imap host", "Smtp host", "Api key", "Base url", "Mask pii", "Wait closed seconds", "Type delay ms", "Insecure tls". | O2 #2, O4 #2, O5 #4, D3 #7. | Write real `x-ooa-label`s: "Database address (JDBC URL)", "SQL statement", "Incoming mail server", "Outgoing mail server", "API key", "Endpoint address", "Hide personal data", "Wait until closed (seconds)". | P2 |
| 7 | **Desktop object** step card | Card label uses ControlType and Name only; objects found by AutomationId show "control", so six steps in D1 look identical. The Action list also cannot order by control type. | D1 #6-#11, D4, D5. | Fall back to Automation ID (and ClassName) in `objectLabel`; store ControlType with every captured object even when it is not "used" for matching. | P2 |
| 8 | Window targeting | Window commands match "title contains, case-insensitive"; desktop objects' window block matches "equals". The title is retyped in up to 9 steps; the process id from Start program cannot be used to find the window. | D1 (7 copies), D5 (two windows), D3. | Allow a **window variable** (or "window of process `$app_pid`") in Window commands and in desktop objects; use one default match rule (contains) for both. | P2 |
| 9 | **Start program** Arguments | A List input, so a JSON array of strings is required. | D1-D5 #1 (6-7 items each). | Accept a single command-line text (as AA does) and split it, keeping the list form for advanced use; or a one-item-per-row list editor. | P2 |
| 10 | **Data table aggregate** / list inputs | Group by is a JSON list, Summaries a JSON object with a `function:column` mini-language. | O1 #4. | Row editor: "New column | Function (sum, count, average...) | Column"; Group by as a column multi-select. | P2 |
| 11 | **String Find** / **Extract text** | Find returns the whole regex match, not a group; Extract text cannot stop at the end of a line. Two steps per value plus two regexes. | O3 #4-#5. | Add "Return group" to Find (regex), and an "end of line" choice for Extract text's Before marker. | P2 |
| 12 | Column names in table commands | Get value needs the exact case of the column (`CITY`, not `city`) because the database driver upper-cases names. | O4 #7. | Match column names case-insensitively when there is no exact match (all datatable commands). | P2 |
| 13 | **Database Connect** | Developer must write a JDBC URL by hand. | O4 #2. | Offer a database type list (SQL Server, PostgreSQL, MySQL, Oracle, SQLite, H2, Access) with server / database / file fields that build the URL; keep the raw URL as an advanced option. | P2 |
| 14 | **Generative AI** steps | Provider, model, endpoint and key are set on every step; the Connection field is there but the provider fields still show next to it. | O5 #4. | Make Connection the first field and hide the provider fields when a Connection is chosen (Studio already does this for connector `step.connection`; apply it to GenAI's `connection` input). | P2 |
| 15 | Table variable default | An empty table with columns needs JSON `{"columns": [...], "rows": []}` in the variable panel. | O5 #3. | Column-list editor for Table variables in the Variables panel. | P3 |
| 16 | Two variable syntaxes | `$o.Customer` keeps the type; `${o.Amount}` turns it into text; both appear side by side. | D1 #6-#7. | Show a hint in fields ("$name for a value, ${name} inside text") and normalise `${x}` to `$x` when it is the whole field. | P3 |
| 17 | **Get window details** | Returns only a Dictionary, so width/height need two extra Assign steps. | D3 #6. | Also expose `width`, `height`, `x`, `y`, `state` as separate outputs. | P3 |
| 18 | Session names | Custom session names (`report`, `letter`) retyped in 3-6 steps even though the default works. | O1 #6, O3 #6-#9. | Keep Session in More options (already) and, when only one session of that toolkit is open, pre-fill it; flag mismatched names at validation time. | P3 |
| 19 | **Find emails** + Loop | Two steps where AA has one "for each email" iterator; later steps must pass `$m.id`. | O2 #3-#4. | Offer an "Each email in a mailbox" loop iterator (filters as in Find emails) that also sets the current email, so Save attachments/Reply can default the Id. | P3 |

#### Where ours is clearly better than AA (keep)
- **CSV / table work**: one-step Read and Write (no Open/Read/Close sessions), type detection, Filter, Aggregate, Sort, Sum column. AA needed Dictionary loops, text-file round trips and hand-built CSV lines (O1: about 45 steps vs 9; D2).
- **Desktop Get text** works for labels and text boxes alike; AA needs Get property with the right property name (Name vs Value). No fixed Delays were needed after clicks or window changes (AA used 300-500 ms Delays in D1, D3, D5).
- **Get desktop table** returns proper column names; AA's table includes the header as a data row.
- **Close window** tells whether the window really closed; **Get window details** returns size and position (AA has no equivalent).
- **Stop program** in Finally is safe when the process has already ended; AA needs a nested Try in Finally.
- **REST** parses JSON automatically and dotted paths reach nested fields; **Database** uses `?` parameters. AA needed a JSON session, node-path lookups with an index counter, and hand-escaped SQL (O4: 9 steps per row vs 2).
- **PDF Extract text** returns the text directly (AA writes a .txt file and reads it back).
- **Word Create document from a template** in one step (AA: open read-only, Save as, close without saving).
- **Email**: the outgoing server is kept with the Connect session, so Reply needs only the id and text; AA's IMAP Reply is deprecated and needs SMTP sign-in on every reply. Save attachments needs no "create folder" Try.
- **Generative AI Classify** returns exactly one allowed label, with personal-data masking on by default; AA's Chat AI returns free text that needs prompt engineering, trimming and "Includes" checks.
- **Output variables** return results directly; AA bots wrote every result to a log file with number-to-text conversions.
