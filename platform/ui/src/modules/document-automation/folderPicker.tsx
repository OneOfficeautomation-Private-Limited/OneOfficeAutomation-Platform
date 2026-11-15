import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Field, IconButton, PathRow, TextInput } from "../../components";
import { NO_FOLDER_LIST, inFolder, type DocFlowApi, type FolderAccess, type FolderEntry, type FolderListing, type FolderRoots, type FolderUse } from "./api";
import { Chip, type Tone } from "./ui";

/**
 * DA-1 item 5: a folder on DocFlow's server chosen from the folders an administrator allowed (DocFlow 3.2.0), instead
 * of a typed network path. Laid out as the design plan's DA1-FolderPick artboard (approved 15 Nov 2026). Without a
 * list (or with a DocFlow before 3.2.0) the field stays a typed path, as before. DocFlow checks every folder when the
 * process is saved; what this shows is for people, not the check.
 */

export const ACCESS: Record<FolderAccess, { text: string; tone: Tone }> = {
  ok: { text: "DocFlow can read and move files here", tone: "ok" },
  read: { text: "DocFlow can read here but cannot move files", tone: "warn" },
  missing: { text: "Not found on DocFlow's server", tone: "bad" },
  denied: { text: "No access: DocFlow's account cannot open it", tone: "bad" },
};

/** The list, asked once per screen and kept for half a minute (the builder and its dialogs share it). */
const rootsCache = new WeakMap<DocFlowApi, { at: number; roots: Promise<FolderRoots> }>();
export function folderRootsOf(api: DocFlowApi, fresh = false): Promise<FolderRoots> {
  const c = rootsCache.get(api);
  if (c && !fresh && Date.now() - c.at < 30_000) return c.roots;
  const roots = api.folderRoots().catch(() => NO_FOLDER_LIST);
  rootsCache.set(api, { at: Date.now(), roots });
  return roots;
}

const sepOf = (p: string) => (p.includes("\\") || !p.includes("/") ? "\\" : "/");
const join = (folder: string, name: string) => (folder ? folder.replace(/[\\/]+$/, "") + sepOf(folder) + name : name);
const split = (file: string): [string, string] => {
  const at = Math.max(file.lastIndexOf("\\"), file.lastIndexOf("/"));
  return at < 0 ? ["", file] : [file.slice(0, at), file.slice(at + 1)];
};
const count = (n: number) => (n >= 1000 ? "1,000+" : String(n));
const names = (u: FolderUse[], self?: string) => u.map((x) => (x.id === self ? "This process" : x.name)).join(", ");

/**
 * A folder field. With a list: the chosen folder (read-only) and Browse…, which opens the picker; typing the path is
 * one click away. file: an Excel register, so a folder and a file name (the default given).
 */
export function FolderField({ api, label, value, onChange, hint, placeholder, empty, file, processId, title }: {
  api?: DocFlowApi; label: string; value: string; onChange: (v: string) => void; hint?: string; placeholder: string;
  /** Shown while nothing is chosen, for a folder that may be left empty (a watched folder: upload only). */
  empty?: string; file?: string; processId?: string; title: string;
}) {
  const [roots, setRoots] = useState<FolderRoots | null>(null);
  const [typing, setTyping] = useState(false);
  const [picking, setPicking] = useState(false);
  useEffect(() => {
    let on = true;
    if (api) void folderRootsOf(api).then((r) => { if (on) setRoots(r); });
    return () => { on = false; };
  }, [api]);
  const limited = !!roots?.limited;
  if (!limited || typing) return (
    <div className="df-folder-field">
      <Field label={label} hint={hint}>{(id) => <TextInput id={id} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />}</Field>
      {limited && <div className="df-folder-links"><button type="button" className="df-link" onClick={() => setTyping(false)}>Choose from the folders processes may use</button></div>}
    </div>
  );
  const [dir, base] = file ? split(value) : [value, ""];
  const outside = !!dir.trim() && !roots!.roots.some((r) => inFolder(dir, r.path));
  return (
    <div className="df-folder-field">
      <Field label={file ? label + ": folder" : label} hint={hint}>
        {(id) => <PathRow>
          <TextInput id={id} value={dir} readOnly placeholder={empty ?? "No folder chosen"} />
          <Button kind="secondary" icon="folder" className="ui-browse" aria-label={"Browse for " + (file ? "the Excel file's folder" : label.toLowerCase())} onClick={() => setPicking(true)}>Browse…</Button>
        </PathRow>}
      </Field>
      {file && <Field label="File name">{(id) => <TextInput id={id} value={base} placeholder={file} onChange={(e) => onChange(join(dir, e.target.value))} />}</Field>}
      <div className="df-folder-links">
        {outside && <span className="df-text-warn">Not one of the folders processes may use: choose another, or saving is refused.</span>}
        {!file && empty && value && <button type="button" className="df-link" onClick={() => onChange("")}>Clear</button>}
        <button type="button" className="df-link" onClick={() => setTyping(true)}>Type the path</button>
      </div>
      {picking && api && <FolderPicker api={api} title={title} start={dir} processId={processId} onClose={() => setPicking(false)}
        onPick={(p) => { onChange(file ? join(p, base || file) : p); setPicking(false); }} />}
    </div>
  );
}

/** The picker: the allowed folders on the left, the folders inside the open one on the right. */
export function FolderPicker({ api, title, start, processId, onClose, onPick }: {
  api: DocFlowApi; title: string; start?: string; processId?: string; onClose: () => void; onPick: (path: string) => void;
}) {
  const [roots, setRoots] = useState<FolderRoots | null>(null);
  const [here, setHere] = useState<FolderListing | null>(null);
  const [selected, setSelected] = useState<FolderEntry | null>(null);
  const [chosen, setChosen] = useState<FolderListing | null>(null);   // the selected folder, asked for its access
  const [problem, setProblem] = useState("");
  const [busy, setBusy] = useState(false);
  const [naming, setNaming] = useState<string | null>(null);
  const said = (e: unknown) => setProblem(e instanceof Error ? e.message : String(e));
  const latest = useRef("");   // the folder last selected: an older answer must not show its access
  const ask = (path: string) => { latest.current = path; void api.folderList(path).then((l) => { if (latest.current === path) setChosen(l); }, () => undefined); };

  const open = async (path: string, pick?: string) => {
    setBusy(true); setProblem("");
    try {
      const l = await api.folderList(path);
      setHere(l);
      const p = pick ? l.folders.find((f) => f.path === pick) ?? null : null;
      setSelected(p); setChosen(null); latest.current = "";
      if (p) ask(p.path);
    } catch (e) { said(e); } finally { setBusy(false); }
  };
  useEffect(() => {
    void folderRootsOf(api, true).then(async (r) => {
      setRoots(r);
      const first = r.roots.find((x) => start && inFolder(start, x.path)) ?? r.roots[0];
      if (!first) return;
      setBusy(true);
      try { setHere(await api.folderList(start && inFolder(start, first.path) ? start : first.path)); }
      catch { try { setHere(await api.folderList(first.path)); } catch (e) { said(e); } }
      finally { setBusy(false); }
    });
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps

  const select = (f: FolderEntry) => {
    setSelected(f); setChosen(null);
    ask(f.path);
  };
  const make = async () => {
    if (!here || !naming?.trim()) return;
    try {
      const made = await api.folderCreate(here.path, naming.trim());
      setNaming(null);
      await open(here.path, made.path);
    } catch (e) { said(e); }
  };

  const target: { path: string; access?: FolderAccess; waiting: number; watchedBy: FolderUse[] } | null = selected
    ? { path: selected.path, access: chosen?.access, waiting: selected.waiting, watchedBy: selected.watchedBy }
    : here ? { path: here.path, access: here.access, waiting: here.waiting, watchedBy: here.watchedBy } : null;
  const others = target?.watchedBy.filter((u) => u.id !== processId) ?? [];
  const crumbs = here ? crumbsOf(here) : [];

  return (
    <Dialog open onClose={onClose} title={title} width={900}
      footer={<>
        {naming === null
          ? <Button icon="plus" disabled={!here || busy} onClick={() => setNaming("")}>New folder</Button>
          : <span className="df-folder-new">
              <TextInput aria-label="New folder name" value={naming} placeholder="Folder name" onChange={(e) => setNaming(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") void make(); if (e.key === "Escape") { e.stopPropagation(); setNaming(null); } }} />
              <Button kind="secondary" disabled={!naming.trim()} onClick={() => void make()}>Make folder</Button>
              <Button kind="plain" onClick={() => setNaming(null)}>Cancel</Button>
            </span>}
        <span className="df-folder-chosen" aria-live="polite">
          {target && <><span><span className="df-small">Chosen:</span> <b>{target.path}</b></span>
            <span>{target.access && <Chip tone={ACCESS[target.access].tone}>{ACCESS[target.access].text}</Chip>}
              {target.waiting > 0 && <span className="df-small"> · {count(target.waiting)} file{target.waiting === 1 ? "" : "s"} waiting, read once the process is live</span>}</span>
            {others.length > 0 && <span className="df-text-warn">Already watched by {names(others)}: each file goes to whichever process reads it first.</span>}</>}
        </span>
        <Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!target || busy} onClick={() => target && onPick(target.path)}>Use this folder</Button>
      </>}>
      <p className="df-small" style={{ margin: 0 }}>Only folders your administrator allowed are shown. New files put in the chosen folder are read one by one; each read file moves to its <b>processed</b> folder.</p>
      {problem && <p className="df-text-warn" role="alert" style={{ margin: 0 }}>{problem}</p>}
      <div className="df-folder-pick">
        <div className="df-folder-roots" role="group" aria-label="Allowed folders">
          <div className="df-folder-label">Allowed folders</div>
          {(roots?.roots ?? []).map((r) => (
            <button key={r.path} type="button" className={`df-folder-root${here?.root.path === r.path ? " is-on" : ""}`} aria-pressed={here?.root.path === r.path}
              onClick={() => void open(r.path)}>
              <b>{r.name}</b><span className="df-small">{r.path}</span>
              {r.access !== "ok" && <span className={r.access === "read" ? "df-text-warn" : "df-text-bad"}>{ACCESS[r.access].text}</span>}
            </button>))}
        </div>
        <div className="df-folder-main">
          <div className="df-folder-crumb">
            <IconButton icon="up" label="Up one folder" disabled={!here?.parent || busy} onClick={() => here?.parent && void open(here.parent)} />
            <nav aria-label="Where you are">{crumbs.map((c, i) => i === crumbs.length - 1
              ? <b key={c.path} aria-current="location">{c.name}</b>
              : <span key={c.path}><button type="button" className="df-link" onClick={() => void open(c.path)}>{c.name}</button><span className="df-small" aria-hidden="true"> › </span></span>)}</nav>
          </div>
          <div className="df-folder-list">
            <table className="df-table">
              <thead><tr><th>Folder</th><th>Files waiting</th><th>Watched by</th><th><span className="sr-only">Open</span></th></tr></thead>
              <tbody>
                {here?.folders.map((f) => (
                  <tr key={f.path} className={selected?.path === f.path ? "is-selected" : undefined} aria-selected={selected?.path === f.path}>
                    <td><button type="button" className="df-folder-name" onClick={() => select(f)} onDoubleClick={() => void open(f.path)}>{f.name}</button></td>
                    <td>{count(f.waiting)}</td>
                    <td>{f.watchedBy.length ? <Chip tone={f.watchedBy.some((u) => u.id !== processId) ? "warn" : "info"} dot={false}>{names(f.watchedBy, processId)}</Chip> : <span className="df-muted">None</span>}</td>
                    <td><IconButton icon="chevronRight" label={"Open " + f.name} onClick={() => void open(f.path)} /></td>
                  </tr>))}
              </tbody>
            </table>
            {here && !here.folders.length && <p className="df-empty" style={{ margin: 8 }}>No folders inside. Use this folder, or make a new one.</p>}
            {here?.truncated && <p className="df-small" style={{ margin: 8 }}>Only the first 300 folders are shown.</p>}
          </div>
          <p className="df-small" style={{ margin: 0 }}>Choose a folder, then Use this folder; double-click, or ›, opens it. Files waiting: PDF, image and XML files that would be read straight away.</p>
        </div>
      </div>
    </Dialog>
  );
}

/** The way from the allowed folder down to the open one, each part openable. */
function crumbsOf(l: FolderListing): { name: string; path: string }[] {
  const out = [{ name: l.root.name, path: l.root.path }];
  const rest = l.path.slice(l.root.path.replace(/[\\/]+$/, "").length).split(/[\\/]/).filter(Boolean);
  let at = l.root.path.replace(/[\\/]+$/, "");
  for (const part of rest) { at = at + sepOf(l.path) + part; out.push({ name: part, path: at }); }
  return out;
}
