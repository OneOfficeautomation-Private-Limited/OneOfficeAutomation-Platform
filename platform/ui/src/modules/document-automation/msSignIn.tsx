import { useEffect, useRef, useState } from "react";
import { Button, Dialog } from "../../components";
import type { DocFlowApi, MsSignInStart } from "./api";
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
        <span className="df-small">Sign in with Microsoft is not set up on this server: an administrator adds the Microsoft app ID in Administration &gt; Settings &gt; Document Automation. Or use your own Microsoft app under Show advanced settings.</span>
      )}
      {open && api && <SignInDialog api={api} onClose={() => setOpen(false)} onDone={(id, who) => { setOpen(false); onSignedIn(id, who); }} />}
    </div>
  );
}

/** The code to type at Microsoft, and waiting until the person has signed in there. */
function SignInDialog({ api, onClose, onDone }: { api: DocFlowApi; onClose: () => void; onDone: (id: string, account: string) => void }) {
  const [start, setStart] = useState<MsSignInStart | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const done = useRef(onDone);
  done.current = onDone;
  useEffect(() => {
    let live = true, timer: ReturnType<typeof setTimeout> | undefined;
    setStart(null); setProblem(null); setCopied(false);
    const say = (e: unknown) => { if (live) setProblem(e instanceof Error ? e.message : String(e)); };
    api.msSignInStart().then((s) => {
      if (!live) return;
      setStart(s);
      const ask = () => {
        timer = setTimeout(() => {
          api.msSignInPoll(s.id).then((r) => {
            if (!live) return;
            if (r.status === "signed_in") done.current(s.id, r.account);
            else if (r.status === "failed") setProblem(r.message);
            else ask();
          }, say);
        }, Math.max(1, s.interval) * 1000);
      };
      ask();
    }, say);
    return () => { live = false; if (timer) clearTimeout(timer); };
  }, [api, attempt]);
  const copy = async () => {
    if (!start) return;
    try { await navigator.clipboard.writeText(start.userCode); setCopied(true); } catch { setCopied(false); }
  };
  return (
    <Dialog open onClose={onClose} title="Sign in with Microsoft" width={520}
      footer={<>{problem && <Button onClick={() => setAttempt(attempt + 1)}>Try again</Button>}<Button onClick={onClose}>Cancel</Button></>}>
      {!start && !problem && <p style={{ margin: 0 }} role="status">Asking Microsoft for a code…</p>}
      {start && (
        <ol className="df-ms-steps">
          <li>Open <a href={start.verificationUri} target="_blank" rel="noopener noreferrer">{start.verificationUri.replace(/^https?:\/\//, "")}</a> in your browser.</li>
          <li>Enter this code: <span className="df-ms-code" aria-label="Code">{start.userCode}</span>{" "}
            <button type="button" className="df-link" onClick={copy}>{copied ? "Copied" : "Copy code"}</button></li>
          <li>Sign in with the mailbox's account. If Microsoft asks for an administrator's approval, your Microsoft 365 administrator approves One CommandCenter once.</li>
        </ol>
      )}
      {start && !problem && <p className="df-small" role="status" style={{ margin: 0 }}>Waiting for you to sign in at Microsoft… The code works for {Math.round(start.expiresIn / 60)} minutes.</p>}
      {problem && <p className="df-text-bad" role="alert" style={{ margin: 0 }}>{problem}</p>}
    </Dialog>
  );
}
