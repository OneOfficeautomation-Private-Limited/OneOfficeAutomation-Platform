import { useCallback, useEffect, useMemo, useState } from "react";
import type { AiGovernance, CrApi } from "../api/cr";
import { Button, Select, TextInput } from "../components";

/**
 * AI > AI governance, built as AA's AI governance (prompt logs) and UiPath's AI Trust Layer audit (checked against their
 * docs, 31 Oct 2026): every AI model call agents made in a period, by model and by agent, with tokens, what was masked
 * and which tools the AI used. Prompts are never stored, only a fingerprint. Calls export as CSV.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const n = (v?: number | null) => Number(v ?? 0).toLocaleString();
const list = (v?: string) => v && v !== "[]" ? v.replace(/[[\]"]/g, "") : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;

export function Governance({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [days, setDays] = useState(30);
  const [g, setG] = useState<AiGovernance | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const load = useCallback(async () => { try { setG(await api.governance(days)); setError(null); } catch (e) { setError(message(e)); } }, [api, days]);
  useEffect(() => { load(); }, [load]);
  const t = g?.totals ?? {};
  const recent = useMemo(() => (g?.recent ?? []).filter((c) => !q.trim() || (c.bot_path + " " + (c.requested_by ?? "") + " " + (c.purpose ?? "") + " " + (c.model ?? "")).toLowerCase().includes(q.trim().toLowerCase())), [g, q]);
  const exportCsv = () => {
    const text = [["Time", "Agent", "User", "Purpose", "Provider", "Model", "Tokens in", "Tokens out", "Masked", "Tools used", "Prompt fingerprint", "Run id"],
      ...recent.map((c) => [c.ts, c.bot_path, c.requested_by ?? "", c.purpose ?? "", c.provider ?? "", c.model ?? "", String(c.input_tokens ?? 0), String(c.output_tokens ?? 0), list(c.masked), list(c.tool_calls), c.prompt_sha256 ?? "", c.run_id ?? ""])]
      .map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "ai-calls.csv"; document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + recent.length + " AI call(s).");
  };
  const tiles: [string, string][] = [[n(t.calls), "AI calls"], [n((t.input_tokens ?? 0) + (t.output_tokens ?? 0)), "Tokens"], [n(t.masked_calls), "Calls with personal data masked"],
    [n(t.tool_calls), "Calls where the AI used tools"], [t.avg_latency_ms ? (Number(t.avg_latency_ms) / 1000).toFixed(1) + " s" : "—", "Average answer time"]];
  return (
    <section className="st-page cc-home" aria-labelledby="cc-gov-title">
      <header className="st-page-head">
        <div><h1 id="cc-gov-title">AI governance</h1><p>Every AI model call made by agents: which model, how many tokens, what was masked and which tools the AI used. Prompts are never stored, only a fingerprint.</p></div>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <Select aria-label="Period" value={String(days)} onChange={(e) => setDays(Number(e.target.value))} options={[["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]].map(([value, label]) => ({ value, label }))} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      {!g && !error && <p className="st-muted" role="status">Loading…</p>}
      {g && <>
        <div className="cc-tiles" role="list" aria-label="Figures">{tiles.map(([v, l]) => <div key={l} className="cc-tile" role="listitem"><strong>{v}</strong><span>{l}</span></div>)}</div>
        <div className="cc-grid2">
          <div><h2 className="cc-log-title">By model</h2>
            <table className="st-table" aria-label="By model"><thead><tr><th scope="col">Provider</th><th scope="col">Model</th><th scope="col">Calls</th><th scope="col">Tokens in</th><th scope="col">Tokens out</th></tr></thead>
              <tbody>{(g.by_model ?? []).map((x) => <tr key={x.provider + x.model}><td>{x.provider}</td><td>{x.model ?? ""}</td><td>{n(x.calls)}</td><td>{n(x.input_tokens)}</td><td>{n(x.output_tokens)}</td></tr>)}
                {!(g.by_model ?? []).length && <tr><td colSpan={5} className="st-muted">No calls.</td></tr>}</tbody></table></div>
          <div><h2 className="cc-log-title">By agent</h2>
            <table className="st-table" aria-label="By agent"><thead><tr><th scope="col">Agent</th><th scope="col">Calls</th><th scope="col">Tokens</th></tr></thead>
              <tbody>{(g.by_agent ?? []).map((x) => <tr key={x.bot_path}><td>{agentName(x.bot_path)}</td><td>{n(x.calls)}</td><td>{n(x.tokens)}</td></tr>)}
                {!(g.by_agent ?? []).length && <tr><td colSpan={3} className="st-muted">No calls.</td></tr>}</tbody></table></div>
        </div>
        <h2 className="cc-log-title">Recent AI calls</h2>
        <div className="cc-bar" role="toolbar" aria-label="Call actions">
          <TextInput type="search" aria-label="Search calls" placeholder="Search agent, user, purpose, model" value={q} onChange={(e) => setQ(e.target.value)} />
          <span className="st-bar-fill" /><Button kind="secondary" onClick={exportCsv}>Export CSV</Button>
        </div>
        <div className="cc-table-wrap">
          <table className="st-table cc-table" aria-label="Recent AI calls">
            <thead><tr><th scope="col">Time</th><th scope="col">Agent</th><th scope="col">User</th><th scope="col">Purpose</th><th scope="col">Model</th><th scope="col">Tokens</th><th scope="col">Masked</th><th scope="col">Tools used</th><th scope="col">Fingerprint</th></tr></thead>
            <tbody>
              {recent.map((c, i) => <tr key={i} className="is-static"><td>{new Date(c.ts).toLocaleString()}</td><td>{agentName(c.bot_path)}</td><td>{c.requested_by ?? ""}</td><td>{c.purpose ?? ""}</td>
                <td>{(c.provider ?? "") + "/" + (c.model ?? "")}</td><td>{n((c.input_tokens ?? 0) + (c.output_tokens ?? 0))}</td><td>{list(c.masked) || "—"}</td><td className="ui-mono">{list(c.tool_calls) || "—"}</td>
                <td className="ui-mono" title={c.prompt_sha256}>{(c.prompt_sha256 ?? "").slice(0, 12)}</td></tr>)}
              {!recent.length && <tr><td colSpan={9} className="st-muted">No AI calls in this period.</td></tr>}
            </tbody>
          </table>
        </div>
      </>}
    </section>
  );
}
