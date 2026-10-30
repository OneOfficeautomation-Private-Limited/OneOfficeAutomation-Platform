import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrPerson, CrTeam, CrUser } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Administration > Teams. AA has no teams of its own (it maps directory groups to roles); this follows UiPath's groups
 * and Blue Prism's team-based access (checked against their docs, 31 Oct 2026): a team is named team:NAME in agent
 * folder permissions, vault folders and work queues, so adding someone gives them all of it. The list shows leads,
 * members and where each team is used (an access review); a team opens with its members, which administrators and
 * the team's leads change (only administrators appoint or remove leads, as GitHub's maintainers).
 */
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const leads = (t: CrTeam) => t.members.filter((m) => m.team_role === "lead").map((m) => m.username);

export function Teams({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [teams, setTeams] = useState<CrTeam[] | null>(null);
  const [people, setPeople] = useState<CrPerson[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [mine, setMine] = useState(false);
  const [dialog, setDialog] = useState<null | { kind: "team"; team: CrTeam | null }>(null);
  const admin = user.permissions.includes("teams.manage");

  const load = useCallback(async () => {
    try { setTeams(await api.teams()); setError(null); } catch (e) { setError(message(e)); }
    if (user.permissions.includes("users.manage")) api.users().then(setPeople, () => {});
  }, [api, user.permissions]);
  useEffect(() => { load(); }, [load]);

  const shown = useMemo(() => {
    const text = q.trim().toLowerCase();
    return (teams ?? []).filter((t) => (!text || (t.name + " " + (t.description ?? "") + " " + t.members.map((m) => m.username).join(" ")).toLowerCase().includes(text))
      && (!mine || t.members.some((m) => m.username === user.username)));
  }, [teams, q, mine, user.username]);
  const exportCsv = () => {
    const text = [["Team", "Description", "Leads", "Members", "Named in rules as", "Used in"],
      ...shown.map((t) => [t.name, t.description ?? "", leads(t).join(" "), t.members.map((m) => m.username).join(" "), "team:" + t.name, (t.used_in ?? []).join("; ")])]
      .map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "teams.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + shown.length + " team" + (shown.length === 1 ? "" : "s") + ".");
  };

  return (
    <section className="st-page cc-home" aria-labelledby="cc-teams-title">
      <header className="st-page-head">
        <div><h1 id="cc-teams-title">Teams</h1><p>Groups of people. Name a team as team:NAME in agent folder permissions, vault folders and work queues; adding someone to the team gives them all of it.</p></div>
        {admin && <Button kind="primary" onClick={() => setDialog({ kind: "team", team: null })}>Create team</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search teams" placeholder="Search teams or members" value={q} onChange={(e) => setQ(e.target.value)} />
        <label className="st-check-line"><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Only teams I am in</label>
      </div>
      <div className="cc-bar" role="toolbar" aria-label="Team actions">
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
        <Button kind="secondary" onClick={exportCsv}>Export CSV</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Teams list">
          <thead><tr><th scope="col">Team</th><th scope="col">Description</th><th scope="col">Leads</th><th scope="col">Members</th><th scope="col">Named in rules as</th><th scope="col">Used in</th></tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={() => setDialog({ kind: "team", team: t })}>
                <td>{t.name}</td><td>{t.description ?? ""}</td><td>{leads(t).join(", ") || "None"}</td><td>{t.members.length}</td>
                <td className="ui-mono">team:{t.name}</td><td>{(t.used_in ?? []).length ? (t.used_in ?? []).join(", ") : "Not used yet"}</td>
              </tr>
            ))}
            {teams && !shown.length && <tr><td colSpan={6} className="st-muted">{teams.length ? "No teams match." : admin ? "No teams yet." : "You are not in any team."}</td></tr>}
            {!teams && !error && <tr><td colSpan={6} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog && <TeamDialog key={dialog.team?.id ?? "new"} api={api} team={dialog.team} admin={admin} me={user.username} people={people}
        onClose={() => setDialog(null)} onSaved={(t, close) => { onNotice(t); if (close) setDialog(null); load(); }} />}
    </section>
  );
}

/**
 * A team: its name (fixed once made: rules name it), description and members with their place (member or lead).
 * Administrators change everything; a lead changes the members and stays a lead.
 */
function TeamDialog({ api, team, admin, me, people, onClose, onSaved }:
  { api: CrApi; team: CrTeam | null; admin: boolean; me: string; people: CrPerson[]; onClose: () => void; onSaved: (t: string, close: boolean) => void }) {
  const [name, setName] = useState(team?.name ?? "");
  const [description, setDescription] = useState(team?.description ?? "");
  const [members, setMembers] = useState<{ username: string; role: "member" | "lead" }[]>(team?.members.map((m) => ({ username: m.username, role: m.team_role })) ?? []);
  const [adding, setAdding] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const isLead = !!team?.members.some((m) => m.username === me && m.team_role === "lead");
  const canMembers = admin || isLead;
  const add = () => {
    const u = adding.trim();
    if (u && !members.some((m) => m.username === u)) setMembers([...members, { username: u, role: "member" }]);
    setAdding("");
  };
  const save = async () => {
    try {
      let id = team?.id ?? null;
      if (admin) id = (await api.saveTeam(id, name.trim(), description)).id ?? id;
      if (canMembers || !team) await api.setTeamMembers(id!, members);
      onSaved((team ? "Saved" : "Created") + " the team " + name.trim() + ".", true);
    } catch (e) { setError(message(e)); }
  };
  const others = people.filter((p) => !members.some((m) => m.username === p.username));
  return (
    <Dialog open title={team ? "Team " + team.name : "Create team"} width={640} onClose={onClose} footer={<>
      {team && admin && <Button kind="danger" onClick={() => setConfirmDelete(true)}>Delete</Button>}
      <span className="st-bar-fill" />
      <Button kind="plain" onClick={onClose}>{canMembers || !team ? "Cancel" : "Close"}</Button>
      {(canMembers || !team) && <Button kind="primary" disabled={!/^[A-Za-z0-9_.-]{1,80}$/.test(name.trim())} onClick={save}>{team ? "Save changes" : "Create team"}</Button>}</>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirmDelete && <div className="st-alert" role="alert"><span>Delete the team {team!.name}? Teams still named in rules are kept.</span>
        <Button kind="danger" onClick={() => api.deleteTeam(team!.id).then(() => onSaved("Deleted the team " + team!.name + ".", true), (e) => { setConfirmDelete(false); setError(message(e)); })}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirmDelete(false)}>Cancel</Button></div>}
      <div className="cc-grid2">
        <Field label="Name (letters, digits, . _ -; fixed once made)">{(id) => <TextInput id={id} value={name} disabled={!!team} maxLength={80} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description">{(id) => <TextInput id={id} value={description} disabled={!admin} maxLength={500} onChange={(e) => setDescription(e.target.value)} />}</Field>
      </div>
      {team && <p className="st-muted">Named in rules as <code>team:{team.name}</code>. {(team.used_in ?? []).length ? "Used in " + team.used_in!.join(", ") + "." : "Not named in any rule yet."}</p>}
      <h3 className="cc-log-title">Members</h3>
      <table className="st-table" aria-label="Members">
        <thead><tr><th scope="col">Person</th><th scope="col">Place</th>{canMembers && <th scope="col"><span className="st-sr">Remove</span></th>}</tr></thead>
        <tbody>
          {members.map((m, i) => (
            <tr key={m.username}>
              <td>{m.username}</td>
              <td>{admin ? <Select aria-label={"Place of " + m.username} value={m.role} onChange={(e) => setMembers(members.map((x, j) => j === i ? { ...x, role: e.target.value as "member" | "lead" } : x))}
                options={[{ value: "member", label: "Member" }, { value: "lead", label: "Lead (changes the members)" }]} /> : m.role === "lead" ? "Lead" : "Member"}</td>
              {canMembers && <td><Button kind="plain" onClick={() => setMembers(members.filter((_, j) => j !== i))}>Remove</Button></td>}
            </tr>
          ))}
          {!members.length && <tr><td colSpan={3} className="st-muted">No members yet.</td></tr>}
        </tbody>
      </table>
      {canMembers && <span className="cc-inline">
        {others.length ? <Select aria-label="Person to add" value={adding} onChange={(e) => setAdding(e.target.value)}
          options={[{ value: "", label: "Choose a person" }, ...others.map((p) => ({ value: p.username, label: (p.display_name || p.username) + " (" + p.username + ")" }))]} />
          : <TextInput aria-label="Person to add" placeholder="User name" value={adding} onChange={(e) => setAdding(e.target.value)} />}
        <Button kind="secondary" disabled={!adding.trim()} onClick={add}>Add</Button>
      </span>}
    </Dialog>
  );
}
