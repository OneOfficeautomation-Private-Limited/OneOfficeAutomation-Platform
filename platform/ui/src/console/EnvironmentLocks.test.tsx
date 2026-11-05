import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi, type CrUser } from "../api/cr";
import { HelpProvider } from "../components";
import { MENU_SECTIONS } from "../shell/menu";
import { EnvironmentLocks } from "./EnvironmentLocks";

afterEach(cleanup);

const locks = [
  { name: "SAP login", run_id: "r1", device_name: "Runner A", bot_path: "Finance/Post.flow.json", held_by: "ann", acquired: "2026-11-04T08:00:00Z",
    expires: "2026-11-04T09:00:00Z", comment: "month end", expired: false },
  { name: "Shared/Inbox", run_id: "r2", device_name: "Runner B", held_by: "bob", acquired: "2026-11-04T07:00:00Z", expires: "2026-11-04T07:30:00Z", expired: true }];

function server() {
  const calls: { method: string; url: string }[] = [];
  let held = [...locks];
  const f = (async (url: string, init: RequestInit) => {
    calls.push({ method: init.method ?? "GET", url });
    if (url.startsWith("/api/environment-locks") && init.method === "DELETE") {
      const name = new URLSearchParams(url.split("?")[1]).get("name");
      held = held.filter((l) => l.name !== name);
      return new Response(JSON.stringify({ released: true }), { status: 200 });
    }
    if (url === "/api/environment-locks") return new Response(JSON.stringify({ locks: held }), { status: 200 });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

const person = (permissions: string[]) => ({ id: "1", username: "a", display_name: "A", role: "x", permissions }) as unknown as CrUser;

describe("Environment locks page", () => {
  it("is in the Manage menu for people who see runners", () => {
    const manage = MENU_SECTIONS.find((s) => s.id === "manage")!;
    expect(manage.pages).toContainEqual({ id: "locks", label: "Environment locks", perm: "devices.view" });
  });

  it("lists the locks held, with the lease marked when it has passed, and no Release without the permission", async () => {
    const { api } = server();
    render(<HelpProvider><EnvironmentLocks api={api} user={person(["devices.view"])} onNotice={() => {}} /></HelpProvider>);
    const table = await screen.findByRole("table", { name: "Environment locks list" });
    expect(await within(table).findByText("SAP login")).toBeTruthy();
    expect(within(table).getByText("SAP login").getAttribute("title")).toBe("month end");
    expect(within(table).getByText("Run r1 for ann")).toBeTruthy();
    expect(within(table).getByText("Runner B")).toBeTruthy();
    expect(within(table).getByText(/\(passed\)/)).toBeTruthy();
    expect(screen.getByText("2 held")).toBeTruthy();
    expect(within(table).queryByRole("button", { name: /Release/ })).toBeNull();
    fireEvent.change(screen.getByLabelText("Search locks"), { target: { value: "inbox" } });
    expect(within(table).queryByText("SAP login")).toBeNull();
    expect(within(table).getByText("Shared/Inbox")).toBeTruthy();
  });

  it("lets an administrator release a lock, asked first", async () => {
    const { api, calls } = server();
    let notice = "";
    render(<HelpProvider><EnvironmentLocks api={api} user={person(["devices.view", "locks.manage"])} onNotice={(t) => { notice = t; }} /></HelpProvider>);
    const table = await screen.findByRole("table", { name: "Environment locks list" });
    fireEvent.click(await within(table).findByRole("button", { name: "Release Shared/Inbox" }));
    expect(screen.getByRole("alert").textContent).toContain("Release the lock Shared/Inbox?");
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    fireEvent.click(within(table).getByRole("button", { name: "Release Shared/Inbox" }));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Release" })); });
    expect(calls).toContainEqual({ method: "DELETE", url: "/api/environment-locks?name=Shared%2FInbox" });
    expect(notice).toBe("Released the lock Shared/Inbox.");
    expect(await within(table).findByText("SAP login")).toBeTruthy();
    expect(within(table).queryByText("Shared/Inbox")).toBeNull();
  });
});
