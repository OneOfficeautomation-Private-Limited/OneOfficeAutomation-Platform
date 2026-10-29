import { useMemo, useState } from "react";
import type { Flow, PackageManifest } from "../api/client";
import { Field, IconTile, SidePanel, TabPanel, Tabs, TextInput, usePersistent } from "../components";
import { conditionText, describeAction, plainValue, stepRows, summarize, toolkitMark } from "./toolkits";

/** The action library on the left: toolkits with their mark and count, searchable, one open at a time. */
export function ActionsPanel({ packages }: { packages: PackageManifest[] }) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const q = query.trim().toLowerCase();
  const [hidden, setHidden] = usePersistent("panel.actions.hidden", false);
  const total = packages.reduce((n, p) => n + p.actions.length, 0);
  const list = packages.map((p) => ({ p, actions: q ? p.actions.filter((a) => a.name.toLowerCase().includes(q) || p.name.toLowerCase().includes(q)) : p.actions }))
    .filter((x) => x.actions.length).sort((a, b) => a.p.name.localeCompare(b.p.name));
  return (
    <SidePanel title="Actions" side="left" collapsed={hidden} onToggle={() => setHidden(!hidden)} className="st-actions">
      <div className="st-panel-search"><TextInput aria-label="Search actions" placeholder={`Search ${total.toLocaleString()} actions`} value={query} onChange={(e) => setQuery(e.target.value)} /></div>
      <ul className="st-groups">
        {list.map(({ p, actions }) => {
          const isOpen = open === p.id || (!!q && list.length <= 3);
          const m = toolkitMark(p.id);
          return (
            <li key={p.id}>
              <button type="button" className={`st-group${isOpen ? " is-open" : ""}`} aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : p.id)}>
                <IconTile name={m.icon} color={m.color} size={22} /><span className="st-group-name">{p.name}</span><span className="st-count">{actions.length}</span>
              </button>
              {isOpen && <ul className="st-group-actions">{actions.map((a) => <li key={a.id} title={a.description}>{a.name}</li>)}</ul>}
            </li>
          );
        })}
      </ul>
    </SidePanel>
  );
}

/**
 * One open agent: its steps, numbered, with the toolkit mark and guide lines for blocks; and on the right its
 * properties, variables and triggers. Read-only in this first step; editing arrives in P2 (use Classic until then).
 */
export function AgentView({ flow, packages, classicHref }: { flow: Flow; packages: PackageManifest[]; classicHref: string }) {
  const rows = useMemo(() => stepRows(flow.steps), [flow]);
  const [selected, setSelected] = useState<string | null>(() => {
    const first = rows.find((r) => r.kind === "step");
    return first?.kind === "step" ? first.step.id : null;
  });
  const [rightTab, setRightTab] = useState("properties");
  const [rightHidden, setRightHidden] = usePersistent("panel.details.hidden", false);
  const sel = rows.find((r) => r.kind === "step" && r.step.id === selected);
  const selStep = sel?.kind === "step" ? sel : null;
  const selInfo = selStep ? describeAction(selStep.step.action, packages) : null;
  const stepCount = rows.filter((r) => r.kind === "step").length;

  return (
    <div className="st-agent">
      <section className="st-steps" aria-label="Agent steps">
        <div className="st-readonly" role="note">Read-only preview. Editing comes next; to change this agent now, <a href={classicHref}>open it in Classic Studio</a>.</div>
        <div className="st-steps-meta">{stepCount} steps</div>
        <div className="st-marker st-start">START</div>
        <ol className="st-step-list">
          {rows.map((r, i) => {
            if (r.kind === "section") return <li key={"s" + i} className="st-section" style={{ paddingLeft: 46 + r.depth * 22 }}>{r.label}</li>;
            const info = describeAction(r.step.action, packages);
            const isSel = r.step.id === selected;
            return (
              <li key={r.step.id + i}>
                <button type="button" className={`st-step${isSel ? " is-selected" : ""}${r.step.disabled ? " is-disabled" : ""}`} aria-pressed={isSel}
                  onClick={() => setSelected(r.step.id)}>
                  <span className="st-step-n">{r.n}</span>
                  <span className="st-guides" style={{ width: r.depth * 22 }} />
                  <IconTile name={info.mark.icon} color={info.mark.color} size={24} />
                  <strong>{info.name}</strong>
                  <span className="st-step-sum">{summarize(r.step, packages)}</span>
                  {r.step.disabled && <span className="st-badge">OFF</span>}
                </button>
              </li>
            );
          })}
        </ol>
        <div className="st-marker st-end">END</div>
      </section>

      <SidePanel title="Details" label="Properties, variables and triggers" side="right" collapsed={rightHidden}
        onToggle={() => setRightHidden(!rightHidden)} className="st-right">
        <Tabs label="Right panel" value={rightTab} onChange={setRightTab}
          tabs={[{ value: "properties", label: "Properties" }, { value: "variables", label: "Variables" }, { value: "triggers", label: "Triggers" }]}>
          <TabPanel value="properties">
            {selStep && selInfo ? <>
              <div className="st-prop-head"><IconTile name={selInfo.mark.icon} color={selInfo.mark.color} size={28} />
                <div><strong>{selInfo.name}</strong><div>Line {selStep.n} · {selInfo.toolkit}</div></div></div>
              <h3 className="st-prop-section">Settings</h3>
              <div className="st-prop-fields">
                {Object.entries(selStep.step.args ?? {}).map(([k, v]) => (
                  <Field key={k} label={k.replace(/_/g, " ")} layout="beside">{(id) =>
                    <TextInput id={id} readOnly mono={typeof v === "string" && v.startsWith("$")} value={k === "condition" ? conditionText(v, packages) : plainValue(v, 400)} />}</Field>
                ))}
                {!Object.keys(selStep.step.args ?? {}).length && <p className="st-muted">This step has no settings.</p>}
              </div>
              {selStep.step.out && <>
                <h3 className="st-prop-section">Output</h3>
                <div className="st-prop-fields">
                  {Object.entries(selStep.step.out).map(([k, v]) => <Field key={k} label={k.replace(/_/g, " ")} layout="beside">{(id) =>
                    <TextInput id={id} readOnly mono value={"$" + v} />}</Field>)}
                </div>
              </>}
            </> : <p className="st-muted st-pad">Select a step to see its settings.</p>}
          </TabPanel>
          <TabPanel value="variables">
            <ul className="st-vars">
              {(flow.variables ?? []).map((v) => (
                <li key={v.name}><span className="st-var">${v.name}</span><span className="st-type">{v.type}</span>
                  <span className="st-dir">{v.direction === "input" ? "Input" : v.direction === "output" ? "Output" : v.direction === "both" ? "Input and output" : ""}</span></li>
              ))}
              {!flow.variables?.length && <li className="st-muted">No variables yet.</li>}
            </ul>
          </TabPanel>
          <TabPanel value="triggers">
            <p className="st-pad">Triggers start this agent automatically (new email, a file arriving, a schedule). They are set in Classic Studio until P3.</p>
          </TabPanel>
        </Tabs>
      </SidePanel>
    </div>
  );
}
