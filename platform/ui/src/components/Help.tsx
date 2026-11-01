import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { Icon } from "./Icon";

/**
 * Help beside the page (issue 11, design B on the canvas, chosen 1 Nov 2026): a small ⓘ after a page title, section or
 * group heading opens a Help panel at the right of the page that says, in plain words, what each option there does and
 * how to use it. One topic shows at a time; the same ⓘ, the ✕ or Esc closes it, and it closes when the page changes.
 */
export type HelpItem = { name: string; text: string };
export type HelpTopic = { title: string; about?: string; items?: HelpItem[] };

type HelpState = { topic: HelpTopic | null; open: (t: HelpTopic | null) => void };
const HelpContext = createContext<HelpState | null>(null);

export function HelpProvider({ children }: { children: ReactNode }) {
  const [topic, open] = useState<HelpTopic | null>(null);
  return <HelpContext.Provider value={{ topic, open }}>{children}</HelpContext.Provider>;
}

/** The ⓘ that opens (or closes) a topic in the Help panel; nothing without a HelpProvider around it. */
export function HelpButton({ topic }: { topic: HelpTopic }) {
  const help = useContext(HelpContext);
  if (!help) return null;
  const on = help.topic?.title === topic.title;
  return (
    <button type="button" className={"ui-help" + (on ? " is-on" : "")} aria-label={"Help: " + topic.title} aria-expanded={on}
      title={on ? "Close the help" : "How to use " + topic.title} onClick={() => help.open(on ? null : topic)}>i</button>
  );
}

/** The panel at the right of the page; `page` closes it when the page changes. */
export function HelpPanel({ page }: { page?: string }) {
  const help = useContext(HelpContext);
  const topic = help?.topic ?? null;
  useEffect(() => { help?.open(null); }, [page]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!topic) return;
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") help?.open(null); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [topic, help]);
  if (!help || !topic) return null;
  return (
    <aside className="ui-help-panel" aria-label={"Help: " + topic.title}>
      <div className="ui-help-head">
        <h2>Help: {topic.title}</h2>
        <button type="button" className="ui-help-close" aria-label="Close the help" title="Close (Esc)" onClick={() => help.open(null)}><Icon name="close" size={14} /></button>
      </div>
      <div className="ui-help-body">
        {topic.about && <p className="ui-help-about">{topic.about}</p>}
        {(topic.items ?? []).map((i) => <div key={i.name} className="ui-help-item"><strong>{i.name}</strong><p>{i.text}</p></div>)}
      </div>
    </aside>
  );
}
