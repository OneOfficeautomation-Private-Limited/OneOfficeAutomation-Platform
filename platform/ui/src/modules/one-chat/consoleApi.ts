import { CrApi } from "../../api/cr";

/**
 * one-chat's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
/** One Chat: a message in a chat, as the server keeps it; kind says what the reply is (confirm waits for yes or no; started, run_* and approval_* follow a run). */
export type ChatMessage = { role: "user" | "assistant"; text: string; kind?: string; run_id?: string; notice?: string } & ChatDetails;
/**
 * What a reply may carry besides its text (S3): a confirmation's card (what it is about, its risk, why it asked, what
 * undoing it takes, when the question ends), and a tool's small table and the console page that shows more.
 */
export type ChatCard = { id: string; expires: string; risk: string; name: string; kind: "agent" | "tool" | "read"; reason?: string; undo?: string };
export type ChatDetails = { task_id?: string; confirmation?: ChatCard; rows?: Record<string, unknown>[]; columns?: string[]; page?: string };
/** A knowledge base (G71): an index kept in One CommandCenter, filled by an indexing agent. */
/** notice: set when the OneChat service could not answer and the built-in engine did (S6). */
export type ChatReply = { session_id: string; reply: string; kind: string; run_id?: string; engine?: "builtin" | "service"; notice?: string } & ChatDetails;

/** My account > AI assistants (S2): one assistant's choices for the person, and what the administrator allows. */
export type AssistantArea = { mine: { model: string; language: string; instructions: string; ask_first?: boolean }; default_model: string; models: string[];
  language_fixed: string; own_instructions: boolean; company_instructions: string };
export type AssistantsMine = { assistants: { chat: AssistantArea; studio: AssistantArea }; languages: Record<string, string>;
  /** One Chat's memory (S4): the administrator's mode, and the notes this person asked it to remember. */
  memory?: { mode: "chat" | "off" | "across"; notes: { id: string; note: string; created: string }[];
    /** S5: "service" when the OneChat service keeps the notes; notes_error when it did not answer. */
    kept_by?: "service"; notes_error?: string } };
/** The OneChat service's version and whether this release works with it (S5, Settings > AI assistants > One Chat). */
export type OneChatVersion = { contract: string; tested: string; minimum: string; version: string | null;
  status: "compatible" | "newer" | "older" | "wrong_contract" | "unreachable" | "not_set_up"; compatible: boolean; message: string; engine: "builtin" | "service"; url: string;
  /** S6: who answers now (none: the service cannot and fallback is off), the fallback, and the circuit breaker. */
  answering?: "service" | "builtin" | "none"; fallback?: "builtin" | "off"; paused_until?: string | null; last_failure?: string | null; last_failure_at?: string | null };
/** S6: OneChat model servers behind Model Connections, against this release's action catalog. */
export type OneChatModelCheck = { catalog: { format: string; entries: number; sha256: string };
  connections: { connection: string; status: string; message: string; version?: string; model_step?: number; catalog_actions?: number }[] };
/** One of the person's kept chats (S4, One Chat's Your chats). */
export type ChatSummary = { id: string; title: string; last_used: string; messages: number };

declare module "../../api/cr" {
  interface CrApi {
    /** My account > AI assistants: the person's own choices per assistant and what is allowed. */
    assistantsMine(): Promise<AssistantsMine>;
    /** Forgets one of the notes One Chat keeps for the person, or all of them (no id). */
    forgetNotes(id?: string): Promise<AssistantsMine>;
    /** One Chat: the person's kept chats, newest first. */
    chats(): Promise<ChatSummary[]>;
    /** The OneChat service's version and whether it works with this release; fresh asks it again at once. */
    onechatVersion(fresh?: boolean): Promise<OneChatVersion>;
    /** S6: whether the OneChat model servers behind Model Connections were built for this release (administrators). */
    onechatModelCheck(): Promise<OneChatModelCheck>;
    /** Saves the person's own choices for one assistant (chat or studio), within what is allowed. */
    saveAssistantPrefs(area: "chat" | "studio", prefs: { model: string; language: string; instructions: string; ask_first?: boolean }): Promise<AssistantsMine>;
    /** One Chat: sends a message (a new chat when sessionId is null); the reply comes in the person's language. */
    chat(sessionId: string | null, message: string, lang: string): Promise<ChatReply>;
    /** One Chat: the messages of a chat, including what One CommandCenter added since (a run finished, an approval decided). */
    chatHistory(sessionId: string): Promise<ChatMessage[]>;
  }
}

/** One Chat: sends a message (a new chat when sessionId is null); the reply comes in the person's language. */

CrApi.prototype.chat = function (sessionId: string | null, message: string, lang: string) { return this.request<ChatReply>("POST", "chat", { session_id: sessionId, message, lang }); };
/** One Chat: the messages of a chat, including what One CommandCenter added since (a run finished, an approval decided). */

CrApi.prototype.chatHistory = async function (sessionId: string) { return (await this.request<{ messages?: ChatMessage[] }>("GET", "chat/" + encodeURIComponent(sessionId))).messages ?? []; };

/** My account > AI assistants: the person's own choices per assistant and what is allowed. */
CrApi.prototype.assistantsMine = function () { return this.request<AssistantsMine>("GET", "assistants/me"); };
/** Saves the person's own choices for one assistant (chat or studio), within what is allowed. */
CrApi.prototype.saveAssistantPrefs = function (area: "chat" | "studio", prefs: { model: string; language: string; instructions: string; ask_first?: boolean }) {
  return this.request<AssistantsMine>("PUT", "assistants/me/" + area, prefs);
};

/** Forgets one of the notes One Chat keeps for the person, or all of them (no id). */
CrApi.prototype.forgetNotes = function (id?: string) { return this.request<AssistantsMine>("DELETE", "assistants/me/memory" + (id ? "/" + encodeURIComponent(id) : "")); };
/** One Chat: the person's kept chats, newest first. */
CrApi.prototype.chats = async function () { return (await this.request<{ chats?: ChatSummary[] }>("GET", "chat")).chats ?? []; };
/** The OneChat service's version and whether it works with this release; fresh asks it again at once. */
CrApi.prototype.onechatModelCheck = function () { return this.request<OneChatModelCheck>("GET", "onechat/model-check"); };
CrApi.prototype.onechatVersion = function (fresh?: boolean) { return this.request<OneChatVersion>("GET", "onechat/version" + (fresh ? "?fresh=1" : "")); };
