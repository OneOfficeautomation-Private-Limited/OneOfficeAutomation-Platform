# Web actions, Capture and Recorder: comparison with the reference product

Checked on 2026-10-22 in the reference product's community edition (a test agent, never saved) and in its public
documentation (pages: actions per captured object type, using the Capture action, the Universal Recorder, the
Recorder package). Written in our own words; only behaviour is compared, nothing is copied.

## 1. Browser actions

| Reference | What it offers | Ours today | Gap |
| --- | --- | --- | --- |
| Open | What to open: existing tab, new tab, new window. Existing/new tab pick the browser tab (Browser list or a window variable); new window picks the browser (default, IE, Firefox, Chrome, Edge). Link, time-out (240 s, min 9) | `open` (new window, keeps it open), `navigate` (current tab), `switch_tab` | One Open with the three modes and the tab picker; a "default browser" choice; a time-out |
| Close | Tab, window or all browsers; the tab is picked | `close` (session) | Close a tab, a window, or all browsers |
| Go back | Tab, number of steps, error when history is shorter | `history` (back/forward/refresh) | Number of steps; optional error |
| Download file | URL, save to, overwrite; NTLM sign-in | `download` | Check: URL-only download with overwrite |
| Get source code | Tab, optional iframe (captured), output variable | `get_page_source` | Iframe from a captured object |
| Run JavaScript | Tab (browser/application/variable), optional iframe, script from a file (variable, server file, desktop file) or typed, output | `run_javascript` | Script file choices; iframe from a captured object |
| Call a JavaScript function | Tab, iframe, function name, arguments list, return type | `call_javascript_function` | Return type |
| Find broken links | Page, scope (page / whole site), CSV path, append, encoding, threads, per-link time-out | `find_broken_links` | Check the same options |

## 2. Window actions and window variables

The reference product has a **Window** variable type. The Capture action and the recorder create one for the window
they work in, and later steps choose "Variable" to reuse it. Window actions: Activate, Get active window title, Assign
(copy one window variable into another), Close, Close all (with windows to keep), Maximize, Minimize, Resize (left,
top, width, height), Restore, Set title (plain or regular expression).

Ours: the Window toolkit has the same actions except Assign; a browser "window" choice can name a session, but there is
no Window variable type and nothing creates one automatically.

## 3. Capture

Panel order: Window (Browser / Application / Variable), Resize window, Main / Anchor, Capture object (with a
technology choice: auto, Active Accessibility, UI Automation, UI Automation COM), Preview, **Action to take on object**,
the object's properties, Set system time out (Basic / Advanced), Save the outcome to a variable.

- **Window list.** Browser tabs are grouped by browser with a tab count; each tab shows the browser's icon and the
  page title. The Application list starts with presets (currently active window, desktop, taskbar). After choosing:
  title as text or pattern, wildcards, case-sensitive (on by default), link of the web page, browser, program path,
  optional resize to a width and height.
- **Actions depend on the control type.** The detected control type (text box, password text, button, link, check box,
  radio button, combo box, list, table, label, menu, tab, tree, slider, calendar, client) decides the list. Examples:
  check box: get status, check, uncheck, toggle, set focus; combo box: get total items, get selected index / text /
  key, select by index (counts from 1) or text, expand; table: get table, cell text by index, cell index by text, set
  cell, click cell, total rows / columns, select row, select / deselect all; text box: set text, append text; slider:
  set position; calendar: set date / month / year. Every type also has get property and the clicks; web controls
  also get a "debugger click".
- **Per-action fields** appear only for the chosen action. Set text: the value as a credential, a variable or plain
  text, and the time between keystrokes (0 pastes, more than 0 types). Get property: which property, and the
  outcome variable. Several actions: "Run in background".
- **Properties:** the ticked ones by default, all on request; fixed rows for browser, technology and control type;
  values are text with `*` wildcards and variables.
- **Anchor**, **time-out** (Basic: one wait; Advanced: wait for the browser, wait for the object, and "stop with an
  error" or "skip and carry on").

Ours: the property table, anchor, time-out and skip are in place (Part 7). Missing: the control type and the action
list that follows it; per-action fields shown only when relevant (today all inputs are listed); the outcome variable
inside the panel; the grouped window list with presets; a case-sensitive switch; resize for browser windows; several
actions (get status, toggle, set focus, get total items, selected index / key, select by index counting from 1,
table cell actions, set date, set position, get all children names / values).

## 4. Recorder

Started from the editor's toolbar; a dialog counts the recorded actions and can be cancelled. Every recorded
interaction becomes a **Capture** step with the chosen action and properties. A window variable is created for the
window and the steps use it. Password fields become Set text with a credential. It records in windows that are already
open, for web pages and desktop applications alike.

Ours: records only in a new browser it opens; produces click / set text / select steps (with captured objects and,
since this week, variables for the typed values); no window variable; web only; no live count in Studio.

## 5. How the reference agent is built (seen on this computer, read-only)

Looked at file names, versions and package contents only; no configuration values were read.

| Part | Reference agent | Ours (One Runner) |
| --- | --- | --- |
| Runtime | Java 11 (bundled Azul Zulu JRE) | Java 21 |
| Service | Java programs run as Windows services through a service wrapper: device agent (`node-manager`), bot runner (`bot-launcher`), trigger listener, child-session and virtual-desktop launchers (unattended runs in a separate Windows session through an RDP client), auto-login helper with a Windows credential provider, an elevation helper | One process (server plus runner) or a separate runner; no Windows service wrapper, no separate session, no auto-login |
| Actions | One jar per action package, downloaded from the Control Room and cached; each package unpacks its native helpers into a cache folder | Toolkits are jars inside the install; no per-package download |
| Web | A browser extension for Chrome, Edge and Firefox plus a native-messaging host (stdio) and a small .NET program per browser. It drives the person's normal browser, so every open tab can be listed, captured and recorded with no debugging port | Playwright over the DevTools protocol: browsers we start (or start with a debugging port) |
| Desktop | A compiled UI Automation (COM) host that talks to the Java side over protobuf; Active Accessibility (MSAA) through JNI; Java applications through the Java Access Bridge and an attached Java agent; Citrix and remote drivers; a macOS accessibility adapter | UI Automation through a PowerShell-hosted .NET helper (JSON over stdio); no MSAA, no Java Access Bridge, no Citrix |
| Inside | gRPC/protobuf between components, JNA/JNI for Windows calls, JavaFX in places, Python models for image-based capture, ffmpeg for screen recording, FIPS-certified crypto library | JSON over stdio and HTTP; no screen recording; JDK crypto |

### What it would take to match

1. **Browser extension + native-messaging host** (biggest gain): our own Chrome/Edge extension (Manifest V3) and a
   small host started by the browser that relays to the runner. Gives: listing every open tab in normal windows,
   Capture and Recorder in any tab, runs in the person's own browser session (sign-ins, cookies). Playwright stays for
   headless and unattended runs. The extension must be installed once (store, enterprise policy or developer mode).
2. **Compiled desktop helper**: replace the PowerShell-hosted helper with a small compiled program (same JSON
   protocol), faster to start; add Active Accessibility (MSAA) next to UI Automation, and the Java Access Bridge for
   Java desktop applications.
3. **Runner as a Windows service** with an unattended mode: run agents in a separate Windows session, sign in with a
   stored Windows credential, record the screen of a run.
4. **Packages downloaded on demand** from Command Center, with their native helpers unpacked to a cache.
