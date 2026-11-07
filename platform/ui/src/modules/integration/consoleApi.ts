import { CrApi } from "../../api/cr";

/**
 * integration's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
declare module "../../api/cr" {
  interface CrApi {
    createAgentApi(b: Record<string, unknown>): Promise<{
      id: string;
      name?: string;
      url: string;
      token: string;
    }>;
    newAgentApiToken(id: string): Promise<{
      token: string;
    }>;
    setAgentApiEnabled(id: string, enabled: boolean): Promise<void>;
    deleteAgentApi(id: string): Promise<void>;
  }
}

CrApi.prototype.createAgentApi = function (b: Record<string, unknown>) { return this.request<{ id: string; name?: string; url: string; token: string }>("POST", "apitasks", b); };
CrApi.prototype.newAgentApiToken = function (id: string) { return this.request<{ token: string }>("POST", "apitasks/" + encodeURIComponent(id) + "/token"); };
CrApi.prototype.setAgentApiEnabled = async function (id: string, enabled: boolean) { await this.request("PATCH", "apitasks/" + encodeURIComponent(id), { enabled }); };
CrApi.prototype.deleteAgentApi = async function (id: string) { await this.request("DELETE", "apitasks/" + encodeURIComponent(id)); };
