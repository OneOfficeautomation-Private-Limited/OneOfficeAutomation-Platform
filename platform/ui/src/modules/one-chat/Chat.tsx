import "./consoleApi";
import { useCallback, useEffect, useRef, useState } from "react";
import type { CrApi } from "../../api/cr";
import type { ChatMessage } from "./consoleApi";
import { Button, HelpButton, TextInput, type HelpTopic } from "../../components";

/**
 * AI > One Chat: run agents and check your runs in plain words. Runs start as the person, with their permissions; anything
 * that changes data waits for their yes, and R3 agents for a second person's approval in the Task inbox. The server keeps
 * the chat (schema 45), so it survives a restart; this page keeps the chat it is in while the person visits other pages.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const code = (e: unknown) => (e as { code?: string })?.code;
const lang = () => document.documentElement.lang || navigator.language || "en";

const HELP: HelpTopic = { title: "One Chat", about: "Ask for an agent by its name or describe the task, or ask about your runs.",
  items: [
    { name: "What it runs", text: "Only agents you may run. A run starts as you, with your permissions, and shows in Agent Runs and the Audit Trail." },
    { name: "Your yes", text: "Before running anything that changes data, the chat asks; answer Yes, run it or No." },
    { name: "Approvals", text: "Agents marked R3 also need someone else's approval in the Task inbox; never your own." },
    { name: "Afterwards", text: "The chat says when a run it started finishes or fails. New chat starts over." },
    { name: "Settings", text: "Administrators choose how the chat understands requests (rules only, AI model, or both) under Settings." },
  ] };

/** The chat in progress, kept while the person visits other pages (as the classic console does). */
let kept: { session: string | null; messages: ChatMessage[] } = { session: null, messages: [] };
/** Starts over (tests, and New chat). */
export function resetChat() { kept = { session: null, messages: [] }; }

/** True while a run or an approval from this chat has not reported back yet, so the page keeps listening. */
function waiting(messages: ChatMessage[]) {
  return messages.some((m) => (m.kind === "awaiting_approval" && !messages.some((x) => String(x.kind ?? "").startsWith("approval_")))
    || ((m.kind === "started" || m.kind === "approval_approved") && !!m.run_id
      && !messages.some((x) => x.run_id === m.run_id && String(x.kind ?? "").startsWith("run_"))));
}

export function Chat({ api, onOpenRuns, pollMs = 10000 }: { api: CrApi; onOpenRuns: () => void; pollMs?: number }) {
  const [session, setSession] = useState<string | null>(kept.session);
  const [messages, setMessages] = useState<ChatMessage[]>(kept.messages);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const log = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => { kept = { session, messages }; }, [session, messages]);
  useEffect(() => { log.current?.scrollTo?.({ top: log.current.scrollHeight }); }, [messages]);
  useEffect(() => { input.current?.focus(); }, []);

  const send = useCallback(async (raw: string) => {
    const t = raw.trim();
    if (!t || busy) return;
    setBusy(true);
    setMessages((m) => [...m, { role: "user", text: t }]);
    try {
      const r = await api.chat(session, t, lang());
      setSession(r.session_id);
      setMessages((m) => [...m, { role: "assistant", text: r.reply, kind: r.kind, run_id: r.run_id }]);
    } catch (e) {
      if (code(e) === "CR.NOT_FOUND") setSession(null);   // the chat expired on the server: the next message starts a new one
      setMessages((m) => [...m, { role: "assistant", text: message(e), kind: "error" }]);
    } finally { setBusy(false); input.current?.focus(); }
  }, [api, busy, session]);

  // while a run or an approval from this chat is open, pick up what One CommandCenter adds (finished, failed, approved)
  useEffect(() => {
    if (!session || busy || !waiting(messages)) return;
    const id = setInterval(async () => {
      try {
        const all = await api.chatHistory(session);
        if (all.length > messages.length) setMessages(all);
      } catch { /* the chat expired; the next message starts a new one */ }
    }, pollMs);
    return () => clearInterval(id);
  }, [api, session, busy, messages, pollMs]);

  const last = messages[messages.length - 1];
  const submit = (e: React.FormEvent) => { e.preventDefault(); const t = text; setText(""); send(t); };
  return (
    <section className="st-page cc-home cc-list cc-chat-page" aria-labelledby="cc-chat-title">
      <header className="cc-list-head">
        <h1 id="cc-chat-title">One Chat</h1>
        <HelpButton topic={HELP} />
        <span className="ov-fill" />
        <Button onClick={() => { resetChat(); setSession(null); setMessages([]); setText(""); input.current?.focus(); }} disabled={busy}>New chat</Button>
      </header>
      <p className="cc-chat-intro">Run agents and check your runs in plain words. Runs start as you, with your permissions, and show in Agent Runs and the Audit Trail.</p>
      <div className="cc-chat" >
        <div className="cc-chat-log" role="log" aria-live="polite" aria-label="Conversation" ref={log}>
          {messages.length === 0 && <p className="cc-chat-empty">Ask for an agent by name or describe the task. I only offer agents you may run, and I ask before running anything that changes data.</p>}
          {messages.map((m, i) => (
            <div key={i} className={`cc-chat-msg is-${m.role}${m.kind === "error" ? " is-error" : ""}`}>
              {/* replies come already in the person's language, and what people type stays as typed */}
              <span className="cc-chat-text" data-no-i18n="">{m.text}</span>
              {m === last && m.kind === "confirm" && !busy && (
                <div className="cc-chat-actions">
                  <Button kind="primary" onClick={() => send("yes")}>Yes, run it</Button>
                  <Button onClick={() => send("no")}>No</Button>
                </div>)}
              {m.run_id && (m.kind === "started" || String(m.kind ?? "").startsWith("run_")) && (
                <div className="cc-chat-actions"><Button onClick={onOpenRuns}>Open Agent Runs</Button></div>)}
            </div>))}
          {busy && <div className="cc-chat-msg is-assistant is-busy" aria-label="Waiting for the reply">…</div>}
        </div>
        <form className="cc-chat-form" onSubmit={submit}>
          <TextInput ref={input} aria-label="Message" value={text} maxLength={2000} onChange={(e) => setText(e.target.value)}
            placeholder="For example: post today's invoices for Acme, or: what are my runs?" />
          <Button kind="primary" type="submit" disabled={busy || !text.trim()}>Send</Button>
        </form>
      </div>
    </section>
  );
}
