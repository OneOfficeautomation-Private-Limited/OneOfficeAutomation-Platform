import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi, type CrUser } from "../api/cr";
import { HelpProvider } from "../components";
import { Schedules, weekendText } from "./Schedules";

afterEach(cleanup);

/** Gap G34: a holiday calendar's weekend, shown and edited in Manage > Schedules > Holiday calendars. */
function server() {
  const puts: { url: string; body: Record<string, unknown> }[] = [];
  const cals = [{ name: "India 2026", dates: ["2026-08-15"], weekend: ["SAT", "SUN"] }, { name: "Gulf", dates: [], weekend: ["FRI", "SAT"] }, { name: "Open", dates: [], weekend: [] }];
  const f = (async (url: string, init: RequestInit) => {
    const ok = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });
    if (url === "/api/schedules") return ok({ schedules: [] });
    if (url === "/api/calendars") return ok({ calendars: cals });
    if (url.startsWith("/api/calendars/") && init.method === "PUT") { puts.push({ url, body: JSON.parse(String(init.body)) }); return ok({ ok: true }); }
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return { api: new CrApi("/api/", f), puts };
}

const person = (permissions: string[]) => ({ id: "1", username: "a", display_name: "A", role: "x", permissions }) as unknown as CrUser;

async function openCalendars(permissions: string[]) {
  const s = server();
  await act(async () => { render(<HelpProvider><Schedules api={s.api} user={person(permissions)} onNotice={() => {}} /></HelpProvider>); });
  await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Holiday calendars" })); });
  return s;
}

describe("Holiday calendars with a weekend", () => {
  it("names the weekend in short day names", () => {
    expect(weekendText(undefined)).toBe("Sat, Sun");
    expect(weekendText(["FRI", "SAT"])).toBe("Fri, Sat");
    expect(weekendText([])).toBe("none");
  });

  it("lists each calendar's weekend", async () => {
    await openCalendars(["schedules.view"]);
    const table = screen.getByRole("table", { name: "Calendars" });
    expect(within(table).getByText("Fri, Sat")).toBeTruthy();
    expect(within(table).getByText("none")).toBeTruthy();
    expect(screen.queryByRole("group", { name: "Weekend days" })).toBeNull();
  });

  it("edits a calendar's weekend and saves it with the dates", async () => {
    const { puts } = await openCalendars(["schedules.view", "schedules.manage"]);
    const gulf = within(screen.getByRole("table", { name: "Calendars" })).getByText("Gulf").closest("tr")!;
    await act(async () => { fireEvent.click(within(gulf).getByRole("button", { name: "Edit" })); });
    const days = screen.getByRole("group", { name: "Weekend days" });
    expect((within(days).getByLabelText("Fri") as HTMLInputElement).checked).toBe(true);
    expect((within(days).getByLabelText("Sun") as HTMLInputElement).checked).toBe(false);
    await act(async () => { fireEvent.click(within(days).getByLabelText("Sat")); fireEvent.click(within(days).getByLabelText("Thu")); });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save calendar" })); });
    expect(puts).toEqual([{ url: "/api/calendars/Gulf", body: { dates: [], weekend: ["THU", "FRI"] } }]);
  });

  it("sends NONE for no weekend and refuses a week without a working day", async () => {
    const { puts } = await openCalendars(["schedules.view", "schedules.manage"]);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Shop" } });
    const days = screen.getByRole("group", { name: "Weekend days" });
    for (const d of ["Mon", "Tue", "Wed", "Thu", "Fri"]) fireEvent.click(within(days).getByLabelText(d));
    expect((screen.getByRole("button", { name: "Save calendar" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Leave at least one working day in the week.")).toBeTruthy();
    for (const d of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) fireEvent.click(within(days).getByLabelText(d));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save calendar" })); });
    expect(puts).toEqual([{ url: "/api/calendars/Shop", body: { dates: [], weekend: "NONE" } }]);
  });
});
