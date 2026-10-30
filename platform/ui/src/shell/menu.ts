import type { IconName } from "../components";

/**
 * The Command Center menu (V6.1), shared by Studio and the console so the two never differ: Home, then five sections
 * by task. Page ids are the console's (`/?page=<id>` opens one in today's console); `perm` is the permission a page
 * needs (several joined by "|": any of them; "team_lead" means the person leads a team), as today's console checks.
 */
export type MenuPage = { id: string; label: string; perm?: string };
export type MenuSection = { id: string; label: string; color: string; icon: IconName; pages: MenuPage[] };

export const MENU_SECTIONS: MenuSection[] = [
  { id: "build", label: "BUILD", color: "#2f6fde", icon: "folder", pages: [
    { id: "automation", label: "Agent repository", perm: "repo.view" }, { id: "templates", label: "Templates", perm: "repo.view" },
    { id: "studio", label: "One Agent Studio" }, { id: "apitasks", label: "API tasks", perm: "apitasks.manage" }, { id: "tests", label: "Agent tests", perm: "repo.view" }] },
  { id: "manage", label: "MANAGE", color: "#0f766e", icon: "pulse", pages: [
    { id: "activity", label: "Agent Runs", perm: "runs.view" }, { id: "inbox", label: "Task inbox" }, { id: "devices", label: "Runners", perm: "devices.view" },
    { id: "schedules", label: "Schedules", perm: "schedules.view" }, { id: "triggers", label: "Triggers", perm: "triggers.view" },
    { id: "queues", label: "Work queues", perm: "queues.view" }, { id: "workflows", label: "One Workflow", perm: "runs.view" },
    { id: "globals", label: "Shared values", perm: "globals.view" }, { id: "savedconn", label: "Connections", perm: "globals.view" },
    { id: "toolkits", label: "Toolkits", perm: "toolkits.manage" }] },
  { id: "insights", label: "INSIGHTS", color: "#0e7490", icon: "sliders", pages: [
    { id: "operations", label: "Operations", perm: "analytics.view" }, { id: "dashboards", label: "Dashboards", perm: "analytics.view" }] },
  { id: "administration", label: "ADMINISTRATION", color: "#475569", icon: "gear", pages: [
    { id: "users", label: "Users", perm: "users.manage" }, { id: "roles", label: "Roles", perm: "users.manage" },
    { id: "teams", label: "Teams", perm: "teams.manage|team_lead" }, { id: "credentials", label: "Agent credential vault" },
    { id: "audit", label: "One Audit Trail", perm: "audit.view" }, { id: "licenses", label: "Licenses", perm: "users.manage|settings.manage" },
    { id: "settings", label: "Settings", perm: "settings.manage" }] },
  { id: "ai", label: "AI", color: "#7c3aed", icon: "spark", pages: [
    { id: "connections", label: "One Model Connections", perm: "connections.manage" }, { id: "skills", label: "Prompt Skills", perm: "skills.view" },
    { id: "docautomation", label: "Document Automation", perm: "repo.view" },
    { id: "governance", label: "AI governance", perm: "audit.view" }] },
];

/** What a signed-in person may see; Studio, which has no sign-in of its own, shows every page. */
export type MenuViewer = { permissions: string[]; leadsTeam?: boolean } | null;

export function canSee(page: MenuPage, viewer: MenuViewer): boolean {
  if (!viewer || !page.perm) return true;
  return page.perm.split("|").some((p) => p === "team_lead" ? !!viewer.leadsTeam : viewer.permissions.includes(p));
}
