import "./consoleApi";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CrApi, CrUser } from "../../api/cr";
import type { BucketFile, DataBucket, DataEntity, DataField, DataRecord } from "./consoleApi";
import { Button, Dialog, Field, HelpButton, Select, TabPanel, Tabs, TextInput, type HelpTopic, PageError } from "../../components";
import { RefreshButton, RowMenu, rowClick, when } from "../../console/ListParts";

/**
 * Manage > Data store (gap G66, after UiPath's Data Service and Storage Buckets): entities (tables of business records
 * whose fields an administrator defines) and storage buckets (file areas runs read and write). Agents use both with
 * the Data store toolkit. data.view sees, data.edit changes records and files, data.manage defines entities and buckets.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

const HELP: HelpTopic = { title: "Data store", about: "Business records and files that agents read and write while they run, kept in One CommandCenter.",
  items: [
    { name: "Entities", text: "Tables of records, such as suppliers or cases. An administrator defines the fields: text, number, yes/no, date (yyyy-MM-dd) "
      + "or a choice from a list, each required or not and unique or not. Agents use Create, Update, Get, Query and Delete record." },
    { name: "Storage buckets", text: "Named file areas. Agents upload, download, list and delete files while they run; repository files, by contrast, are read-only to runs." },
    { name: "Folder", text: "An entity or bucket with a folder is only for agents in that folder or below it. Empty: every agent may use it." },
    { name: "Who may do what", text: "See the data store; Change data store records and files; Define data store entities and buckets (administrators). "
      + "Changes made here are written to the audit trail." },
  ] };

const TYPE_LABEL: Record<string, string> = { text: "Text", number: "Number", boolean: "Yes/no", date: "Date", choice: "Choice" };
const size = (b: number) => b < 1024 ? b + " B" : b < 1048576 ? (b / 1024).toFixed(1) + " KB" : (b / 1048576).toFixed(1) + " MB";
const show = (v: unknown) => v === true ? "Yes" : v === false ? "No" : v == null ? "" : String(v);

export function DataStore({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState("entities");
  const can = { edit: user.permissions.includes("data.edit"), manage: user.permissions.includes("data.manage") };
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-data-title">
      <header className="cc-list-head">
        <h1 id="cc-data-title">Data store</h1>
        <HelpButton topic={HELP} />
        <span className="ov-fill" />
      </header>
      <Tabs label="Data store" value={tab} onChange={setTab} tabs={[{ value: "entities", label: "Entities" }, { value: "buckets", label: "Storage buckets" }]}>
        <TabPanel value="entities"><Entities api={api} can={can} onNotice={onNotice} /></TabPanel>
        <TabPanel value="buckets"><Buckets api={api} can={can} onNotice={onNotice} /></TabPanel>
      </Tabs>
    </section>
  );
}

type Can = { edit: boolean; manage: boolean };

function Entities({ api, can, onNotice }: { api: CrApi; can: Can; onNotice: (t: string) => void }) {
  const [list, setList] = useState<DataEntity[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<DataEntity | "new" | null>(null);
  const [deleting, setDeleting] = useState<DataEntity | null>(null);
  const [open, setOpen] = useState<DataEntity | null>(null);
  const load = useCallback(async () => { try { setList(await api.dataEntities()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  if (open) return <Records api={api} entity={open} can={can} onBack={() => { setOpen(null); load(); }} onNotice={onNotice} />;
  const remove = (e: DataEntity) => api.deleteDataEntity(e.id).then(() => { onNotice("Deleted the entity " + e.name + " and its records."); setDeleting(null); load(); },
    (x) => { setDeleting(null); setError(message(x)); });
  return (
    <>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters">{list && <span className="cc-list-count">{list.length} entities</span>}</div>
        <div className="cc-list-right" role="toolbar" aria-label="Entity tools">
          {can.manage && <Button kind="primary" onClick={() => setEditing("new")}>New entity</Button>}
          <RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the entity {deleting.name} and its {deleting.records} records? Agents that use it will fail.</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Entities list">
            <thead><tr>{["Entity", "Folder", "Fields", "Records", "Changed"].map((h) => <th key={h} scope="col">{h}</th>)}
              {can.manage && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {(list ?? []).map((e) => (
                <tr key={e.id} onClick={rowClick(() => setOpen(e))}>
                  <td title={e.description ?? undefined}>{e.name}</td>
                  <td>{e.folder || "Every folder"}</td>
                  <td className="cc-clip" title={e.fields.map((f) => f.name).join(", ")}>{e.fields.map((f) => f.name).join(", ")}</td>
                  <td>{e.records.toLocaleString()}</td>
                  <td>{when(e.updated)}</td>
                  {can.manage && <td className="cc-list-menu"><RowMenu label={"Actions for " + e.name} items={[
                    { key: "edit", label: "Edit fields", onSelect: () => setEditing(e) },
                    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(e) }]} /></td>}
                </tr>
              ))}
              {list && !list.length && <tr className="is-static"><td colSpan={6} className="cc-none">No entities yet.{can.manage ? " Make one with New entity." : ""}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={6} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <EntityDialog api={api} entity={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </>
  );
}

function EntityDialog({ api, entity, onClose, onSaved }: { api: CrApi; entity: DataEntity | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: entity?.name ?? "", folder: entity?.folder ?? "", description: entity?.description ?? "" });
  const [fields, setFields] = useState<(DataField & { choicesText?: string })[]>(entity?.fields.map((f) => ({ ...f, choicesText: (f.choices ?? []).join(", ") }))
    ?? [{ name: "name", type: "text", required: true, unique: true }]);
  const [error, setError] = useState<string | null>(null);
  const setField = (i: number, f: Partial<DataField & { choicesText?: string }>) => setFields(fields.map((x, j) => j === i ? { ...x, ...f } : x));
  const save = () => api.saveDataEntity(entity?.id ?? null, { name: v.name.trim(), folder: v.folder.trim(), description: v.description,
    fields: fields.map(({ choicesText, ...f }) => ({ ...f, name: f.name.trim(), ...(f.type === "choice" ? { choices: (choicesText ?? "").split(",").map((c) => c.trim()).filter(Boolean) } : {}) })) })
    .then(() => onSaved((entity ? "Saved " : "Made the entity ") + v.name.trim() + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={entity ? "Entity " + entity.name : "New entity"} width={760} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim() || !fields.length} onClick={save}>{entity ? "Save" : "Make the entity"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={80} placeholder="Suppliers" onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Only for agents in folder" hint="Empty: every agent">{(id) => <TextInput id={id} value={v.folder} placeholder="Finance" onChange={(e) => setV({ ...v, folder: e.target.value })} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} maxLength={500} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      <h3 className="cc-log-title">Fields</h3>
      {entity && <p className="cc-none">Records keep their values when fields change; a value that no longer fits is checked the next time the record is saved.</p>}
      <table className="cc-list-table" aria-label="Fields">
        <thead><tr><th scope="col">Field</th><th scope="col">Type</th><th scope="col">Required</th><th scope="col">Unique</th><th scope="col">Choices</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
        <tbody>
          {fields.map((f, i) => (
            <tr key={i} className="is-static">
              <td><TextInput aria-label={"Field " + (i + 1) + " name"} value={f.name} maxLength={60} onChange={(e) => setField(i, { name: e.target.value })} /></td>
              <td><Select aria-label={"Field " + (i + 1) + " type"} value={f.type} onChange={(e) => setField(i, { type: e.target.value })}
                options={Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }))} /></td>
              <td><input type="checkbox" aria-label={"Field " + (i + 1) + " required"} checked={!!f.required} onChange={(e) => setField(i, { required: e.target.checked })} /></td>
              <td><input type="checkbox" aria-label={"Field " + (i + 1) + " unique"} checked={!!f.unique} onChange={(e) => setField(i, { unique: e.target.checked })} /></td>
              <td>{f.type === "choice" && <TextInput aria-label={"Field " + (i + 1) + " choices"} placeholder="Open, Closed" value={f.choicesText ?? ""} onChange={(e) => setField(i, { choicesText: e.target.value })} />}</td>
              <td><Button kind="plain" aria-label={"Remove field " + (f.name || i + 1)} onClick={() => setFields(fields.filter((_, j) => j !== i))}>Remove</Button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p><Button kind="secondary" onClick={() => setFields([...fields, { name: "", type: "text", required: false, unique: false }])}>Add field</Button></p>
    </Dialog>
  );
}

function Records({ api, entity, can, onBack, onNotice }: { api: CrApi; entity: DataEntity; can: Can; onBack: () => void; onNotice: (t: string) => void }) {
  const [data, setData] = useState<{ records: DataRecord[]; total: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [editing, setEditing] = useState<DataRecord | "new" | null>(null);
  const [deleting, setDeleting] = useState<DataRecord | null>(null);
  const load = useCallback(async () => { try { setData(await api.dataRecords(entity.id, q)); setError(null); } catch (e) { setError(message(e)); } }, [api, entity.id, q]);
  useEffect(() => { load(); }, [load]);
  const remove = (r: DataRecord) => api.deleteDataRecord(entity.id, r.id).then(() => { onNotice("Deleted the record " + r.id + "."); setDeleting(null); load(); },
    (e) => { setDeleting(null); setError(message(e)); });
  const cols = entity.fields.length + 2 + (can.edit ? 1 : 0);
  return (
    <>
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <Button kind="plain" onClick={onBack}>← Entities</Button>
          <strong>{entity.name}</strong>
          <span className="cc-list-search"><TextInput type="search" aria-label="Search records" placeholder="Search records" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          {data && <span className="cc-list-count">{data.total.toLocaleString()} records</span>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Record tools">
          {can.edit && <Button kind="primary" onClick={() => setEditing("new")}>Add record</Button>}
          <RefreshButton onClick={load} /></div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the record {deleting.id}?</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label={entity.name + " records"}>
            <thead><tr><th scope="col">Id</th>{entity.fields.map((f) => <th key={f.name} scope="col">{f.name}</th>)}<th scope="col">Changed</th>
              {can.edit && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {(data?.records ?? []).map((r) => (
                <tr key={r.id} onClick={rowClick(() => can.edit && setEditing(r))} className={can.edit ? undefined : "is-static"}>
                  <td className="cc-mono">{r.id}</td>
                  {entity.fields.map((f) => <td key={f.name} className="cc-clip">{show(r.data[f.name])}</td>)}
                  <td title={r.updated_by ?? undefined}>{when(r.updated)}</td>
                  {can.edit && <td className="cc-list-menu"><RowMenu label={"Actions for record " + r.id} items={[
                    { key: "edit", label: "Edit", onSelect: () => setEditing(r) },
                    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(r) }]} /></td>}
                </tr>
              ))}
              {data && !data.records.length && <tr className="is-static"><td colSpan={cols} className="cc-none">{q ? "No records match." : "No records yet."}</td></tr>}
            </tbody>
          </table>
        </div>
        {data && data.total > data.records.length && <div className="cc-list-pager"><span>The first {data.records.length} of {data.total.toLocaleString()}; search to find others.</span></div>}
      </div>
      {editing && <RecordDialog api={api} entity={entity} record={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </>
  );
}

function RecordDialog({ api, entity, record, onClose, onSaved }: { api: CrApi; entity: DataEntity; record: DataRecord | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState<Record<string, string | boolean>>(() => Object.fromEntries(entity.fields.map((f) => {
    const x = record?.data[f.name];
    return [f.name, f.type === "boolean" ? x === true : x == null ? "" : String(x)];
  })));
  const [error, setError] = useState<string | null>(null);
  const save = () => api.saveDataRecord(entity.id, record?.id ?? null, Object.fromEntries(entity.fields.map((f) => [f.name, v[f.name] === "" ? null : v[f.name]])))
    .then((r) => onSaved((record ? "Saved the record " : "Added the record ") + r.id + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={record ? "Record " + record.id : "New " + entity.name + " record"} width={620} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={save}>{record ? "Save" : "Add"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {entity.fields.map((f) => (
        <Field key={f.name} label={f.name + (f.required ? " (required)" : "")} hint={TYPE_LABEL[f.type] + (f.unique ? ", unique" : "")}>
          {(id) => f.type === "boolean"
            ? <input id={id} type="checkbox" checked={v[f.name] === true} onChange={(e) => setV({ ...v, [f.name]: e.target.checked })} />
            : f.type === "choice"
              ? <Select id={id} value={String(v[f.name])} onChange={(e) => setV({ ...v, [f.name]: e.target.value })}
                  options={[{ value: "", label: "(none)" }, ...(f.choices ?? []).map((c) => ({ value: c, label: c }))]} />
              : <TextInput id={id} type={f.type === "number" ? "number" : f.type === "date" ? "date" : "text"} value={String(v[f.name])}
                  onChange={(e) => setV({ ...v, [f.name]: e.target.value })} />}
        </Field>
      ))}
    </Dialog>
  );
}

function Buckets({ api, can, onNotice }: { api: CrApi; can: Can; onNotice: (t: string) => void }) {
  const [list, setList] = useState<DataBucket[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<DataBucket | "new" | null>(null);
  const [deleting, setDeleting] = useState<DataBucket | null>(null);
  const [open, setOpen] = useState<DataBucket | null>(null);
  const load = useCallback(async () => { try { setList(await api.dataBuckets()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  if (open) return <BucketFiles api={api} bucket={open} can={can} onBack={() => { setOpen(null); load(); }} onNotice={onNotice} />;
  const remove = (b: DataBucket) => api.deleteDataBucket(b.id).then(() => { onNotice("Deleted the bucket " + b.name + " and its files."); setDeleting(null); load(); },
    (x) => { setDeleting(null); setError(message(x)); });
  return (
    <>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
        <div className="cc-list-filters">{list && <span className="cc-list-count">{list.length} buckets</span>}</div>
        <div className="cc-list-right" role="toolbar" aria-label="Bucket tools">
          {can.manage && <Button kind="primary" onClick={() => setEditing("new")}>New bucket</Button>}
          <RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the bucket {deleting.name} and its {deleting.files} files?</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Storage buckets list">
            <thead><tr>{["Bucket", "Folder", "Files", "Size", "Largest file"].map((h) => <th key={h} scope="col">{h}</th>)}
              {can.manage && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {(list ?? []).map((b) => (
                <tr key={b.id} onClick={rowClick(() => setOpen(b))}>
                  <td title={b.description ?? undefined}>{b.name}</td>
                  <td>{b.folder || "Every folder"}</td>
                  <td>{b.files}</td>
                  <td>{size(b.bytes)}</td>
                  <td>{b.max_mb} MB</td>
                  {can.manage && <td className="cc-list-menu"><RowMenu label={"Actions for " + b.name} items={[
                    { key: "edit", label: "Edit", onSelect: () => setEditing(b) },
                    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(b) }]} /></td>}
                </tr>
              ))}
              {list && !list.length && <tr className="is-static"><td colSpan={6} className="cc-none">No buckets yet.{can.manage ? " Make one with New bucket." : ""}</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <BucketDialog api={api} bucket={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </>
  );
}

function BucketDialog({ api, bucket, onClose, onSaved }: { api: CrApi; bucket: DataBucket | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: bucket?.name ?? "", folder: bucket?.folder ?? "", description: bucket?.description ?? "", max_mb: String(bucket?.max_mb ?? 25) });
  const [error, setError] = useState<string | null>(null);
  const save = () => api.saveDataBucket(bucket?.id ?? null, { name: v.name.trim(), folder: v.folder.trim(), description: v.description, max_mb: Number(v.max_mb) })
    .then(() => onSaved((bucket ? "Saved " : "Made the bucket ") + v.name.trim() + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={bucket ? "Bucket " + bucket.name : "New storage bucket"} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim()} onClick={save}>{bucket ? "Save" : "Make the bucket"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={80} placeholder="Invoices" onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <Field label="Only for agents in folder" hint="Empty: every agent">{(id) => <TextInput id={id} value={v.folder} onChange={(e) => setV({ ...v, folder: e.target.value })} />}</Field>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} maxLength={500} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      <Field label="Largest file (MB)">{(id) => <TextInput id={id} type="number" min={1} max={200} value={v.max_mb} onChange={(e) => setV({ ...v, max_mb: e.target.value })} />}</Field>
    </Dialog>
  );
}

function BucketFiles({ api, bucket, can, onBack, onNotice }: { api: CrApi; bucket: DataBucket; can: Can; onBack: () => void; onNotice: (t: string) => void }) {
  const [files, setFiles] = useState<BucketFile[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [deleting, setDeleting] = useState<BucketFile | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const load = useCallback(async () => { try { setFiles(await api.bucketFiles(bucket.id)); setError(null); } catch (e) { setError(message(e)); } }, [api, bucket.id]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (files ?? []).filter((f) => !q.trim() || f.path.toLowerCase().includes(q.trim().toLowerCase())), [files, q]);
  const upload = async (list: FileList | null) => {
    if (!list) return;
    try { for (const f of Array.from(list)) await api.uploadBucketFile(bucket.id, f.name, f); onNotice("Uploaded " + list.length + " file" + (list.length === 1 ? "" : "s") + " to " + bucket.name + "."); load(); }
    catch (e) { setError(message(e)); }
  };
  const remove = (f: BucketFile) => api.deleteBucketFile(bucket.id, f.path).then(() => { onNotice("Deleted " + f.path + "."); setDeleting(null); load(); },
    (e) => { setDeleting(null); setError(message(e)); });
  return (
    <>
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <Button kind="plain" onClick={onBack}>← Buckets</Button>
          <strong>{bucket.name}</strong>
          <span className="cc-list-search"><TextInput type="search" aria-label="Search files" placeholder="Search files" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="File tools">
          {can.edit && <><input ref={picker} type="file" multiple hidden aria-label="Files to upload" onChange={(e) => { upload(e.target.files); e.target.value = ""; }} />
            <Button kind="primary" onClick={() => picker.current?.click()}>Upload</Button></>}
          <RefreshButton onClick={load} /></div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete {deleting.path} from {bucket.name}?</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label={bucket.name + " files"}>
            <thead><tr><th scope="col">File</th><th scope="col">Size</th><th scope="col">Changed</th><th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((f) => (
                <tr key={f.path} className="is-static">
                  <td className="cc-clip">{f.path}</td><td>{size(f.bytes)}</td><td>{when(f.modified)}</td>
                  <td className="cc-list-menu"><span className="cc-inline"><a className="ui-btn ui-btn-plain" href={api.bucketFileUrl(bucket.id, f.path)} download>Download</a>
                    {can.edit && <Button kind="plain" aria-label={"Delete " + f.path} onClick={() => setDeleting(f)}>Delete</Button>}</span></td>
                </tr>
              ))}
              {files && !shown.length && <tr className="is-static"><td colSpan={4} className="cc-none">{files.length ? "No files match." : "No files yet."}</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
