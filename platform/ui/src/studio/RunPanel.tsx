import type { DebugCommand, DebugState, RunEvent, RunResult } from "../api/client";
import { Button } from "../components";
import { showValue } from "./flowEdit";

/** A run of the open agent from this Studio: what it reported so far and, when it is being debugged, where it stopped. */
export type LiveRun = { id: string; debug: boolean; state: string; events: RunEvent[]; result?: RunResult; stopped?: DebugState; elapsed: number };

const FINISHED = new Set(["success", "failed", "cancelled", "stopped", "timeout"]);
export const isFinished = (run: LiveRun | null | undefined) => !!run && FINISHED.has(run.state);

/** The step a run is at: where a debugged run is stopped, else the last step that started and has not ended. */
export function runningStep(run: LiveRun | null | undefined): string | null {
  if (!run || isFinished(run)) return null;
  if (run.stopped) return run.stopped.stepId;
  const open = new Set<string>();
  for (const e of run.events) {
    if (!e.stepId) continue;
    if (e.type === "step.started") open.add(e.stepId);
    else if (e.type === "step.finished" || e.type === "step.failed") open.delete(e.stepId);
  }
  return [...open].pop() ?? null;
}

const STATE_WORDS: Record<string, string> = {
  running: "Running", paused: "Paused", debugging: "Stopped at a step", success: "Finished", failed: "Failed", cancelled: "Stopped", stopped: "Stopped", timeout: "Timed out",
};

/** One event as a line a person reads: steps by their line number and name, logs, values set, the end. */
function line(e: RunEvent, stepName: (id: string) => string): { text: string; tone?: "bad" | "good" } | null {
  const d = e.data ?? {};
  const at = e.stepId ? stepName(e.stepId) + " · " : "";
  switch (e.type) {
    case "run.started": return { text: "Run started" + (d.totalSteps ? ` (${d.totalSteps} steps)` : "") };
    case "step.finished": return { text: at + "done" + (typeof d.durationMs === "number" ? ` in ${d.durationMs} ms` : "") };
    case "step.failed": return { text: at + "failed: " + String(d.message ?? d.code ?? ""), tone: "bad" };
    case "step.retry": return { text: at + "trying again" };
    case "log": return { text: at + String(d.message ?? ""), tone: d.level === "error" ? "bad" : undefined };
    case "variable.set": return { text: at + "$" + String(d.name) + " = " + showValue(d.value) };
    case "run.paused": return { text: "Paused" };
    case "run.resumed": return { text: "Resumed" };
    case "run.finished": return { text: "Run " + (d.status === "success" ? "finished" : String(d.status)), tone: d.status === "success" ? "good" : "bad" };
    default: return null;   // step.started and other detail stay out of the way
  }
}

export function RunOutput({ run, stepName, onStop, onDebug }:
  { run: LiveRun | null; stepName: (id: string) => string; onStop: () => void; onDebug: (c: DebugCommand) => void }) {
  if (!run) return <p className="st-muted st-pad">Press Run to try the agent here. The output appears in this panel.</p>;
  const lines = run.events.map((e) => ({ e, l: line(e, stepName) })).filter((x) => x.l);
  const done = isFinished(run);
  return (
    <div className="st-run">
      <div className="st-run-head">
        <span className={`st-run-state is-${run.state}`}>{STATE_WORDS[run.state] ?? run.state}</span>
        <span className="st-muted">{(run.elapsed / 1000).toFixed(1)} s{run.debug ? " · debugging" : ""}</span>
        {run.stopped && <span>at {stepName(run.stopped.stepId)}{run.stopped.reason ? ` (${run.stopped.reason})` : ""}</span>}
        <span className="st-bar-fill" />
        {run.stopped && <>
          <Button kind="run" icon="play" onClick={() => onDebug("continue")} title="Continue to the next breakpoint (F5)">Continue</Button>
          <Button kind="secondary" onClick={() => onDebug("stepOver")} title="Run this step and stop at the next (F10)">Step over</Button>
          <Button kind="secondary" onClick={() => onDebug("stepInto")} title="Go into a block or child agent (F11)">Step into</Button>
          <Button kind="secondary" onClick={() => onDebug("stepOut")} title="Finish the block and stop after it (Shift+F11)">Step out</Button>
        </>}
        {!done && <Button kind="secondary" onClick={onStop}>Stop</Button>}
      </div>
      <ol className="st-run-lines" aria-live="polite">
        {lines.map(({ e, l }) => <li key={e.seq} className={l!.tone ? "is-" + l!.tone : undefined}>{l!.text}</li>)}
      </ol>
      {done && run.result && <RunSummary result={run.result} stepName={stepName} />}
    </div>
  );
}

function RunSummary({ result, stepName }: { result: RunResult; stepName: (id: string) => string }) {
  const outputs = Object.entries(result.outputs ?? {});
  return (
    <div className="st-run-summary">
      {result.error && (
        <p className="st-run-error"><strong>{result.error.stepId ? stepName(result.error.stepId) + ": " : ""}{result.error.message}</strong>
          {" "}<span className="st-muted">({result.error.code}){result.error.suggestedFix ? " " + result.error.suggestedFix : ""}</span></p>
      )}
      {outputs.length > 0 && <>
        <h3 className="st-prop-section">Outputs</h3>
        <table className="st-table st-values"><tbody>
          {outputs.map(([k, v]) => <tr key={k}><th scope="row">${k}</th><td>{showValue(v)}</td></tr>)}
        </tbody></table>
      </>}
    </div>
  );
}

export function DebugValues({ run }: { run: LiveRun | null }) {
  const vars = Object.entries(run?.stopped?.variables ?? {});
  if (!run?.stopped) return <p className="st-muted st-pad">Values show here while a debugged run is stopped at a step. Press Debug, with a breakpoint on a step (F9).</p>;
  return (
    <table className="st-table st-values"><tbody>
      {vars.map(([k, v]) => <tr key={k}><th scope="row">${k}</th><td>{showValue(v)}</td></tr>)}
      {!vars.length && <tr><td className="st-empty">No values yet.</td></tr>}
    </tbody></table>
  );
}
