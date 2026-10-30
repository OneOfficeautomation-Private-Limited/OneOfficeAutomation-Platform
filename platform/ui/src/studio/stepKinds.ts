import type { FieldSpec, FlowStep, PackageManifest } from "../api/client";

/**
 * Settings that depend on what a step does: which fields an element action uses, the actions that suit a captured
 * control, the variable an action's result goes to, the kinds of loop, and the inputs a saved connection supplies.
 * The same rules Classic Studio used, so a step shows the same as before.
 */

/** Is a field shown for the step's other settings? { iterator: ["while"] } or, with "!" first, { iterator: ["!", "times"] }. */
export function shownWhen(rule: Record<string, unknown[]> | undefined, value: (name: string) => unknown): boolean {
  return Object.entries(rule ?? {}).every(([other, values]) => {
    const v = String(value(other) ?? "");
    return values[0] === "!" ? !values.slice(1).some((x) => String(x) === v) : values.some((x) => String(x) === v);
  });
}

/** The help of a field, with its limits ("Smallest value: 0."). */
export function helpText(spec: FieldSpec & { minimum?: number; maximum?: number }, required: boolean): string {
  const limits = spec.minimum !== undefined && spec.maximum !== undefined ? `From ${spec.minimum} to ${spec.maximum}.`
    : spec.minimum !== undefined ? `Smallest value: ${spec.minimum}.` : spec.maximum !== undefined ? `Largest value: ${spec.maximum}.` : "";
  return [required ? "Required." : "", spec.description ?? "", limits].filter(Boolean).join(" ");
}

// ---- element actions (web pages and desktop controls) ----

export const WEB_ACTION = "ooa.browser/element_action";
export const DESKTOP_ACTION = "ooa.desktop_ui/object_action";
export const isElementAction = (action: string) => action === WEB_ACTION || action === DESKTOP_ACTION;

export const WEB_NAMES: Record<string, string> = {
  click: "Click", double_click: "Double click", right_click: "Right click", middle_click: "Middle click", hover: "Move the mouse over",
  focus: "Set focus", set_text: "Set text", append_text: "Append text", clear: "Clear text", press_key: "Press a key",
  get_text: "Get text", get_value: "Get value", get_attribute: "Get attribute", get_property: "Get property", get_all_properties: "Get all properties",
  get_html: "Get HTML", get_inner_html: "Get inner HTML", get_coordinates: "Get position", get_children_text: "Get the text of all children", get_table: "Get table",
  select_by_text: "Select item by text", select_by_value: "Select item by value", select_by_index: "Select item by index (0 = first)",
  get_selected: "Get selected text", get_selected_index: "Get selected index (1 = first)", get_total_items: "Get total items", get_options: "Get all items",
  check: "Check", uncheck: "Uncheck", toggle: "Toggle", select: "Select", get_status: "Get status", is_checked: "Is checked",
  is_visible: "Is visible", is_enabled: "Is enabled", is_present: "Is present", count: "Count matches", scroll_into_view: "Scroll into view",
  highlight: "Highlight", wait_to_appear: "Wait for it to appear", wait_to_disappear: "Wait for it to disappear", drag_to: "Drag to",
  upload_file: "Upload file", screenshot: "Screenshot", get_total_rows: "Get total rows", get_total_columns: "Get total columns",
  get_cell_text: "Get cell text by position", get_cell_index_by_text: "Get cell position by text", set_cell_text: "Set cell text by position",
  click_cell: "Click cell by position", set_position: "Set position", get_control_type: "Get control type",
};
const WEB_COMMON = ["get_property", "get_all_properties", "click", "double_click", "right_click", "hover", "focus", "scroll_into_view", "highlight",
  "screenshot", "get_coordinates", "is_visible", "is_present", "wait_to_appear", "wait_to_disappear"];
const WEB_BY_CONTROL: Record<string, string[]> = {
  "Text box": ["set_text", "append_text", "clear", "press_key", "get_value", "get_text"],
  "Password box": ["set_text", "clear", "press_key"],
  "Check box": ["check", "uncheck", "toggle", "get_status", "is_checked"],
  "Radio button": ["select", "get_status", "is_checked"],
  "Drop-down list": ["select_by_text", "select_by_value", "select_by_index", "get_selected", "get_selected_index", "get_total_items", "get_options"],
  "List": ["select_by_text", "select_by_value", "select_by_index", "get_selected", "get_selected_index", "get_total_items", "get_options"],
  "Table": ["get_table", "get_cell_text", "get_cell_index_by_text", "set_cell_text", "click_cell", "get_total_rows", "get_total_columns"],
  "Button": ["get_text", "is_enabled"], "Link": ["get_text", "get_attribute"], "Label": ["get_text"], "Image": ["get_attribute"],
  "Calendar": ["set_text", "get_value"], "Slider": ["set_position", "get_value"], "File input": ["upload_file"],
  "Tab": ["get_text", "get_total_items"], "Menu": ["get_text", "get_total_items", "get_children_text"], "Tree": ["get_text", "get_children_text"],
  "Element": ["get_text", "get_html", "get_inner_html", "get_children_text", "set_text", "get_attribute"],
};

export const DESKTOP_NAMES: Record<string, string> = {
  click: "Click", set_text: "Set text", get_text: "Get text", get_property: "Get property", get_all_properties: "Get all properties",
  select_item: "Select item", expand: "Expand", collapse: "Collapse", toggle: "Toggle", check: "Check", uncheck: "Uncheck", invoke: "Press (invoke)",
  focus: "Set focus", get_children: "Get all children", get_list_items: "Get all items", get_table: "Get table", wait_appear: "Wait for it to appear",
  wait_disappear: "Wait for it to disappear", scroll_into_view: "Scroll into view", get_coordinates: "Get position",
};
const DESKTOP_COMMON = ["get_property", "get_all_properties", "get_text", "get_coordinates", "focus", "wait_appear", "wait_disappear", "scroll_into_view"];
const DESKTOP_BY_CONTROL: Record<string, string[]> = {
  Edit: ["set_text", "get_text", "click"], Document: ["set_text", "get_text", "click"], Button: ["click", "invoke"], SplitButton: ["click", "invoke", "expand", "collapse"],
  CheckBox: ["check", "uncheck", "toggle"], RadioButton: ["click", "invoke"], ComboBox: ["select_item", "get_list_items", "expand", "collapse"],
  List: ["select_item", "get_list_items", "get_children"], ListItem: ["click", "invoke"], Tree: ["select_item", "get_children", "expand", "collapse"],
  TreeItem: ["expand", "collapse", "click"], Tab: ["select_item", "get_list_items"], TabItem: ["click"], Table: ["get_table", "get_children"],
  DataGrid: ["get_table", "get_children"], DataItem: ["click", "get_children"], Menu: ["get_children"], MenuBar: ["get_children"],
  MenuItem: ["click", "invoke", "expand", "collapse"], Hyperlink: ["click", "invoke"], Text: ["get_text"], Window: ["get_children"], Pane: ["get_children"], Group: ["get_children"],
};

/** The variable an action's result goes to when it is chosen, and its type. */
const OUTCOME: Record<string, string> = {
  get_text: "element_text", get_value: "field_value", get_attribute: "attribute_value", get_property: "property_value",
  get_all_properties: "element_properties", get_html: "element_html", get_inner_html: "inner_html", get_coordinates: "element_position",
  get_children_text: "children_text", get_table: "table_data", get_selected: "selected_item", get_selected_index: "selected_index",
  get_total_items: "item_count", get_options: "items", get_status: "status", is_checked: "is_checked", is_visible: "is_visible", is_enabled: "is_enabled",
  is_present: "is_present", count: "match_count", get_total_rows: "row_count", get_total_columns: "column_count", get_cell_text: "cell_text",
  get_cell_index_by_text: "cell_position", get_control_type: "control_type", toggle: "is_checked", get_children: "children", get_list_items: "items",
};
export function outcomeOf(action: string): { name: string; type: string } | null {
  const name = OUTCOME[action];
  if (!name) return null;
  const type = action === "get_table" ? "Table" : action.startsWith("is_") || action === "toggle" ? "Boolean"
    : /^get_(total|selected_index)|^count$|_rows$|_columns$/.test(action) ? "Number" : /^get_(all_properties|coordinates)$/.test(action) ? "Dictionary"
    : /^get_(options|children|list_items|children_text)$/.test(action) ? "List" : "String";
  return { name, type };
}

/** The control type a captured object recorded (ControlType on the desktop, control_type on a page). */
export function controlType(step: FlowStep): string {
  const o = step.args?.object as { properties?: { name: string; value?: unknown }[] } | undefined;
  const want = step.action === DESKTOP_ACTION ? "ControlType" : "control_type";
  const p = Array.isArray(o?.properties) ? o!.properties.find((x) => x.name === want) : undefined;
  return p?.value ? String(p.value) : "";
}

/** The action list: actions for the captured control first ("For this check box"), the rest after. */
export function actionGroups(step: FlowStep, all: string[]): { label: string; actions: string[] }[] {
  const desktop = step.action === DESKTOP_ACTION;
  const ct = controlType(step);
  const mine = ct ? [...((desktop ? DESKTOP_BY_CONTROL : WEB_BY_CONTROL)[ct] ?? []), ...(desktop ? DESKTOP_COMMON : WEB_COMMON)]
    .filter((a, i, l) => all.includes(a) && l.indexOf(a) === i) : [];
  const other = all.filter((a) => !mine.includes(a));
  return mine.length ? [{ label: "For this " + ct.toLowerCase(), actions: mine }, { label: "Other actions", actions: other }] : [{ label: "", actions: other }];
}

/** The inputs an element action shows for its chosen action; null for other steps (they show every input). */
export function elementFields(step: FlowStep): Set<string> | null {
  const a = String(step.args?.action ?? "click");
  if (step.action === DESKTOP_ACTION) {
    const f = new Set(["object", "action", "timeout_seconds", "if_not_found", "run_in_background"]);
    // in the background only UI Automation is used, so the mouse and typing choices do not apply
    const bg = step.args?.run_in_background === true;
    if (a === "click") (bg ? ["click_type"] : ["click_type", "click_method", "offset_x", "offset_y"]).forEach((x) => f.add(x));
    if (a === "set_text") (bg ? ["text", "clear_first"] : ["text", "clear_first", "text_method", "type_delay_ms"]).forEach((x) => f.add(x));
    if (a === "get_property") f.add("property");
    if (a === "select_item") { f.add("item"); f.add("item_index"); }
    return f;
  }
  if (step.action !== WEB_ACTION) return null;
  const f = new Set(["window", "object", "selector", "action", "timeout_ms", "wait_for_page_ms", "if_not_found", "wait_after_ms", "use_captured_page", "session", "alternatives", "run_in_background"]);
  if (/^(set_text|append_text|press_key|get_attribute|get_property|select_by_|drag_to|upload_file|screenshot|get_cell_index_by_text|set_cell_text|set_position)/.test(a)) f.add("value");
  if (/^(click|double_click|right_click|middle_click|hover)$/.test(a)) ["click_method", "modifiers", "offset_x", "offset_y", "force"].forEach((x) => f.add(x));
  if (/^(set_text|append_text)$/.test(a)) ["typing", "key_delay_ms", "clear_first"].forEach((x) => f.add(x));
  if (/^(get_cell_text|set_cell_text|click_cell)$/.test(a)) { f.add("row"); f.add("column"); }
  if (/^(check|uncheck|select_by_|clear)/.test(a)) f.add("force");
  return f;
}

/** The value field's label follows the action: "Text to enter" for Set text, "Item text" for Select by text ... */
export function valueLabel(step: FlowStep): string | null {
  if (step.action !== WEB_ACTION) return null;
  return ({ set_text: "Text to enter", append_text: "Text to add", press_key: "Key (e.g. Enter, Ctrl+A)", get_attribute: "Attribute name",
    get_property: "Property name", select_by_text: "Item text", select_by_value: "Item value", select_by_index: "Item index (0 = first)",
    drag_to: "Drop on (selector)", upload_file: "File path(s)", screenshot: "Image file", get_cell_index_by_text: "Cell text",
    set_cell_text: "Text to enter", set_position: "Position value" } as Record<string, string>)[String(step.args?.action ?? "click")] ?? null;
}

/** The inputs of a "wait" box: the main wait first, the rest under Advanced. */
export const TIMEOUT_FIELDS = ["timeout_ms", "timeout_seconds", "wait_for_page_ms", "if_not_found"];
export const TIMEOUT_LABELS: Record<string, string> = { timeout_ms: "Wait for the element (milliseconds)", timeout_seconds: "Wait for the control (seconds)",
  wait_for_page_ms: "Wait for the page first (milliseconds)", if_not_found: "When the wait runs out" };

// ---- loops ----

export type LoopMode = "times" | "each" | "while";
export const loopMode = (step: FlowStep): LoopMode => { const it = String(step.args?.iterator ?? "list"); return it === "times" ? "times" : it === "while" ? "while" : "each"; };
/** What For each goes through, in plain words; the agent keeps the iterator's id. */
export const EACH_KINDS: { value: string; label: string; over: string; as: string }[] = [
  { value: "table_rows", label: "Each row of a table", over: "Table to go through", as: "Store each row in" },
  { value: "list", label: "Each item of a list", over: "List to go through", as: "Store each item in" },
  { value: "dictionary", label: "Each key and value of a dictionary", over: "Dictionary to go through", as: "Store each key and value in" },
  { value: "dictionary_keys", label: "Each key of a dictionary", over: "Dictionary to go through", as: "Store each key in" },
  { value: "dictionary_values", label: "Each value of a dictionary or record", over: "Dictionary or record to go through", as: "Store each value in" },
];
export const LOOP_LABELS: Record<string, string> = { count: "Number of times", start: "Start counting at", step: "Count in steps of", as: "Store each item in",
  index: "Position (0 = first) in", condition: "Repeat while", check_at_end: "Check at the end (run at least once)", from_index: "Start at position",
  to_index: "Stop after position", reverse: "Go through backwards", max_iterations: "Stop after this many passes", label: "Name (for Exit loop and Next pass)",
  columns_to: "Put columns in variables", over: "Go through", iterator_args: "Iterator settings" };
const LOOP_MORE = new Set(["max_iterations", "label", "columns_to", "step", "reverse", "check_at_end", "iterator_args"]);
export const loopIsMore = (name: string) => LOOP_MORE.has(name);

// ---- saved connections ----

/**
 * The inputs a connector repeats on (nearly) every action, such as its address and sign-in: what a saved connection
 * supplies. Toolkits whose shared inputs are a file or a request (Excel, PDF, REST, Generative AI) have none.
 */
export function connectionInputs(pkg: PackageManifest | undefined): Set<string> {
  if (!pkg || pkg.actions.length < 3 || ["ooa.excel_desktop", "ooa.pdf", "ooa.rest", "ooa.genai"].includes(pkg.id)) return new Set();
  const count = new Map<string, number>(), types = new Map<string, string | undefined>();
  for (const a of pkg.actions) for (const [k, v] of Object.entries(a.inputs?.properties ?? {})) { count.set(k, (count.get(k) ?? 0) + 1); types.set(k, v["x-ooa-type"]); }
  const common = [...count].filter(([, n]) => n >= 0.8 * pkg.actions.length).map(([k]) => k);
  return common.some((k) => types.get(k) === "Credential") ? new Set(common) : new Set();
}
