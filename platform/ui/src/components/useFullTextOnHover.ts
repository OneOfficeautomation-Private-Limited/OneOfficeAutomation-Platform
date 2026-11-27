import { useEffect } from "react";

/**
 * Improvement list 13 Nov 2026, item 11: a table cell, heading or label whose text is cut off with "…" shows its whole
 * text on hover. One listener for the page: when the pointer comes onto a cut-off element without a title of its own,
 * the element gets its text as its title.
 */
export function useFullTextOnHover() {
  useEffect(() => {
    const on = (e: MouseEvent) => {
      const el = ((e.target as HTMLElement | null)?.closest?.("td, th, .cc-clip, .cc-hbar-label, .cc-sum span, .st-menu-entry-label") as HTMLElement | null)
        ?? shortenedAncestor(e.target as HTMLElement | null);
      if (!el || el.hasAttribute("title")) return;
      if (el.scrollWidth > el.clientWidth + 1) el.setAttribute("title", (el.innerText || el.textContent || "").trim());
    };
    document.addEventListener("mouseover", on);
    return () => document.removeEventListener("mouseover", on);
  }, []);
}

/** Layout fixes 27 Nov, item 5: any other element the layout shortens with "…" (the pointer's element or one of its three
 *  nearest parents), on every page and every module. */
function shortenedAncestor(start: HTMLElement | null): HTMLElement | null {
  for (let el = start, i = 0; el && i < 4; el = el.parentElement, i++) {
    if (getComputedStyle(el).textOverflow === "ellipsis") return el;
  }
  return null;
}
