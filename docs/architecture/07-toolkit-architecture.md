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
14. [Patterns for cloud toolkits](#14-patterns-for-cloud-toolkits)
15. [Related documents](#related-documents)

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

## 14. Patterns for cloud toolkits

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
