import { useCallback, useEffect, useState } from "react";
import type { CrApi } from "../../api/cr";
import { Button } from "../../components";
import "./consoleApi";
import type { OneChatVersion } from "./consoleApi";

const PILL: Record<OneChatVersion["status"], [string, string]> = {
  compatible: ["is-success", "Works with this release"],
  newer: ["is-success", "Newer, same contract"],
  older: ["is-failed", "Too old"],
  wrong_contract: ["is-failed", "Other contract"],
  unreachable: ["is-failed", "Not answering"],
  not_set_up: ["is-disabled", "Not set up"],
};

/**
 * Settings > AI assistants > One Chat (S5, 20 Nov 2026): the OneChat service's version and whether this release works
 * with it (GET /api/onechat/version), under the tab's note. With the built-in engine chosen it says so, and still
 * shows the service's state, so an administrator can check it before switching.
 */
export function OneChatStatus({ api, engine }: { api: CrApi; engine: string }) {
  const [v, setV] = useState<OneChatVersion | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = useCallback((fresh: boolean) => {
    setBusy(true);
    api.onechatVersion(fresh).then((r) => { setV(r); setError(""); }, (e) => setError(e instanceof Error ? e.message : String(e))).finally(() => setBusy(false));
  }, [api]);
  useEffect(() => { load(true); }, [load]);
  const [cls, word] = v ? PILL[v.status] ?? ["is-disabled", v.status] : ["is-disabled", "Checking…"];
  return <div className="cc-oc-status" role="status" aria-label="OneChat service">
    <span className="cc-oc-engine">Engine: <strong>{engine === "service" ? "OneChat service" : "Built in"}</strong></span>
    <span className="cc-oc-sep" aria-hidden="true">·</span>
    <span>OneChat service{v?.version ? " " + v.version : ""}</span>
    <span className={"cc-pill " + cls}><span className="cc-pill-dot" aria-hidden="true" />{error ? "Unknown" : word}</span>
    <span className="cc-oc-msg">{error || (v ? v.message + (v.status === "compatible" || v.status === "newer" ? "" : engine === "service" ? " One Chat says so and runs nothing until it is fixed." : "") : "")}</span>
    <Button kind="plain" disabled={busy} onClick={() => load(true)}>Check again</Button>
  </div>;
}
