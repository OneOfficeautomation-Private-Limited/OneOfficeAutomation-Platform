import { useCallback, useEffect, useState } from "react";
import { ApiFailure, StudioApi, type Flow, type NewItemKind, type PackageManifest, type StudioInfo, type WorkspaceNode } from "../api/client";
import { Button, Dialog, Field, Icon, TextInput } from "../components";
import { Automation } from "./Automation";
import { CommandMenu, type StudioView } from "./CommandMenu";
import { ActionsPanel, AgentView } from "./Editor";

type OpenTab = { path: string; name: string; flow: Flow };
const MAX_TABS = 10;

/** The new Studio (docs/architecture/12-ui-redesign.md): Command Center menu, Automation page, agents in tabs. */
export function App({ api: given }: { api?: StudioApi }) {
  // one client for the life of the page: a new one on every render would reload the data in a loop
  const [api] = useState(() => given ?? new StudioApi());
  const [info, setInfo] = useState<StudioInfo | null>(null);
  const [tree, setTree] = useState<WorkspaceNode | null>(null);
  const [packages, setPackages] = useState<PackageManifest[]>([]);
  const [view, setView] = useState<StudioView>("automation");
  const [tabs, setTabs] = useState<OpenTab[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newFolderIn, setNewFolderIn] = useState<string | null>(null);

  const fail = (e: unknown) => setError(e instanceof ApiFailure ? e.error.message : String(e));
  const reloadTree = useCallback(() => api.tree().then(setTree).catch(fail), [api]);
  useEffect(() => {
    api.info().then(setInfo).catch(fail);
    api.packages().then(setPackages).catch(fail);
    reloadTree();
  }, [api, reloadTree]);

  const open = async (n: WorkspaceNode) => {
    if (n.type !== "bot") return;
    if (!tabs.some((t) => t.path === n.path)) {
      if (tabs.length >= MAX_TABS) { setError(`Up to ${MAX_TABS} items can be open. Close one first.`); return; }
      try { const flow = await api.bot(n.path); setTabs((t) => [...t, { path: n.path, name: flow.name || n.name, flow }]); }
      catch (e) { fail(e); return; }
    }
    setActive(n.path);
  };
  const close = (path: string) => {
    const rest = tabs.filter((t) => t.path !== path);
    setTabs(rest);
    if (active === path) setActive(rest.length ? rest[rest.length - 1].path : null);
  };
  const created = async (kind: NewItemKind, parent: string, name: string) => {
    const path = await api.create(kind, parent, name);
    await reloadTree();
    if (kind === "TaskBot" || kind === "ApiTask") await open({ name, path, type: "bot" });
  };

  const current = tabs.find((t) => t.path === active) ?? null;
  const classicHref = "../";
  const [search, setSearch] = useState("");

  return (
    <div className="st-app">
      <header className="st-top">
        <div className="st-brand"><span className="st-logo" />One Agent Studio</div>
        <nav aria-label="Breadcrumb" className="st-crumbs">
          {current ? current.path.split("/").slice(0, -1).map((p, i) => <span key={i}>{p}<span aria-hidden="true"> › </span></span>) : null}
          <span className="st-crumb-current">{current ? current.name : "Automation"}</span>
          <span className="st-preview-tag">New Studio preview</span>
        </nav>
        <label className="st-search"><Icon name="search" size={14} /><input aria-label="Search items" placeholder="Search items in the workspace" value={search}
          onChange={(e) => { setSearch(e.target.value); setActive(null); }} /></label>
        <div className="st-top-actions">
          {/* only what works today, never faded buttons: Save, Run and Debug join here in P2 and P3 */}
          {current
            ? <>
                <a className="ui-btn ui-btn-primary" href={classicHref}>Edit in Classic Studio</a>
                <Button kind="light" icon="close" onClick={() => close(current.path)}>Close</Button>
              </>
            : <a className="st-classic" href="../">Classic Studio</a>}
        </div>
      </header>

      {error && <div className="st-alert" role="alert"><span>{error}</span><Button kind="secondary" onClick={() => setError(null)}>Dismiss</Button></div>}

      <div className="st-body">
        <CommandMenu view={current ? null : view} controlRoom={info?.controlRoom ?? null} onView={(v) => { setView(v); setActive(null); }} />
        {current && <ActionsPanel packages={packages} />}
        <main className="st-main">
          {tabs.length > 0 && (
            <div className="st-tabs" role="tablist" aria-label="Open items">
              {tabs.map((t) => (
                <div key={t.path} className={`st-tab${t.path === active ? " is-active" : ""}`}>
                  <button type="button" role="tab" aria-selected={t.path === active} onClick={() => setActive(t.path)}>{t.name}</button>
                  <button type="button" className="st-tab-close" aria-label={"Close " + t.name} onClick={() => close(t.path)}><Icon name="close" size={12} stroke={2.6} /></button>
                </div>
              ))}
            </div>
          )}
          {current
            ? <AgentView key={current.path} flow={current.flow} packages={packages} classicHref={classicHref} />
            : tree
              ? <Automation view={view} tree={tree} search={search} onOpen={open} onCreated={created} onNewFolder={(p) => setNewFolderIn(p)} />
              : <p className="st-pad">Loading the workspace…</p>}
        </main>
      </div>

      <NewFolderDialog parent={newFolderIn} onClose={() => setNewFolderIn(null)}
        onCreate={async (name) => { await api.createFolder(newFolderIn ?? "", name); await reloadTree(); setNewFolderIn(null); }} />
    </div>
  );
}

function NewFolderDialog({ parent, onClose, onCreate }: { parent: string | null; onClose: () => void; onCreate: (name: string) => Promise<void> }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  if (parent === null) return null;
  const submit = () => {
    if (!name.trim()) { setError("Give the folder a name."); return; }
    onCreate(name.trim()).catch((e) => setError(e instanceof ApiFailure ? e.error.message : String(e)));
  };
  return (
    <Dialog open onClose={onClose} title="New folder" width={460}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={submit}>Create folder</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }} className="st-form">
        <Field label="Name" hint={"In " + (parent || "Workspace")}>{(id) => <TextInput id={id} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        {error && <p role="alert" className="st-error">{error}</p>}
      </form>
    </Dialog>
  );
}
