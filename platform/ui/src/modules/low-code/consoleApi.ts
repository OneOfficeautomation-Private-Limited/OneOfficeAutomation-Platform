import { CrApi, CrFailure } from "../../api/cr";
import type { CrError } from "../../api/cr";

/**
 * low-code's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
export type AppEmbed = { id: string; app_id: string; name: string; origins: string[]; created: string; created_by?: string; last_used?: string | null };
export type AppInfo = { id: string; name: string; description?: string | null; audience: string[]; published_by: string; updated?: string; definition?: AppDefinition; can_edit?: boolean };
export type DataEntity = { id: string; name: string; folder: string; description?: string | null; fields: DataField[]; records: number; updated?: string; updated_by?: string | null };
export type DataRecord = { id: string; data: Record<string, unknown>; created?: string; created_by?: string | null; updated?: string; updated_by?: string | null };
export type DataBucket = { id: string; name: string; folder: string; description?: string | null; max_mb: number; files: number; bytes: number };
export type BucketFile = { path: string; bytes: number; modified: string };
export type AppDefinition = { pages: { id: string; title: string; components: AppComponent[] }[] };
/** The business data store (G66): entities with typed fields and their records, and storage buckets of files. */
export type DataField = { name: string; type: string; required?: boolean; unique?: boolean; choices?: string[] };
export type AppComponent = { id: string; kind: string; text?: string; name?: string; label?: string; hint?: string; type?: string; required?: boolean; choices?: string[];
  style?: string; requires?: string[]; action?: AppAction; entity?: string; title?: string; columns?: string[]; sort?: string };
/** Low-code apps (G67): pages of components; a button's action is kept by the server, the browser sends only which button and the fields. */
export type AppAction = { kind: string; agent?: string; runner?: string; pool?: string; inputs?: Record<string, unknown>; entity?: string; values?: Record<string, unknown>;
  queue?: string; data?: Record<string, unknown>; page?: string; success?: string };

declare module "../../api/cr" {
  interface CrApi {
    apps(): Promise<AppInfo[]>;
    app(id: string): Promise<AppInfo>;
    saveApp(id: string | null, body: {
      name: string;
      description?: string;
      audience: string[];
      definition: AppDefinition;
    }): Promise<AppInfo>;
    appEmbeds(id: string): Promise<AppEmbed[]>;
    makeAppEmbed(id: string, body: {
      name: string;
      origins: string[];
    }): Promise<AppEmbed & {
      key: string;
      path: string;
    }>;
    deleteAppEmbed(id: string, embed: string): Promise<void>;
    /** A Teams app package (zip) whose tab shows the embed link; the key is needed again, as only its hash is kept. */
    teamsPackage(id: string, embed: string, key: string, baseUrl: string): Promise<Blob>;
    deleteApp(id: string): Promise<void>;
    appAct(id: string, component: string, fields: Record<string, unknown>): Promise<{
      run_id?: string;
      record_id?: string;
      item_id?: string;
      page?: string;
      message?: string;
      outputs?: Record<string, unknown>;
    }>;
    appRun(id: string, runId: string): Promise<{
      status: string;
      outputs?: Record<string, unknown>;
      error?: string;
    }>;
    appRecords(id: string, component: string, q?: string): Promise<{
      records: {
        id: string;
        data: Record<string, unknown>;
      }[];
      total: number;
      fields: {
        name: string;
      }[];
    }>;
    dataEntities(): Promise<DataEntity[]>;
    saveDataEntity(id: string | null, body: {
      name: string;
      folder: string;
      description?: string;
      fields: DataField[];
    }): Promise<DataEntity>;
    deleteDataEntity(id: string): Promise<void>;
    dataRecords(entity: string, q?: string): Promise<{
      records: DataRecord[];
      total: number;
    }>;
    saveDataRecord(entity: string, id: string | null, data: Record<string, unknown>): Promise<DataRecord>;
    deleteDataRecord(entity: string, id: string): Promise<void>;
    dataBuckets(): Promise<DataBucket[]>;
    saveDataBucket(id: string | null, body: {
      name: string;
      folder: string;
      description?: string;
      max_mb: number;
    }): Promise<DataBucket>;
    deleteDataBucket(id: string): Promise<void>;
    bucketFiles(bucket: string): Promise<BucketFile[]>;
    bucketFileUrl(bucket: string, path: string): string;
    uploadBucketFile(bucket: string, path: string, file: Blob): Promise<{
      error?: CrError;
      path?: string;
    }>;
    deleteBucketFile(bucket: string, path: string): Promise<void>;
  }
}

CrApi.prototype.apps = async function () { return (await this.request<{ apps?: AppInfo[] }>("GET", "apps")).apps ?? []; };
CrApi.prototype.app = function (id: string) { return this.request<AppInfo>("GET", "apps/" + encodeURIComponent(id)); };
CrApi.prototype.saveApp = function (id: string | null, body: { name: string; description?: string; audience: string[]; definition: AppDefinition }) {
  return this.request<AppInfo>(id ? "PUT" : "POST", "apps" + (id ? "/" + encodeURIComponent(id) : ""), body); };
CrApi.prototype.appEmbeds = async function (id: string) { return (await this.request<{ embeds?: AppEmbed[] }>("GET", "apps/" + encodeURIComponent(id) + "/embeds")).embeds ?? []; };
CrApi.prototype.makeAppEmbed = function (id: string, body: { name: string; origins: string[] }) { return this.request<AppEmbed & { key: string; path: string }>("POST", "apps/" + encodeURIComponent(id) + "/embeds", body); };
CrApi.prototype.deleteAppEmbed = async function (id: string, embed: string) { await this.request("DELETE", "apps/" + encodeURIComponent(id) + "/embeds/" + encodeURIComponent(embed)); };
/** A Teams app package (zip) whose tab shows the embed link; the key is needed again, as only its hash is kept. */

CrApi.prototype.teamsPackage = async function (id: string, embed: string, key: string, baseUrl: string) {
  const res = await this.fetcher(this.base + "apps/" + encodeURIComponent(id) + "/embeds/" + encodeURIComponent(embed) + "/teams", { method: "POST", credentials: "same-origin",
    headers: { "X-OOA-Request": "1", "Content-Type": "application/json" }, body: JSON.stringify({ key, base_url: baseUrl }) });
  if (!res.ok) { let d: { error?: CrError } = {}; try { d = JSON.parse(await res.text()); } catch { /* not JSON */ } throw new CrFailure(res.status, d.error ?? { code: "HTTP." + res.status, message: res.statusText || "Failed" }); }
  return res.blob();
};
CrApi.prototype.deleteApp = async function (id: string) { await this.request("DELETE", "apps/" + encodeURIComponent(id)); };
CrApi.prototype.appAct = function (id: string, component: string, fields: Record<string, unknown>) {
  return this.request<{ run_id?: string; record_id?: string; item_id?: string; page?: string; message?: string; outputs?: Record<string, unknown> }>("POST", "apps/" + encodeURIComponent(id) + "/act", { component, fields }); };
CrApi.prototype.appRun = function (id: string, runId: string) { return this.request<{ status: string; outputs?: Record<string, unknown>; error?: string }>("GET", "apps/" + encodeURIComponent(id) + "/runs/" + encodeURIComponent(runId)); };
CrApi.prototype.appRecords = function (id: string, component: string, q = "") {
  return this.request<{ records: { id: string; data: Record<string, unknown> }[]; total: number; fields: { name: string }[] }>("GET", "apps/" + encodeURIComponent(id) + "/records/" + encodeURIComponent(component) + (q.trim() ? "?q=" + encodeURIComponent(q.trim()) : "")); };
CrApi.prototype.dataEntities = async function () { return (await this.request<{ entities?: DataEntity[] }>("GET", "data/entities")).entities ?? []; };
CrApi.prototype.saveDataEntity = function (id: string | null, body: { name: string; folder: string; description?: string; fields: DataField[] }) {
  return this.request<DataEntity>(id ? "PUT" : "POST", "data/entities" + (id ? "/" + encodeURIComponent(id) : ""), body); };
CrApi.prototype.deleteDataEntity = async function (id: string) { await this.request("DELETE", "data/entities/" + encodeURIComponent(id)); };
CrApi.prototype.dataRecords = function (entity: string, q = "") { return this.request<{ records: DataRecord[]; total: number }>("GET", "data/entities/" + encodeURIComponent(entity) + "/records" + (q.trim() ? "?q=" + encodeURIComponent(q.trim()) : "")); };
CrApi.prototype.saveDataRecord = function (entity: string, id: string | null, data: Record<string, unknown>) {
  return this.request<DataRecord>(id ? "PUT" : "POST", "data/entities/" + encodeURIComponent(entity) + "/records" + (id ? "/" + encodeURIComponent(id) : ""), { data }); };
CrApi.prototype.deleteDataRecord = async function (entity: string, id: string) { await this.request("DELETE", "data/entities/" + encodeURIComponent(entity) + "/records/" + encodeURIComponent(id)); };
CrApi.prototype.dataBuckets = async function () { return (await this.request<{ buckets?: DataBucket[] }>("GET", "data/buckets")).buckets ?? []; };
CrApi.prototype.saveDataBucket = function (id: string | null, body: { name: string; folder: string; description?: string; max_mb: number }) {
  return this.request<DataBucket>(id ? "PUT" : "POST", "data/buckets" + (id ? "/" + encodeURIComponent(id) : ""), body); };
CrApi.prototype.deleteDataBucket = async function (id: string) { await this.request("DELETE", "data/buckets/" + encodeURIComponent(id)); };
CrApi.prototype.bucketFiles = async function (bucket: string) { return (await this.request<{ files?: BucketFile[] }>("GET", "data/buckets/" + encodeURIComponent(bucket) + "/files")).files ?? []; };
CrApi.prototype.bucketFileUrl = function (bucket: string, path: string) { return this.base + "data/buckets/" + encodeURIComponent(bucket) + "/file?path=" + encodeURIComponent(path); };
CrApi.prototype.uploadBucketFile = async function (bucket: string, path: string, file: Blob) {
  const res = await this.fetcher(this.base + "data/buckets/" + encodeURIComponent(bucket) + "/files?path=" + encodeURIComponent(path), { method: "PUT", credentials: "same-origin",
    headers: { "X-OOA-Request": "1", "Content-Type": "application/octet-stream" }, body: file });
  const text = await res.text();
  let d: { error?: CrError; path?: string } = {};
  try { d = JSON.parse(text); } catch { /* not JSON */ }
  if (!res.ok) throw new CrFailure(res.status, d.error ?? { code: "HTTP." + res.status, message: res.statusText || "Upload failed" });
  return d;
};
CrApi.prototype.deleteBucketFile = async function (bucket: string, path: string) { await this.request("DELETE", "data/buckets/" + encodeURIComponent(bucket) + "/files?path=" + encodeURIComponent(path)); };
