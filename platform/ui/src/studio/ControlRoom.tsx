import { useEffect, useState, type FormEvent } from "react";
import { ApiFailure, type CrStatus, type RepoAgent, type RepoVersion, type StudioApi } from "../api/client";
import { Button, Dialog, Field, TextInput } from "../components";

/** The server's message, with what to do when it says ("Check the address and that Command Center is running."). */
const message = (e: unknown) => e instanceof ApiFailure ? e.error.message + (e.error.suggestedFix ? ". " + e.error.suggestedFix : "") : String(e);
const shortName = (path: string) => path.replace(/\.flow\.json$/, "");

/**
 * Signing in to Command Center, for the public agent repository. The person types their own password; Studio sends it
 * once and keeps only the session. When Studio and Command Center run together, the browser's Command Center sign-in
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
      <p className="ui-field-hint">Agent Studio and Command Center run together here. Sign in to Command Center in this browser, and Studio uses that sign-in for check-in and check-out.</p>
      <div className="st-cr-acts">
        <a className="ui-btn ui-btn-primary" href={home} target="ooa-control-room">Open Command Center to sign in</a>
        <Button kind="secondary" onClick={async () => {
          try { const s = await api.crStatus(); if (s.connected) onSignedIn(s); else setError("Not signed in yet. Sign in in the Command Center tab, then press this again."); }
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
    <form className="st-cr-form" aria-label="Sign in to Command Center" onSubmit={connect}>
      <Field label="Command Center address">{(id) => <TextInput id={id} value={url} onChange={(e) => setUrl(e.target.value)} />}</Field>
      <Field label="User name">{(id) => <TextInput id={id} autoComplete="username" value={user} onChange={(e) => setUser(e.target.value)} />}</Field>
      <Field label="Password">{(id) => <TextInput id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
      <Field label="Two-step code" hint="Only if your account asks for one.">{(id) => <TextInput id={id} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />}</Field>
      {error && <p className="st-cr-error" role="alert">{error}</p>}
      <div className="st-cr-acts"><Button kind="primary" type="submit" disabled={busy || !url.trim() || !user.trim()}>{busy ? "Signing in…" : "Sign in"}</Button></div>
    </form>
  );
}

/**
 * The Public tab of the Automation page: the Command Center agent repository. Check out an agent to get a private copy
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
      <p className="st-muted">Your workspace is private. Sign in to Command Center to share agents through its agent repository, with check-out and check-in.</p>
      <SignIn api={api} status={status} home={home} onSignedIn={(s) => { onStatus(s); onNotice("Signed in to Command Center."); }} />
    </div>
  );
  const me = status.user?.username;
  const localOf = (path: string) => Object.keys(list?.mapping ?? {}).find((k) => list!.mapping[k] === path);
  return (
    <div className="st-cr">
      <div role="toolbar" aria-label="Agent repository" className="st-items-bar">
        <span className="st-items-count">{status.url} · signed in as {me}</span>
        <input className="ui-input st-cr-search" type="search" aria-label="Search the agent repository" placeholder="Search the agent repository" value={q} onChange={(e) => setQ(e.target.value)} />
        <Button kind="plain" onClick={reload}>Refresh</Button>
        {status.sameServer ? <span className="st-muted">Signed in through Command Center</span>
          : <Button kind="plain" onClick={async () => { try { onStatus(await api.crDisconnect()); setList(null); onNotice("Signed out of Command Center."); } catch (e) { onError(e); } }}>Sign out</Button>}
      </div>
      {failed && <p className="st-cr-error" role="alert">{failed}</p>}
      <table className="st-table">
        <thead><tr><th scope="col">Agent</th><th scope="col">Latest</th><th scope="col">Live</th><th scope="col">Checked out</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
        <tbody>
          {(list?.agents ?? []).map((a) => {
            const mine = !!me && a.locked_by === me, local = localOf(a.path);
            return (
              <tr key={a.path}>
                <td><span title={a.path}>{shortName(a.path)}</span>{local && <div className="st-item-path">Private copy: {local}</div>}</td>
                <td>v{a.latest_version}</td>
                <td>{a.production_version ? "v" + a.production_version : ""}</td>
                <td>{a.locked_by ? (mine ? "By you" : "By " + a.locked_by) : ""}</td>
                <td className="st-cr-row-acts">
                  {(!a.locked_by || mine) && <Button kind="secondary" onClick={async () => { try { await onCheckOut(a.path, local); reload(); } catch (e) { onError(e); } }}>
                    {mine ? "Open my copy" : "Check out"}</Button>}
                  {mine && <Button kind="plain" onClick={() => setUndoing(a)}>Undo check-out</Button>}
                  <Button kind="plain" onClick={async () => { try { setVersions({ agent: a, list: await api.crVersions(a.path) }); } catch (e) { onError(e); } }}>Versions</Button>
                </td>
              </tr>
            );
          })}
          {list && !list.agents.length && <tr><td colSpan={5} className="st-empty">{q ? "Nothing matches." : "The agent repository is empty. Open an agent and press Check in."}</td></tr>}
          {!list && !failed && <tr><td colSpan={5} className="st-empty">Reading the agent repository…</td></tr>}
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
        ? <><p className="st-muted">Sign in to Command Center first. The agent is saved, then checked in as a new version.</p>
            <SignIn api={api} status={status} home={home} onSignedIn={onStatus} /></>
        : <>
            <p className="st-muted">To {status.url}, as {status.user?.username}. Unsaved changes are saved first.</p>
            <Field label="What changed" hint="Shown in the version history.">{(id) =>
              <TextInput id={id} value={comment} placeholder="What changed in this version?" onChange={(e) => setComment(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && !busy) send(); }} />}</Field>
            <label className="st-check-line"><input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> Make this the live version (runs, schedules and triggers use it from now on)</label>
            <p className="ui-field-hint">Leave it off to add a new version only; the live version can be changed later in Command Center. An agent's first version is always live. With approval before production switched on, ticking it sends a request instead.</p>
          </>}
    </Dialog>
  );
}
