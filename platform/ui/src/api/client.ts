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
export type Flow = { kind: string; id: string; name: string; description?: string; variables?: FlowVariable[]; steps: FlowStep[] };

/** An agent to start from (Create > Start from): its variables and steps are copied into the new agent. */
export type Blueprint = { id: string; name: string; description: string; variables: FlowVariable[]; steps: FlowStep[] };

/** Something the check on save found: the step, the field, what is wrong and how to fix it. */
export type Problem = { stepId?: string; path?: string; message: string; suggestedFix?: string };

export type StudioInfo = { product: string; edition: string; workspace: string; controlRoom: string | null };

/** The kinds of item that "Create" makes, as the Studio API names them. */
export type NewItemKind = "TaskBot" | "ApiTask" | "form" | "process" | "document" | "skill" | "script-python" | "script-powershell";

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
  async blueprints() { return (await this.call<{ blueprints: Blueprint[] }>("GET", "blueprints")).blueprints; }
  async createFolder(parent: string, name: string) { return (await this.call<{ path: string }>("POST", "files/folder", { parent, name })).path; }

  /** Creates an item in a folder and returns its path. */
  async create(kind: NewItemKind, parent: string, name: string) {
    const r = kind === "TaskBot" || kind === "ApiTask"
      ? await this.call<{ path: string }>("POST", "files/bot", { parent, name, kind })
      : await this.call<{ path: string }>("POST", "files/item", { parent, name, type: kind });
    return r.path;
  }
}
