# Child agents: one agent running another

**Audience:** agent builders who want to split a large job into smaller, reusable agents, such as a "log in to the
ERP" agent or a "book one invoice" agent that several parent agents use.

## Contents

1. [Why use child agents](#1-why-use-child-agents)
2. [Prepare the child agent](#2-prepare-the-child-agent)
3. [Add a Run Agent step](#3-add-a-run-agent-step)
4. [Give inputs and keep outputs](#4-give-inputs-and-keep-outputs)
5. [The other settings](#5-the-other-settings)
6. [Open the child, and find the way back](#6-open-the-child-and-find-the-way-back)
7. [Which agents run this one: the "Run by" note](#7-which-agents-run-this-one-the-run-by-note)
8. [Test child agents](#8-test-child-agents)
9. [Child agents in One CommandCenter](#9-child-agents-in-one-command-center)
10. [If it goes wrong](#10-if-it-goes-wrong)
11. [Related guides](#related-guides)

---

## 1. Why use child agents

- **Reuse:** build "Sign in to the portal" once and run it from every agent that needs it.
- **Clarity:** a parent agent of ten steps that each run a child is easier to read than one of two hundred steps.
- **Testing:** each child can be run and tested on its own.
- **Team work:** different people can own different children.

A child agent runs with its own variables. It sees only the inputs it is given, and the parent sees only the outputs it
returns.

## 2. Prepare the child agent

1. Create the child agent as usual (see [Building agents](03-studio-building-agents.md)).
2. In **Agent settings**, give it a clear **Agent id** (for example `portal_sign_in`) and a **Description**. Parents
   choose it by its id.
3. In **Variables**, decide what it needs and what it gives back:
   - tick **Use as input** for each value the parent must give (mark it **Required** when it cannot run without it);
   - tick **Use as output** for each result the parent should get;
   - write a **Description** for each: it appears under the field in the parent's settings.
4. Save it.

> **Tip:** Pass a secret to a child as a **Credential** input. The parent hands over its own Credential variable, so the
> secret never appears in either agent.

## 3. Add a Run Agent step

1. Open the parent agent.
2. In **Actions > Logic and flow**, add **Run Agent** where the child should run.
3. In **Action settings**, under **Agent to run**, open the list. It shows every other agent of your workspace as
   "name — path". Choose the child.
   - **↻** refreshes the list after you created or renamed agents.
   - An agent id that is not in the workspace stays listed as "(not in this workspace)".

**What you see:** the step card shows the child's name. The **Input values** and **Save outputs to** fields fill with
the child's variables.

## 4. Give inputs and keep outputs

**Input values** shows one row per input variable of the child, with its type and description:

- type text or a number, or a `$variable` of the parent (use **{x}** or type `$` to pick one);
- a Number input turns `42` into a number and a Boolean input turns `true`/`false` into true or false;
- the placeholder shows the child's default ("default: …"), or says **required**;
- for a Credential input, give a Credential variable of the parent, for example `$erp_password`;
- leave a row empty to use the child's default.

**Save outputs to** shows one row per output of the child:

- choose a variable of the parent with a matching type,
- or **+ New variable "<name>"**, which creates a parent variable of the right type,
- or leave **(do not keep)**.

The step also has its own outputs, under **Outputs** in Action settings:

| Output | Holds |
| --- | --- |
| **outputs** | A Dictionary with all of the child's outputs (of the last run when repeated). |
| **status** | `success` or `failed`. |
| **error** | The child's error (code, message, stepId) when it failed and **Fail on error** is off; empty otherwise. |
| **runs** | How many times the child ran. |

If the child has no inputs or outputs, the fields say "The agent has no input variables." or "The agent has no output
variables."

## 5. The other settings

Run Agent has more settings under **More options** (labels as Studio shows them):

| Setting | What it does |
| --- | --- |
| **Version** | Which version runs in One CommandCenter: a version number, or `production` (the live production version, the default). |
| **Timeout seconds** | Stops the child with `CHILD.TIMEOUT` when one run takes longer; empty means no limit. |
| **Same session** | Shares the parent's open sessions (workbooks, browsers, connections) with the child; sessions the child opens stay open for the parent. |
| **Fail on error** | On by default: when the child fails, this step fails. Turn it off to carry on and read **status** and **error** yourself. |
| **Repeat** / **Repeat delay seconds** | Run the child several times in a row, with a pause between runs. |

The usual **Error handling** settings (retries, continue on error) apply too. A child's error codes start with `CHILD.`;
a Catch for `CHILD.*` handles them.

## 6. Open the child, and find the way back

When you work on a parent you often want to look at the child it calls. There are two ways to open it:

- In **Action settings** of the Run Agent step, click **Open this agent** under **Agent to run**.
- In the step menu (⋮ or right-click), choose **Open the agent it runs**.

**What you see:** the child opens. The breadcrumb at the top shows the way back to the parent, at the step you came from:

```
Automation / Invoice run › line 7 / Book one invoice
```

1. Click **Invoice run › line 7** to go back. The parent opens with that Run Agent step selected and in view.
2. If you went down several levels (parent → child → grandchild), each level has its own link.

Before switching agents, Studio asks "Discard unsaved changes to the open agent?" if you have unsaved changes. Save first.

## 7. Which agents run this one: the "Run by" note

When you open an agent that other agents of your workspace run, the breadcrumb ends with a note:

```
Automation / Shared / Book one invoice · Run by Invoice run, Month end close
```

Each name is a link. Clicking it opens that parent at its (first) Run Agent step for this agent.

The **Dependencies** tab gives the same information in more detail: **Child agents and agent tools** (the agents this one
calls) and **Used by** (the agents that call it). Click a row to open it.

> **Tip:** Before you change a child's inputs or outputs, look at **Run by**. Every parent that uses the child may need
> its Run Agent step updated.

## 8. Test child agents

1. **Test the child on its own first.** Open it, fill in its inputs in the Run tab, and click **Run**. Check the outputs
   in the result.
2. **Then test the parent.** Run it; the Run log shows the child's steps too.
3. **Debug into the child.** Set a breakpoint on the Run Agent step, click **Debug**, then **Step into** (F11). The run
   stops at the child's first step. The debugger panel says "in child agent <id>" and the **Call stack** lists the
   child above the parent. **Step out** (Shift+F11) finishes the child and stops back in the parent.
4. **Run this step only** on the Run Agent step runs just the child with the inputs the step gives, using the values the
   parent's last run had at that step. See [Running and debugging](04-studio-running-and-debugging.md).

## 9. Child agents in One CommandCenter

- When a parent runs in One CommandCenter, it calls the child **by its agent id**, in the version you chose
  (`production` by default).
- So **check in every child agent** as well as the parent, and make sure each has a live version (tick **Make this
  the live version** at check-in, or set it in the repository). The **Dependencies** tab of the parent lists them and reminds
  you: "Agent check-in child agents too before running this agent from Command Center."
- If you duplicate an agent in the Explorer, the copy gets a new agent id so it does not replace the original as a
  child.

## 10. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| "Choose the agent to run first." | **Agent to run** is empty. |
| "Agent … is not in this workspace." | The child was deleted, renamed with a new id, or is only in One CommandCenter. Check it out or pick another agent. |
| The list says "No other agents in this workspace" | Create the child first, then click **↻**. |
| **Input values** shows "Loading the agent's variables…" for a long time | Click **↻** next to **Agent to run**. |
| The parent fails with a `CHILD.` error | Open the child and run it with the same inputs to see where it fails. |
| A new output of the child is missing from **Save outputs to** | Save the child, then click **↻** in the parent. |
| In One CommandCenter the parent cannot find the child | Check in the child, and make sure it has a live production version (or set **Version**). |

## Related guides

- [Building agents](03-studio-building-agents.md)
- [Running and debugging](04-studio-running-and-debugging.md)
- [Studio basics](02-studio-basics.md)
- [Publishing to Command Center](15-publishing-to-command-center.md)
