import { useEffect, useState, type FormEvent } from "react";
import { CrApi, CrFailure, type CrUser, type Environment } from "../api/cr";
import { Button, Field, HelpPanel, HelpProvider, Icon, inTextField, is, ShortcutsDialog, TextInput, usePersistent } from "../components";
import { CommandMenu } from "../shell/CommandMenu";
import { MENU_SECTIONS } from "../shell/menu";
import { enableTableTools } from "./TableTools";
import { Activity } from "./Activity";
import { AgentApis } from "../modules/integration/AgentApis";
import { Audit } from "./Audit";
import { Connections } from "./Connections";
import { Devices } from "./Devices";
import { DocumentAutomation } from "../modules/document-automation/ConsolePage";
import { EnvironmentLocks } from "./EnvironmentLocks";
import { Ideas } from "../modules/coe/Ideas";
import { DataStore } from "../modules/low-code/DataStore";
import { Apps } from "../modules/low-code/Apps";
import { KnowledgeBases } from "../modules/ai-services/KnowledgeBases";
import { Chat } from "../modules/one-chat/Chat";
import { EventSubscriptions } from "./EventSubscriptions";
import { Globals } from "./Globals";
import { Governance } from "../modules/governance/Governance";
import { Home } from "./Home";
import { Inbox } from "./Inbox";
import { Licenses } from "./Licenses";
import { ModelConnections } from "./ModelConnections";
import { Queues } from "./Queues";
import { Policies } from "../modules/governance/Policies";
import { Roles } from "./Roles";
import { Schedules } from "./Schedules";
import { Settings } from "./Settings";
import { Skills } from "../modules/ai-services/Skills";
import { Repository } from "./Repository";
import { Teams } from "./Teams";
import { Templates } from "./Templates";
import { Tests } from "../modules/testing/Tests";
import { Toolkits } from "./Toolkits";
import { Triggers } from "./Triggers";
import { Users } from "./Users";
import { Alerts } from "../modules/insights/Alerts";
import { BusinessPage, OperationsPage } from "../modules/insights/Insights";
import { Overview } from "../modules/insights/Overview";
import { EnvironmentLabel, TopBar } from "./TopBar";
import { listKeys } from "./listKeys";
import { Vault } from "./Vault";
import { Workflows } from "./Workflows";

/** The pages this console shows itself so far; every other page opens in today's console. */
const LOCAL = new Set<string>(["policies", "activity", "alerts", "apitasks", "apps", "audit", "automation", "chat", "connections", "credentials", "data", "devices", "docautomation", "events", "globals", "governance", "ideas", "inbox", "insights", "licenses", "locks", "operations", "business", "queues", "roles", "savedconn", "schedules", "settings", "skills", "teams", "templates", "tests", "toolkits", "triggers", "workflows", "users", "knowledge"]);
/** Pages that became tabs of Operations and Business (item 7, 1 Nov 2026): their old addresses open the tab, so links keep working. */
const MOVED: Record<string, [string, string]> = { capacity: ["operations", "runners"], queueinsights: ["operations", "queues"], value: ["business", "value"], dashboards: ["business", "dashboards"] };
/** The page in the address (#/activity, or #/activity?run=<id> for one run), so a page can be linked to and the browser's Back works. */
const pageOf = (hash: string) => {
  const [id, query] = hash.replace(/^#\/?/, "").split("?");
  const moved = MOVED[id];
  if (moved) { const q = new URLSearchParams(query ?? ""); q.set("tab", moved[1]); history.replaceState(null, "", "#/" + moved[0] + "?" + q.toString()); return moved[0]; }
  return LOCAL.has(id) ? id : "home";
};
/** Today's console, which this one is served beside (at <base>/next/). */
const TODAY = "../";

/**
 * The rebuilt One CommandCenter console (C0, docs/architecture/12-ui-redesign.md): sign-in, the menu by task shared with
 * Studio, and Home. A page that is not rebuilt yet opens in today's console from the menu, so nothing here is a
 * placeholder; both consoles share the session, so no second sign-in is needed.
 */
export function ConsoleApp({ api: given }: { api?: CrApi }) {
  // one client for the life of the page: a new one on each render would re-run every effect that depends on it
  const [api] = useState(() => given ?? new CrApi());
  const [user, setUser] = useState<CrUser | null | undefined>(undefined);   // undefined: still asking
  const [page, setPageState] = useState(() => pageOf(location.hash));
  const setPage = (id: string) => { location.hash = id === "home" ? "" : "/" + id; setPageState(id); };
  useEffect(() => { const on = () => setPageState(pageOf(location.hash)); addEventListener("hashchange", on); return () => removeEventListener("hashchange", on); }, []);
  const [notice, setNotice] = useState<string | null>(null);
  const [showKeys, setShowKeys] = useState(false);   // ? or Help > Keyboard shortcuts
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (!inTextField(e) && is(e, "app.shortcuts")) { e.preventDefault(); setShowKeys(true); }
      // / from the menu, the top bar or nowhere in particular: the page's own list search (inside the page, listKeys does it)
      else if (!e.defaultPrevented && !inTextField(e) && is(e, "list.search") && !document.querySelector("[role=dialog]")) {
        const box = document.querySelector<HTMLInputElement>(".cc-main .cc-list-search input, .cc-main input[aria-label^='Search']");
        if (box) { e.preventDefault(); box.focus(); box.select(); }
      }
    };
    addEventListener("keydown", on); return () => removeEventListener("keydown", on);
  }, []);
  const [studio, setStudio] = useState(false);
  const [theme] = usePersistent<"light" | "dark">("display.theme", "light");
  useEffect(() => { document.documentElement.dataset.theme = theme; }, [theme]);
  useEffect(() => enableTableTools(), []);   // widen, narrow and filter the columns of every table
  useEffect(() => {
    api.me().then(setUser, () => setUser(null));
    api.studio().then(setStudio, () => setStudio(false));
  }, [api]);
  // Settings: sign out after idle_minutes without a key press, click, scroll or touch in this console (pages that refresh
  // themselves keep the server's session alive, so the browser decides what idle is)
  const [idleOut, setIdleOut] = useState(0);
  const idle = user ? user.idle_minutes ?? 0 : 0;
  useEffect(() => {
    if (!idle) return;
    let last = Date.now();
    const seen = () => { last = Date.now(); };
    const kinds = ["keydown", "pointerdown", "wheel", "touchstart"];
    kinds.forEach((k) => addEventListener(k, seen, { passive: true }));
    const timer = setInterval(() => {
      if (Date.now() - last < idle * 60_000) return;
      api.logout().catch(() => { /* already signed out */ });
      setIdleOut(idle); setUser(null);
    }, 15_000);
    return () => { kinds.forEach((k) => removeEventListener(k, seen)); clearInterval(timer); };
  }, [api, idle]);
  const [changing, setChanging] = useState(false);   // changing one's password before it expires

  if (user === undefined) return <div className="cc-loading" role="status">Loading…</div>;
  if (user === null) return <SignIn api={api} onIn={(u) => { setIdleOut(0); setUser(u); }} note={idleOut ? "Signed out after " + idleOut + " minutes without activity." : null} />;
  // the steps a sign-in may still need, before anything else: a new password, then two-step sign-in
  if (user.must_change) return <ChangePassword api={api} onDone={setUser} />;
  if (changing) return <ChangePassword api={api} onDone={(u) => { setChanging(false); setUser(u); setNotice("Your password is changed."); }} onCancel={() => setChanging(false)} />;
  if (user.mfa_setup_required) return <TwoStepSetup api={api} onDone={setUser} />;

  const signOut = async () => { try { await api.logout(); } catch { /* already signed out */ } setUser(null); setPage("home"); };
  const hrefFor = (id: string) => LOCAL.has(id) ? "#/" + id : TODAY + "?page=" + id;

  return (
    <div className="st-app cc-app">
      <TopBar api={api} user={user} page={page} classicHref={TODAY + "?classic"} onPage={setPage} onSignOut={signOut} onShortcuts={() => setShowKeys(true)} />
      {showKeys && <ShortcutsDialog app="console" onClose={() => setShowKeys(false)} />}
      {user.announcement && <div className="st-notice" role="status"><span>{user.announcement}</span></div>}
      {user.license_banner && <div className="st-alert" role="alert"><span>{user.license_banner}</span></div>}
      {user.password_expires_in_days !== undefined && <div className="st-notice" role="status">
        <span>Your password must be changed {user.password_expires_in_days === 0 ? "today" : "within " + user.password_expires_in_days + " day" + (user.password_expires_in_days === 1 ? "" : "s")}.</span>
        <Button kind="secondary" onClick={() => setChanging(true)}>Change it now</Button></div>}
      {notice && <div className="st-notice" role="status"><span>{notice}</span><Button kind="secondary" onClick={() => setNotice(null)}>OK</Button></div>}
      <HelpProvider><div className="st-body">
        <CommandMenu sections={MENU_SECTIONS} current={page} local={LOCAL} onLocal={setPage} hrefFor={hrefFor}
          home={{ onClick: () => setPage("home"), current: page === "home" }} viewer={{ permissions: user.permissions, leadsTeam: user.leads_team }} console />
        <main className="st-main cc-main" onKeyDown={listKeys}>
          {page === "home" && <Home api={api} user={user} pageHref={hrefFor} />}
          {page === "insights" && <Overview api={api} user={user} />}
          {page === "alerts" && <Alerts api={api} user={user} onNotice={setNotice} />}
          {page === "operations" && <OperationsPage api={api} user={user} />}
          {page === "business" && <BusinessPage api={api} user={user} onNotice={setNotice} />}
          {page === "activity" && <Activity api={api} user={user} onNotice={setNotice} />}
          {page === "devices" && <Devices api={api} user={user} onNotice={setNotice} />}
          {page === "locks" && <EnvironmentLocks api={api} user={user} onNotice={setNotice} />}
          {page === "ideas" && <Ideas api={api} onNotice={setNotice} />}
          {page === "data" && <DataStore api={api} user={user} onNotice={setNotice} />}
          {page === "apps" && <Apps api={api} user={user} onNotice={setNotice} />}
          {page === "knowledge" && <KnowledgeBases api={api} user={user} onNotice={setNotice} />}
          {page === "chat" && <Chat api={api} onOpenRuns={() => setPage("activity")} />}
          {page === "docautomation" && <DocumentAutomation api={api} />}
          {page === "users" && <Users api={api} user={user} onNotice={setNotice} />}
          {page === "roles" && <Roles api={api} onNotice={setNotice} />}
          {page === "policies" && <Policies api={api} user={user} onNotice={setNotice} />}
          {page === "teams" && <Teams api={api} user={user} onNotice={setNotice} />}
          {page === "credentials" && <Vault api={api} user={user} onNotice={setNotice} />}
          {page === "audit" && <Audit api={api} onNotice={setNotice} />}
          {page === "events" && <EventSubscriptions api={api} onNotice={setNotice} />}
          {page === "licenses" && <Licenses api={api} user={user} onNotice={setNotice} />}
          {page === "settings" && <Settings api={api} onNotice={setNotice} />}
          {page === "inbox" && <Inbox api={api} user={user} onNotice={setNotice} />}
          {page === "schedules" && <Schedules api={api} user={user} onNotice={setNotice} />}
          {page === "triggers" && <Triggers api={api} user={user} onNotice={setNotice} />}
          {page === "queues" && <Queues api={api} user={user} onNotice={setNotice} />}
          {page === "workflows" && <Workflows api={api} user={user} onNotice={setNotice} />}
          {page === "globals" && <Globals api={api} user={user} onNotice={setNotice} />}
          {page === "savedconn" && <Connections api={api} user={user} onNotice={setNotice} />}
          {page === "toolkits" && <Toolkits api={api} user={user} onNotice={setNotice} />}
          {page === "connections" && <ModelConnections api={api} user={user} onNotice={setNotice} />}
          {page === "skills" && <Skills api={api} user={user} onNotice={setNotice} />}
          {page === "governance" && <Governance api={api} onNotice={setNotice} />}
          {page === "apitasks" && <AgentApis api={api} user={user} onNotice={setNotice} />}
          {page === "automation" && <Repository api={api} user={user} onNotice={setNotice} studio={studio} />}
          {page === "tests" && <Tests api={api} user={user} onNotice={setNotice} />}
          {page === "templates" && <Templates api={api} user={user} onNotice={setNotice} />}
        </main>
        <HelpPanel page={page} />
      </div></HelpProvider>
    </div>
  );
}

/**
 * Sign in with a user name and password, then a code when two-step sign-in is on; or with the organisation's identity
 * provider (OpenID Connect or SAML, when set up), which brings the person back to this page. Local accounts, such as
 * the administrator, can always sign in with their password, so a broken single sign-on never locks everyone out.
 */
function SignIn({ api, onIn, note }: { api: CrApi; onIn: (u: CrUser) => void; note?: string | null }) {
  const [sso, setSso] = useState<{ oidc: string | null; saml: string | null; notice: { text: string; accept: boolean } | null; environment: Environment | null; directory: boolean }>({ oidc: null, saml: null, notice: null, environment: null, directory: false });
  // the sign-in notice (Settings): shown above the form, and ticked before signing in when it must be accepted
  const [accepted, setAccepted] = useState(false);
  const mustAccept = !!sso.notice?.accept && !accepted;
  useEffect(() => { api.ssoInfo().then(setSso, () => {}); }, [api]);
  const back = location.pathname + location.hash;
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const r = mfaToken ? await api.loginCode(mfaToken, code.trim()) : await api.login(username.trim(), password, accepted);
      setPassword("");
      if (r.mfa_required && r.mfa_token) { setMfaToken(r.mfa_token); return; }
      onIn(r.user ?? await api.me());
    } catch (err) {
      if (err instanceof CrFailure && err.code === "CR.MFA_EXPIRED") { setMfaToken(null); setCode(""); }
      setError(err instanceof Error ? err.message : String(err));
    } finally { setBusy(false); }
  };
  return (
    <div className="cc-signin">
      <form className="cc-signin-card" onSubmit={submit} aria-label="Sign in">
        <img className="cc-signin-logo" src="/logo-full.png" alt="ONEOFFICEAUTOMATION" /><div className="st-brand cc-signin-brand">One CommandCenter</div>
        {note && <p className="cc-signin-note" role="status">{note}</p>}
        {sso.environment && <div className="cc-signin-env"><EnvironmentLabel env={sso.environment} /></div>}
        {sso.directory && !mfaToken && <p className="cc-signin-note">Your Windows (directory) user name and password work here.</p>}
        {sso.notice && !mfaToken && <div className="cc-signin-notice" role="note" aria-label="Sign-in notice">
          <p>{sso.notice.text}</p>
          {sso.notice.accept && <label className="st-check-line"><input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} /> I have read and accept this</label>}
        </div>}
        {mfaToken
          ? <Field label="Code from your authenticator app">{(id) => <TextInput id={id} value={code} inputMode="numeric" autoComplete="one-time-code" onChange={(e) => setCode(e.target.value)} />}</Field>
          : <>
            <Field label="User name">{(id) => <TextInput id={id} value={username} autoComplete="username" onChange={(e) => setUsername(e.target.value)} />}</Field>
            <Field label="Password">{(id) => <TextInput id={id} type="password" value={password} autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} />}</Field>
          </>}
        {error && <p className="cc-error" role="alert">{error}</p>}
        <Button kind="primary" type="submit" disabled={busy || (mfaToken ? !code.trim() : !username.trim() || !password || mustAccept)}>{busy ? "Signing in…" : mfaToken ? "Continue" : "Sign in"}</Button>
        {mfaToken && <Button kind="plain" type="button" onClick={() => { setMfaToken(null); setCode(""); setError(null); }}>Back</Button>}
        {!mfaToken && (sso.oidc || sso.saml) && <>
          <div className="cc-or" aria-hidden="true">or</div>
          {sso.oidc && !mustAccept && <a className="ui-btn ui-btn-secondary cc-sso" href={api.ssoStart("oidc", back)}><Icon name="person" size={14} />{sso.oidc}</a>}
          {sso.saml && !mustAccept && <a className="ui-btn ui-btn-secondary cc-sso" href={api.ssoStart("saml", back)}><Icon name="person" size={14} />{sso.saml}</a>}
          {mustAccept && <p className="cc-none">Accept the notice above to sign in with your organisation account.</p>}
        </>}
        <p className="cc-signin-alt">Forgot your password? An administrator can reset it.</p>
      </form>
    </div>
  );
}

/** The first sign-in with a password an administrator set, or after a reset: the person chooses their own. */
function ChangePassword({ api, onDone, onCancel }: { api: CrApi; onDone: (u: CrUser) => void; onCancel?: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (next !== again) { setError("The new passwords do not match."); return; }
    setBusy(true); setError(null);
    try { await api.changePassword(current, next); setCurrent(""); setNext(""); setAgain(""); onDone(await api.me()); }
    catch (err) { setError(err instanceof Error ? err.message : String(err)); }
    finally { setBusy(false); }
  };
  return (
    <div className="cc-signin">
      <form className="cc-signin-card" onSubmit={submit} aria-label="Choose your password">
        <img className="cc-signin-logo" src="/logo-full.png" alt="ONEOFFICEAUTOMATION" /><div className="st-brand cc-signin-brand">One CommandCenter</div>
        <p>{onCancel ? "Choose a new password." : "Choose your own password before you go on."}</p>
        <Field label="Current password">{(id) => <TextInput id={id} type="password" value={current} autoComplete="current-password" onChange={(e) => setCurrent(e.target.value)} />}</Field>
        <Field label="New password">{(id) => <TextInput id={id} type="password" value={next} autoComplete="new-password" onChange={(e) => setNext(e.target.value)} />}</Field>
        <Field label="New password again">{(id) => <TextInput id={id} type="password" value={again} autoComplete="new-password" onChange={(e) => setAgain(e.target.value)} />}</Field>
        {error && <p className="cc-error" role="alert">{error}</p>}
        <Button kind="primary" type="submit" disabled={busy || !current || !next || !again}>{busy ? "Saving..." : "Save and continue"}</Button>
        {onCancel && <Button kind="plain" type="button" onClick={onCancel}>Not now</Button>}
      </form>
    </div>
  );
}

/**
 * Two-step sign-in when the administrator requires it: a key for an authenticator app, a code to turn it on, then
 * the recovery codes, shown once.
 */
function TwoStepSetup({ api, onDone }: { api: CrApi; onDone: (u: CrUser) => void }) {
  const [key, setKey] = useState<{ secret: string; otpauth_uri: string } | null>(null);
  const [code, setCode] = useState("");
  const [recovery, setRecovery] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.mfaSetup().then(setKey, (e) => setError(e instanceof Error ? e.message : String(e))); }, [api]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try { setRecovery((await api.mfaEnable(code.trim())).recovery_codes); }
    catch (err) { setError(err instanceof Error ? err.message : String(err)); }
  };
  return (
    <div className="cc-signin">
      {recovery ? (
        <div className="cc-signin-card" role="region" aria-label="Recovery codes">
          <img className="cc-signin-logo" src="/logo-full.png" alt="ONEOFFICEAUTOMATION" /><div className="st-brand cc-signin-brand">One CommandCenter</div>
          <p>Two-step sign-in is on. Keep these recovery codes somewhere safe: each one signs you in once if you lose your phone. They are shown only now.</p>
          <pre className="cc-codes">{recovery.join("\n")}</pre>
          <Button kind="primary" onClick={async () => onDone(await api.me())}>I have saved them</Button>
        </div>
      ) : (
        <form className="cc-signin-card" onSubmit={submit} aria-label="Set up two-step sign-in">
          <img className="cc-signin-logo" src="/logo-full.png" alt="ONEOFFICEAUTOMATION" /><div className="st-brand cc-signin-brand">One CommandCenter</div>
          <p>Your administrator requires a code from an authenticator app as well as your password.</p>
          <p>1. In an authenticator app (Microsoft Authenticator, Google Authenticator or another time-based app), add an account with this key:</p>
          {key ? <><code className="cc-key">{key.secret}</code><a className="cc-signin-alt" href={key.otpauth_uri}>On a phone: open it in the app</a></> : !error && <p className="st-muted" role="status">Loading...</p>}
          <Field label="2. The 6-digit code the app shows">{(id) => <TextInput id={id} value={code} inputMode="numeric" autoComplete="one-time-code" maxLength={6} onChange={(e) => setCode(e.target.value)} />}</Field>
          {error && <p className="cc-error" role="alert">{error}</p>}
          <Button kind="primary" type="submit" disabled={!key || code.trim().length < 6}>Turn on</Button>
        </form>
      )}
    </div>
  );
}
