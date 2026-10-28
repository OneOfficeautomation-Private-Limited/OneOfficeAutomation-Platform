# 07 Toolkit architecture

## What this covers / who it is for

This document explains how a toolkit is built and how the platform uses it: the annotations, inputs and outputs, the
panel layout syntax, sessions, capabilities, saved connections and credentials, errors, the manifest that every other
part reads, first-party toolkits versus custom signed toolkits, several versions side by side, how Studio renders a
step's settings panel from the manifest, and how custom connectors are imported from OpenAPI, Swagger and Postman
files. It is for toolkit developers (in-house and customers) and for Studio developers.

## Contents

1. [What a toolkit is](#1-what-a-toolkit-is)
2. [Annotations](#2-annotations)
3. [The action method and ActionContext](#3-the-action-method-and-actioncontext)
4. [Types](#4-types)
5. [Panel layout syntax](#5-panel-layout-syntax)
6. [Sessions, capabilities, connections and credentials](#6-sessions-capabilities-connections-and-credentials)
7. [Errors and retries](#7-errors-and-retries)
8. [The manifest](#8-the-manifest)
9. [Registry, signing and versions](#9-registry-signing-and-versions)
10. [First-party toolkits](#10-first-party-toolkits)
11. [Custom signed toolkits](#11-custom-signed-toolkits)
12. [How Studio renders panels](#12-how-studio-renders-panels)
13. [Custom connectors from API descriptions](#13-custom-connectors-from-api-descriptions)
14. [Captured objects and window choice](#14-captured-objects-and-window-choice)
15. [Patterns for cloud toolkits](#15-patterns-for-cloud-toolkits)
16. [Related documents](#related-documents)

---

## 1. What a toolkit is

A toolkit is one Java class annotated `@OoaPackage`. Its public methods annotated `@Action` become actions; methods
annotated `@Condition` feed If, Else if, While and Wait; methods annotated `@LoopIterator` feed Loop. A step calls an
action as `<toolkit id>/<action id>`, for example `ooa.excel/open`.

```java
@OoaPackage(id = "acme.crm", name = "CRM", version = "1.0.0",
            description = "Read and update CRM records.", publisher = "acme")
public final class CrmPackage {

    @Action(id = "get_customer", name = "Get customer", description = "Find a customer by id.",
            category = "Customers", timeoutSeconds = 60, idempotent = true, capabilities = "network",
            panel = "base_url, customer_id | token, include_orders")
    @Output(name = "customer", type = OoaType.DICTIONARY, description = "Customer record.")
    public Map<String, Object> getCustomer(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, description = "CRM address.") String baseUrl,
            @Input(name = "customer_id", type = OoaType.STRING, description = "Customer id.") String id,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = "API token from the vault.") Object token,
            @Input(name = "include_orders", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                   description = "Also read the orders.") Boolean orders) {
        ctx.info("Reading customer " + id);           // secrets are masked
        return Map.of("customer", Map.of("id", id));
    }
}
```

## 2. Annotations

| Annotation | Attributes |
| --- | --- |
| `@OoaPackage` (class) | `id` (`^[a-z][a-z0-9_.]*$`), `name`, `version` (semantic `x.y.z`), `description` (required), `publisher` (default `oneofficeautomation`) |
| `@Action` (method) | `id` (`lower_snake_case`), `name`, `description` (required), `category` (default `General`), `timeoutSeconds` (default 60), `idempotent`, `capabilities` (`desktop`, `browser`, `network`, `filesystem`, `ai`), `panel` (layout, section 5) |
| `@Input` (parameter) | `name`, `type` (default `ANY`), `description` (required), `label`, `required` (default true), `defaultValue` (a JSON literal), `pattern` (regex), `enumValues`, `example`, `visibleWhen`, `min`, `max` |
| `@Output` (method, repeatable) | `name`, `type`, `description` |
| `@Condition` (method returning boolean) | `id`, `name`, `description` |
| `@LoopIterator` (method returning an `Iterable`) | `id`, `name`, `description` |

`PackageIntrospector` validates the annotations when a toolkit is installed and fails with `PKG.ERROR` on: a missing
description, an id that is not snake case, a non-semantic version, a duplicate action id, a first parameter that is not
`ActionContext`, a parameter without `@Input`, or a `visibleWhen` rule naming an input that does not exist.

`visibleWhen` shows a field only when other fields have certain values: `other=value1|value2`; several conditions are
joined with `;` and all must hold; `!=` negates one. Example: `"iterator=times"` or `"mode=regex;ignore_case=true"`.

When an input has no `label`, `FieldLabels.of(name)` writes one: words split on `_`, abbreviations written out
(`id` → ID, `url` → address (URL), `pii` → personal data), a trailing unit in brackets (`delay_ms` → "Delay
(milliseconds)"), and a list of whole names with their own label (`jdbc_url` → "Database address (JDBC URL)").

## 3. The action method and ActionContext

Signature: `Map<String, Object> method(ActionContext ctx, @Input ... args)`, returning output name to value (or null).
The runner converts each argument to the declared type, checks required, pattern, enum and min/max, then calls the
method on a worker thread with the step's timeout.

| `ActionContext` member | Use |
| --- | --- |
| `runId()`, `stepId()`, `tenantId()` | Identity of the call |
| `workDir()`, `resolvePath(p)` | The run's working folder; relative paths resolve there; with the `confine_paths` service (serverless runs) any path outside it fails with `FILE.OUTSIDE_WORKSPACE` |
| `info`, `warn`, `log(level, msg)` | Run log lines, with every known secret replaced by `****` |
| `redact(text)` | Mask secrets in text written anywhere outside the run |
| `sessions()`, `session(name, type)`, `sessionOrOnly(...)` | Named run sessions |
| `service(name, type)` | Platform services: `vault`, `repository`, `bot_invoker`, `global_values`, `model_connections`, `connections`, `screen_capture` … (`SERVICE.UNAVAILABLE` when missing) |
| `checkCancelled()` | Long-running actions call it to stop promptly (`RUN.CANCELLED`) |

## 4. Types

`OoaType`: `STRING`, `NUMBER` (`BigDecimal`), `BOOLEAN`, `DATETIME`, `LIST`, `DICTIONARY`, `TABLE`, `RECORD`, `FILE`,
`WINDOW`, `UIELEMENT`, `CREDENTIAL` (`SecureString`), `ANY`. Each maps to a Flow type name (`flowName()`), so the same
names appear in agent variables, the manifest and Studio's editors.

## 5. Panel layout syntax

`@Action(panel = "...")` lays out the step's settings panel and must name **every input exactly once**
(`ActionSpec.panelLayout`, checked at install):

| Syntax | Meaning |
| --- | --- |
| `a, b, c` | Fields in this order |
| `… \| d, e` | Fields after the single `\|` go under **More options** |
| `[Label: x, y]` | A tick box with that label that reveals `x` and `y` |
| `(Label: Option A = a, b / Option B = c)` | A choice that shows only the chosen option's fields; an option without `=` takes its first field's label; an option with a label and no fields means "leave the others empty" |

Example: `"a, b, [Has a password: p, q], c | d, e"`. The parsed layout goes into the manifest as
`x-ooa-panel: {main: [...], more: [...]}` where items are field names, `{tick, fields}` or `{choice, options}`.
Without `panel`, inputs appear in declaration order and rarely used ones (not required, with a default, after the
first few) go under More options.

## 6. Sessions, capabilities, connections and credentials

- **Sessions**: an "open" action puts an object in `ctx.sessions()` under a name (for example `excel`); later actions
  take a `session` input and call `ctx.sessionOrOnly(name, "excel", Workbook.class)`, which also finds the only open
  session when the step kept the default name. Implement `AutoCloseable` so the runner closes it at the end of the run.
- **Capabilities** describe what a runner needs. `ApiTask` agents may not use actions requiring `desktop`. Serverless
  runs allow only a fixed toolkit list.
- **Credentials**: any secret input is `OoaType.CREDENTIAL`; the value arrives as a `SecureString` and is revealed only
  when sent to the target. Values typed into screens should call `SecureString.checkPasswordField` so password-only
  vault values are refused for other fields.
- **Saved connections**: a step's `connection` fills empty inputs of the action from a named connection (values plus
  vault references). `GET /api/connection-kinds` lists, per toolkit, the inputs that many actions share, which become
  the fields of a connection.
- **Child agents**: an action can run a published agent through `ctx.session(ChildAgents.SESSION, ChildAgents.class)`.

## 7. Errors and retries

- Throw `ActionException(code, message, suggestedFix)`; codes are `NAME.CODE`, upper case and dotted (`GITHUB.NOT_FOUND`).
- Mark transient failures retryable (HTTP 429 and 5xx in `HttpJson`); step retry policies then apply.
- Use code tails that the cross-toolkit Catch kinds recognise: `…NOT_FOUND`, `…TIMEOUT`, `AUTH_FAILED`, `UNAUTHORIZED`,
  `FORBIDDEN`, `RATE_LIMITED` (see `ErrorCodes`).
- Unexpected exceptions become `ACTION.UNEXPECTED`; a class missing at run time becomes `ACTION.NOT_IN_THIS_PROGRAM`.

## 8. The manifest

`PackageIntrospector.manifest(cls)` produces JSON (`manifestVersion` 1):

```json
{"id": "acme.crm", "name": "CRM", "version": "1.0.0", "description": "...", "publisher": "acme",
 "actions": [{"id": "get_customer", "name": "Get customer", "description": "...", "category": "Customers",
   "inputs": {"type": "object", "properties": {"customer_id": {"type": "string", "description": "...",
       "x-ooa-type": "String", "x-ooa-label": "Customer ID"}}, "required": ["customer_id"], "additionalProperties": false},
   "outputs": {"customer": {"x-ooa-type": "Dictionary", "description": "..."}},
   "timeoutSeconds": 60, "idempotent": true, "capabilities": ["network"], "x-ooa-panel": {"main": [...], "more": [...]}}],
 "conditions": [...], "iterators": [...]}
```

Input properties may also carry `default`, `enum`, `pattern`, `minimum`, `maximum`, `examples` and
`x-ooa-visible-when`. Consumers of the manifest: Studio (Actions panel, settings panels, validation), `FlowValidator`,
the Studio AI assistant, the Command Center's Toolkits page and code analysis, `connection-kinds`, MCP tool schemas,
`ooa manifest`, and `docs/COMMAND_CATALOG.md`.

## 9. Registry, signing and versions

`PackageRegistry`:

- `trust(publisherKey)` adds a publisher's Ed25519 public key; an optional allow-list limits publishers per tenant.
- `install(class, signature)` inspects the class, recomputes `PackageSigning.digest` (SHA-256 over the canonical
  manifest and the class file bytes) and verifies the signature. Errors: `PKG.UNSIGNED`, `PKG.PUBLISHER_NOT_ALLOWED`,
  `PKG.UNTRUSTED`, `PKG.TAMPERED`, `PKG.BAD_SIGNATURE`.
- Several **versions** of a toolkit can be installed. The default is the newest unless set (`setDefault`).
  `resolve(id, range)` uses the default if it satisfies the agent's `requires.packages` range, else the newest version
  that does (`PKG.VERSION_MISMATCH` otherwise). Ranges: `^1.2.0`, `~1.2.0`, `>=1.2.0`, exact, `*`, `latest`.
- `recordUsage` keeps which agents used which version, for the Toolkits page.
- Toolkits can be **switched off** per Command Center (`toolkit_settings`); check-in refuses agents that use a
  switched-off toolkit.

## 10. First-party toolkits

- Built-in classes are listed in `FirstPartyPackages.ALL`; optional-module classes by name in
  `FirstPartyPackages.OPTIONAL` (loaded only when present).
- `FirstPartyPackages.registry()` signs them at start with a generated first-party key; release builds are meant to
  ship pre-signed jars.
- First-party ids start with `ooa.` (or `core`); the architecture test checks naming and descriptions.
- Current inventory: see [02-components-and-modules.md](02-components-and-modules.md#6-toolkit-inventory-by-module).
  The AWS EC2 toolkit `ooa.aws_ec2` (connect and disconnect a session, describe instances, instance status, start,
  stop, reboot and terminate, enable and disable monitoring, launch instances, set tags, wait for a state) is being
  added; like the other AWS integrations it signs requests itself with AWS Signature V4 instead of using the AWS SDK.

Adding a first-party toolkit: create the class (in `packages`, or a new optional module for heavy libraries), register
it, write a test against a local fake server, add the toolkit to `docs/COMMAND_CATALOG.md`, and give it a row in
`GROUP_LIST` in `studio.js` (otherwise it appears under "More toolkits").

## 11. Custom signed toolkits

```mermaid
sequenceDiagram
    participant Dev as Toolkit developer
    participant Adm as Administrator
    participant CC as Command Center
    participant R as Runner / RunWorker
    Dev->>Dev: ooa toolkit keygen --publisher acme  (acme.key, acme.pub)
    Dev->>Dev: build jar with the SDK; ooa toolkit sign --jar crm.jar --class com.acme.CrmPackage --key acme.key
    Adm->>CC: Toolkits > Publishers: add acme.pub (toolkit_publishers)
    Adm->>CC: Toolkits > Upload crm.jar
    CC->>CC: read META-INF/ooa-toolkit.json, check signature, digest, id, version; load in own class loader
    CC->>CC: store jar in repository files, row in custom_toolkits
    Note over CC,R: a run that uses acme.crm
    CC-->>R: job.custom_toolkits [{id, version, publisher, public_key, sha256, jar}]
    R->>R: check SHA-256 and signature again, ToolkitJar.load, registry.install
```

- `ToolkitJar.sign` writes `META-INF/ooa-toolkit.json` with `class`, `publisher`, `digest` and `signature`.
- `ToolkitJar.load` opens each jar in its **own `URLClassLoader`** (named `ooa-toolkit-<sha prefix>`) with the SDK from
  the parent, so two versions of the same toolkit live side by side and agents choose by version range.
- `ooa toolkit inspect --jar FILE` shows id, version, publisher and actions.
- Custom toolkits travel with each job, so runners need no installation; a runner's missing-toolkit check ignores them.
- In the SaaS service uploads are refused (`SAAS.NOT_AVAILABLE`); custom toolkits run only on customers' own runners.

## 12. How Studio renders panels

Studio (`studio.js`) never contains toolkit-specific forms. It reads `GET /studio/api/packages` (the manifests) and:

1. Builds the **Actions panel** grouped by technology (`GROUP_LIST`, with `groupOf()` splitting toolkits that cover
   several technologies, for example Scripts by language).
2. For the selected step, builds the **settings panel** from the action's `inputs` schema: an editor per `x-ooa-type`
   (text, number with min/max, Boolean, enum drop-down, variable picker, table, dictionary, credential reference,
   captured object, window), labelled with `x-ooa-label`.
3. Applies `x-ooa-panel` when present: main fields, tick boxes that reveal fields, choices that show one option's
   fields, and More options. Remembers ticks and choices made before any field has a value.
4. Hides fields whose `x-ooa-visible-when` rule does not hold for the step's current values.
5. Validates live with the manifest (required inputs, unknown inputs, types) and the server-side `validate` route.

The same manifest drives the AI assistant (`assistant/build`), which drafts agents by choosing actions and filling
their inputs.

## 13. Custom connectors from API descriptions

The `ooa.connector` toolkit turns any REST API into callable operations without Java code:

| Action | Purpose |
| --- | --- |
| `import_connector` | Read an OpenAPI 3.x, Swagger 2.0 (JSON, or YAML when SnakeYAML is present) or Postman v2.1 file and save a definition |
| `list_connectors`, `list_operations`, `describe_operation` | Browse saved connectors |
| `call_operation` | Call an operation by id with typed parameters, authentication and response handling |

`ConnectorImporter` writes `connectors/<name>.connector.json` (format `ooa.connector/v1`) with `servers`, `auth`
schemes (`api_key` in header/query/cookie, `basic`, `bearer`, `oauth2_client_credentials`, `aws_sigv4`), `security`,
and operations with parameters and schemas. Local `$ref`s are resolved in place; recursive references are cut and
marked `x-recursive`. `ConnectorStore` reads and writes these files for the toolkit (in the run's workspace) and for
Studio (`/studio/api/connectors`). Credentials for connectors come from the vault, never from the file.

## 14. Captured objects and window choice

Web and desktop UI actions find an element by a **captured object** instead of one selector. The format is shared by
web and desktop, so Studio has one editor for both (`captureEditor` in `studio.js`).

### 14.1 Web objects (`WebObjects`, module `packages-browser`)

```json
{"technology": "web",
 "page": {"url": "...", "url_match": "starts_with", "title": "...", "title_match": "..."},
 "frames": ["iframe#main"],
 "search_by": "properties",
 "index": 0, "ignore_case": false, "self_heal": true,
 "properties": [{"name": "id", "value": "save", "match": "equals", "use": true}],
 "rect": {"left": 355, "top": 517, "width": 586, "height": 41},
 "anchor": {"technology": "web", "properties": [...], "offset": {"dx": 0, "dy": 26}},
 "screenshot": "base64 PNG"}
```

| Part | Meaning |
| --- | --- |
| `properties` | Only entries with `use: true` are tested. `match` is `equals`, `not_equals`, `contains`, `starts_with`, `ends_with`, `wildcard` or `regex` |
| `search_by` | `properties` (default), `css` or `xpath`. A ticked `css` or XPath property with `equals` means "the element that selector finds" |
| `frames` | The iframe path, outermost first |
| `index` | Which match to use when several elements match |
| `self_heal` | On by default; `false` turns the self-healing search off |
| `anchor` | Optional. When the element is not found, or several match, the anchor is found by its own properties and the candidate nearest to anchor position plus `offset` is used. When the element's own properties no longer match, only elements with the same tag within 250 pixels of that spot count |
| `rect`, `screenshot` | Position on the page and a picture, both from capture time |

- **One property reader.** The page script `ooa-page.js` (`WebObjects.LIB_JS`) reads the properties. Capture, the
  Recorder, the run-time search, `get_all_properties` and the browser extension all use it, so a property name means
  the same thing everywhere.
- **Property names:** the attributes (`tag`, `id`, `name`, `type`, `class`, `href`, `src`, `alt`, `title`, `role`,
  `aria-label`, `placeholder`, `data-*` and any other attribute), `text` (inner text), `value`, `label`, `dom_xpath`
  (short XPath), `xpath` (full XPath), `css`, `path` (child positions from the page root), `parent`, `tag_index`,
  `source_index`, `left`, `top`, `width`, `height`, `visible`, `enabled`, `checked`, `selected`, `child_count`,
  `in_frame`, `frame_url`, `inner_html` and `outer_html`.
- **`element_action` options:** `wait_for_page_ms` waits for the page to load first (`BROWSER.PAGE_TIMEOUT` when it does
  not); `if_not_found` is `error` (default) or `continue`, which skips the step and sets `found` to false;
  `run_in_background` leaves the tab where it is and acts on the element directly, without bringing the window to the
  front.
- **Recorder** (`ooa.browser/record`): each recorded click, typing and selection gets the full captured object,
  including its iframe path, and its window. `stepsFromEvents` turns the recording into steps.

### 14.2 Desktop objects (`DesktopObject`, module `packages-windows`)

```json
{"technology": "desktop",
 "window": {"title": "Untitled - Notepad", "title_match": "wildcard", "process": "notepad",
            "path": "C:\\Windows\\notepad.exe", "resize": {"width": 1200, "height": 800}},
 "properties": [{"name": "ControlType", "value": "Edit", "match": "equals", "use": true}],
 "path": [{"ControlType": "Window", "Name": "..."}],
 "index": 0,
 "anchor": {"technology": "desktop", "properties": [...], "offset": {"dx": 0, "dy": 30}},
 "screenshot": "base64 PNG"}
```

- **Matching** runs in Java and has no native code, so it is unit-tested. UI Automation is reached through the helper
  `UiaBridge` (PowerShell with .NET `UIAutomationClient`). Text matches ignore case, except `regex`, which is used as
  written and may match anywhere in the value. Wildcards (`*`, `?`) must match the whole value.
- **Properties** recorded at capture: the UI Automation properties (Name, AutomationId, ClassName, ControlType,
  FrameworkId, Value, HelpText and others, the patterns and states), plus `Text`, `Path` (child positions from the
  window), `XPath` (`ControlType[n]` steps, the last with its AutomationId or Name), `Index`, `Parent`, and `Left`,
  `Top`, `Width`, `Height` relative to the window.
- **Quick search first.** The helper returns a short list of properties for every control while searching. Only when a
  ticked rule needs another property (path, XPath, states, text) does it read the details (`DesktopObject.needsDetail`).
- **Anchor:** the nearest control of the same type to anchor plus offset, measured on the screen rectangles.
- **`window.resize`** sets the window's size before the search, so positions match the capture.
- **`object_action`** has `if_not_found` as on the web, and `run_in_background`, which uses UI Automation patterns only:
  no window activation, mouse or keys.
- **Recording** (`ooa.desktop_ui/record`): the helper's `record` operation installs low-level mouse and keyboard hooks
  and shows a **Stop recording** bar, on their own thread. It captures the control under each mouse press and the text
  of a field when the person leaves it; password fields are never read. Progress lines arrive before the answer
  (`UiaBridge.call(..., progress)`). `DesktopUiPackage.stepsFromEvents` turns the result into `object_action` steps.

### 14.3 Window choice of web actions (`BrowserWindows`)

Every web element action (`element_action`, `click`, `type_text`, `select_option`, `get_text`, `get_attribute`,
`wait_for`, `extract_table`, `download`, `screenshot`) and the conditions `element_exists` and `element_visible` take an
optional `window` input. Values may contain `$variables`; the Runner resolves them first.

```json
{"source": "browser", "title": "Invoice * - Shop", "title_match": "wildcard",
 "url": "shop.test/invoices", "url_match": "contains", "browser": "edge", "variable": "browser"}
```

| `source` | What it finds |
| --- | --- |
| `browser` (default) | The first tab whose title and/or address matches, among every page of the run's browser sessions (the step's own session first, then the others), then tabs reached through the browser connector. If nothing matches, a browser listening on the default debugging port 9222 is attached once as session `attached_9222` and searched too. The tab becomes its session's current page |
| `variable` | The browser session with that name. `attach` and `open_for_automation` return a ready window value |
| `application` | A desktop window title. The browser's suffix (for example " - Microsoft Edge") is removed and the rest is matched against tabs as above. Any other desktop window cannot take web actions; the error points to the Desktop objects toolkit |

- Match modes: `wildcard` (default for the title), `contains` (default for the address), `equals` or `regex`. Text
  matches ignore case unless `case_sensitive` is set.
- The action waits up to its `timeout_ms` for the window, then fails with `BROWSER.WINDOW_NOT_FOUND`, which lists up to
  20 open tabs. Conditions return false instead.

### 14.4 Browsers that stay open, and browsers already open

Browsers are reached over the DevTools protocol (Playwright `connectOverCDP`):

| Action | Behaviour |
| --- | --- |
| `ooa.browser/open` with `keep_open` (default true) | Starts Edge on debugging port 9222 or Chrome on 9223, with a profile under `.ooa/browser-profiles/<browser>-<port>` in the workspace. Ending the run only disconnects; **Close browser** closes the windows that session opened |
| `ooa.browser/attach` | Connects to `http://127.0.0.1:9222` (or another endpoint) as a session. Closing that session only disconnects |
| `ooa.browser/open_for_automation` | Starts Edge or Chrome with `--remote-debugging-port` and its own profile folder, or reuses the browser already listening on that port |
| `ooa.browser/list_windows` | Lists reachable tabs (the run's sessions and `/json/list` of each endpoint) and, on Windows, desktop window titles (one `Get-Process` call, started with an argument list) |

A browser started the usual way has no debugging port and cannot be reached this way; recent browsers also refuse a
debugging port on the everyday profile. The browser connector (extension plus native host, see
[06-security-architecture.md](06-security-architecture.md#9-studio-and-local-components)) covers the person's own tabs.

Studio uses the same code at design time: `GET /studio/api/windows[?endpoints=...&desktop=false]` returns
`{"windows": [{source, title, url, browser}]}` for the window editor; `POST /studio/api/capture` (with `endpoint`,
`tab_title`, `tab_url` or `tab_id` to pick inside an open tab), `capture/test` and `capture-desktop` capture and test
objects.

## 15. Patterns for cloud toolkits

- Use `HttpJson` (JDK `HttpClient`): base-URL input, Credential token input, JSON bodies, paging, typed errors with the
  service's own message, retryable 429/5xx, and secret masking in logs. `HttpOptions` adds proxy and TLS options.
- OAuth token requests for Salesforce, ServiceNow and Workday go through `EnterpriseAuth` (form POST; secrets in the
  body only; the token returns as a Credential).
- AWS requests are signed with `AwsSigV4` (JDK crypto only).
- XML goes through `XmlSupport`; never print to the console.
- Test with a local fake HTTP server (`ToolkitTestSupport`, `RestToolkitTestSupport`): check method, path, query,
  headers, body, outputs, error codes, and that no secret appears in logs.

## Related documents

- [README.md](README.md)
- [01-system-overview.md](01-system-overview.md)
- [02-components-and-modules.md](02-components-and-modules.md)
- [03-agent-model-and-runner.md](03-agent-model-and-runner.md) – how steps call actions
- [04-command-center-internals.md](04-command-center-internals.md)
- [05-data-model.md](05-data-model.md)
- [06-security-architecture.md](06-security-architecture.md) – signing and trust
- [08-ai-architecture.md](08-ai-architecture.md)
- [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md)
- [10-extensibility-and-integration.md](10-extensibility-and-integration.md)
