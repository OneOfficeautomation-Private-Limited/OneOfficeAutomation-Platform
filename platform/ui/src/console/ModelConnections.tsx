import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, ModelConnection } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * AI > Model Connections, built as AA's Model connections (checked against AA's docs and UiPath's AI Trust Layer, 31 Oct
 * 2026): named connections to an AI model that agents use. Each shows its provider and model, the roles that may use
 * it, its key's vault reference, tokens used this month against the budget (UiPath-style limits), and its privacy
 * settings. Create in sections: model, key and access, privacy and safety, budget; Test connection before saving.
 */
const PROVIDERS: [string, string][] = [["openai", "OpenAI"], ["azure_openai", "Azure OpenAI"], ["anthropic", "Anthropic"], ["aws_bedrock", "Amazon Bedrock"], ["google_vertex", "Google Vertex AI"],
  ["aws_bedrock_sagemaker", "Amazon SageMaker endpoint"], ["huggingface", "Hugging Face"], ["openai_compatible", "OpenAI-compatible (local or hosted)"]];
const HELP: Record<string, string> = {
  aws_bedrock: "Model id, for example anthropic.claude-sonnet-4-5 or amazon.nova-pro-v1:0. Endpoint: https://bedrock-runtime.REGION.amazonaws.com. Key: ACCESS_KEY_ID:SECRET in one credential.",
  aws_bedrock_sagemaker: "Model: the SageMaker endpoint name. Endpoint: https://runtime.sagemaker.REGION.amazonaws.com. Key: ACCESS_KEY_ID:SECRET in one credential.",
  google_vertex: "Model, for example gemini-2.5-flash. Endpoint: https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION. Key: a service-account JSON key or an access token.",
  azure_openai: "Model: the deployment name. Endpoint: your resource, for example https://myresource.openai.azure.com.",
  huggingface: "Model id on the Hugging Face router, or leave the endpoint set to your own TGI server.",
};
const providerName = (p: string) => PROVIDERS.find(([k]) => k === p)?.[1] ?? p;
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const pct = (c: ModelConnection) => c.monthly_tokens ? Math.min(100, Math.round(100 * (c.tokens_this_month ?? 0) / c.monthly_tokens)) : 0;

export function ModelConnections({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<ModelConnection[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [provider, setProvider] = useState("");
  const [dialog, setDialog] = useState<null | { c: ModelConnection | null }>(null);
  const manage = user.permissions.includes("connections.manage");
  const load = useCallback(async () => { try { setList(await api.modelConnections()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((c) => (!provider || c.provider === provider)
    && (!q.trim() || (c.name + " " + (c.model ?? "") + " " + (c.description ?? "")).toLowerCase().includes(q.trim().toLowerCase()))), [list, q, provider]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-mc-title">
      <header className="st-page-head">
        <div><h1 id="cc-mc-title">Model Connections</h1><p>Named connections to AI models that agents use through prompt skills, AI agents and generative AI actions. Keys stay in the Credential vault; agents receive only the reference.</p></div>
        {manage && <Button kind="primary" onClick={() => setDialog({ c: null })}>Create connection</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search model connections" placeholder="Search connections and models" value={q} onChange={(e) => setQ(e.target.value)} />
        <Select aria-label="Provider" value={provider} onChange={(e) => setProvider(e.target.value)} options={[{ value: "", label: "Every provider" }, ...PROVIDERS.map(([value, label]) => ({ value, label }))]} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Model connections list">
          <thead><tr><th scope="col">Connection</th><th scope="col">Provider</th><th scope="col">Model</th><th scope="col">Roles</th><th scope="col">Tokens this month</th><th scope="col">Privacy</th><th scope="col">Key</th><th scope="col">Updated</th></tr></thead>
          <tbody>
            {shown.map((c) => (
              <tr key={c.name} onClick={() => manage && setDialog({ c })} className={manage ? undefined : "is-static"}>
                <td>{c.name}{c.description && <div className="st-muted">{c.description}</div>}</td>
                <td>{providerName(c.provider)}</td>
                <td>{c.model ?? ""}{c.model_type === "fine_tuned" && <span className="cc-tag">fine-tuned</span>}</td>
                <td>{(c.allowed_roles ?? []).join(", ") || "Every role"}</td>
                <td>{(c.tokens_this_month ?? 0).toLocaleString()}{c.monthly_tokens ? " of " + c.monthly_tokens.toLocaleString() : ""}
                  {c.monthly_tokens ? <span className="cc-meter" role="img" aria-label={pct(c) + "% of the budget"}><span className={pct(c) >= 100 ? "is-full" : pct(c) >= 80 ? "is-high" : ""} style={{ width: pct(c) + "%" }} /></span> : null}</td>
                <td>{[c.pii_analyzer_url ? "Masks personal data" : "", c.content_check === "moderation" ? "Checks answers" : ""].filter(Boolean).join(", ") || "None"}</td>
                <td className="ui-mono">{c.key_ref ?? "None"}</td>
                <td>{when(c.updated)}{c.updated_by ? " by " + c.updated_by : ""}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={8} className="st-muted">{list.length ? "No connections match." : "No model connections yet."}</td></tr>}
            {!list && !error && <tr><td colSpan={8} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog && <ConnectionForm key={dialog.c?.name ?? "new"} api={api} c={dialog.c} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
    </section>
  );
}

/** A model connection in sections: model, key and access, privacy and safety, budget; tested before saving. */
function ConnectionForm({ api, c, onClose, onSaved }: { api: CrApi; c: ModelConnection | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: c?.name ?? "", provider: c?.provider ?? "openai", type: c?.model_type ?? "standard", model: c?.model ?? "", base: c?.base_url ?? "", key: c?.key_ref ?? "",
    description: c?.description ?? "", roles: (c?.allowed_roles ?? []).join(", "), monthly: c?.monthly_tokens ? String(c.monthly_tokens) : "", perMinute: c?.calls_per_minute ? String(c.calls_per_minute) : "",
    perRun: c?.calls_per_run ? String(c.calls_per_run) : "", analyzer: c?.pii_analyzer_url ?? "", check: c?.content_check === "moderation" ? "moderation" : "none" });
  const [test, setTest] = useState<{ ok: boolean; text: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const body = () => ({ provider: v.provider, model_type: v.type, model: v.model.trim() || null, base_url: v.base.trim() || null, key_ref: v.key.trim() || null, description: v.description,
    allowed_roles: v.roles.split(",").map((x) => x.trim()).filter(Boolean), monthly_tokens: Number(v.monthly || 0), calls_per_minute: Number(v.perMinute || 0), calls_per_run: Number(v.perRun || 0),
    pii_analyzer_url: v.analyzer.trim() || null, content_check: v.check });
  return (
    <Dialog open title={c ? "Model connection " + c.name : "Create model connection"} width={760} onClose={onClose} footer={<>
      {c && <Button kind="danger" onClick={() => setConfirm(true)}>Delete</Button>}
      <Button kind="secondary" onClick={() => { setTest({ ok: true, text: "Testing…" }); api.testModelConnection({ name: v.name.trim(), ...body() })
        .then((r) => setTest({ ok: r.ok, text: r.ok ? "Connected in " + r.ms + " ms" + (r.answer ? ": " + r.answer : "") : r.message ?? "Failed" }), (e) => setTest({ ok: false, text: message(e) })); }}>Test connection</Button>
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!v.name.trim()} onClick={() => api.saveModelConnection(v.name.trim(), body()).then(() => onSaved("Saved the model connection " + v.name.trim() + "."), (e) => setError(message(e)))}>
        {c ? "Save changes" : "Create connection"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {test && <p className={test.ok ? "st-notice" : "st-alert"} role="status">{test.text}</p>}
      {confirm && <div className="st-alert" role="alert"><span>Delete {c!.name}? AI steps that use it fail until another is chosen.</span>
        <Button kind="danger" onClick={() => api.deleteModelConnection(c!.name).then(() => onSaved("Deleted the model connection " + c!.name + "."), (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <h3 className="cc-log-title">Model</h3>
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} disabled={!!c} placeholder="company-gpt" onChange={set("name")} />}</Field>
        <Field label="Provider">{(id) => <Select id={id} value={v.provider} onChange={set("provider")} options={PROVIDERS.map(([value, label]) => ({ value, label }))} />}</Field>
        <Field label="Model or deployment">{(id) => <TextInput id={id} value={v.model} onChange={set("model")} />}</Field>
        <Field label="Type">{(id) => <Select id={id} value={v.type} onChange={set("type")} options={[{ value: "standard", label: "Standard model" }, { value: "fine_tuned", label: "Fine-tuned model" }]} />}</Field>
      </div>
      {HELP[v.provider] && <p className="st-muted">{HELP[v.provider]}</p>}
      <Field label="Endpoint (optional)">{(id) => <TextInput id={id} value={v.base} placeholder="https://..." onChange={set("base")} />}</Field>
      <h3 className="cc-log-title">Key and access</h3>
      <div className="cc-grid2">
        <Field label="API key from the Credential vault (folder/credential/attribute)">{(id) => <TextInput id={id} value={v.key} placeholder="ai/openai/key" onChange={set("key")} />}</Field>
        <Field label="Roles that may use it (separated by commas; empty: every role)">{(id) => <TextInput id={id} value={v.roles} onChange={set("roles")} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} onChange={set("description")} />}</Field>
      <h3 className="cc-log-title">Privacy and safety</h3>
      <div className="cc-grid2">
        <Field label="Microsoft Presidio analyzer: masks names, places and ids before the model sees them">{(id) => <TextInput id={id} value={v.analyzer} placeholder="https://presidio-analyzer.internal" onChange={set("analyzer")} />}</Field>
        <Field label="Content check">{(id) => <Select id={id} value={v.check} onChange={set("check")} options={[{ value: "none", label: "No content check" }, { value: "moderation", label: "Check answers with the provider's moderation endpoint" }]} />}</Field>
      </div>
      <h3 className="cc-log-title">Budget and limits</h3>
      <div className="cc-grid2">
        <Field label={"Tokens a month (in and out)" + (c?.tokens_this_month ? "; " + c.tokens_this_month.toLocaleString() + " used this month" : "")}>{(id) => <TextInput id={id} type="number" min={0} value={v.monthly} placeholder="no limit" onChange={set("monthly")} />}</Field>
        <Field label="Calls a minute, per run (more calls wait)">{(id) => <TextInput id={id} type="number" min={0} value={v.perMinute} placeholder="no limit" onChange={set("perMinute")} />}</Field>
        <Field label="Calls a run (more calls fail)">{(id) => <TextInput id={id} type="number" min={0} value={v.perRun} placeholder="no limit" onChange={set("perRun")} />}</Field>
      </div>
      <p className="st-muted">When the month's tokens are used up, AI steps fail with AI.BUDGET_REACHED until the next month or a higher budget. Runners read the key from the vault only while an agent runs, and only for agents whose owner may use that vault folder.</p>
    </Dialog>
  );
}
