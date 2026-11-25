# Document Automation: flexibility and global-readiness audit (25 Nov 2026)

The user's rule: we build for global customers; no hard-coded values or strict conditions; every choice list has an
"Other" / own-value option; country rules are optional, never the default. This audit read the platform screens
(`platform/ui/src/modules/document-automation`), the platform module (`platform/modules/document-automation`) and
DocFlow (`oneoffice-docauto/docauto`, 3.2.0). Read-only: nothing was changed. About 260 findings, grouped below.
`UI/` = platform/ui/src/modules/document-automation, `J/` = platform module Java, `DF/` = oneoffice-docauto/docauto.

## 0. Bugs found on the way (fix first)

| # | Where | What goes wrong |
|---|---|---|
| B1 | UI/http.ts:133-135, 588-590 | Saving a document type turns field types the screens do not know (boolean, object) into text: data loss |
| B2 | DF/api/app.py:794-796 + store.py:278 | A reference table's id is an ASCII slug of its name ("table" for non-Latin names) and saving upserts: a second such table overwrites the first |
| B3 | DF/docflow.py:220 | Default invoice check `subtotal + vat_amount - total`: no tax line gives an error and sends the invoice to review; discounts/shipping fail; 0.05 ignores 0- and 3-decimal currencies |
| B4 | UI/api.ts:934-947, engines/CostSection.tsx | The cost estimate uses prices typed into the screen code, not DocFlow's `/costs` prices |
| B5 | UI/stages.tsx:64, 210, 214 | Saved values outside a fixed list (folder interval, review confidence, review within) show blank |
| B6 | UI/upload.tsx:7, J/DocFlowProxy.java:482 vs 527, J/McpDocuments.java:140, testsets/ItemDialog.tsx:35, DF/docflow.py:47 vs instances.py:41 | Accepted file types differ between upload, email, MCP, test sets and DocFlow (XML accepted in some only) |
| B7 | UI/help.ts:78 vs UI/DocTypes.tsx:234, DF/docflow.py:1247 | Help says 3 to 5 samples; the dialog and DocFlow allow 1 to 3 |
| B8 | J/McpDocuments.java:140 | A wrong file type is reported as "check the service is running" |
| B9 | UI/engines/RoutingSection.tsx:36 | "Checks and review" shows the OCR retry threshold, not the review threshold |
| B10 | DF/settings.py:84, yaml:25 | Azure region defaults to westeurope, so residency rules think Azure is in Europe when nobody set it |
| B11 | DF/settings.py:88 vs google_docai/provider.py:77 | Document AI location default is "eu" in settings and "us" in the engine |
| B12 | UI/DocTypes.tsx:16 + 177, DF/docflow.py:55-62, 667 | Non-Latin names (Arabic, Chinese, Hindi...) all become "field" / "doctype" / "flow": the second one is refused as a duplicate |
| B13 | DF/paddleocr/provider.py:78 | PaddleOCR uses only the first language: for English + Arabic types Arabic is never read |
| B14 | DF/tesseract/provider.py:75-80 + en,ar default | A server without Arabic Tesseract data cannot read any built-in document type |
| B15 | UI/Inbox.tsx:55, overview/ProcessTable.tsx:83 | Source filters leave out "split" |

## 1. Country rules shown or applied as if general

| # | Where | Today | Change to |
|---|---|---|---|
| R1 | UI/stages.tsx:71, help.ts:64, api.ts:66/122 | "Read e-invoice data first (ZATCA XML, QR codes)" | "Read e-invoice data first" + a list of the standards to read (chosen per process) |
| R2 | DF/einvoice.py (all) | UBL 2.1, ZATCA QR and India GST QR only, always on; first QR only; GST date fixed | E-invoice reader packs (registry like engine packs): UBL/Peppol/EN 16931, CII (Factur-X, ZUGFeRD, XRechnung), ZATCA, India IRN, Mexico CFDI, Italy FatturaPA, Swiss QR-bill, EPC SEPA QR, Poland KSeF, Malaysia MyInvois... chosen per process |
| R3 | UI/review/EInvoicePanel.tsx:4-6 | Any e-invoice XML is labelled "ZATCA e-invoice XML" | Name the standard DocFlow detected |
| R4 | DF/docflow.py:211, scoring.py:133, extraction.py:173 | Built-in invoice's VAT number must be Saudi (`3\d{14}`): other countries' VAT numbers lose half their confidence and go to review | No pattern by default; "Tax ID format" picker by country + Other/none |
| R5 | DF/docflow.py:217, 1.9 | Field "VAT", alias "VAT 15%" | "Tax" with aliases per language (VAT, GST, Sales tax, IVA, MwSt, TVA...) |
| R6 | DF/docflow.py:219, scoring.py:131, extraction.py:228 | Currency list SAR, AED, USD, EUR, GBP; anything else refused | ISO 4217 list, plus own value |
| R7 | DF/docflow.py:241 | Bank statement account must be a Saudi IBAN | Any IBAN (checksum) or any account number |
| R8 | DF/docflow.py:251-255 | ID document = Saudi ID / Iqama pattern | No fixed pattern; country formats optional; passport MRZ reading |
| R9 | DF/docflow.py:236 | Payment methods include Mada, no UPI, Pix, iDEAL, bank transfer, cheque | Editable list per tenant |
| R10 | UI/api.ts:99/139, stages.tsx:325/352, DF/docflow.py:317-329 | Check kind "KSA VAT (ZATCA)" offered to everyone; rate 15% and pattern fixed | Generic "Tax check": rate(s), ID pattern, rounding; country presets (KSA, UAE, India GST, EU...) + own |
| R11 | UI/formats.ts:10-29 | Formats: Saudi VAT/IBAN/ID, India GSTIN/PAN, US EIN; amount only 1,234.56; dates only yyyy-mm-dd and dd/mm/yyyy; +966 examples | Formats grouped by country with a filter; amounts per locale and decimals; dates mm/dd, dd.mm, month names; neutral examples; Own pattern stays |
| R12 | DF/pii.py:18-35 | Personal-data masking knows Saudi ID, Saudi phones, IBAN, email; masks any 10-digit number starting 1 or 2 | Pattern list per country, switchable (Aadhaar, SSN, NINO, Emirates ID, cards with Luhn...) |
| R13 | UI/templates.ts:17-34, DF samples, testsets.py:194, benchmark.py:42, http.ts:698 | Samples and seed sets are Saudi (SAR, VAT 15%, KSA ID); seed set is Arabic/English invoices only | Samples by region, chosen from the tenant's country |
| R14 | UI/dialogs.tsx:85, help.ts:128, ConsolePage.tsx:22 | "Supplier invoices KSA", "in this country or in the cloud" | Neutral wording |
| R15 | DF/costs.py, UI currency "$" everywhere | Prices in USD only | Tenant currency + rate; prices from a file/setting |
| R16 | DF/policy.py:46-49 | Residency matches exact region names ("eu" is not "westeurope") | Region groups (EU, GCC, India, US...) |

## 2. Languages, scripts, numbers and dates

| # | Where | Today | Change to |
|---|---|---|---|
| L1 | DF/languages.py:15, config.py:25, docflow.py:76/810/1248, UI/DocTypes.tsx:72, ClassificationStep.tsx:61 | Every new document type is English + Arabic | The tenant's offered languages (set at install) |
| L2 | DF/normalize.py:53-67 | Language is "ar" or "en" only; only Arabic is right-to-left (Hebrew is not) | Script detection for all languages |
| L3 | DF/extraction.py:22 | Only Arabic and Persian digits converted | Any Unicode digit |
| L4 | DF/extraction.py:23-24, 57-66 | Dates: day-first tried before month-first (03/04 = 3 April); English month names only; no Hijri, Persian, Thai, Japanese era, CJK | Date order (DMY/MDY/YMD) per tenant or document type; localized months; calendars |
| L5 | DF/extraction.py:27-43; UI/stages.tsx:312 | A single "." is always decimal (1.234 means 1234 in Germany); 1'234.50, (1,234.00) not read; screen refuses "1,000" | Decimal and grouping separators per tenant / document type |
| L6 | DF/extraction.py:69, 116-122; tables.py:26; grounding.py:28; docflow.py:512 | Yes/no words, company endings, document titles, table-total words, currency symbols: English and Arabic (GCC) only | Word lists per language pack, editable |
| L7 | DF/llm_openai_compatible/provider.py:26-32 | AI prompt says documents are "English and Arabic" | Uses the document type's languages |
| L8 | UI/docflow.css (47 left/right rules) | Screens do not mirror for Arabic, Urdu, Persian, Hebrew | Logical CSS + `dir` from the user's language |
| L9 | UI toLocaleString("en") in CostSection, Dashboard, ProcessTable, RefData; http.ts:221 day labels | English digits and grouping forced; day of month only | The user's locale |
| L10 | UI/DocTypes.tsx:15 | Keyword/alias lists split only on "," | Also Arabic "،", full-width "，", ";" |
| L11 | DF/docflow.py:503, instances.py:535, extraction.py:216 | Teach from samples and learning from corrections only see Latin and Arabic labels | Any script |

## 3. Lists with no "Other" / own value

| # | Where | List | Add |
|---|---|---|---|
| O1 | UI/dialogs.tsx:87, ClassificationStep.tsx:38, Review.tsx:211, TestLab.tsx:85, testsets/SetList.tsx:77; DF/docflow.py:203-260 | Document type pickers: only DocFlow's types; five built-in types, no "Other" | A built-in **Other / General document** type (reads text, key-value pairs and tables with no fixed fields); "Not a known type" in Review; "Create a new type from this document"; library as an editable data file with more types (delivery note, contract, utility bill, payslip, credit note, bill of lading, customs, insurance claim, tax form) |
| O2 | DF/docflow.py:446-448, 862-969 | A document that fits no type can only be reclassified or rejected | Per process "When no type fits": review / deliver as Other / reject / ignore |
| O3 | DF/docflow.py:828 | Single-type process reads every document as its type (delivery note in an invoice process is read as an invoice) | Optional "check it really is this type" |
| O4 | UI/DocTypes.tsx:14, DF/models.py:230 | Field types: Text, Date, Amount, Number, Choice, Table | Yes/No (checkbox), Percentage, Date and time, Email, Phone, Address, Signature present, Multiple choice; Choice values editable (UI/DocTypes.tsx:10, 193) |
| O5 | DF/scoring.py:131, extraction.py:228 | Choice fields refuse values not in the list | "Allow other values" per field |
| O6 | UI/stages.tsx:20-25, DF/docflow.py:144 | Results go to: Excel, Folder, Web address, Start a bot | Email, SFTP, cloud storage (S3, Azure Blob, SharePoint/OneDrive), database, plus "Other: start a bot or script" |
| O7 | UI/stages.tsx:12, Builder.tsx:159 | If a check fails: Review, Reject, Warn | Send to another process, Notify someone |
| O8 | UI/stages.tsx:280 | Compare: > >= < <= == | not equal, between, contains |
| O9 | UI/api.ts:99, DF/docflow.py:134 | Six fixed check kinds | Regex check, date range check, custom script |
| O10 | UI/stages.tsx:82 | Mailbox: Microsoft 365 or IMAP; no SSL/STARTTLS choice (stages.tsx:101) | Gmail, POP3, Exchange; SSL choice |
| O11 | UI/stages.tsx:14-18, Engines.tsx:33, http.ts:77/99-104, TestLab.tsx:52, testsets/format.ts:14 | Engine lists typed into screen code; unknown option packs dropped | Built from DocFlow's engine list; "Other engines" group |
| O12 | UI/engines/EngineSettings.tsx:25, DF/settings.py:88/110-117 | Claude model, Document AI location, ABBYY product: fixed lists | Pick from list or type your own |
| O13 | UI/engines/RoutingSection.tsx:27-36, 59 | Pipeline picture names Tesseract and Rules; retry engine PaddleOCR or none | From the process's real engines; any ready local engine |
| O14 | UI/languages.tsx | Only languages engines know can be offered | Type a language code |
| O15 | UI/RefData.tsx:143-149 | CSV/Excel only; first row must be headings | TSV, JSON, ODS; heading row choice |
| O16 | UI/ProcessPage.tsx:66-69 | "Read by" and confidence filters fixed | From engines; any percentage |
| O17 | UI/Review.tsx:201, DF/api/app.py:748, J/McpDocuments.java:58 | Reject with no reason; review actions submit/reject only | Reason list + own reason; Skip, Escalate, Send to another group, Reclassify (MCP too) |
| O18 | J/DocFlowProxy.java:1091 | Trigger events: six fixed | Document failed, Delivery succeeded |
| O19 | DF/policy.py:8 | Three privacy profiles | Custom profile with own allow list |

## 4. Fixed numbers, times, paths that need a setting (default kept)

**Times:** review-task sync 20 s and mail poll 30 s (J/DocumentAutomationModule.java:85-86); mail interval minimum 60 s, not on screen (J/DocFlowProxy.java:502); timeouts to DocFlow 5 min / 5 s, IMAP 30 s; folder interval lists 30 s-15 min (UI/stages.tsx:62, dialogs.tsx:107, different lists); review confidence 70-95 % and "review within" 4-72 h lists (stages.tsx:209-214); field threshold 70-99 % (stages.tsx:155); dashboard always 14 days (UI/http.ts:86, DF/store.py:384); SLA fallback 24 h; DocFlow job, outbox, callback, webhook, watcher (5 s), SLA check (60 s), file settle (2 s) timings (DF/pipeline.py:38-41, outbox.py:27-31, events.py:32-35, docflow.py:1148-1506); test-run polling 2 s.
**Thresholds:** colour bands 85/70 % ignore the process's own threshold (UI/ui.tsx:46, review/TableField.tsx:21, testsets/RunResults.tsx:81); routing defaults 0.85 + PaddleOCR (UI/http.ts:487); DocFlow scoring constants (scoring.py:26-28, grounding.py:25/328, extraction.py, tables.py:30-32, docflow.py:121-128); benchmark repeats the retry threshold instead of reading the setting.
**Limits:** 500 pages hard stop (DF/preprocess.py:16, UI text upload.tsx:47); **no upload size limit at all** in DocFlow (whole file in memory, api/app.py:122/649/792); 50 MB agent download; 200 documents per process page with no paging (UI/http.ts:229-271); list_finished scans only the latest 500 (J/DocFlowProxy.java:742-774), so older ones are never listed; 1-3 teaching samples; 2 engines in the Test lab; 6 combinations per test run; 20 past runs; 8 corrections remembered; folder picker 300 folders; 9,999 pages in the page route.
**Paths and places:** developer folder `C:\OOA\oneoffice-docauto` in the launcher (J/DocFlowLauncher.java:35); DocFlow autostart Windows/PowerShell only (:116); Tesseract path `C:\Program Files\...` (DF/tesseract/provider.py:56); Windows-only path examples on screen; allowed folders must be a drive, UNC or "/" (UI/allowedFolders.tsx:17); "processed" subfolder and output folder names fixed; file names use 8 characters of the id; DocFlow address must have no path, so no reverse proxy at /docflow (J/DocumentAutomationModule.java:58); agent paths must end .flow.json.
**Other:** the process page's document columns are always "Supplier · invoice no." and "Total" (UI/http.ts:279, ProcessPage.tsx:60): choose columns from the type's fields; which roles get docs.* permissions is fixed (J/DocumentAutomationModule.java:51); worker count only in YAML; DocFlow model names and prices duplicated in code; Ctrl+Enter only (no Cmd on Mac).

## 5. Rules that are too strict

| # | Where | Today | Change to |
|---|---|---|---|
| S1 | UI/Builder.tsx:88, http.ts:360 | A process has exactly one document type (DocFlow accepts several) | Several types per process |
| S2 | UI/dialogs.tsx:47 | A new process must have a type | "Any type: recognise each document" |
| S3 | UI/DocTypes.tsx:113 | A type cannot be saved with no fields | Save as draft |
| S4 | DF/scoring.py:133-140 | Any pattern mismatch halves confidence and forces review | Per field: error / warning / off |
| S5 | DF/provider.py:56, router.py:57 | An engine must know every language of the type or it is never used | Use engines that cover some, ranked |
| S6 | DF/extraction.py:325 | Company names with more than 2 digits or 8 words skipped ("3M", "7-Eleven") | Looser rule |
| S7 | DF/docflow.py:838, 840 | E-invoice check only for .xml in single mode; only types named invoice accept e-invoices | Sniff PDFs too; "accepts e-invoices" per type (credit notes) |
| S8 | J/DocTemplates.java:49, 75 | Installing a template switches off every delivery except Start a bot; Smart Inbox never in a template | Ask which to keep |
| S9 | UI/dialogs.tsx:98 | Upload always on | Can be switched off |
| S10 | UI/stages.tsx:110, 246 | Email filters single values; web address delivery has no method, headers or sign-in | Lists; method, headers, vault sign-in |
| S11 | UI/RefData.tsx:151 | Uploading a table with an existing name replaces it silently | Ask: replace or keep both |
| S12 | J/DocFlowProxy.java:56-63, 550; J/DocTemplates.java:73 | Ids ASCII only, 80-120 characters | Generated ids, names in any script |
| S13 | DF/folders.py:25 | Windows reserved names refused on Linux too | Only on Windows |

## 6. Ideas from AWS, Google and Azure (light: no new installs, no heavy code)

Checked 25 Nov 2026: Amazon Bedrock Data Automation (blueprints, explicit/inferred fields, instruction optimization with
ground truth), Amazon Textract (queries, adapters, signatures, layout; A2I human review is in maintenance since July
2026), Azure Content Understanding (field methods extract/classify/generate, confidence + grounding on every field,
labeled samples with Auto label), Azure Document Intelligence (add-ons: query fields, barcodes, searchable PDF),
Google Document AI Workbench (custom extractor without training, classifier, splitter, precision/recall/F1 per parser).

| # | Idea | Seen in | What we have today | Change |
|---|---|---|---|---|
| C1 | Field instructions in plain words ("the amount the customer must pay, after discounts") | BDA, Azure CU, Google | DocFlow FieldDef.description exists and AI engines can use it; the screens cannot edit it, nor the document type's description | Edit both on the Document types page; AI reading and AI classifying use them |
| C2 | How a field is read: Copy as written / Work it out / Pick a category | BDA explicit vs inferred; Azure extract / generate / classify | Every field is "copy as written" | Field option; "Work it out" fields (summary, is it overdue, risk, category) go only to AI engines |
| C3 | Ask a question as a field ("Who signed for the buyer?") | Textract queries, Azure query fields | No | Same as C2 "work it out", phrased as a question |
| C4 | Improve my instructions from examples: give 3-10 documents with right values, AI rewrites the field instructions, shows before/after match rate and F1, you save or discard | BDA instruction optimization | Test sets with right values and per-field accuracy exist | "Improve with AI" on a test set: runs, proposes new instructions, compares, saves as a new document-type version |
| C5 | Auto-fill right values: run the engine, fill the expected values, the person only corrects | BDA Auto-populate, Azure Auto label | Test-set items are typed by hand | "Fill from a reading" in the test-set item dialog |
| C6 | Labeled samples: corrected documents teach new layouts | Azure CU labeled samples | Last 8 reviewer corrections go into the AI prompt; learned aliases | Pick which approved documents are "teaching examples" per type (and see/remove them) |
| C7 | Accuracy from real work: per field how often reviewers change it; straight-through rate per process | Google precision/recall/F1; AA automation rate | Accuracy only from test sets | Accuracy tab per process and type from review history; straight-through %, top corrected fields |
| C8 | Threshold advice: "at 85 %, 72 % go straight through and about 1 in 80 wrong values slip by" | Azure CU (confidence for straight-through) | Thresholds picked from a list | Slider in step 6 with numbers from the review history |
| C9 | Searchable PDF as a result | Azure DI (free add-on), Textract | DocFlow has POST /pdf/searchable; no screen or delivery uses it | Delivery option "also save a searchable PDF" |
| C10 | Checkbox and signature fields | Textract signatures, Azure selection marks | DocFlow has the SELECTION_MARKS capability; no field type | Field types "Ticked?" and "Signed?" filled by engines that report them (Azure, Textract, AI) |
| C11 | Value normalising: output format per field (date as yyyy-mm-dd or dd/mm/yyyy, number with 2 decimals, upper case, trimmed, country code to name) | BDA normalization | Values typed; no output format choice | "Write as" per field |
| C12 | Split review: see where one scan was cut into documents and move the cut or change a part's type | Google splitter, AA | Split batches switch only | Review shows the parts with page ranges; drag/edit the cut |
| C13 | Engine per document type inside one process (invoices to Claude, IDs to Azure) | Azure composed models, BDA projects | Engine chosen per process | Optional engine override per type |
| C14 | Choice descriptions: each choice value explained so AI picks it right | Azure classify method | Choice values only | Short description per value |
| C15 | Document to text for search and One Chat: whole document as text/markdown with headings and tables | Azure layout markdown, Google layout parser | OCR text kept per page | Delivery "send to Knowledge" (platform's Knowledge Q&A) |
| C16 | Our own review stays the human review; no dependency on cloud review services | A2I in maintenance (Jul 2026) | Task inbox review | Keep; say so in docs and sales notes |
| C17 | Budget per process: monthly cloud/AI spend limit, warn at 80 %, then local engines only | AWS/Azure budgets | Cost estimate only | Setting per process + Overview warning |
| C18 | Field limit hint: many fields in one AI call lower accuracy; group them | BDA (15 sync / 100 async fields) | No hint | Hint on the document type when it has many fields |

## Build order and status (user's OK 25 Nov 2026: "fix all the defects and improvements and suggested ideas and features and complete the unit testing")

Branches: platform `claude/document-automation-unit-testing-f56b50`; DocFlow `da-global-flex` (worktree
`C:\OOA\oneoffice-docauto-flex`, from main f2e9e24). No DocFlow release or platform merge without the user's OK.
Resume: read this section, continue at the first batch not Done.

| Batch | Items | Status |
|---|---|---|
| 1 | Bugs B1-B15 | Done |
| 2 | Country rules as optional packs R1-R16 | Done |
| 3 | Languages, numbers, dates L1-L11 | Done (L8: CSS is logical; the app has no right-to-left UI language yet) |
| 4 | Other options, fixed values, strict rules | Done except: O6 more delivery kinds (email, SFTP, cloud storage), O7 route or notify when a check fails, O10 Gmail/POP3 inboxes, O19 own privacy profile, S9 switching off upload, roles per permission in code, folder picker cap of 300, agents' list of finished documents scans the newest 500 |
| 5 | Cloud-inspired ideas C1-C18 | Done (C10: the field kinds exist; mapping cloud engines' check boxes and signatures to them is left) |
| 6 | Unit tests | Done: DocFlow 260 pass (test_global_flex.py 29 cases); platform module tests pass; DocFlowLiveTest 15/15 against the branch's DocFlow (the UT Document Automation agent's cases through a running agent's routes); UI 542 pass (globalFlex.test.tsx 13). The UT agent itself still runs only in One CommandCenter with a runner |
| Later | DA-2 to DA-7 phases (keys, events, engines, volume, Helm, regions), Entra client ID | Each its own phase per document-layer-plan.md |
