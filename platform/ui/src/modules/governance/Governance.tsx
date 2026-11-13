import "./consoleApi";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi } from "../../api/cr";
import type { AiGovernance } from "./consoleApi";
import { Button, HelpButton, Select, TextInput, type HelpTopic, PageError } from "../../components";
import { RefreshButton, rowClick } from "../../console/ListParts";

/**
 * AI > AI governance, built as AA's AI governance (prompt logs) and UiPath's AI Trust Layer audit (checked against their
 * docs, 31 Oct 2026): every AI model call agents made in a period, by model and by agent, with tokens, what was masked
 * and which tools the AI used. Prompts are never stored, only a fingerprint. Calls export as CSV.
 * Review round 2 (1 Nov 2026): the title row and figures of the other pages, Recent calls, By agent and By model in one
 * full-width tabbed panel, one line per call opening its run, and an agent's row showing that agent's calls.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const n = (v?: number | null) => Number(v ?? 0).toLocaleString();
const list = (v?: string) => v && v !== "[]" ? v.replace(/[[\]"]/g, "") : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
type Tab = "recent" | "agents" | "models";

const HELP: HelpTopic = { title: "AI governance", about: "Every AI model call made by agents: which model, how many tokens, "
  + "what was masked and which tools the AI used. Prompts are never stored, only a fingerprint.",
  items: [
    { name: "Period", text: "Pick the last 7, 30 or 90 days or the last year; the figures and lists cover that period." },
    { name: "Calls with personal data masked", text: "How many calls had personal data hidden from the model before it was sent." },
    { name: "Calls where the AI used tools", text: "How many calls in which the model asked to use a tool, such as running another action." },
    { name: "Recent calls", text: "One line per call, newest first. Click a call to open the run that made it in Agent Activity." },
    { name: "Fingerprint", text: "A fingerprint (SHA-256) of the prompt, so the same prompt can be recognised without the prompt itself being kept." },
    { name: "By agent", text: "Calls and tokens per agent. Click an agent to see only its calls in Recent calls; Show every agent goes back." },
    { name: "By model", text: "Calls and tokens in and out per provider and model, for example to compare costs." },
    { name: "Export CSV", text: "Saves the recent calls the search finds as a CSV file, with the prompt fingerprint and run id of each." },
  ] };

export function Governance({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [days, setDays] = useState(30);
  const [g, setG] = useState<AiGovernance | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [agent, setAgent] = useState("");   // Recent calls, only this agent's (an agent row's choice)
  const [tab, setTab] = useState<Tab>("recent");
  const load = useCallback(async () => { try { setG(await api.governance(days)); setError(null); } catch (e) { setError(message(e)); } }, [api, days]);
  useEffect(() => { load(); }, [load]);
  const t = g?.totals ?? {};
  const recent = useMemo(() => (g?.recent ?? []).filter((c) => (!agent || c.bot_path === agent)
    && (!q.trim() || (c.bot_path + " " + (c.requested_by ?? "") + " " + (c.purpose ?? "") + " " + (c.model ?? "")).toLowerCase().includes(q.trim().toLowerCase()))), [g, q, agent]);
  const exportCsv = () => {
    const text = [["Time", "Agent", "User", "Purpose", "Provider", "Model", "Tokens in", "Tokens out", "Masked", "Tools used", "Prompt fingerprint", "Run id"],
      ...recent.map((c) => [c.ts, c.bot_path, c.requested_by ?? "", c.purpose ?? "", c.provider ?? "", c.model ?? "", String(c.input_tokens ?? 0), String(c.output_tokens ?? 0), list(c.masked), list(c.tool_calls), c.prompt_sha256 ?? "", c.run_id ?? ""])]
      .map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "ai-calls.csv"; document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + recent.length + " AI call(s).");
  };
  const figures: [string, string][] = [[n(t.calls), "AI calls"], [n((t.input_tokens ?? 0) + (t.output_tokens ?? 0)), "Tokens"], [n(t.masked_calls), "Calls with personal data masked"],
    [n(t.tool_calls), "Calls where the AI used tools"], [t.avg_latency_ms ? (Number(t.avg_latency_ms) / 1000).toFixed(1) + " s" : "—", "Average answer time"]];
  const tabs: [Tab, string, number][] = g ? [["recent", "Recent calls", (g.recent ?? []).length], ["agents", "By agent", (g.by_agent ?? []).length], ["models", "By model", (g.by_model ?? []).length]] : [];
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-gov-title">
      <header className="cc-list-head">
        <h1 id="cc-gov-title">AI governance</h1>
        <HelpButton topic={HELP} />
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-chip"><Select aria-label="Period" value={String(days)} onChange={(e) => setDays(Number(e.target.value))} options={[["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]].map(([value, label]) => ({ value, label }))} /></span>
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      {!g && !error && <p className="st-muted" role="status">Loading…</p>}
      {g && <>
        <div className="cc-sums" role="group" aria-label="Figures">{figures.map(([v, l]) => <div key={l} className="cc-sum is-static"><strong>{v}</strong><span>{l}</span></div>)}</div>
        <div className="cc-list-panel cc-ops-lists">
          <div className="ov-tabs" role="tablist" aria-label="Calls">
            {tabs.map(([k, l, c]) => <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}<span className="ov-cnt">{c}</span></button>)}
            {tab === "recent" && agent && <span className="cc-val-only">Only {agentName(agent)} <button type="button" className="cc-link" onClick={() => setAgent("")}>Show every agent</button></span>}
            <span className="ov-fill" />
            {tab === "recent" && <span className="cc-gov-tools">
              <TextInput type="search" aria-label="Search calls" placeholder="Search agent, user, purpose, model" value={q} onChange={(e) => setQ(e.target.value)} />
              <Button kind="plain" onClick={exportCsv}>Export CSV</Button></span>}
          </div>
          <div className="cc-list-scroll">
            {tab === "recent" && <table className="cc-list-table" aria-label="Recent AI calls">
              <thead><tr><th scope="col">Event time</th><th scope="col">Agent</th><th scope="col">User</th><th scope="col">Purpose</th><th scope="col">Model</th><th scope="col" className="cc-num">Tokens</th><th scope="col">Masked</th><th scope="col">Tools used</th><th scope="col">Fingerprint</th></tr></thead>
              <tbody>
                {recent.map((c, i) => <tr key={i} className={c.run_id ? undefined : "is-static"} title={c.run_id ? "Open the run that made this call" : undefined}
                  onClick={rowClick(() => { if (c.run_id) location.hash = "#/activity?run=" + encodeURIComponent(c.run_id); })}>
                  <td>{new Date(c.ts).toLocaleString()}</td><td><span className="cc-aico" aria-hidden="true">A</span>{agentName(c.bot_path)}</td><td>{c.requested_by ?? ""}</td>
                  <td className="cc-clip" title={c.purpose ?? undefined}>{c.purpose ?? ""}</td>
                  <td className="cc-clip">{(c.provider ?? "") + "/" + (c.model ?? "")}</td><td className="cc-num">{n((c.input_tokens ?? 0) + (c.output_tokens ?? 0))}</td>
                  <td className="cc-clip" title={list(c.masked) || undefined}>{list(c.masked) || "—"}</td><td className="ui-mono cc-clip" title={list(c.tool_calls) || undefined}>{list(c.tool_calls) || "—"}</td>
                  <td className="ui-mono" title={c.prompt_sha256}>{(c.prompt_sha256 ?? "").slice(0, 12)}</td></tr>)}
                {!recent.length && <tr className="is-static"><td colSpan={9} className="cc-none">No AI calls {agent ? "by " + agentName(agent) + " " : ""}in this period.</td></tr>}
              </tbody>
            </table>}
            {tab === "agents" && <table className="cc-list-table" aria-label="By agent">
              <thead><tr><th scope="col">Agent</th><th scope="col" className="cc-num">Calls</th><th scope="col" className="cc-num">Tokens</th></tr></thead>
              <tbody>{(g.by_agent ?? []).map((x) => <tr key={x.bot_path} title="Show this agent's calls" onClick={rowClick(() => { setAgent(x.bot_path); setTab("recent"); })}>
                <td><span className="cc-aico" aria-hidden="true">A</span>{agentName(x.bot_path)}</td><td className="cc-num">{n(x.calls)}</td><td className="cc-num">{n(x.tokens)}</td></tr>)}
                {!(g.by_agent ?? []).length && <tr className="is-static"><td colSpan={3} className="cc-none">No calls.</td></tr>}</tbody>
            </table>}
            {tab === "models" && <table className="cc-list-table" aria-label="By model">
              <thead><tr><th scope="col">Provider</th><th scope="col">Model</th><th scope="col" className="cc-num">Calls</th><th scope="col" className="cc-num">Tokens in</th><th scope="col" className="cc-num">Tokens out</th></tr></thead>
              <tbody>{(g.by_model ?? []).map((x) => <tr key={x.provider + x.model} className="is-static"><td>{x.provider}</td><td>{x.model ?? ""}</td><td className="cc-num">{n(x.calls)}</td>
                <td className="cc-num">{n(x.input_tokens)}</td><td className="cc-num">{n(x.output_tokens)}</td></tr>)}
                {!(g.by_model ?? []).length && <tr className="is-static"><td colSpan={5} className="cc-none">No calls.</td></tr>}</tbody>
            </table>}
          </div>
        </div>
      </>}
    </section>
  );
}
