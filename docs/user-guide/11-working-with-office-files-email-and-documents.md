# Working with Office files, email and documents

**Audience:** agent builders who automate everyday office work: workbooks, Word letters, slide decks, PDF files, CSV
exports, folders full of files and mailboxes.

## Contents

1. [Which toolkit to use](#1-which-toolkit-to-use)
2. [Sessions: open, work, close](#2-sessions-open-work-close)
3. [Excel without Office (basic)](#3-excel-without-office-basic)
4. [Excel desktop (advanced, needs Excel)](#4-excel-desktop-advanced-needs-excel)
5. [Word](#5-word)
6. [PowerPoint](#6-powerpoint)
7. [PDF](#7-pdf)
8. [CSV and text files](#8-csv-and-text-files)
9. [Files and folders](#9-files-and-folders)
10. [Email: SMTP and IMAP](#10-email-smtp-and-imap)
11. [Email: Microsoft 365, Outlook desktop and Gmail](#11-email-microsoft-365-outlook-desktop-and-gmail)
12. [Worked examples](#12-worked-examples)
13. [If it goes wrong](#13-if-it-goes-wrong)
14. [Related guides](#related-guides)

---

## 1. Which toolkit to use

In the **Actions** panel these toolkits sit under **Office documents**, **Files and folders**, **Data formats** and
**Email and collaboration**.

| Job | Group in Actions | Needs Microsoft Office? |
| --- | --- | --- |
| Read and write .xlsx/.xls workbooks | **Excel** | No |
| Run macros, refresh data, export to PDF in real Excel | **Excel desktop** | Yes (Windows) |
| Read and write .docx letters and reports | **Word** | No |
| Read and write .pptx decks | **PowerPoint** | No |
| Read, split, merge, fill and protect PDFs | **PDF** | No |
| CSV and delimited text files | **CSV / Text files** | No |
| Files, folders, zip | **Files**, **Folders** | No |
| Mail through any mail server | **Email** | No |
| Outlook mail, OneDrive, SharePoint, Teams, Excel online | **Microsoft 365** | No (cloud) |
| The Outlook program on the desktop | **Outlook desktop** | Yes (Windows) |
| Gmail, Google Drive, Sheets, Calendar | **Google Workspace** | No (cloud) |

## 2. Sessions: open, work, close

Excel, Word and PowerPoint work in **sessions**: you open a file once, then later steps work on it, then you close it.

- The open action's **Session** (under More options) is a name such as `excel`. Steps that keep the default name use the
  one open file, so for one file at a time you never need to touch it.
- To work on two workbooks at once, open the second with another session name (for example `source`) and set the same
  name in the steps that work on it.
- **Close** has **Save changes**. Studio ticks it for you when the agent has steps that change the file; otherwise
  closing does not save.
- Put the close step in the **Finally** section of a **Try**, so the file is closed even when a step fails.

Paths can be relative to your workspace (`data/sales.xlsx`) or full (`C:\Reports\sales.xlsx`). In a **File** field,
Studio suggests the files of your workspace as you type.

## 3. Excel without Office (basic)

The **Excel** group works on workbook files directly, so it runs on servers and runners without Office.

**Open workbook**

- **Path** of the workbook; **Header** (the first row holds column names, on by default);
- tick **Open a named sheet** to choose the **Sheet**; **Read only**;
- tick **The file has a password** for an encrypted workbook (a Credential);
- tick **The file is on OneDrive or SharePoint** to give an **Access token** and **Drive** (the file is downloaded on
  open and saved back on save; the path looks like `onedrive:Reports/sales.xlsx`);
- More options: **Create** (make the workbook when it does not exist), **Session**.

**Read data**

| Action | Gives |
| --- | --- |
| **Get all cells** (the active sheet or a named sheet) | A Table and the number of data rows |
| **Get range** (`A1:D20`) | A Table |
| **Get cell** (the active cell or a specific cell) | The value |
| **Read row**, **Read column** | A List |
| **Find** | The addresses of matching cells |
| **Get number of rows**, **Get sheet names**, **Get sheet count** | Numbers and names |

**Write data**

**Set cell**, **Set formula**, **Write from Data Table** (with **Start cell** and **Include header**), **Insert rows**,
**Delete rows**, **Insert columns**, **Delete columns**, **Replace**, **Sort**, **Filter**, **Remove blank rows**,
**Set cell colour**.

**Worksheets and workbooks**

**Create workbook**, **Create worksheet**, **Delete worksheet**, **Rename worksheet**, **Copy worksheet**,
**Switch to sheet**, **Hide sheet**, **Unhide sheet**, **Append workbook**, **Append worksheet**, **Protect workbook**,
**Protect sheet**, **Save**, **Save as** (other formats, with **Overwrite**), **Close**.

**Loop over rows without reading them first:** in a **Loop**, choose **For each** and under **Go through** pick
**Each row in a worksheet**.

## 4. Excel desktop (advanced, needs Excel)

**Excel desktop** drives the real Excel program, for what only Excel can do:

| Action | Use |
| --- | --- |
| **Run macro** | Run a VBA macro by name, with **Arguments**, and **Save** afterwards |
| **Refresh all data** | Refresh queries, pivot tables and connections |
| **Update workbook links** | Update links to other workbooks |
| **Export to PDF** | The whole workbook or one sheet; quality, page range |
| **Convert workbook** | Save as another format, optionally with a new password |

These need Windows with Excel installed on the runner. Leave **Visible** off for unattended runs.

## 5. Word

**Word** reads and writes .docx files without Word installed (**Extract text from a file** also reads old .doc and .rtf).

1. **Open document** (or **Create document**, optionally from a **Template**).
2. Work on it:
   - **Replace text**: put values into a template, for example replace `{{customer}}` with `${customer}`;
   - **Insert text** at a position or after an anchor, with font, colour, size and bold;
   - **Add paragraph**, **Insert table** (from a Table variable, at the end, after a text or after a bookmark),
     **Insert picture**, **Find and replace picture**, **Format text**;
   - **Read text** (the whole document, a paragraph range, a bookmark or a page), **Read tables**.
3. **Save as** another name (format .docx or plain text), or **Save**, then **Close**.

## 6. PowerPoint

**PowerPoint** opens and edits .pptx decks without PowerPoint: **Open presentation**, **Create presentation**
(optionally from a template), **Add slide**, **Copy slide**, **Delete slide**, **Insert text**, **Insert picture**,
**Insert table**, **Find and replace text**, **Format text**, **Find and replace picture**, **Read text**,
**Extract text from a file**, **Get slide count**, **Save**, **Save as**, **Close**.

A common pattern: keep a template deck with placeholders such as `{{month}}`, open it, **Find and replace text**, insert
this month's chart picture and table, and **Save as** a new file.

## 7. PDF

| Action | What it does |
| --- | --- |
| **Extract text** | All pages, a page range or listed pages; optionally save the text to a file |
| **Extract text from area** | Text inside a rectangle of one page (fixed layouts such as forms) |
| **Get page count**, **Get document properties** | Information about the file |
| **Merge PDFs** | Listed files, or every PDF in a folder |
| **Split PDF** | By pages per file, page lists or bookmarks |
| **Read form fields**, **Fill form fields** | PDF forms; **Flatten** makes the filled values permanent |
| **Encrypt PDF**, **Decrypt PDF** | Passwords and permissions (printing, copying, changing …) |
| **Convert to images**, **Extract images** | Pages as pictures; pictures out of the PDF |

A scanned PDF has no text; turn the pages into images and read them with **OCR** (see
[Web and desktop automation](10-web-and-desktop-automation.md)), or use document extraction with AI (see
[AI in agents](13-ai-in-agents.md)).

## 8. CSV and text files

- **Read to table** (group **CSV / Text files**): **Path**, **Header**, **Delimiter**, **Trim**, **Encoding**; More options
  **Skip lines**, **Read only the first rows**, **Skip empty rows** and **Detect numbers and dates** (kept in view
  because it matters: with it, `1200` becomes a number you can compare).
- **Write from table**: **Table**, **Path**, **Header**, **Delimiter**, **Encoding**, **Append**; More options
  **Quote all** and **Line ending**.
- In a loop, **Each row in CSV/TXT** goes through a file row by row without reading it all first.
- Then use the **Data table** group: **Filter rows**, **Sort**, **Look up value**, **Group and summarise**,
  **Join tables**, **Merge tables**, **Remove duplicate rows** and more.
- **Files > Read text** and **Write text** handle plain text files.

## 9. Files and folders

**Files** group: **Copy**, **Move or rename**, **Delete**, **List files**, **Get file info**, **Wait for file**, **Zip**,
**Unzip**, **Read text**, **Write text**, **Read variables from file**, **Get file name**, **Get parent folder**,
**Create shortcut**, **Print files**. Copy, Move and Delete can work on many files at once: tick **Copy several files by
name pattern** (or the Move/Delete equivalent) and **Filter by size** / **Filter by date**.

**Folders** group: **Create folder**, **Delete folder**, **List subfolders**, **Copy folder**, **Rename folder**,
**Open folder**.

Conditions: **File exists**, **File size is**, **File date is**, **File has extension**, **Folder exists**,
**Folder is empty**. Loops: **Each file in folder**, **Each folder in folder**, and **Each new file in folder** (trigger
loop).

> **Tip:** **Delete** of files cannot be undone. Test with **List files** first and check the count.

## 10. Email: SMTP and IMAP

The **Email** group works with any mail server.

### 10.1 Send an email

1. Add **Send email**.
2. Fill in **To** (addresses separated by `,` or `;`, or a List), **Cc**, **Bcc**, **Reply to**, **Importance**,
   **Subject**, **Attachments** and **Body**; tick **Body is HTML** for HTML.
3. Choose the server:
   - after a **Connect to mailbox** step that kept an outgoing server, nothing more is needed;
   - otherwise tick **Send through a specific server** and fill in **Outgoing mail server (SMTP)** (for example
     `smtp.office365.com`), **Port** (587 for STARTTLS, 465 for SSL), **Security**, **User** and **Password** (a
     Credential) or **Access token**.
4. Optional (More options): **From**, **Read receipt**, **Inline images**.

The output is the **message id** of the sent mail.

### 10.2 Read a mailbox

1. **Connect to mailbox**: **Incoming mail server (IMAP)**, **User**, **Sign in with** *A password* or *An access
   token*, **Protocol** (IMAP keeps folders and read status; POP3 reads only the inbox), **Security** (SSL by default),
   **Port**. Tick **Keep an outgoing server for sending** to add the SMTP server for later replies. More options:
   **Shared mailbox**.
2. **Find emails**: **Folder**, **Unread only**, **Read only**, **From contains**, **Subject contains**, **Since**,
   **Before** and more. The result is a Table with `id`, `from`, `subject`, `received`, `unread` and
   `has_attachments`, and a count.
3. Loop over the rows (or use **Each email in a mailbox** in a loop) and use the `id`:
   **Read email**, **Save attachments**, **Mark read or unread**, **Move email**, **Reply to email**, **Reply to all**,
   **Forward email**, **Delete email**, **Save email as .eml**.
4. Bulk actions: **Save attachments of all matching emails**, **Move all matching emails**,
   **Delete all matching emails**.
5. **Disconnect**.

## 11. Email: Microsoft 365, Outlook desktop and Gmail

**Microsoft 365** (through Microsoft Graph) needs an **Access token** from the vault (a Credential). Its mail actions
are marked "(Outlook)": **Send email (Outlook)**, **Find emails (Outlook)**, **Read email (Outlook)**,
**Save email attachments (Outlook)**, **Move email (Outlook)**, **Reply to email (Outlook)**, **Forward email (Outlook)**,
**Change email status (Outlook)** and others. **Mailbox** (More options) is `me` or another user's address (needs
application permission). The same toolkit has OneDrive/SharePoint files, Excel online, calendar and Teams (see
[Cloud and business apps](12-cloud-and-business-apps.md)).

**Outlook desktop** drives the Outlook program on Windows with its mail profile: **Find emails**, **Read email**,
**Send email**, **Reply to email**, **Forward email**, **Save attachments**, **Move email**, **Mark read or unread**,
**Delete email**, **Get folders**, including shared mailboxes. It needs a signed-in Windows desktop.

**Google Workspace** needs a Google OAuth **Access token** from the vault: **Send Gmail**, **List Gmail messages**,
**Read Gmail message**, **Reply to Gmail message**, **Forward Gmail message**, **Save Gmail attachments**,
**Move Gmail message**, **Change Gmail message status**, **Delete Gmail message** and the "all matching" versions.

## 12. Worked examples

### 12.1 Collect invoice attachments from a mailbox

1. Variables: `mail_password` (Credential, input), `emails` (Table), `email` (Record), `saved` (List).
2. **Connect to mailbox**: server `imap.example.com`, user `invoices@example.com`, password `$mail_password`.
3. **Find emails**: folder `INBOX`, **Unread only**, subject contains `Invoice`; save **table** in `$emails`.
4. **Loop** > For each > **Each row in a table**, over `$emails`, store each row in `$email`:
   - **Save attachments**: **Id** `$email.id`, target folder `invoices/incoming`; save **files** in `$saved`.
   - **Mark read or unread**: **Id** `$email.id`, **Read** true.
5. **Disconnect**.

Run it with the input `mail_password` set to a vault reference such as `mail/invoices/password`.

### 12.2 Fill a Word letter for every row of a workbook

1. **Open workbook** `data/customers.xlsx`; **Get all cells** into `$customers`; **Close**.
2. **Loop** over `$customers`, each row in `$row`:
   1. **Open document** `templates/letter.docx`.
   2. **Replace text** `{{name}}` with `${row.Name}`; again for `{{amount}}` with `${row.Amount}`.
   3. **Save as** `out/letter-${row.Id}.docx`.
   4. **Close** without saving the template.
3. Wrap the loop body in a **Try** whose Catch logs `${row.Id}` and the error, so one bad row does not stop the rest.

The sample agent `O3-pdf-to-word-letters` in `examples/use-cases` shows a complete version that starts from PDF invoices.

### 12.3 Monthly report from CSV to Excel

1. **Read to table** `exports/sales.csv` with **Detect numbers and dates**.
2. **Group and summarise**: group by `Region`; summary row *Total* = `sum` of `Amount`.
3. **Create workbook** `out/sales-report.xlsx` (tick **Replace the file if it exists**).
4. **Write from Data Table** at `A1` with header; **Save**; **Close**.
5. **Send email** with the workbook as attachment.

## 13. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| A `FILE.NOT_FOUND` error | The path is wrong or relative to another folder. Use a workspace path or a full path; check with **File exists**. |
| The workbook is unchanged after the run | **Close** did not save. Tick **Save changes**, or add **Save** before **Close**. |
| "The file is in use" kinds of errors | Another program (or an earlier failed run) still has the file open. Close it; put **Close** in a **Finally**. |
| Numbers from CSV compare as text | Tick **Detect numbers and dates**, or convert with the Number toolkit. |
| SMTP refuses to sign in (`EMAIL.` error) | Check port and **Security** (587 STARTTLS, 465 SSL), and that the account may send through SMTP; Microsoft 365 often needs an access token instead of a password. |
| Microsoft 365 or Google steps fail with an authorisation error | The access token has expired or lacks the permission (scope). Renew the token in the vault. |
| Excel desktop or Outlook desktop steps fail on a runner | The runner needs Windows, Office installed and (for Outlook) a mail profile, in a signed-in session. |

## Related guides

- [Building agents](03-studio-building-agents.md)
- [Cloud and business apps](12-cloud-and-business-apps.md)
- [AI in agents](13-ai-in-agents.md)
- [Web and desktop automation](10-web-and-desktop-automation.md)
- [Running and debugging](04-studio-running-and-debugging.md)
