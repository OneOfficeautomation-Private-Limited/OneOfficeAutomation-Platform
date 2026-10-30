import { useEffect, useState } from "react";
import { Icon, IconTile, usePersistent } from "../components";
import { canSee, type MenuSection, type MenuViewer } from "./menu";

/**
 * The Command Center menu (approved look), used by Studio and the console: Home, then the sections. A page the app
 * shows itself (`local`) is a button; any other page is a link (`hrefFor`), or plain text when there is nowhere to go.
 * The whole menu folds to a column of icons, and each section opens and closes on its heading; both are remembered.
 * While something is being edited (`editing`) the menu folds, as AA's editor hides the Control Room menu, so the work
 * keeps its room; it can still be opened, and the person's own choice for the other pages is left as it was.
 */
export function CommandMenu({ sections, current, local, onLocal, hrefFor, home, viewer = null, editing = false, offTitle }:
  { sections: MenuSection[]; current: string | null; local: ReadonlySet<string>; onLocal: (id: string) => void;
    hrefFor: (id: string) => string | undefined; home: { href?: string; onClick?: () => void; current?: boolean };
    viewer?: MenuViewer; editing?: boolean; offTitle?: string }) {
  const [chosenMini, setChosenMini] = usePersistent("menu.mini", false);
  const [openWhileEditing, setOpenWhileEditing] = useState(false);
  useEffect(() => { if (!editing) setOpenWhileEditing(false); }, [editing]);
  const mini = editing ? !openWhileEditing : chosenMini;
  const setMini = (m: boolean) => { if (editing) setOpenWhileEditing(!m); else setChosenMini(m); };
  const [closed, setClosed] = usePersistent<string[]>("menu.closed", []);
  const toggleSection = (label: string) => setClosed(closed.includes(label) ? closed.filter((x) => x !== label) : [...closed, label]);
  const homeBody = <><Icon name="home" size={18} />{!mini && "Home"}</>;
  const homeEl = home.onClick
    ? <button type="button" className={`st-menu-home${home.current ? " is-current" : ""}`} aria-current={home.current ? "page" : undefined} title="Home" onClick={home.onClick}>{homeBody}</button>
    : home.href ? <a className="st-menu-home" href={home.href} title="Home">{homeBody}</a>
    : <span className="st-menu-home" aria-disabled="true" title={offTitle}>{homeBody}</span>;
  const shown = sections.map((s) => ({ ...s, pages: s.pages.filter((p) => canSee(p, viewer)) })).filter((s) => s.pages.length);

  return (
    <nav className={`st-menu${mini ? " is-mini" : ""}`} aria-label="Command Center">
      <button type="button" className="st-menu-fold" aria-expanded={!mini} onClick={() => setMini(!mini)}
        aria-label={mini ? "Expand menu" : "Collapse menu"} title={mini ? "Expand menu" : "Collapse menu"}>
        <Icon name={mini ? "chevronRight" : "chevronLeft"} size={14} stroke={2.6} />{!mini && "Collapse menu"}
      </button>
      {homeEl}
      {shown.map((sec) => {
        const open = !closed.includes(sec.label);
        if (mini) {
          // folded: one icon per section; it opens the menu at that section
          return (
            <button key={sec.label} type="button" className="st-menu-mini" title={sec.label} aria-label={sec.label}
              onClick={() => { setMini(false); if (!open) toggleSection(sec.label); }}>
              <IconTile name={sec.icon} color={sec.color} size={26} />
            </button>
          );
        }
        return (
          <div key={sec.label} className="st-menu-section">
            <button type="button" className="st-menu-heading" aria-expanded={open} onClick={() => toggleSection(sec.label)}>
              <IconTile name={sec.icon} color={sec.color} size={20} />{sec.label}
              <span className="st-menu-caret"><Icon name={open ? "chevronDown" : "chevronRight"} size={12} stroke={2.6} /></span>
            </button>
            {open && (
              <ul className="st-menu-items">
                {sec.pages.map((it) => {
                  if (local.has(it.id)) {
                    const isCurrent = current === it.id;
                    return <li key={it.id}><button type="button" className={`st-menu-item${isCurrent ? " is-current" : ""}`} aria-current={isCurrent ? "page" : undefined}
                      onClick={() => onLocal(it.id)}>{it.label}</button></li>;
                  }
                  const href = hrefFor(it.id);
                  const external = it.id === "studio";
                  return <li key={it.id}>{href
                    ? <a className="st-menu-item" href={href} target={external ? "_blank" : undefined} rel={external ? "noopener" : undefined} title={external ? it.label + " (opens in a new tab)" : undefined}>{it.label}{external && " ↗"}</a>
                    : <span className="st-menu-item is-off" aria-disabled="true" title={offTitle}>{it.label}</span>}</li>;
                })}
              </ul>
            )}
          </div>
        );
      })}
    </nav>
  );
}
