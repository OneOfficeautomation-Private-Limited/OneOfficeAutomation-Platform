import type { ComponentType, ReactNode } from "react";
import type { CrApi, CrUser } from "../api/cr";

/** What the console gives a module's page. */
export type PageProps = { api: CrApi; user: CrUser; onNotice: (text: string) => void; setPage: (id: string) => void; studio: boolean };
/** A module's console pages, by menu page id, and parts that kernel pages show when the module is there. */
export type UiModulePages = {
  pages?: Record<string, (p: PageProps) => ReactNode>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  parts?: Record<string, ComponentType<any>>;
};

const found = import.meta.glob<{ ui: UiModulePages }>("./*/pages.tsx", { eager: true });
const all = Object.values(found).map((m) => m.ui);

/** Every module page, by menu page id (the console's registry; modules/registry.ts has their menu entries). */
export const MODULE_PAGES: Record<string, (p: PageProps) => ReactNode> = Object.assign({}, ...all.map((u) => u.pages ?? {}));

/** A part a module offers (runner-management's "pool-scaling" dialog, low-code's "embed-app"), or undefined without it. */
export function modulePart<P>(name: string): ComponentType<P> | undefined {
  for (const u of all) if (u.parts?.[name]) return u.parts[name] as ComponentType<P>;
  return undefined;
}
