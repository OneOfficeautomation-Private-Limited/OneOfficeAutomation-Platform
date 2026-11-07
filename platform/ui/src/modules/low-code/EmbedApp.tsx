import { useMemo } from "react";
import type { AppApi } from "./Apps";
import { AppView } from "./Apps";

/**
 * /embed/<link id>#k=<key> (gap G68): an app shown inside another site (Salesforce, ServiceNow, Teams, an intranet).
 * Nobody signs in: the key from the URL's fragment goes with each call as X-OOA-Embed-Key, and only the four embed
 * calls accept it. The page's query fills the app's fields, so the host page can pass the record the person is on
 * (?customer=ACME).
 */
type Err = { error?: { code: string; message: string } };

export class EmbedApi implements AppApi {
  constructor(private readonly id: string, private readonly key: string, private readonly fetcher: typeof fetch = (...a) => fetch(...a)) {}

  private async call<T>(method: string, route: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { "X-OOA-Embed-Key": this.key };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const res = await this.fetcher("/api/embed/" + encodeURIComponent(this.id) + "/" + route, { method, credentials: "omit", headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    const data = (text ? JSON.parse(text) : {}) as T & Err;
    if (!res.ok) throw new Error(data.error?.message ?? res.statusText ?? "Request failed");
    return data;
  }

  app() { return this.call<Awaited<ReturnType<AppApi["app"]>>>("GET", "app"); }
  appAct(_app: string, component: string, fields: Record<string, unknown>) { return this.call<Awaited<ReturnType<AppApi["appAct"]>>>("POST", "act", { component, fields }); }
  appRun(_app: string, runId: string) { return this.call<Awaited<ReturnType<AppApi["appRun"]>>>("GET", "runs/" + encodeURIComponent(runId)); }
  appRecords(_app: string, component: string, q = "") {
    return this.call<Awaited<ReturnType<AppApi["appRecords"]>>>("GET", "records/" + encodeURIComponent(component) + (q.trim() ? "?q=" + encodeURIComponent(q.trim()) : ""));
  }
}

/** The link id from the path, the key from the fragment (#k=...), the fields from the query. */
export function readEmbedLocation(loc: { pathname: string; hash: string; search: string }) {
  const id = decodeURIComponent(loc.pathname.replace(/^\/embed\//, "").replace(/\/.*$/, ""));
  const key = new URLSearchParams(loc.hash.replace(/^#/, "")).get("k") ?? "";
  const initial: Record<string, string> = {};
  new URLSearchParams(loc.search).forEach((v, k) => { if (/^[A-Za-z][A-Za-z0-9_]{0,59}$/.test(k)) initial[k] = v; });
  return { id, key, initial };
}

export function EmbedApp({ fetcher }: { fetcher?: typeof fetch }) {
  const { id, key, initial } = useMemo(() => readEmbedLocation(window.location), []);
  const api = useMemo(() => new EmbedApi(id, key, fetcher), [id, key, fetcher]);
  if (!key) return <p className="st-alert" role="alert">This embed link has no key. Copy the whole link, including the part after #, from the app's Embed links.</p>;
  return <div className="cc-embed"><AppView api={api} appId={id} initial={initial} /></div>;
}
