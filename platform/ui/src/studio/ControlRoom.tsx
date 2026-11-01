import { useEffect, useState, type FormEvent } from "react";
import { ApiFailure, type CrStatus, type RepoAgent, type RepoVersion, type StudioApi } from "../api/client";
import { Button, Dialog, Field, Icon, IconTile, Menu, TextInput } from "../components";

/** The server's message, with what to do when it says ("Check the address and that One CommandCenter is running."). */
const message = (e: unknown) => e instanceof ApiFailure ? e.error.message + (e.error.suggestedFix ? ". " + e.error.suggestedFix : "") : String(e);
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
  const [error, setError] = useState<string | null>(null);
  if (status.sameServer) return (
    <div className="st-cr-form">
      <p className="ui-field-hint">One AgentStudio and One CommandCenter run together here. Sign in to One CommandCenter in this browser, and Studio uses that sign-in for check-in and check-out.</p>
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
    try { const s = await api.crConnect(url.trim(), user.trim(), password, code.trim()); setPassword(""); setCode(""); onSignedIn(s); }
    catch (err) { setError(message(err)); }
    finally { setBusy(false); }
  };
  return (
    <form className="st-cr-form" aria-label="Sign in to One CommandCenter" onSubmit={connect}>
      <Field label="One CommandCenter address">{(id) => <TextInput id={id} value={url} onChange={(e) => setUrl(e.target.value)} />}</Field>
      <Field label="User name">{(id) => <TextInput id={id} autoComplete="username" value={user} onChange={(e) => setUser(e.target.value)} />}</Field>
      <Field label="Password">{(id) => <TextInput id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
      <Field label="Two-step code" hint="Only if your account asks for one.">{(id) => <TextInput id={id} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />}</Field>
      {error && <p className="st-cr-error" role="alert">{error}</p>}
      <div className="st-cr-acts"><Button kind="primary" type="submit" disabled={busy || !url.trim() || !user.trim()}>{busy ? "Signing in…" : "Sign in"}</Button></div>
    </form>
  );
}

/**
 * The Public tab of the Automation page: One CommandCenter agent repository. Check out an agent to get a private copy
 * in the workspace (others cannot check it in meanwhile); check it back in from the agent's Check in button.
 */
export function PublicRepository({ api, status, home, onStatus, onCheckOut, onNotice, onError }:
  { api: StudioApi; status: CrStatus; home: string; onStatus: (s: CrStatus) => void; onCheckOut: (repoPath: string, local?: string) => Promise<void>;
    onNotice: (text: string) => void; onError: (e: unknown) => void }) {
  const [q, setQ] = useState("");
  const [list, setList] = useState<{ agents: RepoAgent[]; mapping: Record<string, string> } | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [versions, setVersions] = useState<{ agent: RepoAgent; list: RepoVersion[] } | null>(null);
  const [undoing, setUndoing] = useState<RepoAgent | null>(null);
  const [tick, setTick] = useState(0);
  const reload = () => setTick((t) => t + 1);
  useEffect(() => {
    if (!status.connected) return;
    const t = setTimeout(() => { api.crRepo(q.trim()).then((r) => { setList(r); setFailed(null); }, (e) => setFailed(message(e))); }, q ? 250 : 0);
    return () => clearTimeout(t);
  }, [api, status.connected, q, tick]);
  if (!status.connected) return (
    <div className="st-cr">
      <p className="st-muted">Your workspace is private. Sign in to One CommandCenter to share agents through its agent repository, with check-out and check-in.</p>
      <SignIn api={api} status={status} home={home} onSignedIn={(s) => { onStatus(s); onNotice("Signed in to One CommandCenter."); }} />
    </div>
  );
  const me = status.user?.username;
  const localOf = (path: string) => Object.keys(list?.mapping ?? {}).find((k) => list!.mapping[k] === path);
  return (
    <div className="st-cr">
      <div role="toolbar" aria-label="Agent Repository" className="st-items-bar">
        <span className="st-items-count">{status.url} · signed in as {me}</span>
        <input className="ui-input st-cr-search" type="search" aria-label="Search the agent repository" placeholder="Search the agent repository" value={q} onChange={(e) => setQ(e.target.value)} />
        <button type="button" className="st-tool" aria-label="Refresh" title="Refresh" onClick={reload}><Icon name="loop" size={15} /></button>
        {status.sameServer ? <span className="st-muted">Signed in through One CommandCenter</span>
          : <Button kind="plain" onClick={async () => { try { onStatus(await api.crDisconnect()); setList(null); onNotice("Signed out of One CommandCenter."); } catch (e) { onError(e); } }}>Sign out</Button>}
      </div>
      {failed && <p className="st-cr-error" role="alert">{failed}</p>}
      <table className="st-table">
        <thead><tr><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col">Latest</th><th scope="col">Live</th><th scope="col">Checked out</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
        <tbody>
          {(list?.agents ?? []).map((a) => {
            const mine = !!me && a.locked_by === me, local = localOf(a.path);
            return (
              <tr key={a.path}>
                <td><span className="st-name"><IconTile name="bolt" color="#2f6fde" size={18} /><span title={a.path}>{nameOf(a.path)}</span>
                  {local && <span className="st-pill" title={"Private copy: " + local}>private copy</span>}</span></td>
                <td>{folderOf(a.path)}</td>
                <td>v{a.latest_version}</td>
                <td>{a.production_version ? "v" + a.production_version : ""}</td>
                <td>{a.locked_by ? (mine ? "By you" : "By " + a.locked_by) : ""}</td>
                <td className="st-cr-row-acts">
                  {(!a.locked_by || mine) && <Button kind="secondary" onClick={async () => { try { await onCheckOut(a.path, local); reload(); } catch (e) { onError(e); } }}>
                    {mine ? "Open my copy" : "Check out"}</Button>}
                  <Menu label={nameOf(a.path) + " menu"} align="end" items={[
                    { key: "versions", label: "Versions", onSelect: async () => { try { setVersions({ agent: a, list: await api.crVersions(a.path) }); } catch (e) { onError(e); } } },
                    ...(mine ? [{ key: "undo", label: "Undo check-out…", separatorBefore: true, onSelect: () => setUndoing(a) }] : []),
                  ]} trigger={<button type="button" className="st-tool" aria-label={"Actions for " + nameOf(a.path)} title="Actions"><Icon name="more" size={16} stroke={3} /></button>} />
                </td>
              </tr>
            );
          })}
          {list && !list.agents.length && <tr><td colSpan={6} className="st-empty">{q ? "Nothing matches." : "The agent repository is empty. Open an agent and press Check in."}</td></tr>}
          {!list && !failed && <tr><td colSpan={6} className="st-empty">Reading the agent repository…</td></tr>}
        </tbody>
      </table>
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

/** Check in: the open agent becomes a new version in the repository; signs in first when needed. */
export function CheckInDialog({ api, name, status, home, onStatus, onClose, onCheckIn }:
  { api: StudioApi; name: string; status: CrStatus; home: string; onStatus: (s: CrStatus) => void; onClose: () => void;
    onCheckIn: (comment: string, live: boolean) => Promise<void> }) {
  const [comment, setComment] = useState("");
  const [live, setLive] = useState(false);
  const [busy, setBusy] = useState(false);
  const send = async () => { setBusy(true); try { await onCheckIn(comment.trim(), live); } finally { setBusy(false); } };
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
          </>}
    </Dialog>
  );
}
