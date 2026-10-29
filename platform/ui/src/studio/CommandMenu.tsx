import { useEffect, useState } from "react";
import { Icon, IconTile, usePersistent, type IconName } from "../components";

/**
 * The Command Center menu (approved look): Home, then four sections: BUILD, MANAGE, ADMINISTRATION, AI. Studio's own pages are views; the rest are links.
 * The whole menu folds to a column of icons, and each section opens and closes on its heading; both are remembered.
 */
// BUILD holds only Automation: workflows, forms and the rest are made there (Create) and called from agents' steps.
export type StudioView = "automation";

type Entry = { label: string; view?: StudioView; page?: string };
const SECTIONS: { label: string; color: string; icon: IconName; items: Entry[] }[] = [
  { label: "BUILD", color: "#2f6fde", icon: "folder", items: [
    { label: "Automation", view: "automation" }] },
  { label: "MANAGE", color: "#0f766e", icon: "pulse", items: [
    { label: "Activity", page: "activity" }, { label: "Runners", page: "devices" }, { label: "Schedules and triggers", page: "schedules" },
    { label: "Work queues", page: "queues" }, { label: "Task inbox", page: "inbox" }] },
  { label: "ADMINISTRATION", color: "#475569", icon: "gear", items: [
    { label: "Users and roles", page: "users" }, { label: "Vault", page: "credentials" }, { label: "Audit", page: "audit" },
    { label: "Licenses", page: "licenses" }, { label: "Settings", page: "settings" }] },
  { label: "AI", color: "#7c3aed", icon: "spark", items: [
    { label: "Model connections", page: "connections" }, { label: "Prompt Skills", page: "skills" }, { label: "AI governance", page: "governance" }] },
];

export function CommandMenu({ view, onView, controlRoom, editing = false }:
  { view: StudioView | null; onView: (v: StudioView) => void; controlRoom: string | null; editing?: boolean }) {
  // while an agent is open the menu folds to icons, as AA's editor hides the Control Room menu, so the steps keep their
  // room; it can still be opened, and the person's own choice for the other pages is left as it was
  const [chosenMini, setChosenMini] = usePersistent("menu.mini", false);
  const [openWhileEditing, setOpenWhileEditing] = useState(false);
  useEffect(() => { if (!editing) setOpenWhileEditing(false); }, [editing]);
  const mini = editing ? !openWhileEditing : chosenMini;
  const setMini = (m: boolean) => { if (editing) setOpenWhileEditing(!m); else setChosenMini(m); };
  const [closed, setClosed] = usePersistent<string[]>("menu.closed", []);
  const pageLink = (page: string) => controlRoom ? controlRoom + "?page=" + page : undefined;
  const toggleSection = (label: string) => setClosed(closed.includes(label) ? closed.filter((x) => x !== label) : [...closed, label]);
  const home = controlRoom
    ? <a className="st-menu-home" href={controlRoom} title="Home"><Icon name="home" size={18} />{!mini && "Home"}</a>
    : <span className="st-menu-home" aria-disabled="true" title="Connect Studio to a Command Center to open its pages"><Icon name="home" size={18} />{!mini && "Home"}</span>;

  return (
    <nav className={`st-menu${mini ? " is-mini" : ""}`} aria-label="Command Center">
      <button type="button" className="st-menu-fold" aria-expanded={!mini} onClick={() => setMini(!mini)}
        aria-label={mini ? "Expand menu" : "Collapse menu"} title={mini ? "Expand menu" : "Collapse menu"}>
        <Icon name={mini ? "chevronRight" : "chevronLeft"} size={14} stroke={2.6} />{!mini && "Collapse menu"}
      </button>
      {home}
      {SECTIONS.map((sec) => {
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
                {sec.items.map((it) => {
                  if (it.view) {
                    const current = view === it.view;
                    return <li key={it.label}><button type="button" className={`st-menu-item${current ? " is-current" : ""}`} aria-current={current ? "page" : undefined}
                      onClick={() => onView(it.view!)}>{it.label}</button></li>;
                  }
                  const href = pageLink(it.page!);
                  return <li key={it.label}>{href
                    ? <a className="st-menu-item" href={href}>{it.label}</a>
                    : <span className="st-menu-item is-off" aria-disabled="true" title="Connect Studio to a Command Center to open this page">{it.label}</span>}</li>;
                })}
              </ul>
            )}
          </div>
        );
      })}
    </nav>
  );
}
