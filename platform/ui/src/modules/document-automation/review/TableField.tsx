import { Button } from "../../../components";
import type { ReviewTable, TableRow } from "../api";
import { Chip } from "../ui";

/**
 * A table field (line items) as read: one input per cell, rows that can be added or removed. Clicking a row shows it
 * on the page; a row DocFlow could not find on the page says so.
 */
export function TableField({ table, rows, onChange, selectedRow, onSelectRow, selected }:
  { table: ReviewTable; rows: TableRow[]; onChange: (rows: TableRow[]) => void; selectedRow: number | null;
    onSelectRow: (row: number | null) => void; selected: boolean }) {
  const set = (i: number, col: string, v: string) => onChange(rows.map((r, j) => (j === i ? { ...r, [col]: v } : r)));
  const empty = () => Object.fromEntries(table.columns.map((c) => [c.name, ""]));
  return (
    <section className={`df-review-tablefield${selected ? " is-selected" : ""}`} aria-label={table.label}>
      <div className="df-head-row" style={{ gap: 8 }}>
        <button type="button" className="df-link" onClick={() => onSelectRow(null)}>{table.label}</button>
        <span className="df-small">{rows.length} row{rows.length === 1 ? "" : "s"}</span>
        <span className="df-grow" />
        {table.place?.grounded === false && <Chip tone="warn">Not found on the page</Chip>}
        {table.flagged ? <Chip tone="warn">Check</Chip> : table.confidence !== null && <Chip tone={table.confidence >= 85 ? "ok" : "warn"} dot={false}>{table.confidence}%</Chip>}
      </div>
      {table.flagged && <div className="df-text-warn">{table.flagged.reason}</div>}
      <div className="df-table-wrap">
        <table className="df-table df-review-table">
          <thead><tr><th>#</th>{table.columns.map((c) => <th key={c.name}>{c.label}</th>)}<th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>
            {rows.map((r, i) => {
              const missing = table.rowPlaces?.[i]?.grounded === false;
              return (
                <tr key={i} className={i === selectedRow ? "is-selected" : undefined} aria-selected={i === selectedRow}>
                  <td className="df-small" title={missing ? "Not found on the page" : undefined}>{i + 1}{missing && <span className="df-text-warn"> · not found</span>}</td>
                  {table.columns.map((c) => (
                    <td key={c.name}>
                      <input id={`df-t-${table.id}-${i}-${c.name}`} className="ui-input df-num" dir="auto" value={r[c.name] ?? ""}
                        aria-label={`${c.label}, row ${i + 1}`} onFocus={() => onSelectRow(i)} onChange={(e) => set(i, c.name, e.target.value)} />
                    </td>
                  ))}
                  <td className="df-right"><Button kind="plain" aria-label={`Remove row ${i + 1}`} onClick={() => { onChange(rows.filter((_, j) => j !== i)); onSelectRow(null); }}>Remove</Button></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {!rows.length && <div className="df-small">No rows were read.</div>}
      <div><Button icon="plus" onClick={() => onChange([...rows, empty()])}>Add row</Button></div>
    </section>
  );
}
