import type { Flow, FlowStep, FlowVariable } from "../api/client";
import { innerLists } from "./flowEdit";
import { renameRefs } from "./variables";

/**
 * Captured objects and recordings, as pure functions: how a property must match, readable property names, the
 * numbered window and value variables a capture or a recording makes (Window_1, Text_1, Choice_1), and the anchor's
 * offset. The same rules Classic Studio used, so agents made there and here are alike.
 */

export type CapturedProperty = { name: string; value?: unknown; match?: string; use?: boolean; custom?: boolean };
export type Captured = {
  technology?: "web" | "desktop"; search_by?: string; index?: number; ignore_case?: boolean; self_heal?: boolean;
  properties?: CapturedProperty[]; page?: { url?: string; title?: string; url_match?: string; title_match?: string; browser?: string };
  window?: DesktopWindow | string; anchor?: Captured & { offset?: { dx: number; dy: number } }; frames?: string[];
  rect?: { left: number; top: number }; screenshot?: string; window_shot?: WindowShot; [k: string]: unknown;
};
/** The whole window when captured (a JPEG) and where the element sits in it, as fractions of its size. */
export type WindowShot = { image: string; left: number; top: number; width: number; height: number };
export type DesktopWindow = { title?: string; title_match?: string; process?: string; path?: string; preset?: string;
  size?: { width: number; height: number }; resize?: { width: number; height: number } };

export const MATCHES: [string, string][] = [["equals", "equals"], ["not_equals", "not equals"], ["contains", "contains"], ["starts_with", "starts with"],
  ["ends_with", "ends with"], ["wildcard", "wildcard (* ?)"], ["regex", "regular expression"]];

/** Readable names for captured web properties; the stored name shows on hover. Others (data-*, attributes) show as they are. */
export const WEB_LABELS: Record<string, string> = {
  tag: "HTML tag", control_type: "Control type", id: "HTML ID", name: "HTML name", type: "HTML type", class: "HTML class", text: "Inner text", value: "Value", label: "Field label",
  placeholder: "Placeholder", title: "Tooltip (title)", "aria-label": "Accessible name", role: "Role", href: "Link (href)", src: "Source (src)", alt: "Alt text",
  for: "Label for", "data-testid": "Test ID", dom_xpath: "DOM XPath", relative_xpath: "Relative XPath", xpath: "Full XPath", css: "CSS selector", path: "Element path",
  parent: "Parent", tag_index: "Tag index", source_index: "Source index", child_count: "Children", left: "Left", top: "Top", width: "Width", height: "Height",
  visible: "Is visible", enabled: "Is enabled", checked: "Is checked", selected: "Selected item", in_frame: "Inside a frame", frame_url: "Frame address",
  inner_html: "Inner HTML", outer_html: "Outer HTML",
};
export const DESKTOP_LABELS: Record<string, string> = {
  Name: "Name", AutomationId: "Automation ID", ClassName: "Class name", ControlType: "Control type", LocalizedControlType: "Role", FrameworkId: "Framework",
  Text: "Text", Value: "Value", HelpText: "Help text", AccessKey: "Access key", AcceleratorKey: "Shortcut key", ItemType: "Item type", ItemStatus: "Item status",
  Path: "Path", XPath: "XPath", Index: "Index", Parent: "Parent", Left: "Left", Top: "Top", Width: "Width", Height: "Height", States: "States",
  IsEnabled: "Is enabled", IsOffscreen: "Is off screen", IsKeyboardFocusable: "Can take focus", HasKeyboardFocus: "Has focus", IsPassword: "Is password",
  IsReadOnly: "Is read-only", ToggleState: "Checked state", IsSelected: "Is selected", ExpandCollapseState: "Expanded state", RangeValue: "Range value",
  IsContentElement: "Is content", IsControlElement: "Is control", Orientation: "Orientation", SupportedPatterns: "Supported patterns", ProcessName: "Process",
  BoundingRectangle: "Screen rectangle",
};

/** Desktop window presets: the window with the title, the one in front, the desktop, the taskbar. */
export const WINDOW_PRESETS: [string, string][] = [["", "The window with this title"], ["active", "The window in front when the step runs"], ["desktop", "The desktop"], ["taskbar", "The taskbar"]];

/** The next free numbered name: Window_1, Text_2, Choice_1 (short and stable, as the reference product names them). */
export function numberedName(flow: Flow, base: string, taken: string[] = []): string {
  const used = new Set([...(flow.variables ?? []).map((v) => v.name.toLowerCase()), ...taken.map((t) => t.toLowerCase())]);
  let k = 1;
  while (used.has((base + "_" + k).toLowerCase())) k++;
  return base + "_" + k;
}

export type WebWindow = { source?: string; title?: string; title_match?: string; link?: string; browser?: string; [k: string]: unknown };

/** The window variable for a window (same source, title and browser: the same variable), made the first time a step needs it. */
export function windowVariable(flow: Flow, w: WebWindow): { flow: Flow; name: string; made: boolean } {
  const same = (flow.variables ?? []).find((v) => v.type === "Dictionary" && v.default && typeof v.default === "object" && /^window_\d+$/i.test(v.name)
    && (v.default as WebWindow).source === (w.source || "browser") && (v.default as WebWindow).title === w.title && ((v.default as WebWindow).browser || "") === (w.browser || ""));
  if (same) return { flow, name: same.name, made: false };
  const name = numberedName(flow, "Window");
  const v: FlowVariable = { name, type: "Dictionary", direction: "local", default: w,
    description: "Window " + (w.source === "application" ? "" : "(browser title) ") + "“" + w.title + "”, made by Capture." };
  return { flow: { ...flow, variables: [...(flow.variables ?? []), v] }, name, made: true };
}

/** The same desktop window: title and program alike, and not a preset (the window in front, the desktop, the taskbar). */
const sameDesktopWindow = (a: unknown, w: DesktopWindow) => !!a && typeof a === "object" && !(a as DesktopWindow).preset
  && ((a as DesktopWindow).title ?? "") === (w.title ?? "") && ((a as DesktopWindow).process ?? "") === (w.process ?? "");

/** The window variable for an application window (same title and program: the same variable), made the first time a step needs it. */
export function desktopWindowVariable(flow: Flow, w: DesktopWindow, description?: string): { flow: Flow; name: string; made: boolean } {
  const same = (flow.variables ?? []).find((v) => v.type === "Dictionary" && /^window_/i.test(v.name) && sameDesktopWindow(v.default, w));
  if (same) return { flow, name: same.name, made: false };
  const name = numberedName(flow, "Window");
  const def = structuredClone(w); delete def.resize;
  const v: FlowVariable = { name, type: "Dictionary", direction: "local", default: def,
    description: description ?? "Application window “" + (w.title ?? "") + "”" + (w.process ? " (" + w.process + ")" : "") + ", made by Capture; change its title here once for every step in it." };
  return { flow: { ...flow, variables: [...(flow.variables ?? []), v] }, name, made: true };
}

/**
 * After a desktop capture: the object's window goes into a window variable, as Capture does in AA, so steps in the same
 * window share it. A preset window (the one in front, the desktop, the taskbar) or one without a title stays in the step.
 */
export function shareDesktopWindow(flow: Flow, obj: Captured): { flow: Flow; obj: Captured; name?: string } {
  const w = obj.window;
  if (!w || typeof w !== "object" || w.preset || !w.title) return { flow, obj };
  const r = desktopWindowVariable(flow, w);
  return { flow: r.flow, obj: { ...obj, window: "$" + r.name }, name: r.name };
}

/** After a web capture: the step's window becomes the captured page (title, link, browser) in a window variable, when none is set. */
export function fillWindowFromCapture(flow: Flow, step: FlowStep, obj: Captured, hasWindowInput: boolean): Flow {
  if (!obj.page || !hasWindowInput) return flow;
  const cur = step.args?.window;
  if (typeof cur === "string" && cur.trim()) return flow;
  if (cur && typeof cur === "object" && ((cur as WebWindow).title || (cur as { url?: string }).url || (cur as { variable?: string }).variable)) return flow;
  const w: WebWindow = { source: "browser", title: obj.page.title || "", title_match: "equals", link: obj.page.url || "", browser: obj.page.browser || "edge" };
  if (!w.title) { delete w.title; step.args = { ...(step.args ?? {}), window: w }; return flow; }
  const r = windowVariable(flow, w);
  step.args = { ...(step.args ?? {}), window: "$" + r.name };
  return r.flow;
}

/** The action a captured element suggests: type into fields, pick from lists, tick check boxes, otherwise click. */
export function actionFor(el: { tag?: string; type?: string } | undefined): string {
  const tag = el?.tag ?? "";
  if (/^(input|textarea)$/.test(tag) && !/^(checkbox|radio|submit|button)$/.test(el?.type ?? "")) return "set_text";
  return tag === "select" ? "select_by_text" : el?.type === "checkbox" ? "check" : "click";
}
export function desktopActionFor(object: Captured): string {
  const type = String((object.properties ?? []).find((p) => p.name === "ControlType")?.value ?? "");
  return /^(Edit|Document)$/.test(type) ? "set_text" : /^(ComboBox|List)$/.test(type) ? "select_item" : type === "CheckBox" ? "check" : "click";
}

/** Where the element sits from its anchor (page pixels, or the screen rectangles a desktop capture records). */
export function withAnchorOffset(obj: Captured): Captured {
  const a = obj.anchor;
  if (!a) return obj;
  if (obj.rect && a.rect) return { ...obj, anchor: { ...a, offset: { dx: Math.round(obj.rect.left - a.rect.left), dy: Math.round(obj.rect.top - a.rect.top) } } };
  const at = (o: Captured) => { const v = String((o.properties ?? []).find((x) => x.name === "BoundingRectangle")?.value ?? "").split(",").map(Number); return v.length >= 2 && !v.some(isNaN) ? v : null; };
  const m = at(obj), n = at(a);
  return m && n ? { ...obj, anchor: { ...a, offset: { dx: m[0] - n[0], dy: m[1] - n[1] } } } : obj;
}

/**
 * Variables for recorded steps: one window variable per window the recording used (the steps point at it), and each
 * typed value an input variable Text_1, Text_2 ... (each selected option Choice_1 ...) holding the recorded value as
 * its default, its description naming the field. A recording that typed $password gets a Credential input for it.
 */
export function recordedVariables(flow: Flow, steps: FlowStep[]): { flow: Flow; made: string[] } {
  let f = flow;
  const made: string[] = [];
  const fieldName = (st: FlowStep) => {
    const props = ((st.args?.object as Captured | undefined)?.properties) ?? [];
    const val = (n: string) => String(props.find((x) => x.name === n)?.value ?? "");
    return val("label") || val("name") || val("aria-label") || val("placeholder") || val("id") || val("Name") || val("AutomationId")
      || (st.action.endsWith("select_option") ? "choice" : "text");
  };
  for (const st of steps) {
    // a desktop step: its object's window (one variable per application window)
    const o = st.args?.object as Captured | undefined;
    if (o && typeof o === "object" && o.technology === "desktop" && o.window && typeof o.window === "object" && !o.window.preset && o.window.title) {
      const dw = o.window;
      const r = desktopWindowVariable(f, dw, "Application window the recording worked in (“" + dw.title + "”" + (dw.process ? ", " + dw.process : "") + ").");
      f = r.flow;
      if (r.made) made.push(r.name);
      st.args = { ...st.args, object: { ...o, window: "$" + r.name } };
      continue;
    }
    const w = st.args?.window as WebWindow | undefined;
    if (!w || typeof w !== "object" || !w.title) continue;
    const r = windowVariable(f, w);
    if (r.made) {
      f = { ...r.flow, variables: r.flow.variables!.map((v) => v.name === r.name ? { ...v, description: "Window the recording worked in (browser title “" + w.title + "”)." } : v) };
      made.push(r.name);
    }
    st.args = { ...st.args, window: "$" + r.name };
  }
  for (const st of steps) {
    const key = st.action === "ooa.browser/type_text" || (st.action === "ooa.desktop_ui/object_action" && st.args?.action === "set_text") ? "text"
      : st.action === "ooa.browser/select_option" ? "option" : null;
    const value = key ? st.args?.[key] : undefined;
    if (!key || typeof value !== "string" || value.startsWith("$")) continue;
    const name = numberedName(f, key === "text" ? "Text" : "Choice");
    f = { ...f, variables: [...(f.variables ?? []), { name, type: "String", direction: "input", default: value,
      description: (key === "text" ? "Typed into" : "Selected in") + " the field “" + fieldName(st) + "” when recorded (change it here or pass it in)." }] };
    st.args = { ...st.args, [key]: "$" + name };
    made.push(name);
  }
  if (JSON.stringify(steps).includes('"$password"') && !(f.variables ?? []).some((v) => v.name === "password")) {
    f = { ...f, variables: [...(f.variables ?? []), { name: "password", type: "Credential", direction: "input", description: "Password used by the recorded steps (from the vault)." }] };
    made.push("password");
  }
  return { flow: f, made };
}

/** Recorded variables that older recordings named after a page title or field label ($window_contact_us_acme, $tell_us_more). */
export function longRecordedVariables(flow: Flow): FlowVariable[] {
  return (flow.variables ?? []).filter((v) => {
    if (/^(Window|Text|Choice)_\d+$/.test(v.name)) return false;
    const d = String(v.description ?? "");
    return (v.type === "Dictionary" && /^window_/i.test(v.name) && /^Window\b/.test(d)) || /^Recorded (typing|selection)/.test(d);
  });
}

/** Renames those to numbered names (Window_1, Text_1, Choice_1), steps included. */
export function shortenRecordedNames(flow: Flow): Flow {
  let f = flow;
  for (const v of longRecordedVariables(flow)) {
    const base = v.type === "Dictionary" ? "Window" : /selection/.test(v.description ?? "") ? "Choice" : "Text";
    const name = numberedName({ ...f, variables: (f.variables ?? []).filter((x) => x.name !== v.name) }, base);
    f = renameRefs(f, v.name, name);
    f = { ...f, variables: (f.variables ?? []).map((x) => x.name !== v.name ? x : { ...x, name,
      description: base === "Window" ? x.description : (base === "Choice" ? "Selected in" : "Typed into") + " the field “" + v.name.replace(/_+/g, " ").trim() + "” when recorded (change it here or pass it in)." }) };
  }
  return f;
}

/** The first step's id after the agent's steps, for steps a recording or capture adds (s7, s8 ...). */
export function freshIds(flow: Flow, steps: FlowStep[]): void {
  let n = 0;
  const walk = (list: FlowStep[]) => { for (const s of list) { const m = /^s(\d+)$/.exec(s.id ?? ""); if (m) n = Math.max(n, +m[1]); innerLists(s).forEach(walk); } };
  walk(flow.steps);
  const give = (list: FlowStep[]) => { for (const s of list) { s.id = "s" + ++n; innerLists(s).forEach(give); } };
  give(steps);
}
