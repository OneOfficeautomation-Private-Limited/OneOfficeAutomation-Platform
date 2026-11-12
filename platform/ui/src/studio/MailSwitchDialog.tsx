import { useMemo, useState } from "react";
import type { Flow, PackageManifest } from "../api/client";
import { Button, Dialog, Field, Select } from "../components";
import { MAIL_SERVICES, servicesUsed, switchMailService, type MailService } from "./mailSwitch";

/** A2: Tools > Switch mail service. Shows what the switch does to each step before it is applied, as one change (Undo takes it back). */
export function MailSwitchDialog({ flow, packages, onApply, onClose }:
  { flow: Flow; packages: PackageManifest[]; onApply: (f: Flow, note: string) => void; onClose: () => void }) {
  const used = useMemo(() => servicesUsed(flow), [flow]);
  const [from, setFrom] = useState<MailService | "">(used[0]?.id ?? "");
  const targets = MAIL_SERVICES.filter((s) => s.id !== from && packages.some((p) => p.id === s.pkg));
  const [to, setTo] = useState<MailService | "">(targets.find((s) => s.id === "microsoft365")?.id ?? targets[0]?.id ?? "");
  const result = useMemo(() => from && to && from !== to ? switchMailService(flow, from, to, packages) : null, [flow, from, to, packages]);
  const name = (id: string) => MAIL_SERVICES.find((s) => s.id === id)?.name ?? id;
  return (
    <Dialog open title="Switch mail service" width={720} onClose={onClose} footer={<>
      <Button kind="secondary" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!result || !result.report.changed}
        onClick={() => result && onApply(result.flow, "Switched " + result.report.changed + " step" + (result.report.changed === 1 ? "" : "s") + " from " + name(from) + " to " + name(to) + ". Undo takes it back.")}>
        Switch {result?.report.changed ?? 0} step{result?.report.changed === 1 ? "" : "s"}</Button></>}>
      {!used.length ? <p className="st-muted">This agent has no mail steps.</p> : <>
        <p className="st-muted">Turns this agent's mail steps into the same steps on another service, e.g. from Exchange to Microsoft 365. Fields that are named
          differently are renamed; what has no match is left out and listed below. Check the listed steps before running the agent.</p>
        <div className="st-row-tight st-switch-pick">
          <Field label="From">{(id) => <Select id={id} value={from} onChange={(e) => setFrom(e.target.value as MailService)}
            options={used.map((u) => ({ value: u.id, label: name(u.id) + " (" + u.steps + " step" + (u.steps === 1 ? "" : "s") + ")" }))} />}</Field>
          <Field label="To">{(id) => <Select id={id} value={to} onChange={(e) => setTo(e.target.value as MailService)}
            options={targets.map((s) => ({ value: s.id, label: s.name }))} />}</Field>
        </div>
        {result && <>
          <h3 className="st-conn-sub">What changes</h3>
          <ul className="st-switch-report" aria-label="What changes">{result.report.lines.map((l, i) => <li key={i}>{l}</li>)}</ul>
        </>}
      </>}
    </Dialog>
  );
}
