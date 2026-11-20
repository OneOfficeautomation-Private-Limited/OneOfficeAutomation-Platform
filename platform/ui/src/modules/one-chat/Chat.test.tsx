import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi } from "../../api/cr";
import { HelpProvider } from "../../components";
import { MENU_SECTIONS } from "../../shell/menu";
import { Chat, resetChat } from "./Chat";

afterEach(() => { cleanup(); resetChat(); });

type Reply = { status?: number; body: unknown };
/** A stand-in One CommandCenter: POST /api/chat answers from the script in order; GET /api/chat/{id} gives history. */
function server(replies: Reply[], history: unknown[] = [], chats: unknown[] = []) {
  const calls: { method: string; url: string; body?: Record<string, unknown> }[] = [];
  const f = (async (url: string, init: RequestInit = {}) => {
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init.method ?? "GET", url, body });
    if (url === "/api/chat" && init.method === "POST") {
      const r = replies.shift() ?? { body: { session_id: "s1", reply: "?", kind: "not_understood" } };
      return new Response(JSON.stringify(r.body), { status: r.status ?? 200 });
    }
    if (url === "/api/chat") return new Response(JSON.stringify({ chats }), { status: 200 });
    if (url.startsWith("/api/chat/")) return new Response(JSON.stringify({ session_id: "s1", messages: history }), { status: 200 });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

function show(api: CrApi, opts: { onOpenRuns?: () => void; pollMs?: number } = {}) {
  render(<HelpProvider><Chat api={api} onOpenRuns={opts.onOpenRuns ?? (() => {})} pollMs={opts.pollMs ?? 10000} /></HelpProvider>);
}

const type = (t: string) => {
  fireEvent.change(screen.getByRole("textbox", { name: "Message" }), { target: { value: t } });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
};

describe("One Chat page", () => {
  it("lists the person's kept chats only when asked, and reopens one (S4)", async () => {
    const s = server([], [{ role: "user", text: "show payables aging" }, { role: "assistant", text: "Here is the aging report.", kind: "answer" }],
      [{ id: "chat_7", title: "show payables aging", last_used: "2026-11-20T09:00:00Z", messages: 2 }]);
    show(s.api);
    expect(s.calls.length).toBe(0);   // nothing is read until the person opens the list
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Your chats" })); });
    const list = await screen.findByRole("navigation", { name: "Your chats" });
    await act(async () => { fireEvent.click(within(list).getByRole("button", { name: "show payables aging" })); });
    expect(await screen.findByText("Here is the aging report.")).toBeTruthy();
    expect(s.calls.map((c) => c.method + " " + c.url)).toEqual(["GET /api/chat", "GET /api/chat/chat_7"]);
    type("again please");
    await waitFor(() => expect(s.calls.at(-1)?.body).toMatchObject({ session_id: "chat_7", message: "again please" }));
  });

  it("shows a confirmation as a card with what undoing it takes, and a tool's answer as a table with its page (S3)", async () => {
    const card = { id: "cnf_1", expires: "2026-11-20T10:40:00Z", risk: "R2", name: "Disable a person", kind: "tool", undo: "Enable them again in Users." };
    const s = server([
      { body: { session_id: "s1", reply: "I'll do this: Disable a person (person = jsmith).\nShall I go ahead? (yes/no)", kind: "confirm", confirmation: card } },
      { body: { session_id: "s1", reply: "jsmith is disabled.", kind: "done", page: "#/users" } },
      { body: { session_id: "s1", reply: "2 people, 1 disabled.", kind: "answer", rows: [{ username: "jsmith", enabled: false }], page: "#/users" } },
      { body: { session_id: "s1", reply: "I've asked for approval (task task_9).", kind: "awaiting_approval", task_id: "task_9" } }]);
    show(s.api);
    type("disable jsmith");
    const c = await screen.findByRole("group", { name: "Disable a person" });
    expect(c.textContent).toContain("Waiting for your yes");
    expect(c.textContent).toContain("Can be undone");
    expect(c.textContent).toContain("Enable them again in Users.");
    expect(c.textContent).toContain("This question ends");
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Yes, do it" })); });
    expect(s.calls.at(-1)?.body).toMatchObject({ session_id: "s1", message: "yes" });
    expect(await screen.findByText("jsmith is disabled.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Yes, do it" })).toBeNull();
    type("who is disabled");
    const table = await screen.findByRole("table", { name: "Answer" });
    expect(table.textContent).toContain("jsmith");
    expect(table.textContent).toContain("No");
    expect(screen.getAllByRole("link", { name: "Open the page" }).at(-1)?.getAttribute("href")).toBe("#/users");
    type("delete jsmith");
    expect((await screen.findByRole("group", { name: "Waiting for approval" })).textContent).toContain("task_9");
  });

  it("is in the AI menu for people who may see runs", () => {
    const ai = MENU_SECTIONS.find((s) => s.id === "ai")!;
    expect(ai.pages.map((p) => p.label)[0]).toBe("One Chat");
    expect(ai.pages.find((p) => p.id === "chat")?.perm).toBe("runs.view");
  });

  it("sends a message in the person's language and shows the reply; the next message continues the same chat", async () => {
    const s = server([{ body: { session_id: "s1", reply: "Which customer?", kind: "ask" } }, { body: { session_id: "s1", reply: "Noted.", kind: "ask" } }]);
    show(s.api);
    expect(screen.getByText(/I only offer agents you may run/)).toBeTruthy();
    type("post invoices");
    expect(await screen.findByText("Which customer?")).toBeTruthy();
    expect(screen.getByText("post invoices")).toBeTruthy();
    expect(s.calls[0].body).toMatchObject({ session_id: null, message: "post invoices" });
    expect(typeof s.calls[0].body?.lang).toBe("string");
    type("Acme");
    await screen.findByText("Noted.");
    expect(s.calls[1].body).toMatchObject({ session_id: "s1", message: "Acme" });
    expect((screen.getByRole("textbox", { name: "Message" }) as HTMLInputElement).value).toBe("");
  });

  it("asks before running: Yes sends yes, and a started run offers Agent Runs", async () => {
    let opened = 0;
    const s = server([{ body: { session_id: "s1", reply: "Run Post invoices for Acme?", kind: "confirm" } },
      { body: { session_id: "s1", reply: "Started.", kind: "started", run_id: "r1" } }]);
    show(s.api, { onOpenRuns: () => opened++ });
    type("post invoices for Acme");
    fireEvent.click(await screen.findByRole("button", { name: "Yes, run it" }));
    await screen.findByText("Started.");
    expect(s.calls[1].body).toMatchObject({ message: "yes", session_id: "s1" });
    expect(screen.queryByRole("button", { name: "Yes, run it" })).toBeNull();   // only on the latest question
    fireEvent.click(screen.getByRole("button", { name: "Open Agent Runs" }));
    expect(opened).toBe(1);
  });

  it("No declines, and an expired chat starts a new one with the next message", async () => {
    const s = server([{ body: { session_id: "s1", reply: "Run it?", kind: "confirm" } },
      { status: 404, body: { error: { code: "CR.NOT_FOUND", message: "This chat has ended. Send your request again." } } },
      { body: { session_id: "s2", reply: "Hello again.", kind: "ask" } }]);
    show(s.api);
    type("run it");
    fireEvent.click(await screen.findByRole("button", { name: "No" }));
    expect(await screen.findByText("This chat has ended. Send your request again.")).toBeTruthy();
    expect(s.calls[1].body).toMatchObject({ message: "no" });
    type("hello");
    await screen.findByText("Hello again.");
    expect(s.calls[2].body).toMatchObject({ session_id: null });
  });

  it("while a run is open, picks up what One CommandCenter adds to the chat", async () => {
    const history = [{ role: "user", text: "go" }, { role: "assistant", text: "Started.", kind: "started", run_id: "r1" },
      { role: "assistant", text: "Post invoices finished.", kind: "run_success", run_id: "r1" }];
    const s = server([{ body: { session_id: "s1", reply: "Started.", kind: "started", run_id: "r1" } }], history);
    show(s.api, { pollMs: 20 });
    type("go");
    expect(await screen.findByText("Post invoices finished.", undefined, { timeout: 2000 })).toBeTruthy();
    expect(s.calls.some((c) => c.method === "GET" && c.url === "/api/chat/s1")).toBe(true);
    // the run reported back: nothing is open, so the page stops asking
    const asked = s.calls.length;
    await act(() => new Promise((r) => setTimeout(r, 80)));
    expect(s.calls.length).toBe(asked);
  });

  it("keeps the chat while the person visits other pages, and New chat starts over", async () => {
    const s = server([{ body: { session_id: "s1", reply: "Which customer?", kind: "ask" } }, { body: { session_id: "s9", reply: "Hi.", kind: "ask" } }]);
    show(s.api);
    type("post invoices");
    await screen.findByText("Which customer?");
    cleanup();
    show(s.api);
    expect(screen.getByText("Which customer?")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "New chat" }));
    expect(screen.queryByText("Which customer?")).toBeNull();
    type("hi");
    await waitFor(() => expect(s.calls[1].body).toMatchObject({ session_id: null }));
  });
});
