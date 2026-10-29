import { useState } from "react";
import { ApiFailure, type ModelConnection, type ModelConnections, type StudioApi } from "../api/client";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { oneStep, runOnce } from "./runOnce";

/** The AI services a connection can use, in plain words, with what each needs (approved design V6.5). */
const SERVICES: { value: string; label: string; needsAddress: boolean; address?: string; models: string[] }[] = [
  { value: "openai", label: "OpenAI", needsAddress: false, models: ["gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"] },
  { value: "azure_openai", label: "Azure OpenAI", needsAddress: true, address: "https://YOUR-RESOURCE.openai.azure.com", models: [] },
  { value: "anthropic", label: "Anthropic", needsAddress: false, models: ["claude-sonnet-4-5", "claude-haiku-4-5"] },
  { value: "aws_bedrock", label: "Amazon Bedrock", needsAddress: false, models: ["anthropic.claude-sonnet-4-5", "amazon.nova-pro-v1:0"] },
  { value: "google_vertex", label: "Google Vertex AI", needsAddress: true, address: "https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION", models: ["gemini-2.5-flash"] },
  { value: "openai_compatible", label: "Other compatible service (LiteLLM, vLLM, Ollama)", needsAddress: true, address: "http://localhost:11434/v1", models: [] },
  { value: "huggingface", label: "Hugging Face", needsAddress: false, models: [] },
  { value: "aws_bedrock_sagemaker", label: "Amazon SageMaker endpoint", needsAddress: true, address: "https://runtime.sagemaker.REGION.amazonaws.com", models: [] },
];
const STEPS = ["Service", "Model and address", "Sign-in", "Test and save"];
const NAME = /^[A-Za-z0-9_.-]{1,60}$/;

/**
 * A new model connection in four short steps: which service, which model (and address when the service needs one),
 * where the key comes from (a vault reference or an environment variable, never the key itself), then save and test.
 * The test sends one tiny prompt through the saved connection, on this computer.
 */
export function ConnectionWizard({ api, existing, onClose, onSaved }:
  { api: StudioApi; existing: ModelConnections; onClose: () => void; onSaved: (c: ModelConnections, name: string) => void }) {
  const [step, setStep] = useState(0);
  const [c, setC] = useState<ModelConnection>({ name: "", provider: "openai", model: "gpt-4o-mini" });
  const [keyFrom, setKeyFrom] = useState<"vault" | "env">("vault");
  const [keyRef, setKeyRef] = useState("");
  const [asDefault, setAsDefault] = useState(!existing.default);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [test, setTest] = useState<{ busy: boolean; text?: string; ok?: boolean } | null>(null);
  const service = SERVICES.find((s) => s.value === c.provider)!;
  const set = (k: keyof ModelConnection, v: string) => setC({ ...c, [k]: v });

  const problem = (): string | null => {
    if (step === 0 && !NAME.test(c.name.trim())) return "Give it a name with letters, digits, . _ or - (no spaces).";
    if (step === 0 && existing.connections.some((x) => x.name === c.name.trim())) return "There is already a connection called " + c.name.trim() + ".";
    if (step === 1 && !c.model?.trim()) return "Choose or type the model.";
    if (step === 1 && service.needsAddress && !c.base_url?.trim()) return "This service needs its address.";
    if (step === 2 && !keyRef.trim() && c.provider !== "openai_compatible") return keyFrom === "vault" ? "Give the vault reference of the key." : "Give the environment variable's name.";
    return null;
  };
  const next = () => { const p = problem(); setError(p); if (!p) setStep(step + 1); };
  const save = async () => {
    const conn: ModelConnection = { name: c.name.trim(), provider: c.provider };
    if (c.model?.trim()) conn.model = c.model.trim();
    if (c.base_url?.trim()) conn.base_url = c.base_url.trim();
    if (keyRef.trim()) { if (keyFrom === "vault") conn.api_key = { $credential: keyRef.trim() }; else conn.api_key_env = keyRef.trim(); }
    if (c.description?.trim()) conn.description = c.description.trim();
    try {
      const out = await api.saveModelConnections({ default: asDefault ? conn.name : existing.default, connections: [...existing.connections, conn] });
      setSaved(true); setError(null); onSaved(out, conn.name);
    } catch (e) { setError(e instanceof ApiFailure ? e.error.message + (e.error.hint ? " " + e.error.hint : "") : String(e)); }
  };
  const runTest = async () => {
    setTest({ busy: true });
    try {
      const r = await runOnce(api, oneStep("ooa.genai/prompt", { prompt: "Reply with the single word OK.", connection: c.name.trim(), max_tokens: 5 }, { text: "answer" }));
      setTest(r.status === "success" ? { busy: false, ok: true, text: "Connected. The model answered: " + String(r.outputs?.answer ?? "").trim() }
        : { busy: false, ok: false, text: "The test failed: " + (r.error?.message ?? r.status) + (r.error?.suggestedFix ? " " + r.error.suggestedFix : "") });
    } catch (e) { setTest({ busy: false, ok: false, text: "The test failed: " + (e instanceof ApiFailure ? e.error.message : String(e)) }); }
  };

  return (
    <Dialog open onClose={onClose} title="New model connection" width={720}
      footer={<>
        <Button kind="secondary" onClick={onClose}>{saved ? "Close" : "Cancel"}</Button>
        <span className="st-bar-fill" />
        {step > 0 && !saved && <Button kind="secondary" onClick={() => { setError(null); setStep(step - 1); }}>Back</Button>}
        {step < 3 && <Button kind="primary" onClick={next}>Next</Button>}
        {step === 3 && !saved && <Button kind="primary" onClick={save}>Save connection</Button>}
        {step === 3 && saved && <Button kind="run" icon="play" onClick={runTest} disabled={test?.busy}>{test?.busy ? "Testing…" : "Test it"}</Button>}
      </>}>
      <ol className="st-wizard-steps" aria-label="Steps">
        {STEPS.map((label, i) => <li key={label} aria-current={i === step ? "step" : undefined} className={i === step ? "is-current" : i < step ? "is-done" : ""}>
          <span className="st-wizard-n">{i < step ? "✓" : i + 1}</span>{label}</li>)}
      </ol>
      <form className="st-form" onSubmit={(e) => { e.preventDefault(); if (step < 3) next(); }}>
        {step === 0 && <>
          <Field label="AI service">{(id) => <Select id={id} value={c.provider} onChange={(e) => {
            const s = SERVICES.find((x) => x.value === e.target.value)!;
            setC({ ...c, provider: s.value, model: s.models[0] ?? "", base_url: "" });
          }} options={SERVICES.map((s) => ({ value: s.value, label: s.label }))} />}</Field>
          <Field label="Name" hint="Agents use the connection by this name.">{(id) => <TextInput id={id} value={c.name} placeholder="company-gpt" onChange={(e) => set("name", e.target.value)} />}</Field>
          <Field label="Description">{(id) => <TextInput id={id} value={c.description ?? ""} placeholder="Optional" onChange={(e) => set("description", e.target.value)} />}</Field>
        </>}
        {step === 1 && <>
          <Field label="Model" hint={service.models.length ? "Pick one, or type another." : "Type the model, deployment or endpoint name."}>{(id) => <>
            <TextInput id={id} list="st-models" value={c.model ?? ""} onChange={(e) => set("model", e.target.value)} />
            <datalist id="st-models">{service.models.map((m) => <option key={m} value={m} />)}</datalist>
          </>}</Field>
          <Field label={"Service address" + (service.needsAddress ? "" : " (optional)")} hint={service.needsAddress ? "This service needs it." : "Only for a private or regional endpoint."}>{(id) =>
            <TextInput id={id} value={c.base_url ?? ""} placeholder={service.address ?? ""} onChange={(e) => set("base_url", e.target.value)} />}</Field>
        </>}
        {step === 2 && <>
          <Field label="The key comes from">{(id) => <Select id={id} value={keyFrom} onChange={(e) => setKeyFrom(e.target.value as "vault" | "env")}
            options={[{ value: "vault", label: "The vault (recommended)" }, { value: "env", label: "An environment variable on this computer" }]} />}</Field>
          <Field label={keyFrom === "vault" ? "Vault reference" : "Environment variable"}
            hint={keyFrom === "vault" ? "folder/credential/attribute, for example ai/openai/api_key. The key itself never goes in the file." : "For example OPENAI_API_KEY."}>{(id) =>
            <TextInput id={id} mono value={keyRef} onChange={(e) => setKeyRef(e.target.value)} />}</Field>
        </>}
        {step === 3 && <>
          <dl className="st-summary">
            <dt>Name</dt><dd>{c.name}</dd><dt>Service</dt><dd>{service.label}</dd><dt>Model</dt><dd>{c.model}</dd>
            {c.base_url && <><dt>Address</dt><dd>{c.base_url}</dd></>}
            <dt>Key</dt><dd>{keyRef ? (keyFrom === "vault" ? "Vault: " : "Environment: ") + keyRef : "None"}</dd>
          </dl>
          <label className="st-check-line"><input type="checkbox" checked={asDefault} disabled={saved} onChange={(e) => setAsDefault(e.target.checked)} /> Use it by default when an agent names no connection</label>
          {saved && <p role="status">Saved. Test it now: it sends one short prompt through this connection.</p>}
          {test?.text && <p role="status" className={test.ok ? "st-ok" : "st-error"}>{test.text}</p>}
        </>}
        {error && <p role="alert" className="st-error">{error}</p>}
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}
