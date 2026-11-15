import { useEffect, useState } from "react";
import { Button, MicrosoftSignInDialog } from "../../components";
import type { DocFlowApi } from "./api";
import { Chip } from "./ui";

/**
 * DA-1: "Sign in with Microsoft" for a process's Microsoft 365 email inbox. The person signs in at Microsoft with a
 * short code (no app IDs or secrets to type); the server keeps the sign-in and reads the mailbox with it. Shows who is
 * signed in, or the button, or why it is not offered.
 */
export function MicrosoftAccount({ api, account, signedIn, onSignedIn, onSignOut }:
  { api?: DocFlowApi; account?: string; signedIn: boolean; onSignedIn: (id: string, account: string) => void; onSignOut: () => void }) {
  const [ready, setReady] = useState<boolean | null>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let live = true;
    if (!api) { setReady(false); return; }
    api.msSignInReady().then((r) => { if (live) setReady(r); }, () => { if (live) setReady(false); });
    return () => { live = false; };
  }, [api]);
  return (
    <div className="df-ms-account" role="group" aria-label="Microsoft account">
      <b>Microsoft account</b>
      {signedIn ? (
        <div className="df-head-row" style={{ gap: 8 }}>
          <Chip tone="ok">Signed in as {account || "a Microsoft account"}</Chip>
          <button type="button" className="df-link" onClick={onSignOut}>Sign out</button>
        </div>
      ) : ready === null ? <span className="df-small">Checking…</span> : ready ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 4, alignItems: "flex-start" }}>
          <Button kind="primary" onClick={() => setOpen(true)}>Sign in with Microsoft</Button>
          <span className="df-small">Sign in with the mailbox's account, or with one that may read the shared mailbox. Your Microsoft 365 administrator approves One CommandCenter once.</span>
        </div>
      ) : (
        <span className="df-small">Sign in with Microsoft is not set up on this server: an administrator adds the Microsoft app ID in Administration &gt; Settings &gt; E-mail, proxy and SIEM. Or use your own Microsoft app under Show advanced settings.</span>
      )}
      {open && api && <MicrosoftSignInDialog start={() => api.msSignInStart()} poll={(id) => api.msSignInPoll(id)} onClose={() => setOpen(false)}
        onDone={(id, who) => { setOpen(false); onSignedIn(id, who); }} />}
    </div>
  );
}
