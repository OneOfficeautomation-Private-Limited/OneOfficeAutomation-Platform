/**
 * Typed client for the Studio API. The screens call the server only through here. The base is the folder the API
 * lives in: locally Studio's own /api/ (or /studio/api/ under `ooa server`); later the Command Center (V2).
 */

export type ApiError = { code: string; message: string; hint?: string };

export class ApiFailure extends Error {
  constructor(readonly status: number, readonly error: ApiError) { super(error.message); }
}

export type WorkspaceNode = {
  name: string; path: string; type: "folder" | "bot" | "form" | "process" | "document" | "skill" | "script" | "file";
  kind?: "TaskBot" | "ApiTask"; modified?: string; children?: WorkspaceNode[];
};

/** One input or output of an action, as the toolkit catalogue describes it (JSON Schema plus OOA's own keys). */
export type FieldSpec = {
  type?: string; description?: string; default?: unknown; enum?: unknown[]; examples?: unknown[];
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

export type FlowVariable = { name: string; type: string; direction?: "input" | "output" | "both"; default?: unknown; description?: string };
export type FlowStep = {
  id: string; action: string; args?: Record<string, unknown>; out?: Record<string, string>; disabled?: boolean; note?: string;
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
export type RunOptions = { debug?: boolean; breakpoints?: string[]; stopAtStart?: boolean };
export type DebugCommand = "continue" | "stepOver" | "stepInto" | "stepOut";

/** Starts an agent on its own: a file arriving in a folder, a schedule, or a call to a webhook address. */
export type Trigger = {
  id: string; name?: string; bot: string; type: "file" | "schedule" | "webhook"; enabled?: boolean;
  folder?: string; pattern?: string; every_minutes?: number; daily_at?: string; secret?: string;
};

/** An agent of the workspace, by the id other agents and AI agents call it with. */
export type AgentSummary = { path: string; id: string; name: string; variables: FlowVariable[] };
/**
 * A model connection: a provider, model and address kept in one place, used by name. The key is never in the file:
 * an environment variable name, or a vault reference {"$credential": "folder/credential/attribute"}.
 */
export type ModelConnection = { name: string; provider: string; model?: string; base_url?: string; api_key_env?: string; api_key?: { $credential: string }; description?: string };
export type ModelConnections = { default: string; connections: ModelConnection[] };

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

  async agents() { return (await this.call<{ agents: AgentSummary[] }>("GET", "agents")).agents; }
  modelConnections() { return this.call<ModelConnections>("GET", "model-connections"); }
  saveModelConnections(c: ModelConnections) { return this.call<ModelConnections>("PUT", "model-connections", c); }

  async blueprints() { return (await this.call<{ blueprints: Blueprint[] }>("GET", "blueprints")).blueprints; }
  /** Moves an item or folder to the workspace's .trash folder (it can be moved back); returns where it went. */
  async remove(path: string) { return (await this.call<{ path: string }>("POST", "files/delete", { path })).path; }
  async createFolder(parent: string, name: string) { return (await this.call<{ path: string }>("POST", "files/folder", { parent, name })).path; }

  /** Creates an item in a folder and returns its path. */
  async create(kind: NewItemKind, parent: string, name: string) {
    const r = kind === "TaskBot" || kind === "ApiTask" || kind === "AiAgent"
      ? await this.call<{ path: string }>("POST", "files/bot", { parent, name, kind: kind === "ApiTask" ? "ApiTask" : "TaskBot" })
      : await this.call<{ path: string }>("POST", "files/item", { parent, name, type: kind });
    return r.path;
  }
}
