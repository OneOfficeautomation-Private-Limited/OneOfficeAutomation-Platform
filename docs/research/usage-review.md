# Usage review (fix items 21-28), research before building

Started 9 Nov 2026. The method follows item 28. For each feature:

- Read how it is **used** in Automation 360 (AA) and Blue Prism (BP). UiPath will be added when its docs arrive.
- Try ours as a user in the console and One AgentStudio.
- Write down the gap and a proposal.

Nothing here is built yet. Sources are the offline doc library (see the URLs cited). Everything is summarised in our own words.

---

## Area A: repository lifecycle (items 21, 22, 23)

### A1. How AA uses Public and Private

- **Public** is the shared, deployable workspace (`cr-repository-operations`). Agents are run, scheduled, labelled, deleted
  and versioned there. **Nobody edits in Public.** AA's own guideline is: use Public for deployment, not for storing work.
- **Private** belongs to one builder. New agents start there. Check-in moves them to Public, into the same folder, and
  removes them from Private. Admins and runner-only users have no Private workspace.
- Names must be unique across both workspaces. You can't create, check out or rename something in Private if the same
  name already exists in the same Public folder.

| Action | Where it starts | Where the result lands | Rules worth copying |
|---|---|---|---|
| **Check out** (`cloud-bot-check-out`) | Public ⋮ menu | Private, editable, same folder | One person at a time. **Advanced options** pick an earlier version (that is AA's rollback) and the dependency versions. Child agents someone else holds are *cloned* (read-only). The owner can cancel the check-out from Private, an admin from Public. |
| **Clone** (`clone-a-bot`) | Public ⋮ menu | Private, **read-only**, same folder | Doesn't lock; many people can clone. The clone follows later Public changes. To change it: copy the clone, or delete it and check out instead. |
| **Copy** (`cloud-copy-a-bot`) | ⋮ menu | **Always Private.** Name field, optional Browse for the folder | Keeps images, recorded objects, files and child agents. No trailing dot or space, no reserved Windows names. |
| **Move** (`move-a-bot`) | Private only | Private | Only *new* agents (not checked out, edited or cloned). Moving one warns that callers need their reference updated. |
| **Check in** (`cloud-bot-check-in`) | Private ⋮ menu, or up to 10 ticked | Public | General tab: comment plus child dependencies with their status (New, Checked out, Cloned, Checked out and edited). New children always go along. Checked-out children can be held back. Then a Review dependencies tab. Logged as Check In Request, then Check In. |
| **Cancel check-out / Revert edits** | Private (owner), Public (admin) | — | Revert edits undoes your changes and keeps the check-out. |
| **Delete** (`delete-a-bot`) | Either workspace | — | Private: only New or Cloned items. Public: only checked-in items (not checked out), and only if no other agent calls it. "Yes, delete"; AA says it can't be restored. |
| **History / Compare / Label** | Public ⋮ menu, or the open agent's ⋮ | — | History lists version, comment, when and by whom. Compare shows two versions side by side by Actions, Variables, Triggers, Packages and Dependencies, with Swap and Close. A label (Production) on a version is what runs, schedules and exports use. |
| **Dependency map** | ⋮ "View dependencies and references" | — | Shows direct parents and children. |

Opening a Public agent shows it **read-only**. Its ⋮ menu offers the same lifecycle actions, and the view has a plain
**Close**.

### A2. How Blue Prism does the same job

- BP has no Private workspace. Studio **locks** an item while someone has it open (`frmSystemUnlock`). Anyone else gets a
  choice: take the lock (only if no unsaved work) or **view it read-only** (Preview mode).
- **Clone** in BP is File > Save as: a new name, a group you are allowed to create in, then a description. It is our
  "Copy", not AA's Clone.
- **Delete** asks for a reason, and items that have already run can't be deleted, for audit (`frmProcessDelete`).
- History has a compare wizard (System > Processes > History). Releases are moved with Release Manager.

What we take from it: read-only viewing is the default for anything you don't hold. A delete reason is a cheap audit win.

### A3. What we do today (code and a run-through on the local instance)

- **Copy in Public lands in Public.** It was confirmed in the UI: `Test4 (copy1` sits in Public › My Bots, v1, not live.
  - The console agent view's ⋯ menu offers **Copy…** to anyone with check-in rights (`console/Repository.tsx:347`).
  - `ControlRoom.copyBot` (`ControlRoom.java:1158`) stores the copy straight into the shared repository as version 1. It
    skips Private and check-in, so there's no review of dependencies and no check-in comment.
  - That is the opposite of AA, where a copy always lands in Private.
- **Move…** is also offered on Public agents for anyone who can edit the folder (`Repository.tsx:346`). AA allows moves
  only in Private, and only for new items.
- **No Clone.** There is no read-only private copy. The Studio Public tab has Check out, Versions, "Check out a version…"
  and Undo check-out (`studio/ControlRoom.tsx:178-184`). When an agent is checked out, its called agents are copied
  without a check-out, which is close to AA's dependency clone. But you can't clone on purpose.
- **Edit** in the console agent view just opens the Studio Public tab (`Repository.tsx:365`). It doesn't check out that
  agent, so you have to find it again.
- **Close (item 22).** The open agent view's only way back is a bare "×" in a 32 px icon slot at the far right, after
  Edit, Run and ⋯ (`Repository.tsx:368`). Its tooltip says "Back to the folder". It's easy to miss beside the ⋯, and
  Escape is the only other way out.
- **Inputs and details (item 23).**
  - Facts are a run-on list of term/value pairs.
  - The Inputs and Outputs tables have **no header row**, so you can't tell which column is the type and which is
    "Required".
  - Inputs show only for the **live** version, so a not-live agent shows nothing at all.
  - The Runs table has no header either.
  - Versions does have headers, so it's the odd one out the other way.
- **What we already do well, keep it:**
  - check-out of a chosen version;
  - Undo check-out (owner or admin) and Revert edits;
  - Private statuses New / Checked out / Checked out · edited / Edited, not checked out;
  - checking in up to 10 at once;
  - labels and the live (production) version, plus approval before live;
  - side-by-side version compare;
  - Deleted agents with Recover (AA can't restore, so ours is better);
  - Dependencies "Calls / Called by".

### A4. Why the first pass missed this

The October comparison checked that each **feature exists** (copy ✓, check-out ✓, delete ✓), and most tests called the
API. Nobody asked **where the result lands**, **who may do it in which workspace**, or **what the screen looks like** for
a user. Copy existed on both sides, so it was marked equal, even though AA's copy is a Private-only action. From now on
each row records: where it starts, where it lands, the rules, and a UI run-through (item 28).

### A5. Proposals (for you to choose; nothing built)

| # | Proposal | Notes |
|---|---|---|
| A-1 | **Copy always lands in Private.** The Public ⋯ shows "Copy to Private…" with a name and a folder picker (the Private tree). The server refuses a Copy whose target is Public. An admin can still import. | Fixes item 21. One Studio route plus a console dialog. Existing copies in Public stay; we tell admins with a one-time notice. |
| A-2 | **Add Clone (read-only private copy).** On Public ⋯ and Studio's Public tab. It doesn't lock, and it refreshes when Public changes. Opening it says "Read-only clone: Copy it or Check it out to change it". | AA parity. It also makes "look at how this works" safe. |
| A-3 | **Public is read-only for structure.** Remove Move… from Public (move in Private before check-in; admins keep folder Rename/Move for whole folders). Delete in Public stays for people with delete rights; add an optional **reason** (as Blue Prism) and block deleting an agent other agents call (we already list "Called by"). | Matches AA. Moving between public folders stays an admin folder task. |
| A-4 | **Edit = check out this agent.** The console's Edit checks this agent out and opens it in Studio. If it's already yours, it opens your copy; if someone else holds it, it offers Clone instead. | Saves the "find it again" step. |
| A-5 | **A clear Close.** A labelled **Close** button (secondary style, with an × icon and text) at the left of the action group, plus "‹ Back to *folder*" in the breadcrumb. Escape still works. | Item 22. Same pattern for every full-page view (agent, run, queue item), so it's learnt once. |
| A-6 | **Inputs and details as a standard page.** A two-column "Details" card (Kind, Folder, Status, Live, Latest, Updated, Agent id, Your access). Then **Inputs** and **Outputs** tables with headers (Name · Type · Required · Default · Description), for the version picked in the version list, not only the live one. Runs gets headers too (When · Version · Status · Runner · Started by). | Item 23. It becomes the table rule for every tab: always a header row, same column order, empty state in words. |
| A-7 | **Check-in dialog like AA's.** A comment, then the dependencies with their status. New ones always go; checked-out ones can be held back. A review step comes before Check in. | We already check in called agents. This makes it visible. |

Next: area B (editor step menu, command explanations, notices: items 24, 25, 27), then the commands (item 26).

---

## Build progress (branch ui/usage-review, approved 9 Nov 2026: "Yes build all slowly and test it")

Order, one tested commit each. Tick when committed:

- [x] 22, 23: labelled Close and Back to folder; Details card and headed tables (Inputs, Outputs, Runs)
- [x] 21: Copy to Private only (server refuses Public), Clone (read-only private copy), no Move in Public, Delete reason and called-by block, Edit checks this agent out, check-in dialog with dependencies
- [x] 25: Disable step / Enable step; Run from here on a disabled step starts at the next enabled one; Run this step only greyed out; every step action tested
- [x] 24: one-line command description under the name; field hints as text, ⓘ only for long help
- [x] 27: info notices close after about 6 s (hover or focus keeps them), errors stay with a labelled Close; one notice at a time (a newer one replaces it)
- [x] 26: Open program/file command (location, start in, parameters, timeout); Open file hidden from the library; library search by AA command names
- [x] 16: foldable menu sections (remembered, current always open)
- [x] 19, 20: Catch fields in AA's order; On error variable field with message and line
- [x] UI check at full width (item 17), 9 Nov 2026: Close, Details tables, Clone, Copy to Private, Edit = check out, Disable step and Run from here, notices, written help, AA-name search, menu folding
- [x] Full test suite (UI 358 tests, Java BUILD SUCCESSFUL), branch pushed

After that, with the user's OK: the per-command review (UI, behaviour, technical logic, reuse, easy upgrades).
