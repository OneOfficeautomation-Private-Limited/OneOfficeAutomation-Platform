import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi, type CrUser, type RunWait } from "../api/cr";
import { SuspendedRuns, waitsFor } from "./SuspendedRuns";

afterEach(cleanup);

const waits: RunWait[] = [
  { id: "w1", run_id: "r1", bot_path: "Finance/Approve invoice.flow.json", version: 3, step_id: "4", kind: "callback", ref: null, status: "waiting",
    created: "2026-11-05T08:00:00Z", created_by: "ann", timeout_at: "2026-11-06T08:00:00Z", device_name: "Runner A" },
  { id: "w2", run_id: "r2", bot_path: "Ops/Nightly.flow.json", kind: "task", ref: "task_9", status: "waiting", created: "2026-11-05T09:00:00Z", created_by: "bob", run_as: "svc" }];

function server() {
  const calls: { method: string; url: string }[] = [];
  let open = [...waits];
  const f = (async (url: string, init: RequestInit) => {
    calls.push({ method: init.method ?? "GET", url });
    if (url.startsWith("/api/run-waits?")) return new Response(JSON.stringify({ waits: open }), { status: 200 });
    const m = url.match(/^\/api\/run-waits\/(\w+)\/(resume|cancel)$/);
    if (m && init.method === "POST") {
      open = open.filter((w) => w.id !== m[1]);
      return new Response(JSON.stringify(m[2] === "resume" ? { run_id: "r_next" } : { ok: true }), { status: 200 });
    }
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}
const person = (permissions: string[]) => ({ id: "1", username: "a", display_name: "A", role: "x", permissions }) as unknown as CrUser;

describe("Suspended runs", () => {
  it("says what each run waits for", () => {
    expect(waitsFor(waits[0])).toBe("A call to its callback URL");
    expect(waitsFor(waits[1])).toBe("Task task_9 to be done");
    expect(waitsFor({ ...waits[1], kind: "queue_item", ref: "item_1" })).toBe("Work item item_1 to end");
  });

  it("lists suspended runs, opens a run on click, and has no buttons without the permission", async () => {
    const { api } = server();
    const opened: string[] = [];
    render(<SuspendedRuns api={api} user={person(["runs.view"])} onNotice={() => {}} onOpen={(id) => opened.push(id)} />);
    const table = await screen.findByRole("table", { name: "Suspended runs" });
    expect(await within(table).findByText("Approve invoice")).toBeTruthy();
    expect(within(table).getByText("A call to its callback URL")).toBeTruthy();
    expect(within(table).getByText("bob as svc")).toBeTruthy();
    expect(within(table).getByText("Never")).toBeTruthy();
    expect(within(table).queryByRole("button", { name: /Resume/ })).toBeNull();
    fireEvent.click(within(table).getByText("Approve invoice"));
    expect(opened).toEqual(["r1"]);
    fireEvent.change(screen.getByLabelText("Search suspended runs"), { target: { value: "nightly" } });
    expect(within(table).queryByText("Approve invoice")).toBeNull();
  });

  it("resumes a run now and stops another, asked first", async () => {
    const { api, calls } = server();
    const notices: string[] = [];
    render(<SuspendedRuns api={api} user={person(["runs.view", "runs.stop"])} onNotice={(t) => notices.push(t)} onOpen={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Resume Approve invoice now" }));
    expect(screen.getByRole("alert").textContent).toContain("A new run carries on from the waiting step");
    fireEvent.click(screen.getByRole("button", { name: "Resume now" }));
    expect(await screen.findByRole("button", { name: "Stop the suspended run of Nightly" })).toBeTruthy();
    expect(calls.some((c) => c.method === "POST" && c.url === "/api/run-waits/w1/resume")).toBe(true);
    await waitFor(() => expect(notices[0]).toContain("as run r_next"));
    fireEvent.click(screen.getByRole("button", { name: "Stop the suspended run of Nightly" }));
    fireEvent.click(screen.getByRole("button", { name: "Stop" }));
    expect(await screen.findByText("No run is suspended now.")).toBeTruthy();
    expect(calls.some((c) => c.method === "POST" && c.url === "/api/run-waits/w2/cancel")).toBe(true);
  });
});
