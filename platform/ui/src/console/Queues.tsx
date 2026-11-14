import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, ItemModel, RunnerPool, WorkItem, WorkQueue } from "../api/cr";
import { Button, Dialog, Field, HelpButton, PrincipalPicker, principalDirectory, principalLabel, Select, TextArea, TextInput, type HelpTopic, reportMinor, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, when, CloseView } from "./ListParts";
import { PageIntro } from "../shell/PageIntro";

/**
 * Manage > Work queues, built as AA's Workload queues (checked against AA's docs, UiPath's queues and Blue Prism's work
 * queues, 31 Oct 2026): queues with their agent, pool, items waiting, in progress and done, service level and items past
 * it (UiPath's SLA), processing on or off. A queue opens with its items by status, retry, defer or delete selected,
 * add items (JSON, CSV or Excel), validate items an agent was unsure of, each item's data, result and error; members;
 * export and import of a queue's definition. Work item models are AA's work item templates. Refreshes every 10 seconds.
 * Review round 2 (31 Oct 2026), as AA's View queue: one line per queue (its description on hover); a queue opens as its own
 * page with Work items (status chips, one line per item, its data in a column of its own), Details and Members.
 * G31 (4 Nov 2026), as UiPath's and Blue Prism's queues: tags on items (filter by tag), the kind of an item's exception
 * (business or system), unlock, copy and move to another queue, delete finished items, and encrypted item data.
 */
const STATUS: [string, string][] = [["new", "New"], ["in_progress", "In progress"], ["review", "To validate"], ["retry", "Waiting to retry"], ["success", "Succeeded"], ["failed", "Failed"], ["deferred", "Deferred"]];
const statusName = (s: string) => STATUS.find(([k]) => k === s)?.[1] ?? s;
const agentName = (p?: string | null) => p ? p.split("/").pop()!.replace(/\.flow\.json$/, "") : "Not chosen";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const MEMBER_ROLES: Record<string, string> = { owner: "Owner (manages the queue)", participant: "Participant (adds and changes items)", consumer: "Consumer (processes the items)" };
const KIND: Record<string, string> = { business: "Business", system: "System" };
const waiting = (q: WorkQueue) => (q.counts.new ?? 0) + (q.counts.retry ?? 0);
const parsed = (v: unknown) => { if (typeof v !== "string") return v; try { return JSON.parse(v); } catch { return v; } };
const short = (v: unknown) => { const p = parsed(v); return p === null || p === undefined ? "" : typeof p === "string" ? p : JSON.stringify(p); };
function save(name: string, text: string, type: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type }));
  a.download = name; document.body.appendChild(a); a.click(); a.remove();
}

const HELP: HelpTopic = { title: "Work queues",
  about: "Lists of work items, such as invoices, tickets or orders, that an agent works one by one on a runner pool, with retries and a service level. "
    + "The list refreshes every 10 seconds; click a queue to open its own page.",
  items: [
    { name: "Create work queue", text: "Makes a queue: give it a name, an optional work item model, the agent that works the items, the runner pool, "
      + "a service level in minutes and how many attempts each item gets. Needs permission to manage work queues." },
    { name: "Work item models", text: "Named fields with types that a queue's items must have. Items added by hand, from CSV or Excel, by agents or over "
      + "the API are checked against the model." },
    { name: "Import queue", text: "Makes a queue from a definition file exported from another queue, with its settings, model, members and waiting items. "
      + "The agent and runner pool are linked when they exist here." },
    { name: "Status", text: "On means waiting items are handed to free runners in the queue's pool. Off keeps them waiting; start or stop it from the "
      + "row's ⋯ menu." },
    { name: "Waiting", text: "New items plus items waiting to retry. A tag shows items an agent was unsure of, which a person must validate." },
    { name: "Service level", text: "The minutes an item may take from being added to being done. Items past it are counted in red." },
    { name: "Open its items", text: "Opens the queue's own page with its work items, details and members. Clicking the row does the same." },
    { name: "Start processing or Stop processing", text: "Switches processing on or off for the queue. Needs permission to work queues." },
    { name: "Change settings…", text: "Changes the queue's name, model, agent, runner pool, service level, attempts and whether item data is kept "
      + "encrypted. Needs permission to manage work queues." },
  ] };

const HELP_QUEUE: HelpTopic = { title: "Work queue",
  about: "One queue with its work items, details and members. The agent gets each item in an input named work_item; a failed run retries the item until its "
    + "attempts are used.",
  items: [
    { name: "Start processing or Stop processing", text: "Hands waiting items to the runners of the queue's pool, or keeps them waiting. Needs permission "
      + "to work queues." },
    { name: "Add items", text: "Paste a JSON list or CSV with a header row, or choose a CSV, JSON or Excel file. A reference column names each item; "
      + "priority puts it first." },
    { name: "Work items", text: "Every item with its data, status, attempts and result or error. Pick a status chip to see only those items, or search the "
      + "reference or data." },
    { name: "Retry, Defer and Delete", text: "Tick items to act on several at once. Retry tries them again, Defer holds them until a time you choose or "
      + "until Retry, and Delete removes them; items being worked on are kept." },
    { name: "Unlock", text: "Puts items stuck in progress back to new, keeping their attempts. Items whose run is still going stay locked: stop the run "
      + "first." },
    { name: "Tags…", text: "Adds or removes labels on the ticked items. Type a tag in Filter by tag to see only items with it (several, comma-separated: "
      + "items with all of them). Agents can take only items with given tags." },
    { name: "Copy to… and Move to…", text: "Puts the ticked items in another queue as new items with the same data, reference, priority and tags. Items "
      + "being worked on, and items whose reference the other queue has, stay." },
    { name: "Delete finished items…", text: "Deletes succeeded or failed items, optionally only those older than some hours. Needs permission to work "
      + "queues." },
    { name: "Business or System", text: "The kind of an item's last exception. A business exception (the item itself is wrong) fails it at once; a system "
      + "exception (something around it failed) is retried until its attempts are used." },
    { name: "Validate", text: "For items an agent was not sure of: check the marked fields, correct them and approve, or reject with a reason. Approved "
      + "items go back to the agent." },
    { name: "Export CSV", text: "Saves the items, or only those of the chosen status, as a CSV file." },
    { name: "Details", text: "The queue's agent, runner pool, attempts, service level, processing, item model and whether item data is encrypted. Edit "
      + "settings changes them." },
    { name: "Members", text: "Who may use the queue: owners manage it, participants add and change items, consumers process them. With no members, everyone "
      + "whose role allows it can use it." },
    { name: "Export definition", text: "Saves the queue's settings, model, members and waiting items as a file that Import queue reads, here or on another "
      + "One CommandCenter." },
    { name: "Delete queue…", text: "Deletes the queue and all its items, after you confirm. Needs permission to manage work queues." },
  ] };

export function Queues({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<WorkQueue[] | null>(null);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "edit"; queue: WorkQueue | null } | { kind: "models" } | { kind: "import" }>(null);
  // a queue's own page, in place of the list; #/queues?queue=<id> (Work queue insights) opens it
  const [openId, setOpenId] = useState<string | null>(() => new URLSearchParams(location.hash.split("?")[1] ?? "").get("queue"));
  const [version, setVersion] = useState(0);   // read the open queue again after its settings change
  const can = (p: string) => user.permissions.includes(p);

  const load = useCallback(async () => {
    try { const [l, p] = await Promise.all([api.queues(), can("devices.view") ? api.pools().catch(() => []) : Promise.resolve([])]); setList(l); setPools(p); setError(null); }
    catch (e) { setError(message(e)); }
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);
  useEffect(() => { if (location.hash.includes("?")) history.replaceState(null, "", "#/queues"); }, []);
  useEffect(() => { const t = setInterval(() => { if (!dialog && !openId) load(); }, 10_000); return () => clearInterval(t); }, [load, dialog, openId]);
  const poolName = (id?: string | null) => id ? pools.find((p) => p.id === id)?.name ?? id : "Not chosen";
  const shown = useMemo(() => (list ?? []).filter((x) => !q.trim() || (x.name + " " + (x.description ?? "") + " " + (x.bot_path ?? "")).toLowerCase().includes(q.trim().toLowerCase())), [list, q]);

  const totals = (list ?? []).reduce((t, x) => ({ waiting: t.waiting + waiting(x), failed: t.failed + (x.counts.failed ?? 0), overdue: t.overdue + (x.overdue ?? 0) }), { waiting: 0, failed: 0, overdue: 0 });
  const setOn = (x: WorkQueue) => api.setProcessing(x.id, !x.processing).then(() => { onNotice((x.processing ? "Stopped " : "Started ") + x.name + "."); load(); }, (e) => setError(message(e)));
  const rowMenu = (x: WorkQueue) => [
    { key: "open", label: "Open its items", onSelect: () => setOpenId(x.id) },
    ...(can("queues.work") ? [{ key: "on", label: x.processing ? "Stop processing" : "Start processing", onSelect: () => { setOn(x); } }] : []),
    ...(can("queues.manage") ? [{ key: "edit", label: "Change settings…", onSelect: () => setDialog({ kind: "edit", queue: x }) }] : []),
  ];

  const form = dialog?.kind === "edit" && <QueueForm api={api} queue={dialog.queue} pools={pools} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); setVersion((v) => v + 1); load(); }} />;
  if (openId) return (
    <section className="st-page cc-home cc-explorer" aria-label="Work queue">
      <QueueView key={openId} api={api} id={openId} version={version} can={can} poolName={poolName} onClose={() => { setOpenId(null); load(); }} onNotice={onNotice}
        onEdit={(x) => setDialog({ kind: "edit", queue: x })} />
      {form}
    </section>
  );

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-queues-title">
      <header className="cc-list-head">
        <h1 id="cc-queues-title">Work queues</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:queues" />
        {list && <span className="cc-list-count">{list.length} queue{list.length === 1 ? "" : "s"} · {totals.waiting} item{totals.waiting === 1 ? "" : "s"} waiting{totals.failed ? " · " + totals.failed + " failed" : ""}{totals.overdue ? " · " + totals.overdue + " past the service level" : ""}</span>}
        <span className="ov-fill" />
        <Button kind="secondary" onClick={() => setDialog({ kind: "models" })}>Work item models</Button>
        {can("queues.manage") && <Button kind="secondary" onClick={() => setDialog({ kind: "import" })}>Import queue</Button>}
        {can("queues.manage") && <Button kind="primary" onClick={() => setDialog({ kind: "edit", queue: null })}>Create work queue</Button>}
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search work queues" placeholder="Search work queues" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Work queues list tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Work queues list">
            <thead><tr><th scope="col">Status</th><th scope="col">Queue</th><th scope="col">Agent</th><th scope="col">Runner pool</th><th scope="col">Waiting</th>
              <th scope="col">In progress</th><th scope="col">Succeeded</th><th scope="col">Failed</th><th scope="col">Service level</th>
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((x) => (
                <tr key={x.id} onClick={rowClick(() => setOpenId(x.id))}>
                  <td><span className={"cc-pill is-" + (x.processing ? "active" : "disabled")}><span className="cc-pill-dot" />{x.processing ? "On" : "Off"}</span></td>
                  <td title={x.description ?? undefined}><strong>{x.name}</strong></td>
                  <td>{x.bot_path && <span className="cc-aico" aria-hidden="true">A</span>}{agentName(x.bot_path)}</td><td>{poolName(x.pool_id)}</td>
                  <td>{waiting(x)}{(x.counts.review ?? 0) > 0 && <span className="cc-tag">{x.counts.review} to validate</span>}</td>
                  <td>{x.counts.in_progress ?? 0}</td><td>{x.counts.success ?? 0}</td><td className={(x.counts.failed ?? 0) > 0 ? "cc-err" : undefined}>{x.counts.failed ?? 0}</td>
                  <td>{x.sla_minutes ? x.sla_minutes + " min" : <span className="cc-none">None</span>}{x.overdue ? <span className="cc-risk">{x.overdue} past it</span> : null}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + x.name} items={rowMenu(x)} /></td>
                </tr>
              ))}
              {list && !shown.length && <tr className="is-static"><td colSpan={10} className="cc-none">{list.length ? "No queues match." : "No work queues yet. Create one, then add items by hand, from a CSV or Excel file, or from an agent."}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={10} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}{q.trim() ? " matching" : ""} · refreshes every 10 seconds</span></div>}
      </div>
      {form}
      {dialog?.kind === "models" && <Models api={api} manage={can("queues.manage")} onClose={() => setDialog(null)} onNotice={onNotice} />}
      {dialog?.kind === "import" && <ImportQueue api={api} onClose={() => setDialog(null)} onDone={(t) => { onNotice(t); setDialog(null); load(); }} />}
    </section>
  );
}

/** Create or change a queue: its name, item model, agent, pool, service level and attempts. */
function QueueForm({ api, queue: x, pools, onClose, onSaved }: { api: CrApi; queue: WorkQueue | null; pools: RunnerPool[]; onClose: () => void; onSaved: (t: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [models, setModels] = useState<ItemModel[]>([]);
  const [v, setV] = useState({ name: x?.name ?? "", description: x?.description ?? "", model: x?.model_id ?? "", bot: x?.bot_path ?? "", pool: x?.pool_id ?? "",
    sla: x?.sla_minutes ? String(x.sla_minutes) : "", attempts: String(x?.max_attempts ?? 2) });
  const [encrypt, setEncrypt] = useState(!!x?.encrypt_data);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveAgents().then((b) => setAgents(b.map((a) => a.path)), (e) => reportMinor("the list of agents", e)); api.itemModels().then(setModels, (e) => reportMinor("the item models", e)); }, [api]);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const submit = () => api.saveQueue(x?.id ?? null, { name: v.name.trim(), description: v.description, bot_path: v.bot || null, pool_id: v.pool || null,
    sla_minutes: v.sla ? Number(v.sla) : null, max_attempts: Number(v.attempts), model_id: v.model || null, encrypt_data: encrypt }).then(() => onSaved((x ? "Saved " : "Created ") + v.name.trim() + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={x ? "Work queue " + x.name : "Create work queue"} width={620} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim()} onClick={submit}>{x ? "Save changes" : "Create work queue"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} placeholder="Vendor invoices" onChange={set("name")} />}</Field>
        <Field label="Description">{(id) => <TextInput id={id} value={v.description} onChange={set("description")} />}</Field>
      </div>
      <Field label="Work item model (the fields every item has; items are checked against it)">{(id) => <Select id={id} value={v.model} onChange={set("model")}
        options={[{ value: "", label: "None: items are free-form" }, ...models.map((m) => ({ value: m.id, label: m.name + " (" + m.fields.length + " fields)" }))]} />}</Field>
      <div className="cc-grid2">
        <Field label="Agent that works the items">{(id) => <Select id={id} value={v.bot} onChange={set("bot")} options={[{ value: "", label: "Choose later" }, ...agents.map((a) => ({ value: a, label: agentName(a) }))]} />}</Field>
        <Field label="Runner pool">{(id) => <Select id={id} value={v.pool} onChange={set("pool")} options={[{ value: "", label: "Choose later" }, ...pools.map((p) => ({ value: p.id, label: p.name }))]} />}</Field>
        <Field label="Service level (minutes from added to done)">{(id) => <TextInput id={id} type="number" min={0} value={v.sla} placeholder="240" onChange={set("sla")} />}</Field>
        <Field label="Attempts per item">{(id) => <TextInput id={id} type="number" min={1} max={10} value={v.attempts} onChange={set("attempts")} />}</Field>
      </div>
      <label className="cc-switch"><input type="checkbox" role="switch" aria-label="Encrypt item data" checked={encrypt} onChange={(e) => setEncrypt(e.target.checked)} />
        <span className="cc-switch-track" aria-hidden="true" />Encrypt item data</label>
      <p className="cc-list-hint">Encrypted items are kept in the database with the credential vault's key and shown only to people and agents allowed to see the queue; searching their data no longer finds them.</p>
      <p className="cc-list-hint">The agent receives each item in an input named work_item and its id in work_item_id. A failed run retries the item until its attempts are used.</p>
    </Dialog>
  );
}

/** A queue's own page, as AA's View queue: Work items (status chips, one line per item), Details and Members; its actions on top. */
function QueueView({ api, id, version, can, poolName, onClose, onNotice, onEdit }: { api: CrApi; id: string; version: number; can: (p: string) => boolean; poolName: (id?: string | null) => string;
  onClose: () => void; onNotice: (t: string) => void; onEdit: (q: WorkQueue) => void }) {
  type Full = Awaited<ReturnType<CrApi["queue"]>>;
  const [x, setX] = useState<Full | null>(null);
  const [items, setItems] = useState<{ items: WorkItem[]; total: number } | null>(null);
  const [tab, setTab] = useState<"items" | "details" | "members">("items");
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [tag, setTag] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [sub, setSub] = useState<null | "add" | "members" | "defer" | "delete" | "tags" | "purge" | { move: boolean } | { item: string } | { validate: WorkItem }>(null);
  const load = useCallback(async () => {
    try { const [q, it] = await Promise.all([api.queue(id), api.queueItems(id, status, search.trim(), 200, tag.trim())]); setX(q); setItems(it); setError(null); setSelected((s) => s.filter((k) => it.items.some((i) => i.id === k))); }
    catch (e) { setError(message(e)); }
  }, [api, id, status, search, tag, version]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { const t = setTimeout(load, 200); return () => clearTimeout(t); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!sub) load(); }, 10_000); return () => clearInterval(t); }, [load, sub]);
  const act = (fn: () => Promise<unknown>, done: string) => fn().then(() => { if (done) onNotice(done); setSelected([]); setSub(null); load(); }, (e) => setError(message(e)));
  const close = <CloseView onClick={onClose} to="the work queues" />;
  if (!x) return <div className="cc-agent" role="dialog" aria-modal="false" aria-label="Work queue"><div className="cc-agent-head"><h1>Work queue</h1><span className="ov-fill" />{close}</div>
    {error ? <p className="st-alert" role="alert">{error}</p> : <p className="cc-none cc-agent-pad" role="status">Loading…</p>}</div>;
  const work = can("queues.work"), manage = can("queues.manage");
  const total = Object.values(x.counts).reduce((n, v) => n + (v ?? 0), 0);
  const open = (it: WorkItem) => setSub(it.status === "review" && work ? { validate: it } : { item: it.id });
  const more = [...(manage ? [{ key: "edit", label: "Edit settings", onSelect: () => onEdit(x) }] : []),
    ...(work ? [{ key: "purge", label: "Delete finished items…", onSelect: () => setSub("purge") }] : []),
    { key: "export", label: "Export definition", onSelect: () => { api.exportQueue(x.id, true).then((d) => save(x.name.replace(/[^A-Za-z0-9_.-]+/g, "_") + ".ooaqueue.json", JSON.stringify(d, null, 2), "application/json"), (e) => setError(message(e))); } },
    ...(manage ? [{ key: "delete", label: "Delete queue…", separatorBefore: true, onSelect: () => setConfirm(true) }] : [])];
  const itemMenu = (it: WorkItem) => [{ key: "open", label: it.status === "review" && work ? "Validate" : "Open", onSelect: () => open(it) },
    ...(work && ["failed", "deferred"].includes(it.status) ? [{ key: "retry", label: "Retry", onSelect: () => { act(() => api.itemsAction(x.id, "retry", [it.id]), "Retrying " + (it.reference || it.id) + "."); } }] : []),
    ...(work && it.status === "in_progress" ? [{ key: "unlock", label: "Unlock", onSelect: () => { unlock([it.id]); } }] : [])];
  const unlock = (ids: string[]) => act(() => api.unlockItems(x.id, ids).then((r) => { if (r.unlocked < ids.length) throw new Error("Unlocked " + r.unlocked + " of " + ids.length
    + " item(s): the others are not in progress, or their run is still going (stop it first)."); }), "Unlocked " + ids.length + " item(s).");
  const statusPill = (it: WorkItem) => {
    const tone: Record<string, string> = { new: "stopped", in_progress: "pending", review: "pending", retry: "pending", success: "success", failed: "failed", deferred: "stopped" };
    return <><span className={"cc-pill is-" + (tone[it.status] ?? "pending")}>{it.status === "success" || it.status === "failed" ? <span className="cc-pill-dot" /> : null}{statusName(it.status)}</span>
      {it.status === "deferred" && <span className="cc-none"> {it.defer_until ? "until " + when(it.defer_until) : "until Retry"}</span>}</>;
  };
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Work queue " + x.name}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Work queues</button> ›</span>
        <h1>{x.name}</h1>
        <HelpButton topic={HELP_QUEUE} />
        <span className={"cc-pill is-" + (x.processing ? "active" : "disabled")}><span className="cc-pill-dot" />Processing {x.processing ? "on" : "off"}</span>
        <span className="cc-none">{agentName(x.bot_path)} · pool {poolName(x.pool_id)} · {x.sla_minutes ? "service level " + x.sla_minutes + " min" : "no service level"}
          {x.overdue ? <> · <span className="cc-risk">{x.overdue} past it</span></> : null}</span>
        <span className="ov-fill" />
        {work && <Button kind="secondary" onClick={() => act(() => api.setProcessing(x.id, !x.processing), (x.processing ? "Stopped " : "Started ") + x.name + ".")}>{x.processing ? "Stop processing" : "Start processing"}</Button>}
        {work && <Button kind="primary" onClick={() => setSub("add")}>Add items</Button>}
        <RowMenu label={"More for " + x.name} items={more} />
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert cc-inline" role="alert"><span>Delete the queue {x.name} and all its items?</span>
        <Button kind="danger" onClick={() => act(() => api.deleteQueue(x.id), "Deleted " + x.name + ".").then(onClose)}>Delete</Button><Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this queue">
        {([["items", "Work items (" + total + ")"], ["details", "Details"], ["members", "Members (" + x.members.length + ")"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "items" && <>
        <div className="cc-inline cc-counts cc-queue-chips" role="group" aria-label="Items by status">
          <button type="button" className="st-page-tab" aria-pressed={status === ""} onClick={() => setStatus("")}>All<span className="cc-count">{total}</span></button>
          {STATUS.filter(([k]) => k !== "review" || x.counts.review).map(([k, l]) => <button key={k} type="button" className="st-page-tab" aria-pressed={status === k} onClick={() => setStatus(status === k ? "" : k)}>
            {l}<span className="cc-count">{x.counts[k] ?? 0}</span></button>)}
        </div>
        <div className="cc-list-tools cc-pane-tools">
          <div className="cc-list-filters" role="group" aria-label="Filters">
            <span className="cc-list-search"><TextInput type="search" aria-label="Search items" placeholder="Search reference or data" value={search} onChange={(e) => setSearch(e.target.value)} /></span>
            <span className="cc-list-search"><TextInput type="search" aria-label="Filter by tag" placeholder="Filter by tag" value={tag} onChange={(e) => setTag(e.target.value)} /></span>
          </div>
          <div className="cc-list-right" role="toolbar" aria-label="Work items tools">
            <Button kind="plain" onClick={() => api.itemsCsv(x.id, status).then((t) => save(x.name.replace(/[^A-Za-z0-9_.-]+/g, "_") + "-items.csv", t, "text/csv"), (e) => setError(message(e)))}>Export CSV</Button>
            <RefreshButton onClick={load} />
          </div>
        </div>
        {selected.length > 0 && <div className="cc-list-bulk" role="toolbar" aria-label="Item actions">
          <strong>{selected.length} selected</strong>
          {work && <>
            <Button kind="secondary" onClick={() => act(() => api.itemsAction(x.id, "retry", selected), "Retrying " + selected.length + " item(s).")}>Retry</Button>
            <Button kind="secondary" onClick={() => setSub("defer")}>Defer</Button>
            <Button kind="secondary" onClick={() => unlock(selected)}>Unlock</Button>
            <Button kind="secondary" onClick={() => setSub("tags")}>Tags…</Button>
            <Button kind="secondary" onClick={() => setSub({ move: false })}>Copy to…</Button>
            <Button kind="secondary" onClick={() => setSub({ move: true })}>Move to…</Button>
            <Button kind="danger" onClick={() => setSub("delete")}>Delete</Button>
          </>}
          <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
        </div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Work items">
            <thead><tr><th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all items" checked={!!items?.items.length && items.items.every((i) => selected.includes(i.id))}
              onChange={(e) => setSelected(e.target.checked ? (items?.items ?? []).map((i) => i.id) : [])} /></th>
              <th scope="col">Reference</th><th scope="col">Data</th><th scope="col">Status</th><th scope="col" className="cc-num">Attempts</th><th scope="col" className="cc-num">Priority</th>
              <th scope="col">Added</th><th scope="col">Result or error</th><th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {(items?.items ?? []).map((it) => (
                <tr key={it.id} className={selected.includes(it.id) ? "is-selected" : ""} onClick={rowClick(() => open(it))}>
                  <td className="cc-check"><input type="checkbox" aria-label={"Select " + (it.reference || it.id)} checked={selected.includes(it.id)} onChange={(e) => setSelected(e.target.checked ? [...selected, it.id] : selected.filter((k) => k !== it.id))} /></td>
                  <td><strong>{it.reference || it.id}</strong>{(it.tags ?? []).map((t) => <span key={t} className="cc-tag">{t}</span>)}</td>
                  <td className="ui-mono cc-clip cc-queue-data" title={short(it.data)}>{short(it.data)}</td>
                  <td>{statusPill(it)}</td>
                  <td className="cc-num">{it.attempts}</td><td className="cc-num">{it.priority}</td><td>{when(it.created)}</td>
                  <td className={it.error ? "cc-err cc-clip" : "cc-clip"} title={it.error ? short(it.error) : short(it.result)}>{it.error_kind && it.error ? <span className="cc-tag">{KIND[it.error_kind] ?? it.error_kind}</span> : null}{it.error ? short(it.error) : short(it.result) || <span className="cc-none">–</span>}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + (it.reference || it.id)} items={itemMenu(it)} /></td>
                </tr>
              ))}
              {items && !items.items.length && <tr className="is-static"><td colSpan={9} className="cc-none">No items{status ? " " + statusName(status).toLowerCase() : ""}.</td></tr>}
              {!items && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {items && <div className="cc-list-pager"><span>{items.items.length} of {items.total} item{items.total === 1 ? "" : "s"}{status ? ", " + statusName(status).toLowerCase() + " only" : ""}
          {items.total > items.items.length ? " · narrow the search to see more" : ""}</span></div>}
      </>}
      {tab === "details" && <div className="cc-agent-tab">
        {x.description && <p>{x.description}</p>}
        <dl className="cc-facts cc-facts-wide">
          <dt>Agent</dt><dd>{agentName(x.bot_path)}</dd><dt>Runner pool</dt><dd>{poolName(x.pool_id)}</dd>
          <dt>Attempts per item</dt><dd>{x.max_attempts}</dd><dt>Service level</dt><dd>{x.sla_minutes ? x.sla_minutes + " minutes" : "None"}</dd>
          <dt>Processing</dt><dd>{x.processing ? "On" + (x.run_as ? " (as " + x.run_as + ")" : "") : "Off"}</dd>{x.model && <><dt>Item model</dt><dd>{x.model.name}</dd></>}
          <dt>Item data</dt><dd>{x.encrypt_data ? "Encrypted in the database" : "Not encrypted"}</dd>
        </dl>
        {manage && <span><Button kind="secondary" onClick={() => onEdit(x)}>Edit settings</Button></span>}
      </div>}
      {tab === "members" && <div className="cc-agent-tab">
        <table className="cc-list-table" aria-label="Queue members">
          <thead><tr><th scope="col">Member</th><th scope="col">As</th></tr></thead>
          <tbody>{x.members.map((m) => <tr key={m.principal + m.member_role} className="is-static"><td>{m.principal}</td><td>{MEMBER_ROLES[m.member_role] ?? m.member_role}</td></tr>)}
            {!x.members.length && <tr className="is-static"><td colSpan={2} className="cc-none">No members: everyone whose role allows it can use this queue.</td></tr>}</tbody>
        </table>
        {manage && <span><Button kind="secondary" onClick={() => setSub("members")}>Change members</Button></span>}
      </div>}

      {sub === "add" && <AddItems api={api} queue={x} onClose={() => setSub(null)} onAdded={(n) => { onNotice("Added " + n + " item(s) to " + x.name + "."); setSub(null); load(); }} />}
      {sub === "members" && <Members api={api} queue={x} onClose={() => setSub(null)} onSaved={() => { onNotice("Saved the members of " + x.name + "."); setSub(null); load(); }} />}
      {sub === "defer" && <DeferDialog count={selected.length} onClose={() => setSub(null)} onDefer={(until) => act(() => api.itemsAction(x.id, "defer", selected, until), "Deferred " + selected.length + " item(s).")} />}
      {sub === "delete" && <Dialog open title="Delete items" width={420} onClose={() => setSub(null)} footer={<>
        <Button kind="plain" onClick={() => setSub(null)}>Cancel</Button><Button kind="danger" onClick={() => act(() => api.itemsAction(x.id, "delete", selected), "Deleted " + selected.length + " item(s).")}>Delete</Button></>}>
        <p>Delete {selected.length} item(s)? Items being worked on are kept.</p></Dialog>}
      {sub === "tags" && <TagsDialog count={selected.length} onClose={() => setSub(null)}
        onSave={(add, remove) => act(() => api.tagItems(x.id, selected, add, remove), "Changed the tags of " + selected.length + " item(s).")} />}
      {sub && typeof sub === "object" && "move" in sub && <TransferDialog api={api} from={x} move={sub.move} count={selected.length} onClose={() => setSub(null)}
        onDone={(to, name) => act(() => api.transferItems(x.id, sub.move ? "move" : "copy", to, selected).then((r) => { onNotice((sub.move ? "Moved " : "Copied ") + r.count + " item(s) to " + name
          + (r.skipped ? "; " + r.skipped + " left out, as " + name + " has their reference" : "") + "."); }), "")} />}
      {sub === "purge" && <PurgeDialog onClose={() => setSub(null)} onDelete={(b) => act(() => api.purgeItems(x.id, b).then((r) => onNotice("Deleted " + r.deleted + " item(s).")), "")} />}
      {sub && typeof sub === "object" && "item" in sub && <ItemDialog api={api} queue={x} itemId={sub.item} canWork={work} onClose={() => setSub(null)} onSaved={(t) => { onNotice(t); setSub(null); load(); }} />}
      {sub && typeof sub === "object" && "validate" in sub && <Validate api={api} queue={x} item={sub.validate} onClose={() => setSub(null)} onDone={(t) => { onNotice(t); setSub(null); load(); }} />}
    </div>
  );
}

function DeferDialog({ count, onClose, onDefer }: { count: number; onClose: () => void; onDefer: (until?: string) => void }) {
  const [until, setUntil] = useState("");
  return (
    <Dialog open title="Defer items" width={420} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={() => onDefer(until ? new Date(until).toISOString() : undefined)}>Defer</Button></>}>
      <p>{count} item(s) wait until this time, then are picked up again by themselves. Leave it empty to wait until someone presses Retry.</p>
      <Field label="Until (your time)">{(id) => <TextInput id={id} type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** Add or remove tags on the ticked items (comma-separated). */
function TagsDialog({ count, onClose, onSave }: { count: number; onClose: () => void; onSave: (add: string[], remove: string[]) => void }) {
  const [add, setAdd] = useState("");
  const [remove, setRemove] = useState("");
  const list = (t: string) => t.split(",").map((s) => s.trim()).filter(Boolean);
  return (
    <Dialog open title="Tags" width={460} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!list(add).length && !list(remove).length} onClick={() => onSave(list(add), list(remove))}>Save tags</Button></>}>
      <p>Change the tags of {count} item(s). Separate tags with commas.</p>
      <Field label="Add tags">{(id) => <TextInput id={id} value={add} placeholder="urgent, north" onChange={(e) => setAdd(e.target.value)} />}</Field>
      <Field label="Remove tags">{(id) => <TextInput id={id} value={remove} onChange={(e) => setRemove(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** Copy or move the ticked items to another queue (as new items). */
function TransferDialog({ api, from, move, count, onClose, onDone }: { api: CrApi; from: WorkQueue; move: boolean; count: number; onClose: () => void; onDone: (to: string, name: string) => void }) {
  const [queues, setQueues] = useState<WorkQueue[]>([]);
  const [to, setTo] = useState("");
  useEffect(() => { api.queues().then((l) => setQueues(l.filter((q) => q.id !== from.id)), (e) => reportMinor("the other queues", e)); }, [api, from.id]);
  const name = queues.find((q) => q.id === to)?.name ?? "";
  return (
    <Dialog open title={(move ? "Move" : "Copy") + " items"} width={460} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!to} onClick={() => onDone(to, name)}>{move ? "Move items" : "Copy items"}</Button></>}>
      <p>{count} item(s) become new items in the other queue with the same data, reference, priority and tags; their attempts start again.{move ? " They leave " + from.name + "." : ""} Items being worked on stay.</p>
      <Field label="To the queue">{(id) => <Select id={id} value={to} onChange={(e) => setTo(e.target.value)} options={[{ value: "", label: "Choose a queue" }, ...queues.map((q) => ({ value: q.id, label: q.name }))]} />}</Field>
    </Dialog>
  );
}

/** Delete finished items: succeeded, failed or both, optionally only those older than some hours. */
function PurgeDialog({ onClose, onDelete }: { onClose: () => void; onDelete: (b: { status: string; older_than_hours?: number }) => void }) {
  const [status, setStatus] = useState("finished");
  const [hours, setHours] = useState("");
  return (
    <Dialog open title="Delete finished items" width={460} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="danger" onClick={() => onDelete({ status, ...(Number(hours) > 0 ? { older_than_hours: Number(hours) } : {}) })}>Delete</Button></>}>
      <p>Items waiting or being worked on are never deleted here.</p>
      <div className="cc-grid2">
        <Field label="Items">{(id) => <Select id={id} value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: "finished", label: "Succeeded and failed" }, { value: "success", label: "Succeeded" }, { value: "failed", label: "Failed" }]} />}</Field>
        <Field label="Older than (hours, empty for any age)">{(id) => <TextInput id={id} type="number" min={0} value={hours} onChange={(e) => setHours(e.target.value)} />}</Field>
      </div>
    </Dialog>
  );
}

/** Add items from pasted JSON or CSV, or a CSV, JSON or Excel file (One CommandCenter reads CSV and Excel by the item model). */
function AddItems({ api, queue: x, onClose, onAdded }: { api: CrApi; queue: WorkQueue; onClose: () => void; onAdded: (n: number) => void }) {
  const [text, setText] = useState("");
  const [book, setBook] = useState<{ name: string; data: string } | null>(null);
  const [sheet, setSheet] = useState("");
  const [error, setError] = useState<string | null>(null);
  const pick = async (f?: File) => {
    if (!f) return;
    setBook(null);
    if (/\.xlsx$/i.test(f.name)) {
      const buf = new Uint8Array(await f.arrayBuffer()); let bin = "";
      for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000));
      setBook({ name: f.name, data: btoa(bin) }); setText("");
    } else setText(await f.text());
  };
  const add = async () => {
    try {
      const t = text.trim();
      const r = book ? await api.addItemsXlsx(x.id, book.data, sheet.trim() || null)
        : t.startsWith("[") || t.startsWith("{") ? await api.addItems(x.id, ([] as unknown[]).concat(JSON.parse(t))) : await api.addItemsCsv(x.id, t);
      onAdded(r.added);
    } catch (e) { setError(message(e)); }
  };
  return (
    <Dialog open title={"Add items to " + x.name} width={640} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!text.trim() && !book} onClick={add}>Add items</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">Paste a JSON list of objects or CSV with a header row, or choose a CSV, JSON or Excel file. A reference column names the item; priority puts it first.</p>
      <input type="file" aria-label="CSV, JSON or Excel file" accept=".csv,.json,.xlsx" onChange={(e) => pick(e.target.files?.[0])} />
      {book ? <><p>{book.name} loaded (Excel).</p><Field label="Excel sheet (empty for the first)">{(id) => <TextInput id={id} value={sheet} onChange={(e) => setSheet(e.target.value)} />}</Field></>
        : <TextArea aria-label="Items" rows={8} value={text} placeholder={'[{"_reference": "INV-1001", "vendor": "Acme", "amount": 120.5}]'} onChange={(e) => setText(e.target.value)} />}
    </Dialog>
  );
}

/** Members: owners manage the queue, participants add and change items, consumers process and retry them. */
function Members({ api, queue: x, onClose, onSaved }: { api: CrApi; queue: WorkQueue & { members: { principal: string; member_role: string }[] }; onClose: () => void; onSaved: () => void }) {
  const [rows, setRows] = useState(x.members.map((m) => ({ principal: m.principal, role: m.member_role })));
  const [who, setWho] = useState("");
  const directory = useCallback(() => principalDirectory(api), [api]);
  const [role, setRole] = useState("participant");
  const [error, setError] = useState<string | null>(null);
  const ROLES: [string, string][] = [["owner", "Owner (manages the queue)"], ["participant", "Participant (adds and changes items)"], ["consumer", "Consumer (processes the items)"]];
  return (
    <Dialog open title={"Members of " + x.name} width={560} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={() => api.setQueueMembers(x.id, rows).then(onSaved, (e) => setError(message(e)))}>Save members</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">With members, only they can use the queue. Keep yourself as an owner. Members can be people, teams or roles.</p>
      <table className="st-table" aria-label="Queue members"><tbody>
        {rows.map((r, i) => <tr key={r.principal + r.role}><td>{principalLabel(r.principal)}</td><td>{ROLES.find(([k]) => k === r.role)?.[1] ?? r.role}</td>
          <td><Button kind="plain" onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</Button></td></tr>)}
        {!rows.length && <tr><td className="st-muted">No members: everyone whose role allows it can use this queue.</td></tr>}
      </tbody></table>
      <span className="cc-inline">
        <PrincipalPicker label="Member" single source={directory} value={who} onChange={setWho} />
        <Select aria-label="Member as" value={role} onChange={(e) => setRole(e.target.value)} options={ROLES.map(([value, label]) => ({ value, label }))} />
        <Button kind="secondary" disabled={!who.trim()} onClick={() => { if (!rows.some((r) => r.principal === who.trim() && r.role === role)) setRows([...rows, { principal: who.trim(), role }]); setWho(""); }}>Add</Button>
      </span>
    </Dialog>
  );
}

/** An item: its data (by the model's fields, or as JSON), reference, priority, result and error. */
function ItemDialog({ api, queue: x, itemId, canWork, onClose, onSaved }: { api: CrApi; queue: WorkQueue & { model?: ItemModel | null }; itemId: string; canWork: boolean; onClose: () => void; onSaved: (t: string) => void }) {
  const [it, setIt] = useState<WorkItem | null>(null);
  const [data, setData] = useState<Record<string, string>>({});
  const [json, setJson] = useState("");
  const [ref, setRef] = useState("");
  const [prio, setPrio] = useState("0");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.queueItem(x.id, itemId).then((i) => {
    setIt(i); const d = (parsed(i.data) ?? {}) as Record<string, unknown>;
    setData(Object.fromEntries(Object.entries(d).map(([k, v]) => [k, v === null || v === undefined ? "" : String(v)]))); setJson(JSON.stringify(d, null, 2));
    setRef(i.reference ?? ""); setPrio(String(i.priority));
  }, (e) => setError(message(e))); }, [api, x.id, itemId]);
  if (!it) return <Dialog open title="Work item" width={640} onClose={onClose} footer={null}>{error ? <p className="st-alert" role="alert">{error}</p> : <p className="st-muted" role="status">Loading…</p>}</Dialog>;
  const fields = x.model?.fields;
  const locked = it.status === "in_progress" || !canWork;
  const submit = () => {
    let d: unknown;
    if (fields) { const o: Record<string, unknown> = {}; for (const f of fields) { const t = data[f.name] ?? ""; if (t !== "") o[f.name] = f.type === "number" ? Number(t) : f.type === "boolean" ? t === "true" : t; } d = o; }
    else { try { d = JSON.parse(json || "{}"); } catch { setError("The data is not JSON."); return; } }
    api.updateQueueItem(x.id, it.id, { data: d, reference: ref.trim() || null, priority: Number(prio || 0) }).then(() => onSaved("Saved the item " + (ref || it.id) + "."), (e) => setError(message(e)));
  };
  return (
    <Dialog open title={"Work item " + (it.reference || it.id)} width={680} onClose={onClose} footer={locked ? null : <Button kind="primary" onClick={submit}>Save changes</Button>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <dl className="cc-facts"><dt>Status</dt><dd>{statusName(it.status)}</dd><dt>Attempts</dt><dd>{it.attempts}</dd><dt>Added</dt><dd>{when(it.created)}</dd>{it.finished && <><dt>Finished</dt><dd>{when(it.finished)}</dd></>}
        {!!it.tags?.length && <><dt>Tags</dt><dd>{it.tags.join(", ")}</dd></>}{it.error_kind && <><dt>Exception</dt><dd>{KIND[it.error_kind] ?? it.error_kind}</dd></>}</dl>
      <div className="cc-grid2">
        <Field label="Reference">{(id) => <TextInput id={id} value={ref} disabled={locked} onChange={(e) => setRef(e.target.value)} />}</Field>
        <Field label="Priority">{(id) => <TextInput id={id} type="number" value={prio} disabled={locked} onChange={(e) => setPrio(e.target.value)} />}</Field>
      </div>
      <h3 className="cc-log-title">Data</h3>
      {fields ? <div className="cc-grid2">{fields.map((f) => <Field key={f.name} label={f.name + (f.required ? " *" : "") + " (" + f.type + ")"}>{(id) => f.type === "boolean"
        ? <Select id={id} value={data[f.name] ?? ""} disabled={locked} onChange={(e) => setData({ ...data, [f.name]: e.target.value })} options={[{ value: "", label: "(empty)" }, { value: "true", label: "True" }, { value: "false", label: "False" }]} />
        : <TextInput id={id} type={f.type === "number" ? "number" : f.type === "date" ? "date" : "text"} value={data[f.name] ?? ""} disabled={locked} onChange={(e) => setData({ ...data, [f.name]: e.target.value })} />}</Field>)}</div>
        : <TextArea aria-label="Item data (JSON)" rows={8} value={json} disabled={locked} onChange={(e) => setJson(e.target.value)} />}
      {it.result != null && <><h3 className="cc-log-title">Result</h3><pre className="cc-codes">{JSON.stringify(parsed(it.result), null, 2)}</pre></>}
      {it.error != null && <><h3 className="cc-log-title">Error</h3><pre className="cc-codes cc-err">{JSON.stringify(parsed(it.error), null, 2)}</pre></>}
      {it.status === "in_progress" && <p className="st-muted">The item is being worked on; it can be changed after its run ends.</p>}
    </Dialog>
  );
}

/** Validation: fields the agent read with too little confidence (marked); approve with corrections or reject with a reason. */
function Validate({ api, queue: x, item: it, onClose, onDone }: { api: CrApi; queue: WorkQueue; item: WorkItem; onClose: () => void; onDone: (t: string) => void }) {
  const res = (parsed(it.result) ?? {}) as { validation?: { fields?: Record<string, unknown>; confidence?: Record<string, number>; min_confidence?: number } };
  const v = res.validation ?? {}, conf = v.confidence ?? {}, min = Number(v.min_confidence ?? 0);
  const [fields, setFields] = useState<Record<string, string>>(Object.fromEntries(Object.entries(v.fields ?? {}).map(([k, val]) => [k, val === null || val === undefined ? "" : String(val)])));
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const name = it.reference || it.id;
  return (
    <Dialog open title={"Validate " + name} width={680} onClose={onClose} footer={<>
      <Button kind="danger" disabled={!reason.trim()} onClick={() => api.validateItem(x.id, it.id, { reject: true, reason: reason.trim() }).then(() => onDone("Rejected " + name + "."), (e) => setError(message(e)))}>Reject</Button>
      <span className="st-bar-fill" />
      <Button kind="primary" onClick={() => api.validateItem(x.id, it.id, { fields: Object.fromEntries(Object.entries(fields).map(([k, val]) => [k, val === "" ? null : val])) })
        .then((r) => onDone("Approved " + name + (r.changed?.length ? " with " + r.changed.length + " correction(s)" : "") + "."), (e) => setError(message(e)))}>Approve</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">The agent was not sure of the fields marked (below {Math.round(min * 100)} %). Check them against the document, correct them and approve: the item goes back to the agent with these values.</p>
      <div className="cc-grid2">{Object.keys(fields).map((k) => {
        const c = conf[k] === undefined ? null : Number(conf[k]); const unsure = c !== null && c < min;
        return <div key={k} className={unsure ? "cc-unsure" : undefined}><Field label={k + (c !== null ? " (" + Math.round(c * 100) + " %" + (unsure ? ", unsure" : "") + ")" : "")}>{(id) =>
          <TextInput id={id} value={fields[k]} onChange={(e) => setFields({ ...fields, [k]: e.target.value })} />}</Field></div>;
      })}</div>
      <Field label="Reject instead: why (needed to reject)">{(id) => <TextInput id={id} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** Work item models (AA's work item templates): named fields with types, required and shown in the list. */
function Models({ api, manage, onClose, onNotice }: { api: CrApi; manage: boolean; onClose: () => void; onNotice: (t: string) => void }) {
  const [models, setModels] = useState<ItemModel[] | null>(null);
  const [edit, setEdit] = useState<ItemModel | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => api.itemModels().then(setModels, (e) => setError(message(e))), [api]);
  useEffect(() => { read(); }, [read]);
  if (edit) return <ModelForm api={api} model={edit === "new" ? null : edit} onClose={() => setEdit(null)} onSaved={(t) => { onNotice(t); setEdit(null); read(); }} />;
  return (
    <Dialog open title="Work item models" width={720} onClose={onClose} footer={manage ? <Button kind="primary" onClick={() => setEdit("new")}>Create model</Button> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">A model names the fields of a queue's items and their types. Items added by hand, from CSV or Excel, by agents or over the API are checked against it.</p>
      <table className="st-table" aria-label="Models">
        <thead><tr><th scope="col">Model</th><th scope="col">Fields</th><th scope="col">Queues</th><th scope="col"><span className="st-sr">Actions</span></th></tr></thead>
        <tbody>
          {(models ?? []).map((m) => <tr key={m.id}><td>{m.name}</td><td>{m.fields.map((f) => f.name + (f.required ? "*" : "") + " (" + f.type + ")").join(", ")}</td><td>{m.queues ?? 0}</td>
            <td>{manage && <span className="cc-inline"><Button kind="plain" onClick={() => setEdit(m)}>Edit</Button>
              <Button kind="plain" onClick={() => api.deleteItemModel(m.id).then(() => { onNotice("Deleted the model " + m.name + "."); read(); }, (e) => setError(message(e)))}>Delete</Button></span>}</td></tr>)}
          {models && !models.length && <tr className="is-static"><td colSpan={4} className="cc-none">No models yet.</td></tr>}
        </tbody>
      </table>
    </Dialog>
  );
}

function ModelForm({ api, model: m, onClose, onSaved }: { api: CrApi; model: ItemModel | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [name, setName] = useState(m?.name ?? "");
  const [description, setDescription] = useState(m?.description ?? "");
  const [fields, setFields] = useState<ItemModel["fields"]>(m?.fields.map((f) => ({ ...f })) ?? [{ name: "", type: "text", required: true, display: true }]);
  const [error, setError] = useState<string | null>(null);
  const set = (i: number, p: Partial<ItemModel["fields"][number]>) => setFields(fields.map((f, j) => j === i ? { ...f, ...p } : f));
  return (
    <Dialog open title={m ? "Model " + m.name : "Create work item model"} width={720} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!name.trim() || fields.some((f) => !f.name.trim())} onClick={() => api.saveItemModel(m?.id ?? null, { name: name.trim(), description, fields }).then(() => onSaved("Saved the model " + name.trim() + "."), (e) => setError(message(e)))}>Save model</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="Invoice" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description">{(id) => <TextInput id={id} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      </div>
      <table className="st-table" aria-label="Model fields">
        <thead><tr><th scope="col">Field</th><th scope="col">Type</th><th scope="col">Required</th><th scope="col">Shown in the list</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
        <tbody>{fields.map((f, i) => <tr key={i}>
          <td><TextInput aria-label={"Field " + (i + 1)} value={f.name} placeholder="invoice_no" onChange={(e) => set(i, { name: e.target.value.trim() })} /></td>
          <td><Select aria-label={"Type of field " + (i + 1)} value={f.type} onChange={(e) => set(i, { type: e.target.value as ItemModel["fields"][number]["type"] })}
            options={[{ value: "text", label: "Text" }, { value: "number", label: "Number" }, { value: "boolean", label: "True/false" }, { value: "date", label: "Date" }]} /></td>
          <td><input type="checkbox" aria-label={"Field " + (i + 1) + " required"} checked={!!f.required} onChange={(e) => set(i, { required: e.target.checked })} /></td>
          <td><input type="checkbox" aria-label={"Field " + (i + 1) + " shown"} checked={f.display !== false} onChange={(e) => set(i, { display: e.target.checked })} /></td>
          <td><Button kind="plain" onClick={() => setFields(fields.filter((_, j) => j !== i))}>Remove</Button></td></tr>)}</tbody>
      </table>
      <Button kind="plain" onClick={() => setFields([...fields, { name: "", type: "text", required: false, display: true }])}>Add field</Button>
      {m && <p className="st-muted">While queues using this model have items, fields can be added (optional) but not removed, made required or given another type.</p>}
    </Dialog>
  );
}

/** Import a queue definition exported from One CommandCenter: settings, model, members and waiting items. */
function ImportQueue({ api, onClose, onDone }: { api: CrApi; onClose: () => void; onDone: (t: string) => void }) {
  const [def, setDef] = useState<unknown>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="Import a work queue" width={560} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!def} onClick={() => api.importQueue(def, name.trim() || null).then((r) => onDone("Made " + r.name + " with " + r.items + " item(s)." + (r.notes?.length ? " " + r.notes.join(" ") : "")), (e) => setError(message(e)))}>Import</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-list-hint">A queue definition exported from One CommandCenter. The agent and runner pool are linked when they exist here.</p>
      <input type="file" aria-label="Queue definition file" accept=".json,application/json" onChange={async (e) => { const f = e.target.files?.[0]; if (!f) return; try { setDef(JSON.parse(await f.text())); setError(null); } catch { setError("The file is not JSON."); } }} />
      <Field label="Name here (empty: the name in the file)">{(id) => <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
    </Dialog>
  );
}
