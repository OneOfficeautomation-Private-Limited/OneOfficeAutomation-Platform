import type { Accuracy, DocFlowApi } from "./api";
import { Loading, useLoad } from "./ui";

const pct = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `${Math.round(v * 1000) / 10}%`);
/** "1 in 80" for a small share; "none" for 0. */
const oneIn = (v: number | null | undefined) => (v === null || v === undefined ? "—" : v === 0 ? "none" : v >= 0.1 ? pct(v) : `about 1 in ${Math.round(1 / v)}`);

/**
 * How well a process reads, from its own reviewed documents: the share that needed no person, the fields reviewers
 * correct most, and what each review level would do (fields straight through, and wrong values that would slip by).
 */
export function AccuracyCard({ api, processId }: { api: DocFlowApi; processId: string }) {
  const [a] = useLoad(() => api.accuracy(processId), [api, processId]);
  if (!a) return <Loading />;
  return <AccuracyView a={a} />;
}

export function AccuracyView({ a }: { a: Accuracy }) {
  const top = a.fields.filter((f) => f.seen > 0).slice(0, 8);
  const current = a.levels.reduce((best, l) => (Math.abs(l.level - a.reviewLevel) < Math.abs(best.level - a.reviewLevel) ? l : best), a.levels[0] ?? { level: a.reviewLevel, straightThrough: null, wrongPassed: null, fields: 0 });
  return (
    <section className="df-card df-pad" aria-label="How well it reads" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div className="df-head-row"><b>How well it reads</b><span className="df-grow" />
        <span className="df-small">From {a.reviewed} reviewed of {a.documents} documents</span></div>
      {!a.reviewed ? <p className="df-muted" style={{ margin: 0 }}>Nothing reviewed yet: the numbers appear once people review documents of this process.</p> : <>
        <div className="df-grid df-g3">
          <div><div className="df-small">Needed no person</div><b style={{ fontSize: 20 }}>{pct(a.straightThrough)}</b></div>
          <div><div className="df-small">At today's review level ({pct(a.reviewLevel)})</div>
            <b>{pct(current.straightThrough)}</b> of fields go straight through; wrong ones that slip by: <b>{oneIn(current.wrongPassed)}</b></div>
          <div><div className="df-small">Fields measured</div><b>{a.measured}</b></div>
        </div>
        <div className="df-grid df-g2" style={{ alignItems: "start" }}>
          <table className="df-table" aria-label="Fields reviewers correct">
            <thead><tr><th>Field</th><th className="df-right">Reviewed</th><th className="df-right">Corrected</th></tr></thead>
            <tbody>{top.map((f) => <tr key={f.name}><td>{f.label}</td><td className="df-right df-num">{f.seen}</td><td className="df-right df-num">{pct(f.correctedShare)}</td></tr>)}</tbody>
          </table>
          <table className="df-table" aria-label="Review level advice">
            <thead><tr><th>Review a field under</th><th className="df-right">Straight through</th><th className="df-right">Wrong values let through</th></tr></thead>
            <tbody>{a.levels.map((l) => (
              <tr key={l.level} className={l === current ? "df-row-current" : undefined}><td>{pct(l.level)}{l === current ? " (now)" : ""}</td>
                <td className="df-right df-num">{pct(l.straightThrough)}</td><td className="df-right df-num">{oneIn(l.wrongPassed)}</td></tr>))}</tbody>
          </table>
        </div>
        <p className="df-small" style={{ margin: 0 }}>Change the level in the process settings, step 6 (Human review). A higher level sends more fields to people and lets fewer wrong values through.</p>
      </>}
    </section>
  );
}
