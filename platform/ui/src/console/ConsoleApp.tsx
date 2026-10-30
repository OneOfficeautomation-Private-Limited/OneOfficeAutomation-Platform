import { useEffect, useState, type FormEvent } from "react";
import { CrApi, CrFailure, type CrUser } from "../api/cr";
import { Button, Field, Icon, Menu, TextInput, usePersistent } from "../components";
import { CommandMenu } from "../shell/CommandMenu";
import { MENU_SECTIONS } from "../shell/menu";
import { Home } from "./Home";

/** The pages this console shows itself so far; every other page opens in today's console. */
const LOCAL = new Set<string>([]);
/** Today's console, which this one is served beside (at <base>/next/). */
const TODAY = "../";

/**
 * The rebuilt Command Center console (C0, docs/architecture/12-ui-redesign.md): sign-in, the menu by task shared with
 * Studio, and Home. A page that is not rebuilt yet opens in today's console from the menu, so nothing here is a
 * placeholder; both consoles share the session, so no second sign-in is needed.
 */
export function ConsoleApp({ api = new CrApi() }: { api?: CrApi }) {
  const [user, setUser] = useState<CrUser | null | undefined>(undefined);   // undefined: still asking
  const [page, setPage] = useState("home");
  const [studio, setStudio] = useState(false);
  const [theme] = usePersistent<"light" | "dark">("display.theme", "light");
  const [density] = usePersistent<"standard" | "compact">("display.density", "standard");
  useEffect(() => { document.documentElement.dataset.theme = theme; document.documentElement.dataset.density = density; }, [theme, density]);
  useEffect(() => {
    api.me().then(setUser, () => setUser(null));
    api.studio().then(setStudio, () => setStudio(false));
  }, [api]);

  if (user === undefined) return <div className="cc-loading" role="status">Loading…</div>;
  if (user === null) return <SignIn api={api} onIn={setUser} />;

  const signOut = async () => { try { await api.logout(); } catch { /* already signed out */ } setUser(null); setPage("home"); };
  const hrefFor = (id: string) => id === "studio" ? (studio ? "../studio/" : undefined) : TODAY + "?page=" + id;
  // the steps signing in still needs (a new password, two-step sign-in) are done in today's console
  const pending = user.must_change ? "Change your password before you go on." : user.mfa_setup_required ? "Set up two-step sign-in before you go on." : null;

  return (
    <div className="st-app">
      <header className="st-top">
        <div className="st-brand"><span className="st-logo" />One Command Center</div>
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
      {pending && <div className="st-alert" role="alert"><span>{pending}</span><a className="ui-btn ui-btn-secondary" href={TODAY}>Open today's console</a></div>}
      <div className="st-body">
        <CommandMenu sections={MENU_SECTIONS} current={page} local={LOCAL} onLocal={setPage} hrefFor={hrefFor}
          home={{ onClick: () => setPage("home"), current: page === "home" }} viewer={{ permissions: user.permissions, leadsTeam: user.leads_team }}
          offTitle="Agent Studio runs beside the Command Center (ooa server), on the same computer" />
        <main className="st-main cc-main">
          {page === "home" && <Home api={api} user={user} pageHref={hrefFor} />}
        </main>
      </div>
    </div>
  );
}

/** Sign in with a user name and password, then a code when two-step sign-in is on. */
function SignIn({ api, onIn }: { api: CrApi; onIn: (u: CrUser) => void }) {
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
        <div className="st-brand cc-signin-brand"><span className="st-logo" />One Command Center</div>
        {mfaToken
          ? <Field label="Code from your authenticator app">{(id) => <TextInput id={id} value={code} inputMode="numeric" autoComplete="one-time-code" onChange={(e) => setCode(e.target.value)} />}</Field>
          : <>
            <Field label="User name">{(id) => <TextInput id={id} value={username} autoComplete="username" onChange={(e) => setUsername(e.target.value)} />}</Field>
            <Field label="Password">{(id) => <TextInput id={id} type="password" value={password} autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} />}</Field>
          </>}
        {error && <p className="cc-error" role="alert">{error}</p>}
        <Button kind="primary" type="submit" disabled={busy || (mfaToken ? !code.trim() : !username.trim() || !password)}>{busy ? "Signing in…" : mfaToken ? "Continue" : "Sign in"}</Button>
        {mfaToken && <Button kind="plain" type="button" onClick={() => { setMfaToken(null); setCode(""); setError(null); }}>Back</Button>}
        <a className="cc-signin-alt" href={TODAY}>Single sign-on, or a forgotten password: use today's console</a>
      </form>
    </div>
  );
}
