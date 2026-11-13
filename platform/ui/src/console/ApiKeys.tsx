import { useEffect, useState } from "react";
import type { ApiKey, CrApi } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

const errorTextOf = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * Your API keys (Your account menu), moved from the classic console on 13 Nov 2026 (improvement list item 8): keys let
 * other systems call One CommandCenter as you (Authorization: Bearer <key>), with your role's permissions. A new key is
 * shown once; a key can be revoked.
 */
export function ApiKeysDialog({ api, onClose }: { api: CrApi; onClose: () => void }) {
  const [keys, setKeys] = useState<ApiKey[] | null>(null);
  const [name, setName] = useState("");
  const [days, setDays] = useState("90");
  const [made, setMade] = useState<{ name: string; key: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => api.apiKeys().then((k) => { setKeys(k); setError(null); }, (e) => setError(errorTextOf(e)));
  useEffect(() => { load(); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  const create = () => api.createApiKey(name.trim(), Number(days)).then((k) => { setMade({ name: k.name || name.trim(), key: k.key }); setName(""); load(); }, (e) => setError(errorTextOf(e)));
  const revoke = (k: ApiKey) => { if (confirm("Revoke key " + k.name + "? Systems using it stop working.")) api.revokeApiKey(k.id).then(load, (e) => setError(errorTextOf(e))); };
  const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "never";
  return (
    <Dialog open title="Your API keys" width={640} onClose={onClose} footer={<Button kind="secondary" onClick={onClose}>Close</Button>}>
      <p className="st-muted">API keys let other systems call One CommandCenter as you (Authorization: Bearer &lt;key&gt;), with your role's permissions. A key is shown only once.</p>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {made && <div className="cc-key-made" role="status"><strong>{made.name}</strong>: copy it now and keep it in your system's secret store; it is not shown again.
        <code className="cc-key">{made.key}</code></div>}
      <table className="cc-list-table" aria-label="Your API keys">
        <thead><tr><th scope="col">Name</th><th scope="col">Created</th><th scope="col">Expires</th><th scope="col">Last used</th><th scope="col"><span className="st-sr">Revoke</span></th></tr></thead>
        <tbody>{keys === null ? <tr><td colSpan={5} className="cc-none" role="status">Loading…</td></tr>
          : !keys.length ? <tr><td colSpan={5} className="cc-none">No API keys yet.</td></tr>
          : keys.map((k) => <tr key={k.id}><td>{k.name}</td><td>{when(k.created)}</td><td>{k.expires ? when(k.expires) : "never"}</td><td>{when(k.last_used)}</td>
            <td><Button kind="plain" onClick={() => revoke(k)}>Revoke</Button></td></tr>)}</tbody>
      </table>
      <h3 className="cc-key-new">New key</h3>
      <div className="cc-key-form">
        <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="Nightly ERP sync" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Valid for">{(id) => <Select id={id} value={days} onChange={(e) => setDays(e.target.value)}
          options={[{ value: "30", label: "30 days" }, { value: "90", label: "90 days" }, { value: "365", label: "1 year" }, { value: "0", label: "Never expires" }]} />}</Field>
        <Button kind="primary" disabled={!name.trim()} onClick={create}>Create key</Button>
      </div>
    </Dialog>
  );
}
