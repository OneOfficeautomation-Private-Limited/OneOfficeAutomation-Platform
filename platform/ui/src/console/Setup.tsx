import { useCallback, useEffect, useState } from "react";
import type { CrApi, CrUser, SetupGuide, SetupStep } from "../api/cr";
import { Button, HelpButton, type HelpTopic, PageError, Tabs, TabPanel } from "../components";
import { PageIntro } from "../shell/PageIntro";

const HELP: HelpTopic = { title: "Install and set up", about: "The steps to install OOA and get it working, for someone doing it the first time. Each step checks itself when you open the page.",
  items: [
    { name: "Phases", text: "Server, People and runners, Developers and AI, First run. The cards show how many steps of each are done; pick one to see its steps." },
    { name: "A step", text: "Done or To do, what the check found, how to do it, and a command to copy or a button to the page where it is done. Optional steps can be left." },
    { name: "Check again", text: "Asks every check again, after you finished a step on the server." },
    { name: "Hide Setup from my menu", text: "Takes Setup out of your menu in this browser once you are done. An administrator can take it out of everyone's menu in Settings (Show Setup in the menu)." },
  ] };

/**
 * Setup > Install and set up (26 Nov 2026, design S2 chosen on the canvas): the install guide in four phases with
 * progress cards, one phase's steps at a time, each with what its live check found (GET /api/setup), how to do it, a
 * command to copy and the page where it is done. Modules add their own steps (DocFlow, OneChat). Administrators only.
 */
export function Setup({ api, setPage, hidden, setHidden }: { api: CrApi; user: CrUser; setPage: (id: string) => void; hidden: boolean; setHidden: (v: boolean) => void }) {
  const [guide, setGuide] = useState<SetupGuide | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const load = useCallback(() => {
    setBusy(true);
    api.setup().then((g) => { setGuide(g); setError(null); setPhase((p) => p ?? (g.phases.find((x) => x.done < x.total) ?? g.phases[0])?.id ?? null); },
      (e) => setError(e instanceof Error ? e.message : String(e))).finally(() => setBusy(false));
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const copy = (text: string) => { navigator.clipboard?.writeText(text).then(() => setNote("Copied: " + text), () => setNote("Select the command and copy it.")); };
  const runSample = () => {
    setBusy(true);
    api.runSample().then((r) => { setNote("The sample agent is running (run " + r.run_id + "). Check again when it has finished."); load(); },
      (e) => { setNote(e instanceof Error ? e.message : String(e)); setBusy(false); });
  };
  const step = (s: SetupStep) => <li key={s.id} className="cc-setup-step">
    <span className={"cc-pill " + (s.status === "done" || s.status === "not_needed" ? "is-success" : "is-pending")}>{s.status === "done" ? "Done" : s.status === "not_needed" ? "Not needed" : "To do"}</span>
    <div className="cc-setup-body">
      <div className="cc-setup-title"><strong>{s.title}</strong>{s.optional && <span className="cc-setup-optional">optional</span>}</div>
      <div className="cc-setup-detail">{s.detail}</div>
      {s.status !== "done" && <p className="cc-setup-help">{s.help}</p>}
      {s.status !== "done" && s.command && <code className="cc-setup-cmd">{s.command}</code>}
      {s.status !== "done" && <div className="cc-setup-actions">
        {s.command && <Button kind="secondary" onClick={() => copy(s.command!)}>Copy command</Button>}
        {s.id === "sample" && <Button kind="primary" disabled={busy} onClick={runSample}>Run it</Button>}
        {s.page && s.id !== "sample" && <Button kind="secondary" onClick={() => setPage(s.page!)}>Open the page</Button>}
      </div>}
    </div>
  </li>;
  const all = guide?.done === guide?.total && !!guide;
  return <div className="cc-page cc-setup">
    <header className="st-page-head"><div><div className="st-head-row"><h1>Install and set up</h1><HelpButton topic={HELP} /><PageIntro k="page:setup" /></div>
      <p>{guide ? (all ? "Every step is done. " : guide.done + " of " + guide.total + " steps done. ") : ""}Four phases, from the server to the first run.</p></div>
      <div className="cc-setup-head-actions">
        <label className="cc-setup-hide"><input type="checkbox" checked={hidden} onChange={(e) => setHidden(e.target.checked)} />Hide Setup from my menu</label>
        <Button kind="secondary" disabled={busy} onClick={load}>Check again</Button>
      </div>
    </header>
    {note && <p className="st-notice" role="status">{note}</p>}
    {error && <PageError text={error} />}
    {guide && <>
      <ul className="cc-setup-cards" aria-label="Phase progress">
        {guide.phases.map((p, i) => { const pct = p.total ? Math.round(100 * p.done / p.total) : 100; const state = p.done === p.total ? "is-done" : p.done ? "is-part" : "";
          return <li key={p.id}><button type="button" className={"cc-setup-card " + state + (phase === p.id ? " is-current" : "")} aria-pressed={phase === p.id} onClick={() => setPhase(p.id)}>
            <span className="cc-setup-card-name">{i + 1} · {p.name}</span>
            <span className="cc-setup-card-count">{p.done} of {p.total}</span>
            <span className="cc-setup-meter" aria-hidden="true"><span style={{ width: pct + "%" }} /></span>
          </button></li>; })}
      </ul>
      <Tabs label="Phases" value={phase ?? guide.phases[0].id} onChange={setPhase}
        tabs={guide.phases.map((p, i) => ({ value: p.id, label: (i + 1) + " · " + p.name }))}>
        {guide.phases.map((p) => <TabPanel key={p.id} value={p.id}>
          <ol className="cc-setup-steps">{p.steps.map(step)}</ol>
        </TabPanel>)}
      </Tabs>
    </>}
  </div>;
}
