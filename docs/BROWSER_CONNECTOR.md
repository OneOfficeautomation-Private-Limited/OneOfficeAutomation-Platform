# ONEOFFICEAUTOMATION Browser Connector

The Browser Connector lets agents and One Agent Studio work in **your own Edge or Chrome**: the windows and tabs you
already have open, signed in as you. Without it, web actions use a browser the agent starts itself (Playwright).

## How it fits together

```
Edge / Chrome                         this computer
┌──────────────────────────────┐      ┌─────────────────────────────────────────────┐
│ extension (Manifest V3)      │      │ native-messaging host  (ooa native-host)    │
│  background.js  ── stdin/out ├─────►│  started by the browser, one per browser    │
│  content.js + ooa-page.js    │      │  local socket 127.0.0.1:<port> + token      │
│  (every page and frame)      │      │  registration ~/.ooa/bridge/<browser>-<pid>  │
└──────────────────────────────┘      └───────────────▲─────────────────────────────┘
                                                       │ JSON lines
                                      ┌────────────────┴─────────────────────────────┐
                                      │ agent / Studio (ExtensionBridge in the       │
                                      │ Browser toolkit)                             │
                                      └──────────────────────────────────────────────┘
```

- **Extension** (`platform/browser-extension`, shipped as `browser-extension\` in the install). The background worker
  lists, opens, activates, navigates and closes tabs and windows, runs page scripts and takes screenshots. The content
  script finds captured objects and acts on them, shows the Capture picker and records. It uses the same page script
  (`ooa-page.js`) as Playwright runs, so a captured object means the same thing in both.
- **Native-messaging host** (`ooa native-host`). The browser starts it when the extension connects. It accepts only
  this extension (fixed extension id `fnceeakhokkekgdehkobdpdehpfnemah`), relays requests from agents on this computer
  and passes the extension's events (picked element, recorded step) to them. Agents find it through the registration
  file and must send its random token first.
- **Bridge** (`ExtensionBridge`). Used by the Browser toolkit: a tab driven through the connector is a run session like
  a Playwright one.

## Install (once per Windows user)

1. Run:

   ```bash
   ooa browser-connector install
   ```

   It writes the host launcher and manifests to `%LOCALAPPDATA%\OneOfficeAutomation\browser-connector` and registers
   them for Edge, Chrome and Firefox under `HKCU\Software\...\NativeMessagingHosts\com.oneofficeautomation.bridge`.
   Run it from a normal PowerShell or Command Prompt window. A program started from inside a packaged (MSIX) app writes
   these into the app's private copy of the registry and AppData, which the browsers do not see.
2. In each browser: open `edge://extensions` (or `chrome://extensions`), turn on **Developer mode**, choose **Load
   unpacked** and pick the `browser-extension` folder of the install. Companies can push it with browser policy instead.
   For **Firefox** (128 or later): open `about:debugging#/runtime/this-firefox`, choose **Load Temporary Add-on** and pick
   `manifest.json` in the install's `browser-extension-firefox` folder. Then, in `about:addons` > OOA Connector >
   **Permissions**, switch on access to all websites. A temporary add-on is gone when Firefox restarts; for a lasting
   install the add-on is signed by Mozilla (an unlisted listing on addons.mozilla.org) and pushed with Firefox policy.
3. The extension's toolbar icon shows **Connected**. `ooa browser-connector status` lists connected browsers. In
   Firefox, right after loading or reloading the add-on, open its window once from the extensions menu: Firefox may not
   start it before that.

`ooa browser-connector uninstall` removes the host and the registry keys (remove the extension in the browser).

## What it does

| Area | With the connector |
| --- | --- |
| Open browser | `engine` auto (default): a new window in your Edge, Chrome or Firefox when it is connected; it stays open after the run, Close browser closes it |
| Window choice | Steps find a tab by title and address in any connected browser (the tab you look at wins when several match) |
| Element actions | Capture: element action, Click, Set text, Select item, Get text / attribute, Wait for element, Element exists / visible, Extract table, Download file (by clicking), Screenshot (page or element) |
| Page actions | Go to URL, Back / forward / refresh, Get URL and title, Get page source, Run JavaScript, Call JavaScript function, Find broken links, Switch tab |
| Studio Capture | Picks in an open tab chosen from a list grouped by browser; fills the step's window (title, link, browser); preview image |
| Studio Record | Records in an open tab; every step has the full captured object and its window; Stop recording on the page |

## Limits

- Run JavaScript on a page whose content security policy forbids evaluating code goes through the browser's debugging
  interface instead, so the browser briefly shows its "being debugged" bar. Call JavaScript function still needs the
  page to allow it.
- Events are created by the page script, so a page that accepts only real (trusted) input events may ignore them.
- Upload file sends the files' contents to the page (50 MB at most in all): a file field gets them as if chosen, any
  other element (a drop zone) gets them dropped on it. Drag-to sends both HTML drag events and pointer moves, for
  drag libraries that listen to either.
- A screenshot of a window hidden behind other windows uses the browser's debugging interface for a moment, so the
  browser briefly shows its "being debugged" bar.
- **Firefox** has no debugging interface for extensions. A screenshot of a tab in the background uses Firefox's own
  tab capture instead, with no bar shown. Run JavaScript on a page whose policy blocks scripts stops with a clear
  message (run that step in Edge or Chrome). Everything else in the table above works the same in Firefox (tested on
  Firefox 159: fill, upload, click, read, run JavaScript, page and element pictures, background tab).
