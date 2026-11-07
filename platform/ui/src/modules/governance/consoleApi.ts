import { CrApi } from "../../api/cr";
import type { CodePolicy } from "../../api/cr";

/**
 * governance's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
/** A policy under Administration > Policies (1 Nov 2026): code analysis rules or AI guardrails, given to folders; "default" is the default code analysis. */
export type Policy = { id: string; name: string; kind: "code" | "ai"; description?: string; rules: CodePolicy & Record<string, unknown>; folders: string[]; built_in: boolean;
  updated?: string; updated_by?: string };
export type AiGovernance = { totals?: { calls?: number; input_tokens?: number; output_tokens?: number; masked_calls?: number; tool_calls?: number; avg_latency_ms?: number | null };
  by_model?: { provider: string; model?: string; calls: number; input_tokens: number; output_tokens: number }[]; by_agent?: { bot_path: string; calls: number; tokens: number }[];
  recent?: { ts: string; bot_path: string; requested_by?: string; purpose?: string; provider?: string; model?: string; input_tokens?: number; output_tokens?: number; masked?: string; tool_calls?: string; prompt_sha256?: string; run_id?: string }[] };

declare module "../../api/cr" {
  interface CrApi {
    governance(days: number): Promise<AiGovernance>;
    codePolicy(): Promise<CodePolicy>;
    policies(): Promise<Policy[]>;
    /** What an AI guardrails policy stopped (steps, latest first) and masked (by kind) over the last days. */
    policyStopped(id: string, days: string): Promise<{
      stopped: {
        run_id: string;
        agent: string;
        created: string;
        code: string;
        message: string;
      }[];
      stopped_total: number;
      masked: Record<string, number>;
      calls_masked: number;
    }>;
    savePolicy(id: string | null, b: {
      name?: string;
      kind?: string;
      description?: string;
      folders?: string[];
      rules: unknown;
    }): Promise<Policy>;
    deletePolicy(id: string): Promise<void>;
    saveCodePolicy(b: {
      enforce_at_checkin: boolean;
      rules: Record<string, {
        enabled: boolean;
        severity: string;
        params: Record<string, unknown>;
      }>;
    }): Promise<CodePolicy>;
  }
}

CrApi.prototype.governance = function (days: number) { return this.request<AiGovernance>("GET", "governance?days=" + days); };
CrApi.prototype.codePolicy = function () { return this.request<CodePolicy>("GET", "repo/policy"); };
CrApi.prototype.policies = async function () { return (await this.request<{ policies?: Policy[] }>("GET", "policies")).policies ?? []; };
/** What an AI guardrails policy stopped (steps, latest first) and masked (by kind) over the last days. */

CrApi.prototype.policyStopped = function (id: string, days: string) {
  return this.request<{ stopped: { run_id: string; agent: string; created: string; code: string; message: string }[]; stopped_total: number; masked: Record<string, number>; calls_masked: number }>(
    "GET", "policies/" + encodeURIComponent(id) + "/stopped?days=" + encodeURIComponent(days));
};
CrApi.prototype.savePolicy = function (id: string | null, b: { name?: string; kind?: string; description?: string; folders?: string[]; rules: unknown }) {
  return id ? this.request<Policy>("PUT", "policies/" + encodeURIComponent(id), b) : this.request<Policy>("POST", "policies", b);
};
CrApi.prototype.deletePolicy = async function (id: string) { await this.request("DELETE", "policies/" + encodeURIComponent(id)); };
CrApi.prototype.saveCodePolicy = function (b: { enforce_at_checkin: boolean; rules: Record<string, { enabled: boolean; severity: string; params: Record<string, unknown> }> }) { return this.request<CodePolicy>("PUT", "repo/policy", b); };
