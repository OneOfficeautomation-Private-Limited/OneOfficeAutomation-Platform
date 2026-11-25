# OneChat: requests from the platform (25 Nov 2026)

What the platform needs from the OneChat repository (`OneOfficeautomation-Private-Limited/OneOfficeAutomation-OneChat`)
so the flow-writing model plugs in cleanly. It is based on a read of `main` at commit 1b224fa (21 Nov 2026). The
platform side is described in [one-chat-contract.md](one-chat-contract.md), sections 15 to 17. These are requests, not
changes: the OneChat repository is its developer's.

| # | Request | Why | Contract |
| --- | --- | --- | --- |
| 1 | Build the action catalog from `docs/reference/action-catalog.jsonl` (or `ooa catalog`) at the platform release's tag, instead of parsing the toolkit reference pages | On 25 Nov the model's catalog had 1,374 entries. The release has 1,538 (1,439 actions), so 133 actions were missing. The new file is stable and has a checksum | Section 17 |
| 2 | Add `platform_catalog_sha256` to `GET /api/assistant/v1/admin/version`: the `sha256` from the header of the catalog the model was built from | Settings then shows **Built for this release** or **Built for another release** instead of **Catalog not stated** | Section 16 |
| 3 | Keep `schema_sha256` as the SHA-256 of `contracts/flow-schema/flow.v1.json` (as written, or with `\n` line ends) | Settings compares the flow rules too | Section 16 |
| 4 | Turn the direct cloud escalation (`allow_escalation` with `ANTHROPIC_API_KEY`, or `escalate_url`) off in OOA installations, and leave it off by default | Decision 2: model calls go through the platform's AI controls. The platform now has **If the OneChat model fails, try** (another Model Connection) for this | Section 16 |
| 5 | Keep the model names `ooa-flow-assistant` and `-fast`, `-smart`, `-ultra` | Studio sends the mode chosen for each request as the model name | Section 16 |
| 6 | Keep `POST /v1/feedback` accepting `{request, verdict: inserted\|discarded, flow: {variables, steps}, note}` | One CommandCenter sends it when the company allows training on its requests | Section 16 |
| 7 | Release in versions: tags, a container image, and the model files as release files with their test report (not a Google Drive folder) | The platform pins a tested version, as it does with DocFlow | Section 8 |
| 8 | List the licences of the pre-training and chat datasets before the model is sold | The repository's own explainer says these are not checked yet | n/a |
| 9 | Repository tidy-up: drop `rpa model old/` (a second copy, 40 MB), and use a folder name without a space | Smaller clones, fewer path problems on Windows | n/a |

The `/api/chat/v1` conversation service of sections 1 to 14 is a later phase. Until it exists, One Chat keeps its
built-in engine, and the platform falls back to it whenever the service can't answer (section 15).
