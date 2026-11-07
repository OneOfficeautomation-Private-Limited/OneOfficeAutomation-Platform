import { CrApi } from "../../api/cr";

/**
 * runner-management's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
export type PoolScaling = { enabled?: boolean; min?: number | ""; max?: number | ""; runs_per_runner?: number | ""; cooldown_minutes?: number | ""; provider?: string; [k: string]: unknown };
export type PoolScalingNow = { queued: number; running: number; refused_recently: number; online: number; desired: number };

declare module "../../api/cr" {
  interface CrApi {
    /** G52: a pool's auto-scaling and what it would ask for now. */
    poolScaling(id: string): Promise<{
      scaling: PoolScaling;
      now: PoolScalingNow;
    }>;
    savePoolScaling(id: string, b: PoolScaling): Promise<{
      scaling: PoolScaling;
      now: PoolScalingNow;
    }>;
  }
}

/** G52: a pool's auto-scaling and what it would ask for now. */

CrApi.prototype.poolScaling = function (id: string) { return this.request<{ scaling: PoolScaling; now: PoolScalingNow }>("GET", "pools/" + encodeURIComponent(id) + "/scaling"); };
CrApi.prototype.savePoolScaling = function (id: string, b: PoolScaling) { return this.request<{ scaling: PoolScaling; now: PoolScalingNow }>("PUT", "pools/" + encodeURIComponent(id) + "/scaling", b); };
