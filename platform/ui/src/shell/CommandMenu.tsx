import { useEffect, useState } from "react";
import { Icon, IconTile, Splitter, usePanelSize, usePersistent } from "../components";
import { canSee, type MenuGroup, type MenuPage, type MenuSection, type MenuViewer } from "./menu";

/**
 * One CommandCenter menu (approved look), used by Studio and the console: Home, then the sections. A page the app
 * shows itself (`local`) is a button; any other page is a link (`hrefFor`), or plain text when there is nowhere to go.
 * The whole menu folds to a column of icons, and each section opens and closes on its heading; both are remembered.
 * While something is being edited (`editing`) the menu folds, as AA's editor hides the Control Room menu, so the work
 * keeps its room; it can still be opened, and the person's own choice for the other pages is left as it was.
 * In the console (`console`, the design plan's step 1): a box to find a page, only the current section open (opening
 * another closes it), Collapse at the bottom, and its width set by dragging its edge or with Narrower and Wider (200 to
 * 360 px, 232 by default; remembered). Studio uses the same look since 1 Nov 2026; Favourites was taken out that day.
 * With `groups` (the console, option B, 8 Nov 2026): every section open, one entry per group of pages with its icon;
 * an entry opens the first of its pages the person may see and is current on any of them (the page shows the others
 * as tabs); a page in no group is an entry of its own.
 */
type Entry = { id: string; label: string; icon?: MenuGroup["icon"]; first: MenuPage; pages: string[] };
const WIDTH = { min: 200, max: 360, normal: 232, step: 32 };
export function CommandMenu({ sections, current, local, onLocal, hrefFor, home, viewer = null, editing = false, offTitle, console: inConsole = false, groups }:
  { sections: MenuSection[]; current: string | null; local: ReadonlySet<string>; onLocal: (id: string) => void;
    hrefFor: (id: string) => string | undefined; home: { href?: string; onClick?: () => void; current?: boolean };
    viewer?: MenuViewer; editing?: boolean; offTitle?: string; console?: boolean; groups?: MenuGroup[] }) {
  const [chosenMini, setChosenMini] = usePersistent("menu.mini", false);
  const [openWhileEditing, setOpenWhileEditing] = useState(false);
  useEffect(() => { if (!editing) setOpenWhileEditing(false); }, [editing]);
  // on a phone (G69) the menu starts folded and opens over the page, folding again once a page is picked
  const narrow = useNarrow();
  const [openNarrow, setOpenNarrow] = useState(false);
  const mini = narrow ? !openNarrow : editing ? !openWhileEditing : chosenMini;
  const setMini = (m: boolean) => { if (narrow) setOpenNarrow(!m); else if (editing) setOpenWhileEditing(!m); else setChosenMini(m); };
  const [closed, setClosed] = usePersistent<string[]>("menu.closed", []);
  const toggleSection = (label: string) => setClosed(closed.includes(label) ? closed.filter((x) => x !== label) : [...closed, label]);
  const shown = sections.map((s) => ({ ...s, pages: s.pages.filter((p) => canSee(p, viewer)) })).filter((s) => s.pages.length);
  // the console opens one section at a time: the current page's, or the one the person opened last
  const sectionOf = (id: string | null) => shown.find((s) => s.pages.some((p) => p.id === id))?.label ?? null;
  const [openOne, setOpenOne] = useState<string | null>(() => sectionOf(current));
  useEffect(() => { const s = sectionOf(current); if (s) setOpenOne(s); }, [current]);   // eslint-disable-line react-hooks/exhaustive-deps
  const isOpen = (label: string) => groups ? true : inConsole ? openOne === label : !closed.includes(label);
  // the entries of a section: its groups with a page the person may see (a page goes where its group is), then its pages in no group
  const visible = new Map(shown.flatMap((s) => s.pages.map((p) => [p.id, p] as const)));
  const entriesOf = (sec: MenuSection): Entry[] => {
    if (!groups) return sec.pages.map((p) => ({ id: p.id, label: p.label, first: p, pages: [p.id] }));
    const grouped: Entry[] = groups.filter((g) => g.section === sec.id).flatMap((g) => {
      const pages = g.pages.filter((id) => visible.has(id));
      return pages.length ? [{ id: g.id, label: g.label, icon: g.icon, first: visible.get(pages[0])!, pages }] : [];
    });
    const loose = sec.pages.filter((p) => !groups.some((g) => g.pages.includes(p.id))).map((p) => ({ id: p.id, label: p.label, first: p, pages: [p.id] }));
    return [...grouped, ...loose];
  };
  const sectionsShown = groups ? sections.filter((s) => entriesOf(s).length) : shown;
  const toggle = (label: string) => { if (inConsole) setOpenOne(openOne === label ? null : label); else toggleSection(label); };
  const [width, setWidth] = usePanelSize("console.menu");
  const w = width ?? WIDTH.normal;
  const resize = (by: number) => setWidth(Math.max(WIDTH.min, Math.min(WIDTH.max, w + by)));
  const [find, setFind] = useState("");
  const query = find.trim().toLowerCase();

  const homeBody = <><Icon name="home" size={18} />{!mini && "Home"}</>;
  const homeEl = home.onClick
    ? <button type="button" className={`st-menu-home${home.current ? " is-current" : ""}`} aria-current={home.current ? "page" : undefined} title="Home" onClick={home.onClick}>{homeBody}</button>
    : home.href ? <a className="st-menu-home" href={home.href} title="Home">{homeBody}</a>
    : <span className="st-menu-home" aria-disabled="true" title={offTitle}>{homeBody}</span>;
  const fold = (
    <button type="button" className="st-menu-fold" aria-expanded={!mini} onClick={() => setMini(!mini)}
      aria-label={mini ? "Expand menu" : "Collapse menu"} title={mini ? "Expand menu" : "Collapse menu"}>
      <Icon name={mini ? "chevronRight" : "chevronLeft"} size={14} stroke={2.6} />{!mini && "Collapse menu"}
    </button>
  );
  /** A group's entry: its icon and name; it opens its first page and is current on any of its pages. */
  const entry = (e: Entry) => {
    const isCurrent = !!current && e.pages.includes(current);
    const body = <>{e.icon && <Icon name={e.icon} size={18} />}<span className="st-menu-entry-label">{e.label}</span></>;
    if (local.has(e.first.id)) return <button type="button" className={`st-menu-item st-menu-entry${isCurrent ? " is-current" : ""}`} aria-current={isCurrent ? "page" : undefined}
      onClick={() => { setFind(""); setOpenNarrow(false); onLocal(e.first.id); }}>{body}</button>;
    const href = hrefFor(e.first.id);
    return href ? <a className={`st-menu-item st-menu-entry${isCurrent ? " is-current" : ""}`} href={href} aria-current={isCurrent ? "page" : undefined}>{body}</a>
      : <span className="st-menu-item st-menu-entry is-off" aria-disabled="true" title={offTitle}>{body}</span>;
  };
  const item = (it: MenuPage, extra?: string) => {
    if (local.has(it.id)) {
      const isCurrent = current === it.id;
      return <button type="button" className={`st-menu-item${isCurrent ? " is-current" : ""}`} aria-current={isCurrent ? "page" : undefined}
        onClick={() => { setFind(""); setOpenNarrow(false); onLocal(it.id); }}>{it.label}{extra && <span className="st-menu-where">{extra}</span>}</button>;
    }
    const href = hrefFor(it.id);
    const isCurrent = current === it.id;   // a page served apart (Document Automation) marks its own entry
    return href
      ? <a className={`st-menu-item${isCurrent ? " is-current" : ""}`} href={href} aria-current={isCurrent ? "page" : undefined}>{it.label}{extra && <span className="st-menu-where">{extra}</span>}</a>
      : <span className="st-menu-item is-off" aria-disabled="true" title={offTitle}>{it.label}</span>;
  };
  const found = query ? shown.flatMap((s) => s.pages.filter((p) => p.label.toLowerCase().includes(query)).map((p) => ({ p, sec: s.label }))) : [];

  return (<>
    <nav className={`st-menu${mini ? " is-mini" : ""}${inConsole ? " is-console" : ""}`} aria-label="One CommandCenter" style={inConsole && !mini ? { width: w } : undefined}>
      {!inConsole && fold}
      {inConsole && !mini && <input type="search" className="st-menu-find" aria-label="Find a page" placeholder="Find a page" value={find}
        onChange={(e) => setFind(e.target.value)} onKeyDown={(e) => { if (e.key === "Escape") setFind(""); }} />}
      {query && !mini ? (
        found.length ? <ul className="st-menu-items st-menu-found" aria-label="Pages found">{found.map(({ p, sec }) => <li key={p.id}>{item(p, sec)}</li>)}</ul>
          : <p className="st-menu-hint">No page matches.</p>
      ) : <>
        {homeEl}
        {sectionsShown.map((sec) => {
          const open = isOpen(sec.label);
          if (mini) {
            // folded: one icon per section; it opens the menu at that section
            return (
              <button key={sec.label} type="button" className="st-menu-mini" title={sec.label} aria-label={sec.label}
                onClick={() => { setMini(false); if (!open) toggle(sec.label); }}>
                <IconTile name={sec.icon} color={sec.color} size={26} />
              </button>
            );
          }
          if (groups) return (
            <div key={sec.label} className="st-menu-section is-grouped">
              <div className="st-menu-group-head"><IconTile name={sec.icon} color={sec.color} size={20} /><span className="st-menu-label">{sec.label}</span></div>
              <ul className="st-menu-items" aria-label={sec.label}>{entriesOf(sec).map((e) => <li key={e.id}>{entry(e)}</li>)}</ul>
            </div>
          );
          return (
            <div key={sec.label} className="st-menu-section">
              <button type="button" className="st-menu-heading" aria-expanded={open} title={sec.label} onClick={() => toggle(sec.label)}>
                <IconTile name={sec.icon} color={sec.color} size={20} /><span className="st-menu-label">{sec.label}</span>
                <span className="st-menu-caret"><Icon name={open ? "chevronDown" : "chevronRight"} size={12} stroke={2.6} /></span>
              </button>
              {open && <ul className="st-menu-items">{sec.pages.map((it) => <li key={it.id}>{item(it)}</li>)}</ul>}
            </div>
          );
        })}
      </>}
      {inConsole && <><span className="st-menu-fill" />
        <div className="st-menu-foot">{fold}{!mini && <span className="st-menu-size" role="group" aria-label="Menu width">
          <button type="button" aria-label="Narrower menu" title="Narrower" disabled={w <= WIDTH.min} onClick={() => resize(-WIDTH.step)}>−</button>
          <button type="button" aria-label="Wider menu" title="Wider" disabled={w >= WIDTH.max} onClick={() => resize(WIDTH.step)}>+</button>
        </span>}</div></>}
    </nav>
    {inConsole && !mini && <Splitter label="Menu width" axis="x" grow={1} value={width} min={WIDTH.min} max={WIDTH.max} measure={() => w} onChange={setWidth} />}
  </>);
}

/** True on a phone-sized screen (640 px wide or less), following the window as it turns or resizes. */
function useNarrow() {
  const query = "(max-width: 640px)";
  const get = () => typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(query).matches;
  const [narrow, setNarrow] = useState(get);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const m = window.matchMedia(query);
    const on = () => setNarrow(m.matches);
    m.addEventListener?.("change", on);
    return () => m.removeEventListener?.("change", on);
  }, []);
  return narrow;
}
