import type { IconName } from "../components";
import { UI_MODULES, withModules } from "../modules/registry";

/**
 * One CommandCenter menu (V6.1), shared by Studio and the console so the two never differ: Home, then five sections
 * by task. Page ids are the console's (`/next/#/<id>` opens one); `perm` is the permission a page
 * needs (several joined by "|": any of them; "team_lead" means the person leads a team), as today's console checks.
 */
export type MenuPage = { id: string; label: string; perm?: string };
export type MenuSection = { id: string; label: string; color: string; icon: IconName; pages: MenuPage[] };

/** The kernel's own pages; feature modules add theirs (modules/<id>/module.ts). */
export const KERNEL_SECTIONS: MenuSection[] = [
  { id: "build", label: "BUILD", color: "#2f6fde", icon: "folder", pages: [
    { id: "automation", label: "Automation", perm: "repo.view" }, { id: "templates", label: "Agent Templates", perm: "repo.view" }] },
  { id: "manage", label: "MANAGE", color: "#0f766e", icon: "pulse", pages: [
    { id: "activity", label: "Agent Activity", perm: "runs.view" }, { id: "inbox", label: "Task inbox" }, { id: "devices", label: "Runner Machines", perm: "devices.view" },
    { id: "locks", label: "Environment locks", perm: "devices.view" },
    { id: "schedules", label: "Schedules", perm: "schedules.view" }, { id: "triggers", label: "Triggers", perm: "triggers.view" },
    { id: "queues", label: "Work queues", perm: "queues.view" },
    { id: "globals", label: "Shared Global values", perm: "globals.view" }, { id: "savedconn", label: "Connections", perm: "globals.view" },
    { id: "credentials", label: "Credential vault" }, { id: "toolkits", label: "Agent ToolKits", perm: "toolkits.manage" }] },
  { id: "insights", label: "OPERATIONAL INSIGHTS", color: "#0e7490", icon: "sliders", pages: [] },
  { id: "administration", label: "ADMINISTRATION", color: "#475569", icon: "gear", pages: [
    { id: "users", label: "Users", perm: "users.manage" }, { id: "roles", label: "Roles", perm: "users.manage" },
    { id: "teams", label: "Teams", perm: "teams.manage|team_lead" },
    { id: "audit", label: "Audit Trail", perm: "audit.view" }, { id: "events", label: "Event subscriptions", perm: "events.manage" },
    { id: "licenses", label: "Licenses", perm: "users.manage|settings.manage" },
    { id: "settings", label: "Settings", perm: "settings.manage" }] },
  { id: "ai", label: "AI", color: "#7c3aed", icon: "spark", pages: [
    { id: "connections", label: "Model Connections", perm: "connections.manage" }] },
];

/** The menu: the kernel's sections with the pages of every module found (modules/registry.ts). */
export const MENU_SECTIONS: MenuSection[] = withModules(KERNEL_SECTIONS, UI_MODULES);

/**
 * The menu's entries (option B of the console usability canvas, chosen 8 Nov 2026): 17 entries in five sections, all
 * open, each a group of related pages shown as tabs of one page (Scheduling: Schedules, Triggers, Upcoming runs). Every
 * page keeps its own address; an entry opens the first of its pages the person may see. A page in no group (a module
 * added later) is an entry of its own in its section.
 */
export type MenuGroup = { id: string; label: string; icon: IconName; section: string; pages: string[] };
export const MENU_GROUPS: MenuGroup[] = [
  { id: "g-automation", label: "Automation", icon: "folder", section: "build", pages: ["automation", "templates", "apitasks", "tests", "toolkits"] },
  // improvement list 12 Nov 2026 (items 4, 7, 9): Ideas and Apps, Operations and Business, and every Administration page
  // are entries of their own; Manage and AI keep their groups
  { id: "g-ideas", label: "Ideas", icon: "idea", section: "build", pages: ["ideas"] },
  { id: "g-apps", label: "Apps", icon: "grid", section: "build", pages: ["apps"] },
  { id: "g-activity", label: "Activity", icon: "pulse", section: "manage", pages: ["activity", "inbox"] },
  { id: "g-runners", label: "Runners", icon: "machine", section: "manage", pages: ["devices", "locks", "runner-status"] },
  { id: "g-scheduling", label: "Scheduling", icon: "clock", section: "manage", pages: ["schedules", "triggers", "upcoming"] },
  { id: "g-queues", label: "Work queues", icon: "queue", section: "manage", pages: ["queues", "workflows"] },
  { id: "g-data", label: "Shared data", icon: "db", section: "manage", pages: ["globals", "data", "savedconn"] },
  { id: "g-credentials", label: "Credentials", icon: "lock", section: "manage", pages: ["credentials"] },
  { id: "g-overview", label: "Overview", icon: "chart", section: "insights", pages: ["insights"] },
  { id: "g-alerts", label: "Alerts", icon: "bell", section: "insights", pages: ["alerts", "alert-rules"] },
  { id: "g-operations", label: "Operations", icon: "sliders", section: "insights", pages: ["operations"] },
  { id: "g-business", label: "Business", icon: "target", section: "insights", pages: ["business"] },
  { id: "g-users", label: "Users", icon: "person", section: "administration", pages: ["users"] },
  { id: "g-roles", label: "Roles", icon: "people", section: "administration", pages: ["roles"] },
  { id: "g-teams", label: "Teams", icon: "people", section: "administration", pages: ["teams"] },
  { id: "g-settings", label: "Settings", icon: "gear", section: "administration", pages: ["settings"] },
  { id: "g-policies", label: "Policies", icon: "lock", section: "administration", pages: ["policies"] },
  { id: "g-audit", label: "Audit trail", icon: "search", section: "administration", pages: ["audit"] },
  { id: "g-events", label: "Event subscriptions", icon: "bell", section: "administration", pages: ["events"] },
  { id: "g-licenses", label: "Licenses", icon: "star", section: "administration", pages: ["licenses"] },
  { id: "g-chat", label: "One Chat", icon: "chat", section: "ai", pages: ["chat"] },
  { id: "g-ai", label: "AI setup", icon: "spark", section: "ai", pages: ["connections", "skills", "knowledge", "governance"] },
  { id: "g-docs", label: "Document Automation", icon: "doc", section: "ai", pages: ["docautomation"] },
];
/** The group a page is in, or undefined. */
export const groupOf = (page: string) => MENU_GROUPS.find((g) => g.pages.includes(page));

/** What a signed-in person may see; Studio, which has no sign-in of its own, shows every page. */
export type MenuViewer = { permissions: string[]; leadsTeam?: boolean } | null;

export function canSee(page: MenuPage, viewer: MenuViewer): boolean {
  if (!viewer || !page.perm) return true;
  return page.perm.split("|").some((p) => p === "team_lead" ? !!viewer.leadsTeam : viewer.permissions.includes(p));
}
