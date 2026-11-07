import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi } from "../../api/cr";
import { HelpProvider } from "../../components";
import { MENU_SECTIONS } from "../../shell/menu";
import { Ideas } from "./Ideas";

afterEach(cleanup);

const base = { description: "", department: "Finance", status: "submitted", submitted: "2026-11-05T08:00:00Z", votes: 0, voted: false, hours_year: 0, score: null };
const ideas = [
  { ...base, id: "i1", title: "Post supplier invoices", submitted_by: "ann", mine: false, status: "approved", score: 82, hours_year: 1200, votes: 3, bot_path: "Finance/Post.flow.json",
    runs_30_days: 40, hours_saved_30_days: 6.7 },
  { ...base, id: "i2", title: "Reconcile bank statements", submitted_by: "me", mine: true, votes: 1, voted: true },
];

function server(canManage: boolean) {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  const list = ideas.map((i) => ({ ...i }));
  const f = (async (url: string, init: RequestInit) => {
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init.method ?? "GET", url, body });
    if (url === "/api/ideas" && (init.method ?? "GET") === "GET")
      return new Response(JSON.stringify({ ideas: list, can_manage: canManage, statuses: ["submitted", "assessing", "approved", "building", "live", "rejected", "on_hold"] }), { status: 200 });
    if (url === "/api/ideas/i1/vote") { list[0].votes++; list[0].voted = true; return new Response(JSON.stringify(list[0]), { status: 200 }); }
    if (url === "/api/ideas" && init.method === "POST") return new Response(JSON.stringify({ ...base, ...body, id: "i3", submitted_by: "me", mine: true }), { status: 200 });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

async function show(canManage: boolean) {
  const s = server(canManage);
  render(<HelpProvider><Ideas api={s.api} onNotice={() => {}} /></HelpProvider>);
  await screen.findByText("Post supplier invoices");
  return s;
}

describe("Automation ideas page", () => {
  it("is in the Build menu for everyone signed in", () => {
    const build = MENU_SECTIONS.find((s) => s.id === "build")!;
    expect(build.pages).toContainEqual({ id: "ideas", label: "Automation ideas" });
  });

  it("lists ideas with status, score, estimated hours and the hours the linked agent saved", async () => {
    await show(false);
    const row = screen.getByText("Post supplier invoices").closest("tr")!;
    expect(within(row).getByText("Approved")).toBeTruthy();
    expect(within(row).getByText("82")).toBeTruthy();
    expect(within(row).getByText("1,200")).toBeTruthy();
    expect(within(row).getByText("6.7 h (40 runs)")).toBeTruthy();
    expect(within(screen.getByText("Reconcile bank statements").closest("tr")!).getByTitle("Not assessed yet")).toBeTruthy();
  });

  it("votes, sorts by votes, and suggests an idea without the assessment fields", async () => {
    const { calls } = await show(false);
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Vote for Post supplier invoices" })); });
    expect(calls.some((c) => c.method === "POST" && c.url === "/api/ideas/i1/vote")).toBe(true);
    fireEvent.change(screen.getByLabelText("Sort"), { target: { value: "votes" } });
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows[0].textContent).toContain("Post supplier invoices");
    fireEvent.click(screen.getByRole("button", { name: "Suggest an idea" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).queryByLabelText("Status")).toBeNull();
    fireEvent.change(within(dialog).getByLabelText("What should be automated"), { target: { value: "Close tickets" } });
    fireEvent.change(within(dialog).getByLabelText("Cases a month"), { target: { value: "300" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Suggest" })); });
    const post = calls.find((c) => c.method === "POST" && c.url === "/api/ideas")!;
    expect(post.body).toMatchObject({ title: "Close tickets", volume_month: 300 });
    expect(post.body).not.toHaveProperty("status");
  });

  it("opens someone else's idea read-only for people who do not assess", async () => {
    await show(false);
    fireEvent.click(screen.getByText("Post supplier invoices"));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("What should be automated").matches(":disabled")).toBe(true);
    expect(within(dialog).queryByRole("button", { name: "Save" })).toBeNull();
  });

  it("lets the automation team assess an idea", async () => {
    const { calls } = await show(true);
    fireEvent.click(screen.getByText("Post supplier invoices"));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Status"), { target: { value: "building" } });
    fireEvent.change(within(dialog).getByLabelText("Rule-based (1-5)"), { target: { value: "5" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Save" })); });
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe("/api/ideas/i1");
    expect(put.body).toMatchObject({ status: "building", rules_score: 5 });
  });
});
