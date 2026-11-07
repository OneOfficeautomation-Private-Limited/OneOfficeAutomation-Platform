import { useEffect, useState } from "react";
import { ApiFailure } from "../api/client";
import type { CrApi, PushStatus } from "../api/cr";
import { Button, Dialog } from "../components";

/**
 * Phone notifications (G43): new tasks for you arrive on your phone through the ntfy app, with Approve and Reject when
 * the administrator turned approval links on. Turning it on makes a private topic; turning it on again makes a new one,
 * and the old one stops working (for a lost phone).
 */
export function PhoneNotificationsDialog({ api, onClose }: { api: CrApi; onClose: () => void }) {
  const [st, setSt] = useState<PushStatus | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fail = (e: unknown) => setError(e instanceof ApiFailure ? e.error.message + (e.error.hint ? " " + e.error.hint : "") : String(e));
  useEffect(() => { api.pushStatus().then(setSt, fail); }, [api]);
  const act = (p: Promise<PushStatus | void>, done: string) => { setError(null); setMsg(null); p.then((r) => { if (r) setSt(r); else api.pushStatus().then(setSt, fail); setMsg(done); }, fail); };
  return (
    <Dialog open onClose={onClose} title="Phone notifications" width={560}
      footer={<><Button kind="secondary" onClick={onClose}>Close</Button><span className="st-bar-fill" />
        {st?.available && !st.enabled && <Button kind="primary" onClick={() => act(api.pushEnable(), "Phone notifications are on.")}>Turn on</Button>}
        {st?.enabled && <Button kind="secondary" onClick={() => act(api.pushTest(), "A test notification is on its way.")}>Send a test</Button>}
        {st?.enabled && <Button kind="secondary" onClick={() => act(api.pushEnable(), "You have a new private topic; subscribe to it again on your phone.")}>New topic</Button>}
        {st?.enabled && <Button kind="danger" onClick={() => act(api.pushDisable(), "Phone notifications are off.")}>Turn off</Button>}</>}>
      {!st ? <p className="st-muted">Reading…</p>
        : !st.available ? <p>Phone notifications are not set up on this One CommandCenter. An administrator sets the push server in Settings.</p>
        : !st.enabled ? <p>When a workflow gives you a form or an approval, your phone tells you, with Approve and Reject buttons when your
            administrator allows approving from outside the Task inbox. You need the free <strong>ntfy</strong> app on the phone.</p>
        : <>
            <p>New tasks for you go to your own private topic. On your phone, open the <strong>ntfy</strong> app, choose <strong>Subscribe to topic</strong>,
              tick <strong>Use another server</strong> when it is not ntfy.sh, and enter:</p>
            <dl className="cc-push-facts">
              <dt>Server</dt><dd><code>{st.server}</code></dd>
              <dt>Topic</dt><dd><code>{st.topic}</code></dd>
            </dl>
            <p className="st-muted">Keep the topic to yourself: anyone who knows it can read your task notices. If your phone is lost, press
              New topic; the old one stops working.</p>
          </>}
      {msg && <p role="status">{msg}</p>}
      {error && <p className="st-error" role="alert">{error}</p>}
    </Dialog>
  );
}
