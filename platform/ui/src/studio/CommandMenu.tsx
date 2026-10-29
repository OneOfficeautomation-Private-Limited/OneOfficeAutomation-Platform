import { Icon, IconTile, type IconName } from "../components";

/** The Command Center menu (approved look): Home, then four sections. Studio's own pages are views; the rest are links. */
export type StudioView = "automation" | "workflows" | "forms";

type Entry = { label: string; view?: StudioView; page?: string };
const SECTIONS: { label: string; color: string; icon: IconName; items: Entry[] }[] = [
  { label: "BUILD", color: "#2f6fde", icon: "folder", items: [
    { label: "Automation", view: "automation" }, { label: "Workflows", view: "workflows" }, { label: "Forms", view: "forms" }, { label: "Templates", page: "templates" }] },
  { label: "MONITOR", color: "#0f766e", icon: "pulse", items: [
    { label: "Activity", page: "activity" }, { label: "Runners", page: "devices" }, { label: "Schedules and triggers", page: "schedules" },
    { label: "Work queues", page: "queues" }, { label: "Task inbox", page: "inbox" }] },
  { label: "AI", color: "#7c3aed", icon: "spark", items: [
    { label: "Model connections", page: "connections" }, { label: "Prompt Skills", page: "skills" }, { label: "AI governance", page: "governance" }] },
  { label: "ADMINISTRATION", color: "#475569", icon: "gear", items: [
    { label: "Users and roles", page: "users" }, { label: "Vault", page: "credentials" }, { label: "Audit", page: "audit" },
    { label: "Licenses", page: "licenses" }, { label: "Settings", page: "settings" }] },
];

export function CommandMenu({ view, onView, controlRoom }: { view: StudioView | null; onView: (v: StudioView) => void; controlRoom: string | null }) {
  const pageLink = (page: string) => controlRoom ? controlRoom + "?page=" + page : undefined;
  return (
    <nav className="st-menu" aria-label="Command Center">
      {controlRoom
        ? <a className="st-menu-home" href={controlRoom}><Icon name="home" size={18} />Home</a>
        : <span className="st-menu-home" aria-disabled="true" title="Connect Studio to a Command Center to open its pages"><Icon name="home" size={18} />Home</span>}
      {SECTIONS.map((sec) => (
        <div key={sec.label} className="st-menu-section">
          <div className="st-menu-heading"><IconTile name={sec.icon} color={sec.color} size={20} />{sec.label}</div>
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
        </div>
      ))}
    </nav>
  );
}
