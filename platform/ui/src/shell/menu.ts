import type { IconName } from "../components";
import { UI_MODULES, withModules } from "../modules/registry";

/**
 * One CommandCenter menu, shared by Studio and the console so the two never differ. Since 26 Nov 2026 (design S2 on the
 * canvas): no Home; five sections by the life of an automation: Setup (the install guide), Build, Run, Manage (all
 * administration) and Monitor (dashboards and analytics). Page ids are the console's (`/next/#/<id>` opens one) and
 * did not change, so old links work; `perm` is the permission a page needs (several joined by "|": any of them;
 * "team_lead" means the person leads a team), as today's console checks.
 */
export type MenuPage = { id: string; label: string; perm?: string };
export type MenuSection = { id: string; label: string; color: string; icon: IconName; pages: MenuPage[] };

/** The kernel's own pages; feature modules add theirs (modules/<id>/module.ts). */
export const KERNEL_SECTIONS: MenuSection[] = [
  { id: "setup", label: "Setup", color: "#b45309", icon: "power", pages: [
    { id: "setup", label: "Install and set up", perm: "settings.manage" }] },
  { id: "build", label: "Build", color: "#2f6fde", icon: "folder", pages: [
    { id: "automation", label: "Agents", perm: "repo.view" }, { id: "templates", label: "Agent Templates", perm: "repo.view" }] },
  { id: "run", label: "Run", color: "#0f766e", icon: "play", pages: [
    { id: "activity", label: "Agent Activity", perm: "runs.view" },
    { id: "schedules", label: "Schedules", perm: "schedules.view" }, { id: "triggers", label: "Triggers", perm: "triggers.view" },
    { id: "queues", label: "Work queues", perm: "queues.view" }, { id: "inbox", label: "Task inbox" },
    { id: "devices", label: "Runner Machines", perm: "devices.view" }, { id: "locks", label: "Environment locks", perm: "devices.view" }] },
  { id: "manage", label: "Manage", color: "#475569", icon: "gear", pages: [
    { id: "users", label: "Users", perm: "users.manage" }, { id: "roles", label: "Roles", perm: "users.manage" },
    { id: "teams", label: "Teams", perm: "teams.manage|team_lead" },
    { id: "credentials", label: "Credential vault" }, { id: "audit", label: "Audit Trail", perm: "audit.view" },
    { id: "savedconn", label: "Connections", perm: "globals.view" }, { id: "connections", label: "Model Connections", perm: "connections.manage" },
    { id: "globals", label: "Shared Global values", perm: "globals.view" },
    { id: "toolkits", label: "Agent ToolKits", perm: "toolkits.manage" },
    { id: "settings", label: "Settings", perm: "settings.manage" }, { id: "licenses", label: "Licenses", perm: "users.manage|settings.manage" },
    { id: "events", label: "Event subscriptions", perm: "events.manage" }] },
  { id: "monitor", label: "Monitor", color: "#0e7490", icon: "chart", pages: [
    { id: "status", label: "Status" }] },
];

/** The menu: the kernel's sections with the pages of every module found (modules/registry.ts). */
export const MENU_SECTIONS: MenuSection[] = withModules(KERNEL_SECTIONS, UI_MODULES);

/** Where a person lands after signing in, or on an address with no page: Build > Agents, else the first page they may see. */
export const FIRST_PAGE = "automation";

/**
 * The menu's entries: one per group of related pages, shown as tabs of one page (Scheduling: Schedules, Triggers,
 * Upcoming runs). Every page keeps its own address; an entry opens the first of its pages the person may see. A page
 * in no group (a module added later) is an entry of its own in its section.
 */
export type MenuGroup = { id: string; label: string; icon: IconName; section: string; pages: string[] };
export const MENU_GROUPS: MenuGroup[] = [
  { id: "g-setup", label: "Install and set up", icon: "clipboard", section: "setup", pages: ["setup"] },
  { id: "g-automation", label: "Agents", icon: "folder", section: "build", pages: ["automation", "templates", "tests", "apitasks"] },
  { id: "g-docs", label: "Document Automation", icon: "doc", section: "build", pages: ["docautomation"] },
  { id: "g-workflows", label: "Workflows", icon: "process", section: "build", pages: ["workflows"] },
  { id: "g-apps", label: "Apps", icon: "grid", section: "build", pages: ["apps"] },
  { id: "g-ai-blocks", label: "AI building blocks", icon: "spark", section: "build", pages: ["skills", "knowledge"] },
  { id: "g-ideas", label: "Ideas", icon: "idea", section: "build", pages: ["ideas"] },
  { id: "g-activity", label: "Agent Activity", icon: "pulse", section: "run", pages: ["activity"] },
  { id: "g-scheduling", label: "Scheduling", icon: "clock", section: "run", pages: ["schedules", "triggers", "upcoming"] },
  { id: "g-queues", label: "Work queues", icon: "queue", section: "run", pages: ["queues"] },
  { id: "g-inbox", label: "Task inbox", icon: "list", section: "run", pages: ["inbox"] },
  { id: "g-runners", label: "Runners", icon: "machine", section: "run", pages: ["devices", "locks"] },
  { id: "g-chat", label: "One Chat", icon: "chat", section: "run", pages: ["chat"] },
  { id: "g-people", label: "People", icon: "people", section: "manage", pages: ["users", "roles", "teams"] },
  { id: "g-security", label: "Security", icon: "shield", section: "manage", pages: ["credentials", "policies", "audit"] },
  { id: "g-connections", label: "Connections", icon: "globe", section: "manage", pages: ["savedconn", "connections"] },
  { id: "g-data", label: "Shared data", icon: "db", section: "manage", pages: ["globals", "data"] },
  { id: "g-toolkits", label: "Toolkits", icon: "sliders", section: "manage", pages: ["toolkits"] },
  { id: "g-platform", label: "Platform", icon: "gear", section: "manage", pages: ["settings", "licenses", "events"] },
  { id: "g-overview", label: "Overview", icon: "chart", section: "monitor", pages: ["status", "insights"] },
  { id: "g-operations", label: "Operations", icon: "sliders", section: "monitor", pages: ["operations", "runner-status"] },
  { id: "g-business", label: "Business", icon: "target", section: "monitor", pages: ["business"] },
  { id: "g-alerts", label: "Alerts", icon: "bell", section: "monitor", pages: ["alerts", "alert-rules"] },
  { id: "g-ai-usage", label: "AI usage", icon: "stars", section: "monitor", pages: ["governance"] },
];
/** The group a page is in, or undefined. */
export const groupOf = (page: string) => MENU_GROUPS.find((g) => g.pages.includes(page));

/** What a signed-in person may see; Studio, which has no sign-in of its own, shows every page. */
export type MenuViewer = { permissions: string[]; leadsTeam?: boolean } | null;

export function canSee(page: MenuPage, viewer: MenuViewer): boolean {
  if (!viewer || !page.perm) return true;
  return page.perm.split("|").some((p) => p === "team_lead" ? !!viewer.leadsTeam : viewer.permissions.includes(p));
}
