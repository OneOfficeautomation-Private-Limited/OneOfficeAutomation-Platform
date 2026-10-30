# Browser connector guide

**Audience:** builders who capture and record web steps in their own browser, and runner owners whose agents work in
the Edge, Chrome or Firefox windows people already have open.

**You need:** ONEOFFICEAUTOMATION installed on the computer (the connector uses its `bin\ooa.bat` and its
`browser-extension` folders), Edge or Chrome 116 or later, or Firefox 140 or later, and a normal Windows user account.
No administrator rights are needed: the connector is installed per Windows user.

## Contents

1. [What the connector does](#1-what-the-connector-does)
2. [How it fits together](#2-how-it-fits-together)
3. [Install the connector on this computer](#3-install-the-connector-on-this-computer)
4. [Load the extension in Edge and Chrome](#4-load-the-extension-in-edge-and-chrome)
5. [Load the add-on in Firefox](#5-load-the-add-on-in-firefox)
6. [Check that it is connected](#6-check-that-it-is-connected)
7. [Using it in Studio and in agents](#7-using-it-in-studio-and-in-agents)
8. [What works in each browser](#8-what-works-in-each-browser)
9. [Rolling it out to many computers](#9-rolling-it-out-to-many-computers)
10. [Troubleshooting](#10-troubleshooting)
11. [Removing it](#11-removing-it)
12. [Related guides](#related-guides)

## 1. What the connector does

Without the connector, an agent's web steps use a browser the agent starts itself (Playwright): a clean window with
no cookies and no sign-ins. With the connector, agents and One AgentStudio can also work **in your own Edge, Chrome
or Firefox**: the windows and tabs you already have open, signed in as you. That matters when:

- a site needs your single sign-on, a smart card or a session you already have;
- you want to capture or record in the exact tab you are using;
- an attended agent should help you in the browser you are working in.

## 2. How it fits together

| Part | Where | What it does |
| --- | --- | --- |
| **The extension** (Manifest V3) | Inside each browser | Lists, opens, switches, navigates and closes tabs and windows; finds captured objects on pages and acts on them; shows the Capture picker; records steps; takes screenshots |
| **The native-messaging host** (`ooa native-host`) | This computer | Started by the browser when the extension connects, one per browser. It accepts only the ONEOFFICEAUTOMATION extension, listens on 127.0.0.1 with a random token, and relays requests from agents on this computer |
| **The bridge** | Inside agents and Studio | Treats a tab driven through the connector like any browser session |

Everything stays on the computer: the host answers only on 127.0.0.1, and only to programs that present its token.
The extension uses the same page script as Playwright runs, so a captured object means the same thing in both.

## 3. Install the connector on this computer

Do this once per Windows user.

1. Open a **normal PowerShell or Command Prompt window** from the Start menu.

   > **Important.** Do not run the install from inside another packaged (MSIX) app, such as an app's built-in
   > terminal. Programs started from such an app write their registry keys and files into the app's private copy of
   > the registry and AppData, which the browsers never see. The install would look fine but the browsers could not
   > find the connector.

2. Go to the install folder and run:

   ```
   bin\ooa.bat browser-connector install
   ```

   (Use `--home DIR` to name the install folder when you run it from somewhere else.)

3. What you see:

   ```
   Browser connector installed for <you>.
     Host:      ...\OneOfficeAutomation\browser-connector\com.oneofficeautomation.bridge.json
     Extension: <install folder>\browser-extension
   Load the extension once in each browser: ...
   ```

   The command wrote a small launcher and the host manifests to
   `%LOCALAPPDATA%\OneOfficeAutomation\browser-connector`, and registered them for Edge, Chrome and Firefox under
   `HKCU\Software\...\NativeMessagingHosts\com.oneofficeautomation.bridge`.

## 4. Load the extension in Edge and Chrome

1. Open `edge://extensions` (Edge) or `chrome://extensions` (Chrome).
2. Turn on **Developer mode**.
3. Choose **Load unpacked** and pick the `browser-extension` folder of the install (the **Extension** path the install
   command printed).
4. The extension **ONEOFFICEAUTOMATION Browser Connector** (short name **OOA Connector**) appears. Pin its icon to the
   toolbar if you like.
5. Restart the browser once, so it starts the host.

The extension asks for access to all sites and to tabs, scripting, native messaging, navigation events, storage,
alarms, downloads and the debugger. It needs them to find and act on elements on any page, to download files by
clicking and to take screenshots or run scripts on pages that forbid them.

## 5. Load the add-on in Firefox

1. Open `about:debugging#/runtime/this-firefox`.
2. Choose **Load Temporary Add-on** and pick `manifest.json` in the install's `browser-extension-firefox` folder.
3. Open `about:addons` > **OOA Connector** > **Permissions** and switch on access to all websites.
4. Right after loading (or reloading) the add-on, open its window once from the extensions menu: Firefox may not start
   it before that.

A temporary add-on is gone when Firefox restarts. For a lasting install the add-on is signed by Mozilla (an unlisted
listing on addons.mozilla.org) and pushed with Firefox policy.

## 6. Check that it is connected

**In the browser:** click the connector's toolbar icon. You see one of:

| State | Meaning |
| --- | --- |
| Green dot, **Connected** | "Agents and Agent Studio can see and automate the tabs of this browser" |
| Red dot, **Not connected** | The host is not installed or not reachable. The text tells you to run `ooa browser-connector install` and restart the browser, with the error |
| **Not available** | The extension's background worker did not answer; reload the extension |

**On the computer:**

```
bin\ooa.bat browser-connector status
```

It prints the host manifest path (or "not installed"), the extension folder, and one line per **Connected browser**
with the extension version and host process. "No browser is connected now" means no browser with the extension loaded
is open.

## 7. Using it in Studio and in agents

- **Open browser**: with the engine on `auto` (the default), the step opens a new window in your connected Edge,
  Chrome or Firefox. The window stays open after the run; **Close browser** closes it.
- **Finding a tab**: steps find a tab by title and address in any connected browser; when several match, the tab you
  are looking at wins.
- **Capture in Studio**: pick an open tab from a list grouped by browser, then click the element. The step gets the
  captured object, its window (title, link, browser) and a preview image.
- **Record in Studio**: records in an open tab; each step gets the full captured object and its window. Stop
  recording from the page.

See [10 Web and desktop automation](10-web-and-desktop-automation.md) for building web steps.

## 8. What works in each browser

| Area | Edge and Chrome | Firefox |
| --- | --- | --- |
| Open browser, choose window or tab | Yes | Yes |
| Element actions: Capture: element action, Click, Set text, Select item, Get text or attribute, Wait for element, Element exists or visible, Extract table, Download file (by clicking), Screenshot of page or element | Yes | Yes |
| Page actions: Go to URL, Back, Forward, Refresh, Get URL and title, Get page source, Find broken links, Switch tab | Yes | Yes |
| Run JavaScript | Yes. On a page whose content security policy forbids it, the step uses the browser's debugging interface and the browser briefly shows a "being debugged" bar | Yes, except on pages whose policy blocks scripts: the step stops with a clear message (run it in Edge or Chrome) |
| Call JavaScript function | Needs the page to allow scripts | Needs the page to allow scripts |
| Screenshot of a window hidden behind others | Uses the debugging interface for a moment (the bar shows briefly) | Uses Firefox's own tab capture, no bar |
| Upload file | Up to 50 MB in all; a file field gets the files as if chosen, any other element (a drop zone) gets them dropped | Same |
| Drag to | Sends HTML drag events and pointer moves | Same |
| Studio Capture and Record | Yes | Yes |

The Firefox column was checked on Firefox 159: filling fields, uploading, clicking, reading, running JavaScript, page
and element pictures, and a tab in the background.

**Good to know:** events are created by the page script, so a page that accepts only real (trusted) input events may
ignore them. For such a page use a Playwright browser, or desktop mouse and keyboard actions.

## 9. Rolling it out to many computers

- Run `bin\ooa.bat browser-connector install` as each user, for example in a sign-in script that runs in the user's
  normal session (not from a packaged app).
- Push the extension with browser policy instead of **Load unpacked**: Edge and Chrome extension policies can force-
  install it; the extension has a fixed id (`fnceeakhokkekgdehkobdpdehpfnemah`), which the host checks.
- For Firefox, have the add-on signed by Mozilla (unlisted) and push it with Firefox policy. Its id is
  `browser-connector@oneofficeautomation.com`.

## 10. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Icon says **Not connected** right after install | Restart the browser completely (close every window). Then run `ooa browser-connector status` |
| Still not connected; the install ran from an app's built-in terminal | The registry keys went into the app's private copy. Run `bin\ooa.bat browser-connector install` again from a Start-menu PowerShell or Command Prompt |
| `status` says the host manifest is "not installed" | Run the install command (as this Windows user) |
| "error: no ONEOFFICEAUTOMATION install at ..." | Give the install folder with `--home DIR` |
| Firefox add-on missing after a restart | It was a temporary add-on: load it again, or use the signed add-on |
| Firefox add-on loaded but never connects | Open its window once from the extensions menu; check its **Permissions** allow all websites |
| A "being debugged" bar flashes in Edge or Chrome | Normal for Run JavaScript on strict pages and for screenshots of hidden windows |
| A click or typing has no effect on one site | The site accepts only trusted input events; use a Playwright browser or desktop actions |
| Agents on a runner cannot see the browser | The connector and the browser must be in the same Windows session as the runner; a service runner has no desktop and no browsers |
| Run JavaScript fails in Firefox on one site | The page's policy blocks scripts; run that step in Edge or Chrome |

## 11. Removing it

```
bin\ooa.bat browser-connector uninstall
```

This removes the host files and registry keys. Remove the extension in each browser's extensions page yourself.

## Related guides

- [00 Index](00-README.md)
- [07 Runner guide](07-runner-guide.md)
- [10 Web and desktop automation](10-web-and-desktop-automation.md)
- [02 Studio basics](02-studio-basics.md)
