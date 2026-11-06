import { Button, Field, Select } from "../components";
import type { ProcessDesign } from "./api";
import { folderLabel } from "./dialogs";
import { Chip } from "./ui";

/**
 * Step 1 of the process builder, Select a process: the one place to choose which process to build (every process,
 * the One Inbox too), or to make a new one. Choosing one opens its step 2.
 */
export function SelectStep({ choices, chosen, onChoose, onNew }:
  { choices: { id: string; name: string }[]; chosen: ProcessDesign | null; onChoose: (id: string) => void; onNew: () => void }) {
  return (
    <section className="df-card df-pad" aria-label="Select a process" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div><b style={{ fontSize: 15 }}>1 · Select a process</b>
        <div className="df-muted">Choose the process to build or change, or make a new one.</div></div>
      <div className="df-head-row" style={{ alignItems: "flex-end" }}>
        <div style={{ flex: "1 1 260px", minWidth: 0, maxWidth: 420 }}>
          <Field label="Process">
            {(id) => <Select id={id} value={chosen?.id ?? ""} onChange={(e) => { if (e.target.value) onChoose(e.target.value); }}
              options={[...(chosen ? [] : [{ value: "", label: choices.length ? "Choose a process" : "No process yet" }]), ...choices.map((p) => ({ value: p.id, label: p.name }))]} />}
          </Field>
        </div>
        <Button kind={choices.length ? "secondary" : "primary"} icon="plus" onClick={onNew}>New process</Button>
      </div>
      {chosen ? (
        <div className="df-grid df-g3" style={{ paddingTop: 14, borderTop: "1px solid var(--ui-divider)" }}>
          <div><div className="df-small">Name</div><b>{chosen.name}</b></div>
          <div><div className="df-small">Folder</div>{chosen.folder ? folderLabel(chosen.folder) : "None (open to all)"}</div>
          <div><div className="df-small">Status</div>
            <Chip tone={chosen.state === "live" ? "ok" : "mute"}>{chosen.state === "live" ? "Live" : "Draft"} · version {chosen.version}</Chip></div>
        </div>
      ) : <p className="df-small" style={{ margin: 0 }}>{choices.length ? "Steps 2 to 8 open once a process is chosen." : "No process yet. Click New process."}</p>}
    </section>
  );
}
