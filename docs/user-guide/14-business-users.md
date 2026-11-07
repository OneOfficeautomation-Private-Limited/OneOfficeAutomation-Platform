# For business users: tasks, starting agents and notifications

**Audience:** people who work alongside agents but do not build them: approvers, clerks who fill in forms, and team
members who start agents themselves from their desktop.

## Contents

1. [How you meet agents in your daily work](#1-how-you-meet-agents-in-your-daily-work)
2. [The Task inbox](#2-the-task-inbox)
3. [Fill in a form task](#3-fill-in-a-form-task)
4. [Approve or reject](#4-approve-or-reject)
5. [Take a task, or give it back](#5-take-a-task-or-give-it-back)
6. [Notices](#6-notices)
7. [The assistant in the notification area](#7-the-assistant-in-the-notification-area)
8. [Start an agent from the assistant](#8-start-an-agent-from-the-assistant)
9. [Notifications on your desktop](#9-notifications-on-your-desktop)
9a. [Suggest a process to automate](#9a-suggest-a-process-to-automate)
9b. [Use an app](#9b-use-an-app)
9c. [One CommandCenter on your phone](#9c-one-commandcenter-on-your-phone)
10. [If it goes wrong](#10-if-it-goes-wrong)
11. [Related guides](#related-guides)

---

## 1. How you meet agents in your daily work

| Situation | Where you see it |
| --- | --- |
| A workflow needs information from you | A **Fill in** task in your **Task inbox** |
| A workflow needs your decision | An **Approve** task in your Task inbox |
| A workflow tells you something | A **Notice** in your Task inbox (and, for email addresses, an email) |
| You want to start an agent yourself | **Run an agent** in the ONEOFFICEAUTOMATION assistant |
| An agent you started finishes or fails | A notification on your desktop from the assistant |

You need an account in One CommandCenter (ask your administrator). You see only what your role allows.

## 2. The Task inbox

1. Open One CommandCenter in your browser and sign in.
2. Choose **Task inbox** in the menu (under **Manage**), or click the **Tasks waiting for you** tile on **Home**.

**What you see:**

- the page title **Task inbox** and the line "Work that workflows assign to you or your role: forms to fill in,
  approvals and notices.";
- two tabs: **To do** and **Done**;
- a table with **Task** (title and description), **Kind** (**Fill in**, **Approve** or **Notice**), **Assigned to**,
  **Due** (marked **Overdue** in red when late) and **Created** (on **Done**: **Outcome**);
- **Search tasks**, and export of the list.

Tasks reach you when they are assigned to you (`user:…`) or to one of your roles or teams. If your administrator has
switched it on, a form or an approval given to you (or to your role) also arrives as an e-mail, at the address on your
account; tasks for everyone stay in the inbox only. The page refreshes itself every few seconds. When nothing is waiting it says "Nothing waiting for you. ✨".

Administrators also see a tick **Everyone's tasks** to look at all open tasks.

## 3. Fill in a form task

1. In **To do**, click a task of kind **Fill in**.
2. A window opens with the task title, its description, the due date ("Due …") and the form's fields. Required fields
   are marked *****; some fields may already hold a suggested value.
3. Fill in the fields. The form checks required fields, numbers (minimum and maximum), choices, dates and email
   addresses.
4. Optional: open **Workflow data** to see the details the workflow has gathered so far.
5. Click the form's button (usually **Submit**). The toast says "Done" and the task moves to **Done**.

Your answers go back to the workflow, which carries on with the next stage, for example an agent that books what you
entered.

## 4. Approve or reject

A task from a workflow shows a **stage bar** at the top: the workflow's stages in order, those before this task ticked
as done, this task's stage marked as current, and the rest still to come. You see where the request stands and what
happens after your part.


1. Click a task of kind **Approve**. **Workflow data** opens straight away so you can see what you are approving (for
   example the supplier, the amount and the invoice number).
2. Optionally type a **Comment** ("Comment (optional)"). It is kept with your decision.
3. Click **Approve** or **Reject**. The toast says "Done" or "Rejected".

What happens after a rejection was decided by whoever designed the workflow: it ends, carries on anyway, or goes back to
an earlier stage (for example back to the requester to correct the form). Every decision is recorded in Audit Trail
with your name.

### Approve from e-mail, Teams or your phone

When your administrator allows it, you can approve without opening One CommandCenter:

- **E-mail (Outlook or any mail program):** the e-mail about a new approval has an **Approve** link and a **Reject**
  link. They are made for you alone, so do not forward the e-mail.
- **Teams:** an approval given only to you is posted in your team's channel with **Approve** and **Reject** buttons.
  Tasks for a group show **Open the task**.
- **Your phone:** open your account menu (top right), choose **Phone notifications** and press **Turn on**. In the free
  **ntfy** app, subscribe to the server and topic shown. New tasks then appear on your phone, and approvals come with
  **Approve** and **Reject** buttons. **Send a test** checks it works. **New topic** replaces your topic if the phone
  is lost (the old topic stops working), and **Turn off** stops the notifications.

Each link or button opens a page that shows what the request is and asks you to confirm, with room for a comment.
Nothing is decided until you press **Approve** or **Reject** on that page. A link stops working after the time your
administrator set, after the task is decided, and when the task is no longer yours.

## 5. Take a task, or give it back

When a task is assigned to a role, several people see it. To avoid two people doing the same work:

1. Open the task and click **Take it**. The toast says "It's yours: others no longer see it as open". In **Assigned to**
   the task now shows your name with "(working on it)".
2. If you cannot finish it, open it again and click **Give back**. It returns to everyone in the role.

You can also complete a task without taking it first.

## 6. Notices

A **Notice** tells you that something happened, for example "Order PO-1001 approved". Open it and click
**Mark as read** to move it to **Done**.

When a workflow sends a notice to an email address instead of a user or role, it arrives as an ordinary email (once
email is set up in One CommandCenter).

## 7. The assistant in the notification area

The **ONEOFFICEAUTOMATION assistant** is a small Windows app that sits in the notification area (next to the clock).
It runs as you, so it can only do what you may do in One CommandCenter.

**Set it up once:**

1. In One CommandCenter, open the user menu (your name) > **API keys** and create a key. Copy it; it is shown once.
2. Start the assistant: your IT department may have put it in your Startup folder; otherwise run `ooa assistant` from
   the installation's `bin` folder (or ask IT for a shortcut).
3. In **Sign in to ONEOFFICEAUTOMATION**, enter the **One CommandCenter address** and **Your API key**, and press OK.
4. The ONEOFFICEAUTOMATION icon appears in the notification area. The key is kept protected for your Windows user only.

**Right-click the icon** for the menu:

| Entry | What it does |
| --- | --- |
| **Signed in as …** | Shows who you are |
| **Run an agent** | The agents you may run (those with a live production version) |
| **Task inbox** / **Task inbox (n)** | Opens the Task inbox; n is the number of open tasks for you |
| **My runs** | Opens your runs in One CommandCenter |
| **Open One CommandCenter** | Opens the console |
| **Refresh** | Reloads the agent list and tasks now |
| **Sign out** | Forgets the address and key |

Hover the icon to see how many tasks are waiting ("2 task(s) waiting").

## 8. Start an agent from the assistant

1. Right-click the icon > **Run an agent** and choose the agent.
2. The assistant asks for each input in turn. The question shows the input's name and description, and its default if
   it has one.
   - For a password or other secret, the question says "A vault reference: folder/credential/attribute". Type the
     reference your team gave you (for example `finance/sap/password`), never the secret itself.
   - Leave an answer empty to use the default. **Cancel** stops without starting anything.
3. The agent starts on your default runner, as you, in attended mode (it may show you forms or questions). A
   notification says **Agent started** with the run id.
4. When it ends you get **Agent finished**, **Agent failed** or **Agent stopped**.

> **Tip:** Stay away from mouse and keyboard while an agent works in desktop applications on your computer, unless the
> agent asks you for something.

## 9. Notifications on your desktop

The assistant checks every 30 seconds and shows a Windows notification when:

- **New task**: a task arrives in your inbox (with its title);
- **Agent finished**, **Agent failed** or **Agent stopped**: one of your runs ends.

Click the notification (or the icon) to open the matching page in One CommandCenter: the Task inbox, or the runs.

Your administrator can also have One CommandCenter e-mail you when a run you started fails (with the agent, the
reason and a link to the run).

## 9a. Suggest a process to automate

If part of your work is repetitive, suggest it: in One CommandCenter open **Build > Automation ideas** and choose
**Suggest an idea**. Say what should be automated, how it is done today, your department, roughly how many cases a
month and how many minutes each takes by hand. Everyone signed in can see the ideas and vote for the ones they would
use (vote again to take a vote back).

The automation team assesses each idea: how rule-based, structured and stable the process is, and how hard it is to
build. That gives a **score** from 0 to 100, which with the votes decides what is built first. The idea then moves
through **Assessing**, **Approved**, **Building** and **Live** (or **Rejected** or **On hold**, with the team's notes).
Once it is live, the list shows the hours the agent actually saved over the last 30 days next to the estimate.

You can change or delete your own idea while it is still **Submitted**.

## 9b. Use an app

Your automation team may give you **apps**: simple pages with fields and buttons, in **Build > Apps**. Open one, fill
in the fields and press its button. A button may start an agent (the app waits and shows what the agent gave back),
save a record or add a work item for an agent to pick up. A button stays grey until its required fields are filled.

You do not need the right to start agents yourself: the app does what it was built to do on behalf of the person who
published it, and the audit trail records that you pressed the button.

## 9c. One CommandCenter on your phone

One CommandCenter works as an app on phones and tablets. Open its address in the phone's browser (Chrome or Edge on
Android, Safari on iPhone and iPad), sign in, then:

- **Android**: the browser offers **Install app** (or ⋮ > **Add to Home screen**);
- **iPhone and iPad**: Share > **Add to Home Screen**.

The app opens full screen at your **Task inbox**, so you can approve, reject and fill in tasks; the menu (the narrow
column of icons on the left; **Expand menu** opens it) leads to **Apps** and, if you may, to starting agents. Pressing and holding the app's icon offers Task inbox
and Apps directly. On a phone the menu folds away once you pick a page.

The app needs One CommandCenter to be reachable over **https://** from the phone (on the company network, a VPN, or a
public address your administrator sets up). Nothing from One CommandCenter is kept on the phone; without a connection
the app says so. For notices on the phone when a task arrives, see [Notifications on your desktop](#9-notifications-on-your-desktop)
and ask your administrator about push notices and approval links.

## 10. If it goes wrong

| What you see | What to do |
| --- | --- |
| The Task inbox is empty but you expected a task | Check **Done**, or ask whether the task was assigned to your user, role or team. Someone else may have taken it. |
| The form will not submit | A required field is empty or a value is not allowed (a number out of range, a bad date or email address). The message says which. |
| **Take it** fails | Someone else took the task a moment before you. Refresh the inbox. |
| "Not started: <input> is required" | Run the agent again and give that input. |
| "Not started" with another reason | You may not run that agent, or you have no default runner. Ask your administrator. |
| **Run an agent** shows "(no agents you may run)" | No agent with a live production version is shared with you. Ask the agent's owner. |
| "The assistant needs a desktop with a notification area (Windows)." | The assistant runs only on Windows desktops; use One CommandCenter in the browser. |
| The assistant stops working after a while | Your API key may have expired or been revoked (your administrator may limit how long keys last). **Sign out**, create a new key, and sign in again. |

## Related guides

- [User guide overview](00-README.md)
- [Publishing to One CommandCenter](15-publishing-to-command-center.md)
- [AI in agents](13-ai-in-agents.md) (validating extracted values)
- [Getting started](01-getting-started.md)
- [Operators guide](05-operators-guide.md)
