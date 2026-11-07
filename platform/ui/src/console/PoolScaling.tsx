import { useEffect, useState } from "react";
import type { CrApi, PoolScaling, PoolScalingNow, RunnerPool } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * A runner pool's auto-scaling (G52): how many machines it may have and where One CommandCenter sends the size it needs
 * (a webhook, an AWS Auto Scaling group or an Azure scale set). Secrets are vault references. Shows what it would ask
 * for now, and why.
 */
const PROVIDERS = [{ value: "webhook", label: "A webhook (any cloud or tool)" }, { value: "aws_asg", label: "AWS Auto Scaling group" }, { value: "azure_vmss", label: "Azure virtual machine scale set" }];
const FIELDS: Record<string, [string, string, string][]> = {
  webhook: [["url", "Webhook address", "https://scale.example.com/ooa"], ["secret_ref", "Signing secret (vault reference, optional)", "ops/scaling/secret"]],
  aws_asg: [["group", "Auto Scaling group", "ooa-runners"], ["region", "Region", "eu-west-1"], ["access_key_ref", "Access key id (vault reference)", "aws/scaling/key_id"],
    ["secret_key_ref", "Secret access key (vault reference)", "aws/scaling/secret"]],
  azure_vmss: [["subscription", "Subscription id", ""], ["resource_group", "Resource group", ""], ["scale_set", "Scale set", "ooa-runners"], ["tenant", "Tenant id", ""],
    ["client_id", "App (client) id", ""], ["client_secret_ref", "Client secret (vault reference)", "azure/scaling/secret"]],
};

export function PoolScalingDialog({ api, pool, onClose, onSaved }: { api: CrApi; pool: RunnerPool; onClose: () => void; onSaved: (t: string) => void }) {
  const [s, setS] = useState<PoolScaling | null>(null);
  const [now, setNow] = useState<PoolScalingNow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fail = (e: unknown) => setError(e instanceof Error ? e.message : String(e));
  useEffect(() => { api.poolScaling(pool.id).then((r) => { setS({ min: 0, max: 2, runs_per_runner: 1, cooldown_minutes: 5, provider: "webhook", ...r.scaling }); setNow(r.now); }, fail); }, [api, pool.id]);
  if (!s) return null;
  const put = (k: string, v: unknown) => setS({ ...s, [k]: v });
  const save = () => api.savePoolScaling(pool.id, s).then((r) => { setNow(r.now); onSaved("Saved the auto-scaling of " + pool.name + "."); }, fail);
  const n = (k: "min" | "max" | "runs_per_runner" | "cooldown_minutes", label: string, hint: string) =>
    <Field label={label} hint={hint}>{(id) => <TextInput id={id} type="number" value={String(s[k] ?? "")} onChange={(e) => put(k, e.target.value === "" ? "" : Number(e.target.value))} />}</Field>;
  return (
    <Dialog open title={"Auto-scaling: " + pool.name} width={560} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Close</Button><span className="st-bar-fill" /><Button kind="primary" onClick={save}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <label className="st-check-line"><input type="checkbox" checked={!!s.enabled} onChange={(e) => put("enabled", e.target.checked)} /> Scale this pool by its work</label>
      <div className="st-grid2">
        {n("min", "Fewest machines", "0 lets the pool scale to nothing.")}
        {n("max", "Most machines", "Up to 500.")}
        {n("runs_per_runner", "Runs per machine", "Waiting and running runs one machine takes.")}
        {n("cooldown_minutes", "Minutes between changes", "1 to 1440.")}
      </div>
      <Field label="Where the size goes">{(id) => <Select id={id} value={s.provider ?? "webhook"} onChange={(e) => put("provider", e.target.value)} options={PROVIDERS} />}</Field>
      {(FIELDS[s.provider ?? "webhook"] ?? []).map(([k, label, ph]) =>
        <Field key={k} label={label}>{(id) => <TextInput id={id} value={String((s as Record<string, unknown>)[k] ?? "")} placeholder={ph} onChange={(e) => put(k, e.target.value)} />}</Field>)}
      {now && <p className="st-muted" role="status">Now: {now.queued} waiting, {now.running} running, {now.refused_recently} refused for lack of a runner in the last 10 minutes,
        {" "}{now.online} online. It would ask for <strong>{now.desired}</strong> machine{now.desired === 1 ? "" : "s"}.</p>}
      <p className="st-muted">Machines that start must register as runners of this pool (a temporary runner key in their start-up script). A webhook gets
        {" "}<code>{"{\"pool\", \"desired\", \"at\"}"}</code>, signed in <code>X-OOA-Signature</code> when a secret is set.</p>
    </Dialog>
  );
}
