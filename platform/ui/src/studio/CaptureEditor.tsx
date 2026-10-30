import { useState } from "react";
import type { FlowStep, OpenWindow } from "../api/client";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { locate } from "./flowEdit";
import {
  DESKTOP_LABELS, fillWindowFromCapture, MATCHES, numberedName, WEB_LABELS, WINDOW_PRESETS, withAnchorOffset, type Captured, type CapturedProperty, type DesktopWindow,
} from "./capture";
import { useVars, type WhereChoice } from "./VariablesPanel";

// "only the selected properties" or "all properties", remembered across steps as in Classic
const VIEW_KEY = "ooa.studio.captureView2";
const readView = () => { try { return localStorage.getItem(VIEW_KEY) === "all" ? "all" : "used"; } catch { return "used"; } };

/**
 * A captured object: what was captured (web page or desktop window), Recapture and Test, Main and Anchor, the property
 * table (tick the ones to search by, how each must match, add your own), which of several matches, and the page or
 * window it lives in. A nested editor (the anchor) has no head, tabs or page.
 */
export function CaptureEditor({ step, value, onChange, nested, parent }:
  { step: FlowStep; value: unknown; onChange: (v: Captured | undefined) => void; nested?: boolean; parent?: Captured }) {
  const vars = useVars();
  const obj = value && typeof value === "object" && !Array.isArray(value) ? value as Captured : null;
  const desktop = step.action.startsWith("ooa.desktop_ui/") || obj?.technology === "desktop";
  const labels = desktop ? DESKTOP_LABELS : WEB_LABELS;
  const [tab, setTab] = useState<"main" | "anchor">("main");
  const [view, setView] = useState<"used" | "all">(readView);
  const [keep, setKeep] = useState<Set<number>>(new Set());   // unticked while only the selected show: they stay until the list is switched
  const [filter, setFilter] = useState("");
  const [busy, setBusy] = useState(false);
  if (!vars) return null;
  const pageUrl = obj?.page?.url || parent?.page?.url;
  const put = (o: Captured) => onChange(o);
  const name = (p: CapturedProperty) => labels[p.name] ?? p.name;

  /** Capture (or recapture): web in a browser tab or page, desktop by hovering and Ctrl+Shift+C. */
  const capture = async (forAnchor = false) => {
    setBusy(true);
    try {
      let o: Captured | undefined;
      if (desktop) {
        vars.notice?.(forAnchor ? "Hover over the anchor (a nearby control that is easy to find, such as the field's label), then press Ctrl+Shift+C."
          : "Hover over the control in the application (a red frame follows the pointer), then press Ctrl+Shift+C.");
        const r = await vars.api.captureDesktop({ timeout_seconds: 60, screenshot: !forAnchor, highlight: true });
        o = (r.object ?? r) as Captured;
      } else {
        const where = await vars.where?.("capture", { title: typeof step.args?.window === "object" ? (step.args.window as { title?: string }).title : undefined, url: pageUrl });
        if (!where) return;
        vars.notice?.(forAnchor ? "Click the anchor: a nearby element that is easy to find, such as the field's label or a heading." : "Click the element in the browser window (Esc cancels).");
        o = (await vars.api.capture(where)).object as Captured | undefined;
      }
      if (!o) return;
      if (forAnchor && obj) {
        delete o.screenshot; if (desktop) delete o.window;   // the anchor is looked for in the same window
        const next = withAnchorOffset({ ...obj, anchor: o });
        put(next);
        vars.notice?.("Anchor captured" + (next.anchor?.offset ? ` (${next.anchor.offset.dx}, ${next.anchor.offset.dy} px from the ${desktop ? "control" : "element"})` : "") + ".");
        return;
      }
      const next = obj?.anchor ? withAnchorOffset({ ...o, anchor: obj.anchor }) : o;
      if (nested) { put(next); return; }
      // a web capture also sets the step's window (a numbered window variable), as one change
      const f = structuredClone(vars.flow);
      const at = locate(f.steps, step.id);
      if (!at) return;
      at.step.args = { ...(at.step.args ?? {}), object: next };
      vars.edit(desktop ? f : fillWindowFromCapture(f, at.step, next, "window" in (at.step.args ?? {}) || step.action.startsWith("ooa.browser/")));
      vars.notice?.("Captured " + (next.properties ?? []).length + " properties.");
    } catch (e) { vars.fail?.(e); }
    finally { setBusy(false); }
  };
  const test = async () => {
    if (!obj) return;
    const url = pageUrl || window.prompt("Open which page to test on?", "https://");
    if (!url) return;
    vars.notice?.("Opening the page: what matches is outlined (green: the one the step uses).");
    try {
      const n = Number((await vars.api.captureTest(url, obj)).count ?? 0);
      vars.notice?.(n === 0 ? "Nothing matches: loosen a property (contains, wildcard) or recapture."
        : n + (n === 1 ? " element matches." : " elements match. Use Which match or an anchor to choose one, or tick another property."));
    } catch (e) { vars.fail?.(e); }
  };

  if (!obj) return (
    <div className="st-cap st-cap-empty">
      <span>Nothing captured yet.</span>
      <Button kind="secondary" onClick={() => capture()} disabled={busy}>{busy ? "Capturing…" : desktop ? "Capture desktop object" : "Capture element"}</Button>
    </div>
  );

  const props = obj.properties ?? [];
  const setProp = (i: number, patch: Partial<CapturedProperty>) => put({ ...obj, properties: props.map((p, j) => j === i ? { ...p, ...patch } : p) });
  const used = props.filter((p) => p.use).length;
  const f = filter.trim().toLowerCase();
  const shown = props.map((p, i) => [p, i] as const).filter(([p, i]) => (view === "all" || p.use || p.custom || keep.has(i) || !!f)
    && (!f || (p.name + " " + name(p) + " " + String(p.value ?? "")).toLowerCase().includes(f)));
  const switchView = (v: "used" | "all") => { setView(v); setKeep(new Set()); try { localStorage.setItem(VIEW_KEY, v); } catch { /* storage unavailable */ } };
  const index = obj.index ?? 0;
  const where = desktop ? (typeof obj.window === "string" ? "window " + obj.window : (obj.window?.title ?? "") + (obj.window?.process ? " (" + obj.window.process + ")" : ""))
    : obj.page?.title || obj.page?.url || "";

  const table = (
    <div className="st-cap-props">
      <div className="st-cap-props-head"><strong>Object properties</strong><span className="st-cap-count">{used} selected of {props.length}</span>
        <button type="button" className="st-link st-cap-more" aria-expanded={view === "all"} onClick={() => switchView(view === "used" ? "all" : "used")}>
          {view === "used" ? "Show all properties" + (props.length - shown.length > 0 ? ` (${props.length - shown.length} more)` : "") : "Show only selected"}</button></div>
      {props.length > 8 && view === "all" && <TextInput type="search" aria-label="Find a property" placeholder="Find a property" value={filter} onChange={(e) => setFilter(e.target.value)} />}
      <table className="st-cap-table" aria-label="Captured properties">
        <thead><tr><th scope="col" title="Ticked properties are used to find it">Use</th><th scope="col">Name</th><th scope="col">Value</th><th scope="col">Match</th></tr></thead>
        <tbody>
          {shown.map(([p, i]) => (
            <tr key={i} className={p.use ? "is-on" : ""}>
              <td><input type="checkbox" checked={!!p.use} aria-label={"Search by " + name(p)}
                onChange={(e) => { if (!e.target.checked && view === "used") setKeep(new Set(keep).add(i)); setProp(i, { use: e.target.checked }); }} /></td>
              <td>{p.custom ? <TextInput aria-label="Property name" placeholder="attribute" defaultValue={p.name} onBlur={(e) => setProp(i, { name: e.target.value.trim() })} />
                : <span className="st-cap-name" title={p.name}>{name(p)}</span>}</td>
              <td><TextInput aria-label={"Value of " + name(p)} defaultValue={p.value == null ? "" : String(p.value)} title={p.value == null ? "" : String(p.value)}
                onBlur={(e) => { if (e.target.value !== String(p.value ?? "")) setProp(i, { value: e.target.value }); }} /></td>
              <td><div className="st-row-tight"><Select aria-label={"Match for " + name(p)} value={p.match || "equals"} onChange={(e) => setProp(i, { match: e.target.value })}
                options={MATCHES.map(([v, l]) => ({ value: v, label: l }))} />
                {p.custom && <Button kind="plain" aria-label="Remove this property" onClick={() => put({ ...obj, properties: props.filter((_, j) => j !== i) })}>Remove</Button>}</div></td>
            </tr>
          ))}
          {!shown.length && <tr><td colSpan={4} className="st-muted">{f ? "No property matches “" + filter + "”." : "No property is selected: show all properties and tick the ones to search by."}</td></tr>}
        </tbody>
      </table>
      <Button kind="plain" icon="plus" onClick={() => put({ ...obj, properties: [...props, { name: "", value: "", match: "equals", use: true, custom: true }] })}>Add property</Button>
    </div>
  );
  const options = (
    <div className="st-cap-opts">
      <Field label="Search by" layout="beside">{(id) => <Select id={id} aria-label="Search by" value={obj.search_by || "properties"} onChange={(e) => put({ ...obj, search_by: e.target.value })}
        options={(desktop ? [["properties", "Properties"]] : [["properties", "Ticked properties"], ["css", "CSS selector"], ["xpath", "XPath (DOM, then full)"]]).map(([v, l]) => ({ value: v, label: l }))} />}</Field>
      <Field label="Which match" layout="beside" info="When several match: which one the step uses.">{(id) => <div className="st-row-tight">
        <Select id={id} aria-label="Which match" value={index === 0 ? "first" : index === -1 ? "last" : "nth"}
          onChange={(e) => put({ ...obj, index: e.target.value === "first" ? 0 : e.target.value === "last" ? -1 : Math.max(1, index) })}
          options={[{ value: "first", label: "First" }, { value: "last", label: "Last" }, { value: "nth", label: "Number…" }]} />
        {index > 0 && <TextInput aria-label="Match number" type="number" min={1} defaultValue={String(index + 1)}
          onBlur={(e) => put({ ...obj, index: Math.max(0, (parseInt(e.target.value || "1", 10) || 1) - 1) })} />}</div>}</Field>
      <label className="st-check-line"><input type="checkbox" checked={!!obj.ignore_case} onChange={(e) => put({ ...obj, ignore_case: e.target.checked })} /> Ignore case</label>
      {!desktop && <label className="st-check-line" title="Try the captured CSS selector and XPath when the search finds nothing">
        <input type="checkbox" checked={obj.self_heal !== false} onChange={(e) => put({ ...obj, self_heal: e.target.checked })} /> Self-heal</label>}
    </div>
  );
  const anchorPane = (
    <div className="st-cap-anchor">
      <p className="st-muted">If the {desktop ? "control" : "element"} can't be found when the agent runs, or several match, a nearby anchor (such as its label or a heading) helps find the right one.</p>
      {!obj.anchor ? <Button kind="secondary" onClick={() => capture(true)} disabled={busy}>Capture anchor</Button> : <>
        <div className="st-row-tight"><span className="st-muted">{obj.anchor.offset ? `It is ${obj.anchor.offset.dx} px across and ${obj.anchor.offset.dy} px down from the anchor.` : "No position recorded: the nearest match to the anchor is used."}</span></div>
        <div className="st-row-tight"><Button kind="secondary" onClick={() => capture(true)} disabled={busy}>Recapture anchor</Button>
          <Button kind="secondary" icon="trash" onClick={() => { const { anchor: _a, ...rest } = obj; put(rest); }}>Remove anchor</Button></div>
        <CaptureEditor step={step} value={obj.anchor} nested parent={obj} onChange={(a) => put(a ? { ...obj, anchor: a } : (() => { const { anchor: _a, ...rest } = obj; return rest; })())} />
      </>}
    </div>
  );

  return (
    <div className={"st-cap" + (nested ? " is-nested" : "")}>
      {!nested && <div className="st-cap-head">
        <span className="st-badge-soft">{desktop ? "Desktop" : "Web"}</span>
        <span className="st-cap-where" title={desktop ? "" : obj.page?.url ?? ""}>{where}</span>
        <Button kind="secondary" onClick={() => capture()} disabled={busy}>Recapture</Button>
        {!desktop && <Button kind="secondary" onClick={test}>Test</Button>}
      </div>}
      {!nested && <div className="st-seg" role="tablist" aria-label="Element or anchor">
        {(["main", "anchor"] as const).map((k) => <button key={k} type="button" role="tab" aria-selected={tab === k} className="st-seg-btn" onClick={() => setTab(k)}>
          {k === "main" ? "Main" : "Anchor" + (obj.anchor ? " ✓" : "")}</button>)}
      </div>}
      {!nested && tab === "anchor" ? anchorPane : <>
        {obj.screenshot && <figure className="st-cap-shot"><figcaption>Preview</figcaption><img alt="Captured element" src={"data:image/png;base64," + obj.screenshot} /></figure>}
        {table}
        {options}
        {!desktop && Array.isArray(obj.frames) && obj.frames.length > 0 && <p className="st-muted">Inside iframe: <code>{obj.frames.join("  ›  ")}</code></p>}
        {!desktop && !nested && <PageDetails obj={obj} put={put} />}
        {desktop && <DesktopWindowDetails obj={obj} put={put} nested={!!nested} />}
      </>}
    </div>
  );
}

function PageDetails({ obj, put }: { obj: Captured; put: (o: Captured) => void }) {
  const pg = obj.page ?? {};
  const set = (patch: Partial<NonNullable<Captured["page"]>>) => put({ ...obj, page: { ...pg, ...patch } });
  return (
    <details className="st-cap-page">
      <summary>Page (used when the step switches to the captured page)</summary>
      <Field label="Address" layout="beside">{(id) => <div className="st-row-tight"><TextInput id={id} defaultValue={pg.url ?? ""} onBlur={(e) => set({ url: e.target.value })} />
        <Select aria-label="Address match" value={pg.url_match || "starts_with"} onChange={(e) => set({ url_match: e.target.value })} options={MATCHES.map(([v, l]) => ({ value: v, label: l }))} /></div>}</Field>
      <Field label="Title" layout="beside">{(id) => <div className="st-row-tight"><TextInput id={id} defaultValue={pg.title ?? ""} onBlur={(e) => set({ title: e.target.value })} />
        <Select aria-label="Title match" value={pg.title_match || ""} onChange={(e) => set({ title_match: e.target.value || undefined })}
          options={[{ value: "", label: "(ignore the title)" }, ...MATCHES.map(([v, l]) => ({ value: v, label: l }))]} /></div>}</Field>
    </details>
  );
}

/**
 * The window of a desktop object: a preset (the window with this title, the one in front, the desktop, the taskbar),
 * its title and how it matches, the program, a size to set first so positions match the capture, and one shared window
 * variable for every step in that window.
 */
function DesktopWindowDetails({ obj, put, nested }: { obj: Captured; put: (o: Captured) => void; nested: boolean }) {
  const vars = useVars();
  if (!vars) return null;
  if (typeof obj.window === "string") return (
    <details className="st-cap-page" open><summary>Window</summary>
      <p className="st-muted">The window in {obj.window}, shared with other steps. Change its title in Variables.</p>
      <Button kind="secondary" onClick={() => {
        const v = (vars.flow.variables ?? []).find((x) => "$" + x.name === obj.window);
        put({ ...obj, window: v?.default && typeof v.default === "object" ? structuredClone(v.default) as DesktopWindow : { title: "", title_match: "wildcard" } });
      }}>Use a window of this step only</Button>
    </details>
  );
  if (!obj.window) return null;
  const w = obj.window;
  const size = w.resize ?? w.size ?? { width: 0, height: 0 };
  const set = (patch: Partial<DesktopWindow>) => put({ ...obj, window: { ...w, ...patch } });
  const share = () => {
    // one Dictionary variable holds the window; every desktop step in the same window (title and program) uses it
    const same = (x: unknown) => !!x && typeof x === "object" && ((x as DesktopWindow).title ?? "") === (w.title ?? "") && ((x as DesktopWindow).process ?? "") === (w.process ?? "") && !(x as DesktopWindow).preset;
    const f = structuredClone(vars.flow);
    let name = (f.variables ?? []).find((v) => v.type === "Dictionary" && same(v.default) && /^window_/i.test(v.name))?.name;
    if (!name) {
      name = numberedName(f, "Window");
      const def = structuredClone(w); delete def.resize;
      f.variables = [...(f.variables ?? []), { name, type: "Dictionary", default: def, description: "The application window that the desktop steps use; change its title here once." }];
    }
    const walk = (list: FlowStep[]) => { for (const s of list) {
      const o = s.args?.object as Captured | undefined;
      if (o && typeof o === "object" && o.technology === "desktop" && same(o.window)) s.args = { ...s.args, object: { ...o, window: "$" + name } };
      for (const l of [s.children, s.else, s.finally, ...(s.elseIf ?? []).map((b) => b.children), ...(s.catch ?? []).map((c) => c.children)]) if (l) walk(l);
    } };
    walk(f.steps);
    vars.edit(f);
    vars.notice?.("The steps in this window now use $" + name + ".");
  };
  return (
    <details className="st-cap-page" open={!nested}>
      <summary>Window</summary>
      <Field label="Window" layout="beside">{(id) => <Select id={id} aria-label="Window preset" value={w.preset ?? ""} onChange={(e) => set({ preset: e.target.value || undefined })}
        options={WINDOW_PRESETS.map(([v, l]) => ({ value: v, label: l }))} />}</Field>
      {!w.preset && <>
        <Field label="Title" layout="beside">{(id) => <div className="st-row-tight"><TextInput id={id} defaultValue={w.title ?? ""} onBlur={(e) => set({ title: e.target.value })} />
          <Select aria-label="Window title match" value={w.title_match || "wildcard"} onChange={(e) => set({ title_match: e.target.value })} options={MATCHES.map(([v, l]) => ({ value: v, label: l }))} /></div>}</Field>
        <Field label="Process" layout="beside">{(id) => <TextInput id={id} defaultValue={w.process ?? ""} onBlur={(e) => set({ process: e.target.value })} />}</Field>
        {w.path && <p className="st-muted" title={w.path}>Program: {w.path}</p>}
        <label className="st-check-line" title="Set the window to this size before looking for the control, so positions match the capture">
          <input type="checkbox" aria-label="Resize window" checked={!!w.resize}
            onChange={(e) => set({ resize: e.target.checked ? { width: Number(size.width) || 1280, height: Number(size.height) || 800 } : undefined })} /> Resize window</label>
        {w.resize && <div className="st-row-tight">
          <TextInput aria-label="Window width" type="number" min={1} defaultValue={String(w.resize.width)} onBlur={(e) => { const v = parseInt(e.target.value, 10); if (v > 0) set({ resize: { ...w.resize!, width: v } }); }} />
          <TextInput aria-label="Window height" type="number" min={1} defaultValue={String(w.resize.height)} onBlur={(e) => { const v = parseInt(e.target.value, 10); if (v > 0) set({ resize: { ...w.resize!, height: v } }); }} />
        </div>}
        {!nested && <Button kind="secondary" onClick={share} title="Keep the window in one variable, so a changed title is set once">Use one window for all steps in it</Button>}
      </>}
    </details>
  );
}

const shortUrl = (u?: string) => (u ?? "").replace(/^https?:\/\//, "").slice(0, 60);

const BROWSER_NAMES: Record<string, string> = { chrome: "Google Chrome", edge: "Microsoft Edge", firefox: "Mozilla Firefox" };
/** The browser a desktop window belongs to, from its program (msedge, chrome, firefox), or "" for any other application. */
export const browserOf = (w: OpenWindow) => w.browser || (/^msedge$/i.test(w.process ?? "") ? "edge" : /^chrome$/i.test(w.process ?? "") ? "chrome" : /^firefox$/i.test(w.process ?? "") ? "firefox" : "");

/**
 * Where to capture or record, as AA's recorder asks before it starts. Browser tabs Studio can reach (through the
 * ONEOFFICEAUTOMATION Browser Connector, or in the browser agents keep open); for recording also every open application
 * window, where only that application's clicks and typing are recorded; or a new page at an address. A browser
 * window Studio cannot reach says how to connect it, and offers a new recording window or desktop recording instead.
 */
export function WhereDialog({ mode, tabs: given, url, onChoose, onClose, onRefresh, ownTitle = "One Agent Studio" }:
  { mode: "capture" | "record"; tabs: OpenWindow[]; url?: string; onChoose: (c: WhereChoice) => void; onClose: () => void;
    onRefresh?: () => Promise<OpenWindow[]>; ownTitle?: string }) {
  const [all, setAll] = useState(given);
  const [refreshing, setRefreshing] = useState(false);
  const [unreached, setUnreached] = useState<OpenWindow | null>(null);
  const reachable = (w: OpenWindow) => (w.source ?? "browser") === "browser" && (mode === "record" ? !!w.connector || !!w.endpoint : !!w.endpoint || !!w.connector) && !!(w.title || w.url);
  const tabs = all.filter(reachable);
  // application windows: not Studio itself; a Store app once (its frame, ApplicationFrameHost, and its own program share
  // the title), named by its own program; a browser window only when none of its tabs is listed above
  const apps: OpenWindow[] = [];
  for (const w of mode === "record" ? all : []) {
    if (w.source !== "application" || !w.title || w.title.startsWith(ownTitle)) continue;
    const twin = apps.findIndex((a) => a.title === w.title && (a.process === "ApplicationFrameHost" || w.process === "ApplicationFrameHost"));
    if (twin < 0) apps.push(w);
    else if (apps[twin].process === "ApplicationFrameHost") apps[twin] = w;
  }
  const tabTitles = new Set(tabs.map((t) => t.title));
  const pageTitle = (w: OpenWindow) => (w.title ?? "").replace(/ [-–—] (Google Chrome|Microsoft\u200b? Edge|Mozilla Firefox)$/, "").replace(/ - (Personal|Work)$/, "").replace(/ and \d+ more pages?$/, "");
  const browserWindows = apps.filter((w) => browserOf(w) && !tabTitles.has(pageTitle(w)));
  const programs = apps.filter((w) => !browserOf(w));
  const reachedBrowsers = new Set(tabs.map((t) => t.connector ? t.browser : "agents"));
  const [asking, setAsking] = useState(!tabs.length && !programs.length && mode === "capture");
  const [address, setAddress] = useState(url || "https://");
  const [browser, setBrowser] = useState<"edge" | "chrome">("edge");
  const groups = new Map<string, OpenWindow[]>();
  for (const t of tabs) {
    const b = t.endpoint && !t.connector ? (BROWSER_NAMES[t.browser ?? ""] ?? "Browser") + " (opened by agents)" : BROWSER_NAMES[t.browser ?? ""] ?? "Browser";
    groups.set(b, [...(groups.get(b) ?? []), t]);
  }
  const tabChoice = (t: OpenWindow): WhereChoice => t.connector || t.tab_id !== undefined ? { tab_id: t.tab_id, browser: t.browser || "edge" }
    : { endpoint: t.endpoint, tab_title: t.title, tab_url: t.url, browser: t.browser || "edge" };
  const go = () => { const u = address.trim(); if (u && u !== "https://") onChoose({ url: /^[a-z]+:/i.test(u) ? u : "https://" + u, browser }); };
  const refresh = async () => {
    if (!onRefresh) return;
    setRefreshing(true);
    try { setAll(await onRefresh()); setUnreached(null); } finally { setRefreshing(false); }
  };
  const ub = unreached ? browserOf(unreached) : "";
  return (
    <Dialog open onClose={onClose} title={mode === "capture" ? "Capture in which window?" : "Record in which window?"} width={640}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button>
        {onRefresh && <Button kind="plain" onClick={refresh} disabled={refreshing} title="Read the open windows again">{refreshing ? "Refreshing…" : "Refresh"}</Button>}
        <span className="st-bar-fill" />
        {asking ? <Button kind="primary" onClick={go} disabled={!address.trim() || address.trim() === "https://"}>{mode === "capture" ? "Open and capture" : "Open and record"}</Button>
          : <Button kind="secondary" onClick={() => setAsking(true)}>Open a new page…</Button>}</>}>
      <div className="st-where-list">
        {tabs.length > 0 && <>
          <p className="st-where-intro">{mode === "record" ? "Web pages: each click, typing and choice becomes a web step." : "Open browser tabs (a browser opened by an agent stays open):"}</p>
          {[...groups].map(([b, list]) => <div key={b} className="st-where-group" role="group" aria-label={b}><div className="st-where-head">{b} ({list.length})</div>
            {list.map((t, i) => <button key={i} type="button" className="st-where" onClick={() => onChoose(tabChoice(t))}>
              <strong>{t.title || "(no title)"}</strong><span>{shortUrl(t.url)}</span></button>)}</div>)}
        </>}
        {programs.length > 0 && <div className="st-where-group" role="group" aria-label="Applications">
          <div className="st-where-head">Applications ({programs.length})</div>
          <p className="st-where-intro">The window comes to the front; only clicks and typing in that application (with its dialogs) are recorded.</p>
          {programs.map((w, i) => <button key={i} type="button" className="st-where" onClick={() => onChoose({ target: "desktop", window_title: w.title, process: w.process })}>
            <strong>{w.title}</strong>{w.process && <span>{w.process}</span>}</button>)}</div>}
        {browserWindows.length > 0 && <div className="st-where-group" role="group" aria-label="Browser windows">
          <div className="st-where-head">Browser windows ({browserWindows.length})</div>
          {browserWindows.map((w, i) => <button key={i} type="button" className={`st-where${unreached === w ? " is-on" : ""}`} aria-pressed={unreached === w}
            onClick={() => setUnreached(w)}>
            <strong>{w.title}</strong><span>{BROWSER_NAMES[browserOf(w)]}{reachedBrowsers.has(browserOf(w)) ? " · its tabs are listed above" : " · not connected to Studio"}</span></button>)}
        </div>}
        {!tabs.length && !programs.length && !browserWindows.length && <p className="st-muted">{mode === "record"
          ? "No open window was found. Open the application or page first and press Refresh, or open a new page."
          : "No open browser window: open a new page."}</p>}
      </div>
      {unreached && <div className="st-where-help" role="status">
        <p><strong>{BROWSER_NAMES[ub]} is not connected to Studio</strong>, so its pages cannot be recorded as web steps.
          Add the ONEOFFICEAUTOMATION Browser Connector to {BROWSER_NAMES[ub]} and press Refresh: its tabs then appear above.</p>
        <div className="st-cr-acts">
          {ub !== "firefox" && <Button kind="primary" onClick={() => { setBrowser(ub === "chrome" ? "chrome" : "edge"); setAsking(true); setUnreached(null); }}>Record a page in a new {BROWSER_NAMES[ub]} window…</Button>}
          <Button kind="secondary" onClick={() => onChoose({ target: "desktop", window_title: unreached.title, process: unreached.process })}>Record this window as an application</Button>
        </div>
      </div>}
      {asking && <div className="st-where-new">
        <Field label="Web address">{(id) => <TextInput id={id} value={address} onChange={(e) => setAddress(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") go(); }} />}</Field>
        <Field label="Browser">{(id) => <Select id={id} value={browser} onChange={(e) => setBrowser(e.target.value as "edge" | "chrome")}
          options={[{ value: "edge", label: "Microsoft Edge" }, { value: "chrome", label: "Google Chrome" }]} />}</Field>
      </div>}
    </Dialog>
  );
}
