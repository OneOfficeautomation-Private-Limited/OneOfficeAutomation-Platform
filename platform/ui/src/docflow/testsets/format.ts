import type { ComboResult, EngineOption, SetRun, TestSet } from "../api";

/** How the test-set screens show numbers: rates 0–1 as percentages, money in dollars, time per page in seconds. */
export const pct = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `${Math.round(v * 1000) / 10}%`);
export const money = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `$${v.toFixed(2)}`);
export const secs = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `${v < 1 ? v.toFixed(2) : v.toFixed(1)} s`);
export const day = (iso: string | null | undefined) => {
  const d = new Date(iso ?? "");
  return isNaN(d.getTime()) ? "—" : d.toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
};
export const value = (v: unknown) => (v === null || v === undefined || v === "" ? "—" : typeof v === "object" ? JSON.stringify(v) : String(v));

/** The extraction engines (they read the fields); every other engine reads pages (OCR). */
export const EXTRACTORS = ["builtin-rules", "claude", "llm-local", "llm-cloud"];

/** The engines a run can use, from the Engines page's list: OCR first ("auto" = as routed live), then extraction. */
export function engineChoices(options: EngineOption[]) {
  const all = options.flatMap((o) => o.engines);
  const ocr = [{ id: "auto", name: "Routing (as live)", ready: true },
    ...all.filter((e) => !EXTRACTORS.includes(e.id) && e.id !== "pdf-text").map((e) => ({ id: e.id, name: e.name, ready: e.status === "ready" }))];
  const extraction = EXTRACTORS.map((id) => all.find((e) => e.id === id)).filter((e): e is NonNullable<typeof e> => !!e)
    .map((e) => ({ id: e.id, name: e.name, ready: e.status === "ready" }));
  const names: Record<string, string> = Object.fromEntries([...ocr, ...extraction].map((e) => [e.id, e.name]));
  return { ocr, extraction, name: (id: string) => (id === "auto" ? "As live" : names[id] ?? id) };
}

/** The result that read the most fields right (ties: more straight through). */
export function topResult(run: SetRun | null | undefined): ComboResult | null {
  const ran = (run?.results ?? []).filter((r) => !r.skipped && r.fieldAccuracy !== null && r.fieldAccuracy !== undefined);
  return ran.sort((a, b) => (b.fieldAccuracy! - a.fieldAccuracy!) || ((b.straightThrough ?? 0) - (a.straightThrough ?? 0)))[0] ?? null;
}

export const lastRunText = (s: TestSet) =>
  !s.lastRun ? "Never run" : s.lastRun.status === "running" ? "Running…" : s.lastRun.status === "failed" ? "Failed " + day(s.lastRun.createdAt) : day(s.lastRun.createdAt);

/**
 * Which results are best in a column: the highest (or lowest) value among the combinations that ran. Only when two or
 * more ran, so one result alone is not called "best".
 */
export function bestOf(results: ComboResult[], pick: (r: ComboResult) => number | null | undefined, lowest = false): Set<ComboResult> {
  const ran = results.filter((r) => !r.skipped && pick(r) !== null && pick(r) !== undefined);
  if (ran.length < 2) return new Set();
  const values = ran.map((r) => pick(r)!);
  const top = lowest ? Math.min(...values) : Math.max(...values);
  return new Set(ran.filter((r) => pick(r) === top));
}
