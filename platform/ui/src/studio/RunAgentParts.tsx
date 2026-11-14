import { useMemo, useState } from "react";
import type { AgentSummary, Flow, FlowVariable } from "../api/client";
import { Button, Dialog, TextInput, Tree, type TreeNode } from "../components";
import { TypeBadge } from "./VariablesPanel";
import { compatible, holdsType, isInput, isOutput, varRef } from "./variables";

/**
 * The parts of the Run Agent step (14 Nov 2026, items D and E, as AA's Task Bot Run): Find an agent (Browse a folder,
 * or Search every folder) and Quick map (each input and output of the agent it runs to this agent's variable of the
 * same name and a fitting type, with what will happen shown first).
 */

/** The top folder of the workspace, as on the Agents page. */
export const AGENTS_ROOT = "Agents";

/** An agent's folder in the workspace ("" at the top). */
export const folderOf = (path: string) => (path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "");

/** Where an agent is, as people read it: Agents › Finance › Invoice (never the file name). */
export const agentPlace = (a: AgentSummary) => [AGENTS_ROOT, ...folderOf(a.path).split("/").filter(Boolean), a.name || a.id].join(" › ");

function folderTree(agents: AgentSummary[]): TreeNode {
  const root: TreeNode = { id: "", label: AGENTS_ROOT, children: [] };
  const byPath = new Map<string, TreeNode>([["", root]]);
  for (const f of [...new Set(agents.map((a) => folderOf(a.path)).filter(Boolean))].sort()) {
    const parts = f.split("/");
    parts.forEach((part, i) => {
      const p = parts.slice(0, i + 1).join("/");
      if (byPath.has(p)) return;
      const node: TreeNode = { id: p, label: part, children: [] };
      byPath.get(parts.slice(0, i).join("/"))!.children!.push(node);
      byPath.set(p, node);
    });
  }
  return root;
}

/**
 * Find an agent: Browse shows the folder tree (Agents at the top) and the agents of the chosen folder as a table;
 * Search finds agents in every folder by name or folder. A click chooses a row, Choose (or a double-click) takes it.
 */
export function AgentFinder({ agents, current, onPick, onClose }:
  { agents: AgentSummary[]; current?: string; onPick: (id: string) => void; onClose: () => void }) {
  const start = agents.find((a) => a.id === current);
  const [tab, setTab] = useState<"browse" | "search">("browse");
  const [folder, setFolder] = useState(start ? folderOf(start.path) : "");
  const [open, setOpen] = useState<Set<string>>(() => {
    const s = new Set<string>([""]);
    const parts = (start ? folderOf(start.path) : "").split("/").filter(Boolean);
    parts.forEach((_, i) => s.add(parts.slice(0, i + 1).join("/")));
    return s;
  });
  const [q, setQ] = useState("");
  const [chosen, setChosen] = useState<string | undefined>(current);
  const tree = useMemo(() => folderTree(agents), [agents]);
  const f = q.trim().toLowerCase();
  const rows = tab === "browse" ? agents.filter((a) => folderOf(a.path) === folder && (!f || (a.name || a.id).toLowerCase().includes(f)))
    : agents.filter((a) => !f || (a.name || a.id).toLowerCase().includes(f) || folderOf(a.path).toLowerCase().includes(f));
  const sorted = [...rows].sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
  const count = (a: AgentSummary, dir: (v: FlowVariable) => boolean) => (a.variables ?? []).filter(dir).length;
  return (
    <Dialog open title="Find an agent" width={860} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!chosen} onClick={() => chosen && onPick(chosen)}>Choose</Button></>}>
      <div className="st-finder">
        <div className="st-finder-top">
          <div className="st-seg st-finder-tabs" role="radiogroup" aria-label="Find by">
            <button type="button" role="radio" className="st-seg-btn" aria-checked={tab === "browse"} onClick={() => setTab("browse")}>Browse</button>
            <button type="button" role="radio" className="st-seg-btn" aria-checked={tab === "search"} onClick={() => setTab("search")}>Search</button>
          </div>
          <TextInput type="search" aria-label="Find an agent by name" placeholder={tab === "browse" ? "Name in this folder" : "Name or folder, in every folder"}
            value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className={"st-finder-body" + (tab === "search" ? " is-search" : "")}>
          {tab === "browse" && <div className="st-finder-tree">
            <Tree label="Folders" nodes={[tree]} selected={folder} expanded={open}
              onSelect={(id) => { setFolder(id); setOpen((o) => new Set(o).add(id)); }}
              onToggle={(id) => setOpen((o) => { const n = new Set(o); if (n.has(id)) n.delete(id); else n.add(id); return n; })} />
          </div>}
          <div className="st-finder-list">
            <table className="st-table" aria-label="Agents">
              <thead><tr><th scope="col">Name</th>{tab === "search" && <th scope="col">Folder</th>}<th scope="col">Inputs</th><th scope="col">Outputs</th></tr></thead>
              <tbody>
                {sorted.map((a) => <tr key={a.id} className={a.id === chosen ? "is-selected" : undefined} aria-selected={a.id === chosen}
                  onClick={() => setChosen(a.id)} onDoubleClick={() => onPick(a.id)}>
                  <td><button type="button" className="st-link" onClick={() => setChosen(a.id)}>{a.name || a.id}</button></td>
                  {tab === "search" && <td className="st-muted">{[AGENTS_ROOT, ...folderOf(a.path).split("/").filter(Boolean)].join(" › ")}</td>}
                  <td>{count(a, isInput)}</td><td>{count(a, isOutput)}</td>
                </tr>)}
              </tbody>
            </table>
            {!sorted.length && <p className="st-muted st-finder-empty">{f ? "No agent matches." : "No agents in this folder."}</p>}
          </div>
        </div>
      </div>
    </Dialog>
  );
}

/** What Quick map will do with one input or output of the agent it runs. */
export type MapState = "ready" | "new" | "set" | "type";
export type MapRow = { name: string; type: string; dir: "input" | "output"; state: MapState; to?: string };

const STATE_LABEL: Record<MapState, string> = {
  ready: "Ready to map", new: "New variable needed", set: "Already set", type: "Same name, another type (left as it is)",
};

/** Each input and output with what Quick map does with it, given this agent's variables and what the step already has. */
export function planQuickMap(child: AgentSummary, flow: Flow, inputs: Record<string, unknown>, outMap: Record<string, string>): MapRow[] {
  const mine = flow.variables ?? [];
  const rows: MapRow[] = [];
  for (const v of (child.variables ?? []).filter(isInput)) {
    const same = mine.find((x) => x.name === v.name);
    const type = v.type || "Any";
    const state: MapState = inputs[v.name] !== undefined ? "set" : !same ? "new" : compatible(type, same.type) ? "ready" : "type";
    rows.push({ name: v.name, type, dir: "input", state, to: inputs[v.name] !== undefined ? String(inputs[v.name]) : undefined });
  }
  for (const v of (child.variables ?? []).filter(isOutput)) {
    const same = mine.find((x) => x.name === v.name);
    const type = v.type || "Any";
    const state: MapState = outMap[v.name] ? "set" : !same ? "new" : holdsType(same.type, type) ? "ready" : "type";
    rows.push({ name: v.name, type, dir: "output", state, to: outMap[v.name] });
  }
  return rows;
}

/** The agent with the step mapped: same-name variables, missing ones made when asked, set ones only when overwriting. */
export function applyQuickMap(flow: Flow, stepId: string, child: AgentSummary, inputs: Record<string, unknown>, outMap: Record<string, string>,
  opts: { create: boolean; overwrite: boolean }, setArg: (f: Flow, id: string, name: string, v: unknown) => Flow): { flow: Flow; mapped: number; made: number } {
  const mine = [...(flow.variables ?? [])];
  const ins = { ...inputs }, outs = { ...outMap };
  let mapped = 0, made = 0;
  for (const r of planQuickMap(child, flow, inputs, outMap)) {
    const fits = r.state === "ready" || (r.state === "new" && opts.create) || (r.state === "set" && opts.overwrite && mine.some((x) => x.name === r.name));
    if (!fits) continue;
    if (r.state === "new") {
      const v = (child.variables ?? []).find((x) => x.name === r.name);
      mine.push({ name: r.name, type: r.type, ...(v?.description ? { description: v.description } : {}) });
      made++;
    }
    if (r.dir === "input") ins[r.name] = varRef(r.name); else outs[r.name] = r.name;
    mapped++;
  }
  let g: Flow = { ...flow, variables: mine };
  g = setArg(g, stepId, "inputs", Object.keys(ins).length ? ins : undefined);
  g = setArg(g, stepId, "output_map", Object.keys(outs).length ? outs : undefined);
  return { flow: g, mapped, made };
}

/** Quick map: the child's inputs and outputs grouped by what will happen, two choices, then Apply. */
export function QuickMapDialog({ rows, onApply, onClose }:
  { rows: MapRow[]; onApply: (opts: { create: boolean; overwrite: boolean }) => void; onClose: () => void }) {
  const [create, setCreate] = useState(true);
  const [overwrite, setOverwrite] = useState(false);
  const order: MapState[] = ["ready", "new", "set", "type"];
  return (
    <Dialog open title="Quick map" width={640} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" onClick={() => onApply({ create, overwrite })}>Apply</Button></>}>
      <div className="st-qmap">
        <div className="st-qmap-list" role="list" aria-label="Inputs and outputs">
          {order.filter((s) => rows.some((r) => r.state === s)).map((s) => <div key={s} role="group" aria-label={STATE_LABEL[s]}>
            <div className="st-vp-head">{STATE_LABEL[s]}</div>
            {rows.filter((r) => r.state === s).map((r) => <div key={r.dir + r.name} role="listitem" className="st-qmap-row">
              <TypeBadge type={r.type} /><span className="ui-mono">{r.name}</span>
              <span className="st-muted">{r.dir === "input" ? "input" : "output"}{r.to ? (r.dir === "input" ? " ← " : " → ") + r.to : ""}</span>
            </div>)}
          </div>)}
          {!rows.length && <p className="st-muted">The agent has no inputs or outputs.</p>}
        </div>
        <div className="st-qmap-opts">
          <p>Each input and output goes to this agent's variable with the same name and a type that fits.</p>
          <label className="st-check-row"><input type="checkbox" checked={create} onChange={(e) => setCreate(e.target.checked)} />
            Make the missing variables, with the same name and type</label>
          <label className="st-check-row"><input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} />
            Also replace values already set</label>
        </div>
      </div>
    </Dialog>
  );
}
