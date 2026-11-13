import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrPerson, CrTeam, CrUser } from "../api/cr";
import { Button, Field, HelpButton, Select, TextInput, type HelpTopic, reportMinor, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, CloseView } from "./ListParts";
import { PageIntro } from "../shell/PageIntro";

/**
 * Administration > Teams. AA has no teams of its own (it maps directory groups to roles); this follows UiPath's groups
 * and Blue Prism's team-based access (checked against their docs, 31 Oct 2026): a team is named team:NAME in agent
 * folder permissions, vault folders and work queues, so adding someone gives them all of it. The list shows leads,
 * members and where each team is used (an access review); a team opens with its members, which administrators and
 * the team's leads change (only administrators appoint or remove leads, as GitHub's maintainers).
 * Laid out as the design plan's list pattern (applied 31 Oct 2026): a count, one toolbar, a ⋯ menu per row.
 * Review round 2 (1 Nov 2026): a team opens as its own page (Members, each opening in Users; Details), deleting asks
 * first in a bar, and each team stays on one line (description and where it is used in full on hover).
 */
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const leads = (t: CrTeam) => t.members.filter((m) => m.team_role === "lead").map((m) => m.username);

const HELP: HelpTopic = { title: "Teams",
  about: "Groups of people. Name a team as team:NAME in agent folder permissions, vault folders and work queues; adding someone to the team then gives them "
    + "all of it.",
  items: [
    { name: "Create team", text: "Opens a new team's page: give it a name and description, then add its members. Needs permission to manage teams." },
    { name: "Only teams I am in", text: "Shows only the teams you are a member or lead of." },
    { name: "Leads", text: "People who may change the team's members. Only administrators make someone a lead or remove a lead." },
    { name: "Named in rules as", text: "The exact words to type where you give a team access, for example in folder permissions or a work queue's members." },
    { name: "Used in", text: "Where the team is named today, such as folders, vault folders and work queues. Use it to review who can reach what." },
    { name: "Open", text: "Opens the team's page with its members and details. Clicking the row does the same." },
    { name: "Delete…", text: "Deletes the team after you confirm. A team still named in rules is kept; remove it from those rules first." },
    { name: "Export CSV", text: "Saves the teams shown, with their leads, members and where they are used, as a CSV file." },
  ] };

const HELP_TEAM: HelpTopic = { title: "Team",
  about: "One team: its members and its details. Administrators change everything; a lead changes the members. Nothing is kept until you save.",
  items: [
    { name: "Members", text: "Everyone in the team. Choose a person and press Add; Remove from the team in a person's ⋯ menu takes them out." },
    { name: "Place", text: "Member or Lead. A lead may change the members; only administrators can set this." },
    { name: "Open in Users", text: "Opens the person in Users, when you may manage users." },
    { name: "Details", text: "The team's name and description, the words to use in rules, and where the team is used. The name cannot change once the team "
      + "is made, since rules use it." },
    { name: "Save changes", text: "Keeps the members and details you changed." },
    { name: "Delete…", text: "In the ⋯ menu: deletes the team after you confirm. Only for administrators." },
  ] };

export function Teams({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [teams, setTeams] = useState<CrTeam[] | null>(null);
  const [people, setPeople] = useState<CrPerson[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [mine, setMine] = useState(false);
  const [page, setPage] = useState<null | { team: CrTeam | null }>(null);   // a team's own page, in place of the list
  const [asking, setAsking] = useState<CrTeam | null>(null);   // a team to delete, asked first
  const admin = user.permissions.includes("teams.manage");

  const load = useCallback(async () => {
    try { setTeams(await api.teams()); setError(null); } catch (e) { setError(message(e)); }
    if (user.permissions.includes("users.manage")) api.users().then(setPeople, (e) => reportMinor("the list of users", e));
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

  const inTeams = new Set((teams ?? []).flatMap((t) => t.members.map((m) => m.username))).size;
  const remove = (t: CrTeam) => api.deleteTeam(t.id).then(() => { onNotice("Deleted the team " + t.name + "."); setAsking(null); setPage(null); load(); }, (e) => { setAsking(null); setError(message(e)); });
  const askBar = asking && <div className="cc-list-bulk" role="alert"><span>Delete the team {asking.name}? Teams still named in rules are kept.</span>
    <Button kind="danger" onClick={() => remove(asking)}>Delete</Button><Button kind="plain" onClick={() => setAsking(null)}>Cancel</Button></div>;

  if (page) return (
    <section className="st-page cc-home cc-explorer" aria-label="Team">
      {error && <PageError text={error} />}
      {askBar}
      <TeamPage key={page.team?.id ?? "new"} api={api} team={page.team} admin={admin} me={user.username} people={people}
        onClose={() => { setPage(null); setAsking(null); }} onDelete={setAsking} onSaved={(t) => { onNotice(t); setPage(null); load(); }} />
    </section>
  );

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-teams-title">
      <header className="cc-list-head">
        <h1 id="cc-teams-title">Teams</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:teams" />
        {teams && <span className="cc-list-count">{teams.length} team{teams.length === 1 ? "" : "s"} · {inTeams} {inTeams === 1 ? "person" : "people"} in them</span>}
        <span className="ov-fill" />
        {admin && <Button kind="primary" onClick={() => setPage({ team: null })}>Create team</Button>}
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search teams" placeholder="Search teams or members" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          <label className={"cc-chip-check" + (mine ? " is-on" : "")}><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Only teams I am in</label>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Teams list tools">
          <Button kind="plain" onClick={exportCsv}>Export CSV</Button>
          <RefreshButton onClick={load} />
        </div>
      </div>
      <div className="cc-list-panel">
        {askBar}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Teams list">
            <thead><tr><th scope="col">Team</th><th scope="col">Description</th><th scope="col">Leads</th><th scope="col">Members</th><th scope="col">Named in rules as</th><th scope="col">Used in</th>
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((t) => (
                <tr key={t.id} onClick={rowClick(() => setPage({ team: t }))}>
                  <td><strong>{t.name}</strong></td><td className="cc-clip" title={t.description || undefined}>{t.description || <span className="cc-none">–</span>}</td>
                  <td>{leads(t).length ? leads(t).map((l) => <span key={l} className="cc-role">{l}</span>) : <span className="cc-none">None</span>}</td>
                  <td>{t.members.length}</td>
                  <td><code className="cc-code">team:{t.name}</code></td>
                  <td className="cc-clip" title={(t.used_in ?? []).join(", ") || undefined}>{(t.used_in ?? []).length ? (t.used_in ?? []).join(", ") : <span className="cc-none">Not used yet</span>}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={[
                    { key: "open", label: "Open", onSelect: () => setPage({ team: t }) },
                    { key: "delete", label: "Delete…", disabled: !admin, separatorBefore: true, onSelect: () => setAsking(t) }]} /></td>
                </tr>
              ))}
              {teams && !shown.length && <tr className="is-static"><td colSpan={7} className="cc-none">{teams.length ? "No teams match." : admin ? "No teams yet." : "You are not in any team."}</td></tr>}
              {!teams && !error && <tr className="is-static"><td colSpan={7} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {teams && <div className="cc-list-pager"><span>{shown.length} of {teams.length}{q || mine ? " matching" : ""}</span></div>}
      </div>
      {teams && !teams.length && admin && <p className="cc-list-hint">Make a team for each group that shares agents, vault folders or work queues, then name it as team:NAME in their permissions.</p>}
    </section>
  );
}

/**
 * A team's own page: its members with their place (member or lead), then its details. Its name is fixed once made, since
 * rules name it. Administrators change everything; a lead changes the members and stays a lead.
 */
function TeamPage({ api, team, admin, me, people, onClose, onDelete, onSaved }:
  { api: CrApi; team: CrTeam | null; admin: boolean; me: string; people: CrPerson[]; onClose: () => void; onDelete: (t: CrTeam) => void; onSaved: (t: string) => void }) {
  const [tab, setTab] = useState<"members" | "details">(team ? "members" : "details");
  const [name, setName] = useState(team?.name ?? "");
  const [description, setDescription] = useState(team?.description ?? "");
  const [members, setMembers] = useState<{ username: string; role: "member" | "lead" }[]>(team?.members.map((m) => ({ username: m.username, role: m.team_role })) ?? []);
  const [adding, setAdding] = useState("");
  const [error, setError] = useState<string | null>(null);
  const isLead = !!team?.members.some((m) => m.username === me && m.team_role === "lead");
  const canMembers = admin || isLead || !team;
  const canSave = admin || isLead;
  const add = () => {
    const u = adding.trim();
    if (u && !members.some((m) => m.username === u)) setMembers([...members, { username: u, role: "member" }]);
    setAdding("");
  };
  const save = async () => {
    try {
      let id = team?.id ?? null;
      if (admin) id = (await api.saveTeam(id, name.trim(), description)).id ?? id;
      if (canMembers) await api.setTeamMembers(id!, members);
      onSaved((team ? "Saved" : "Created") + " the team " + name.trim() + ".");
    } catch (e) { setError(message(e)); }
  };
  const others = people.filter((p) => !members.some((m) => m.username === p.username));
  const used = team?.used_in ?? [];
  const leadCount = members.filter((m) => m.role === "lead").length;
  const close = <CloseView onClick={onClose} to="the teams" />;
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={team ? "Team " + team.name : "Create team"}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Teams</button> ›</span>
        <h1>{team ? team.name : "Create team"}</h1>
        <HelpButton topic={HELP_TEAM} />
        {team && <code className="cc-code">team:{team.name}</code>}
        <span className="cc-none">{members.length} member{members.length === 1 ? "" : "s"} · {leadCount} lead{leadCount === 1 ? "" : "s"}{team ? " · " + (used.length ? "used in " + used.length + (used.length === 1 ? " place" : " places") : "not used yet") : ""}</span>
        <span className="ov-fill" />
        {canSave && <Button kind="primary" disabled={!/^[A-Za-z0-9_.-]{1,80}$/.test(name.trim())} onClick={save}>{team ? "Save changes" : "Create team"}</Button>}
        {team && admin && <RowMenu label={"More for " + team.name} items={[{ key: "delete", label: "Delete…", onSelect: () => onDelete(team) }]} />}
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this team">
        {([["members", "Members (" + members.length + ")"], ["details", "Details"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "members" && <>
        {canMembers && <div className="cc-list-tools cc-pane-tools"><div className="cc-list-filters" role="group" aria-label="Add a person">
          {others.length ? <Select aria-label="Person to add" value={adding} onChange={(e) => setAdding(e.target.value)}
            options={[{ value: "", label: "Choose a person" }, ...others.map((p) => ({ value: p.username, label: (p.display_name || p.username) + " (" + p.username + ")" }))]} />
            : <TextInput aria-label="Person to add" placeholder="User name" value={adding} onChange={(e) => setAdding(e.target.value)} />}
          <Button kind="secondary" disabled={!adding.trim()} onClick={add}>Add</Button>
          <span className="cc-none">Changes are kept when you choose {team ? "Save changes" : "Create team"}.</span>
        </div></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Members">
            <thead><tr><th scope="col">Person</th><th scope="col">Place</th>{canMembers && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {members.map((m, i) => {
                const open = people.length ? () => { location.hash = "#/users?user=" + encodeURIComponent(m.username); } : null;
                return <tr key={m.username} className={open ? undefined : "is-static"} title={open ? "Open this person in Users" : undefined} onClick={rowClick(() => open?.())}>
                  <td><span className="cc-who"><span className="cc-who-av" aria-hidden="true">{m.username.slice(0, 2).toUpperCase()}</span>{m.username}</span></td>
                  <td>{admin ? <Select aria-label={"Place of " + m.username} value={m.role} onChange={(e) => setMembers(members.map((x, j) => j === i ? { ...x, role: e.target.value as "member" | "lead" } : x))}
                    options={[{ value: "member", label: "Member" }, { value: "lead", label: "Lead (changes the members)" }]} /> : m.role === "lead" ? "Lead" : "Member"}</td>
                  {canMembers && <td className="cc-list-menu"><RowMenu label={"Actions for " + m.username} items={[
                    ...(open ? [{ key: "open", label: "Open in Users", onSelect: open }] : []),
                    { key: "remove", label: "Remove from the team", separatorBefore: !!open, onSelect: () => setMembers(members.filter((_, j) => j !== i)) }]} /></td>}
                </tr>;
              })}
              {!members.length && <tr className="is-static"><td colSpan={3} className="cc-none">No members yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </>}
      {tab === "details" && <div className="cc-agent-tab">
        <div className="cc-grid2">
          <Field label="Name (letters, digits, . _ -; fixed once made)">{(id) => <TextInput id={id} value={name} disabled={!!team} maxLength={80} onChange={(e) => setName(e.target.value)} />}</Field>
          <Field label="Description">{(id) => <TextInput id={id} value={description} disabled={!admin} maxLength={500} onChange={(e) => setDescription(e.target.value)} />}</Field>
        </div>
        {team && <dl className="cc-facts cc-facts-wide">
          <dt>Named in rules as</dt><dd><code>team:{team.name}</code></dd>
          <dt>Used in</dt><dd>{used.length ? used.join(", ") : "Not named in any rule yet"}</dd>
        </dl>}
      </div>}
    </div>
  );
}
