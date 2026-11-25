import { useCallback, useEffect, useState } from "react";
import type { CrApi } from "../../api/cr";
import { Button } from "../../components";
import "./consoleApi";
import type { OneChatModelCheck } from "./consoleApi";

const PILL: Record<string, [string, string]> = {
  compatible: ["is-success", "Built for this release"],
  catalog_unknown: ["is-disabled", "Catalog not stated"],
  built_for_another_release: ["is-failed", "Built for another release"],
  wrong_contract: ["is-failed", "Other contract"],
  unreachable: ["is-failed", "Not answering"],
};

/**
 * Settings > AI assistants > Studio assistant (S6, 25 Nov 2026): for each Model Connection to a OneChat model server,
 * whether its model was built from this release's action catalog and flow rules (GET /api/onechat/model-check).
 * Nothing shows when there is no such connection. Information only: Studio still checks every suggested step.
 */
export function OneChatModelStatus({ api }: { api: CrApi }) {
  const [r, setR] = useState<OneChatModelCheck | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => {
    setBusy(true);
    api.onechatModelCheck().then((x) => { setR(x); setError(""); }, (e) => setError(e instanceof Error ? e.message : String(e))).finally(() => setBusy(false));
  }, [api]);
  useEffect(() => { load(); }, [load]);
  if (!error && (!r || r.connections.length === 0)) return null;
  return <div className="cc-oc-models" role="status" aria-label="OneChat model servers">
    {error ? <div className="cc-oc-status"><span className="cc-oc-msg cc-ap-error">{error}</span></div>
      : r!.connections.map((c) => { const [cls, word] = PILL[c.status] ?? ["is-disabled", c.status];
        return <div className="cc-oc-status" key={c.connection}>
          <span className="cc-oc-engine">OneChat model: <strong>{c.connection}</strong>{c.version ? " " + c.version : ""}</span>
          <span className={"cc-pill " + cls}><span className="cc-pill-dot" aria-hidden="true" />{word}</span>
          <span className="cc-oc-msg">{c.message}</span>
        </div>; })}
    <div className="cc-oc-actions">
      {r && <span className="cc-oc-sep">This release: {r.catalog.entries} catalog entries</span>}
      <Button kind="plain" disabled={busy} onClick={load}>Check again</Button>
    </div>
  </div>;
}
