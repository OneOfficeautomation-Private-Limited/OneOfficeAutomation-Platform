import { CrApi } from "../../api/cr";
import type { TaskFormField } from "../../api/cr";

/**
 * orchestration's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
/** A run of a workflow: its stage, data and the timeline of what happened. */
export type WorkflowRun = { id: string; path: string; version: number; status: string; stage?: string | null; reference?: string | null; started_by?: string; created: string;
  updated?: string; finished?: string | null; error?: { code?: string; message?: string } | null; data?: Record<string, unknown>;
  history?: { event: string; stage?: string; ts: string; detail?: Record<string, unknown> | null }[];
  tasks?: { id: string; stage?: string; kind: string; title: string; assignee?: string; status: string; claimed_by?: string | null; outcome?: string | null; created?: string; completed?: string | null }[] };

declare module "../../api/cr" {
  interface CrApi {
    workflowRuns(status?: string): Promise<WorkflowRun[]>;
    workflowRun(id: string): Promise<WorkflowRun>;
    workflowForm(path: string): Promise<{
      form?: {
        fields?: TaskFormField[];
      } | null;
    }>;
    startWorkflow(path: string, data: Record<string, unknown>, reference: string | null): Promise<{
      id: string;
      reference?: string;
    }>;
    cancelWorkflow(id: string): Promise<void>;
  }
}

CrApi.prototype.workflowRuns = async function (status?: string) { return (await this.request<{ runs?: WorkflowRun[] }>("GET", "workflows/runs" + (status ? "?status=" + encodeURIComponent(status) : ""))).runs ?? []; };
CrApi.prototype.workflowRun = function (id: string) { return this.request<WorkflowRun>("GET", "workflows/runs/" + encodeURIComponent(id)); };
CrApi.prototype.workflowForm = function (path: string) { return this.request<{ form?: { fields?: TaskFormField[] } | null }>("GET", "workflows/form?path=" + encodeURIComponent(path)); };
CrApi.prototype.startWorkflow = function (path: string, data: Record<string, unknown>, reference: string | null) { return this.request<{ id: string; reference?: string }>("POST", "workflows/start", { path, data, reference }); };
CrApi.prototype.cancelWorkflow = async function (id: string) { await this.request("POST", "workflows/runs/" + encodeURIComponent(id) + "/cancel"); };
