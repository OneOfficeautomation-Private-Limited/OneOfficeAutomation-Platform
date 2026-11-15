import { useEffect, useRef, useState } from "react";
import { Button } from "./Button";
import { Dialog } from "./Dialog";

/** What the server answers when a sign-in starts: the code the person types at Microsoft, where, and for how long. */
export interface MsSignInStart { id: string; userCode: string; verificationUri: string; expiresIn: number; interval: number }
export type MsSignInState = { status: "pending" } | { status: "signed_in"; account: string } | { status: "failed"; message: string };

/**
 * "Sign in with Microsoft" for reading a Microsoft 365 mailbox (Document Automation's email inboxes and the Outlook
 * trigger; the server's MicrosoftSignIn). The person signs in at Microsoft with a short code, so nobody types an app id
 * or a secret; this asks the server every few seconds until the sign-in is done.
 */
export function MicrosoftSignInDialog({ start, poll, onClose, onDone }: {
  start: () => Promise<MsSignInStart>; poll: (id: string) => Promise<MsSignInState>; onClose: () => void; onDone: (id: string, account: string) => void;
}) {
  const [begun, setBegun] = useState<MsSignInStart | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const calls = useRef({ start, poll, onDone });
  calls.current = { start, poll, onDone };
  useEffect(() => {
    let live = true, timer: ReturnType<typeof setTimeout> | undefined;
    setBegun(null); setProblem(null); setCopied(false);
    const say = (e: unknown) => { if (live) setProblem(e instanceof Error ? e.message : String(e)); };
    calls.current.start().then((s) => {
      if (!live) return;
      setBegun(s);
      const ask = () => {
        timer = setTimeout(() => {
          calls.current.poll(s.id).then((r) => {
            if (!live) return;
            if (r.status === "signed_in") calls.current.onDone(s.id, r.account);
            else if (r.status === "failed") setProblem(r.message);
            else ask();
          }, say);
        }, Math.max(1, s.interval) * 1000);
      };
      ask();
    }, say);
    return () => { live = false; if (timer) clearTimeout(timer); };
  }, [attempt]);
  const copy = async () => {
    if (!begun) return;
    try { await navigator.clipboard.writeText(begun.userCode); setCopied(true); } catch { setCopied(false); }
  };
  return (
    <Dialog open onClose={onClose} title="Sign in with Microsoft" width={520}
      footer={<>{problem && <Button onClick={() => setAttempt(attempt + 1)}>Try again</Button>}<Button onClick={onClose}>Cancel</Button></>}>
      {!begun && !problem && <p style={{ margin: 0 }} role="status">Asking Microsoft for a code…</p>}
      {begun && (
        <ol className="ui-ms-steps">
          <li>Open <a href={begun.verificationUri} target="_blank" rel="noopener noreferrer">{begun.verificationUri.replace(/^https?:\/\//, "")}</a> in your browser.</li>
          <li>Enter this code: <span className="ui-ms-code" aria-label="Code">{begun.userCode}</span>{" "}
            <button type="button" className="ui-ms-copy" onClick={copy}>{copied ? "Copied" : "Copy code"}</button></li>
          <li>Sign in with the mailbox's account. If Microsoft asks for an administrator's approval, your Microsoft 365 administrator approves One CommandCenter once.</li>
        </ol>
      )}
      {begun && !problem && <p className="ui-ms-wait" role="status">Waiting for you to sign in at Microsoft… The code works for {Math.round(begun.expiresIn / 60)} minutes.</p>}
      {problem && <p className="ui-ms-problem" role="alert">{problem}</p>}
    </Dialog>
  );
}
