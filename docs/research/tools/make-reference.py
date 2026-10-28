"""Writes the toolkit reference (docs/reference/toolkits/) from the toolkit manifests.

Usage: python make-reference.py <manifest.json> <out-folder>
The manifest is what `ooa manifest` prints: every toolkit with its actions, conditions and iterators, their fields
(inputs), panel layout and outputs. One page per toolkit plus an index. Run it again after toolkits change.
"""
import json
import os
import sys

TYPE_NAMES = {"String": "Text", "Number": "Number", "Boolean": "Yes/No", "List": "List", "Dictionary": "Dictionary",
              "Table": "Table", "Record": "Record", "File": "File path", "Credential": "Credential (vault)",
              "Datetime": "Date and time", "Any": "Any"}


def cell(s):
    return str(s if s is not None else "").replace("|", "\\|").replace("\n", " ").strip()


def tname(p):
    return TYPE_NAMES.get(p.get("x-ooa-type"), p.get("x-ooa-type") or p.get("type") or "Any")


def default(p):
    if "default" not in p:
        return ""
    d = p["default"]
    if isinstance(d, bool):
        return "Yes" if d else "No"
    if isinstance(d, (dict, list)):
        return "`" + json.dumps(d) + "`"
    return "`" + str(d) + "`" if d != "" else "(empty)"


def field_rows(names, props, required, where):
    rows = []
    for n in names:
        p = props.get(n)
        if p is None:
            continue
        notes = []
        if p.get("enum"):
            notes.append("One of: " + ", ".join("`" + str(e) + "`" for e in p["enum"]))
        if p.get("examples"):
            notes.append("Example: `" + str(p["examples"][0]) + "`")
        if "minimum" in p or "maximum" in p:
            notes.append("Range: " + str(p.get("minimum", "")) + " to " + str(p.get("maximum", "")))
        if p.get("x-ooa-visible-when"):
            w = p["x-ooa-visible-when"]
            notes.append("Shown when: `" + (w if isinstance(w, str) else json.dumps(w)) + "`")
        rows.append("| " + " | ".join([cell(p.get("x-ooa-label") or n), "`" + n + "`", tname(p),
                                        "Yes" if n in required else "No", cell(default(p)), where,
                                        cell(p.get("description", "")) + (" " + "; ".join(notes) if notes else "")]) + " |")
    return rows


def panel_fields(panel, props):
    """(field name, where) in the order the settings panel shows them, with the group each belongs to."""
    out = []

    def walk(items, more):
        for it in items:
            base = "More options" if more else "Main"
            if isinstance(it, str):
                out.append((it, base))
            elif isinstance(it, dict) and "choice" in it:
                for o in it.get("options", []):
                    for f in o.get("fields", []):
                        out.append((f, base + "; choice " + it["choice"] + " = " + o.get("label", "")))
            elif isinstance(it, dict) and "tick" in it:
                for f in it.get("fields", []):
                    out.append((f, base + "; tick " + it["tick"]))
    if panel:
        walk(panel.get("main", []), False)
        walk(panel.get("more", []), True)
    seen = {f for f, _ in out}
    for n in props:
        if n not in seen:
            out.append((n, "Main"))
    return out


def action_md(pkg_id, a, kind):
    props = (a.get("inputs") or {}).get("properties") or {}
    required = set((a.get("inputs") or {}).get("required") or [])
    lines = ["### " + a.get("name", a["id"]), "", "`" + pkg_id + "/" + a["id"] + "` (" + kind + ")", "", a.get("description", ""), ""]
    fields = panel_fields(a.get("x-ooa-panel"), props)
    if fields:
        lines += ["**Settings**", "", "| Field | Name | Type | Required | Default | Where | Description |",
                  "| --- | --- | --- | --- | --- | --- | --- |"]
        for n, where in fields:
            lines += field_rows([n], props, required, where)
        lines.append("")
    else:
        lines += ["No settings.", ""]
    outs = a.get("outputs") or {}
    if outs:
        lines += ["**Results**", "", "| Name | Type | Description |", "| --- | --- | --- |"]
        for n, o in outs.items():
            lines.append("| `" + n + "` | " + TYPE_NAMES.get(o.get("x-ooa-type"), o.get("x-ooa-type") or "Any") + " | " + cell(o.get("description", "")) + " |")
        lines.append("")
    facts = []
    if a.get("timeoutSeconds"):
        facts.append("time limit " + str(int(a["timeoutSeconds"])) + " s")
    if a.get("idempotent"):
        facts.append("safe to repeat")
    if a.get("capabilities"):
        facts.append("needs: " + ", ".join(a["capabilities"]))
    if facts:
        lines += ["*" + "; ".join(facts).capitalize() + ".*", ""]
    return lines


def main(manifest, out):
    pkgs = json.load(open(manifest, encoding="utf-8-sig"))
    os.makedirs(out, exist_ok=True)
    index = ["# Toolkit reference", "",
             "Every toolkit of this release with every action, condition and loop type: each field as the settings panel shows it",
             "(label, name, type, whether it is required, its default, whether it is in the main area, under a choice or a tick,",
             "or under More options), and each result. Generated from the toolkit manifests (`ooa manifest`) by",
             "`docs/research/tools/make-reference.py`; do not edit these pages by hand.", "",
             "Field names are what a flow file and `ooa run` use; labels are what Studio shows. Text fields accept `${...}`",
             "values and `$variable` references; Credential fields take a credential from the vault.", "",
             "| Toolkit | Id | Actions | Conditions | Loop types | What it does |", "| --- | --- | --- | --- | --- | --- |"]
    total = [0, 0, 0]
    for p in sorted(pkgs, key=lambda x: (x["id"] != "core", x["name"].lower())):
        acts, conds, its = p.get("actions") or [], p.get("conditions") or [], p.get("iterators") or []
        total[0] += len(acts); total[1] += len(conds); total[2] += len(its)
        fname = p["id"].replace(".", "-") + ".md"
        index.append("| [" + cell(p["name"]) + "](" + fname + ") | `" + p["id"] + "` | " + str(len(acts)) + " | " + str(len(conds)) + " | "
                     + str(len(its)) + " | " + cell(p.get("description", "")) + " |")
        lines = ["# " + p["name"], "", "Toolkit `" + p["id"] + "`, version " + str(p.get("version", "")) + ". " + p.get("description", ""), "",
                 "[All toolkits](README.md)", ""]
        cats = {}
        for a in acts:
            cats.setdefault(a.get("category") or "Actions", []).append(a)
        lines += ["## Contents", ""]
        for c, lst in cats.items():
            lines.append("- **" + c + "**: " + ", ".join(x.get("name", x["id"]) for x in lst))
        if conds:
            lines.append("- **Conditions**: " + ", ".join(x.get("name", x["id"]) for x in conds))
        if its:
            lines.append("- **Loop types**: " + ", ".join(x.get("name", x["id"]) for x in its))
        lines.append("")
        for c, lst in cats.items():
            lines += ["## " + c, ""]
            for a in lst:
                lines += action_md(p["id"], a, "action")
        if conds:
            lines += ["## Conditions", "", "Used in If, Loop while and Wait until steps.", ""]
            for a in conds:
                lines += action_md(p["id"], a, "condition")
        if its:
            lines += ["## Loop types", "", "Used as the kind of loop in a Loop step.", ""]
            for a in its:
                lines += action_md(p["id"], a, "loop type")
        with open(os.path.join(out, fname), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines).rstrip() + "\n")
    index += ["", "In all: " + str(len(pkgs)) + " toolkits, " + str(total[0]) + " actions, " + str(total[1]) + " conditions and "
              + str(total[2]) + " loop types."]
    with open(os.path.join(out, "README.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(index) + "\n")
    print(len(pkgs), "toolkits,", total[0], "actions,", total[1], "conditions,", total[2], "loop types")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
