import { useEffect, useRef, useState } from "react";
import { Button, IconButton } from "../../components";
import type { Box, ReviewPage } from "../api";

/** A value's box on the page. key: the field id, or "<table>#<row>" for a table row. */
export interface PageBox { key: string; label: string; bbox: Box; flag?: boolean; row?: boolean }

/**
 * The document page as an image, with a box over each value read from it. Only the page shown is in the page (the
 * image loads lazily), so long documents stay fast. Zoom widens the page (100% fits the width); the chosen box is
 * scrolled into view; clicking a box chooses its field.
 */
export function PageView({ name, pages, index, onIndex, boxes, selected, onSelect, trail }:
  { name: string; pages: ReviewPage[]; index: number; onIndex: (i: number) => void; boxes: PageBox[]; selected: string | null;
    onSelect: (key: string) => void; trail: string }) {
  const [zoom, setZoom] = useState(100);
  const [done, setDone] = useState<{ src: string; ok: boolean } | null>(null);   // the image that finished loading, and how
  const scroller = useRef<HTMLDivElement>(null);
  const page = pages[index];
  const loaded = done?.src !== page.image ? "wait" : done.ok ? "ok" : "bad";
  useEffect(() => {
    if (!selected) return;
    // only the page pane scrolls, so the field being typed in stays where it is
    const s = scroller.current;
    const el = [...(s?.querySelectorAll<HTMLElement>("[data-box]") ?? [])].find((x) => x.dataset.box === selected);
    if (!s || !el) return;
    const r = el.getBoundingClientRect(), v = s.getBoundingClientRect();
    s.scrollTo?.({ top: s.scrollTop + r.top - v.top - (v.height - r.height) / 2, left: s.scrollLeft + r.left - v.left - (v.width - r.width) / 2, behavior: "smooth" });
  }, [selected, index, zoom]);

  return (
    <>
      <div className="df-review-tools">
        <Button kind="plain" aria-label="Zoom out" onClick={() => setZoom(Math.max(50, zoom - 10))}>−</Button>
        <span className="df-num" style={{ minWidth: 44, textAlign: "center" }} aria-live="polite">{zoom}%</span>
        <IconButton icon="plus" label="Zoom in" onClick={() => setZoom(Math.min(300, zoom + 10))} />
        <Button kind="plain" onClick={() => setZoom(100)}>Fit width</Button>
        <span className="df-tool-sep" aria-hidden="true" />
        <IconButton icon="chevronLeft" label="Previous page" disabled={index === 0} onClick={() => onIndex(index - 1)} />
        <span className="df-num" aria-live="polite">Page {index + 1} of {pages.length}</span>
        <IconButton icon="chevronRight" label="Next page" disabled={index >= pages.length - 1} onClick={() => onIndex(index + 1)} />
        <span className="df-grow" />
        <span className="df-muted">{trail}</span>
      </div>
      <div className="df-page-view" ref={scroller}>
        <div className="df-sheet" style={{ width: `${zoom}%`, aspectRatio: page.width && page.height ? `${page.width} / ${page.height}` : undefined }}>
          {loaded !== "ok" && <div className="df-sheet-wait" role="status">{loaded === "bad" ? "The page image could not be loaded." : "Loading page…"}</div>}
          <img key={page.image} src={page.image} alt={`Page ${index + 1} of ${name}`} loading="lazy" decoding="async"
            onLoad={() => setDone({ src: page.image, ok: true })} onError={() => setDone({ src: page.image, ok: false })} />
          {boxes.map((b) => (
            <button key={b.key} type="button" data-box={b.key} aria-label={b.label + " on the page"} aria-pressed={b.key === selected}
              className={`df-box${b.flag ? " is-flag" : ""}${b.row ? " is-row" : ""}${b.key === selected ? " is-selected" : ""}`}
              style={{ left: pct(b.bbox.x), top: pct(b.bbox.y), width: pct(b.bbox.w), height: pct(b.bbox.h) }}
              title={b.label} onClick={() => onSelect(b.key)} />
          ))}
        </div>
      </div>
    </>
  );
}

const pct = (v: number) => `${Math.max(0, Math.min(100, v * 100))}%`;
