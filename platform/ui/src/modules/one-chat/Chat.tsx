import "./consoleApi";
import { useCallback, useEffect, useRef, useState } from "react";
import type { CrApi } from "../../api/cr";
import type { ChatMessage, ChatSummary } from "./consoleApi";
import { Button, HelpButton, TextInput, type HelpTopic } from "../../components";
import { PageIntro } from "../../shell/PageIntro";

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

/**
 * A confirmation as a card (S3, the design plan's AIS-Ask-A): what it is about and its text (with why it asked), what
 * undoing it takes, when the question ends, and Yes / No while it is the last message.
 */
function ConfirmCard({ m, open, onAnswer }: { m: ChatMessage; open: boolean; onAnswer: (t: string) => void }) {
  const c = m.confirmation!;
  const yes = c.risk === "R3" ? "Yes, ask for approval" : c.kind === "tool" ? "Yes, do it" : c.kind === "read" ? "Yes, show it" : "Yes, run it";
  const ends = new Date(c.expires);
  return <div className="cc-chat-card" role="group" aria-label={c.name}>
    <div className="cc-chat-card-head"><strong>{c.name}</strong><span className="cc-chat-pill is-wait">{open ? "Waiting for your yes" : "Asked"}</span></div>
    <div className="cc-chat-card-body"><span className="cc-chat-text" data-no-i18n="">{m.text}</span>
      <dl className="cc-chat-kv">
        {c.undo && <><dt>Can be undone</dt><dd>{c.undo}</dd></>}
        {!isNaN(ends.getTime()) && <><dt>This question ends</dt><dd>{ends.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</dd></>}
      </dl>
    </div>
    {open && <div className="cc-chat-actions"><Button kind="primary" onClick={() => onAnswer("yes")}>{yes}</Button><Button onClick={() => onAnswer("no")}>No</Button></div>}
  </div>;
}

/** A tool's answer as a small table (at most 20 rows; the page link shows the rest). */
function ToolTable({ rows, columns }: { rows: Record<string, unknown>[]; columns?: string[] }) {
  const cols = columns?.length ? columns : Object.keys(rows[0] ?? {});
  const cell = (v: unknown) => v === null || v === undefined ? "" : typeof v === "boolean" ? (v ? "Yes" : "No") : String(v);
  // its own plain style: a chat answer gets no column filters or drag handles (TableTools adds those to list tables)
  return <table className="cc-chat-table" aria-label="Answer">
    <thead><tr>{cols.map((c) => <th key={c} scope="col">{c.replace(/_/g, " ")}</th>)}</tr></thead>
    <tbody>{rows.slice(0, 20).map((r, i) => <tr key={i}>{cols.map((c) => <td key={c}>{cell(r[c])}</td>)}</tr>)}</tbody>
  </table>;
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
      setMessages((m) => [...m, { role: "assistant", text: r.reply, kind: r.kind, run_id: r.run_id, task_id: r.task_id, confirmation: r.confirmation,
        rows: r.rows, columns: r.columns, page: r.page, notice: r.notice }]);
    } catch (e) {
      if (code(e) === "CR.NOT_FOUND") setSession(null);   // the chat expired on the server: the next message starts a new one
      setMessages((m) => [...m, { role: "assistant", text: message(e), kind: "error" }]);
    } finally { setBusy(false); input.current?.focus(); }
  }, [api, busy, session]);

  // S5c: the stream (GET /api/chat/stream) says at once when a run of the person's ends or their request is decided;
  // while it is open the page checks only every 30 seconds, as a safety net (contract section 6)
  const [streaming, setStreaming] = useState(false);
  const [heard, setHeard] = useState(0);
  useEffect(() => {
    if (typeof EventSource === "undefined") return;
    const es = new EventSource(api.base + "chat/stream");
    es.onopen = () => setStreaming(true);
    es.onerror = () => setStreaming(false);
    const refresh = () => setHeard((n) => n + 1);
    es.addEventListener("run_ended", refresh);
    es.addEventListener("approval_decided", refresh);
    return () => es.close();
  }, [api]);
  useEffect(() => {
    if (!heard || !session) return;
    api.chatHistory(session).then((all) => setMessages((m) => all.length > m.length ? all : m), () => { /* the chat expired */ });
  }, [api, heard, session]);

  // while a run or an approval from this chat is open, pick up what One CommandCenter adds (finished, failed, approved)
  useEffect(() => {
    if (!session || busy || !waiting(messages)) return;
    const id = setInterval(async () => {
      try {
        const all = await api.chatHistory(session);
        if (all.length > messages.length) setMessages(all);
      } catch { /* the chat expired; the next message starts a new one */ }
    }, streaming ? Math.max(pollMs, 30000) : pollMs);
    return () => clearInterval(id);
  }, [api, session, busy, messages, pollMs, streaming]);

  // S4: the person's kept chats, to reopen one (kept as long as Settings > AI assistants > One Chat > Keep chats for
  // says); read only when the person opens the list
  const [past, setPast] = useState<ChatSummary[] | null>(null);
  const togglePast = () => past ? setPast(null) : Promise.resolve().then(() => api.chats()).then(setPast, () => setPast([]));
  const reopen = async (id: string) => {
    if (busy) return;
    setPast(null);
    try { const all = await api.chatHistory(id); setSession(id); setMessages(all); }
    catch (e) { setMessages((m) => [...m, { role: "assistant", text: message(e), kind: "error" }]); }
  };
  const last = messages[messages.length - 1];
  const submit = (e: React.FormEvent) => { e.preventDefault(); const t = text; setText(""); send(t); };
  return (
    <section className="st-page cc-home cc-list cc-chat-page" aria-labelledby="cc-chat-title">
      <header className="cc-list-head">
        <h1 id="cc-chat-title">One Chat</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:chat" />
        <span className="ov-fill" />
        <Button aria-expanded={!!past} onClick={togglePast} disabled={busy}>Your chats</Button>
        <Button onClick={() => { resetChat(); setSession(null); setMessages([]); setText(""); input.current?.focus(); }} disabled={busy}>New chat</Button>
      </header>
      {past && <nav className="cc-chat-history" aria-label="Your chats">
        {past.length === 0 ? <p className="st-muted">No kept chats. Chats are kept as long as your administrator set.</p>
          : <ul>{past.map((c) => <li key={c.id}><button type="button" className="cc-link" onClick={() => reopen(c.id)} aria-current={c.id === session ? "true" : undefined}>
              {c.title || "Chat"}</button><span className="st-muted">{" \u00b7 " + new Date(c.last_used).toLocaleString() + " \u00b7 " + c.messages + " messages"}</span></li>)}</ul>}
      </nav>}
      <p className="cc-chat-intro">Run agents and check your runs in plain words. Runs start as you, with your permissions, and show in Agent Runs and the Audit Trail.</p>
      <div className="cc-chat" >
        <div className="cc-chat-log" role="log" aria-live="polite" aria-label="Conversation" ref={log}>
          {messages.length === 0 && <p className="cc-chat-empty">Ask for an agent by name or describe the task. I only offer agents you may run, and I ask before running anything that changes data.</p>}
          {messages.map((m, i) => (
            <div key={i} className={`cc-chat-msg is-${m.role}${m.kind === "error" ? " is-error" : ""}`}>
              {/* replies come already in the person's language, and what people type stays as typed */}
              {m.notice && <p className="cc-chat-notice" role="note">{m.notice}</p>}
              {m.kind === "confirm" && m.confirmation ? <ConfirmCard m={m} open={m === last && !busy} onAnswer={send} />
                : m.kind === "awaiting_approval" ? <div className="cc-chat-card is-approval" role="group" aria-label="Waiting for approval">
                    <span className="cc-chat-text" data-no-i18n="">{m.text}</span><span className="cc-chat-pill is-wait">Waiting for approval</span></div>
                : <span className="cc-chat-text" data-no-i18n="">{m.text}</span>}
              {m === last && m.kind === "confirm" && !m.confirmation && !busy && (
                <div className="cc-chat-actions">
                  <Button kind="primary" onClick={() => send("yes")}>Yes, run it</Button>
                  <Button onClick={() => send("no")}>No</Button>
                </div>)}
              {!!m.rows?.length && <ToolTable rows={m.rows} columns={m.columns} />}
              {m.page && m.role === "assistant" && m.kind !== "confirm" && <a className="cc-link cc-chat-page-link" href={m.page}>Open the page</a>}
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
