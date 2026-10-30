# Web and desktop automation

**Audience:** agent builders who automate web applications in a browser and Windows desktop applications: filling in
forms, clicking buttons, reading values from screens.

## Contents

1. [How UI automation works in ONEOFFICEAUTOMATION](#1-how-ui-automation-works-in-oneofficeautomation)
2. [The Browser Connector (Edge, Chrome, Firefox)](#2-the-browser-connector-edge-chrome-firefox)
3. [Open a browser](#3-open-a-browser)
4. [Choose the window a step works in](#4-choose-the-window-a-step-works-in)
5. [Capture a web element](#5-capture-a-web-element)
6. [Captured objects versus selectors](#6-captured-objects-versus-selectors)
7. [Edit a captured object](#7-edit-a-captured-object)
8. [Record](#8-record)
9. [The browser actions](#9-the-browser-actions)
10. [Desktop applications](#10-desktop-applications)
11. [Images and OCR](#11-images-and-ocr)
12. [Interrupt handlers for unexpected pop-ups](#12-interrupt-handlers-for-unexpected-pop-ups)
13. [Good practice](#13-good-practice)
14. [If it goes wrong](#14-if-it-goes-wrong)
15. [Related guides](#related-guides)

---

## 1. How UI automation works in ONEOFFICEAUTOMATION

UI steps find the thing they act on (a button, a text box, a table cell) by a **captured object**: a set of the
element's properties, such as its HTML id, its label, its text and its position in the page. You tick the properties
that identify it reliably, and choose how each must match. This works the same for web pages and Windows applications.

You get captured objects in three ways:

| Button (canvas toolbar) | What it does |
| --- | --- |
| **Record** | You work in the browser or application; Studio adds a step for each click, typing and selection. |
| **Capture** | You click one element in a web page; Studio adds a **Capture: element action** step for it. |
| **Capture desktop** | You hover a control in a Windows application and press **Ctrl+Shift+C**; Studio adds a **Desktop object action** step. |

In a step's settings, the **Capture** button next to the element field does the same for an existing step.

## 2. The Browser Connector (Edge, Chrome, Firefox)

Without extra set-up, web actions use a browser the agent starts itself. With the **ONEOFFICEAUTOMATION Browser
Connector** (a browser extension), agents and Studio also work in **your own browser windows**: the tabs you already
have open, signed in as you.

**Install once per Windows user** (or ask IT to push it with browser policy):

1. From a normal PowerShell or Command Prompt window (not from inside another app), run
   `ooa browser-connector install`.
2. Edge or Chrome: open `edge://extensions` or `chrome://extensions`, turn on **Developer mode**, choose
   **Load unpacked** and pick the `browser-extension` folder of the installation.
3. Firefox (140 or later): open `about:debugging#/runtime/this-firefox`, choose **Load Temporary Add-on**, pick
   `manifest.json` in the `browser-extension-firefox` folder, then in `about:addons` > OOA Connector > **Permissions**
   allow access to all websites. A temporary add-on is gone after Firefox restarts.
4. The extension's toolbar icon should say **Connected**. `ooa browser-connector status` lists connected browsers.

With the connector:

- **Capture** and **Record** list your open tabs, grouped by browser ("Microsoft Edge (3)", "Google Chrome (1)").
- **Open browser** opens a new window in your own Edge, Chrome or Firefox (engine *auto*), which stays open after the
  run; **Close browser** closes it.
- Steps find a tab by title and address in any connected browser.

Limits to know: pages that accept only real keyboard and mouse input may ignore simulated events; on pages that forbid
running scripts, *Run JavaScript* briefly shows the browser's "being debugged" bar (in Firefox it stops with a message
instead).

## 3. Open a browser

Add **Open browser** (group **Browser**). Its settings start with **Run mode**:

| Run mode | Use it |
| --- | --- |
| **Watch while building** | Edge or Chrome on the desktop; it stays open after the run so you can look at the page. |
| **Unattended** | The built-in browser without a window, closed when the agent ends. For Command Center and scheduled runs. |
| **Custom** | Set **Engine**, **Headless** and **Keep open** yourself under More options. |

Then:

- **Open in**: *New window* (default), *New tab* next to the chosen one, or *Existing tab*.
- **Window**: the tab to open next to or in (for New tab and Existing tab).
- **Browser**: Microsoft Edge (the default), Google Chrome, Chromium or Firefox.
- **Address (URL)**: the page to open.
- More options: **Download folder**, **Session**, **Wait up to (ms)** and the engine settings.

**Engine** choices: *auto* (your own browser through the connector when it is connected), *My own browser (extension)*,
or *Built-in automation browser*.

Other page actions: **Go to URL**, **Back, forward or refresh**, **Switch tab**, **Get URL and title**,
**Close browser** (**What**: what this agent opened, the current tab, the whole window, or every browser window),
**Attach to open browser** and **Start browser for automation** (for browsers started with a debugging port).

## 4. Choose the window a step works in

Every web element action has a **Window** field. It decides where the step looks:

- **Browser / Application / Variable** buttons choose the kind of window.
- **Working in** tells you in words what the step will use, or "the browser's current tab (no window chosen)".
- **Window**: "Pick from open windows…" lists the open tabs grouped by browser; **↻** refreshes it.
- **Browser title**: type a title; `*` and `?` are wildcards. The match can be *wildcard*, *contains*, *equals* or
  *regex*; tick **Case sensitive** if needed.
- **Browser**: any browser, Edge or Chrome.
- **Address must**: text the tab's address must contain, such as `example.com/invoices`.
- **Link of web page**: the address at capture time, for your information only (the page may move on while the agent
  works, so it is not used to match).

When you capture an element, Studio fills the window for you and keeps it in a **window variable** (`Window_1`,
`Window_2` …) under **Variables**. Every step in the same window uses that variable, so when the page title changes you
fix it once, in the variable.

## 5. Capture a web element

1. Open the page in a browser first, or let Studio open it.
2. Select the step after which the new step should go.
3. Click **Capture** in the canvas toolbar.
4. Studio asks **Capture in which window?** and lists the open browser tabs. Click one, or **Open a new page…** and type
   the address. (When the step already names an open window, Studio uses it straight away.)
5. In the browser, move over the element; it is outlined. **Click** it. **Esc** cancels.
6. Studio adds a **Capture: element action** step with an action that suits the element: **Set text** for text boxes,
   **Select item by text** for drop-downs, **Check** for check boxes, **Click** for the rest. The toast says
   "Captured <tag> with n properties".

**Capture: element action** has one **Action to take on the element**. The actions that suit the captured control type
come first ("For this text box"), the rest are under **Other actions**: click, double click, right click, set text,
append text, clear, press a key, get text, get value, get attribute, select item by text/value/index, check, uncheck,
is visible, is enabled, wait for it to appear or disappear, get table, get cell text by index, upload file, drag to,
screenshot and more. Choosing an action that returns something creates an outcome variable at once ("The outcome goes to
$element_text").

**Set text** fields let you choose where the text comes from: **Credential** (a Credential variable, for passwords),
**Variable**, **Text** (with `${…}` in it) or **Calculation**.

**System time-out** controls waiting: **Basic** shows **Wait for the element (milliseconds)**; **Advanced** adds
**Wait for the page first (milliseconds)** and **When the wait runs out** (stop with an error, or skip the step and go
on).

## 6. Captured objects versus selectors

| | Captured object | Selector |
| --- | --- | --- |
| What it is | Several properties, each with its own match rule, plus optional anchor and preview picture | One CSS or XPath text such as `#submit` |
| How you get it | **Capture**, **Record** | Type it, or **Pick** |
| Strength | Survives small page changes; self-heals; easy to read | Short; good for "the n-th row" or selectors built from variables |

To switch a step, click **Use a selector instead** under the element field (or **Capture the element instead**).

**Selectors to objects** (canvas toolbar) converts an existing agent: it runs the agent once (with the inputs of the Run
panel) while every element found by a selector is captured, then puts captured objects in place of the selectors. A
selector stays when it contains a variable, picks the n-th match, lists alternatives, was not reached in the run, or met
different elements. The toast says how many steps changed; **Save** to keep them.

## 7. Edit a captured object

The **Captured object** field of a web or desktop step shows:

- **Web** or **Desktop**, the page or window it came from, **Recapture** and (web) **Test**;
- **Main** and **Anchor** tabs;
- a **Preview** picture of the element;
- **Object properties**: "n selected of m". Each row has a tick (search by this property), the **Name**, the **Value**
  and the **Match**: *equals*, *not equals*, *contains*, *starts with*, *ends with*, *wildcard (\* ?)* or
  *regular expression*. **Show all properties** lists the rest; **Find a property** filters them; **+ Add property**
  adds your own (for example a `data-` attribute). Values may use `$variables`.
- **Search by**: *Ticked properties*, *CSS selector*, or *XPath (DOM, then full)*.
- **Which match**: *First*, *Last* or *Number…* when several elements match.
- **Ignore case** and **Self-heal** (try the captured CSS selector and XPath when the search finds nothing).
- "Inside iframe:" when the element is in a frame.
- **Page (used when the step switches to the captured page)**: address and title with their match rules.

**Test** opens the page and outlines every match, the one the step uses in green. "Nothing matches" means you should
loosen a property (contains, wildcard) or recapture; several matches mean you should tick another property, set
**Which match**, or add an anchor.

**Anchor** helps when the element has nothing unique about it, such as one of many identical "Edit" links. Click
**Capture anchor** and click a nearby element that is easy to find (the field's label, a heading). Studio records how far
the element is from the anchor; when the run cannot find the element, or finds several, it picks the one nearest that
spot. **Recapture anchor** and **Remove anchor** change it.

## 8. Record

1. Select where the recorded steps should go.
2. Click **Record**. **Record in which window?** offers:
   - your open browser tabs (through the Browser Connector);
   - **Desktop application…** to record in Windows programs;
   - **Open a new page…** to start a new browser at an address.
3. Work as usual: click, type, choose items. A **Recording** dialog counts the actions ("Recording actions (5)…").
4. Finish with **Stop recording** on the page (desktop: on the bar at the top right of the screen). **Cancel** in Studio
   throws the recording away.
5. Studio inserts the steps: "Inserted 7 recorded steps and created 3 variables: $Window_1, $Text_1, $Text_2".

Every recorded step has its full captured object and its window. What you typed becomes an **input variable**
(`Text_1`, `Text_2` …, choices `Choice_1` …) with the typed value as its default and a description naming the field, so
you can pass other values in later. Typing into a password field creates a `password` **Credential** input instead.

> **Tip:** Agents recorded with an older version may have long variable names made from page titles. Studio offers to
> rename them ("Rename to Window_1, Text_1, Choice_1 …?"); **Rename** does it everywhere, **Not now** leaves them.

## 9. The browser actions

Besides **Capture: element action**, the **Browser** group has single-purpose actions that take a captured object or a
selector: **Click element**, **Set text**, **Select item**, **Get text**, **Get attribute**, **Wait for element**,
**Extract table** (a whole HTML table into a Table variable), **Extract list** (repeated items, following "next" links
across pages), **Download file** (by clicking), **Take screenshot**, **Run JavaScript**, **Call JavaScript function**,
**Get page source**, **Download file from address** and **Find broken links**.

Conditions for **If** and **Wait for condition**: **Element exists**, **Element is visible**, **Element text is** and
**Page contains text**.

For a page you do not want to script step by step, the **Web goal agent** works towards a goal you describe (see
[AI in agents](13-ai-in-agents.md)).

## 10. Desktop applications

### 10.1 Capture a control

1. Start the application and bring the window you need to the front.
2. In Studio, select where the step should go and click **Capture desktop**.
3. Hover over the control: a red frame follows the pointer. Press **Ctrl+Shift+C**.
4. Studio adds a **Desktop object action** step ("Captured Button: Save") with a suitable action: **Set text** for edit
   boxes, **Select item** for lists and combo boxes, **Check** for check boxes, **Click** otherwise.

The captured object has the UI Automation properties (Name, Automation ID, Class name, Control type, Path, XPath, Index,
position and more) with the same tick-and-match table, **Which match**, **Ignore case** and **Anchor** as web objects.

Its **Window** section holds:

- **Window**: *The window with this title*, *The window in front when the step runs*, *The desktop* or *The taskbar*;
- **Title** with a match rule, and **Process**;
- **Resize window**: set the window to the captured size before looking for the control, so positions match;
- **Use one window for all steps in it**: keeps the window in one variable (`window_<program>`), so a changed title is
  set once. **Use a window of this step only** undoes it for one step.

### 10.2 Desktop object action

**Action to take on the control** lists the actions that suit the control type first: **Click**, **Set text**,
**Get text**, **Select item**, **Expand**, **Collapse**, **Check**, **Uncheck**, **Toggle**, **Press (invoke)**,
**Get table**, **Get all children**, **Get all items**, **Wait for it to appear** and others. Click actions offer the
click type and method; Set text offers **Clear first**, the typing method and the pause between keys.

**Run in background** uses UI Automation only (no mouse, keyboard or window activation), so the agent can work while the
window is behind others.

Other actions in **Capture / Desktop objects**: **Find desktop objects**, **Get desktop object properties**,
**Wait for desktop object**, **Get desktop table**, and for older programs **Find legacy (MSAA) objects** and
**Legacy (MSAA) object action**. Java programs (Swing/AWT) have their own group, **Java applications**.

**Older Windows programs (MSAA).** Some older programs (owner-drawn Win32, VB6, Delphi, and many ERP or terminal front
ends) describe their controls better through MSAA than through UI Automation. Try these when Capture finds nothing
useful:

1. **Find legacy (MSAA) objects** lists the controls of a window: name, role, value, default action, state, position,
   and a path such as `0.3.1`.
2. **Legacy (MSAA) object action** finds a control by **Name** and **Role** (with **Index** when several match) or by
   that **Path**, then does its **default action** (press, check, open as the program defines it), sets or reads its
   value, selects it, or reads its properties.

**Java programs (Swing and AWT).** The runner loads a small helper into the Java program (the Java Attach API) and
reads the program's own accessibility information, as screen readers do. The Java Access Bridge need not be switched
on.

| Action | What it does |
| --- | --- |
| **Get Java applications** | Lists the Java programs with windows, and their window titles |
| **Find Java objects** | Lists the controls of a window: name, role, text, state, actions, position and path |
| **Java object action** | Presses a control, sets or reads its text, gives it the focus, or reads its properties |

The program must run as the runner's Windows user, on Java 8 or later, and must not be started with
`-XX:-EnableDynamicAgentLoading`; otherwise the step fails and says so. The helper answers only on 127.0.0.1, and only
to requests carrying the token it gave this runner. Programs whose names look like developer tools (Gradle, Maven,
IntelliJ and other IDEs) are skipped unless you name them in **Process**.

SAP GUI is automated through SAP GUI Scripting with the SAP toolkit (`ooa.sap`) instead; see its
[reference](../reference/toolkits/ooa-sap.md).

### 10.3 Windows, programs, mouse and keyboard

| Group | Typical actions |
| --- | --- |
| **Window** | Activate, Maximise, Minimise, Restore, Move and resize, Close, Wait for window, Capture window, Get active window title; conditions **Window exists**, **Window is active** |
| **Application** | Start program, Run program and wait, Open file, Stop program; condition **Program is running** |
| **Mouse and keyboard** | Mouse click, Mouse move, Mouse scroll, Type text, Press keys, Capture screen, clipboard actions, Message box, Prompt for value / yes/no / password / file / folder |

**Press keys** has a **+ key** list with ENTER, TAB, ESC, CTRL+A, ALT+F4 and other keys. **Type text** can type a
credential from the vault.

Desktop steps need a signed-in Windows desktop. Message boxes and prompts wait for a person; in unattended runs they fail
at once unless they have a timeout.

## 11. Images and OCR

When an application cannot be read through its controls (remote desktops, Citrix, games, pictures):

- **Image recognition**: **Find image on screen**, **Click image**, **Wait for image** (or until it is gone),
  **Wait for screen change**, **Find image in image**; condition **Image is on screen**. Keep the template images in your
  workspace; set **Tolerance** and, to speed things up, **Search only an area**.
- **OCR (read text in images)**: **Read text from image**, **Read text from image URL**, **Read text from screen**
  (whole screen or one window, optionally an area), **Find text in image**, **Click text on screen**; condition
  **Text is on screen**. **Keep only part of the text** takes the text after or before a word. OCR needs Tesseract
  installed on the computer that runs the agent.

## 12. Interrupt handlers for unexpected pop-ups

Pop-ups such as "Session expired", cookie banners or "Do you want to save?" can break a UI agent at any step. Instead
of checking for them everywhere, add a rule once at the start:

1. Add **Add interrupt rule** (group **Interrupt handlers**).
2. Fill in:
   - **Name** of the rule;
   - **When window**: a title pattern (text it contains, a wildcard pattern, or `re:` and a regular expression),
     and/or **When element**: a selector of the pop-up in the browser;
   - **Then**: *Click* the target, *Press keys*, *Close window*, *Run agent*, or *Ignore* (only log it);
   - **Target**: for Click a selector or `x,y`; for Press keys keys such as `ENTER` or `ALT+F4`; for Run agent the
     agent id;
   - More options: **Browser session** and **Max times**.
3. From then on, desktop, window and browser actions check the rules before they act and when an element is missing.

**Remove interrupt rule**, **Clear interrupt rules**, **List interrupt rules** and **Check interrupt rules now** manage
them.

## 13. Good practice

- Tick properties that do not change between runs: an id, a name, a label. Untick positions, generated ids and texts
  that contain dates or amounts.
- Use **contains** or **wildcard** for values that vary slightly; use `$variables` in values for things that vary per
  run (an invoice number in a link text).
- Always **Test** a captured object after you change it.
- Add **Wait for element** (or **Wait for window**) after actions that load a new page or window.
- Put UI steps in a **Try** with **Take a screenshot when the error is caught**, so a failure leaves a picture.
- Use **Unattended** run mode for agents that run on runners.

## 14. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| "No connected browser window is open" when recording | The Browser Connector is not installed or not connected; record a new page or a desktop application, or install the connector (section 2). |
| "Nothing matches: loosen a property …" after **Test** | A ticked value changed. Use *contains* or *wildcard*, untick it, or recapture. |
| `BROWSER.OBJECT_NOT_FOUND` / `DESKTOP_UI.OBJECT_NOT_FOUND` in a run | The element did not appear in time or no longer matches. Raise the wait, add a wait step, add an anchor, or recapture. |
| `BROWSER.WINDOW_NOT_FOUND` | No tab matches the step's window. The error lists the open tabs; fix the window variable's title or address. |
| "The Browser module is not installed" / "The Desktop objects toolkit is not installed" | The optional module is missing. Ask your administrator. |
| Capture desktop does nothing | Hover the control and press **Ctrl+Shift+C** within a minute; Studio must run on Windows. |
| Recorded typing goes into the wrong field | Check the window and object of the step; recapture if the page layout changed. |
| `RUN.NEEDS_PERSON` in One CommandCenter | A message box or prompt has no timeout in an unattended run. Give it a timeout or run attended. |

## Related guides

- [Building agents](03-studio-building-agents.md)
- [Running and debugging](04-studio-running-and-debugging.md)
- [Office files, email and documents](11-working-with-office-files-email-and-documents.md)
- [AI in agents](13-ai-in-agents.md)
- [Child agents](09-child-agents.md)
- [Browser Connector guide](08-browser-connector-guide.md)
