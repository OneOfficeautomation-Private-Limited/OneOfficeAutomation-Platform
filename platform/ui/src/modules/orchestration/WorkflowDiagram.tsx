import { useState, type DragEvent, type ReactNode } from "react";
import { branchesOf, jumps, meta, numbers, STEP_TYPES, str, typeLabel, type Step } from "./flow";

/**
 * A workflow drawn as a diagram (review round, 26 Nov 2026, design A on the canvas): Start, then the steps top to bottom,
 * numbered; a Parallel step's branches side by side, joined below; a decision's ways as labelled chips, each pointing
 * to the step it goes to (hovering or selecting one marks that step, as AA marks a Go to's target); End. The same
 * drawing serves Studio's editor (steps to select, places to drop new ones) and Run > Workflow runs (each step coloured
 * by what happened in the run).
 */
export type StepState = "done" | "now" | "failed" | "todo" | "skipped";
/** Where a new step goes: after a step, at the end of a branch, or at the end of the workflow. */
export type Slot = { after: string | null; branch?: { par: string; index: number } };

export function WorkflowDiagram({ stages, selected, onSelect, states, editable, onSlot, onDropAt, find, problemSteps, label = "Workflow diagram" }: {
  stages: Step[]; selected?: string | null; onSelect?: (id: string) => void;
  states?: Record<string, StepState>; editable?: boolean;
  /** A click on a place between steps (editable). */
  onSlot?: (slot: Slot) => void;
  /** Something dragged from the step list was dropped on a place (editable); data is what was dragged. */
  onDropAt?: (slot: Slot, data: string) => void;
  find?: string; problemSteps?: Set<string>; label?: string;
}) {
  const [hover, setHover] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const nums = numbers(stages);
  const all = new Map<string, Step>();
  const index = (list: Step[]) => { for (const s of list) { all.set(s.id, s); if (s.type === "parallel") for (const b of branchesOf(s)) index(b.stages ?? []); } };
  index(stages);
  const nameOf = (id: string) => id === "" ? "the next step" : id === "end" ? "the end" : all.has(id) ? (nums.get(id) + " · " + (str(all.get(id)!.name) || id)) : id + " (missing)";
  const marked = new Set<string>();
  if (hover) marked.add(hover);
  if (selected && all.has(selected)) for (const j of jumps(all.get(selected)!)) if (j.to) marked.add(j.to);
  const q = (find ?? "").trim().toLowerCase();

  const slotKey = (s: Slot) => (s.after ?? "") + "|" + (s.branch ? s.branch.par + "#" + s.branch.index : "");
  const place = (slot: Slot, last = false): ReactNode => !editable ? <span className="wfd-line" aria-hidden="true" /> : (
    <button type="button" className={"wfd-slot" + (over === slotKey(slot) ? " is-over" : "") + (last ? " is-last" : "")}
      aria-label={slot.branch ? "Add a step to this branch" : slot.after ? "Add a step after " + nameOf(slot.after) : "Add a step at the end"}
      title="Add a step here (or drop one from the step list)"
      onClick={() => onSlot?.(slot)}
      onDragOver={(e: DragEvent) => { e.preventDefault(); setOver(slotKey(slot)); }} onDragLeave={() => setOver(null)}
      onDrop={(e: DragEvent) => { e.preventDefault(); setOver(null); const d = e.dataTransfer.getData("text/plain"); if (d) onDropAt?.(slot, d); }}>+</button>);

  const node = (s: Step): ReactNode => {
    const t = STEP_TYPES[s.type];
    const st = states?.[s.id];
    const hit = !!q && ((str(s.name) + " " + s.id + " " + typeLabel(s.type)).toLowerCase().includes(q));
    const cls = ["wfd-node", "is-" + s.type, t?.shape ? "is-" + t.shape : "", selected === s.id ? "is-selected" : "", marked.has(s.id) ? "is-target" : "",
      st ? "st-" + st : "", hit ? "is-found" : "", problemSteps?.has(s.id) ? "has-problem" : ""].filter(Boolean).join(" ");
    return (
      <div className={cls} style={{ ["--wfd-color" as string]: t?.color ?? "#5c6575" }}>
        <button type="button" className="wfd-card" aria-pressed={onSelect ? selected === s.id : undefined} aria-label={nums.get(s.id) + " " + (str(s.name) || s.id) + ", " + typeLabel(s.type) + (st ? ", " + st : "")}
          onClick={() => onSelect?.(s.id)} disabled={!onSelect}>
          <span className="wfd-num" aria-hidden="true">{st === "done" ? "✓" : st === "failed" ? "!" : nums.get(s.id)}</span>
          <span className="wfd-text"><strong>{str(s.name) || s.id}</strong><span className="wfd-meta">{typeLabel(s.type)} · {meta(s, nameOf)}</span></span>
          {problemSteps?.has(s.id) && <span className="wfd-warn" title="This step has a problem; see Problems">!</span>}
        </button>
        {jumps(s).length > 0 && <ul className="wfd-ways" aria-label={"Where " + (str(s.name) || s.id) + " goes"}>
          {jumps(s).map((j, i) => <li key={i} onMouseEnter={() => setHover(j.to || null)} onMouseLeave={() => setHover(null)}>
            <span className="wfd-way-label">{j.label}</span> → {j.to ? <button type="button" className="cc-link" onClick={() => j.to && j.to !== "end" && onSelect?.(j.to)}>{nameOf(j.to)}</button> : <span>the next step</span>}
          </li>)}
        </ul>}
        {s.type === "parallel" && <div className="wfd-split" role="group" aria-label={"Branches of " + (str(s.name) || s.id)}>
          {branchesOf(s).map((b, i) => <div key={i} className="wfd-branch">
            <span className="wfd-branch-name">{b.name || "Branch " + (i + 1)}</span>
            {list(b.stages ?? [], { par: s.id, index: i })}
          </div>)}
        </div>}
      </div>
    );
  };

  const list = (items: Step[], branch?: { par: string; index: number }): ReactNode => (
    <ol className="wfd-list">
      {items.map((s) => <li key={s.id}>{node(s)}{place({ after: s.id, branch })}</li>)}
      {!items.length && <li>{place({ after: null, branch }, true)}</li>}
    </ol>
  );

  return (
    <div className="wfd" role="group" aria-label={label}>
      <div className="wfd-cap is-start">Start</div>
      {editable && <div className="wfd-first">{place({ after: null }, false)}</div>}
      {!editable && <span className="wfd-line" aria-hidden="true" />}
      {list(stages)}
      <div className="wfd-cap is-end">End of the workflow</div>
    </div>
  );
}
