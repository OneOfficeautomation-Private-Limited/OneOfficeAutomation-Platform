import type { Flow, RunResult, StudioApi } from "../api/client";

/**
 * Runs a small flow to the end and returns its result: used for checks such as testing a model connection or
 * drafting an AI agent's role and plan, where only the answer matters, not the live output.
 */
export async function runOnce(api: StudioApi, flow: Flow, inputs: Record<string, unknown> = {}, timeoutMs = 120_000): Promise<RunResult> {
  const id = await api.startRun(flow, inputs);
  const until = Date.now() + timeoutMs;
  let after = 0;
  for (;;) {
    const st = await api.runState(id, after);
    after = st.events.reduce((m, e) => Math.max(m, e.seq), after);
    if (st.result) return st.result;
    if (Date.now() > until) { await api.runControl(id, "stop").catch(() => {}); return { status: "timeout", error: { code: "STUDIO.TIMEOUT", message: "No answer in time" } }; }
    await new Promise((r) => setTimeout(r, 400));
  }
}

/** A one-step flow around an action, for runOnce. */
export function oneStep(action: string, args: Record<string, unknown>, out: Record<string, string>): Flow {
  return {
    kind: "TaskBot", id: "studio_check", name: "Studio check",
    variables: Object.values(out).map((v) => ({ name: v, type: "Any", direction: "output" as const })),
    steps: [{ id: "s1", action, args, out }],
  };
}
