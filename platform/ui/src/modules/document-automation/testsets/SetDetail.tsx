import { useEffect, useState } from "react";
import { Button, Dialog, Icon } from "../../../components";
import type { Combo, DocFlowApi, SetRun, TestSetItem } from "../api";
import { Chip, Empty, FilterBar, Loading, reportError, useLoad } from "../ui";
import { ItemDialog } from "./ItemDialog";
import { RunDialog } from "./RunDialog";
import { RunResults } from "./RunResults";
import { day, engineChoices, pct, topResult, value } from "./format";

/** How often a running test is looked at again. */
export const POLL_MS = 2000;

const SOURCE: Record<string, string> = { document: "Reviewed", upload: "Uploaded", seed: "Generated" };

/**
 * One test set: its documents with their right values, Run test, the run's results (updating while it runs) and the
 * past runs. #/testlab/sets/<id>.
 */
export function SetDetail({ api, setId }: { api: DocFlowApi; setId: string }) {
  const [data, reload] = useLoad(() => Promise.all([api.testSet(setId), api.docTypes(), api.engines()]), [api, setId]);
  const [runs, reloadRuns] = useLoad(() => api.setRuns(setId, 20), [api, setId]);
  const [runId, setRunId] = useState<string | null>(null);
  const [editing, setEditing] = useState<TestSetItem | "new" | null>(null);
  const [choosing, setChoosing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const shownId = runId ?? runs?.[0]?.id ?? null;
  const run = usePolledRun(api, setId, shownId, () => { reloadRuns(); reload(); });
  if (!data) return <Loading />;

  const [set, types, engines] = data;
  const type = types.find((t) => t.id === set.docType);
  const fields = (type?.fields ?? []).filter((f) => f.type !== "Table").map((f) => ({ key: f.key, label: f.label }));
  const fieldLabel = (k: string) => fields.find((f) => f.key === k)?.label ?? k;
  const choices = engineChoices(engines.options);
  const items = set.items ?? [];

  const saveItem = async (file: File | null, expected: Record<string, string>, tags: string[]) => {
    try {
      if (editing === "new" && file) await api.addTestItem(set.id, file, expected, tags);
      else if (editing && editing !== "new") await api.updateTestItem(set.id, editing.id, expected, tags);
      setEditing(null); reload();
    } catch (e) { reportError(e); }
  };
  const remove = async (i: TestSetItem) => { try { await api.deleteTestItem(set.id, i.id); reload(); } catch (e) { reportError(e); } };
  const start = async (combos: Combo[]) => {
    try { const r = await api.startSetRun(set.id, combos); setChoosing(false); setRunId(r.id); reloadRuns(); } catch (e) { reportError(e); }
  };
  const drop = async () => {
    try { await api.deleteTestSet(set.id); location.hash = "#/testlab/sets"; } catch (e) { reportError(e); }
  };

  return (
    <div className="df-stack">
      <a className="df-back" href="#/testlab/sets"><Icon name="chevronLeft" size={12} stroke={2.4} />All test sets</a>
      <div className="df-page-head">
        <div>
          <div className="df-head-row" style={{ gap: 8 }}><h3 className="df-section-title">{set.name}</h3><Chip tone="info" dot={false}>{type?.name ?? set.docType}</Chip></div>
          {set.description && <p>{set.description}</p>}
        </div>
        <span className="df-grow" />
        <Button kind="plain" onClick={() => setDeleting(true)}>Delete set</Button>
        <Button kind="run" icon="play" disabled={!items.length || run?.status === "running"} onClick={() => setChoosing(true)}>Run test</Button>
      </div>

      <Items items={items} setId={set.id} api={api} fieldLabel={fieldLabel} onAdd={() => setEditing("new")} onEdit={setEditing} onRemove={remove} />

      {run && <RunResults run={run} name={choices.name} fieldLabel={fieldLabel} />}
      {(runs?.length ?? 0) > 0 && <PastRuns runs={runs!.map((r) => (r.id === run?.id ? run : r))} shown={shownId} name={choices.name} onOpen={setRunId} />}

      {editing && <ItemDialog item={editing === "new" ? null : editing} fields={fields} onSave={saveItem} onClose={() => setEditing(null)} />}
      {choosing && <RunDialog ocr={choices.ocr} extraction={choices.extraction} onRun={start} onClose={() => setChoosing(false)} />}
      <Dialog open={deleting} onClose={() => setDeleting(false)} title="Delete test set?" width={440}
        footer={<><Button onClick={() => setDeleting(false)}>Cancel</Button><Button kind="danger" onClick={drop}>Delete</Button></>}>
        <p style={{ margin: 0 }}>{set.name}, its {items.length} documents and its past runs are deleted. Documents in processes are not touched.</p>
      </Dialog>
    </div>
  );
}

/** A run with its rows, looked at again every POLL_MS while it is running; onDone when it finishes. */
function usePolledRun(api: DocFlowApi, setId: string, runId: string | null, onDone: () => void): SetRun | null {
  const [run, setRun] = useState<SetRun | null>(null);
  useEffect(() => {
    setRun(null);
    if (!runId) return;
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let wasRunning = false;
    const look = async () => {
      try {
        const r = await api.setRun(setId, runId);
        if (!live) return;
        setRun(r);
        if (r.status === "running") { wasRunning = true; timer = setTimeout(look, POLL_MS); } else if (wasRunning) onDone();
      } catch (e) { if (live) reportError(e); }
    };
    void look();
    return () => { live = false; clearTimeout(timer); };
  }, [api, setId, runId]);   // onDone is a new function each render
  return run;
}

function Items({ items, setId, api, fieldLabel, onAdd, onEdit, onRemove }: {
  items: TestSetItem[]; setId: string; api: DocFlowApi; fieldLabel: (k: string) => string;
  onAdd: () => void; onEdit: (i: TestSetItem) => void; onRemove: (i: TestSetItem) => void;
}) {
  return (
    <section className="df-card" aria-label="Documents in the set" style={{ overflow: "hidden" }}>
      <FilterBar count={`${items.length} document${items.length === 1 ? "" : "s"}`}
        actions={<Button icon="plus" onClick={onAdd}>Add a document</Button>}><b>Documents and their right values</b></FilterBar>
      {items.length ? (
        <div className="df-table-wrap">
          <table className="df-table" style={{ minWidth: 760 }}>
            <thead><tr><th>Document</th><th>Right values</th><th>Tags</th><th>From</th><th /></tr></thead>
            <tbody>
              {items.map((i) => {
                const values = Object.entries(i.expected);
                return (
                  <tr key={i.id}>
                    <td><a href={api.testItemFileUrl(setId, i.id)} target="_blank" rel="noreferrer">{i.fileName}</a></td>
                    <td><span className="df-small">{values.length} value{values.length === 1 ? "" : "s"}:</span>{" "}
                      {values.slice(0, 3).map(([k, v]) => <span key={k} className="df-pair"><span className="df-muted">{fieldLabel(k)}</span> <span className="df-num" dir="auto">{value(v)}</span></span>)}
                      {values.length > 3 && <span className="df-small"> …</span>}</td>
                    <td><span className="df-tags">{i.tags.map((t) => <Chip key={t} tone="mute" dot={false}>{t}</Chip>)}</span></td>
                    <td className="df-muted">{SOURCE[i.source] ?? i.source}</td>
                    <td className="df-right" style={{ whiteSpace: "nowrap" }}>
                      <Button kind="plain" aria-label={`Edit right values of ${i.fileName}`} onClick={() => onEdit(i)}>Edit</Button>
                      <Button kind="plain" aria-label={`Remove ${i.fileName}`} onClick={() => onRemove(i)}>Remove</Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : <Empty>No documents yet. Click Add a document, or open an approved document in Review and click Add to test set.</Empty>}
    </section>
  );
}

function PastRuns({ runs, shown, name, onOpen }: { runs: SetRun[]; shown: string | null; name: (id: string) => string; onOpen: (id: string) => void }) {
  return (
    <section className="df-card" aria-label="Past runs" style={{ overflow: "hidden" }}>
      <FilterBar count={`${runs.length} run${runs.length === 1 ? "" : "s"}`}><b>Past runs</b></FilterBar>
      <div className="df-table-wrap">
        <table className="df-table">
          <thead><tr><th>Started</th><th>Engines</th><th className="df-right">Best field accuracy</th><th>Status</th><th /></tr></thead>
          <tbody>
            {runs.map((r) => {
              const top = topResult(r);
              return (
                <tr key={r.id} className={r.id === shown ? "df-row-current" : undefined}>
                  <td className="df-num">{day(r.createdAt)}</td>
                  <td className="df-muted">{r.combos.map((c) => `${name(c.ocr)} + ${name(c.extraction)}`).join(" · ")}</td>
                  <td className="df-right df-num">{pct(top?.fieldAccuracy)}</td>
                  <td>{r.status === "running" ? <Chip tone="info">Running</Chip> : r.status === "failed" ? <Chip tone="bad">Failed</Chip> : <Chip tone="ok">Done</Chip>}</td>
                  <td className="df-right">{r.id === shown ? <span className="df-small">Shown above</span>
                    : <Button kind="plain" aria-label={`Open the run of ${day(r.createdAt)}`} onClick={() => onOpen(r.id)}>Open</Button>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
