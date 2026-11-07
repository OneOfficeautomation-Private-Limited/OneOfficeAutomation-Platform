import { useState, type ReactNode } from "react";
import type { ComboResult, SetRun, SetRunRow } from "../api";
import { HINTS } from "../help";
import { Chip, FilterBar, FilterSelect, Hint } from "../ui";
import { bestOf, day, money, pct, secs, value } from "./format";

type Name = (engine: string) => string;
const comboName = (c: { ocr: string; extraction: string }, name: Name) => `${name(c.ocr)} + ${name(c.extraction)}`;

/** A run's results: one row per engine combination (the best per column marked), accuracy per field, and the documents. */
export function RunResults({ run, name, fieldLabel }: { run: SetRun; name: Name; fieldLabel: (key: string) => string }) {
  const results = run.results;
  return (
    <div className="df-stack">
      {run.status === "running" && (
        <div className="df-card df-pad" role="status"><Chip tone="info">Running</Chip> {results.length} of {run.combos.length} combinations done. This page updates by itself.</div>
      )}
      {run.status === "failed" && <div className="df-alert" role="alert"><b>The run stopped.</b> {run.error}</div>}
      {results.length > 0 && <Summary results={results} name={name} started={run.createdAt} />}
      {results.some((r) => r.byField && Object.keys(r.byField).length) && <ByField results={results} name={name} fieldLabel={fieldLabel} />}
      {(run.rows?.length ?? 0) > 0 && <Rows rows={run.rows!} name={name} fieldLabel={fieldLabel} />}
    </div>
  );
}

const Best = () => <span className="df-best"><Chip tone="ok" dot={false}>Best</Chip></span>;

function Summary({ results, name, started }: { results: ComboResult[]; name: Name; started: string }) {
  const best = {
    accuracy: bestOf(results, (r) => r.fieldAccuracy), straight: bestOf(results, (r) => r.straightThrough),
    wrong: bestOf(results, (r) => r.wronglyAutoApprovedRate, true), cost: bestOf(results, (r) => r.costPer1000PagesUsd, true),
    speed: bestOf(results, (r) => r.secondsPerPage, true),
  };
  const cell = (text: ReactNode, isBest: boolean, tone?: string) =>
    <td className={`df-right df-num${tone ? " " + tone : ""}`}>{text}{isBest && <Best />}</td>;
  return (
    <section className="df-card" aria-label="Results by combination" style={{ overflow: "hidden" }}>
      <FilterBar><b>Results by combination</b><span className="df-muted">run {day(started)}</span></FilterBar>
      <div className="df-table-wrap">
        <table className="df-table" style={{ minWidth: 820 }}>
          <thead><tr><th>Engines (OCR + fields)</th><th className="df-right">Field accuracy</th><th className="df-right">Straight through</th>
            <th className="df-right">Wrongly auto-approved<Hint about="Wrongly auto-approved" text={HINTS.wronglyAutoApproved} /></th>
            <th className="df-right">Cost per 1,000 pages</th><th className="df-right">Seconds per page</th></tr></thead>
          <tbody>
            {results.map((r) => (
              <tr key={r.label}>
                <td><b>{comboName(r, name)}</b>{r.errors ? <div className="df-small df-text-bad">{r.errors} document{r.errors > 1 ? "s" : ""} could not be read</div> : null}</td>
                {r.skipped ? <td colSpan={5} className="df-muted">Skipped: {r.skipped}</td> : <>
                  {cell(pct(r.fieldAccuracy), best.accuracy.has(r))}
                  {cell(pct(r.straightThrough), best.straight.has(r))}
                  {cell(<>{r.wronglyAutoApproved ?? 0} <span className="df-small">({pct(r.wronglyAutoApprovedRate)})</span></>, best.wrong.has(r), r.wronglyAutoApproved ? "df-text-bad" : undefined)}
                  {cell(<>{money(r.costPer1000PagesUsd)}{r.costKnown === false && <span className="df-small" title="An engine has no list price"> + contract</span>}</>, best.cost.has(r))}
                  {cell(secs(r.secondsPerPage), best.speed.has(r))}
                </>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ByField({ results, name, fieldLabel }: { results: ComboResult[]; name: Name; fieldLabel: (key: string) => string }) {
  const ran = results.filter((r) => !r.skipped && r.byField);
  const fields = [...new Set(ran.flatMap((r) => Object.keys(r.byField!)))];
  return (
    <section className="df-card" aria-label="Accuracy per field" style={{ overflow: "hidden" }}>
      <FilterBar><b>Accuracy per field</b></FilterBar>
      <div className="df-table-wrap">
        <table className="df-table">
          <thead><tr><th>Field</th>{ran.map((r) => <th key={r.label} className="df-right">{comboName(r, name)}</th>)}</tr></thead>
          <tbody>
            {fields.map((f) => {
              const best = bestOf(ran, (r) => r.byField![f]);
              return (
                <tr key={f}>
                  <td>{fieldLabel(f)}</td>
                  {ran.map((r) => {
                    const v = r.byField![f];
                    return <td key={r.label} className={`df-right df-num${v !== undefined && v < 0.9 ? " df-text-bad" : ""}`}>{pct(v)}{best.has(r) && best.size < ran.length && <Best />}</td>;
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const outcome = (r: SetRunRow) =>
  r.error ? <Chip tone="bad">Could not read: {r.error}</Chip>
    : r.wronglyAutoApproved ? <Chip tone="bad">Wrongly auto-approved</Chip>
    : r.straightThrough ? <Chip tone="ok">Straight through</Chip> : <Chip tone="warn">To review</Chip>;

function Rows({ rows, name, fieldLabel }: { rows: SetRunRow[]; name: Name; fieldLabel: (key: string) => string }) {
  const [show, setShow] = useState<"wrong" | "all">("wrong");
  const wrong = (r: SetRunRow) => !!r.error || Object.values(r.fields).some((f) => !f.ok);
  const shown = rows.filter((r) => show === "all" || wrong(r));
  return (
    <section className="df-card" aria-label="Documents read" style={{ overflow: "hidden" }}>
      <FilterBar count={`${shown.length} of ${rows.length} readings`}>
        <b>Documents read</b>
        <FilterSelect label="Show" value={show} onChange={setShow} options={[{ value: "wrong", label: "Only with a wrong field" }, { value: "all", label: "All" }]} />
      </FilterBar>
      {shown.length ? (
        <div className="df-table-wrap">
          <table className="df-table" style={{ minWidth: 820 }}>
            <thead><tr><th>Document</th><th>Engines</th><th>Wrong fields</th><th>Result</th><th className="df-right">Time</th></tr></thead>
            <tbody>
              {shown.map((r) => {
                const bad = Object.entries(r.fields).filter(([, f]) => !f.ok);
                return (
                  <tr key={r.itemId + r.label} className={r.wronglyAutoApproved ? "df-row-flag" : undefined}>
                    <td><b>{r.fileName}</b>{r.tags.length > 0 && <div className="df-small">{r.tags.join(", ")}</div>}</td>
                    <td className="df-muted">{comboName(r, name)}</td>
                    <td>{bad.length ? <ul className="df-wrong">{bad.map(([k, f]) => (
                      <li key={k}><b>{fieldLabel(k)}</b>: right <span className="df-num" dir="auto">{value(f.want)}</span>, read <span className="df-num df-text-bad" dir="auto">{value(f.got)}</span></li>
                    ))}</ul> : <span className="df-muted">None</span>}</td>
                    <td>{outcome(r)}</td>
                    <td className="df-right df-num">{secs(r.seconds)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : <div className="df-empty">Every field was read right.</div>}
    </section>
  );
}
