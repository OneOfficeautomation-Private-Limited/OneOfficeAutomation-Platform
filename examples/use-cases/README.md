# Use cases

Twenty-six ready-made agents: sixteen web automations (W1-W5, the larger processes W6-W10 on demo applications, and P1-P6 on real public websites), five Windows desktop automations (D1-D5) and five others
(O1-O5: Excel, email, documents, REST and database, AI). Each one is explained in
`docs/research/use-cases.md`, together with how the same task is done in Automation Anywhere.

- `site/` holds the demo web pages the web cases open (no server needed).
- `apps/order-entry.ps1` is the demo Windows application the desktop cases drive.
- `data/` holds the input files; results are written to `output/`.
- `folder` (an input of every agent) is this folder relative to the workspace; it is `Use cases` when the folder sits
  at the top of the workspace.
- `tools/generate.py` writes these files (`tools/generate_big_web.py` writes W6-W10, `tools/generate_public_web.py` writes P1-P6); `tools/make-office-files.flow.json` makes the Excel and Word data with
  `ooa run`.
