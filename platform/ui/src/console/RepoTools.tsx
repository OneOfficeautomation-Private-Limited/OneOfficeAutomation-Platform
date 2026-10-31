import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { AgentBundle, CodePolicy, CodeReport, CrApi, CrUser, ImportResult, RepoAgent, RepoFile } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Agent Repository, part 2 (checked 31 Oct 2026 against AA's Bot Lifecycle Management and code analysis, UiPath's
 * Automation Ops governance (analyzer before publish) and Blue Prism's Release Manager): export agents as a bundle with
 * the agents they call, or promote them straight to another Command Center; import a bundle after seeing what it holds;
 * the code analysis policy and a report for all agents; an agent's own analysis; and the files kept in a folder.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process|form)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const size = (n: number) => n < 1024 ? n + " B" : n < 1_048_576 ? (n / 1024).toFixed(1) + " KB" : (n / 1_048_576).toFixed(1) + " MB";
const rule = (id: string) => id.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
function save(fileName: string, text: string, type: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type }));
  a.download = fileName;
  document.body.appendChild(a); a.click(); a.remove();
}
const STATUS: Record<string, string> = { created: "Added", updated: "New version", skipped: "Already here: skipped", failed: "Failed", "not imported": "Not imported" };

function Results({ r, label }: { r: ImportResult["results"]; label: string }) {
  return <table className="st-table" aria-label={label}>
    <thead><tr><th scope="col">Agent</th><th scope="col">Result</th><th scope="col">Version</th><th scope="col">Problem</th></tr></thead>
    <tbody>{r.map((x) => <tr key={x.path}><td title={x.path}>{agentName(x.path)}</td>
      <td className={"cc-status is-" + (x.status === "created" || x.status === "updated" ? "success" : x.status === "skipped" ? "stopped" : "failed")}>{STATUS[x.status] ?? x.status}</td>
      <td>{x.version ? "v" + x.version : ""}</td><td>{x.error?.message ?? ""}</td></tr>)}</tbody>
  </table>;
}

/** Export ticked agents as a bundle file, or promote them to another Command Center (AA's export, Blue Prism's release). */
export function ExportAgents({ api, onClose, onNotice }: { api: CrApi; onClose: () => void; onNotice: (t: string) => void }) {
  const [agents, setAgents] = useState<RepoAgent[]>([]);
  const [picked, setPicked] = useState<string[]>([]);
  const [q, setQ] = useState("");
  const [v, setV] = useState({ version: "production", deps: true, target: "", key: "", conflict: "skip" as "skip" | "overwrite", live: false });
  const [promoteOpen, setPromoteOpen] = useState(false);
  const [result, setResult] = useState<ImportResult["results"] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.repo().then(setAgents, (e) => setError(message(e))); }, [api]);   // every agent, not only the open folder
  const shown = agents.filter((a) => !q.trim() || a.path.toLowerCase().includes(q.trim().toLowerCase()));
  const req = () => ({ paths: picked, version: v.version.trim() || "production", include_dependencies: v.deps });
  const download = () => api.exportAgents(req()).then((b) => {
    save("agents-" + new Date().toISOString().slice(0, 10) + ".ooabundle.json", JSON.stringify(b, null, 2), "application/json");
    onNotice("Exported " + b.agents.length + " agent" + (b.agents.length === 1 ? "" : "s") + " to a bundle file."); onClose();
  }, (e) => setError(message(e)));
  const promote = () => api.promoteAgents({ ...req(), target_url: v.target.trim(), api_key_ref: v.key.trim(), conflict: v.conflict, production: v.live }).then((r) => {
    const res = (r.result?.results ?? []) as ImportResult["results"];
    setResult(res);
    if (r.ok) onNotice("Promoted " + res.length + " agent" + (res.length === 1 ? "" : "s") + " to " + r.target + ".");
    else setError("Not promoted (" + r.status + "): " + (res.filter((x) => x.error).map((x) => agentName(x.path) + ": " + x.error!.message).join("; ") || "the other Command Center refused it"));
  }, (e) => setError(message(e)));
  return (
    <Dialog open title="Export or promote agents" width={720} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!picked.length} onClick={download}>Download bundle</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">A bundle holds each agent's exact version with a checksum and, when ticked, every agent it calls. Import it in another Command Center, or promote straight to one. Shared values, credentials and files are not included: the other Command Center needs its own.</p>
      <div className="cc-bar cc-bar-tight">
        <TextInput type="search" aria-label="Search agents to export" placeholder="Search agents" value={q} onChange={(e) => setQ(e.target.value)} />
        <Button kind="plain" onClick={() => setPicked([...new Set([...picked, ...shown.map((a) => a.path)])])}>Tick all shown</Button>
        <Button kind="plain" disabled={!picked.length} onClick={() => setPicked([])}>Clear</Button>
        <span className="cc-chosen">{picked.length} ticked</span>
      </div>
      <div className="cc-pick" role="group" aria-label="Agents to export">{shown.map((a) => <label key={a.path} className="st-check-line">
        <input type="checkbox" checked={picked.includes(a.path)} onChange={(e) => setPicked(e.target.checked ? [...picked, a.path] : picked.filter((p) => p !== a.path))} />
        {agentName(a.path)} <span className="st-muted">{a.path.includes("/") ? a.path.slice(0, a.path.lastIndexOf("/")) + " · " : ""}v{a.latest_version}{a.production_version ? ", live v" + a.production_version : ", not live"}</span></label>)}</div>
      <div className="cc-grid2">
        <Field label="Version" hint="production (the live one), latest, a number or a label">{(id) => <TextInput id={id} value={v.version} onChange={(e) => setV({ ...v, version: e.target.value })} />}</Field>
        <label className="st-check-line cc-gap"><input type="checkbox" checked={v.deps} onChange={(e) => setV({ ...v, deps: e.target.checked })} /> Include the agents they call</label>
      </div>
      <button type="button" className="cc-link cc-gap" aria-expanded={promoteOpen} onClick={() => setPromoteOpen(!promoteOpen)}>Promote to another Command Center</button>
      {promoteOpen && <div className="cc-promote">
        <p className="st-muted">Make an API key there (as someone allowed to import) and keep it in a vault folder here; it is read from the vault and sent only to that address.</p>
        <div className="cc-grid2">
          <Field label="Its address">{(id) => <TextInput id={id} type="url" value={v.target} placeholder="https://cc-prod.example.com" onChange={(e) => setV({ ...v, target: e.target.value })} />}</Field>
          <Field label="Its API key (vault reference)">{(id) => <TextInput id={id} value={v.key} placeholder="vault folder/credential/attribute" onChange={(e) => setV({ ...v, key: e.target.value })} />}</Field>
          <Field label="If an agent is already there">{(id) => <Select id={id} value={v.conflict} onChange={(e) => setV({ ...v, conflict: e.target.value as "skip" | "overwrite" })}
            options={[{ value: "skip", label: "Skip it" }, { value: "overwrite", label: "Add a new version of it" }]} />}</Field>
          <label className="st-check-line cc-gap"><input type="checkbox" checked={v.live} onChange={(e) => setV({ ...v, live: e.target.checked })} /> Make the versions live there</label>
        </div>
        <Button kind="secondary" disabled={!picked.length || !v.target.trim() || v.key.trim().split("/").length !== 3} onClick={promote}>Promote</Button>
      </div>}
      {result && <Results r={result} label="Promotion results" />}
    </Dialog>
  );
}

/** Import a bundle: what it holds first (Blue Prism's conflict check), then each agent's result; nothing is imported unless all pass. */
export function ImportAgents({ api, folder, onClose, onDone }: { api: CrApi; folder: string; onClose: () => void; onDone: (m: string) => void }) {
  const [existing, setExisting] = useState<Set<string>>(new Set());
  useEffect(() => { api.repo().then((a) => setExisting(new Set(a.map((x) => x.path))), () => {}); }, [api]);
  const [bundle, setBundle] = useState<AgentBundle | null>(null);
  const [v, setV] = useState({ folder, conflict: "skip" as "skip" | "overwrite", live: false });
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const read = async (f?: File) => {
    setBundle(null); setResult(null); setError(null);
    if (!f) return;
    try {
      const b = JSON.parse(await f.text()) as AgentBundle;
      if (!Array.isArray(b?.agents)) throw new Error("This is not an agent bundle: export one from an Agent Repository.");
      setBundle(b);
    } catch (e) { setError(e instanceof SyntaxError ? "This file is not JSON: choose a .ooabundle.json file." : message(e)); }
  };
  const target = (p: string) => v.folder.trim() ? v.folder.trim().replace(/\/+$/, "") + "/" + p : p;   // the bundle's own folders go under it
  const run = () => bundle && api.importAgents({ bundle, conflict: v.conflict, production: v.live, target_folder: v.folder.trim() || null }).then((r) => {
    setResult(r);
    if (r.ok) onDone("Imported " + r.imported + " agent" + (r.imported === 1 ? "" : "s") + ".");
  }, (e) => setError(message(e)));
  return (
    <Dialog open title="Import agents" width={720} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>{result?.ok ? "Done" : "Cancel"}</Button>
      {!result?.ok && <Button kind="primary" disabled={!bundle} onClick={run}>Import</Button>}</>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">Every agent is checked first (checksum, validation, folder permissions); nothing is imported unless all of them pass. An agent that is already here keeps its history and gets a new version.</p>
      <input type="file" accept=".json,application/json" aria-label="Bundle file" onChange={(e) => read(e.target.files?.[0])} />
      {bundle && <>
        <p className="cc-gap">{bundle.agents.length} agent{bundle.agents.length === 1 ? "" : "s"}{bundle.exported_by ? ", exported by " + bundle.exported_by : ""}{bundle.exported_at ? " on " + when(bundle.exported_at) : ""}{bundle.source ? " from " + bundle.source : ""}.
          {bundle.toolkits?.length ? " Toolkits needed: " + bundle.toolkits.join(", ") + "." : ""}</p>
        <table className="st-table" aria-label="In the bundle">
          <thead><tr><th scope="col">Agent</th><th scope="col">Version</th><th scope="col">Goes to</th><th scope="col">Here already</th></tr></thead>
          <tbody>{bundle.agents.map((a) => <tr key={a.path}><td title={a.path}>{agentName(a.path)}{a.included_as === "dependency" && <span className="cc-tag">Called by another</span>}</td>
            <td>v{a.version}{a.production ? " (live there)" : ""}</td><td>{target(a.path)}</td>
            <td className={existing.has(target(a.path)) ? "cc-sev is-warning" : undefined}>{existing.has(target(a.path)) ? (v.conflict === "skip" ? "Yes: skipped" : "Yes: new version") : "No"}</td></tr>)}</tbody>
        </table>
      </>}
      <div className="cc-grid2 cc-gap">
        <Field label="Put them under folder" hint="Their own folders go under it; empty keeps them as they are">{(id) => <TextInput id={id} value={v.folder} onChange={(e) => setV({ ...v, folder: e.target.value })} />}</Field>
        <Field label="If an agent is already here">{(id) => <Select id={id} value={v.conflict} onChange={(e) => setV({ ...v, conflict: e.target.value as "skip" | "overwrite" })}
          options={[{ value: "skip", label: "Skip it" }, { value: "overwrite", label: "Add a new version of it" }]} />}</Field>
        <label className="st-check-line"><input type="checkbox" checked={v.live} onChange={(e) => setV({ ...v, live: e.target.checked })} /> Make the imported versions live</label>
      </div>
      {result && <>{!result.ok && <p className="st-alert">Nothing was imported: fix the problems listed.</p>}<Results r={result.results} label="Import results" /></>}
    </Dialog>
  );
}

/** The code analysis policy (as UiPath's analyzer rules and "before publish"): rules, severity, settings, refusing check-ins with errors; a report for all agents. */
export function CodeAnalysisPolicy({ api, user, onClose, onNotice }: { api: CrApi; user: CrUser; onClose: () => void; onNotice: (t: string) => void }) {
  const [pol, setPol] = useState<CodePolicy | null>(null);
  const [text, setText] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const edit = user.permissions.includes("settings.manage");
  useEffect(() => { api.codePolicy().then((p) => {
    setPol(p);
    setText(Object.fromEntries(Object.entries(p.rules).flatMap(([id, r]) => Object.entries(r.params ?? {}).map(([k, v]) => [id + "." + k, Array.isArray(v) ? v.join(", ") : String(v)]))));
  }, (e) => setError(message(e))); }, [api]);
  const set = (id: string, p: Partial<CodePolicy["rules"][string]>) => pol && setPol({ ...pol, rules: { ...pol.rules, [id]: { ...pol.rules[id], ...p } } });
  const saveIt = () => {
    if (!pol) return;
    const rules = Object.fromEntries(Object.entries(pol.rules).map(([id, r]) => [id, { enabled: r.enabled, severity: r.severity,
      params: Object.fromEntries(Object.entries(r.params ?? {}).map(([k, old]) => { const t = text[id + "." + k] ?? ""; return [k, Array.isArray(old) ? t.split(",").map((x) => x.trim()).filter(Boolean) : typeof old === "number" ? Number(t) : t]; })) }]));
    api.saveCodePolicy({ enforce_at_checkin: pol.enforce_at_checkin, rules }).then(() => { onNotice("Saved the code analysis policy."); onClose(); }, (e) => setError(message(e)));
  };
  const report = () => api.codeAnalysisCsv().then((t) => save("code-analysis.csv", t, "text/csv"), (e) => setError(message(e)));
  return (
    <Dialog open title="Code analysis" width={800} onClose={onClose} footer={<>
      <Button kind="secondary" onClick={report}>Report for all agents (CSV)</Button><span className="st-bar-fill" />
      <Button kind="plain" onClick={onClose}>{edit ? "Cancel" : "Close"}</Button>{edit && <Button kind="primary" disabled={!pol} onClick={saveIt}>Save</Button>}</>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">Agents are checked against these rules at every check-in, and on demand from an agent's view. Reports are kept for each version.{edit ? "" : " Only administrators who change settings may change the rules."}</p>
      {!pol && !error && <p className="st-muted" role="status">Loading…</p>}
      {pol && <>
        <label className="st-check-line"><input type="checkbox" disabled={!edit} checked={pol.enforce_at_checkin} onChange={(e) => setPol({ ...pol, enforce_at_checkin: e.target.checked })} /> Refuse check-ins that have errors</label>
        <table className="st-table cc-gap" aria-label="Rules">
          <thead><tr><th scope="col">Use</th><th scope="col">Rule</th><th scope="col">Severity</th><th scope="col">Settings</th></tr></thead>
          <tbody>{Object.entries(pol.rules).map(([id, r]) => <tr key={id}>
            <td><input type="checkbox" disabled={!edit} checked={r.enabled} aria-label={"Use " + rule(id)} onChange={(e) => set(id, { enabled: e.target.checked })} /></td>
            <td><strong>{rule(id)}</strong><div className="st-muted">{r.description}</div></td>
            <td><Select aria-label={"Severity of " + rule(id)} disabled={!edit} value={r.severity} onChange={(e) => set(id, { severity: e.target.value as "error" | "warning" })}
              options={[{ value: "error", label: "Error" }, { value: "warning", label: "Warning" }]} /></td>
            <td>{Object.keys(r.params ?? {}).map((k) => <label key={k} className="cc-param">{k.replace(/_/g, " ")}
              <TextInput disabled={!edit} aria-label={rule(id) + ": " + k.replace(/_/g, " ")} value={text[id + "." + k] ?? ""} onChange={(e) => setText({ ...text, [id + "." + k]: e.target.value })} /></label>)}</td>
          </tr>)}</tbody>
        </table>
      </>}
    </Dialog>
  );
}

/** An agent's own analysis (AA shows findings in the bot editor): the latest version's errors and warnings, analysed again on demand. */
export function AgentAnalysis({ api, path }: { api: CrApi; path: string }) {
  const [r, setR] = useState<CodeReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.codeAnalysis(path).then(setR, (e) => setError(message(e))); }, [api, path]);
  const again = () => api.analyzeAgent(path).then(setR, (e) => setError(message(e)));
  return <>
    <div className="cc-card-head cc-gap"><h3 className="cc-log-title">Code analysis</h3><Button kind="plain" onClick={again}>Analyse again</Button></div>
    {error && <p className="st-alert">{error}</p>}
    {r && <p><span className={"cc-status is-" + (r.passed ? "success" : "failed")}>{r.passed ? "No errors" : r.errors + " error" + (r.errors === 1 ? "" : "s")}</span>
      <span className="st-muted"> · {r.warnings} warning{r.warnings === 1 ? "" : "s"} · v{r.version}{r.analyzed ? " · analysed " + when(r.analyzed) : ""}</span></p>}
    {r && r.violations.length > 0 && <table className="st-table" aria-label="Findings">
      <thead><tr><th scope="col">Severity</th><th scope="col">Rule</th><th scope="col">Step</th><th scope="col">Finding</th></tr></thead>
      <tbody>{r.violations.map((x, i) => <tr key={i}><td className={"cc-sev is-" + (x.severity === "error" ? "critical" : "warning")}>{x.severity === "error" ? "Error" : "Warning"}</td>
        <td>{rule(x.rule)}</td><td className="ui-mono">{x.step ?? ""}</td><td>{x.message}</td></tr>)}</tbody>
    </table>}
  </>;
}

/** The files kept in a folder (templates, workbooks, lists agents use), with upload, download and delete (AA's files in the public workspace). */
export function RepoFiles({ api, user, folder, onNotice }: { api: CrApi; user: CrUser; folder: string; onNotice: (t: string) => void }) {
  const [files, setFiles] = useState<RepoFile[] | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(() => api.repoFiles(folder).then((f) => { setFiles(f); setError(null); }, (e) => setError(message(e))), [api, folder]);
  useEffect(() => { load(); }, [load]);
  const here = useMemo(() => (files ?? []).filter((f) => f.folder === folder), [files, folder]);
  const upload = async (f?: File) => {
    if (!f) return;
    const path = (folder ? folder + "/" : "") + f.name;
    try { const r = await api.uploadRepoFile(path, f); onNotice("Uploaded " + path + " (version " + r.version + ")."); load(); }
    catch (e) { setError(message(e)); }
    if (picker.current) picker.current.value = "";
  };
  const remove = (path: string) => api.deleteRepoFile(path).then(() => { onNotice("Deleted " + path + "."); setDeleting(null); load(); }, (e) => setError(message(e)));
  return <div className="cc-card cc-card-flat cc-files">
    <div className="cc-card-head"><h2>Files{folder ? " in " + folder : " at the top"}</h2>
      {can("repo.checkin") && <><Button kind="secondary" onClick={() => picker.current?.click()}>Upload a file</Button>
        <input ref={picker} type="file" hidden aria-label="File to upload" onChange={(e) => upload(e.target.files?.[0])} /></>}</div>
    <p className="st-muted">Templates, workbooks and lists agents use. An agent lists the files it needs; runners receive them at the same path before the run. Uploading a file that is here adds a version.</p>
    {error && <p className="st-alert" role="alert">{error}</p>}
    {files && (here.length ? <table className="st-table" aria-label="Files">
      <thead><tr><th scope="col">File</th><th scope="col">Size</th><th scope="col">Version</th><th scope="col">Updated</th><th scope="col"></th></tr></thead>
      <tbody>{here.map((f) => <tr key={f.path}><td className="ui-mono">{f.path.split("/").pop()}</td><td>{size(Number(f.size))}</td><td>v{f.version}</td>
        <td>{when(f.updated)}{f.updated_by ? " by " + f.updated_by : ""}</td>
        <td>{deleting === f.path
          ? <span className="cc-inline">Agents that need it will not start. <Button kind="danger" onClick={() => remove(f.path)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Keep</Button></span>
          : <span className="cc-inline"><a className="ui-btn ui-btn-plain" href={api.repoFileUrl(f.path)} download>Download</a>
            {can("repo.delete") && ["edit", "manage"].includes(f.access) && <Button kind="plain" onClick={() => setDeleting(f.path)}>Delete</Button>}</span>}</td></tr>)}</tbody>
    </table> : <p className="st-muted">No files here.</p>)}
  </div>;
}
