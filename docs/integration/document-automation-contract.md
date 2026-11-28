# Document Automation: the contract with DocFlow

Build > Document Automation is One CommandCenter's front end for **DocFlow**. DocFlow is a separate Python service kept
in the repository `OneOfficeautomation-Private-Limited/oneoffice-docauto`. This page is the platform's side of the
agreement between the two. DocFlow keeps the same agreement in its `PLATFORM-CONTRACT.md`, and its
`tests/test_platform_contract.py` fails if DocFlow stops serving something listed here.

## How the two fit together

```
browser ──> /api/docflow/<route> ──(DocFlowProxy: permission check)──> DocFlow /api/docs/v1/<route>
runner  ──> /api/agent/docflow/documents ("Send document to process") ──> DocFlow df/flows/{id}/documents
DocFlow ──> /api/docflow-callback/start-bot  ("Start a bot" deliveries)
DocFlow ──> /api/docflow-callback/events     (not built here yet: see Known gaps)
```

- **Proxy**: `DocFlowProxy.java` forwards only an allow-list of routes and adds the token, tenant and person.
  The browser never sees DocFlow's address or token.
- **Screens**: `platform/ui/src/modules/document-automation` (`docflow.html`, served at `/documents/`). The data calls are in `http.ts`.
- **Background**: Task inbox tasks from DocFlow's review queue (every 20 s) and email inboxes (every 30 s). Both run
  only on the cluster leader. Inbox state is the `docflow_mail` table, schema version 33.
- **Runner**: toolkit `ooa.documents`, action "Send document to process".
- **Menu**: `DocumentAutomationPlugin` (a `ConsolePlugin`).

## Where DocFlow is and how it is trusted

| | On premises | SaaS (each region) |
|---|---|---|
| Address | Settings `docflow_url`; when empty, `OOA_DOCFLOW_URL` (set by the containers), then `DATA\config\ooa-docflow.properties` (set by Windows setup `-DocFlow`); else `http://127.0.0.1:8085` | `OOA_DOCFLOW_URL`, set by the region's operator |
| Token | Settings `docflow_token_ref`, a vault reference `folder/credential/attribute`; when empty, `OOA_DOCFLOW_TOKEN`, then the token in that file, encrypted with the vault key | `OOA_DOCFLOW_TOKEN` |
| Tenant sent | `default` | The tenant's id |

DocFlow has the same token in `DOCAUTO_API_TOKEN`, and the Command Center's address in `DOCAUTO_PLATFORM_URL` for
its callbacks. Every request carries `Authorization: Bearer <token>`, `X-OOA-Tenant` and `X-OOA-User` (the display
name, URL-encoded). A tenant can't point Document Automation somewhere else.

## Permissions

| Permission | Allows |
|---|---|
| `docs.view` | See processes, documents, results (every GET) |
| `docs.review` | Approve, reject, reclassify documents |
| `docs.manage` | Add documents; change processes, document types, reference data; browse DocFlow's folders (`df/folders`) |
| `docs.engines` | Engines, routing, cloud opt-in, the folders processes may use (every `admin/` route, `df/folders/preview`) |

## Routes the platform uses

All are under DocFlow's `/api/docs/v1/`.

- **Screens**:
  - Processes: `df/overview`, `df/flows`, `df/flows/{id}`, `df/flows/{id}/live`, `df/flows/{id}/documents`
  - Documents: `df/documents/{id}`, `df/documents/{id}/review`, `df/documents/{id}/reclassify`, `df/review`,
    `documents/{id}/result`
  - Document types: `df/doctypes`, `df/doctypes/{id}`, `df/doctypes/suggest`, `df/library`
  - Reference data: `df/reftables`, `df/reftables/{id}`
  - Test lab: `df/test`
  - Folder picker (DocFlow 3.2.0): `df/folders` (GET, `?path=`; POST makes a folder), `df/folders/preview`; listings
    name only the processes the person may see; `?q=` finds a folder by name (3.3.0)
- **Callbacks DocFlow makes** (3.3.0, besides `start-bot`): `docflow-callback/send-mail` (e-mail deliveries and
  checks that tell people; sent through Settings > Mail as the process's author) and `docflow-callback/secret` (a
  vault entry for a cloud storage delivery, only one its author may use). Both need DocFlow's service token and are
  audited (`docflow.mail`, `vault.use`). An older DocFlow simply never calls them; the minimum stays 3.1.0.
- **DocFlow 3.4.2** (28 Nov 2026): failed documents carry their `error` in the documents list (`fields=true`), `POST
  df/reftables/preview` shows what a table upload gives without saving, and `POST df/test` takes `compare=fields` to
  compare field-reading engines. The screens use each only when the DocFlow in use has it.
- **DocFlow 3.4.1** (28 Nov 2026): `review.mode` (unsure, all, none) for how much goes to a person; a webhook header
  value `vault:folder/credential/attribute` read at delivery time (the Command Center checks the vault entry and stamps
  `requestedBy` on save); `POST df/flows/{id}/try`, a dry run that keeps and sends nothing; and a draft process holds its
  deliveries (logged as `Held: this process is a draft`). The screens offer the review choice and the try only when the
  DocFlow in use has them.
- **AI engine keys from Model connections** (DocFlow 3.4.0, I-4): an administrator (`connections.manage`) chooses the
  Model connection each AI engine takes its key from at `/api/docflow/engine-connections` (GET: choices,
  connections that fit, providers per engine; PUT `{engine, connection}`, empty clears). Kept in setting
  `docflow_engine_connections`; audited `docflow.engine_connection`; DocFlow is then told `POST admin/reload`.
  DocFlow asks `docflow-callback/credentials` (`{"engines": [...]}`, service token) when it loads its engines and
  gets each chosen, fitting connection's provider, model, address and key (`vault.use` audited); it keeps them in
  memory only. Engines and providers: claude-anthropic, gemini-google_vertex (an API key), bedrock-aws_bedrock
  (`ACCESS_KEY_ID:SECRET`, region from the address), azure-openai-azure_openai, llm-cloud-openai or
  openai_compatible, llm-local-openai_compatible. An engine with no connection keeps its typed key.
  - Engines and settings: `providers`, `costs`, `usage`, `admin/settings`, `admin/providers/{id}/test`
- **Also allowed by the proxy**: `documents/{id}/page/{n}.png`, `documents/{id}/file` and any other `df/...` route.
- **Never forwarded**: DocFlow's self-update (`admin/update`, `admin/installer.bat`).

DocFlow's `PLATFORM-CONTRACT.md` lists the methods, inputs and response fields for each route.

## How versions work

1. **Each side has its own version number.** DocFlow's is in its `VERSION` file (3.4.2 now). It reports it at
   `GET /api/docs/v1/admin/version`, which needs no token.
2. **`/api/docs/v1` only grows.** DocFlow may add routes, inputs and fields. Removing or changing something the
   platform uses means a new `/api/docs/v2`, and v1 stays for at least one platform release.
3. **Each platform release pins one tested DocFlow version** and states the range it works with. A newer DocFlow in
   that range can be installed on its own, for example to fix a bug.
4. **Upgrade DocFlow first, then the Command Center.** Back up both first. Each side upgrades only its own database:
   the platform its numbered schema versions, DocFlow its own PostgreSQL database.
5. **Release approval.** Each DocFlow release and each change to the pinned version is approved before it is merged
   or published.

### What the console shows

The Build > Document Automation page has a **Service** card. It shows the DocFlow version in use and whether it fits
this release. The card is a warning only and never blocks anything. The Command Center asks DocFlow's
`admin/version` at most once a minute. The page reads the answer from `GET /api/docflow/version`, which needs
`docs.view`.

| Status | Means |
|---|---|
| Compatible | Between `minimum` and `pinned` |
| Newer than tested | Same major version, newer than `pinned`. It should work |
| Needs an update | Older than `minimum` |
| Not compatible | Another major version, which is another contract |
| Not reachable, Not set up, Version unknown | DocFlow does not answer, is not configured, or does not say its version |

### Where the pin lives

`platform/modules/document-automation/src/main/resources/docflow-compat.properties` holds the pin:
- `contract`: the API version the Command Center calls (`v1`);
- `pinned`: the DocFlow version this release is tested with (3.4.2);
- `minimum`: the oldest DocFlow version that works (3.1.0);
- `ref`: the oneoffice-docauto release tag that CI checks out (`v3.4.2`; v3.1.0 was the first release, 7 Nov 2026).

CI's job **"Document Automation with the real DocFlow (Linux)"** checks out DocFlow at `ref`, starts it with
PostgreSQL and Tesseract, and runs `DocFlowLiveTest` against it. The test covers:
- that DocFlow reports the pinned version;
- the screens' routes through the proxy;
- a live process with a "Start a bot" delivery;
- an invoice uploaded and read;
- DocFlow calling back to start the agent;
- the review queue turning into Task inbox tasks;
- a wrong token reported plainly.

To move to a newer DocFlow, change `ref`, and `pinned` if the version changed, on a branch of its own. Then let this
job pass before asking for the merge. To run the test on a PC that has a DocFlow running:
`gradlew :document-automation:test --tests "*DocFlowLiveTest*" -Pooa.test.docflow=http://127.0.0.1:8085 -Pooa.test.docflow.token=<token> -Pooa.test.docflow.port=18766 -Pooa.test.docflow.samples=<oneoffice-docauto>/samples`.
DocFlow's `DOCAUTO_PLATFORM_URL` must then be `http://127.0.0.1:18766`.

## Known gaps (2026-11-07)

1. **Events.** DocFlow posts document events to `/api/docflow-callback/events`. The Command Center has no such route
   and answers 404, which DocFlow does not retry. Nothing breaks, because the Command Center reads the review queue
   every 20 s instead. Adding the route is a later phase.
2. **Packaging.** DocFlow is in the containers through the add-on files `docker/compose.docflow.yaml` and
   `docker/compose.saas.docflow.yaml`, which CI tests in the "Container image" job. On Windows, setup's `-DocFlow`
   option installs it online, and CI's "Document Automation installed on Windows" job tests that option. There is
   no Helm chart yet (phase 6), and no offline Windows pack. DocFlow's installer still makes its own desktop
   shortcuts and runs its tests during a managed install; asking DocFlow to skip those is a follow-up.
