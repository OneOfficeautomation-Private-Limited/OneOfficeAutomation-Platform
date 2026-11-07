import { CrApi } from "../../api/cr";

/**
 * ai-services's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
export type KnowledgeBase = { id: string; name: string; folder: string; description?: string; passages: number; documents: number; bytes: number;
  created: string; created_by: string; updated?: string; updated_by_agent?: string; updated_by_run?: string };
export type PromptSkill = { name: string; description?: string | null; owner: string; shared: boolean; template?: boolean; version: number; updated?: string; updated_by?: string };
export type SkillDefinition = { prompt: string; system?: string; output: "text" | "json"; temperature: number; connection?: string; inputs: { name: string; description?: string }[];
  knowledge?: { index: string; top_k?: number; query?: string } };

declare module "../../api/cr" {
  interface CrApi {
    knowledgeBases(): Promise<KnowledgeBase[]>;
    saveKnowledgeBase(id: string | null, body: {
      name: string;
      folder: string;
      description: string;
    }): Promise<KnowledgeBase>;
    deleteKnowledgeBase(id: string): Promise<void>;
    skills(): Promise<PromptSkill[]>;
    skill(name: string): Promise<PromptSkill & {
      definition: SkillDefinition;
      versions?: {
        version: number;
        updated_by?: string;
        updated?: string;
        comment?: string | null;
      }[];
    }>;
    saveSkill(name: string, b: {
      description: string;
      definition: SkillDefinition;
      shared: boolean;
      template: boolean;
      comment: string | null;
    }): Promise<{
      name: string;
      version: number;
    }>;
    deleteSkill(name: string): Promise<void>;
    skillTemplates(): Promise<{
      name: string;
      description?: string;
    }[]>;
    skillFromTemplate(template: string, name: string): Promise<{
      name: string;
    }>;
  }
}

CrApi.prototype.knowledgeBases = async function () { return (await this.request<{ knowledge_bases?: KnowledgeBase[] }>("GET", "knowledge-bases")).knowledge_bases ?? []; };
CrApi.prototype.saveKnowledgeBase = function (id: string | null, body: { name: string; folder: string; description: string }) {
  return this.request<KnowledgeBase>(id ? "PUT" : "POST", "knowledge-bases" + (id ? "/" + encodeURIComponent(id) : ""), body); };
CrApi.prototype.deleteKnowledgeBase = async function (id: string) { await this.request("DELETE", "knowledge-bases/" + encodeURIComponent(id)); };
// ---- Prompt Skills (reviewed prompt templates, versioned) and AI governance (every AI call) ----

CrApi.prototype.skills = async function () { return (await this.request<{ skills?: PromptSkill[] }>("GET", "skills")).skills ?? []; };
CrApi.prototype.skill = function (name: string) { return this.request<PromptSkill & { definition: SkillDefinition; versions?: { version: number; updated_by?: string; updated?: string; comment?: string | null }[] }>("GET", "skills/" + encodeURIComponent(name)); };
CrApi.prototype.saveSkill = function (name: string, b: { description: string; definition: SkillDefinition; shared: boolean; template: boolean; comment: string | null }) { return this.request<{ name: string; version: number }>("PUT", "skills/" + encodeURIComponent(name), b); };
CrApi.prototype.deleteSkill = async function (name: string) { await this.request("DELETE", "skills/" + encodeURIComponent(name)); };
CrApi.prototype.skillTemplates = async function () { return (await this.request<{ templates?: { name: string; description?: string }[] }>("GET", "skills/templates")).templates ?? []; };
CrApi.prototype.skillFromTemplate = function (template: string, name: string) { return this.request<{ name: string }>("POST", "skills/from-template", { template, name }); };
