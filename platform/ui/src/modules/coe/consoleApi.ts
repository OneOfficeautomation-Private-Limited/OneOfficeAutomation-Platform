import { CrApi } from "../../api/cr";

/**
 * coe's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
export type IdeaList = { ideas: Idea[]; can_manage: boolean; statuses: string[] };
/** An automation idea (G65): suggested and voted by anyone, assessed by the automation team; the score and savings are worked out by the server. */
export type Idea = { id: string; title: string; description?: string | null; department?: string | null; process_owner?: string | null; status: string;
  volume_month?: number | null; minutes_each?: number | null; hourly_cost?: number | null; complexity?: string | null; rules_score?: number | null;
  structured_score?: number | null; stable_score?: number | null; bot_path?: string | null; notes?: string | null; submitted_by: string; submitted: string;
  updated?: string; updated_by?: string | null; votes: number; voted: boolean; mine: boolean; hours_year: number; value_year?: number | null; score?: number | null;
  runs_30_days?: number; hours_saved_30_days?: number | null };

declare module "../../api/cr" {
  interface CrApi {
    ideas(): Promise<IdeaList>;
    saveIdea(id: string | null, body: Partial<Idea>): Promise<Idea>;
    voteIdea(id: string): Promise<Idea>;
    deleteIdea(id: string): Promise<void>;
  }
}

CrApi.prototype.ideas = async function () { return this.request<IdeaList>("GET", "ideas"); };
CrApi.prototype.saveIdea = async function (id: string | null, body: Partial<Idea>) { return this.request<Idea>(id ? "PUT" : "POST", id ? "ideas/" + encodeURIComponent(id) : "ideas", body); };
CrApi.prototype.voteIdea = async function (id: string) { return this.request<Idea>("POST", "ideas/" + encodeURIComponent(id) + "/vote"); };
CrApi.prototype.deleteIdea = async function (id: string) { await this.request("DELETE", "ideas/" + encodeURIComponent(id)); };
