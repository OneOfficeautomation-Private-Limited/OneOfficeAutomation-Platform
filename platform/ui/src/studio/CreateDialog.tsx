import { type ReactElement, useMemo, useState } from "react";
import { type Blueprint, type NewItemKind, errorText } from "../api/client";
import { Button, Dialog, IconTile } from "../components";
import type { ItemType } from "./itemTypes";

/**
 * Step 3 of creating, in AA's order: the type is picked from Create; here what to start from, the name and the folder.
 * Create v2 (improvement list 13 Nov 2026, item 4, chosen on the design canvas): two columns. On the left the templates
 * as a table (Name, Category, Steps), sorted by a click on a heading, with a search and a Category filter that gives
 * each category's count; on the right Start from (Blank or Template, a two-way switch), the name and the folder tree.
 * With Blank chosen the left says what Blank gives. Other types show only the name and the folder.
 * Cancel, the close button and Esc close it; Enter creates.
 */
type SortKey = "name" | "category" | "steps";
export function CreateDialog({ type, folderPath, folders, blueprints = [], onCreate, onClose, onChangeFolder }:
  { type: ItemType | null; folderPath: string; folders: string[]; blueprints?: Blueprint[]; onCreate: (kind: NewItemKind, name: string, from: Blueprint | null) => Promise<void>;
    onClose: () => void; onChangeFolder: (path: string) => void }) {
  const [name, setName] = useState("");
  const [mode, setMode] = useState<"blank" | "template">("blank");
  const [start, setStart] = useState("");
  const [q, setQ] = useState("");
  const [kit, setKit] = useState("");
  const [sort, setSort] = useState<{ by: SortKey; up: boolean }>({ by: "name", up: true });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const offers = type?.kind === "TaskBot" ? blueprints : [];
  const kits = useMemo(() => {
    const n = new Map<string, number>();
    offers.forEach((b) => n.set(toolkitOf(b), (n.get(toolkitOf(b)) ?? 0) + 1));
    return [...n.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [offers]);
  if (!type) return null;
  const from = mode === "template" ? offers.find((b) => b.id === start) ?? null : null;
  const t = q.trim().toLowerCase();
  const key = (b: Blueprint): string | number => sort.by === "name" ? b.name.toLowerCase() : sort.by === "category" ? toolkitOf(b) + " " + b.name.toLowerCase() : b.steps.length;
  const shown = offers.filter((b) => (!kit || toolkitOf(b) === kit) && (!t || (b.name + " " + b.description).toLowerCase().includes(t)))
    .sort((a, b) => { const x = key(a), y = key(b); return (x < y ? -1 : x > y ? 1 : 0) * (sort.up ? 1 : -1); });
  const pick = (b: Blueprint) => { setStart(b.id); if (!name.trim() || offers.some((o) => o.name === name)) setName(b.name); };
  const head = (by: SortKey, label: string) => {
    const on = sort.by === by;
    return <button type="button" className={"st-create-th is-" + by} aria-sort={on ? (sort.up ? "ascending" : "descending") : "none"}
      onClick={() => setSort(on ? { by, up: !sort.up } : { by, up: true })}>{label}{on && <span aria-hidden="true">{sort.up ? " \u25B2" : " \u25BC"}</span>}</button>;
  };

  const submit = async () => {
    if (mode === "template" && !from) { setError("Choose a template, or start from Blank."); return; }
    if (!name.trim()) { setError("Give it a name."); return; }
    setBusy(true); setError(null);
    try { await onCreate(type.kind, name.trim(), from); }
    catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  };

  const place = (
    <div className="st-create-place">
      {offers.length > 0 && <>
        <span className="st-create-label">Start from</span>
        <div className="st-seg st-create-switch" role="radiogroup" aria-label="Start from">
          <button type="button" role="radio" className="st-seg-btn" aria-checked={mode === "blank"} onClick={() => setMode("blank")}>Blank</button>
          <button type="button" role="radio" className="st-seg-btn" aria-checked={mode === "template"} onClick={() => setMode("template")}>Template</button>
        </div>
      </>}
      <label className="st-create-label" htmlFor="st-create-name">Name</label>
      <input id="st-create-name" className="ui-input" data-autofocus value={name} placeholder="For example: Invoice intake Germany"
        onChange={(e) => setName(e.target.value)} aria-invalid={!!error && !name.trim()} />
      <span className="st-create-label" id="st-create-folder">Save in</span>
      <FolderTree folders={folders} value={folderPath} onChange={onChangeFolder} labelledBy="st-create-folder" />
      <span className="st-create-path">{["Agents", ...folderPath.split("/").filter(Boolean)].join(" / ")}{name.trim() && " / " + name.trim()}</span>
    </div>
  );

  return (
    <Dialog open onClose={onClose} title={"New " + type.name.toLowerCase()} width={offers.length ? 900 : 480}
      icon={<IconTile name={type.icon} color={type.color} size={24} />}
      footer={<>
        <span className="st-create-esc">Esc closes</span>
        <Button kind="secondary" onClick={onClose}>Cancel</Button>
        <Button kind="primary" onClick={submit} disabled={busy}>{busy ? "Creating\u2026" : "Create and edit"}</Button>
      </>}>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }} className={"st-create" + (offers.length ? " has-start" : "")}>
        {offers.length > 0 && <div className="st-create-list">
          {mode === "blank" ? <p className="st-create-blank">An empty agent: you add its steps in the editor.<br />Choose <b>Template</b> on the right to start from a ready-made agent instead.</p> : <>
            <div className="st-create-tools">
              <input type="search" className="ui-input" aria-label="Search templates" placeholder={"Search " + offers.length + " templates"} value={q}
                onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => { if (e.key === "Escape" && q) { e.stopPropagation(); setQ(""); } }} />
              {kits.length > 1 && <select className="ui-input st-create-cat" aria-label="Category" value={kit} onChange={(e) => setKit(e.target.value)}>
                <option value="">Category: All ({offers.length})</option>
                {kits.map(([k, n]) => <option key={k} value={k}>{k} ({n})</option>)}
              </select>}
            </div>
            <div className="st-create-table">
              <div className="st-create-thead">{head("name", "Name")}{head("category", "Category")}{head("steps", "Steps")}</div>
              {shown.length ? <ul className="st-create-templates" role="listbox" aria-label="Templates">
                {shown.map((b) => <li key={b.id} role="option" aria-selected={b.id === start} tabIndex={0} onClick={() => pick(b)}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(b); } }}>
                  <b>{b.name}</b><span className="is-category">{toolkitOf(b)}</span><span className="is-steps">{b.steps.length}</span></li>)}
              </ul> : <p className="st-create-blank">Nothing matches “{q.trim()}”.</p>}
            </div>
            {from && <p className="st-create-about">{from.description}</p>}
          </>}
        </div>}
        {place}
        {error && <p role="alert" className="st-error st-create-error">{error}</p>}
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}

/** The toolkit a template mostly uses, by name (ooa.excel/open is Excel), to group the templates. */
function toolkitOf(b: Blueprint): string {
  const n = new Map<string, number>();
  for (const s of b.steps) { const k = /^ooa\.([a-z0-9-]+)\//i.exec(s.action ?? "")?.[1]; if (k) n.set(k, (n.get(k) ?? 0) + 1); }
  const top = [...n.entries()].sort((a, c) => c[1] - a[1])[0]?.[0];
  if (!top) return "General";
  return top.length <= 3 ? top.toUpperCase() : top.charAt(0).toUpperCase() + top.slice(1).replace(/-/g, " ");
}

/** A folder icon, open for the chosen folder: a darker outline keeps it clear on white. */
function FolderIcon({ open }: { open: boolean }) {
  return <svg width="16" height="14" viewBox="0 0 16 14" aria-hidden="true" className="st-folder-icon">
    <path d="M1 2.5h5l1.6 1.8H15V13H1z" fill="#f2b84b" stroke="#a16207" strokeWidth="1" strokeLinejoin="round" />
    {open && <path d="M1 13l2-6.5h13L14 13z" fill="#fad48a" stroke="#a16207" strokeWidth="1" strokeLinejoin="round" />}
  </svg>;
}

type Node = { path: string; name: string; children: Node[] };

/** Every folder of the workspace as a tree; a click chooses the folder, the arrow opens and closes it. */
function FolderTree({ folders, value, onChange, labelledBy }: { folders: string[]; value: string; onChange: (p: string) => void; labelledBy: string }) {
  const root = useMemo(() => {
    const top: Node = { path: "", name: "Agents", children: [] };
    const byPath = new Map<string, Node>([["", top]]);
    for (const f of [...new Set(folders.filter(Boolean))].sort()) {
      const parts = f.split("/");
      parts.forEach((part, i) => {
        const p = parts.slice(0, i + 1).join("/");
        if (byPath.has(p)) return;
        const node: Node = { path: p, name: part, children: [] };
        byPath.set(p, node);
        byPath.get(parts.slice(0, i).join("/"))!.children.push(node);
      });
    }
    return top;
  }, [folders]);
  const ancestors = (p: string) => p.split("/").map((_, i, a) => a.slice(0, i).join("/"));
  const [open, setOpen] = useState<Set<string>>(() => new Set(["", ...ancestors(value)]));
  const toggle = (p: string) => setOpen((o) => { const n = new Set(o); if (n.has(p)) n.delete(p); else n.add(p); return n; });
  const row = (n: Node, depth: number): ReactElement => {
    const isOpen = open.has(n.path), chosen = n.path === value;
    return <li key={n.path} role="treeitem" aria-selected={chosen} aria-expanded={n.children.length ? isOpen : undefined}>
      <div className={"st-tree-row" + (chosen ? " is-chosen" : "")} style={{ paddingLeft: 6 + depth * 16 }}>
        {n.children.length ? <button type="button" className="st-tree-caret" aria-label={(isOpen ? "Close " : "Open ") + n.name} onClick={() => toggle(n.path)}>{isOpen ? "▾" : "▸"}</button>
          : <span className="st-tree-caret" />}
        <button type="button" className="st-tree-name" onClick={() => onChange(n.path)}><FolderIcon open={chosen} />{n.name}</button>
      </div>
      {n.children.length > 0 && isOpen && <ul role="group">{n.children.map((c) => row(c, depth + 1))}</ul>}
    </li>;
  };
  return <ul className="st-create-tree" role="tree" aria-labelledby={labelledBy}>{row(root, 0)}</ul>;
}
