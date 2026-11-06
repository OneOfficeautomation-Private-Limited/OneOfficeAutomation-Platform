# Document Automation: the contract with DocFlow

AI > Document Automation is One CommandCenter's front end for **DocFlow**. DocFlow is a separate Python service kept
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
- **Screens**: `platform/ui/src/docflow` (`docflow.html`, served at `/documents/`). The data calls are in `http.ts`.
- **Background**: Task inbox tasks from DocFlow's review queue (every 20 s) and email inboxes (every 30 s). Both run
  only on the cluster leader. Inbox state is the `docflow_mail` table, schema version 33.
- **Runner**: toolkit `ooa.documents`, action "Send document to process".
- **Menu**: `DocumentAutomationPlugin` (a `ConsolePlugin`).

## Where DocFlow is and how it is trusted

| | On premises | SaaS (each region) |
|---|---|---|
| Address | Settings `docflow_url` (empty means `http://127.0.0.1:8085`) | `OOA_DOCFLOW_URL`, set by the region's operator |
| Token | Settings `docflow_token_ref`, a vault reference `folder/credential/attribute` | `OOA_DOCFLOW_TOKEN` |
| Tenant sent | `default` | The tenant's id |

DocFlow has the same token in `DOCAUTO_API_TOKEN`, and the Command Center's address in `DOCAUTO_PLATFORM_URL` for
its callbacks. Every request carries `Authorization: Bearer <token>`, `X-OOA-Tenant` and `X-OOA-User` (the display
name, URL-encoded). A tenant can't point Document Automation somewhere else.

## Permissions

| Permission | Allows |
|---|---|
| `docs.view` | See processes, documents, results (every GET) |
| `docs.review` | Approve, reject, reclassify documents |
| `docs.manage` | Add documents; change processes, document types, reference data |
| `docs.engines` | Engines, routing, cloud opt-in (every `admin/` route) |

## Routes the platform uses

All are under DocFlow's `/api/docs/v1/`.

- **Screens**:
  - Processes: `df/overview`, `df/flows`, `df/flows/{id}`, `df/flows/{id}/live`, `df/flows/{id}/documents`
  - Documents: `df/documents/{id}`, `df/documents/{id}/review`, `df/documents/{id}/reclassify`, `df/review`,
    `documents/{id}/result`
  - Document types: `df/doctypes`, `df/doctypes/{id}`, `df/doctypes/suggest`, `df/library`
  - Reference data: `df/reftables`, `df/reftables/{id}`
  - Test lab: `df/test`
  - Engines and settings: `providers`, `costs`, `usage`, `admin/settings`, `admin/providers/{id}/test`
- **Also allowed by the proxy**: `documents/{id}/page/{n}.png`, `documents/{id}/file` and any other `df/...` route.
- **Never forwarded**: DocFlow's self-update (`admin/update`, `admin/installer.bat`).

DocFlow's `PLATFORM-CONTRACT.md` lists the methods, inputs and response fields for each route.

## How versions work

1. **Each side has its own version number.** DocFlow's is in its `VERSION` file (3.1.0 now). It reports it at
   `GET /api/docs/v1/admin/version`, which needs no token.
2. **`/api/docs/v1` only grows.** DocFlow may add routes, inputs and fields. Removing or changing something the
   platform uses means a new `/api/docs/v2`, and v1 stays for at least one platform release.
3. **Each platform release pins one tested DocFlow version** and states the range it works with. A newer DocFlow in
   that range can be installed on its own, for example to fix a bug.
4. **Upgrade DocFlow first, then the Command Center.** Back up both first. Each side upgrades only its own database:
   the platform its numbered schema versions, DocFlow its own PostgreSQL database.
5. **Release approval.** Each DocFlow release and each change to the pinned version is approved before it is merged
   or published.

### Where the pin lives

`platform/control-room/src/main/resources/docflow-compat.properties` holds the pin:
- `contract`: the API version the Command Center calls (`v1`);
- `pinned`: the DocFlow version this release is tested with (3.1.0);
- `minimum`: the oldest DocFlow version that works (3.1.0);
- `ref`: the oneoffice-docauto commit or tag that CI checks out (a commit until DocFlow has its first tag).

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
`gradlew :control-room:test --tests "*DocFlowLiveTest*" -Pooa.test.docflow=http://127.0.0.1:8085 -Pooa.test.docflow.token=<token> -Pooa.test.docflow.port=18766 -Pooa.test.docflow.samples=<oneoffice-docauto>/samples`.
DocFlow's `DOCAUTO_PLATFORM_URL` must then be `http://127.0.0.1:18766`.

## Known gaps (2026-11-07)

1. **Events.** DocFlow posts document events to `/api/docflow-callback/events`. The Command Center has no such route
   and answers 404, which DocFlow does not retry. Nothing breaks, because the Command Center reads the review queue
   every 20 s instead. Adding the route is a later phase.
2. **Which DocFlow branch.** The platform needs DocFlow's `document-automation` branch. Its `main` branch lacks the
   platform "Start a bot" callback, the email inbox settings, the test lab (`df/test`) and PostgreSQL storage. The
   first DocFlow release should come from `document-automation` once it is merged into `main`.
3. **Not packaged yet.** DocFlow isn't in `platform/docker` compose files or the Windows installer yet (phase 5),
   and there is no Helm chart yet (phase 6).
