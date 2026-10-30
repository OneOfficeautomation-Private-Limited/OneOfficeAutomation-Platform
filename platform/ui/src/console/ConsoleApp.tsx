import { useEffect, useState, type FormEvent } from "react";
import { CrApi, CrFailure, type CrUser } from "../api/cr";
import { Button, Field, Icon, Menu, TextInput, usePersistent } from "../components";
import { CommandMenu } from "../shell/CommandMenu";
import { MENU_SECTIONS } from "../shell/menu";
import { Activity } from "./Activity";
import { Audit } from "./Audit";
import { Devices } from "./Devices";
import { DocumentAutomation } from "./DocumentAutomation";
import { Home } from "./Home";
import { Inbox } from "./Inbox";
import { Licenses } from "./Licenses";
import { Queues } from "./Queues";
import { Roles } from "./Roles";
import { Schedules } from "./Schedules";
import { Settings } from "./Settings";
import { Teams } from "./Teams";
import { Triggers } from "./Triggers";
import { Users } from "./Users";
import { Vault } from "./Vault";
import { Workflows } from "./Workflows";

/** The pages this console shows itself so far; every other page opens in today's console. */
const LOCAL = new Set<string>(["activity", "audit", "credentials", "devices", "docautomation", "inbox", "licenses", "queues", "roles", "schedules", "settings", "teams", "triggers", "workflows", "users"]);
/** The page in the address (#/activity), so a page can be linked to and the browser's Back works. */
const pageOf = (hash: string) => { const id = hash.replace(/^#\/?/, ""); return LOCAL.has(id) ? id : "home"; };
/** Today's console, which this one is served beside (at <base>/next/). */
const TODAY = "../";

/**
 * The rebuilt Command Center console (C0, docs/architecture/12-ui-redesign.md): sign-in, the menu by task shared with
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
  const [studio, setStudio] = useState(false);
  const [theme] = usePersistent<"light" | "dark">("display.theme", "light");
  useEffect(() => { document.documentElement.dataset.theme = theme; }, [theme]);
  useEffect(() => {
    api.me().then(setUser, () => setUser(null));
    api.studio().then(setStudio, () => setStudio(false));
  }, [api]);

  if (user === undefined) return <div className="cc-loading" role="status">Loading…</div>;
  if (user === null) return <SignIn api={api} onIn={setUser} />;
  // the steps a sign-in may still need, before anything else: a new password, then two-step sign-in
  if (user.must_change) return <ChangePassword api={api} onDone={setUser} />;
  if (user.mfa_setup_required) return <TwoStepSetup api={api} onDone={setUser} />;

  const signOut = async () => { try { await api.logout(); } catch { /* already signed out */ } setUser(null); setPage("home"); };
  const hrefFor = (id: string) => id === "studio" ? (studio ? "../studio/" : undefined) : LOCAL.has(id) ? "#/" + id : TODAY + "?page=" + id;

  return (
    <div className="st-app">
      <header className="st-top">
        <div className="st-brand"><span className="st-logo" />One CommandCenter</div>
        <span className="cc-preview">New console (preview)</span>
        <div className="st-top-actions">
          <a className="ui-btn ui-btn-light" href={TODAY}>Today's console</a>
          <Menu label="Your account" align="end" title={user.display_name + " · " + (user.roles?.length ? user.roles.join(", ") : user.role)}
            items={[{ key: "out", label: "Sign out", onSelect: signOut }]} trigger={<button type="button" className="st-top-icon cc-user" aria-label={"Account: " + user.display_name} title={user.display_name}>
            <Icon name="person" size={16} /></button>} />
        </div>
      </header>
      {user.announcement && <div className="st-notice" role="status"><span>{user.announcement}</span></div>}
      {user.license_banner && <div className="st-alert" role="alert"><span>{user.license_banner}</span></div>}
      {notice && <div className="st-notice" role="status"><span>{notice}</span><Button kind="secondary" onClick={() => setNotice(null)}>OK</Button></div>}
      <div className="st-body">
        <CommandMenu sections={MENU_SECTIONS} current={page} local={LOCAL} onLocal={setPage} hrefFor={hrefFor}
          home={{ onClick: () => setPage("home"), current: page === "home" }} viewer={{ permissions: user.permissions, leadsTeam: user.leads_team }}
          offTitle="Agent Studio runs beside the Command Center (ooa server), on the same computer" />
        <main className="st-main cc-main">
          {page === "home" && <Home api={api} user={user} pageHref={hrefFor} />}
          {page === "activity" && <Activity api={api} user={user} onNotice={setNotice} />}
          {page === "devices" && <Devices api={api} user={user} onNotice={setNotice} />}
          {page === "docautomation" && <DocumentAutomation api={api} />}
          {page === "users" && <Users api={api} user={user} onNotice={setNotice} />}
          {page === "roles" && <Roles api={api} onNotice={setNotice} />}
          {page === "teams" && <Teams api={api} user={user} onNotice={setNotice} />}
          {page === "credentials" && <Vault api={api} user={user} onNotice={setNotice} />}
          {page === "audit" && <Audit api={api} onNotice={setNotice} />}
          {page === "licenses" && <Licenses api={api} user={user} onNotice={setNotice} />}
          {page === "settings" && <Settings api={api} onNotice={setNotice} />}
          {page === "inbox" && <Inbox api={api} user={user} onNotice={setNotice} />}
          {page === "schedules" && <Schedules api={api} user={user} onNotice={setNotice} />}
          {page === "triggers" && <Triggers api={api} user={user} onNotice={setNotice} />}
          {page === "queues" && <Queues api={api} user={user} onNotice={setNotice} />}
          {page === "workflows" && <Workflows api={api} user={user} onNotice={setNotice} />}
        </main>
      </div>
    </div>
  );
}

/**
 * Sign in with a user name and password, then a code when two-step sign-in is on; or with the organisation's identity
 * provider (OpenID Connect or SAML, when set up), which brings the person back to this page. Local accounts, such as
 * the administrator, can always sign in with their password, so a broken single sign-on never locks everyone out.
 */
function SignIn({ api, onIn }: { api: CrApi; onIn: (u: CrUser) => void }) {
  const [sso, setSso] = useState<{ oidc: string | null; saml: string | null }>({ oidc: null, saml: null });
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
      const r = mfaToken ? await api.loginCode(mfaToken, code.trim()) : await api.login(username.trim(), password);
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
        <div className="st-brand cc-signin-brand"><span className="st-logo" />One CommandCenter</div>
        {mfaToken
          ? <Field label="Code from your authenticator app">{(id) => <TextInput id={id} value={code} inputMode="numeric" autoComplete="one-time-code" onChange={(e) => setCode(e.target.value)} />}</Field>
          : <>
            <Field label="User name">{(id) => <TextInput id={id} value={username} autoComplete="username" onChange={(e) => setUsername(e.target.value)} />}</Field>
            <Field label="Password">{(id) => <TextInput id={id} type="password" value={password} autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} />}</Field>
          </>}
        {error && <p className="cc-error" role="alert">{error}</p>}
        <Button kind="primary" type="submit" disabled={busy || (mfaToken ? !code.trim() : !username.trim() || !password)}>{busy ? "Signing in…" : mfaToken ? "Continue" : "Sign in"}</Button>
        {mfaToken && <Button kind="plain" type="button" onClick={() => { setMfaToken(null); setCode(""); setError(null); }}>Back</Button>}
        {!mfaToken && (sso.oidc || sso.saml) && <>
          <div className="cc-or" aria-hidden="true">or</div>
          {sso.oidc && <a className="ui-btn ui-btn-secondary cc-sso" href={api.ssoStart("oidc", back)}><Icon name="person" size={14} />{sso.oidc}</a>}
          {sso.saml && <a className="ui-btn ui-btn-secondary cc-sso" href={api.ssoStart("saml", back)}><Icon name="person" size={14} />{sso.saml}</a>}
        </>}
        <p className="cc-signin-alt">Forgot your password? An administrator can reset it.</p>
      </form>
    </div>
  );
}

/** The first sign-in with a password an administrator set, or after a reset: the person chooses their own. */
function ChangePassword({ api, onDone }: { api: CrApi; onDone: (u: CrUser) => void }) {
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
        <div className="st-brand cc-signin-brand"><span className="st-logo" />One CommandCenter</div>
        <p>Choose your own password before you go on.</p>
        <Field label="Current password">{(id) => <TextInput id={id} type="password" value={current} autoComplete="current-password" onChange={(e) => setCurrent(e.target.value)} />}</Field>
        <Field label="New password">{(id) => <TextInput id={id} type="password" value={next} autoComplete="new-password" onChange={(e) => setNext(e.target.value)} />}</Field>
        <Field label="New password again">{(id) => <TextInput id={id} type="password" value={again} autoComplete="new-password" onChange={(e) => setAgain(e.target.value)} />}</Field>
        {error && <p className="cc-error" role="alert">{error}</p>}
        <Button kind="primary" type="submit" disabled={busy || !current || !next || !again}>{busy ? "Saving..." : "Save and continue"}</Button>
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
          <div className="st-brand cc-signin-brand"><span className="st-logo" />One CommandCenter</div>
          <p>Two-step sign-in is on. Keep these recovery codes somewhere safe: each one signs you in once if you lose your phone. They are shown only now.</p>
          <pre className="cc-codes">{recovery.join("\n")}</pre>
          <Button kind="primary" onClick={async () => onDone(await api.me())}>I have saved them</Button>
        </div>
      ) : (
        <form className="cc-signin-card" onSubmit={submit} aria-label="Set up two-step sign-in">
          <div className="st-brand cc-signin-brand"><span className="st-logo" />One CommandCenter</div>
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
