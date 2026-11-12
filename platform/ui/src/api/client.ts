/**
 * Typed client for the Studio API. The screens call the server only through here. The base is the folder the API
 * lives in: locally Studio's own /api/ (or /studio/api/ under `ooa server`); later One CommandCenter (V2).
 */

export type ApiError = { code: string; message: string; hint?: string; suggestedFix?: string | null };

export class ApiFailure extends Error {
  constructor(readonly status: number, readonly error: ApiError) { super(error.message); }
}

export type WorkspaceNode = {
  name: string; path: string; type: "folder" | "bot" | "form" | "process" | "document" | "objects" | "skill" | "script" | "file";
  kind?: "TaskBot" | "ApiTask"; modified?: string; size?: number; platform?: "Windows" | "Any"; children?: WorkspaceNode[];
};

/** One input or output of an action, as the toolkit catalogue describes it (JSON Schema plus OOA's own keys). */
export type FieldSpec = {
  type?: string; description?: string; default?: unknown; enum?: unknown[]; examples?: unknown[]; minimum?: number; maximum?: number;
  "x-ooa-type"?: string; "x-ooa-label"?: string;
  /** Shown only when another input has one of these values, e.g. { iterator: ["while"] }. */
  "x-ooa-visible-when"?: Record<string, unknown[]>;
  /** Names the input had before it was renamed; Studio moves a value under one to the current name. */
  "x-ooa-former-names"?: string[];
};
/** A command panel's entry: a field, a choice of options each showing its own fields, or a tick box that shows its fields. */
export type PanelEntry = string | { choice: string; options: { label: string; fields: string[] }[] } | { tick: string; fields: string[] };
export type ActionManifest = {
  id: string; name: string; category?: string; description?: string;
  inputs?: { properties?: Record<string, FieldSpec>; required?: string[] };
  outputs?: { properties?: Record<string, FieldSpec> };
  "x-ooa-panel"?: { main?: PanelEntry[]; more?: PanelEntry[] };
};
export type PackageManifest = { id: string; name: string; version: string; description?: string; actions: ActionManifest[]; conditions?: ActionManifest[]; iterators?: ActionManifest[] };

/** A variable: in_out is both an input and an output; local (or none) is a working value inside the agent. */
export type FlowVariable = { name: string; type: string; direction?: "input" | "output" | "in_out" | "local"; default?: unknown; description?: string;
  required?: boolean; enum?: unknown[]; kpi?: boolean };
/** A saved connection: a connector's address and settings, with vault references (folder/credential/attribute) for its secrets. */
export type SavedConnection = { name: string; package: string; description?: string; values?: Record<string, unknown>; credentials?: Record<string, string> };
/** A custom connector made from an OpenAPI, Swagger or Postman file (in the workspace's connectors folder). */
export type ConnectorSummary = { name: string; title?: string; operations?: number; server?: string; error?: string };
export type ConnectorOperation = { id: string; name: string; method: string; path: string; tags?: string[];
  parameters?: { name: string; required?: boolean; default?: unknown }[]; body?: { example?: unknown }; result?: { binary?: boolean } };
export type ConnectorDef = { name: string; title?: string; server?: string; operations: ConnectorOperation[] };
/** An open window: a browser tab (with the extension's tab id, or a debugging endpoint) or an application window. */
/** A file or folder on this computer (One AgentStudio's Browse…). */
export type FsEntry = { name: string; path: string; dir: boolean };
/** A folder's contents, its parent, and the places to start from (drives, home, the workspace). */
export type FsListing = { path?: string; parent?: string; entries: FsEntry[]; places: { name: string; path: string }[] };
/** A credential in One CommandCenter's vault the person may use: folder (locker), credential and attribute, never its value. */
export type VaultCredentialRef = { locker: string; name: string; attribute: string };
export type OpenWindow = { title?: string; url?: string; browser?: string; source?: "browser" | "application"; process?: string;
  tab_id?: number; connector?: boolean; endpoint?: string };
/** Values every agent can read: $sys.* from the platform, $global.* shared by all agents. */
export type Builtins = { sys: Record<string, unknown>; global: Record<string, unknown> };
export type FlowStep = {
  id: string; action: string; args?: Record<string, unknown>; out?: Record<string, string>; disabled?: boolean; note?: string;
  /** A saved connection that supplies the connector's address and sign-in. */
  connection?: string;
  /** If the step fails: log it and go on (C3), and trying an action again. */
  continueOnError?: boolean; retry?: RetryPolicy;
  children?: FlowStep[]; elseIf?: { condition?: unknown; children?: FlowStep[] }[]; else?: FlowStep[]; catch?: { codes?: string[]; as?: string; message_to?: string; line_to?: string; continueOnError?: boolean; screenshot?: boolean | string; rethrow?: boolean; children?: FlowStep[] }[]; finally?: FlowStep[];
  /** Switch: the cases, tried in order (a case value is a value to compare, or a condition when match is expression), and the default steps. */
  cases?: { values?: unknown[]; children?: FlowStep[] }[]; default?: FlowStep[];
  /** State machine: named states, each with entry steps and transitions tried in order (one without "when" is the default); a final state ends it. */
  states?: FlowState[];
};
/** A state of a State machine step. */
export type FlowState = { name: string; description?: string; final?: boolean; entry?: FlowStep[]; transitions?: FlowTransition[] };
/** A way out of a state: taken when its condition is true (always, without one); its steps run on the way to state `to`. */
export type FlowTransition = { to: string; when?: unknown; steps?: FlowStep[] };
/** How the agent is shown, kept apart from what it does: folded blocks (as Classic Studio keeps them). */
export type FlowUi = { collapsed?: string[]; breakpoints?: string[]; [key: string]: unknown };
/** A named list of steps inside an agent, run by a Run subflow step; it shares the agent's variables. */
export type Subflow = { name: string; description?: string; steps: FlowStep[] };
export type Flow = { kind: string; id: string; name: string; description?: string; tags?: string[]; variables?: FlowVariable[]; steps: FlowStep[]; subflows?: Subflow[]; onError?: FlowOnError; ui?: FlowUi;
  /** Files kept in One CommandCenter that go to the runner with the agent, at the same path (F1, AA's Control Room files). */
  files?: string[];
  /** The agent's default when a step fails outside a Try (C3): stop or continue, and trying actions again. */
  errorHandling?: { default?: "stop" | "continue"; retry?: RetryPolicy } };
/** Trying a failed action again: up to max more times, delayMs between (doubled each time when exponential), for the codes named ("*" any; none: temporary errors). */
export type RetryPolicy = { max?: number; backoff?: "fixed" | "exponential"; delayMs?: number; codes?: string[] };

/**
 * The agent's error handler: steps that run once when an error no Try block caught ends the run, while its windows and
 * files are still open; then the run fails with that error (fail, the default) or ends as succeeded with a warning.
 */
export type FlowOnError = { as?: string; message_to?: string; line_to?: string; steps: FlowStep[]; then?: "fail" | "succeed"; screenshot?: boolean | string };

/** An agent to start from (Create > Start from): its variables and steps are copied into the new agent. */
export type Blueprint = { id: string; name: string; description: string; variables: FlowVariable[]; steps: FlowStep[]; subflows?: Subflow[]; onError?: FlowOnError };

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

/** One CommandCenter link: signed in or not, and when Studio and One CommandCenter run together, the browser's own sign-in. */
export type CrStatus = { connected: boolean; url?: string | null; user?: { username: string } | null; sameServer?: boolean };
/** An agent in One CommandCenter agent repository (the public one): its latest version, and who has it checked out. */
/** The repository's facts for a list's columns (AA's Public list): labels, code analysis, platform, size, who changed it last. */
export type RepoFacts = { labels?: { label: string; version: number }[]; analysis?: { errors: number; warnings: number }; platform?: "Windows" | "Any";
  size?: number; updated?: string; updated_by?: string };
export type RepoAgent = { path: string; latest_version: number; production_version?: number | null; locked_by?: string | null } & RepoFacts;
/** A private copy's state against the repository, as AA's statuses (1 Nov 2026). "linked": signed out, so the locks are not known. */
export type RepoState = { status: "new" | "linked" | "checked_out" | "checked_out_edited" | "checked_in" | "edited" | "cloned"; path: string; locked_by?: string;
  latest_version?: number; production_version?: number | null } & RepoFacts;
/** A file kept in One CommandCenter's repository. */
export type RepoFile = { path: string; folder?: string; size?: number; updated?: string; updated_by?: string; version?: number };
export type RepoVersion = { version: number; author: string; created: string; comment?: string };
/** A check-in: the new version, and whether it became live or waits for approval first. */
export type CheckIn = { path: string; version: number; local: string; production: boolean; production_request?: unknown };

export type StudioInfo = { product: string; edition: string; workspace: string; controlRoom: string | null };

/** The kinds of item that "Create" makes, as the Studio API names them. */
/** AiAgent is an agent file made from the AI agent template (one Run AI agent step); see studio/aiAgent.ts. */
export type NewItemKind = "TaskBot" | "AiAgent" | "ApiTask" | "form" | "process" | "document" | "objects" | "skill" | "script-python" | "script-powershell";
/** An element of a shared object repository (G42), used by a step in place of its own captured object. */
export type ObjectRef = { repository: string; element: string };
/** Every object repository in the workspace with its elements ("Application/Screen/Element"). */
export type RepoCatalogue = { path: string; name: string; elements: { element: string; description: string; technology: string }[] }[];

export function defaultBase(href = location.href): string {
  // the new Studio is served at <base>/ (the preview was at <base>/next/), its API at <base>/api/
  const folder = new URL(".", href);
  return new URL(folder.pathname.endsWith("/next/") ? "../api/" : "api/", folder).toString();
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
  /** Saves a connection (a new one, or the same name again); secrets are only vault references, never values. */
  async saveConnection(c: SavedConnection) {
    await this.call("PUT", "connections/" + encodeURIComponent(c.name), { package: c.package, description: c.description, values: c.values ?? {}, credentials: c.credentials ?? {} });
  }
  async deleteConnection(name: string) { await this.call("DELETE", "connections/" + encodeURIComponent(name)); }
  /** Custom connectors: list, one with its operations, import from an API description file, delete. */
  async customConnectors() { return (await this.call<{ connectors?: ConnectorSummary[] }>("GET", "connectors")).connectors ?? []; }
  async customConnector(name: string) { return (await this.call<{ connector: ConnectorDef }>("GET", "connectors/" + encodeURIComponent(name))).connector; }
  async importConnector(text: string, name?: string, server?: string) {
    return (await this.call<{ connector: ConnectorDef }>("POST", "connectors/import", { text, name: name || undefined, server: server || undefined })).connector;
  }
  async deleteConnector(name: string) { await this.call("DELETE", "connectors/" + encodeURIComponent(name)); }
  /** Checks a condition with the given values for the variables it uses: true or false, or why it could not be checked. */
  testCondition(condition: unknown, variables: { name: string; type: string; value?: unknown }[]) {
    return this.call<{ result?: boolean; error?: { message?: string } | string }>("POST", "condition/test", { condition, variables });
  }
  /** The problems the server finds in an agent, without saving it. */
  async validate(flow: Flow) { return (await this.call<{ problems?: Problem[] }>("POST", "validate", flow)).problems ?? []; }
  /** Shows the file or folder in File Explorer (Windows). */
  async reveal(path: string) { await this.call("POST", "files/reveal", { path }); }
  /** Uploads a file into a folder as it is (a taken name gets " (2)"); gives where it went. */
  async upload(parent: string, name: string, data: Blob) {
    const res = await this.fetcher(this.base + "files/upload?parent=" + encodeURIComponent(parent) + "&name=" + encodeURIComponent(name),
      { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/octet-stream" }, body: data });
    const text = await res.text();
    const r = text ? JSON.parse(text) : {};
    if (!res.ok) throw new ApiFailure(res.status, r.error ?? { code: "HTTP." + res.status, message: res.statusText || "Upload failed" });
    return String(r.path ?? "");
  }
  /** Where a file downloads from (a folder comes as a zip). */
  downloadUrl(path: string) { return this.base + "files/download?path=" + encodeURIComponent(path); }
  /** Windows open on this computer now, for the window editor. */
  /** This computer's folders for Browse…: a folder's contents, or the places to start from when path is empty. */
  fsList(path: string) { return this.call<FsListing>("GET", "fs/list?path=" + encodeURIComponent(path)); }
  /** The vault credentials of the connected One CommandCenter the person may use (none when not connected). */
  crCredentials() { return this.call<{ connected: boolean; credentials: VaultCredentialRef[] }>("GET", "cr/credentials"); }
  async windows() { return (await this.call<{ windows?: OpenWindow[] }>("GET", "windows")).windows ?? []; }
  /** Capture: the person clicks an element in a browser (a new page at url, or an open tab); gives its object and selector. */
  capture(req: { url?: string; browser?: string; tab_id?: number; endpoint?: string; tab_title?: string; tab_url?: string }) {
    return this.call<{ selector?: string; element?: { tag?: string; type?: string; text?: string }; object?: Record<string, unknown> }>("POST", "capture", req);
  }
  /** Opens the page and outlines every element the captured object matches; gives how many match. */
  captureTest(url: string, object: unknown) { return this.call<{ count?: number }>("POST", "capture/test", { url, object, browser: "edge" }); }
  /** A control in a Windows application: the person hovers it and presses Ctrl+Shift+C. */
  /** A point in a window: the person points and presses Ctrl+Shift+C (M1); x and y are measured from the window's corner. */
  pickPoint(req: { timeout_seconds?: number } = {}) {
    return this.call<{ x: number; y: number; screen_x: number; screen_y: number; window: string; window_width?: number; window_height?: number }>("POST", "pick-point", req);
  }
  captureDesktop(req: { timeout_seconds?: number; screenshot?: boolean; highlight?: boolean } = {}) {
    return this.call<{ object?: Record<string, unknown> } & Record<string, unknown>>("POST", "capture-desktop", req);
  }
  /** Records actions until the person stops (or Cancel); gives the recorded steps. */
  record(req: { target?: "desktop"; url?: string; browser?: string; tab_id?: number; endpoint?: string; tab_title?: string; tab_url?: string; window_title?: string; process?: string }) {
    return this.call<{ steps?: FlowStep[]; cancelled?: boolean }>("POST", "record", req);
  }
  recordProgress() { return this.call<{ recording: boolean; count: number }>("GET", "record/progress"); }
  recordCancel() { return this.call<{ ok: boolean }>("POST", "record/cancel", {}); }
  deps(path: string) { return this.call<Dependencies>("GET", "deps?path=" + encodeURIComponent(path)); }
  async objectCatalogue() { return (await this.call<{ repositories: RepoCatalogue }>("GET", "objects/catalogue")).repositories; }
  addToRepository(b: { repository: string; application: string; screen: string; name: string; description?: string; object: unknown; replace?: boolean }) {
    return this.call<ObjectRef>("POST", "objects/add", b);
  }
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
  // ---- One CommandCenter agent repository: sign in, check out, check in ----
  crStatus() { return this.call<CrStatus>("GET", "cr/status"); }
  crConnect(url: string, username: string, password: string, code: string, accepted = false) {
    return this.call<CrStatus>("POST", "cr/connect", accepted ? { url, username, password, code, accepted } : { url, username, password, code });
  }
  crDisconnect() { return this.call<CrStatus>("POST", "cr/disconnect"); }
  /** The repository's agents (matching q), and which workspace file is the private copy of which repository agent. */
  async crRepo(q = "") { const r = await this.call<{ bots?: RepoAgent[]; mapping?: Record<string, string> }>("GET", "cr/repo?q=" + encodeURIComponent(q)); return { agents: r.bots ?? [], mapping: r.mapping ?? {} }; }
  async crVersions(path: string) { return (await this.call<{ versions?: RepoVersion[] }>("GET", "cr/versions?path=" + encodeURIComponent(path))).versions ?? []; }
  crCheckout(path: string, version?: number) {
    return this.call<{ local: string; path: string; version: number; kept?: boolean; cloned?: string[] }>("POST", "cr/checkout", version ? { path, version } : { path });
  }
  /** The files kept in One CommandCenter that the person may see (F1: AA's Control Room files). */
  async crFiles() { return (await this.call<{ files?: RepoFile[] }>("GET", "cr/files")).files ?? []; }
  /** Clone: a read-only private copy that follows the repository (AA's Clone); check it out to change it. */
  crClone(path: string) { return this.call<{ local: string; path: string; version: number }>("POST", "cr/clone", { path }); }
  /** Copy to Private: the agent's latest version becomes a new agent of your own at "to" (AA's Copy). */
  crCopy(path: string, to: string) { return this.call<{ local: string; from: string; version: number; cloned?: string[] }>("POST", "cr/copy", { path, to }); }
  /** Each private copy linked to the repository, with its state (checked out, edited, checked in …); unlinked items are new. */
  crStates() { return this.call<{ states: Record<string, RepoState>; connected: boolean }>("GET", "cr/states"); }
  /** Revert edits: the private copy goes back to the version that was checked out. */
  crRevert(local: string) { return this.call<{ local: string; path: string; version: number }>("POST", "cr/revert", { local }); }
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
