import { useState } from "react";
import { Button, Dialog, IconButton } from "../../components";
import type { Combo } from "../api";

type Choice = { id: string; name: string; ready: boolean };
const MAX = 6;

/** Run test: the engine combinations to compare (an OCR engine and an extraction engine each); only ready engines. */
export function RunDialog({ ocr, extraction, onRun, onClose }:
  { ocr: Choice[]; extraction: Choice[]; onRun: (combos: Combo[]) => Promise<void>; onClose: () => void }) {
  const firstReady = (list: Choice[], skip?: string) => list.find((e) => e.ready && e.id !== skip)?.id ?? list[0]?.id ?? "auto";
  const [combos, setCombos] = useState<Combo[]>(() => [{ ocr: "auto", extraction: firstReady(extraction) }]);
  const [running, setRunning] = useState(false);
  const set = (i: number, c: Partial<Combo>) => setCombos(combos.map((x, j) => (j === i ? { ...x, ...c } : x)));
  const unique = combos.filter((c, i) => combos.findIndex((x) => x.ocr === c.ocr && x.extraction === c.extraction) === i);
  const ok = unique.every((c) => isReady(ocr, c.ocr) && isReady(extraction, c.extraction));
  const run = async () => { setRunning(true); try { await onRun(unique); } finally { setRunning(false); } };

  return (
    <Dialog open onClose={onClose} title="Run test" width={620}
      footer={<><span className="df-muted">{unique.length} combination{unique.length > 1 ? "s" : ""}</span><span className="df-grow" />
        <Button onClick={onClose}>Cancel</Button><Button kind="run" disabled={!ok || running || !unique.length} onClick={run}>{running ? "Starting…" : "Run test"}</Button></>}>
      <p className="df-muted" style={{ margin: 0 }}>Each combination reads every document of the set. Nothing is filed, delivered or kept. Engines not ready are greyed out: set them up on the Engines page.</p>
      <table className="df-table" aria-label="Engine combinations">
        <thead><tr><th>#</th><th>Reads the pages (OCR)</th><th>Reads the fields</th><th /></tr></thead>
        <tbody>
          {combos.map((c, i) => (
            <tr key={i}>
              <td className="df-muted">{i + 1}</td>
              <td><EngineSelect label={`OCR engine, combination ${i + 1}`} value={c.ocr} choices={ocr} onChange={(v) => set(i, { ocr: v })} /></td>
              <td><EngineSelect label={`Extraction engine, combination ${i + 1}`} value={c.extraction} choices={extraction} onChange={(v) => set(i, { extraction: v })} /></td>
              <td><IconButton icon="trash" label={`Remove combination ${i + 1}`} disabled={combos.length === 1} onClick={() => setCombos(combos.filter((_, j) => j !== i))} /></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div>
        <Button icon="plus" disabled={combos.length >= MAX}
          onClick={() => setCombos([...combos, { ocr: firstReady(ocr, combos.at(-1)?.ocr), extraction: combos.at(-1)?.extraction ?? firstReady(extraction) }])}>
          Add combination</Button>
        <span className="df-small" style={{ marginLeft: 8 }}>Up to {MAX}.</span>
      </div>
    </Dialog>
  );
}

const isReady = (list: Choice[], id: string) => list.some((e) => e.id === id && e.ready);

function EngineSelect({ label, value, choices, onChange }: { label: string; value: string; choices: Choice[]; onChange: (v: string) => void }) {
  return (
    <select className="ui-input ui-select" aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      {choices.map((e) => <option key={e.id} value={e.id} disabled={!e.ready}>{e.name}{e.ready ? "" : " (not ready)"}</option>)}
    </select>
  );
}
