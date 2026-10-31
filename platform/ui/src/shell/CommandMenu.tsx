import { useEffect, useState } from "react";
import { Icon, IconTile, usePersistent } from "../components";
import { canSee, type MenuPage, type MenuSection, type MenuViewer } from "./menu";

/**
 * The Command Center menu (approved look), used by Studio and the console: Home, then the sections. A page the app
 * shows itself (`local`) is a button; any other page is a link (`hrefFor`), or plain text when there is nowhere to go.
 * The whole menu folds to a column of icons, and each section opens and closes on its heading; both are remembered.
 * While something is being edited (`editing`) the menu folds, as AA's editor hides the Control Room menu, so the work
 * keeps its room; it can still be opened, and the person's own choice for the other pages is left as it was.
 * In the console (`console`, the design plan's step 1): a box to find a page, a Favourites slot, only the current
 * section open (opening another closes it), and Collapse at the bottom. Studio keeps the menu as it was.
 */
export function CommandMenu({ sections, current, local, onLocal, hrefFor, home, viewer = null, editing = false, offTitle, console: inConsole = false }:
  { sections: MenuSection[]; current: string | null; local: ReadonlySet<string>; onLocal: (id: string) => void;
    hrefFor: (id: string) => string | undefined; home: { href?: string; onClick?: () => void; current?: boolean };
    viewer?: MenuViewer; editing?: boolean; offTitle?: string; console?: boolean }) {
  const [chosenMini, setChosenMini] = usePersistent("menu.mini", false);
  const [openWhileEditing, setOpenWhileEditing] = useState(false);
  useEffect(() => { if (!editing) setOpenWhileEditing(false); }, [editing]);
  const mini = editing ? !openWhileEditing : chosenMini;
  const setMini = (m: boolean) => { if (editing) setOpenWhileEditing(!m); else setChosenMini(m); };
  const [closed, setClosed] = usePersistent<string[]>("menu.closed", []);
  const toggleSection = (label: string) => setClosed(closed.includes(label) ? closed.filter((x) => x !== label) : [...closed, label]);
  const shown = sections.map((s) => ({ ...s, pages: s.pages.filter((p) => canSee(p, viewer)) })).filter((s) => s.pages.length);
  // the console opens one section at a time: the current page's, or the one the person opened last
  const sectionOf = (id: string | null) => shown.find((s) => s.pages.some((p) => p.id === id))?.label ?? null;
  const [openOne, setOpenOne] = useState<string | null>(() => sectionOf(current));
  useEffect(() => { const s = sectionOf(current); if (s) setOpenOne(s); }, [current]);   // eslint-disable-line react-hooks/exhaustive-deps
  const isOpen = (label: string) => inConsole ? openOne === label : !closed.includes(label);
  const toggle = (label: string) => { if (inConsole) setOpenOne(openOne === label ? null : label); else toggleSection(label); };
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
  const item = (it: MenuPage, extra?: string) => {
    if (local.has(it.id)) {
      const isCurrent = current === it.id;
      return <button type="button" className={`st-menu-item${isCurrent ? " is-current" : ""}`} aria-current={isCurrent ? "page" : undefined}
        onClick={() => { setFind(""); onLocal(it.id); }}>{it.label}{extra && <span className="st-menu-where">{extra}</span>}</button>;
    }
    const href = hrefFor(it.id);
    const external = it.id === "studio";
    return href
      ? <a className="st-menu-item" href={href} target={external ? "_blank" : undefined} rel={external ? "noopener" : undefined} title={external ? it.label + " (opens in a new tab)" : undefined}>{it.label}{external && " ↗"}{extra && <span className="st-menu-where">{extra}</span>}</a>
      : <span className="st-menu-item is-off" aria-disabled="true" title={offTitle}>{it.label}</span>;
  };
  const found = query ? shown.flatMap((s) => s.pages.filter((p) => p.label.toLowerCase().includes(query)).map((p) => ({ p, sec: s.label }))) : [];

  return (
    <nav className={`st-menu${mini ? " is-mini" : ""}${inConsole ? " is-console" : ""}`} aria-label="Command Center">
      {!inConsole && fold}
      {inConsole && !mini && <input type="search" className="st-menu-find" aria-label="Find a page" placeholder="Find a page" value={find}
        onChange={(e) => setFind(e.target.value)} onKeyDown={(e) => { if (e.key === "Escape") setFind(""); }} />}
      {query && !mini ? (
        found.length ? <ul className="st-menu-items st-menu-found" aria-label="Pages found">{found.map(({ p, sec }) => <li key={p.id}>{item(p, sec)}</li>)}</ul>
          : <p className="st-menu-hint">No page matches.</p>
      ) : <>
        {homeEl}
        {inConsole && !mini && <div className="st-menu-section">
          <div className="st-menu-heading is-static"><span className="st-menu-star"><Icon name="star" size={14} filled /></span>Favourites</div>
          <p className="st-menu-hint">Pinned pages show here.</p>
        </div>}
        {shown.map((sec) => {
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
          return (
            <div key={sec.label} className="st-menu-section">
              <button type="button" className="st-menu-heading" aria-expanded={open} onClick={() => toggle(sec.label)}>
                <IconTile name={sec.icon} color={sec.color} size={20} />{sec.label}
                <span className="st-menu-caret"><Icon name={open ? "chevronDown" : "chevronRight"} size={12} stroke={2.6} /></span>
              </button>
              {open && <ul className="st-menu-items">{sec.pages.map((it) => <li key={it.id}>{item(it)}</li>)}</ul>}
            </div>
          );
        })}
      </>}
      {inConsole && <><span className="st-menu-fill" />{fold}</>}
    </nav>
  );
}
