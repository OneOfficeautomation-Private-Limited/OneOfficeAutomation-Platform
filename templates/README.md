# Agent templates

ONEOFFICEAUTOMATION's template catalogue (K7). Each folder is one template: `template.json` names it, describes it,
gives its category and lists its agents (the path in the agent repository, and the agent file relative to
`template.json`). The agent files are the examples in `../examples`, so a template stays the same as its example.

The publish script signs them into `templates.json` on the releases page (`ooa release templates --dir templates
--key private.key --out templates.json`). Command Centers list them in Automation > Templates and install them into a
folder; see docs/user-guide/00-README.md, Templates.
