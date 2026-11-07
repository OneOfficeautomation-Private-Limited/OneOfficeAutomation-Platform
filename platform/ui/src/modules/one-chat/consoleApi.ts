import { CrApi } from "../../api/cr";

/**
 * one-chat's part of the console's API client (docs/architecture/13-modules.md, phase 6): its methods are added
 * to CrApi when this file is loaded (its pages import it), so the kernel's client (api/cr.ts) carries no module's calls.
 */
/** One Chat: a message in a chat, as the server keeps it; kind says what the reply is (confirm waits for yes or no; started, run_* and approval_* follow a run). */
export type ChatMessage = { role: "user" | "assistant"; text: string; kind?: string; run_id?: string };
/** A knowledge base (G71): an index kept in One CommandCenter, filled by an indexing agent. */
export type ChatReply = { session_id: string; reply: string; kind: string; run_id?: string };

declare module "../../api/cr" {
  interface CrApi {
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
