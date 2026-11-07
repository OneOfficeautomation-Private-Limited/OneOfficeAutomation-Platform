import { useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../../components";
import type { DocFlowApi, DocType } from "../api";
import { Empty, FilterBar, FilterSelect, Loading, reportError, useLoad } from "../ui";
import { lastRunText, pct, topResult } from "./format";

const open = (id: string) => { location.hash = "#/testlab/sets/" + encodeURIComponent(id); };

/** Test sets: each one documents of one type with their right values; the last run's best result beside it. */
export function SetList({ api }: { api: DocFlowApi }) {
  const [data] = useLoad(() => Promise.all([api.testSets(), api.docTypes()]), [api]);
  const [type, setType] = useState("all");
  const [making, setMaking] = useState(false);
  const [seeding, setSeeding] = useState(false);
  if (!data) return <Loading />;
  const [sets, types] = data;
  const typeName = (id: string) => types.find((t) => t.id === id)?.name ?? id;
  const shown = sets.filter((s) => type === "all" || s.docType === type);
  const seed = async () => {
    setSeeding(true);
    try { open((await api.seedTestSet()).id); } catch (e) { reportError(e); } finally { setSeeding(false); }
  };

  return (
    <section className="df-card" aria-label="Test sets" style={{ overflow: "hidden" }}>
      <FilterBar count={`${shown.length} of ${sets.length} sets`} onClear={type !== "all" ? () => setType("all") : undefined}
        actions={<>
          <Button disabled={seeding} onClick={seed}>{seeding ? "Adding…" : "Add our sample set"}</Button>
          <Button kind="primary" icon="plus" onClick={() => setMaking(true)}>New test set</Button>
        </>}>
        <FilterSelect label="Document type" value={type} onChange={setType}
          options={[{ value: "all", label: "All types" }, ...[...new Set(sets.map((s) => s.docType))].map((id) => ({ value: id, label: typeName(id) }))]} />
      </FilterBar>
      {shown.length ? (
        <div className="df-table-wrap">
          <table className="df-table" style={{ minWidth: 760 }}>
            <thead><tr><th>Test set</th><th>Document type</th><th className="df-right">Documents</th><th className="df-right">Field accuracy</th>
              <th className="df-right">Straight through</th><th>Last run</th></tr></thead>
            <tbody>
              {shown.map((s) => {
                const top = topResult(s.lastRun);
                return (
                  <tr key={s.id}>
                    <td><a href={"#/testlab/sets/" + encodeURIComponent(s.id)}>{s.name}</a>{s.description && <div className="df-small">{s.description}</div>}</td>
                    <td>{typeName(s.docType)}</td>
                    <td className="df-right df-num">{s.itemCount}</td>
                    <td className="df-right df-num">{pct(top?.fieldAccuracy)}</td>
                    <td className="df-right df-num">{pct(top?.straightThrough)}</td>
                    <td className="df-muted">{lastRunText(s)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>{sets.length ? "No test set of this document type." : "No test set yet. Click New test set, or Add our sample set to start with generated invoices."}</Empty>
      )}
      {making && <NewSetDialog api={api} types={types} initialType={type === "all" ? "" : type} onClose={() => setMaking(false)} />}
    </section>
  );
}

function NewSetDialog({ api, types, initialType, onClose }: { api: DocFlowApi; types: DocType[]; initialType: string; onClose: () => void }) {
  const [name, setName] = useState("");
  const [docType, setDocType] = useState(initialType || types[0]?.id || "");
  const [description, setDescription] = useState("");
  const [saving, setSaving] = useState(false);
  const create = async () => {
    setSaving(true);
    try { open((await api.createTestSet(name.trim(), docType, description.trim())).id); onClose(); } catch (e) { reportError(e); setSaving(false); }
  };
  return (
    <Dialog open onClose={onClose} title="New test set" width={480}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!name.trim() || !docType || saving} onClick={create}>Create set</Button></>}>
      <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="e.g. AP invoices, hard scans" onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Document type">{(id) => <Select id={id} value={docType} onChange={(e) => setDocType(e.target.value)} options={types.map((t) => ({ value: t.id, label: t.name }))} />}</Field>
      <Field label="Description (optional)">{(id) => <TextInput id={id} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
    </Dialog>
  );
}
