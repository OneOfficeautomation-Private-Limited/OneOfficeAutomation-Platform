# Running and debugging agents in One Agent Studio

**Audience:** agent builders who want to test their agents in Studio, follow a run step by step, find out why a step
fails, and fix it quickly.

## Contents

1. [Before you run: Validate and Problems](#1-before-you-run-validate-and-problems)
2. [Run an agent with inputs](#2-run-an-agent-with-inputs)
3. [Follow the run: Run dock and Run window](#3-follow-the-run-run-dock-and-run-window)
4. [Pause, resume and stop](#4-pause-resume-and-stop)
5. [Read the result, the log and the variables](#5-read-the-result-the-log-and-the-variables)
6. [Debug with breakpoints](#6-debug-with-breakpoints)
7. [Step into, over and out](#7-step-into-over-and-out)
8. [Change variables while stopped](#8-change-variables-while-stopped)
9. [Run from here and Run this step only](#9-run-from-here-and-run-this-step-only)
10. [Local triggers](#10-local-triggers)
11. [If it goes wrong](#11-if-it-goes-wrong)
12. [Related guides](#related-guides)

---

## 1. Before you run: Validate and Problems

Studio checks the agent in the background half a second after each change. You see the result in three places:

- the canvas toolbar: "12 steps · 2 problems";
- a red **!n** badge on each step with problems;
- the **Problems** tab of the Run dock, with a count.

To check on demand, click **Validate** in the top bar. The Run dock opens on **Problems** and the toast says
"No problems found" or "n problem(s) found".

Each problem shows the step id in brackets (click it to jump to the step), the message, and often a **Fix:** line with a
suggestion. Problems that are not about one step show `[bot]` or a path.

> **Tip:** An agent with problems can be saved, but it cannot be run. Run refuses it with "n problem(s): fix them before
> running" and opens the Problems tab.

## 2. Run an agent with inputs

1. Open the agent.
2. In the Run dock, open the **Run** tab. On the left, under **Inputs**, there is one field per input variable, with its
   type and description. Required inputs are marked *****.
   - Leave a field empty to use the variable's default (shown as "default: …").
   - A variable with allowed values shows a drop-down.
   - A **Credential** input takes a vault reference, `folder/credential/attribute`, never the secret itself.
   - Lists, dictionaries and tables have their own editors.
3. Click **Run agent** in the dock, or **Run** in the top bar.

**What you see:**

- **Run** and **Debug** are greyed out while the run lasts, and a thin progress bar runs under the dock heading.
- The step that is running is highlighted on the canvas, and the canvas scrolls to keep it in view. Finished steps are
  marked done; a failed step is marked as failed.
- The **Run window** appears (section 3).
- At the end, a toast: "Run success in 1234 ms" (red when it did not succeed).

> **Tip:** Studio can run up to four runs at once. A run started in Studio may last up to an hour (a debug run, a day).

## 3. Follow the run: Run dock and Run window

### 3.1 The Run dock

While the run lasts, the right side of the **Run** tab shows:

- the state (**Starting**, **Running**, **Paused**), the seconds so far and "n of m steps";
- a progress bar;
- **Now:** the action that is running, with its step id (click it to select the step);
- **Pause** / **Resume** and **Stop**;
- **Live log**: the last eight events.

With the dock set to **Mini bar**, the bar shows the same in one line, with **Pause**, **Stop** and **Expand**.

### 3.2 The Run window

The floating **Run window** stays on top of the canvas:

- a coloured dot, the agent name, the state and "finished/total";
- the elapsed time and a progress bar;
- **Line n** and the name of the current step (click it to show the step), its note, and the window it works in;
- buttons: **Pause** / **Resume** and **Stop**; when the debugger has stopped: **Continue**, **Step**, **Step into** and
  **Stop**;
- **–** minimises it to its title bar; drag the title bar to move it. Studio remembers where you put it.

After the run it stays with the result (**Finished**, **Failed** or **Stopped**, and the error) until you click **Close**
(✕) or start the next run.

On Windows, a small run window also appears on the desktop, on top of the applications your agent brings to the front,
so you can watch the agent while it works in other programs. It is not shown for debug runs.

## 4. Pause, resume and stop

- **Pause** waits for the current step to finish, then holds the run ("Pausing after the current step").
- **Resume** carries on.
- **Stop** ends the run ("Stopping the run"). **Finally** sections still run. The result is **stopped**.

An agent can also pause itself with the **Pause agent** action, for example to let you check something; resume it from
Studio (or One Command Center) or let its timeout expire.

## 5. Read the result, the log and the variables

When the run ends:

- **Run** tab, right side: the status (**success**, **failed** or **stopped**), the time and the number of steps run.
  When it failed, a red box shows the **error code** and message, "At step s7" (click to go there) and a **Fix:** line.
  Below, **Outputs** shows the output variables as JSON.
- **Run log** tab: every event with the time since the start ("+250ms"): run started, each step done with its duration,
  **FAILED** lines with code and message, **retry** lines, variables set (`name = value`), **log** lines from
  *Log message* steps, and run finished. Click a step id to jump to it.
- **Variables** tab: a table of each variable's **Last value**, plus **Outputs**. Credentials always show as `****`.
- The step that failed stays marked on the canvas.

> **Tip:** Use **Log message** steps (with `${…}` text) to write what the agent is doing; they appear in the Run log here
> and in One Command Center's run details.

## 6. Debug with breakpoints

A **breakpoint** stops the run just before a step, so you can look at the variables.

**Set breakpoints:**

- click a step's **line number** (it gets a breakpoint mark), or
- select the step and press **F9**, or
- choose **Toggle breakpoint** in the step menu.

The toast says "Breakpoint set on s4" or "Breakpoint cleared". Breakpoints are saved with the agent.

**Start debugging:**

1. Fill in the inputs in the Run tab, if any.
2. Click **Debug** in the top bar.
   - With breakpoints: "Debugging: the run stops at n breakpoint(s)."
   - Without breakpoints: "Debugging: the run stops before the first step."
3. When the run stops, the step is highlighted as paused, the canvas scrolls to it, and the Run dock shows the
   **debugger panel**:
   - **At breakpoint** or **Paused**, and "Before <action> [step id]";
   - when the stop is inside a child agent: "in child agent <id>";
   - the buttons **Continue**, **Step over**, **Step into**, **Step out** and **Stop**;
   - **Call stack**: the agent at the top, then the agents that called it;
   - **Variables (edit and press Set)**.

You can add or clear breakpoints while debugging; the run picks up the change.

## 7. Step into, over and out

| Button | Key | What it does |
| --- | --- | --- |
| **Continue** | F5 | Runs on to the next breakpoint, or to the end. |
| **Step over** (Run window: **Step**) | F10 | Runs the current step (a whole block, or a whole child agent) and stops before the next one. |
| **Step into** | F11 | Goes into the block or child agent and stops at its first step. |
| **Step out** | Shift+F11 | Finishes the current block or child agent and stops after it. The Run window has no button for it; use the dock or the key. |
| **Stop** | | Ends the run. |

## 8. Change variables while stopped

In the debugger panel, under **Variables (edit and press Set)**:

1. Change the value in the box. Text is taken as typed for String variables; other types take JSON (`42`, `true`,
   `["a"]`).
2. Press **Set** (or Enter). The toast says "Changed $name".
3. Continue or step; the agent goes on with the new value.

Credential variables cannot be shown or changed.

## 9. Run from here and Run this step only

These two entries of the step menu save time when you work on one part of a long agent.

| Menu entry | What runs |
| --- | --- |
| **Run from here** | This step and everything after it. The steps before it are skipped. |
| **Run from here with default values** | The same, but variables start from their defaults instead of earlier values. |
| **Run this step only** | Just this step (for a block: everything inside it), then the run ends successfully. |

**Where the variables come from.** Skipped steps never set their variables. So that the step has realistic values to work
with, Studio remembers each agent's last run in Studio:

- If that run reached this step, variables start with **the values they had at this step**. Toast: "Variables start with
  the values the last run had at this step".
- If it ended before reaching the step, they start with **the values it ended with**. Toast: "Variables start with the
  values the last run ended with (it did not reach this step)".
- If there was no earlier run (or you chose **…with default values**), they keep their inputs and defaults.

Credentials and secret values are never remembered.

**How blocks behave.** When the start step is inside a block, the block is entered without being judged: an If's
condition is not checked, a Loop does one pass without setting its item or counter, and a Try keeps its Catch and
Finally. Then the run carries on normally.

> **Tip:** A typical loop for fixing a failing step: run the whole agent once, change the step, then **Run this step
> only** on it. It starts with the values the last run had there, so you see the effect at once.

The entries are greyed out while a run is going on ("A run is already in progress.").

## 10. Local triggers

The **Triggers** panel (lightning icon) starts agents automatically while Studio is running, for testing:

1. Under **New trigger**, give a **Name**, choose the **Agent** and **When**:
   - **File arrives in a folder**: **Folder** and **File pattern** (for example `*.pdf`);
   - **Schedule**: **Every (minutes)** or **Or daily at** `HH:mm`;
   - **Local webhook**: Studio shows the secret once ("Webhook secret (copy it now):"). Callers POST to
     `/api/hooks/<id>` with the header `X-OOA-Secret`.
2. Click **Add trigger**.
3. Each trigger has an **On** tick, **Run now** (test it) and **Delete**. **Recent activity** lists what fired.

Triggers for production belong in One Command Center (see [Publishing to Command Center](15-publishing-to-command-center.md)).

## 11. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| The run does not start; "FLOW.INVALID: n problem(s): fix them before running" | Open **Problems**, fix each one. |
| "Four runs are already in progress" | Wait for a run to finish or stop one. |
| "A run is already in progress." | Run from here and Run this step only wait until the current run ends. |
| "VAULT.NOT_FOUND: Credential '…' not found" | For local runs set the environment variable named in the fix (`OOA_SECRET_…`) before starting ONEOFFICEAUTOMATION, or run from One Command Center. |
| The run window shows **Failed** with a code | Read the code, message and **Fix:** line; click the step id. Check the Run log for the lines before the failure. |
| A step waits and then fails with a timeout | The element, window or file did not appear in time. Raise **Timeout (seconds)** or the action's wait, or add a **Wait for …** step. |
| "Unknown or expired run" | Studio forgets finished runs after ten minutes, or the server was restarted. Run again. |
| Run from here gives unexpected values | They come from the last run; use **Run from here with default values**, or run the whole agent once. |
| The debugger does not stop at a breakpoint | The step is disabled, or not reached (inside an If branch that is not taken). Check the line number has the breakpoint mark. |

## Related guides

- [Studio basics](02-studio-basics.md)
- [Building agents](03-studio-building-agents.md)
- [Child agents](09-child-agents.md)
- [Web and desktop automation](10-web-and-desktop-automation.md)
- [Publishing to Command Center](15-publishing-to-command-center.md)
