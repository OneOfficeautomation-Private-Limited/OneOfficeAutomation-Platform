import { useSyncExternalStore } from "react";
import { setErrorLinkResolver } from "../components/errorLink";

/**
 * Help links (13 Nov 2026 round 3, item 6): every Docs and Video link in the product comes from here, by a key, so a
 * link is changed in one place and never inside a page.
 *
 * Keys: `page:<page id>` (a console page, as #/<id>), `studio:agents-public`, `studio:agents-private`,
 * `docs:<tab>` (Document Automation), `dialog:<name>`, `setting:<section>`, `item:<kind>` (what Create makes),
 * `command:<toolkit>.<action>` (an agent command) and `error:<CODE>`.
 *
 * How an address is made:
 * 1. A link set for the key in OVERRIDES below, or in the server's `<data>/config/help-links.json` (read on each start
 *    of a page; changes there need no rebuild), is used as it is.
 * 2. Otherwise the rule builds it: the docs address + version + the key as a path, for example
 *    `https://docs.oneofficeautomation.com/0.2.0/pages/activity` or `.../commands/excel/open-workbook`; the video
 *    address + the same path. Every key therefore has both links, and a new page or command gets them on its own.
 * The addresses are placeholders until the documentation and video sites exist (the user's choice on 13 Nov 2026:
 * show the links now, change them later). Settings > Help links (help_docs_base, help_video_base) points them at a
 * company's own copy, for servers with no internet. How to update: docs/architecture/help-links.md.
 */
export const DEFAULT_DOCS_BASE = "https://docs.oneofficeautomation.com/";
export const DEFAULT_VIDEO_BASE = "https://videos.oneofficeautomation.com/";

export type HelpLink = { doc?: string; video?: string };
/** Links set by hand for a key, where the rule's address is not the right one. */
export const OVERRIDES: Record<string, HelpLink> = {};

type Config = { version: string; docsBase: string; videoBase: string; links: Record<string, HelpLink> };
let config: Config = { version: "latest", docsBase: DEFAULT_DOCS_BASE, videoBase: DEFAULT_VIDEO_BASE, links: {} };
const subscribers = new Set<() => void>();
const withSlash = (s: string) => s.endsWith("/") ? s : s + "/";

/** What the server says (GET /api/help-links): its version, its addresses (empty: ours) and the links set there. */
export function setHelpConfig(c: { version?: string; docs_base?: string; video_base?: string; links?: Record<string, HelpLink> }) {
  config = {
    version: c.version || config.version,
    docsBase: c.docs_base ? withSlash(c.docs_base) : DEFAULT_DOCS_BASE,
    videoBase: c.video_base ? withSlash(c.video_base) : DEFAULT_VIDEO_BASE,
    links: c.links ?? {},
  };
  subscribers.forEach((f) => f());
}
/** Asks the server once for its help links; without an answer (Studio on its own) the rule's addresses are used. */
let loaded = false;
export function loadHelpConfig(base = "/api/") {
  if (loaded || typeof fetch !== "function" || import.meta.env.MODE === "test") return;
  loaded = true;
  fetch(base + "help-links", { credentials: "same-origin" }).then((r) => r.ok ? r.json() : null).then((c) => { if (c) setHelpConfig(c); }, () => {});
}

/** A key's path under the docs and video addresses: "page:activity" → "pages/activity"; "command:excel.open-workbook" → "commands/excel/open-workbook". */
export function helpPath(key: string): string {
  const [kind, ...rest] = key.split(":");
  const name = rest.join(":");
  const slug = (s: string) => s.trim().toLowerCase().replace(/[^a-z0-9.]+/g, "-").replace(/^-|-$/g, "");
  if (kind === "command") return "commands/" + name.split(".").map(slug).join("/");
  return kind + "s/" + slug(name).replace(/\./g, "-");
}

/** The Docs and Video addresses for a key: a set link first, else the rule's. */
export function helpLinks(key: string): HelpLink {
  const set = { ...OVERRIDES[key], ...config.links[key] };
  const path = helpPath(key);
  return { doc: set.doc ?? config.docsBase + config.version + "/" + path, video: set.video ?? config.videoBase + path };
}

/** The same, for a component: it updates when the server's answer arrives. */
export function useHelpLinks(key: string): HelpLink {
  useSyncExternalStore((f) => { subscribers.add(f); return () => { subscribers.delete(f); }; }, () => config);
  return helpLinks(key);
}

// errors' Learn more (PageError, the run panel) points at the error's page: …/errors/run-input-missing
setErrorLinkResolver((code) => helpLinks("error:" + code).doc);

/** A command's key, from its action name ("excel.openWorkbook" → "command:excel.open-workbook"). */
export const commandKey = (action: string) => "command:" + action.replace("/", ".").replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();
