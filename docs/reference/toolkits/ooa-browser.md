# Browser

Toolkit `ooa.browser`, version 1.0.0. Open web pages, click, type, select, read text and tables, run JavaScript, download files and take screenshots.

[All toolkits](README.md)

## Contents

- **Browser**: Attach to open browser, Close browser, Back, forward or refresh, List windows (design time), Go to URL, Open browser, Start browser for automation, Switch tab
- **Page**: Call JavaScript function, Download file, Download file from address, Find broken links, Get page source, Get URL and title, Run JavaScript, Take screenshot
- **Elements**: Click element, Get attribute, Get text, Select item, Set text, Wait for element
- **Capture**: Capture: element action, Pick element (design time), Test captured object (design time)
- **Data**: Extract list, Extract table
- **Recorder**: Record web actions
- **Conditions**: Element exists, Element text is, Element is visible, Page contains text
- **Loop types**: Each page (click Next until it is gone)

## Browser

### Attach to open browser

`ooa.browser/attach` (action)

Connect to an Edge or Chrome that is already running, through its DevTools debugging port, and use it as a named session. Only a browser started with a debugging port can be reached (use Start browser for automation, or start it with --remote-debugging-port); a browser opened the usual way has none. Closing the session later only disconnects; the browser stays open.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint | `endpoint` | Text | No | `http://127.0.0.1:9222` | Main | Debugging address of the browser: http://host:port, just the port, or a ws:// DevTools address. Example: `http://127.0.0.1:9222` |
| Browser | `browser` | Text | No |  | Main | Which browser it is; found out from the browser itself when left empty. Used by window choices that name a browser. One of: `edge`, `chrome` |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the connection, in milliseconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Title of the session's current tab. |
| `url` | Text | Address of the session's current tab. |
| `tab_count` | Number | Number of open tabs in the browser. |
| `window` | Dictionary | A window choice for this session, to pass as the window of element actions. |

*Time limit 120 s; needs: browser.*

### Close browser

`ooa.browser/close` (action)

Close the browser session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| What | `what` | Text | No | `session` | Main | session: what this agent opened (its window, or the tab it worked in); tab: the current tab; window: the whole window; all: every browser window. One of: `session`, `tab`, `window`, `all` |
| Window | `window` | Dictionary | No |  | Main | The tab or window to close, instead of the session's current one. Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. Shown when: `{"what": ["tab", "window"]}` |
| Session | `session` | Text | No | `browser` | More options | Session name. |

*Time limit 60 s.*

### Back, forward or refresh

`ooa.browser/history` (action)

Go back, go forward or reload the page.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Action | `action` | Text | Yes |  | Main | What to do. One of: `back`, `forward`, `refresh` |
| Steps | `steps` | Number | No | `1` | Main | back: how many pages to go back (e.g. 3). Range: 1 to ; Shown when: `{"action": ["back", "forward"]}` |
| Error if too many | `error_if_too_many` | Yes/No | No | No | Main | back: fail when the tab has visited fewer pages than the steps asked for. Shown when: `{"action": ["back", "forward"]}` |
| Session | `session` | Text | No | `browser` | More options | Session name. |

*Time limit 120 s; needs: browser.*

### List windows (design time)

`ooa.browser/list_windows` (action)

List the browser tabs an agent can reach (in this run's sessions and in browsers with a debugging port) and, on Windows, the titles of open desktop windows. Used by Agent Studio to offer windows to choose from.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoints | `endpoints` | List | No |  | Main | Debugging addresses to look at; default http://127.0.0.1:9222. |
| Include desktop | `include_desktop` | Yes/No | No | Yes | Main | Also list desktop window titles (Windows only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `windows` | List | One entry per window: source (browser or application), title, url and browser. |
| `count` | Number | Number of windows listed. |

*Time limit 60 s; safe to repeat; needs: browser.*

### Go to URL

`ooa.browser/navigate` (action)

Open a URL in the current tab and wait for it to load.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Address to open. |
| Session | `session` | Text | No | `browser` | More options | Session name. |

*Time limit 120 s; needs: browser.*

### Open browser

`ooa.browser/open` (action)

Start a browser, optionally open a URL, as a named session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Open in | `open_in` | Text | No | `new_window` | Main | new_window: a new browser window; new_tab: a new tab next to the chosen one; existing_tab: load the address in the chosen tab. One of: `new_window`, `new_tab`, `existing_tab` |
| Window | `window` | Dictionary | No |  | Main | For new_tab and existing_tab: the tab to use. Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. Shown when: `{"open_in": ["!", "new_window"]}` |
| Browser | `browser` | Text | No | `edge` | Main | edge and chrome use the installed browser; chromium and firefox are downloaded by Playwright. One of: `edge`, `chrome`, `chromium`, `firefox`; Shown when: `{"open_in": ["new_window"]}` |
| Address (URL) | `url` | Text | No |  | Main | Page to open. |
| Download folder | `download_folder` | File path | No |  | More options | Where downloads are saved. Shown when: `{"open_in": ["new_window"]}` |
| Headless | `headless` | Yes/No | No | No | More options | Run without a visible window (for unattended agents). Shown when: `{"open_in": ["new_window"]}` |
| Keep the browser open after the run | `keep_open` | Yes/No | No | Yes | More options | Edge and Chrome: leave the window open when the agent ends (only Close browser closes it), and let Studio and later agents reach it. False closes the browser with the run, as before. |
| Engine | `engine` | Text | No | `auto` | More options | connector: open a window in the person's own Edge, Chrome or Firefox through the ONEOFFICEAUTOMATION Browser Connector extension; playwright: a browser the agent starts itself; auto: the connector when that browser has it, else playwright. One of: `auto`, `connector`, `playwright` |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `240000` | More options | How long to wait for the page to load. Range: 1000 to  |

*Time limit 180 s; needs: browser.*

### Start browser for automation

`ooa.browser/open_for_automation` (action)

Start Edge or Chrome with a debugging port and its own profile folder in the workspace, so this agent, later agents and Attach to open browser can all reach it. When a browser already listens on the port it is reused instead. A browser opened the usual way cannot be reached, because it has no debugging port; recent browsers also refuse a debugging port on the everyday profile, which is why a separate profile is used. The browser keeps running after the agent ends.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Browser | `browser` | Text | No | `edge` | Main | Installed browser to start. One of: `edge`, `chrome` |
| Address (URL) | `url` | Text | No |  | Main | Page to open first. |
| Port | `port` | Number | No | `9222` | Main | Debugging port. 9222 is the one window choices look at by themselves. |
| Profile folder | `profile_folder` | File path | No |  | More options | Profile folder for this browser (cookies, sign-ins). Default: .ooa/browser-profiles/<browser>-<port> in the workspace. |
| Headless | `headless` | Yes/No | No | No | More options | Run without a visible window. |
| Attach | `attach` | Yes/No | No | Yes | More options | Also attach to it as the named session. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Executable | `executable` | File path | No |  | More options | Path of the browser program, when it is not installed in the usual place. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the browser to start, in milliseconds. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Title of the current tab (when attached). |
| `url` | Text | Address of the current tab (when attached). |
| `tab_count` | Number | Number of open tabs (when attached). |
| `window` | Dictionary | A window choice for this session, to pass as the window of element actions. |
| `endpoint` | Text | Debugging address to attach to. |
| `started` | Yes/No | True when a new browser was started; false when one already listened on the port. |
| `pid` | Number | Process id of the started browser, or 0 when an existing one was reused. |

*Time limit 180 s; needs: browser, filesystem.*

### Switch tab

`ooa.browser/switch_tab` (action)

Make another tab current, by number (0 = first) or by title text; new tabs opened by links are included.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Index | `index` | Number | No |  | Main; choice Find the tab = By position | Tab number, starting at 0; -1 for the newest. |
| Title contains | `title_contains` | Text | No |  | Main; choice Find the tab = By title | Pick the first tab whose title contains this text. |
| Session | `session` | Text | No | `browser` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `title` | Text | Title of the tab now current. |

*Time limit 60 s; needs: browser.*

## Page

### Call JavaScript function

`ooa.browser/call_javascript_function` (action)

Call a function defined by the page (e.g. app.calculateTotal) with arguments and return its result.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Function | `function` | Text | Yes |  | Main | Function name, reachable from window; dots for nested objects. Example: `app.calculateTotal` |
| Arguments | `arguments` | List | No |  | Main | Arguments, in order (text, numbers, true/false, lists and dictionaries). |
| Return type | `return_type` | Text | No | `any` | Main | What the function returns: the result is checked and converted (none ignores it). One of: `any`, `none`, `text`, `number`, `boolean`, `list`, `dictionary` |
| Frame | `frame` | Dictionary | No |  | More options | Call the function inside this iframe instead of the main page: the captured iframe element. |
| Session | `session` | Text | No | `browser` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value returned by the function (awaited when it returns a promise). |

*Time limit 120 s; needs: browser.*

### Download file

`ooa.browser/download` (action)

Click a link or button that starts a download and save the file into a folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element that starts the download, used when no captured object is given. |
| Folder | `folder` | File path | Yes |  | Main | Folder to save into. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `120000` | More options | How long to wait for the download. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved file. |

*Time limit 600 s; needs: browser, filesystem.*

### Download file from address

`ooa.browser/download_url` (action)

Download a file from its address and save it, signing in with Windows (NTLM/Kerberos) or a user name and password when the server asks.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Address of the file. Example: `https://intranet.example.com/reports/invoice.xlsx` |
| Save to | `save_to` | File path | Yes |  | Main | File to create, or a folder (the name then comes from the address). |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace the file if it already exists. |
| Username | `username` | Text | No |  | More options; tick The server asks to sign in | User name when the server asks to sign in (domain\user for Windows sign-in); empty uses the Windows sign-in of the agent's user. |
| Password | `password` | Credential (vault) | No |  | More options; tick The server asks to sign in | Password for that user name (a Credential). |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `600000` | More options | How long the download may take. Range: 1000 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved file. |
| `size` | Number | Size in bytes. |

*Time limit 3600 s; needs: network, filesystem.*

### Find broken links

`ooa.browser/find_broken_links` (action)

Collect the links on the current page (or on a page or whole site given by its address) and check each one, reporting those that fail or return an HTTP error; optionally save the list to a CSV file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | No |  | Main | Page to check, by its address, instead of the browser's current page (no browser needed). |
| Scope | `scope` | Text | No | `page` | Main | page: the links of that page; site: also follow the site's own pages (same host) and check their links. One of: `page`, `site` |
| Maximum pages | `max_pages` | Number | No | `50` | Main | With scope site: at most this many pages are read. Range: 1 to ; Shown when: `{"scope": ["site"]}` |
| CSV path | `csv_path` | File path | No |  | Main; tick Save the list to a CSV file | Also save the list to this CSV file. |
| Append | `append` | Yes/No | No | No | Main; tick Save the list to a CSV file | Add to the CSV file if it exists, instead of replacing it. Shown when: `{"csv_path": ["!", ""]}` |
| Encoding | `encoding` | Text | No | `UTF-8` | Main; tick Save the list to a CSV file | CSV file encoding. One of: `UTF-8`, `UTF-16`, `ANSI`; Shown when: `{"csv_path": ["!", ""]}` |
| Threads | `threads` | Number | No | `10` | Main | How many links are checked at the same time (1-50). Range: 1 to 64 |
| Only broken | `only_broken` | Yes/No | No | Yes | More options | List only failing links instead of every link. |
| Same site only | `same_site_only` | Yes/No | No | No | More options | Check only links to the page's own host. |
| Maximum links | `max_links` | Number | No | `200` | More options | Safety limit on links checked. Range: 1 to  |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `10000` | More options | Time allowed for each link, in milliseconds. Range: 100 to  |
| Session | `session` | Text | No | `browser` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: url, status (0 when unreachable), ok, error. |
| `broken_count` | Number | Number of links that failed. |
| `checked_count` | Number | Number of distinct links checked. |

*Time limit 3600 s; needs: browser, network.*

### Get page source

`ooa.browser/get_page_source` (action)

Return the current HTML of the page (after scripts have run), and optionally save it to a file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Frame | `frame` | Dictionary | No |  | Main; choice Read from = One iframe | The source of this iframe instead of the whole page: the captured iframe element. |
| Path | `path` | File path | No |  | Main; tick Also save it to a file | Also save the HTML to this file. |
| Session | `session` | Text | No | `browser` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `source` | Text | HTML of the page. |
| `path` | File path | Saved file, or empty. |

*Time limit 120 s; needs: browser.*

### Get URL and title

`ooa.browser/page_info` (action)

Return the current page's URL and title.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `browser` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `url` | Text | Current URL. |
| `title` | Text | Page title. |

*Time limit 60 s; safe to repeat; needs: browser.*

### Run JavaScript

`ooa.browser/run_javascript` (action)

Run JavaScript in the page and return its result (use a return-value expression, e.g. document.title).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Script | `script` | Text | No |  | Main; choice Script = Type it here | JavaScript expression or function, typed here. Example: `document.querySelectorAll('.item').length`; Shown when: `{"script_file": [""]}` |
| Script file | `script_file` | File path | No |  | Main; choice Script = Run a file | Or a .js file to run (UTF-8), used instead of the typed script. Shown when: `{"script": [""]}` |
| Frame | `frame` | Dictionary | No |  | More options | Run the script inside this iframe instead of the main page: the captured iframe element. |
| Session | `session` | Text | No | `browser` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value returned by the script. |

*Time limit 120 s; needs: browser.*

### Take screenshot

`ooa.browser/screenshot` (action)

Save a PNG screenshot of the page or one element.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | Only this element: the captured object from Capture or Pick in Agent Studio. |
| Selector | `selector` | Text | No |  | Main | Only this element (selector). |
| Path | `path` | File path | Yes |  | Main | PNG file to create. |
| Full page | `full_page` | Yes/No | No | No | Main | Whole scrollable page. |
| Session | `session` | Text | No | `browser` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved image. |

*Time limit 120 s; needs: browser, filesystem.*

## Elements

### Click element

`ooa.browser/click` (action)

Click an element (waits until it is visible and enabled).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Double-click | `double_click` | Yes/No | No | No | Main | Double-click. |
| Then wait for | `then_wait_for` | Text | No | `nothing` | Main | After the click, wait until the page has loaded, or until the element in wait_selector appears or is gone (for example a spinner): no fixed Delay needed when Next keeps the same page. One of: `nothing`, `page_load`, `element_visible`, `element_gone` |
| Wait selector | `wait_selector` | Text | No |  | Main | With then_wait_for element_visible or element_gone: the element to wait for (a selector). |
| Click method | `click_method` | Text | No | `mouse` | More options | mouse: a real click (scrolls the element into view and waits until it can be clicked); javascript: fire the click on the element itself, for elements covered by an advert or a banner. One of: `mouse`, `javascript` |
| Force | `force` | Yes/No | No | No | More options | Mouse click without waiting until nothing covers the element. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Self-healing fallbacks recorded with the element (Pick records them): automation_id, name, id, aria_label, tag, class, index, text, relative {anchor, direction}. Tried in that order when the selector finds nothing; the one that works is logged. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |

*Time limit 120 s; needs: browser.*

### Get attribute

`ooa.browser/get_attribute` (action)

Read an attribute of an element, e.g. href or value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Attribute | `attribute` | Text | Yes |  | Main | Attribute name; use value for input fields. Example: `href` |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |
| If not found | `if_not_found` | Text | No | `error` | More options | When the element is not there in time: error stops with an error; continue leaves the result empty, sets found to false and carries on. One of: `error`, `continue` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Text | Attribute value, empty when missing. |
| `found` | Yes/No | False when the element was not there in time (if_not_found = continue). |

*Time limit 120 s; needs: browser.*

### Get text

`ooa.browser/get_text` (action)

Read the visible text of an element.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Self-healing fallbacks recorded with the element (Pick records them): automation_id, name, id, aria_label, tag, class, index, text, relative {anchor, direction}. Tried in that order when the selector finds nothing; the one that works is logged. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |
| If not found | `if_not_found` | Text | No | `error` | More options | When the element is not there in time: error stops with an error; continue leaves the result empty, sets found to false and carries on. One of: `error`, `continue` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Element text; empty when the element was not found and if_not_found is continue. |
| `found` | Yes/No | False when the element was not there in time (if_not_found = continue). |

*Time limit 120 s; needs: browser.*

### Select item

`ooa.browser/select_option` (action)

Choose an option in a drop-down list by its value or visible text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Option | `option` | Text | Yes |  | Main | Option value or visible text. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Self-healing fallbacks recorded with the element (Pick records them): automation_id, name, id, aria_label, tag, class, index, text, relative {anchor, direction}. Tried in that order when the selector finds nothing; the one that works is logged. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |

*Time limit 120 s; needs: browser.*

### Set text

`ooa.browser/type_text` (action)

Fill a text field (replacing its content); Credential values are typed without ever being logged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Text | `text` | Any | Yes |  | Main | Text, or a Credential variable for passwords. |
| Press Enter afterwards | `press_enter` | Yes/No | No | No | Main | Press Enter afterwards. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Self-healing fallbacks recorded with the element (Pick records them): automation_id, name, id, aria_label, tag, class, index, text, relative {anchor, direction}. Tried in that order when the selector finds nothing; the one that works is logged. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |

*Time limit 120 s; needs: browser.*

### Wait for element

`ooa.browser/wait_for` (action)

Wait until an element appears, becomes visible, hidden or is removed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| State | `state` | Text | No | `visible` | Main | State to wait for. One of: `visible`, `attached`, `hidden`, `detached` |
| Any of | `any_of` | List | No |  | More options | Other elements (captured objects or selectors): the wait ends when the element or any of these is visible (or there). Output which tells which one: 0 for the element, 1 for the first of these, and so on. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `which` | Number | With any_of: which element was found, 0 for the element, 1 for the first of the others. |

*Time limit 600 s; needs: browser.*

## Capture

### Capture: element action

`ooa.browser/element_action` (action)

Do one action on a captured element: click, type, read text, attributes or all properties, select, check, hover, drag, upload, wait and more. The element is the captured object (properties you choose, each with its own match rule) or a selector.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture in Agent Studio: the properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard, regex), search_by (properties, css or xpath), index when several elements match, and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. Used when no captured object is given. |
| Action | `action` | Text | Yes |  | Main | What to do with the element. Studio lists the actions that suit the captured control type. One of: `click`, `double_click`, `right_click`, `middle_click`, `hover`, `focus`, `set_text`, `append_text`, `clear`, `press_key`, `get_text`, `get_value`, `get_attribute`, `get_property`, `get_all_properties`, `get_html`, `get_inner_html`, `get_coordinates`, `get_children_text`, `get_table`, `select_by_text`, `select_by_value`, `select_by_index`, `get_selected`, `get_options`, `check`, `uncheck`, `is_checked`, `is_visible`, `is_enabled`, `is_present`, `count`, `scroll_into_view`, `highlight`, `wait_to_appear`, `wait_to_disappear`, `drag_to`, `upload_file`, `screenshot`, `get_status`, `toggle`, `select`, `get_selected_index`, `get_total_items`, `get_total_rows`, `get_total_columns`, `get_cell_text`, `get_cell_index_by_text`, `set_cell_text`, `click_cell`, `set_position`, `get_control_type` |
| Value | `value` | Any | No |  | Main | Depends on the action: the text to type, the key (e.g. Enter, Ctrl+A), the attribute or property name, the option text, value or index, the target selector for drag_to, the file path(s) for upload_file, or the image path for screenshot. Credentials allowed for set_text. |
| Row | `row` | Number | No |  | Main | Table cell actions: the row, 1 for the first. |
| Column | `column` | Number | No |  | Main | Table cell actions: the column, 1 for the first. |
| Run in background | `run_in_background` | Yes/No | No | No | Main | Leave the browser window where it is: the tab is not brought to the front and clicks are sent to the page (not with the mouse), so other work on the screen can go on. |
| Typing | `typing` | Text | No | `fill` | More options | fill: set the whole text at once (fast); keystrokes: type key by key like a person, for fields that react to each key. One of: `fill`, `keystrokes` |
| Key delay (milliseconds) | `key_delay_ms` | Number | No | `0` | More options | Pause between keys when typing is keystrokes. |
| Clear first | `clear_first` | Yes/No | No | Yes | More options | Clear the field before set_text. |
| Click method | `click_method` | Text | No | `mouse` | More options | mouse: a real click at the element (scrolls it into view and waits until it can be clicked); javascript: fire the click event directly, for elements that are covered or off screen. One of: `mouse`, `javascript` |
| Modifiers | `modifiers` | Text | No |  | More options | Keys held during the click, e.g. CTRL, SHIFT or CTRL+SHIFT. |
| Offset x | `offset_x` | Number | No |  | More options | Click this many pixels from the element's left edge instead of its centre. |
| Offset y | `offset_y` | Number | No |  | More options | Click this many pixels from the element's top edge instead of its centre. |
| Force | `force` | Yes/No | No | No | More options | Act even if the element looks hidden, covered or disabled. |
| Wait after (milliseconds) | `wait_after_ms` | Number | No | `0` | More options | Pause after the action, e.g. to let the page react. |
| Use captured page | `use_captured_page` | Yes/No | No | No | More options | Switch to the open tab whose address (and title, when its match rule is set) matches the page recorded with the captured object. |
| Alternatives | `alternatives` | Dictionary | No |  | More options | Self-healing fallbacks recorded with the element (Pick records them): automation_id, name, id, aria_label, tag, class, index, text, relative {anchor, direction}. Tried in that order when the selector finds nothing; the one that works is logged. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. |
| Wait for page (milliseconds) | `wait_for_page_ms` | Number | No | `0` | More options | First wait up to this long for the page to finish loading (0: don't wait). Counts as not found when it runs out. |
| If not found | `if_not_found` | Text | No | `error` | More options | When the page or the element is not there in time: error stops with an error; continue skips this step (result is empty) and carries on. One of: `error`, `continue` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | Value returned by get, is, count, wait and coordinate actions. |
| `found` | Yes/No | False when the step was skipped because the element was not found (if_not_found = continue). |

*Time limit 300 s; needs: browser.*

### Pick element (design time)

`ooa.browser/pick_element` (action)

Open a page and let a person click one element; returns a robust selector and the element's details. Used by Agent Studio's Pick button.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Page to open. |
| Browser | `browser` | Text | No | `edge` | Main | Browser. One of: `edge`, `chrome`, `chromium` |
| Timeout (minutes) | `timeout_minutes` | Number | No | `10` | More options | Give up after this long. |
| Endpoint | `endpoint` | Text | No |  | More options | Pick in a browser that is already open (its debugging address, e.g. http://127.0.0.1:9222) instead of opening a new one; it stays open. |
| Tab title | `tab_title` | Text | No |  | More options | With endpoint: the tab to pick in, by its exact title. |
| Tab address (URL) | `tab_url` | Text | No |  | More options | With endpoint: the tab to pick in, by its address (used when the title is not given or not found). |
| Tab ID | `tab_id` | Number | No |  | More options | Pick in this open tab of a browser connected through the ONEOFFICEAUTOMATION Browser Connector (from the open-windows list). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `selector` | Text | Selector for the clicked element. |
| `element` | Dictionary | tag, text, type, name and id of the element. |
| `alternatives` | Dictionary | Self-healing fallbacks for the element; pass them as alternatives to element actions. |
| `object` | Dictionary | The captured object: every property with use and match settings, CSS, XPath, iframes, page and a screenshot. |

*Time limit 900 s; needs: browser.*

### Test captured object (design time)

`ooa.browser/test_object` (action)

Open a page, find every element that matches a captured object, outline them for a few seconds and report how many match. Used by Agent Studio's Test button.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Page to open. |
| Captured object | `object` | Dictionary | Yes |  | Main | The captured object to test. |
| Browser | `browser` | Text | No | `edge` | Main | Browser. One of: `edge`, `chrome`, `chromium` |
| Wait (seconds) | `wait_seconds` | Number | No | `10` | More options | How long to wait for the page and the element. |
| Show (seconds) | `show_seconds` | Number | No | `4` | More options | How long to keep the matches outlined. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | How many elements match. |
| `first` | Dictionary | All properties of the element at the object's index, when it exists. |

*Time limit 300 s; needs: browser.*

## Data

### Extract list

`ooa.browser/extract_list` (action)

Read repeated items (product cards, search results, list entries) into a table: one row per item, one column per field found inside it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | A captured item (one card or row): every element like it is read. |
| Selector | `selector` | Text | No |  | Main | Selector of the repeated item, used when no captured item is given. Example: `.product` |
| Fields | `fields` | Dictionary | Yes |  | Main | Column name to a selector inside the item; add @attribute to read an attribute (a@href); an empty selector reads the item's own text. Example: `{"Name": "h3 a", "Price": ".price", "Link": "h3 a@href"}` |
| Maximum items | `max_items` | Number | No |  | Main | Read at most this many items (all when empty). Range: 0 to  |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per item. |
| `count` | Number | Number of items. |

*Time limit 120 s; needs: browser.*

### Extract table

`ooa.browser/extract_table` (action)

Read an HTML table into a Data Table (first row as column names).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No | `table` | Main | Selector of the table element, used when no captured object is given. |
| Lazy load | `lazy_load` | Yes/No | No | No | Main | The page adds rows as you scroll: scroll to the end and read again until no more rows appear. |
| Session | `session` | Text | No | `browser` | More options | Session name. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `30000` | More options | How long to wait for the element, in milliseconds. Range: 0 to  |
| Retries | `retries` | Number | No | `10` | More options | With lazy_load: the most times to scroll and read again. Range: 1 to 500 |
| Retry wait (milliseconds) | `retry_wait_ms` | Number | No | `1000` | More options | With lazy_load: how long to wait after each scroll for new rows, in milliseconds. Range: 0 to 60000 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Table content. |

*Time limit 600 s; needs: browser.*

## Recorder

### Record web actions

`ooa.browser/record` (action)

Open a browser, record your clicks, typing and selections as steps, and return them when you press Stop recording or close the browser. Edge and Chrome stay open afterwards (on the agents' debugging port), so the page can be captured again. Passwords are never recorded.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Page to start on. |
| Browser | `browser` | Text | No | `edge` | Main | Browser to record in. One of: `edge`, `chrome`, `chromium` |
| Timeout (minutes) | `timeout_minutes` | Number | No | `30` | More options | Stop recording automatically after this long. |
| Tab ID | `tab_id` | Number | No |  | More options | Record in this open tab of a browser connected through the ONEOFFICEAUTOMATION Browser Connector (from the open-windows list) instead of a new browser. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `steps` | List | Recorded steps, ready to insert into an agent. |
| `count` | Number | Number of steps. |

*Time limit 3600 s; needs: browser.*

## Conditions

Used in If, Loop while and Wait until steps.

### Element exists

`ooa.browser/element_exists` (condition)

True when at least one element matches the selector (right away, or within the wait).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Session | `session` | Text | No | `browser` | Main | Session name. |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | Wait up to this many seconds for it to become true (0 checks once, right away). Range: 0 to  |

### Element text is

`ooa.browser/element_text` (condition)

True when the element's text (or a field's value) equals, contains, starts with or matches the text (right away, or within the wait).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Text | `text` | Text | Yes |  | Main | Text to compare with. |
| Match | `match` | Text | No | `equals` | Main | How the text is compared; matches takes a regular expression. One of: `equals`, `contains`, `starts_with`, `matches` |
| Ignore case | `ignore_case` | Yes/No | No | Yes | Main | Upper and lower case count as the same. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | Wait up to this many seconds for it to become true (0 checks once, right away). Range: 0 to  |
| Session | `session` | Text | No | `browser` | Main | Session name. |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |

### Element is visible

`ooa.browser/element_visible` (condition)

True when the captured element (or one matching the selector) is visible (right away, or within the wait).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Captured object | `object` | Dictionary | No |  | Main | The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given. |
| Selector | `selector` | Text | No |  | Main | Element selector, used when no captured object is given: CSS (#id, .class), text="Sign in", role=button[name="Save"] or xpath=//input[@name='q']. |
| Session | `session` | Text | No | `browser` | Main | Session name. |
| Window | `window` | Dictionary | No |  | Main | Which window to act in. Leave empty to use the session's current tab. {"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard", "url": "...", "url_match": "contains", "browser": "edge"} switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); {"source": "variable", "variable": "session name"} uses that browser session; {"source": "application", "title": "..."} names a desktop window, which works only when it is a window of an attached browser. Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | Wait up to this many seconds for it to become true (0 checks once, right away). Range: 0 to  |

### Page contains text

`ooa.browser/text_on_page` (condition)

True when the visible page text contains the text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to look for. |
| Session | `session` | Text | No | `browser` | Main | Session name. |

## Loop types

Used as the kind of loop in a Loop step.

### Each page (click Next until it is gone)

`ooa.browser/pages` (loop type)

One pass per page: the first pass is the page as it is, then Next is clicked and the loop waits for the page to change, until Next is no longer there (or is disabled). Gives the page number, from 1.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Next object | `next_object` | Dictionary | No |  | Main | The captured Next button or link. |
| Next selector | `next_selector` | Text | No |  | Main | Selector of the Next button or link, used when no captured object is given. Example: `li.next a` |
| Maximum pages | `max_pages` | Number | No | `100` | Main | Stop after this many pages. Range: 1 to  |
| Wait (milliseconds) | `wait_ms` | Number | No | `10000` | Main | How long to wait for the next page after clicking Next. Range: 0 to  |
| Session | `session` | Text | No | `browser` | Main | Session name. |
