import { createContext, useContext, type ReactNode } from "react";

/**
 * The navigation standard (fix list 27 Nov 2026, items 3d and 3e, board B1 picked): a page's title is the name of the
 * menu entry that opened it, and when that entry holds several pages they are its one tab row, right under the header.
 * The console fills this in for the page on screen; each page draws <PageTitle> in its header and <PageTabs /> after it.
 */
export type PageFrameValue = { entry: string | null; tabs: ReactNode };
export const PageFrame = createContext<PageFrameValue>({ entry: null, tabs: null });

/** The page's h1: its menu entry's name inside the console, else the page's own name (Studio, tests, a page shown elsewhere). */
export function PageTitle({ id, name }: { id?: string; name: string }) {
  const { entry } = useContext(PageFrame);
  return <h1 id={id}>{entry ?? name}</h1>;
}

/** The menu entry's pages as the page's one tab row (nothing for an entry of one page). */
export function PageTabs() {
  return <>{useContext(PageFrame).tabs}</>;
}
