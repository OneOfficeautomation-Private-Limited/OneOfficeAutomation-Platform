# Help links: Docs and Video everywhere

Every page, dialog, Settings section, agent command and coded error in the product shows two links: **Docs** (the
documentation) and **Video** (how to use it). They all come from one catalogue, so a link is changed in one place and
never inside a page. Added on 13 Nov 2026 (round 3, items 5 and 6).

## Where the links show

| Place | Key | Shown as |
| --- | --- | --- |
| Each console page, beside its title and one-line description | `page:<page id>` (as in `#/<id>`) | Docs · Video |
| Studio's Agents page | `studio:agents-public`, `studio:agents-private` | Docs · Video |
| Document Automation's pages | `docs:<tab>` | Docs · Video |
| The help panel (ⓘ) | the page's key | Read the documentation · Watch how to use it (video) |
| Dialogs: Run, Create (each item type), schedules, triggers, users, credentials, folder permissions, approvals | `dialog:<name>`, `item:<kind>` | Docs · Video beside the title |
| Settings sections | `setting:<section id>` | Docs · Video beside the section title |
| Agent commands: the Actions list (on hover) and a selected step's Properties | `command:<toolkit>.<action>` | icons, or Docs · Video |
| Errors with a code (the page's red bar, a run's Why it ended) | `error:<CODE>` | Learn more |

The one-line page descriptions are in `platform/ui/src/shell/PageIntro.tsx` (`PAGE_INTROS`); a test fails if a page in the
menu has none.

## How an address is made

1. A link set for the key is used as it is: first the server's `<data>/config/help-links.json`, then `OVERRIDES` in
   `platform/ui/src/shell/helpCatalogue.ts`.
2. Otherwise one rule builds it: **docs address + version + path** and **video address + path**, where the path comes
   from the key: `page:activity` → `pages/activity`, `command:excel.open-workbook` → `commands/excel/open-workbook`,
   `error:RUN.INPUT_MISSING` → `errors/run-input-missing`. So a new page or command has both links on its own.

The addresses are `https://docs.oneofficeautomation.com/` and `https://videos.oneofficeautomation.com/` (placeholders
until those sites exist). **Settings > General > Help links** (`help_docs_base`, `help_video_base`) points them at a
company's own copy, for servers with no internet access. The version is the server's (`/api/help-links`), so people on
an older release open that release's documentation.

## Changing links after a release (no rebuild, no restart)

Put a file at `<data>/config/help-links.json`:

```json
{ "links": {
    "page:schedules": { "video": "https://videos.example.com/schedules-intro" },
    "command:excel.open-workbook": { "doc": "https://docs.example.com/excel/open" } } }
```

The server reads it on each request (`GET /api/help-links`); the pages pick it up when they next load. A file that does
not parse is reported in the answer (`problem`) and ignored, so a typo never takes the links away.

## Changing links in the product

- A one-off address: add the key to `OVERRIDES` in `helpCatalogue.ts`.
- A new page: add its sentence to `PAGE_INTROS` and put `<PageIntro k="page:<id>" />` after its title's ⓘ.
- A new dialog: pass `help={<HelpLinks k="dialog:<name>" label="..." />}` to `Dialog`.
- Before a release, check that the addresses answer (a dead-link check run on request; tests never go to the internet).
