import { CrApi } from "../../api/cr";

/**
 * document-automation's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
/** GET /api/docflow/version: the DocFlow in use against the one this release is tested with. */
export interface DocFlowVersion {
  status: "compatible" | "newer" | "too_old" | "other_contract" | "unreachable" | "not_set_up" | "unknown";
  compatible: boolean; version: string | null; tested: string; minimum: string; contract: string; message: string;
}

declare module "../../api/cr" {
  interface CrApi {
    /** Whether the Document Automation service (DocFlow) in use is a version this One CommandCenter works with. */
    docflowVersion(): Promise<DocFlowVersion>;
  }
}

/** Whether the Document Automation service (DocFlow) in use is a version this One CommandCenter works with. */

CrApi.prototype.docflowVersion = function () { return this.request<DocFlowVersion>("GET", "docflow/version"); };
