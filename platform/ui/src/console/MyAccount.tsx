import { useEffect, useState } from "react";
import type { CrApi, MyDevices, MyProfile, MySession } from "../api/cr";
import { Button, Dialog, Field, Select, Tabs, TabPanel, TextInput } from "../components";
import { HelpLinks } from "../shell/HelpLinks";
import { when } from "./ListParts";
import { RunnerDownload } from "./Devices";

/**
 * My account (13 Nov 2026 round 3, item 9, stage A), as AA's user menu: My profile (who you are here, your roles,
 * teams and licence seat, how you sign in, where you are signed in now) and My devices (your default runner and the
 * runners you work with, whether each is online). What the account menu held (API keys, password, two-step sign-in,
 * phone notifications) opens from here too.
 * Stage B (as AA's My settings): edit your first name, last name and contact e-mail; Choose your default runner;
 * Connect to my computer (a one-hour key for your own runner); and Device credentials, the Windows sign-in your
 * unattended runs use, kept encrypted for you alone, checked on a runner (Check) or set as its Windows sign-in after a
 * restart (Use for automatic sign-in).
 */
const roleWords = (r: string) => r.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
const statusWords: Record<string, string> = { online: "Online", offline: "Offline", disabled: "Switched off", "out of service": "Out of service" };

export function MyAccountDialog({ api, onClose, onApiKeys, onPassword, onTwoStep, onPhone, startTab = "profile" }:
  { api: CrApi; onClose: () => void; onApiKeys: () => void; onPassword: () => void; onTwoStep: () => void; onPhone: () => void; startTab?: "profile" | "devices" }) {
  const [tab, setTab] = useState(startTab);
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [sessions, setSessions] = useState<MySession[] | null>(null);
  const [devices, setDevices] = useState<MyDevices | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const fail = (e: unknown) => setError(e instanceof Error ? e.message : String(e));
  useEffect(() => { api.myProfile().then(setProfile, fail); api.mySessions().then(setSessions, fail); api.myDevices().then(setDevices, fail); }, [api]);
  const endOthers = () => api.endOtherSessions().then((n) => { setNotice(n ? "Signed out " + n + " other session" + (n === 1 ? "" : "s") + "." : "There were no other sessions."); return api.mySessions(); })
    .then(setSessions, fail);
  const row = (label: string, value: React.ReactNode) => value ? <><dt>{label}</dt><dd>{value}</dd></> : null;
  // stage B
  const [editing, setEditing] = useState<{ first_name: string; last_name: string; email: string } | null>(null);
  const saveProfile = () => editing && api.updateMyProfile(editing).then((p) => { setProfile(p); setEditing(null); setNotice("Your profile is saved."); }, fail);
  const [choices, setChoices] = useState<{ id: string; name: string; computer: string }[] | null>(null);
  const [chosen, setChosen] = useState("");
  const choose = () => api.runnerChoices().then((c) => { setChoices(c); setChosen(devices?.default_runner ?? ""); }, fail);
  const saveDefault = () => chosen && api.chooseDefaultRunner(chosen).then((d) => { setDevices(d); setChoices(null); setNotice("Your default runner is changed."); }, fail);
  const [connect, setConnect] = useState<{ key: string } | null>(null);
  const [cred, setCred] = useState<{ username: string; password: string; device: string } | null>(null);
  const saveCred = () => cred && api.setDeviceCredentials({ username: cred.username, password: cred.password, device_id: cred.device || null })
    .then(() => { setCred(null); setNotice("Your device credentials are saved. Check them on a runner below."); return api.myDevices(); }).then(setDevices, fail);
  const removeCred = () => api.removeDeviceCredentials().then(() => { setNotice("Your device credentials are removed."); return api.myDevices(); }).then(setDevices, fail);
  const ask = (id: string, apply: boolean) => api.checkDeviceLogin(id, apply)
    .then(() => { setNotice(apply ? "Asked the runner to check and use your credentials; the answer shows here within a minute." : "Asked the runner to check your credentials; the answer shows here within a minute."); return api.myDevices(); })
    .then(setDevices, fail);
  const loginWords = (l: NonNullable<MyDevices["runners"][number]["login"]>) => l.state === "waiting" ? "Checking…" : l.state === "ok" ? "Credentials work"
    : l.state === "applied" ? "Credentials work; used for automatic sign-in" : "Did not work: " + (l.error ?? "");

  return (
    <Dialog open title="My account" width={720} onClose={onClose} help={<HelpLinks k="dialog:my-account" label="My account" />}
      footer={<Button kind="primary" onClick={onClose}>Close</Button>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {notice && <p className="cc-ok" role="status">{notice}</p>}
      <Tabs label="My account" value={tab} onChange={(v) => setTab(v as "profile" | "devices")}
        tabs={[{ value: "profile", label: "My profile" }, { value: "devices", label: "My devices" }]}>
        <TabPanel value="profile">
          {!profile ? <p className="st-muted" role="status">Loading…</p> : <>
            <dl className="cc-dl cc-my-dl">
              {row("Name", profile.display_name)}
              {row("User name", profile.username)}
              {row("E-mail", profile.email)}
              {row("Roles", profile.roles.map(roleWords).join(", "))}
              {row("What you may do", profile.permissions.length + " permissions (Administration > Roles lists them)")}
              {row("Teams", profile.teams.length ? profile.teams.map((t) => t.name + (t.lead ? " (lead)" : "")).join(", ") : "None")}
              {row("Licence seat", profile.license_seats.length ? profile.license_seats.map(roleWords).join(", ") : "None assigned")}
              {row("Signs in with", profile.sign_in)}
              {row("Two-step sign-in", profile.mfa_enabled ? "On" : "Off")}
              {row("Last sign-in", profile.last_login ? when(profile.last_login) + (profile.last_sign_in_from ? " from " + profile.last_sign_in_from : "") : "")}
              {row("Password must change by", profile.password_expires ? when(profile.password_expires) : "")}
              {row("Account created", profile.created ? when(profile.created) : "")}
            </dl>
            {editing ? <div className="cc-my-form" role="group" aria-label="Edit profile">
                <Field label="First name">{(id) => <TextInput id={id} value={editing.first_name} maxLength={50} onChange={(e) => setEditing({ ...editing, first_name: e.target.value })} />}</Field>
                <Field label="Last name">{(id) => <TextInput id={id} value={editing.last_name} maxLength={50} onChange={(e) => setEditing({ ...editing, last_name: e.target.value })} />}</Field>
                <Field label="Contact e-mail">{(id) => <TextInput id={id} type="email" value={editing.email} onChange={(e) => setEditing({ ...editing, email: e.target.value })} />}</Field>
                <div className="cc-my-actions"><Button kind="primary" onClick={saveProfile}>Save changes</Button><Button kind="plain" onClick={() => setEditing(null)}>Cancel</Button></div>
              </div> : null}
            <div className="cc-my-actions">
              {!editing && profile.sign_in === "password" && <Button kind="secondary" onClick={() => setEditing({ first_name: profile.first_name ?? "", last_name: profile.last_name ?? "", email: profile.email ?? "" })}>Edit profile</Button>}
              <Button kind="secondary" onClick={onApiKeys}>Your API keys</Button>
              {profile.sign_in === "password" && <Button kind="secondary" onClick={onPassword}>Change password</Button>}
              {!profile.mfa_enabled && <Button kind="secondary" onClick={onTwoStep}>Turn on two-step sign-in</Button>}
              <Button kind="secondary" onClick={onPhone}>Phone notifications</Button>
            </div>
            <h3 className="cc-my-h">Where you are signed in</h3>
            {!sessions ? <p className="st-muted">Loading…</p> : <>
              <table className="st-table" aria-label="Sessions">
                <thead><tr><th scope="col">Signed in</th><th scope="col">From</th><th scope="col">Ends</th><th scope="col"></th></tr></thead>
                <tbody>{sessions.map((s) => <tr key={s.id}><td>{when(s.created)}</td><td>{s.from || "This computer"}</td><td>{when(s.expires)}</td>
                  <td>{s.current ? <strong>This session</strong> : ""}</td></tr>)}</tbody>
              </table>
              {sessions.some((s) => !s.current) && <p><Button kind="secondary" onClick={endOthers}>Sign out everywhere else</Button></p>}
            </>}
          </>}
        </TabPanel>
        <TabPanel value="devices">
          {!devices ? <p className="st-muted" role="status">Loading…</p> : <>
            {devices.hint && <p className="st-alert" role="note">{devices.hint}</p>}
            {devices.runners.length === 0
              ? <p className="cc-none">No runner is set up for you yet. An administrator makes one your default runner in Administration &gt; Users, or adds you as a default user of a runner in Manage &gt; Runner Machines.</p>
              : <table className="st-table" aria-label="My runners">
                <thead><tr><th scope="col">Runner</th><th scope="col">Status</th><th scope="col">Computer</th><th scope="col">Version</th><th scope="col">Last seen</th><th scope="col">Used as</th></tr></thead>
                <tbody>{devices.runners.map((r) => <tr key={r.id}><td><strong>{r.name}</strong></td>
                  <td><span className={"cc-pill is-" + (r.status === "online" ? "success" : r.status === "offline" ? "failed" : "pending")}><span className="cc-pill-dot" />{statusWords[r.status] ?? r.status}</span></td>
                  <td>{r.computer ?? ""}{r.platform ? " · " + r.platform : ""}</td><td>{r.version ?? ""}</td><td>{r.last_seen ? when(r.last_seen) : "Never"}</td>
                  <td>{r.default ? "Your default runner" : r.local ? "Your local runner" : "A runner you may use"}{r.shared ? " (shared)" : ""}</td></tr>)}</tbody>
              </table>}
            <div className="cc-my-actions">
              {devices.can_choose && <Button kind="secondary" onClick={choose}>Choose default runner</Button>}
              {devices.can_connect && <Button kind="secondary" onClick={() => api.connectKey().then((k) => setConnect({ key: k.key }), fail)}>Connect to my computer</Button>}
            </div>
            {choices && <div className="cc-my-form" role="group" aria-label="Choose default runner">
              <Field label="Default runner">{(id) => <Select id={id} value={chosen} onChange={(e) => setChosen(e.target.value)}
                options={[{ value: "", label: "Choose a runner" }, ...choices.map((c) => ({ value: c.id, label: c.name + (c.computer ? " (" + c.computer + ")" : "") }))]} />}</Field>
              <div className="cc-my-actions"><Button kind="primary" disabled={!chosen} onClick={saveDefault}>Save changes</Button><Button kind="plain" onClick={() => setChoices(null)}>Cancel</Button></div>
            </div>}
            {connect && <div className="cc-my-form" role="group" aria-label="Connect to my computer">
              <RunnerDownload api={api} runnerKey={connect.key} />
              <p>Or install the runner yourself and give it this address and key. The key works once, for 1 hour.</p>
              <dl className="cc-dl cc-my-dl">{row("Address", location.origin)}{row("Key", <code>{connect.key}</code>)}</dl>
              <p>With the setup package: run <code>Install.cmd</code> and give the address and key when it asks. With the product already installed, from its folder:</p>
              <pre className="cc-my-code">{"bin\\ooa.bat agent register --url " + location.origin + " --key " + connect.key + "\nbin\\ooa.bat agent install-task"}</pre>
              <p>The runner becomes your local runner and, if you have none, your default runner.</p>
              <div className="cc-my-actions"><Button kind="plain" onClick={() => setConnect(null)}>Done</Button></div>
            </div>}
            <h3 className="cc-my-h">Device credentials</h3>
            <p className="cc-my-note">The Windows user name and password your unattended runs sign in with. They are kept encrypted for you alone, never shown again, and sent only to your own runners when you check or use them.</p>
            {cred ? <div className="cc-my-form" role="group" aria-label="Device credentials">
              <Field label="Windows user name">{(id) => <TextInput id={id} value={cred.username} placeholder="DOMAIN\name or AzureAD\name@company.com" onChange={(e) => setCred({ ...cred, username: e.target.value })} />}</Field>
              <Field label="Password">{(id) => <TextInput id={id} type="password" autoComplete="new-password" value={cred.password} onChange={(e) => setCred({ ...cred, password: e.target.value })} />}</Field>
              <Field label="Use on">{(id) => <Select id={id} value={cred.device} onChange={(e) => setCred({ ...cred, device: e.target.value })}
                options={[{ value: "", label: "Every runner of mine" }, ...devices.runners.map((r) => ({ value: r.id, label: "Only " + r.name }))]} />}</Field>
              <div className="cc-my-actions"><Button kind="primary" disabled={!cred.username.trim() || !cred.password} onClick={saveCred}>Save changes</Button><Button kind="plain" onClick={() => setCred(null)}>Cancel</Button></div>
            </div> : <p>{devices.credentials_set ? "Set." : "Not set."} <Button kind="secondary" onClick={() => setCred({ username: "", password: "", device: "" })}>{devices.credentials_set ? "Change" : "Set credentials"}</Button>
              {devices.credentials_set && <Button kind="plain" onClick={removeCred}>Remove</Button>}</p>}
            {devices.credentials_set && devices.runners.length > 0 && <table className="st-table" aria-label="Check device credentials">
              <thead><tr><th scope="col">Runner</th><th scope="col">Credentials on it</th><th scope="col"></th></tr></thead>
              <tbody>{devices.runners.map((r) => <tr key={r.id}><td>{r.name}{r.local ? " (your local runner)" : ""}</td><td>{r.login ? loginWords(r.login) : "Not checked"}</td>
                <td><Button kind="plain" onClick={() => ask(r.id, false)}>Check</Button><Button kind="plain" onClick={() => ask(r.id, true)}>Use for automatic sign-in</Button></td></tr>)}</tbody>
            </table>}
          </>}
        </TabPanel>
      </Tabs>
    </Dialog>
  );
}
