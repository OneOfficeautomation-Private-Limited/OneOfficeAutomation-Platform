import { useCallback, useEffect, useState } from "react";
import type { CrApi, CrUser, KnowledgeBase } from "../api/cr";
import { Button, Dialog, Field, HelpButton, TextArea, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, when } from "./ListParts";

/**
 * AI > Knowledge bases (gap G71): knowledge indexes kept in One CommandCenter. An indexing agent fills and refreshes a
 * base (Knowledge Q&A > Build index with the base's name), usually on a schedule; agents search and ask it by name.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const size = (b: number) => b < 1048576 ? Math.max(1, Math.round(b / 1024)) + " KB" : (b / 1048576).toFixed(1) + " MB";

const HELP: HelpTopic = { title: "Knowledge bases", about: "Documents indexed once and asked by many agents: policies, manuals, product sheets, web pages.",
  items: [
    { name: "Filling it", text: "An agent's Knowledge Q&A > Build index step with Knowledge base set reads files, folders, web pages and texts and sends the index here. "
      + "Schedule that agent (Schedules) to refresh the base, such as every night." },
    { name: "Asking it", text: "Knowledge Q&A > Search or Ask with Knowledge base set. A runner fetches the base once and again only when it changed." },
    { name: "Folder", text: "A base with a folder is only for agents in that folder or below it; empty: every agent." },
    { name: "Embeddings", text: "When Build index uses an embedding connection, the base can be searched by meaning, with the same connection." },
  ] };

export function KnowledgeBases({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<KnowledgeBase[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<KnowledgeBase | "new" | null>(null);
  const [deleting, setDeleting] = useState<KnowledgeBase | null>(null);
  const manage = user.permissions.includes("knowledge.manage");
  const load = useCallback(async () => { try { setList(await api.knowledgeBases()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const remove = (k: KnowledgeBase) => api.deleteKnowledgeBase(k.id).then(() => { onNotice("Deleted the knowledge base " + k.name + "."); setDeleting(null); load(); },
    (e) => { setDeleting(null); setError(message(e)); });
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-kb-title">
      <header className="cc-list-head">
        <h1 id="cc-kb-title">Knowledge bases</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length}</span>}
        <span className="ov-fill" />
        {manage && <Button kind="primary" onClick={() => setEditing("new")}>New knowledge base</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools"><div className="cc-list-filters" /><div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div></div>
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the knowledge base {deleting.name}? Agents that ask it will fail until it is made and filled again.</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Knowledge bases list">
            <thead><tr>{["Knowledge base", "Folder", "Documents", "Passages", "Size", "Refreshed"].map((h) => <th key={h} scope="col">{h}</th>)}
              {manage && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {(list ?? []).map((k) => (
                <tr key={k.id} className="is-static">
                  <td title={k.description || undefined}>{k.name}</td>
                  <td>{k.folder || "Every folder"}</td>
                  <td>{k.documents}</td>
                  <td>{k.passages}</td>
                  <td>{k.bytes ? size(k.bytes) : ""}</td>
                  <td title={k.updated_by_agent ? "By " + k.updated_by_agent + " (run " + k.updated_by_run + ")" : undefined}>{k.updated ? when(k.updated) : "Not filled yet"}</td>
                  {manage && <td className="cc-list-menu"><RowMenu label={"Actions for " + k.name} items={[
                    { key: "edit", label: "Edit", onSelect: () => setEditing(k) },
                    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(k) }]} /></td>}
                </tr>
              ))}
              {list && !list.length && <tr className="is-static"><td colSpan={7} className="cc-none">No knowledge bases yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <KbDialog api={api} kb={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </section>
  );
}

function KbDialog({ api, kb, onClose, onSaved }: { api: CrApi; kb: KnowledgeBase | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: kb?.name ?? "", folder: kb?.folder ?? "", description: kb?.description ?? "" });
  const [error, setError] = useState<string | null>(null);
  const save = () => api.saveKnowledgeBase(kb?.id ?? null, { name: v.name.trim(), folder: v.folder.trim(), description: v.description })
    .then(() => onSaved((kb ? "Saved " : "Made the knowledge base ") + v.name.trim() + "; fill it with an agent's Build index step."), (e) => setError(message(e)));
  return (
    <Dialog open title={kb ? "Knowledge base " + kb.name : "New knowledge base"} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim()} onClick={save}>{kb ? "Save" : "Make it"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Name" hint="Agents use this name">{(id) => <TextInput id={id} value={v.name} maxLength={80} placeholder="HR policies" onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <Field label="Only for agents in folder" hint="Empty: every agent">{(id) => <TextInput id={id} value={v.folder} onChange={(e) => setV({ ...v, folder: e.target.value })} />}</Field>
      <Field label="What it holds">{(id) => <TextArea id={id} rows={3} maxLength={1000} value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
    </Dialog>
  );
}
