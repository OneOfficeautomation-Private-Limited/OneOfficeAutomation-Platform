import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { ApiFailure, type CrStatus, type RepoAgent, type RepoVersion, type StudioApi, errorText } from "../api/client";
import { Button, Dialog, Field, Icon, IconTile, Menu, SidePanel, Splitter, TextInput, Tree, usePanelSize, usePersistent, type TreeNode } from "../components";
import { Analysis, ColumnsDialog, labelText, listTime, PUBLIC_COLUMNS, PUBLIC_HIDDEN, PUBLIC_HIDDEN_KEY, sizeText, typeOf } from "./columns";

/** The server's message, with what to do when it says ("Check the address and that One CommandCenter is running."). */
const message = errorText;
const shortName = (path: string) => path.replace(/\.flow\.json$/, "");
/** An agent's name and its folder, as Automation shows them. */
const nameOf = (path: string) => shortName(path).split("/").pop()!;
const folderOf = (path: string) => path.split("/").slice(0, -1).join("/");

/**
 * Signing in to One CommandCenter, for the public agent repository. The person types their own password; Studio sends it
 * once and keeps only the session. When Studio and One CommandCenter run together, the browser's One CommandCenter sign-in
 * is used instead and there is nothing to type here.
 */
export function SignIn({ api, status, home, onSignedIn }: { api: StudioApi; status: CrStatus; home: string; onSignedIn: (s: CrStatus) => void }) {
  const [url, setUrl] = useState(status.url || "http://localhost:8720");
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  // One CommandCenter's sign-in notice must be accepted (Settings): asked here once the server says so
  const [mustAccept, setMustAccept] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (status.sameServer) return (
    <div className="st-cr-form">
      <p className="ui-field-hint">Your Private workspace and One CommandCenter run together here. Sign in to One CommandCenter in this browser, and the workspace uses that sign-in for check-in and check-out.</p>
      <div className="st-cr-acts">
        <a className="ui-btn ui-btn-primary" href={home} target="ooa-control-room">Open One CommandCenter to sign in</a>
        <Button kind="secondary" onClick={async () => {
          try { const s = await api.crStatus(); if (s.connected) onSignedIn(s); else setError("Not signed in yet. Sign in in One CommandCenter tab, then press this again."); }
          catch (e) { setError(message(e)); }
        }}>I have signed in</Button>
      </div>
      {error && <p className="st-cr-error" role="alert">{error}</p>}
    </div>
  );
  const connect = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { const s = await api.crConnect(url.trim(), user.trim(), password, code.trim(), accepted); setPassword(""); setCode(""); onSignedIn(s); }
    catch (err) {
      if (err instanceof ApiFailure && err.error.code === "CR.NOTICE_NOT_ACCEPTED") { setMustAccept(true); setError("One CommandCenter asks you to accept its sign-in notice first."); }
      else setError(message(err));
    }
    finally { setBusy(false); }
  };
  return (
    <form className="st-cr-form" aria-label="Sign in to One CommandCenter" onSubmit={connect}>
      <Field label="One CommandCenter address">{(id) => <TextInput id={id} value={url} onChange={(e) => setUrl(e.target.value)} />}</Field>
      <Field label="User name">{(id) => <TextInput id={id} autoComplete="username" value={user} onChange={(e) => setUser(e.target.value)} />}</Field>
      <Field label="Password">{(id) => <TextInput id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
      {mustAccept && <label className="st-check-line"><input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} /> I have read and accept
        the sign-in notice of One CommandCenter (<a href={url.replace(/\/+$/, "") + "/"} target="ooa-control-room">read it</a>)</label>}
      <Field label="Two-step code" hint="Only if your account asks for one.">{(id) => <TextInput id={id} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />}</Field>
      {error && <p className="st-cr-error" role="alert">{error}</p>}
      <div className="st-cr-acts"><Button kind="primary" type="submit" disabled={busy || !url.trim() || !user.trim() || (mustAccept && !accepted)}>{busy ? "Signing in…" : "Sign in"}</Button></div>
    </form>
  );
}

/** The repository's folders as a tree, from the agents' paths (the root is "Automation", as on the Private tab). */
function folderTree(paths: string[]): TreeNode {
  const root: TreeNode = { id: "", label: "Automation", children: [] };
  for (const p of paths) {
    let at = root;
    const parts = folderOf(p).split("/").filter(Boolean);
    parts.forEach((name, i) => {
      const id = parts.slice(0, i + 1).join("/");
      let next = at.children!.find((c) => c.id === id);
      if (!next) { next = { id, label: name, children: [] }; at.children!.push(next); at.children!.sort((a, b) => a.label.localeCompare(b.label)); }
      at = next;
    });
  }
  return root;
}
const FOLDER_ICON = (sel: boolean) => <span style={{ color: sel ? "#ffffff" : "#a16207", display: "inline-flex" }}>
  <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true" fill={sel ? "#ffffff" : "#f5c451"} stroke="currentColor" strokeWidth="1.8"><path d="M3 7h6l2 2h10v10H3z" /></svg></span>;

/**
 * The Public tab of the Automation page: One CommandCenter agent repository, laid out as the Private tab (since 1 Nov
 * 2026, as AA's Public workspace mirrors Private): the folders on the left, the chosen folder's sub-folders and agents on
 * the right. Check out an agent to get a private copy at the same folder path in the workspace (others cannot check it
 * in meanwhile); check it back in from the agent's Check in button. A search looks through every folder.
 */
/** An action on a repository agent asked for from outside the Public tab (the console's agent page): check out, clone or copy. */
export type RepoRequest = { act: "checkout" | "clone" | "copy"; path: string };

/**
 * Copy to Private (AA's Copy): the agent's latest version becomes a new agent of your own, in your Private workspace, at
 * the name and folder chosen; nothing changes in the repository. Names stay unique across Public and Private.
 */
export function CopyToPrivate({ api, path, onClose, onCopied }: { api: StudioApi; path: string; onClose: () => void; onCopied: (local: string, note: string) => void }) {
  const suffix = path.slice(path.search(/\.(flow|process|form)\.json$/));
  const [to, setTo] = useState(path.slice(0, path.length - suffix.length) + " (copy)");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const copy = async () => {
    setBusy(true); setError(null);
    try {
      const r = await api.crCopy(path, to.trim());
      const also = r.cloned?.length ? ` Also copied ${r.cloned.length === 1 ? "the agent" : r.cloned.length + " agents"} it runs: ${r.cloned.join(", ")}.` : "";
      onCopied(r.local, `Copied v${r.version} of ${nameOf(path)} to your Private workspace as ${shortName(r.local)}.` + also);
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title={"Copy " + nameOf(path) + " to Private"} width={520}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={busy || !to.trim()} onClick={copy}>{busy ? "Copying…" : "Copy"}</Button></>}>
      <p className="st-muted">The latest version becomes a new agent of your own in your Private workspace. Change it there, then check it in as a new agent. The original stays as it is.</p>
      <Field label="Name, with its folder" hint="Folders are separated by /. The name may not end with a dot or a space, and may not be one the repository already has in that folder.">{(id) =>
        <TextInput id={id} value={to} onChange={(e) => setTo(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && !busy && to.trim()) copy(); }} />}</Field>
      {error && <p className="st-cr-error" role="alert">{error}</p>}
    </Dialog>
  );
}

export function PublicRepository({ api, status, home, onStatus, onCheckOut, onOpenLocal, request, onRequestDone, onNotice, onError }:
  { api: StudioApi; status: CrStatus; home: string; onStatus: (s: CrStatus) => void; onCheckOut: (repoPath: string, local?: string, mine?: boolean, version?: number) => Promise<void>;
    onOpenLocal: (local: string, note: string) => Promise<void>; request?: RepoRequest | null; onRequestDone?: () => void;
    onNotice: (text: string) => void; onError: (e: unknown) => void }) {
  const [q, setQ] = useState("");
  const [list, setList] = useState<{ agents: RepoAgent[]; mapping: Record<string, string> } | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [versions, setVersions] = useState<{ agent: RepoAgent; list: RepoVersion[] } | null>(null);
  const [picking, setPicking] = useState<{ agent: RepoAgent; list: RepoVersion[]; version: number } | null>(null);   // check out a chosen version
  const [undoing, setUndoing] = useState<RepoAgent | null>(null);
  const [copying, setCopying] = useState<string | null>(null);   // Copy to Private: the repository path
  const [tick, setTick] = useState(0);
  const reload = () => setTick((t) => t + 1);
  const [folder, setFolder] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(new Set([""]));
  const [foldersHidden, setFoldersHidden] = usePersistent("panel.public.folders.hidden", false);
  const [foldersWidth, setFoldersWidth] = usePanelSize("public.folders");
  const foldersPanel = useRef<HTMLElement | null>(null);
  const [hiddenCols, setHiddenCols] = usePersistent<string[]>(PUBLIC_HIDDEN_KEY, PUBLIC_HIDDEN);
  const [choosingCols, setChoosingCols] = useState(false);
  const tree = useMemo(() => folderTree((list?.agents ?? []).map((a) => a.path)), [list]);
  useEffect(() => {
    if (!status.connected) return;
    const t = setTimeout(() => { api.crRepo(q.trim()).then((r) => { setList(r); setFailed(null); }, (e) => setFailed(message(e))); }, q ? 250 : 0);
    return () => clearTimeout(t);
  }, [api, status.connected, q, tick]);
  const clone = async (path: string) => {
    try { const r = await api.crClone(path); reload(); await onOpenLocal(r.local, `Cloned v${r.version} of ${nameOf(path)} into your Private workspace, read-only. Check it out to change it.`); }
    catch (e) { onError(e); }
  };
  // an action asked for from the console's agent page (../studio/#public/checkout/<path>), once the repository is read
  useEffect(() => {
    if (!request || !list) return;
    const a = list.agents.find((x) => x.path === request.path);
    onRequestDone?.();
    if (!a) { onError(new Error("The agent " + request.path + " is not in the repository, or you may not see it.")); return; }
    if (request.act === "copy") setCopying(a.path);
    else if (request.act === "clone") clone(a.path);
    else if (a.locked_by && a.locked_by !== status.user?.username) {
      onNotice(nameOf(a.path) + " is checked out by " + a.locked_by + ". You can clone it (read-only) or copy it to your Private workspace instead.");
      setFolder(folderOf(a.path));
    } else onCheckOut(a.path, localOf(a.path), a.locked_by === status.user?.username).then(reload, onError);
  }, [request, list]);   // eslint-disable-line react-hooks/exhaustive-deps
  if (!status.connected) return (
    <div className="st-cr">
      <p className="st-muted">Your workspace is private. Sign in to One CommandCenter to share agents through its agent repository, with check-out and check-in.</p>
      <SignIn api={api} status={status} home={home} onSignedIn={(s) => { onStatus(s); onNotice("Signed in to One CommandCenter."); }} />
    </div>
  );
  const me = status.user?.username;
  const localOf = (path: string) => Object.keys(list?.mapping ?? {}).find((k) => list!.mapping[k] === path);
  const find = (n: TreeNode, id: string): TreeNode | null => n.id === id ? n : n.children?.reduce<TreeNode | null>((f, c) => f ?? find(c, id), null) ?? null;
  const here = find(tree, folder) ?? tree;
  // with a search, every match in the repository; otherwise the chosen folder's agents (its sub-folders are in the tree
  // only, improvement list 13 Nov 2026, item 2)
  const subfolders: TreeNode[] = [];
  const agents = (list?.agents ?? []).filter((a) => q || folderOf(a.path) === here.id);
  const openFolder = (id: string) => { setFolder(id); setExpanded(new Set([...expanded, ...id.split("/").map((_, i, all) => all.slice(0, i).join("/"))])); };
  const shownCols = PUBLIC_COLUMNS.filter((c) => !hiddenCols.includes(c.key));
  const cols = shownCols.length + (q ? 3 : 2);
  const cell = (key: string, a: RepoAgent, mine: boolean): ReactNode => {
    switch (key) {
      case "type": return typeOf(a.path);
      case "platform": return a.platform ?? "";
      case "latest": return "v" + a.latest_version;
      case "live": return a.production_version ? "v" + a.production_version : "";
      case "label": return labelText(a.labels);
      case "analysis": return <Analysis found={a.analysis} />;
      case "size": return sizeText(a.size);
      case "out": return a.locked_by ? (mine ? "By you" : "By " + a.locked_by) : "";
      case "changed": return listTime(a.updated);
      case "by": return a.updated_by ?? "";
      default: return "";
    }
  };
  return (
    <div className="st-cr">
      <div role="toolbar" aria-label="Agent Repository" className="st-items-bar">
        <span className="st-items-count" aria-live="polite">{list ? agents.length + (agents.length === 1 ? " agent" : " agents") : ""}</span>
        {!status.sameServer && <span className="st-items-count" title={status.url ?? undefined}>Signed in as {me}</span>}
        <input className="ui-input st-cr-search" type="search" aria-label="Search the agent repository" placeholder="Search the agent repository" value={q} onChange={(e) => setQ(e.target.value)} />
        <Button kind="plain" onClick={() => setChoosingCols(true)}>Columns</Button>
        <button type="button" className="st-tool" aria-label="Refresh" title="Refresh" onClick={reload}><Icon name="loop" size={15} /></button>
        {status.sameServer ? <span className="st-muted">Signed in through One CommandCenter</span>
          : <Button kind="plain" onClick={async () => { try { onStatus(await api.crDisconnect()); setList(null); onNotice("Signed out of One CommandCenter."); } catch (e) { onError(e); } }}>Sign out</Button>}
      </div>
      {failed && <p className="st-cr-error" role="alert">{failed}</p>}
      <div className="st-page-body">
        {!q && <><SidePanel title="Folders" side="left" collapsed={foldersHidden} onToggle={() => setFoldersHidden(!foldersHidden)} className="st-folders"
          style={foldersWidth ? { flex: `0 0 ${foldersWidth}px` } : undefined} panelRef={(el) => { foldersPanel.current = el; }}>
          <div className="st-folders-tree"><Tree label="Folders" nodes={[tree]} selected={here.id} expanded={expanded} onSelect={openFolder}
            onToggle={(id) => { const n = new Set(expanded); if (n.has(id)) n.delete(id); else n.add(id); setExpanded(n); }} renderIcon={(_, sel) => FOLDER_ICON(sel)} /></div>
        </SidePanel>
        {!foldersHidden && <Splitter label="Resize the folders panel" axis="x" grow={1} value={foldersWidth} min={180} max={520}
          measure={() => foldersPanel.current?.getBoundingClientRect().width ?? 260} onChange={setFoldersWidth} />}</>}
        <div className="st-items">
          <table className="st-table">
            <thead><tr><th scope="col">Name</th>{q && <th scope="col">Folder</th>}{shownCols.map((c) => <th key={c.key} scope="col" className={c.key === "changed" ? "st-col-changed" : undefined}>{c.label}</th>)}<th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {subfolders.map((f) => <tr key={"f:" + f.id}><td colSpan={cols}>
                <button type="button" className="st-link st-name" onClick={() => openFolder(f.id)}>{FOLDER_ICON(false)}<span>{f.label}</span></button></td></tr>)}
              {agents.map((a) => {
                const mine = !!me && a.locked_by === me, local = localOf(a.path);
                return (
                  <tr key={a.path}>
                    <td><span className="st-name"><IconTile name="bolt" color="#2f6fde" size={18} /><span title={a.path}>{nameOf(a.path)}</span>
                      {local && <span className="st-pill" title={"Private copy: " + local}>private copy</span>}</span></td>
                    {q && <td>{folderOf(a.path)}</td>}
                    {shownCols.map((c) => <td key={c.key} className={c.key === "changed" ? "st-col-changed" : undefined}>{cell(c.key, a, mine)}</td>)}
                    <td className="st-cr-row-acts">
                      {(!a.locked_by || mine) && <Button kind="secondary" onClick={async () => { try { await onCheckOut(a.path, local, mine); reload(); } catch (e) { onError(e); } }}>
                        {mine ? "Open my copy" : "Check out"}</Button>}
                      <Menu label={nameOf(a.path) + " menu"} align="end" items={[
                        { key: "versions", label: "Versions", onSelect: async () => { try { setVersions({ agent: a, list: await api.crVersions(a.path) }); } catch (e) { onError(e); } } },
                        ...(!local ? [{ key: "clone", label: "Clone (read-only)", description: "A copy to look at; it follows the repository", onSelect: () => clone(a.path) }] : []),
                        { key: "copy", label: "Copy to Private…", description: "A new agent of your own, from the latest version", onSelect: () => setCopying(a.path) },
                    ...(!a.locked_by || mine ? [{ key: "pick", label: "Check out a version…", description: "An earlier version, to change it or to bring it back",
                      onSelect: async () => { try { setPicking({ agent: a, list: await api.crVersions(a.path), version: a.latest_version }); } catch (e) { onError(e); } } }] : []),
                        ...(mine ? [{ key: "undo", label: "Undo check-out…", separatorBefore: true, onSelect: () => setUndoing(a) }] : []),
                      ]} trigger={<button type="button" className="st-tool" aria-label={"Actions for " + nameOf(a.path)} title="Actions"><Icon name="more" size={16} stroke={3} /></button>} />
                    </td>
                  </tr>
                );
              })}
              {list && !agents.length && !subfolders.length && <tr><td colSpan={cols} className="st-empty">{q ? "Nothing matches." : list.agents.length ? "No agents in this folder."
                : "The agent repository is empty. Open an agent and press Check in."}</td></tr>}
              {!list && !failed && <tr><td colSpan={cols} className="st-empty">Reading the agent repository…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {versions && (
        <Dialog open onClose={() => setVersions(null)} title={"Versions of " + shortName(versions.agent.path)} width={620}
          footer={<Button kind="secondary" onClick={() => setVersions(null)}>Close</Button>}>
          <table className="st-table">
            <thead><tr><th scope="col">Version</th><th scope="col">By</th><th scope="col">When</th><th scope="col">What changed</th></tr></thead>
            <tbody>{versions.list.map((v) => (
              <tr key={v.version}><td>v{v.version}{v.version === versions.agent.production_version && <strong> · live</strong>}</td><td>{v.author}</td>
                <td>{v.created ? new Date(v.created).toLocaleString() : ""}</td><td>{v.comment}</td></tr>))}</tbody>
          </table>
        </Dialog>
      )}
      {choosingCols && <ColumnsDialog columns={PUBLIC_COLUMNS} hidden={hiddenCols} onChange={setHiddenCols} onClose={() => setChoosingCols(false)} />}
      {copying && <CopyToPrivate api={api} path={copying} onClose={() => setCopying(null)}
        onCopied={(local, note) => { setCopying(null); reload(); onOpenLocal(local, note).catch(onError); }} />}
      {picking && (
        <Dialog open onClose={() => setPicking(null)} title={"Check out a version of " + shortName(picking.agent.path)} width={620}
          footer={<><Button kind="secondary" onClick={() => setPicking(null)}>Cancel</Button>
            <Button kind="primary" onClick={async () => { const p = picking; setPicking(null);
              try { await onCheckOut(p.agent.path, localOf(p.agent.path), false, p.version); reload(); } catch (e) { onError(e); } }}>Check out v{picking.version}</Button></>}>
          <p className="st-muted">Your private copy becomes this version. Check it in to make it the newest version again, for example to bring back an earlier one.</p>
          <table className="st-table" aria-label="Versions">
            <thead><tr><th scope="col"><span className="st-sr">Choose</span></th><th scope="col">Version</th><th scope="col">By</th><th scope="col">When</th><th scope="col">What changed</th></tr></thead>
            <tbody>{picking.list.map((v) => (
              <tr key={v.version} onClick={() => setPicking({ ...picking, version: v.version })}>
                <td><input type="radio" name="st-pick-version" aria-label={"Version " + v.version} checked={picking.version === v.version}
                  onChange={() => setPicking({ ...picking, version: v.version })} /></td>
                <td>v{v.version}{v.version === picking.agent.production_version && <strong> · live</strong>}</td><td>{v.author}</td>
                <td>{v.created ? new Date(v.created).toLocaleString() : ""}</td><td>{v.comment}</td></tr>))}</tbody>
          </table>
        </Dialog>
      )}
      {undoing && (
        <Dialog open onClose={() => setUndoing(null)} title={"Undo check-out of " + shortName(undoing.path) + "?"} width={480}
          footer={<><Button kind="secondary" onClick={() => setUndoing(null)}>Cancel</Button>
            <Button kind="primary" onClick={async () => {
              try { await api.crUndoCheckout(undoing.path); onNotice("Check-out undone. Your private copy stays in the workspace."); reload(); } catch (e) { onError(e); }
              setUndoing(null);
            }}>Undo check-out</Button></>}>
          <p>Others can check the agent out again. Your private copy stays in the workspace; changes in it are not checked in.</p>
        </Dialog>
      )}
    </div>
  );
}

/**
 * An agent the checked-in agent runs, for the dependency review (as AA's Review dependencies): a new one goes in with
 * it (it cannot run without it), one checked out to the person may go in too, and one already in the repository is shown.
 */
export type CheckInDep = { path: string; name: string; status: string };
const DEP_STATUS: Record<string, string> = { new: "New: checked in with it", checked_out: "Checked out to you", checked_out_edited: "Checked out to you, edited",
  checked_in: "Already in the repository", linked: "Already in the repository", edited: "Edited, not checked out: check it out first" };

/** Check in: the agent (or several items) becomes a new version in the repository; signs in first when needed. */
export function CheckInDialog({ api, name, status, home, deps = [], onStatus, onClose, onCheckIn }:
  { api: StudioApi; name: string; status: CrStatus; home: string; deps?: CheckInDep[]; onStatus: (s: CrStatus) => void; onClose: () => void;
    onCheckIn: (comment: string, live: boolean, withDeps: string[]) => Promise<void> }) {
  const [comment, setComment] = useState("");
  const [live, setLive] = useState(false);
  const [busy, setBusy] = useState(false);
  const optional = (d: CheckInDep) => d.status === "checked_out" || d.status === "checked_out_edited";
  const [extra, setExtra] = useState<string[]>([]);
  // the review's list arrives after the dialog opens: new and checked-out agents are ticked, without losing what was typed
  useEffect(() => { setExtra(deps.filter((d) => d.status === "new" || optional(d)).map((d) => d.path)); }, [deps]);   // eslint-disable-line react-hooks/exhaustive-deps
  const send = async () => { setBusy(true); try { await onCheckIn(comment.trim(), live, extra); } finally { setBusy(false); } };
  return (
    <Dialog open onClose={onClose} title={"Check in " + name} width={520}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button>
        {status.connected && <Button kind="primary" onClick={send} disabled={busy}>{busy ? "Checking in…" : "Check in"}</Button>}</>}>
      {!status.connected
        ? <><p className="st-muted">Sign in to One CommandCenter first. The agent is saved, then checked in as a new version.</p>
            <SignIn api={api} status={status} home={home} onSignedIn={onStatus} /></>
        : <>
            <p className="st-muted">To {status.url}, as {status.user?.username}. Unsaved changes are saved first.</p>
            <Field label="What changed" hint="Shown in the version history.">{(id) =>
              <TextInput id={id} value={comment} placeholder="What changed in this version?" onChange={(e) => setComment(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && !busy) send(); }} />}</Field>
            <label className="st-check-line"><input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> Make this the live version (runs, schedules and triggers use it from now on)</label>
            <p className="ui-field-hint">Leave it off to add a new version only; the live version can be changed later in One CommandCenter. An agent's first version is always live. With approval before production switched on, ticking it sends a request instead.</p>
            {deps.length > 0 && <fieldset className="st-deps">
              <legend>Agents it runs</legend>
              {deps.map((d) => <label key={d.path} className="st-check-line" title={d.path}>
                <input type="checkbox" checked={extra.includes(d.path)} disabled={!optional(d)}
                  onChange={(e) => setExtra(e.target.checked ? [...extra, d.path] : extra.filter((x) => x !== d.path))} />
                {d.name} <span className="st-muted">· {DEP_STATUS[d.status] ?? d.status}</span></label>)}
              <p className="ui-field-hint">Ticked agents are checked in first, with the same comment, so the agent finds them in the repository.</p>
            </fieldset>}
          </>}
    </Dialog>
  );
}
