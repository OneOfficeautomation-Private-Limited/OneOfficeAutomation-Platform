import { useEffect, useState } from "react";
import type { CrApi, MyDevices, MyProfile, MySession } from "../api/cr";
import { Button, Dialog, Tabs, TabPanel } from "../components";
import { HelpLinks } from "../shell/HelpLinks";
import { when } from "./ListParts";

/**
 * My account (13 Nov 2026 round 3, item 9, stage A), as AA's user menu: My profile (who you are here, your roles,
 * teams and licence seat, how you sign in, where you are signed in now) and My devices (your default runner and the
 * runners you work with, whether each is online). What the account menu held (API keys, password, two-step sign-in,
 * phone notifications) opens from here too.
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
            <div className="cc-my-actions">
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
                  <td>{r.default ? "Your default runner" : "A runner you may use"}{r.shared ? " (shared)" : ""}</td></tr>)}</tbody>
              </table>}
            <p className="st-muted cc-my-note">Connecting this computer as your own runner, and the sign-in your unattended runs use, come in the next step of My devices.</p>
          </>}
        </TabPanel>
      </Tabs>
    </Dialog>
  );
}
