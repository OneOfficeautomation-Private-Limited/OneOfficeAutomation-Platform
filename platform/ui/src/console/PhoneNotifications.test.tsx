import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CrApi } from "../api/cr";
import { PhoneNotificationsDialog } from "./PhoneNotifications";

function fakeApi(available: boolean) {
  const calls: string[] = [];
  let enabled = false, n = 0;
  const status = () => ({ available, enabled, server: available ? "https://ntfy.example.com" : "", ...(enabled ? { topic: "ooa-topic" + n } : {}) });
  const f = (async (url: string, init: RequestInit = {}) => {
    const route = url.replace(/^\/api\//, "");
    calls.push((init.method ?? "GET") + " " + route);
    if (route === "me/push" && init.method === "POST") { enabled = true; n++; }
    if (route === "me/push" && init.method === "DELETE") { enabled = false; return new Response(JSON.stringify({ ok: true }), { status: 200 }); }
    if (route === "me/push/test") return new Response(JSON.stringify({ ok: true }), { status: 200 });
    return new Response(JSON.stringify(status()), { status: 200 });
  }) as unknown as typeof fetch;
  return { api: new CrApi("/api/", f), calls };
}

describe("phone notifications (G43)", () => {
  it("says when the administrator has not set it up", async () => {
    render(<PhoneNotificationsDialog api={fakeApi(false).api} onClose={() => {}} />);
    expect(await screen.findByText(/not set up on this One CommandCenter/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Turn on" })).toBeNull();
  });

  it("turns on with a private topic, tests, makes a new topic and turns off", async () => {
    const { api, calls } = fakeApi(true);
    render(<PhoneNotificationsDialog api={api} onClose={() => {}} />);
    const on = await screen.findByRole("button", { name: "Turn on" });
    await act(async () => { fireEvent.click(on); });
    expect(await screen.findByText("ooa-topic1")).toBeTruthy();
    expect(screen.getByText("https://ntfy.example.com")).toBeTruthy();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Send a test" })); });
    expect(screen.getByRole("status").textContent).toContain("test notification");
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "New topic" })); });
    expect(await screen.findByText("ooa-topic2")).toBeTruthy();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Turn off" })); });
    expect(await screen.findByRole("button", { name: "Turn on" })).toBeTruthy();
    expect(calls).toEqual(["GET me/push", "POST me/push", "POST me/push/test", "GET me/push", "POST me/push", "DELETE me/push", "GET me/push"]);
  });
});
