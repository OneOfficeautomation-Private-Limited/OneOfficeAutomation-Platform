/**
 * Typed client for the Studio API. The screens call the server only through here. The base is the folder the API
 * lives in: locally Studio's own /api/ (or /studio/api/ under `ooa server`); later the Command Center (V2).
 */

export type ApiError = { code: string; message: string; hint?: string; suggestedFix?: string | null };

export class ApiFailure extends Error {
  constructor(readonly status: number, readonly error: ApiError) { super(error.message); }
}

export type WorkspaceNode = {
  name: string; path: string; type: "folder" | "bot" | "form" | "process" | "document" | "skill" | "script" | "file";
  kind?: "TaskBot" | "ApiTask"; modified?: string; children?: WorkspaceNode[];
};

/** One input or output of an action, as the toolkit catalogue describes it (JSON Schema plus OOA's own keys). */
export type FieldSpec = {
  type?: string; description?: string; default?: unknown; enum?: unknown[]; examples?: unknown[]; minimum?: number; maximum?: number;
  "x-ooa-type"?: string; "x-ooa-label"?: string;
  /** Shown only when another input has one of these values, e.g. { iterator: ["while"] }. */
  "x-ooa-visible-when"?: Record<string, unknown[]>;
};
export type ActionManifest = {
  id: string; name: string; category?: string; description?: string;
  inputs?: { properties?: Record<string, FieldSpec>; required?: string[] };
  outputs?: { properties?: Record<string, FieldSpec> };
  "x-ooa-panel"?: { main?: string[]; more?: string[] };
};
export type PackageManifest = { id: string; name: string; version: string; description?: string; actions: ActionManifest[]; conditions?: ActionManifest[] };

/** A variable: in_out is both an input and an output; local (or none) is a working value inside the agent. */
export type FlowVariable = { name: string; type: string; direction?: "input" | "output" | "in_out" | "local"; default?: unknown; description?: string;
  required?: boolean; enum?: unknown[]; kpi?: boolean };
export type SavedConnection = { name: string; package: string; description?: string };
/** An open window: a browser tab (with the extension's tab id, or a debugging endpoint) or an application window. */
export type OpenWindow = { title?: string; url?: string; browser?: string; source?: "browser" | "application"; process?: string;
  tab_id?: number; connector?: boolean; endpoint?: string };
/** Values every agent can read: $sys.* from the platform, $global.* shared by all agents. */
export type Builtins = { sys: Record<string, unknown>; global: Record<string, unknown> };
export type FlowStep = {
  id: string; action: string; args?: Record<string, unknown>; out?: Record<string, string>; disabled?: boolean; note?: string;
  /** A saved connection that supplies the connector's address and sign-in. */
  connection?: string;
  children?: FlowStep[]; elseIf?: { condition?: unknown; children?: FlowStep[] }[]; else?: FlowStep[]; catch?: { codes?: string[]; as?: string; children?: FlowStep[] }[]; finally?: FlowStep[];
};
/** How the agent is shown, kept apart from what it does: folded blocks (as Classic Studio keeps them). */
export type FlowUi = { collapsed?: string[]; breakpoints?: string[]; [key: string]: unknown };
export type Flow = { kind: string; id: string; name: string; description?: string; tags?: string[]; variables?: FlowVariable[]; steps: FlowStep[]; ui?: FlowUi };

/** An agent to start from (Create > Start from): its variables and steps are copied into the new agent. */
export type Blueprint = { id: string; name: string; description: string; variables: FlowVariable[]; steps: FlowStep[] };

/** Something the check on save found: the step, the field, what is wrong and how to fix it. */
export type Problem = { stepId?: string; path?: string; message: string; suggestedFix?: string };

/** One thing a run reported: a step started, finished or failed, a log line, a variable set, the run ended. */
export type RunEvent = { seq: number; type: string; stepId?: string | null; ts?: string; data?: Record<string, unknown> };
export type RunError = { code: string; message: string; suggestedFix?: string | null; stepId?: string };
export type RunResult = { status: string; outputs?: Record<string, unknown>; error?: RunError | null; durationMs?: number; stepsExecuted?: number };
/** Where a debugged run is stopped, and the values it holds there. */
export type DebugState = { stepId: string; action: string; reason?: string; variables?: Record<string, unknown> };
export type RunState = { run_id: string; state: string; debug?: DebugState; elapsed_ms: number; events: RunEvent[]; result?: RunResult };
/**
 * How a run starts: debugging (with breakpoints), from a step (start_at) or only one step (only_step); the skipped
 * steps' variables take the values the last run here had at that step, unless carry_over is false (their defaults).
 */
export type RunOptions = { debug?: boolean; breakpoints?: string[]; stopAtStart?: boolean; start_at?: string; only_step?: string; carry_over?: boolean };
export type DebugCommand = "continue" | "stepOver" | "stepInto" | "stepOut";

/** Starts an agent on its own: a file arriving in a folder, a schedule, or a call to a webhook address. */
export type Trigger = {
  id: string; name?: string; bot: string; type: "file" | "schedule" | "webhook"; enabled?: boolean;
  folder?: string; pattern?: string; every_minutes?: number; daily_at?: string; secret?: string;
};

/** An agent of the workspace, by the id other agents and AI agents call it with. */
/** An agent of the workspace, with its variables and the agent ids its Run agent steps run. */
export type AgentSummary = { path: string; id: string; name: string; variables: FlowVariable[]; runs?: string[] };
/** What an agent needs and who uses it: one entry per item, with the step that uses it. */
export type Dependency = { path?: string; name?: string; step?: string; missing?: boolean };
export type Dependencies = { bots: Dependency[]; scripts: Dependency[]; skills: Dependency[]; files: Dependency[]; usedBy: Dependency[] };
/** Steps the assistant proposes from a description, with notes and the problems the check found. */
export type Proposal = { steps?: FlowStep[]; variables?: FlowVariable[]; notes?: string; problems?: Problem[] };
/**
 * A model connection: a provider, model and address kept in one place, used by name. The key is never in the file:
 * an environment variable name, or a vault reference {"$credential": "folder/credential/attribute"}.
 */
export type ModelConnection = { name: string; provider: string; model?: string; base_url?: string; api_key_env?: string; api_key?: { $credential: string }; description?: string };
export type ModelConnections = { default: string; connections: ModelConnection[] };

/** The Command Center link: signed in or not, and when Studio and Command Center run together, the browser's own sign-in. */
export type CrStatus = { connected: boolean; url?: string | null; user?: { username: string } | null; sameServer?: boolean };
/** An agent in the Command Center agent repository (the public one): its latest version, and who has it checked out. */
export type RepoAgent = { path: string; latest_version: number; production_version?: number | null; locked_by?: string | null };
export type RepoVersion = { version: number; author: string; created: string; comment?: string };
/** A check-in: the new version, and whether it became live or waits for approval first. */
export type CheckIn = { path: string; version: number; local: string; production: boolean; production_request?: unknown };

export type StudioInfo = { product: string; edition: string; workspace: string; controlRoom: string | null };

/** The kinds of item that "Create" makes, as the Studio API names them. */
/** AiAgent is an agent file made from the AI agent template (one Run AI agent step); see studio/aiAgent.ts. */
export type NewItemKind = "TaskBot" | "AiAgent" | "ApiTask" | "form" | "process" | "document" | "skill" | "script-python" | "script-powershell";

export function defaultBase(href = location.href): string {
  // the new Studio is served at <base>/next/, its API at <base>/api/
  return new URL("../api/", href).toString();
}

export class StudioApi {
  constructor(private readonly base = defaultBase(), private readonly fetcher: typeof fetch = (...a) => fetch(...a)) {}

  private async call<T>(method: string, route: string, body?: unknown): Promise<T> {
    const res = await this.fetcher(this.base + route, {
      method, credentials: "same-origin",
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) throw new ApiFailure(res.status, data.error ?? { code: "HTTP." + res.status, message: res.statusText || "Request failed" });
    return data as T;
  }

  info() { return this.call<StudioInfo>("GET", "info"); }
  async tree() { return (await this.call<{ tree: WorkspaceNode }>("GET", "tree")).tree; }
  async packages() { return (await this.call<{ packages: PackageManifest[] }>("GET", "packages")).packages; }
  async bot(path: string) { return (await this.call<{ flow: Flow }>("GET", "bot?path=" + encodeURIComponent(path))).flow; }
  /** Saves an agent; the server checks it and returns what it found (an empty list when nothing is wrong). */
  async saveBot(path: string, flow: Flow) { return this.call<{ saved: string; problems: Problem[] }>("PUT", "bot?path=" + encodeURIComponent(path), flow); }
  /** Runs the flow as it is in the editor (saved or not), on this computer. */
  async startRun(flow: Flow, inputs: Record<string, unknown>, options: RunOptions = {}) {
    return (await this.call<{ run_id: string }>("POST", "runs", { flow, inputs, ...options })).run_id;
  }
  runState(id: string, after: number) { return this.call<RunState>("GET", "runs/" + id + "?after=" + after); }
  runControl(id: string, what: "stop" | "pause" | "resume") { return this.call<{ ok: boolean }>("POST", "runs/" + id + "/" + what); }
  debug(id: string, command: DebugCommand) { return this.call<{ ok: boolean }>("POST", "runs/" + id + "/debug", { command }); }
  setBreakpoints(id: string, ids: string[]) { return this.call<{ ok: boolean }>("POST", "runs/" + id + "/breakpoints", { ids }); }
  async triggers() { return (await this.call<{ triggers: Trigger[] }>("GET", "triggers")).triggers; }
  /** Replaces every trigger of the workspace (the server checks each one: folder, schedule, secret). */
  async saveTriggers(list: Trigger[]) { return (await this.call<{ triggers: Trigger[] }>("PUT", "triggers", list)).triggers; }
  runTrigger(id: string) { return this.call<{ result: RunResult }>("POST", "triggers/" + id + "/run"); }

  /** A form, workflow, document type, Prompt Skill or script, as its text (JSON items are checked on save). */
  item(path: string) { return this.call<{ path: string; type: WorkspaceNode["type"]; text: string }>("GET", "item?path=" + encodeURIComponent(path)); }
  saveItem(path: string, text: string) { return this.call<{ saved: string }>("PUT", "item?path=" + encodeURIComponent(path), { text }); }

  async builtins() { const b = await this.call<Partial<Builtins>>("GET", "builtins"); return { sys: b.sys ?? {}, global: b.global ?? {} }; }
  /** The workspace's shared clipboard: steps copied here can be pasted in any agent, in any Studio window. */
  sharedClipboard() { return this.call<{ steps: FlowStep[]; variables?: FlowVariable[] }>("GET", "clipboard"); }
  async putSharedClipboard(steps: FlowStep[], variables: FlowVariable[]) { await this.call("PUT", "clipboard", { steps, variables }); }
  /** Saved connections of the workspace (the address and vault references a connector uses). */
  async connections() { return (await this.call<{ connections?: SavedConnection[] }>("GET", "connections")).connections ?? []; }
  /** Windows open on this computer now, for the window editor. */
  async windows() { return (await this.call<{ windows?: OpenWindow[] }>("GET", "windows")).windows ?? []; }
  /** Capture: the person clicks an element in a browser (a new page at url, or an open tab); gives its object and selector. */
  capture(req: { url?: string; browser?: string; tab_id?: number; endpoint?: string; tab_title?: string; tab_url?: string }) {
    return this.call<{ selector?: string; element?: { tag?: string; type?: string; text?: string }; object?: Record<string, unknown> }>("POST", "capture", req);
  }
  /** Opens the page and outlines every element the captured object matches; gives how many match. */
  captureTest(url: string, object: unknown) { return this.call<{ count?: number }>("POST", "capture/test", { url, object, browser: "edge" }); }
  /** A control in a Windows application: the person hovers it and presses Ctrl+Shift+C. */
  captureDesktop(req: { timeout_seconds?: number; screenshot?: boolean; highlight?: boolean } = {}) {
    return this.call<{ object?: Record<string, unknown> } & Record<string, unknown>>("POST", "capture-desktop", req);
  }
  /** Records actions until the person stops (or Cancel); gives the recorded steps. */
  record(req: { target?: "desktop"; url?: string; browser?: string; tab_id?: number }) {
    return this.call<{ steps?: FlowStep[]; cancelled?: boolean }>("POST", "record", req);
  }
  recordProgress() { return this.call<{ recording: boolean; count: number }>("GET", "record/progress"); }
  recordCancel() { return this.call<{ ok: boolean }>("POST", "record/cancel", {}); }
  deps(path: string) { return this.call<Dependencies>("GET", "deps?path=" + encodeURIComponent(path)); }
  assistantBuild(prompt: string, connection: string, flow: Flow) { return this.call<Proposal>("POST", "assistant/build", { prompt, connection, flow }); }
  async agents() { return (await this.call<{ agents: AgentSummary[] }>("GET", "agents")).agents; }
  modelConnections() { return this.call<ModelConnections>("GET", "model-connections"); }
  saveModelConnections(c: ModelConnections) { return this.call<ModelConnections>("PUT", "model-connections", c); }

  async blueprints() { return (await this.call<{ blueprints: Blueprint[] }>("GET", "blueprints")).blueprints; }
  /** Moves an item or folder to the workspace's .trash folder (it can be moved back); returns where it went. */
  async remove(path: string) { return (await this.call<{ path: string }>("POST", "files/delete", { path })).path; }
  /** Renames a file or folder; an item keeps its type (Invoice stays Invoice.form.json). Gives the new path. */
  async rename(path: string, name: string) { return (await this.call<{ path: string }>("POST", "files/rename", { path, name })).path; }
  /** Moves a file or folder into a folder (never into itself). Gives the new path. */
  async move(path: string, to: string) { return (await this.call<{ path: string }>("POST", "files/move", { path, to })).path; }
  /** Copies a file or folder into a folder; a taken name becomes "name - Copy". Gives the copy's path. */
  async copy(path: string, to: string) { return (await this.call<{ path: string }>("POST", "files/copy", { path, to })).path; }
  async duplicate(path: string) { return (await this.call<{ path: string }>("POST", "files/duplicate", { path })).path; }
  /** Agents whose name, description, id, actions or variables match, with what matched. */
  async search(q: string) { return (await this.call<{ results: { path: string; matches: string[] }[] }>("GET", "search?q=" + encodeURIComponent(q))).results; }
  // ---- the Command Center agent repository: sign in, check out, check in ----
  crStatus() { return this.call<CrStatus>("GET", "cr/status"); }
  crConnect(url: string, username: string, password: string, code: string) { return this.call<CrStatus>("POST", "cr/connect", { url, username, password, code }); }
  crDisconnect() { return this.call<CrStatus>("POST", "cr/disconnect"); }
  /** The repository's agents (matching q), and which workspace file is the private copy of which repository agent. */
  async crRepo(q = "") { const r = await this.call<{ bots?: RepoAgent[]; mapping?: Record<string, string> }>("GET", "cr/repo?q=" + encodeURIComponent(q)); return { agents: r.bots ?? [], mapping: r.mapping ?? {} }; }
  async crVersions(path: string) { return (await this.call<{ versions?: RepoVersion[] }>("GET", "cr/versions?path=" + encodeURIComponent(path))).versions ?? []; }
  crCheckout(path: string) { return this.call<{ local: string; path: string; version: number }>("POST", "cr/checkout", { path }); }
  crUndoCheckout(path: string) { return this.call<{ ok: boolean }>("POST", "cr/undo-checkout", { path }); }
  crCheckin(local: string, comment: string, production: boolean) { return this.call<CheckIn>("POST", "cr/checkin", { local, comment, production }); }

  async createFolder(parent: string, name: string) { return (await this.call<{ path: string }>("POST", "files/folder", { parent, name })).path; }

  /** Creates an item in a folder and returns its path. */
  async create(kind: NewItemKind, parent: string, name: string) {
    const r = kind === "TaskBot" || kind === "ApiTask" || kind === "AiAgent"
      ? await this.call<{ path: string }>("POST", "files/bot", { parent, name, kind: kind === "ApiTask" ? "ApiTask" : "TaskBot" })
      : await this.call<{ path: string }>("POST", "files/item", { parent, name, type: kind });
    return r.path;
  }
}
