import { useCallback, useRef, useState, type ReactElement } from "react";
import type { Flow, FlowStep, StudioApi } from "../api/client";
import { setArg, stateLists } from "./flowEdit";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { oneStep, runOnce } from "./runOnce";
import { useVars } from "./VariablesPanel";
import { KeyPicker, keyKind } from "./KeyPicker";

/**
 * Pickers beside step fields (gap round 2, 2 Nov 2026), as other RPA designers offer them: choose a SOAP operation from
 * the service's WSDL, browse the directory for a distinguished name (or build one from a path), draw the area of a PDF
 * page to read, and write an email body as formatted text. Each runs the matching toolkit action on this computer
 * through a one-step check run, so it sees what the agent will see.
 */

type Args = Record<string, unknown>;
const literal = (v: unknown) => (typeof v === "string" && !v.startsWith("$") ? v : "");

/** The picker for a field of a step, or null when the field has none. */
export function pickerFor(step: FlowStep, name: string, props: Record<string, unknown>): ReactElement | null {
  if (step.action === "ooa.soap/send" && name === "url") return <WsdlPicker key="wsdl" step={step} />;
  if (step.action.startsWith("ooa.active_directory/") && DN_FIELDS.has(name)) return <DirectoryPicker key={"dir:" + name} step={step} field={name} />;
  if (step.action === "ooa.pdf/extract_area_text" && name === "x") return <PdfAreaPicker key="area" step={step} />;
  if (isHtmlBody(step, name, props)) return <HtmlBodyEditor key={"html:" + name} step={step} field={name} />;
  if (POINT_STEPS.test(step.action) && name === "x") return <PointPicker key="point" step={step} />;
  const keys = keyKind(step.action, name);
  if (keys) return <KeyPicker key={"keys:" + name} step={step} name={name} kind={keys} />;
  return null;
}

/** Shows a picker's button under the field it fills. */
export function PickerRow({ children }: { children: ReactElement }) {
  return <div className="st-picker-row">{children}</div>;
}

function useArgSetter(step: FlowStep) {
  const vars = useVars();
  return (values: Args) => {
    if (!vars) return;
    let f: Flow = vars.flow;
    for (const [k, v] of Object.entries(values)) f = setArg(f, step.id, k, v);
    vars.edit(f);
  };
}

async function check(api: StudioApi, action: string, args: Args, out: Record<string, string>) {
  const r = await runOnce(api, oneStep(action, args, out), {}, 120_000);
  if (r.status !== "success") throw new Error(r.error?.message ?? "The check did not finish (" + r.status + ")");
  return r.outputs ?? {};
}

// ------------------------------------------------------------------ M1: a point on the screen

/** Steps with a point (x, y) that can be picked on the screen; the mouse steps also keep the window's size (M2). */
const POINT_STEPS = /^ooa\.desktop\/(mouse_click|mouse_move|mouse_scroll|show_callout)$/;

export type PickedPoint = { x: number; y: number; screen_x: number; screen_y: number; window: string; window_width?: number; window_height?: number };

/**
 * What a picked point fills in: the window under the pointer, the point measured from its top-left corner, and for
 * mouse steps the window's size so it is given that size again first. Over no window, the point on the screen.
 */
export function pointArgs(action: string, p: PickedPoint): Args {
  const sized = action !== "ooa.desktop/show_callout";
  if (!p.window) return { x: p.screen_x, y: p.screen_y, relative_to: "screen", window: undefined, ...(sized ? { window_width: undefined, window_height: undefined } : {}) };
  return { x: p.x, y: p.y, relative_to: "window", window: p.window, ...(sized ? { window_width: p.window_width, window_height: p.window_height } : {}) };
}

function PointPicker({ step }: { step: FlowStep }) {
  const vars = useVars();
  const set = useArgSetter(step);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!vars) return null;
  const pick = async () => {
    setBusy(true); setError(null);
    try {
      const p = await vars.api.pickPoint({ timeout_seconds: 60 });
      set(pointArgs(step.action, p));
      vars.notice?.(p.window ? "Point " + p.x + ", " + p.y + " in “" + p.window + "”" + (p.window_width ? ", a window of " + p.window_width + " x " + p.window_height : "") + "."
        : "Point " + p.screen_x + ", " + p.screen_y + " on the screen (no window there).");
    } catch (e) { setError(String((e as Error).message ?? e)); }
    finally { setBusy(false); }
  };
  return <PickerRow><span className="st-row-tight">
    {busy && <span className="st-muted st-source-note">Point at the place and press Ctrl+Shift+C</span>}
    {error && <span className="st-alert" role="alert">{error}</span>}
    <Button kind="secondary" disabled={busy} onClick={pick} title="Point at a place in any window and press Ctrl+Shift+C">{busy ? "Waiting…" : "Pick on screen"}</Button>
  </span></PickerRow>;
}

// ------------------------------------------------------------------ SOAP: operation from the WSDL

type Operation = { name: string; soap_action?: string; endpoint?: string; version?: string; style?: string; body_template?: string };

function WsdlPicker({ step }: { step: FlowStep }) {
  const vars = useVars();
  const set = useArgSetter(step);
  const [open, setOpen] = useState(false);
  const [wsdl, setWsdl] = useState(() => { const u = literal(step.args?.url); return u ? u.replace(/\?wsdl$/i, "") + "?wsdl" : ""; });
  const [ops, setOps] = useState<Operation[] | null>(null);
  const [chosen, setChosen] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!vars) return null;
  const read = async () => {
    setBusy(true); setError(null); setOps(null);
    try {
      const args: Args = { wsdl: wsdl.trim() };
      if (step.args?.basic_user) { args.basic_user = step.args.basic_user; args.basic_password = step.args.basic_password; }
      const o = await check(vars.api, "ooa.soap/read_wsdl", args, { operations: "operations" });
      const list = (o.operations as Operation[] | undefined) ?? [];
      setOps(list); setChosen(list[0]?.name ?? "");
      if (!list.length) setError("The WSDL lists no operations.");
    } catch (e) { setError(String((e as Error).message ?? e)); }
    finally { setBusy(false); }
  };
  const op = ops?.find((o) => o.name === chosen);
  const use = () => {
    if (!op) return;
    const v: Args = { soap_action: op.soap_action || undefined, version: op.version || undefined };
    if (op.endpoint) v.url = op.endpoint;
    if (op.body_template) v.body = op.body_template;
    set(v);
    vars.notice?.("Filled in " + op.name + " from the WSDL: replace each ? in the body with a value or a $variable.");
    setOpen(false);
  };
  return <PickerRow><>
    <Button kind="plain" onClick={() => setOpen(true)}>Choose an operation from the WSDL…</Button>
    <Dialog open={open} onClose={() => setOpen(false)} title="Choose a SOAP operation" width={640}
      footer={<><Button kind="secondary" onClick={() => setOpen(false)}>Cancel</Button><Button kind="primary" disabled={!op} onClick={use}>Use this operation</Button></>}>
      <Field label="WSDL address or file">{(id) => <div className="st-row-tight">
        <TextInput id={id} value={wsdl} placeholder="https://erp.example.com/OrderService?wsdl" onChange={(e) => setWsdl(e.target.value)} />
        <Button kind="secondary" disabled={!wsdl.trim() || busy} onClick={read}>{busy ? "Reading…" : "Read"}</Button></div>}</Field>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ops && ops.length > 0 && <>
        <Field label="Operation">{(id) => <Select id={id} value={chosen} onChange={(e) => setChosen(e.target.value)}
          options={ops.map((o) => ({ value: o.name, label: o.name + (o.version ? " (SOAP " + o.version + ")" : "") }))} />}</Field>
        {op && <dl className="st-picker-facts">
          <dt>Endpoint</dt><dd>{op.endpoint || "—"}</dd>
          <dt>SOAPAction</dt><dd>{op.soap_action || "—"}</dd>
          <dt>Request body</dt><dd><pre className="st-picker-pre">{op.body_template || "—"}</pre></dd>
        </dl>}
      </>}
    </Dialog>
  </></PickerRow>;
}

// ------------------------------------------------------------------ Active Directory: browse or build a distinguished name

const DN_FIELDS = new Set(["base_dn", "container_dn", "group_dn", "ou", "parent_dn", "target_ou_dn", "target_parent_dn", "manager"]);
type DirEntry = { dn: string; name: string; kind: string };
const CONTAINERS = new Set(["organizationalUnit", "container", "domainDNS", "builtinDomain", "lostAndFound"]);

/** The connect step's settings in this flow, to sign in the same way while browsing. */
function connectArgs(flow: Flow): Args | null {
  let found: Args | null = null;
  const walk = (steps: FlowStep[] | undefined) => { for (const s of steps ?? []) {
    if (!found && s.action === "ooa.active_directory/connect") found = s.args ?? {};
    walk(s.children); walk(s.else); walk(s.finally);
    for (const b of s.elseIf ?? []) walk(b.children);
    for (const c of s.catch ?? []) walk(c.children);
    for (const c of s.cases ?? []) walk(c.children);
    walk(s.default);
    for (const l of stateLists(s)) walk(l);
  } };
  walk(flow.steps); walk(flow.onError?.steps ?? []); for (const sub of flow.subflows ?? []) walk(sub.steps ?? []);
  return found;
}

/** Connect inputs that browsing needs but the flow's Connect step leaves empty (it signs in the same way). */
export function connectMissing(conn: Args | null): string[] {
  if (!conn) return [];
  const label: Record<string, string> = { url: "server address (url)", bind_user: "user (bind_user)", password: "password" };
  return Object.keys(label).filter((k) => conn[k] === undefined || conn[k] === null || conn[k] === "").map((k) => label[k]);
}

/** DN from a path and domain, as the toolkit's Build distinguished name does it. */
export function buildDn(path: string, domain: string, name = "", nameKind: "CN" | "OU" = "CN"): string {
  const esc = (v: string) => v.replace(/([,+"\\<>;=])/g, "\\$1").replace(/^([ #])/, "\\$1").replace(/ $/, "\\ ");
  const builtIn = new Set(["users", "computers", "builtin", "system", "program data", "foreignsecurityprincipals", "managed service accounts", "lostandfound"]);
  const segs = path.replace(/\\/g, "/").split("/").map((s) => s.trim()).filter(Boolean);
  let dom = domain.trim();
  if (!dom && segs.length && /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(segs[0])) dom = segs.shift()!;
  if (!dom) return "";
  const rdns: string[] = [];
  if (name.trim()) rdns.push(nameKind + "=" + esc(name.trim()));
  for (let i = segs.length - 1; i >= 0; i--) rdns.push((i === 0 && builtIn.has(segs[i].toLowerCase()) ? "CN=" : "OU=") + esc(segs[i]));
  for (const dc of dom.split(".")) rdns.push("DC=" + dc);
  return rdns.join(",");
}

function DirectoryPicker({ step, field }: { step: FlowStep; field: string }) {
  const vars = useVars();
  const set = useArgSetter(step);
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<"browse" | "build">("browse");
  const [base, setBase] = useState("");
  const [items, setItems] = useState<DirEntry[] | null>(null);
  const [trail, setTrail] = useState<string[]>([]);
  const [pick, setPick] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [path, setPath] = useState(""), [domain, setDomain] = useState(""), [name, setName] = useState("");
  if (!vars) return null;
  const conn = connectArgs(vars.flow);
  const missing = connectMissing(conn);
  const list = async (dn: string) => {
    if (!conn || missing.length) return;
    setBusy(true); setError(null);
    try {
      const flow: Flow = {
        kind: "TaskBot", id: "studio_check", name: "One AgentStudio check",
        variables: [{ name: "t", type: "Any", direction: "output" }],
        steps: [{ id: "c", action: "ooa.active_directory/connect", args: { ...conn, session: "studio_browse" } },
          { id: "q", action: "ooa.active_directory/run_query", args: { session: "studio_browse", base_dn: dn || undefined, scope: "one_level", max_results: 500,
            filter: "(|(objectClass=organizationalUnit)(objectClass=container)(objectClass=builtinDomain)(objectClass=user)(objectClass=group)(objectClass=computer))",
            attributes: ["name", "objectClass", "sAMAccountName"] }, out: { table: "t" } }],
      };
      const r = await runOnce(vars.api, flow, {}, 60_000);
      if (r.status !== "success") throw new Error(r.error?.message ?? "The directory did not answer");
      const t = r.outputs?.t as { columns: string[]; rows: unknown[][] } | undefined;
      const ci = (c: string) => t?.columns.indexOf(c) ?? -1;
      const rows: DirEntry[] = (t?.rows ?? []).map((row) => {
        const classes = ([] as unknown[]).concat(row[ci("objectClass")] ?? []).map(String);
        const kind = classes.find((c) => CONTAINERS.has(c)) ?? (classes.includes("computer") ? "computer" : classes.includes("group") ? "group" : classes.includes("user") ? "user" : classes[classes.length - 1] ?? "");
        return { dn: String(row[ci("dn")]), name: String(row[ci("name")] ?? row[ci("dn")]), kind };
      }).sort((a, b) => Number(CONTAINERS.has(b.kind)) - Number(CONTAINERS.has(a.kind)) || a.name.localeCompare(b.name));
      setItems(rows); setBase(dn);
    } catch (e) { setError(String((e as Error).message ?? e)); }
    finally { setBusy(false); }
  };
  const start = () => { setOpen(true); setItems(null); setTrail([]); setPick(literal(step.args?.[field])); if (conn) list(literal(conn.base_dn)); };
  const enter = (e: DirEntry) => { setTrail([...trail, base]); setPick(e.dn); list(e.dn); };
  const up = () => { const prev = trail[trail.length - 1] ?? ""; setTrail(trail.slice(0, -1)); list(prev); };
  const built = buildDn(path, domain, name);
  const value = tab === "browse" ? pick : built;
  return <PickerRow><>
    <Button kind="plain" onClick={start}>Browse the directory…</Button>
    <Dialog open={open} onClose={() => setOpen(false)} title="Choose a directory object" width={680}
      footer={<><Button kind="secondary" onClick={() => setOpen(false)}>Cancel</Button>
        <Button kind="primary" disabled={!value} onClick={() => { set({ [field]: value }); setOpen(false); }}>Use this name</Button></>}>
      <div className="st-seg" role="radiogroup" aria-label="How to choose">
        {([["browse", "Browse"], ["build", "Build from a path"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="radio" aria-checked={tab === k} className="st-seg-btn" onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "browse" && <>
        {!conn && <p className="st-alert" role="alert">Add a Connect step (Active Directory) to this agent first; browsing signs in the same way.</p>}
        {conn && missing.length > 0 && <p className="st-alert" role="alert">Fill in the Connect step's {missing.join(", ")} first; browsing signs in the same way. Or build the name from a path.</p>}
        {error && <p className="st-alert" role="alert">{error}</p>}
        {conn && missing.length === 0 && <>
          <p className="st-muted">In: <code>{base || "(the base of the connection)"}</code> {trail.length > 0 && <Button kind="plain" onClick={up} disabled={busy}>Up one level</Button>}</p>
          {busy && <p role="status">Reading the directory…</p>}
          <ul className="st-picker-list" aria-label="Directory objects">
            {(items ?? []).map((e) => <li key={e.dn}>
              <button type="button" className={"st-link" + (pick === e.dn ? " is-chosen" : "")} onClick={() => setPick(e.dn)} onDoubleClick={() => CONTAINERS.has(e.kind) && enter(e)}>
                {CONTAINERS.has(e.kind) ? "📁 " : ""}{e.name}</button> <span className="st-muted">{e.kind}</span>
              {CONTAINERS.has(e.kind) && <Button kind="plain" onClick={() => enter(e)} disabled={busy}>Open</Button>}
            </li>)}
            {items && !items.length && <li className="st-muted">Nothing here.</li>}
          </ul>
        </>}
      </>}
      {tab === "build" && <>
        <Field label="Organizational units, top down">{(id) => <TextInput id={id} value={path} placeholder="Corp/Sales/EMEA" onChange={(e) => setPath(e.target.value)} />}</Field>
        <Field label="Domain">{(id) => <TextInput id={id} value={domain} placeholder="corp.example.com" onChange={(e) => setDomain(e.target.value)} />}</Field>
        <Field label="Object name (optional)">{(id) => <TextInput id={id} value={name} placeholder="Ann Lee" onChange={(e) => setName(e.target.value)} />}</Field>
      </>}
      <p className="st-muted">Distinguished name: <code>{value || "—"}</code></p>
    </Dialog>
  </></PickerRow>;
}

// ------------------------------------------------------------------ PDF: draw the area to read

function PdfAreaPicker({ step }: { step: FlowStep }) {
  const vars = useVars();
  const set = useArgSetter(step);
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState(""), [page, setPage] = useState("1");
  const [img, setImg] = useState<{ src: string; w: number; h: number; pages: number } | null>(null);
  const [rect, setRect] = useState<{ x: number; y: number; w: number; h: number } | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null);
  const drag = useRef<{ x: number; y: number } | null>(null);
  const box = useRef<HTMLDivElement>(null);
  if (!vars) return null;
  const load = async (path = file, pg = page) => {
    setBusy(true); setError(null);
    try {
      const o = await check(vars.api, "ooa.pdf/render_page", { path, page: Number(pg) || 1, dpi: 96, ...(step.args?.password ? { password: step.args.password } : {}) },
        { image: "image", width_points: "w", height_points: "h", page_count: "n" });
      setImg({ src: String(o.image), w: Number(o.w), h: Number(o.h), pages: Number(o.n) });
    } catch (e) { setError(String((e as Error).message ?? e)); setImg(null); }
    finally { setBusy(false); }
  };
  const start = () => {
    const p = literal(step.args?.path), pg = String(typeof step.args?.page === "number" ? step.args.page : 1);
    setFile(p); setPage(pg); setOpen(true); setImg(null);
    const x = Number(step.args?.x), y = Number(step.args?.y), w = Number(step.args?.width), h = Number(step.args?.height);
    setRect([x, y, w, h].every((v) => Number.isFinite(v)) ? { x, y, w, h } : null);
    if (p) load(p, pg);
  };
  // pointer position in points, from the shown (scaled) picture
  const at = (e: React.PointerEvent) => {
    const r = box.current!.getBoundingClientRect();
    const sx = img!.w / r.width, sy = img!.h / r.height;
    return { x: Math.max(0, Math.min(img!.w, (e.clientX - r.left) * sx)), y: Math.max(0, Math.min(img!.h, (e.clientY - r.top) * sy)) };
  };
  const round = (v: number) => Math.round(v * 2) / 2;
  return <PickerRow><>
    <Button kind="plain" onClick={start}>Draw the area on the page…</Button>
    <Dialog open={open} onClose={() => setOpen(false)} title="Draw the area to read" width={900}
      footer={<><Button kind="secondary" onClick={() => setOpen(false)}>Cancel</Button>
        <Button kind="primary" disabled={!rect || rect.w < 1 || rect.h < 1} onClick={() => { set({ x: round(rect!.x), y: round(rect!.y), width: round(rect!.w), height: round(rect!.h), page: Number(page) || 1 }); setOpen(false); }}>Use this area</Button></>}>
      <div className="st-row-tight">
        <Field label="PDF file">{(id) => <TextInput id={id} value={file} placeholder="C:\\Invoices\\sample.pdf" onChange={(e) => setFile(e.target.value)} />}</Field>
        <Field label="Page">{(id) => <TextInput id={id} value={page} onChange={(e) => setPage(e.target.value)} />}</Field>
        <Button kind="secondary" disabled={!file || busy} onClick={() => load()}>{busy ? "Opening…" : "Show page"}</Button>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {img && <>
        <p className="st-muted">Drag over the text to read. Page {page} of {img.pages}; {rect ? `area ${round(rect.x)}, ${round(rect.y)} — ${round(rect.w)} × ${round(rect.h)} points` : "no area yet"}.</p>
        <div ref={box} className="st-pdf-page" style={{ aspectRatio: img.w + " / " + img.h }}
          onPointerDown={(e) => { (e.target as HTMLElement).setPointerCapture?.(e.pointerId); const p = at(e); drag.current = p; setRect({ x: p.x, y: p.y, w: 0, h: 0 }); }}
          onPointerMove={(e) => { if (!drag.current) return; const p = at(e), s = drag.current; setRect({ x: Math.min(s.x, p.x), y: Math.min(s.y, p.y), w: Math.abs(p.x - s.x), h: Math.abs(p.y - s.y) }); }}
          onPointerUp={() => { drag.current = null; }}>
          <img src={img.src} alt={"Page " + page} draggable={false} />
          {rect && <div className="st-pdf-area" aria-hidden style={{ left: (rect.x / img.w * 100) + "%", top: (rect.y / img.h * 100) + "%", width: (rect.w / img.w * 100) + "%", height: (rect.h / img.h * 100) + "%" }} />}
        </div>
      </>}
    </Dialog>
  </></PickerRow>;
}

// ------------------------------------------------------------------ Email bodies: formatted text editor

/** Body fields of email steps that are HTML (an html switch, a body type of HTML, or an html_body field). */
export function isHtmlBody(step: FlowStep, name: string, props: Record<string, unknown>): boolean {
  if (!/^(ooa\.(email|m365|microsoft365|google|outlook|outlook_desktop|gmail|smtp|exchange)\b)/.test(step.action) && !/mail|send|reply|forward|draft/.test(step.action)) return false;
  if (name === "html_body" || name === "body_html") return true;
  if (name !== "body" && name !== "message" && name !== "note") return false;
  const a = step.args ?? {};
  if ("html" in props) return a.html === true;
  if ("body_type" in props) return String(a.body_type ?? "").toLowerCase() === "html";
  if ("content_type" in props) return /html/i.test(String(a.content_type ?? ""));
  return false;
}

const TOOLS: [string, string, string?][] = [["bold", "B"], ["italic", "I"], ["underline", "U"], ["insertUnorderedList", "• List"], ["insertOrderedList", "1. List"],
  ["formatBlock", "Heading", "h2"], ["formatBlock", "Paragraph", "p"], ["removeFormat", "Clear format"]];

function HtmlBodyEditor({ step, field }: { step: FlowStep; field: string }) {
  const set = useArgSetter(step);
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"design" | "source">("design");
  const [html, setHtmlState] = useState("");
  const latest = useRef("");
  const setHtml = (v: string) => { latest.current = v; setHtmlState(v); };
  const area = useRef<HTMLDivElement | null>(null);
  // the editing area is filled when it appears (the dialog mounts its content after opening, so an effect can miss it)
  const areaRef = useCallback((el: HTMLDivElement | null) => { area.current = el; if (el) el.innerHTML = latest.current; }, []);
  const current = typeof step.args?.[field] === "string" ? String(step.args?.[field]) : "";
  const sync = () => { if (mode === "design" && area.current) setHtml(area.current.innerHTML); };
  const run = (cmd: string, value?: string) => { area.current?.focus(); document.execCommand(cmd, false, value); sync(); };
  return <PickerRow><>
    <Button kind="plain" onClick={() => { setHtml(current); setMode("design"); setOpen(true); }}>Edit as formatted text…</Button>
    <Dialog open={open} onClose={() => setOpen(false)} title="Email body" width={820}
      footer={<><Button kind="secondary" onClick={() => setOpen(false)}>Cancel</Button>
        <Button kind="primary" onClick={() => { const v = mode === "design" && area.current ? area.current.innerHTML : html; set({ [field]: v }); setOpen(false); }}>Use this body</Button></>}>
      <div className="st-seg" role="radiogroup" aria-label="Editor view">
        {([["design", "Design"], ["source", "HTML"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="radio" aria-checked={mode === k} className="st-seg-btn" onClick={() => { sync(); setMode(k); }}>{l}</button>)}
      </div>
      {mode === "design" && <>
        <div className="st-html-tools" role="toolbar" aria-label="Formatting">
          {TOOLS.map(([cmd, label, v]) => <button key={label} type="button" className="st-seg-btn" onMouseDown={(e) => e.preventDefault()} onClick={() => run(cmd, v)}>{label}</button>)}
          <button type="button" className="st-seg-btn" onMouseDown={(e) => e.preventDefault()} onClick={() => { const u = window.prompt("Link address"); if (u) run("createLink", u); }}>Link</button>
          <label className="st-html-colour">Colour <input type="color" aria-label="Text colour" onChange={(e) => run("foreColor", e.target.value)} /></label>
          <button type="button" className="st-seg-btn" onMouseDown={(e) => e.preventDefault()} onClick={() => run("insertText", "$variable")}>Insert $variable</button>
        </div>
        <div ref={areaRef} className="st-html-area" contentEditable suppressContentEditableWarning role="textbox" aria-multiline aria-label="Body" onInput={sync} onBlur={sync} />
        <p className="st-muted">Variables such as $customer are filled in when the agent runs.</p>
      </>}
      {mode === "source" && <textarea className="st-html-source" aria-label="HTML" rows={16} value={html} onChange={(e) => setHtml(e.target.value)} />}
    </Dialog>
  </></PickerRow>;
}
