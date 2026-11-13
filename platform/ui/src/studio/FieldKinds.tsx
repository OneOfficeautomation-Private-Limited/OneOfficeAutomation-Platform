import { errorText } from "../api/client";
import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Flow, FsEntry, FsListing, OpenWindow, VaultCredentialRef } from "../api/client";
import { Button, Dialog, Icon, IconButton, Select, TextInput } from "../components";
import { showValue } from "./flowEdit";
import { RepoFileBrowser, withRepoFile } from "./RepoFiles";
import { CommitInput } from "./ValueSources";
import { ListEditor, PairsEditor, useVars } from "./VariablesPanel";

/**
 * Step fields by kind (docs/research/input-help-check.md): the help each kind of field gives besides typing. A value
 * or a variable for yes/no, dates, lists and dictionaries; Browse for files and folders; the vault for credentials;
 * the open windows for a window. Each one changes the shared field control, so every command with such a field has it.
 */

type FieldProps = { id: string; name: string; value: unknown; fallback: string; onCommit: (v: unknown) => void; apply: (text: string, flow: Flow) => Flow };

const isVarText = (v: unknown) => typeof v === "string" && /^\s*\$/.test(v);
const isCalc = (v: unknown) => !!v && typeof v === "object" && !Array.isArray(v) && typeof (v as { expr?: unknown }).expr === "string" && Object.keys(v as object).length === 1;

/** A choice of where a value comes from, as a row of buttons (Yes / No / Variable, Value / Variable, ...). */
function SourceSwitch<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return <div className="st-seg" role="radiogroup" aria-label={label}>
    {options.map(([k, t]) => <button key={k} type="button" role="radio" aria-checked={value === k} className="st-seg-btn" onClick={() => onChange(k)}>{t}</button>)}
  </div>;
}

/** The variable side of a field: the text box with the variable list ({x}, Ctrl+Space or $). */
function VariableBox({ id, name, value, want, placeholder, onCommit, apply }: { id: string; name: string; value: unknown; want: string; placeholder: string;
  onCommit: (v: unknown) => void; apply: (text: string, flow: Flow) => Flow }) {
  const text = isCalc(value) ? (value as { expr: string }).expr : typeof value === "string" ? value : "";
  return <CommitInput key={"var" + text} id={id} mono want={want} initial={text} placeholder={placeholder} nameHint={name}
    onCommit={(t) => onCommit(t.trim() === "" ? undefined : t.trim())} apply={apply} />;
}

// ------------------------------------------------------------------ yes / no

/** Yes, No, or a Boolean variable (the reference product offers the same three). */
export function BoolField({ id, name, value, fallback, onCommit, apply }: FieldProps) {
  const [mode, setMode] = useState<"true" | "false" | "variable" | "">(value === true ? "true" : value === false ? "false" : value === undefined ? "" : "variable");
  const pick = (m: typeof mode) => { setMode(m); if (m === "true" || m === "false") onCommit(m === "true"); else if (typeof value === "boolean") onCommit(undefined); };
  return <div className="st-source">
    <SourceSwitch label="Yes, no or a variable" value={mode} options={[["true", "Yes"], ["false", "No"], ["variable", "Variable"]]} onChange={pick} />
    {mode === "variable" && <VariableBox id={id} name={name} value={value} want="Boolean" placeholder="$isReady" onCommit={onCommit} apply={apply} />}
    {mode === "" && fallback && <span className="st-muted st-source-note">{fallback}</span>}
  </div>;
}

// ------------------------------------------------------------------ date and time

const ISO = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2})?)?$/;

/** A date and time from a calendar, or a variable (or text such as an expression). */
export function DateField({ id, name, value, onCommit, apply }: FieldProps) {
  const literal = typeof value === "string" && ISO.test(value.trim());
  const [mode, setMode] = useState<"date" | "variable">(value === undefined || literal ? "date" : "variable");
  return <div className="st-source">
    <SourceSwitch label="Date and time or a variable" value={mode} options={[["date", "Date and time"], ["variable", "Variable"]]} onChange={setMode} />
    {mode === "date"
      ? <TextInput id={id} aria-label="Date and time" type="datetime-local" step="1" key={"d" + String(literal ? value : "")}
          defaultValue={literal ? String(value).slice(0, 19) : ""}
          onChange={(e) => onCommit(e.target.value ? (e.target.value.length === 16 ? e.target.value + ":00" : e.target.value) : undefined)} />
      : <VariableBox id={id} name={name} value={value} want="DateTime" placeholder="$dueDate" onCommit={onCommit} apply={apply} />}
  </div>;
}

// ------------------------------------------------------------------ list, dictionary, table

/** A list or dictionary typed in item by item (or key by key), or a variable; a table as JSON. Kept when the editor is left. */
export function CollectionField({ id, name, type, value, onCommit, apply }: FieldProps & { type: "List" | "Dictionary" | "Table" }) {
  const isValue = value !== undefined && typeof value === "object" && !isCalc(value);
  const [mode, setMode] = useState<"value" | "variable">(isValue || value === undefined ? "value" : "variable");
  const latest = useRef<unknown>(value);
  const keep = () => { if (JSON.stringify(latest.current) !== JSON.stringify(value)) onCommit(latest.current); };
  const editor: ReactNode = type === "List"
    ? <ListEditor value={Array.isArray(value) ? value : []} onChange={(l) => { latest.current = l.length ? l : undefined; }} />
    : type === "Dictionary"
      ? <PairsEditor value={isValue && !Array.isArray(value) ? value as Record<string, unknown> : {}} onChange={(o) => { latest.current = Object.keys(o).length ? o : undefined; }} />
      : <CommitInput id={id} mono initial={isValue ? JSON.stringify(value) : ""} placeholder='{"columns": ["Name"], "rows": [["Ada"]]}'
          onCommit={(t) => { try { onCommit(t.trim() ? JSON.parse(t) : undefined); } catch { onCommit(t); } }} />;
  return <div className="st-source">
    <SourceSwitch label={type + " or a variable"} value={mode} options={[["value", type], ["variable", "Variable"]]} onChange={setMode} />
    {mode === "value"
      ? <div className="st-source-value" onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) keep(); }}>{editor}</div>
      : <VariableBox id={id} name={name} value={value} want={type} placeholder={"$my" + type} onCommit={onCommit} apply={apply} />}
  </div>;
}

// ------------------------------------------------------------------ files and folders

/**
 * A path typed in (with the variable list), chosen with Browse… on this computer, or (files) one kept in One CommandCenter
 * (F1, as AA's Control Room file): that one is also added to the agent's files, so the runner gets it before the run.
 */
export function FileField({ id, name, value, fallback, onCommit, apply, folder }: FieldProps & { folder: boolean }) {
  const vars = useVars();
  const [browsing, setBrowsing] = useState(false);
  const [fromRepo, setFromRepo] = useState(false);
  const text = typeof value === "string" ? value : value === undefined ? "" : showValue(value);
  const inRepo = !!vars?.flow.files?.includes(text);
  // the path gets the field's whole width; where it comes from is chosen just below it, in words (as AA's file sources)
  return <div className="st-file-field">
    <CommitInput key={"f" + text} id={id} initial={text} placeholder={fallback || (folder ? "C:\\Folder or $folder" : "C:\\Folder\\file.xlsx or $file")} want="File"
      nameHint={name} onCommit={(t) => onCommit(t === "" ? undefined : t)} apply={apply} />
    <div className="st-file-sources">
      <button type="button" className="st-link" aria-label="Browse…" title="Choose on this computer" onClick={() => setBrowsing(true)}><Icon name="folder" size={13} /> Browse this computer</button>
      {!folder && vars && <button type="button" className="st-link" aria-label="A file kept in One CommandCenter…" title="The runner gets it with the agent" onClick={() => setFromRepo(true)}>
        <Icon name="globe" size={13} /> From One CommandCenter</button>}
    </div>
    {inRepo && <div className="ui-field-hint">A file from One CommandCenter: the runner gets it with the agent.</div>}
    {browsing && <FileBrowser folder={folder} start={isVarText(text) ? "" : text}
      onPick={(p) => { setBrowsing(false); onCommit(p); }} onClose={() => setBrowsing(false)} />}
    {fromRepo && <RepoFileBrowser onClose={() => setFromRepo(false)} onPick={(p) => {
      setFromRepo(false);
      if (vars && apply) vars.edit(withRepoFile(apply(p, vars.flow), p)); else onCommit(p);
    }} />}
  </div>;
}

/**
 * Browse this computer's folders (OneAgentStudio runs here): drives, the home folder and the workspace first; a file
 * is chosen with a click, a folder with "Use this folder".
 */
export function FileBrowser({ folder, start, onPick, onClose }: { folder: boolean; start: string; onPick: (path: string) => void; onClose: () => void }) {
  const vars = useVars();
  const [at, setAt] = useState<FsListing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const go = (path: string) => {
    if (!vars) return;
    setError(null);
    vars.api.fsList(path).then(setAt, (e) => setError(errorText(e)));
  };
  useEffect(() => { go(start ? start.replace(/[\\/][^\\/]*\.[^\\/]+$/, "") : ""); }, []);   // eslint-disable-line react-hooks/exhaustive-deps
  const entries: FsEntry[] = at?.entries ?? [];
  return (
    <Dialog open title={folder ? "Choose a folder" : "Choose a file"} width={640} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      {at?.path && <Button kind={folder ? "primary" : "secondary"} onClick={() => onPick(at.path!)}>Use this folder</Button>}</>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="st-fs-places" role="group" aria-label="Places">
        {(at?.places ?? []).map((p) => <Button key={p.path} kind="plain" onClick={() => go(p.path)}>{p.name}</Button>)}
      </div>
      {at?.path && <div className="st-row-tight st-fs-where">
        <IconButton icon="up" label="Up one folder" disabled={!at.parent} onClick={() => at.parent && go(at.parent)} />
        <span className="ui-mono" title={at.path}>{at.path.length > 64 ? "…" + at.path.slice(-63) : at.path}</span></div>}
      <ul className="st-fs-list" role="listbox" aria-label={at?.path ? "In " + at.path : "Places"}>
        {entries.map((e) => <li key={e.path}>
          <button type="button" role="option" aria-selected="false" className={"st-fs-item" + (e.dir ? " is-dir" : "")} disabled={!e.dir && folder}
            onClick={() => e.dir ? go(e.path) : onPick(e.path)}><Icon name={e.dir ? "folder" : "doc"} size={14} />{e.name}</button></li>)}
        {at && !entries.length && <li className="st-muted">Nothing here.</li>}
      </ul>
    </Dialog>
  );
}

// ------------------------------------------------------------------ credentials

const vaultRef = (v: unknown) => v && typeof v === "object" && typeof (v as { $credential?: unknown }).$credential === "string" ? (v as { $credential: string }).$credential : null;

/**
 * A credential straight from the vault (folder, credential and attribute, chosen from One CommandCenter's vault when
 * OneAgentStudio is connected to it, or typed), or a Credential variable mapped when the agent runs.
 */
export function CredentialField({ id, name, value, onCommit, apply }: FieldProps) {
  const vars = useVars();
  const ref = vaultRef(value);
  const [mode, setMode] = useState<"vault" | "variable">(ref || value === undefined ? "vault" : "variable");
  const [list, setList] = useState<VaultCredentialRef[] | null>(null);
  useEffect(() => { if (mode === "vault" && vars && list === null) vars.api.crCredentials().then((r) => setList(r.credentials), () => setList([])); }, [mode, vars, list]);
  // the three parts typed one by one (without One CommandCenter's list): kept here until all three are there
  const [parts, setParts] = useState<[string, string, string]>(() => { const [f = "", c = "", a = ""] = (ref ?? "").split("/"); return [f, c, a]; });
  const [folder, cred, attr] = parts;
  const set = (f: string, c: string, a: string) => {
    setParts([f, c, a]);
    if (f.trim() && c.trim() && a.trim()) onCommit({ $credential: f.trim() + "/" + c.trim() + "/" + a.trim() });
    else if (ref) onCommit(undefined);
  };
  const key = (r: VaultCredentialRef) => r.locker + "/" + r.name + "/" + r.attribute;
  return <div className="st-source">
    <SourceSwitch label="Vault or a variable" value={mode} options={[["vault", "Vault"], ["variable", "Variable"]]} onChange={setMode} />
    {mode === "vault" ? <>
      {list && list.length > 0 && <Select id={id} aria-label="Credential from the vault" value={ref ?? ""}
        onChange={(e) => { const [f, c, a] = e.target.value.split("/"); if (e.target.value) set(f, c, a); else onCommit(undefined); }}
        options={[{ value: "", label: "Choose a credential…" }, ...list.map((r) => ({ value: key(r), label: r.locker + " / " + r.name + " / " + r.attribute })),
          ...(ref && !list.some((r) => key(r) === ref) ? [{ value: ref, label: ref.replace(/\//g, " / ") + " (not in the list)" }] : [])]} />}
      {list && !list.length && <div className="st-source st-vault-parts" role="group" aria-label="Vault reference">
        <CommitInput label="Vault folder" initial={folder ?? ""} placeholder="Folder" onCommit={(t) => set(t, cred ?? "", attr ?? "")} />
        <CommitInput label="Credential" initial={cred ?? ""} placeholder="Credential" onCommit={(t) => set(folder ?? "", t, attr ?? "")} />
        <CommitInput label="Attribute" initial={attr ?? ""} placeholder="password" onCommit={(t) => set(folder ?? "", cred ?? "", t)} />
      </div>}
      {list && !list.length && <span className="st-muted st-source-note">Connect to One CommandCenter to choose from its vault.</span>}
    </> : <VariableBox id={id} name={name} value={value} want="Credential" placeholder="$password" onCommit={onCommit} apply={apply} />}
  </div>;
}

// ------------------------------------------------------------------ windows

/** AI steps whose "connection" names a model connection (I1, batch 9). */
export const MODEL_STEPS = /^ooa\.(genai|ai_skills|ai_agent|idp|ai_screen|web_agent|knowledge)\//;

/**
 * I1 (batch 9): the model connection of an AI step, picked from the saved ones. Empty means the organisation's default
 * (shown with its name), which the runner uses when the step sets no model of its own; a name or a variable can still
 * be typed.
 */
export function ModelConnectionField({ id, name, value, onCommit, apply }: FieldProps) {
  const vars = useVars();
  const [conns, setConns] = useState<{ default: string; names: string[] } | null>(null);
  useEffect(() => {
    if (!vars) return;
    vars.api.modelConnections().then((c) => setConns({ default: c.default, names: c.connections.map((x) => x.name) }), () => setConns({ default: "", names: [] }));
  }, [vars]);
  const text = typeof value === "string" ? value : value === undefined ? "" : showValue(value);
  const known = conns?.names ?? [];
  if (text.startsWith("$") || (text && conns && !known.includes(text) && text !== "default"))
    return <CommitInput key={"mc" + text} id={id} initial={text} want="String" nameHint={name} onCommit={(t) => onCommit(t === "" ? undefined : t)} apply={apply} />;
  return <Select id={id} value={text === "default" ? "" : text} onChange={(e) => onCommit(e.target.value === "" ? undefined : e.target.value)}
    options={[{ value: "", label: conns === null ? "Reading the model connections…" : conns.default ? "Default (" + conns.default + ")" : "No default: choose one or set up here" },
      ...known.map((n) => ({ value: n, label: n }))]} />;
}

/** The fallbacks a window lookup understands (WindowPackage.WINDOW_ORDER), in the order they are tried. */
const WINDOW_ALTERNATIVES: { key: string; label: string; hint: string; number?: boolean }[] = [
  { key: "title_pattern", label: "Title pattern", hint: "Invoice*- Excel" },
  { key: "class_name", label: "Window class", hint: "XLMAIN" },
  { key: "process_name", label: "Program name", hint: "excel.exe" },
  { key: "index", label: "Index (with the class)", hint: "0", number: true },
];

/**
 * W3: the Window toolkit's alternatives as named fields instead of a key/value list, stored the same way
 * ({title_pattern, class_name, process_name, index}); a variable holding them still works.
 */
export function WindowAlternativesField({ id, name, value, onCommit, apply }: FieldProps) {
  if (value !== undefined && (typeof value !== "object" || Array.isArray(value)))
    return <CommitInput id={id} initial={showValue(value)} want="Dictionary" nameHint={name} onCommit={(t) => onCommit(t === "" ? undefined : t)} apply={apply} />;
  const cur = (value ?? {}) as Record<string, unknown>;
  const put = (key: string, v: unknown) => {
    const next = { ...cur };
    if (v === undefined || v === "") delete next[key]; else next[key] = v;
    onCommit(Object.keys(next).length ? next : undefined);
  };
  return <div className="st-source" id={id}>
    {WINDOW_ALTERNATIVES.map((a) => <label key={a.key} className="st-row-tight st-alt-field">
      <span className="st-alt-label">{a.label}</span>
      <CommitInput key={a.key + showValue(cur[a.key])} label={a.label} initial={cur[a.key] === undefined ? "" : showValue(cur[a.key])} placeholder={a.hint}
        want={a.number ? "Number" : "String"} nameHint={a.key}
        onCommit={(t) => put(a.key, t === "" ? undefined : a.number && /^\d+$/.test(t.trim()) ? Number(t.trim()) : t)} apply={apply} />
    </label>)}
  </div>;
}

/**
 * Fields that name a window: every "window" field, and the title of the Window toolkit's commands and conditions
 * (W1), which pick from the open windows the same way.
 */
export function isWindowTitle(action: string, name: string, spec?: { "x-ooa-label"?: unknown }) {
  return name === "window" || spec?.["x-ooa-label"] === "Window" || (name === "title" && action.startsWith("ooa.window/"));
}

/**
 * A window title typed in (text in it, * and ? wildcards, or re: and a regular expression), or chosen from the windows
 * open on this computer. The title has the whole width; the picker sits under it (U1), so a long title stays readable.
 */
export function WindowField({ id, name, value, fallback, onCommit, apply }: Omit<FieldProps, "apply"> & { apply?: FieldProps["apply"] }) {
  const vars = useVars();
  const [open, setOpen] = useState<OpenWindow[] | null>(null);
  const [busy, setBusy] = useState(false);
  const text = typeof value === "string" ? value : value === undefined ? "" : showValue(value);
  const load = () => { if (!vars) return; setBusy(true); vars.api.windows().then((w) => setOpen(w.filter((x) => x.title)), () => setOpen([])).finally(() => setBusy(false)); };
  return <div className="st-source st-window-field">
    <CommitInput key={"w" + text} id={id} initial={text} placeholder={fallback || "*Notepad"} want="String" nameHint={name}
      onCommit={(t) => onCommit(t === "" ? undefined : t)} apply={apply} />
    <div className="st-row-tight">
      <Button kind="secondary" disabled={busy || !vars} onClick={load}>{busy ? "Looking…" : open ? "Refresh" : "Open windows"}</Button>
      {open && <Select aria-label="Open windows" value="" onChange={(e) => e.target.value && onCommit(e.target.value)}
        options={[{ value: "", label: open.length ? "Choose an open window…" : "No windows found" },
          ...open.map((w) => ({ value: w.title!, label: w.title! + (w.process ? " (" + w.process + ")" : w.browser ? " (" + w.browser + ")" : "") }))]} />}
    </div>
  </div>;
}
