import { CrApi } from "../../api/cr";
import type { TestRunSummary } from "../../api/cr";

/**
 * testing's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
declare module "../../api/cr" {
  interface CrApi {
    saveTestSet(id: string | null, b: {
      name: string;
      description: string;
      bot_path: string;
      cases: unknown[];
    }): Promise<{
      id: string;
    }>;
    runTestSet(id: string, where: {
      device_id?: string;
      pool_id?: string;
    }): Promise<{
      test_run_id: string;
      started: number;
      cases: number;
    }>;
    testHistory(id: string): Promise<TestRunSummary[]>;
    testRun(id: string): Promise<TestRunSummary & {
      bot_path: string;
      version: number;
      started_by?: string;
      results: {
        case_name: string;
        status: string;
        passed: boolean;
        detail?: string;
        run_id?: string;
      }[];
    }>;
  }
}

CrApi.prototype.saveTestSet = function (id: string | null, b: { name: string; description: string; bot_path: string; cases: unknown[] }) { return this.request<{ id: string }>(id ? "PUT" : "POST", "test-sets" + (id ? "/" + encodeURIComponent(id) : ""), b); };
CrApi.prototype.runTestSet = function (id: string, where: { device_id?: string; pool_id?: string }) { return this.request<{ test_run_id: string; started: number; cases: number }>("POST", "test-sets/" + encodeURIComponent(id) + "/run", where); };
CrApi.prototype.testHistory = async function (id: string) { return (await this.request<{ runs?: TestRunSummary[] }>("GET", "test-sets/" + encodeURIComponent(id) + "/history")).runs ?? []; };
CrApi.prototype.testRun = function (id: string) { return this.request<TestRunSummary & { bot_path: string; version: number; started_by?: string; results: { case_name: string; status: string; passed: boolean; detail?: string; run_id?: string }[] }>("GET", "test-sets/runs/" + encodeURIComponent(id)); };
