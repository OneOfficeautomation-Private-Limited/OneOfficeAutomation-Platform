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
  tasks?: { id: string; stage?: string; kind: string; title: string; assignee?: string; status: string; claimed_by?: string | null; outcome?: string | null; created?: string; completed?: string | null; due?: string | null }[];
  /** Run > Workflow runs (26 Nov 2026): the step it is on by name, what it waits on, when that is due and whether it is late. */
  stage_name?: string | null; waiting_on?: string; waiting_kind?: "person" | "runner" | "agent" | "time" | "children"; due?: string; late?: boolean;
  /** One run: every step of its workflow (a parallel step's branches inside), and its branches' and sub-workflow's runs. */
  steps?: WorkflowStepInfo[]; children?: WorkflowRun[]; parent_id?: string | null; branch?: string | null };
export type WorkflowStepInfo = { id: string; name: string; type: string; branches?: { name: string; steps: WorkflowStepInfo[] }[] };

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
    remindWorkflow(id: string): Promise<{ tasks: number }>;
    reassignWorkflow(id: string, to: string): Promise<{ tasks: number }>;
    skipWorkflowStep(id: string, reason: string): Promise<void>;
  }
}

CrApi.prototype.workflowRuns = async function (status?: string) { return (await this.request<{ runs?: WorkflowRun[] }>("GET", "workflows/runs" + (status ? "?status=" + encodeURIComponent(status) : ""))).runs ?? []; };
CrApi.prototype.workflowRun = function (id: string) { return this.request<WorkflowRun>("GET", "workflows/runs/" + encodeURIComponent(id)); };
CrApi.prototype.workflowForm = function (path: string) { return this.request<{ form?: { fields?: TaskFormField[] } | null }>("GET", "workflows/form?path=" + encodeURIComponent(path)); };
CrApi.prototype.startWorkflow = function (path: string, data: Record<string, unknown>, reference: string | null) { return this.request<{ id: string; reference?: string }>("POST", "workflows/start", { path, data, reference }); };
CrApi.prototype.cancelWorkflow = async function (id: string) { await this.request("POST", "workflows/runs/" + encodeURIComponent(id) + "/cancel"); };
CrApi.prototype.remindWorkflow = function (id: string) { return this.request<{ tasks: number }>("POST", "workflows/runs/" + encodeURIComponent(id) + "/remind"); };
CrApi.prototype.reassignWorkflow = function (id: string, to: string) { return this.request<{ tasks: number }>("POST", "workflows/runs/" + encodeURIComponent(id) + "/reassign", { to }); };
CrApi.prototype.skipWorkflowStep = async function (id: string, reason: string) { await this.request("POST", "workflows/runs/" + encodeURIComponent(id) + "/skip", { reason }); };
