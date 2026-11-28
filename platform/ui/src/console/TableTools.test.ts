import { afterEach, describe, expect, it } from "vitest";
import { enableTableTools } from "./TableTools";

const frame = () => new Promise((r) => requestAnimationFrame(() => r(null)));
const pop = (label: string) => document.querySelector<HTMLElement>('[role="dialog"][aria-label="Filter ' + label + '"]')!;
const button = (root: HTMLElement, text: string) => [...root.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === text)!;
const hidden = () => [...document.querySelectorAll("tbody tr")].map((r) => (r as HTMLElement).hidden);
const type = (input: HTMLInputElement | HTMLSelectElement, value: string) => { input.value = value; input.dispatchEvent(new Event(input.tagName === "SELECT" ? "change" : "input", { bubbles: true })); };
function table(head: string[], rows: string[][], cls = "cc-list-table") {
  document.body.innerHTML = `<table class="${cls}" aria-label="T"><thead><tr>${head.map((h) => "<th>" + h + "</th>").join("")}</tr></thead><tbody>${rows.map((r) => "<tr>" + r.map((c) => "<td>" + c + "</td>").join("") + "</tr>").join("")}</tbody></table>`;
}
const funnel = (label: string) => document.querySelector<HTMLButtonElement>('[aria-label="Filter ' + label + '"]')!;

describe("column tools on every table", () => {
  let stop: (() => void) | null = null;
  afterEach(() => { stop?.(); stop = null; document.body.innerHTML = ""; localStorage.clear(); });

  it("a few values: ticks with counts, applied with Apply; day rows stay while a row under them shows; tick-box and menu columns skipped", async () => {
    document.body.innerHTML = `<table class="cc-list-table" aria-label="Runs"><thead><tr><th class="cc-check"></th><th>Status</th><th>Agent</th><th class="cc-list-menu"><span class="st-sr">Actions</span></th></tr></thead>
      <tbody><tr class="cc-au-day is-static"><td colspan="4">Wed 28 Oct</td></tr>
      <tr><td></td><td>Failed</td><td>Sort tickets</td><td></td></tr><tr><td></td><td>Completed</td><td>Sort tickets</td><td></td></tr><tr><td></td><td>Failed</td><td>Collect emails</td><td></td></tr></tbody></table>`;
    stop = enableTableTools(document);
    await frame();
    const buttons = [...document.querySelectorAll<HTMLButtonElement>(".cc-col-filter")];
    expect(buttons.map((b) => b.getAttribute("aria-label"))).toEqual(["Filter Status", "Filter Agent"]);
    expect(document.querySelectorAll(".cc-col-grip")).toHaveLength(2);

    buttons[0].click();
    const p = pop("Status");
    expect(p.dataset.kind).toBe("values");
    const boxes = [...p.querySelectorAll<HTMLInputElement>('[aria-label="Values of Status"] input')];
    expect(boxes.map((b) => b.value)).toEqual(["Completed", "Failed"]);
    expect([...p.querySelectorAll(".cc-col-pop-n")].map((n) => n.textContent)).toEqual(["1", "2"]);   // counts per value
    boxes[0].click();   // untick Completed: nothing changes until Apply
    expect(hidden()).toEqual([false, false, false, false]);
    button(p, "Apply").click();
    expect(hidden()).toEqual([false, false, true, false]);
    expect(buttons[0].classList.contains("is-on")).toBe(true);
    expect(document.querySelector(".cc-col-filtered")!.textContent).toContain("Filtered by Status: 2 rows shown.");

    buttons[0].click();
    for (const b of pop("Status").querySelectorAll<HTMLInputElement>('[aria-label="Values of Status"] input')) if (b.checked) b.click();
    button(pop("Status"), "Apply").click();
    expect(hidden()).toEqual([true, true, true, true]);   // nothing ticked: the day heading goes too
    buttons[0].click();
    button(pop("Status"), "Clear").click();
    expect(hidden().every((h) => !h)).toBe(true);
    expect(buttons[0].classList.contains("is-on")).toBe(false);
    expect(document.querySelector(".cc-col-filtered")).toBeNull();
  });

  it("a small read-only list (cc-no-filter) gets no funnels", async () => {
    table(["Field", "Kind"], [["Title", "Text"], ["Date", "Date"], ["Total", "Number"]], "df-table cc-no-filter");
    stop = enableTableTools(document);
    await frame();
    expect(document.querySelectorAll(".cc-col-filter")).toHaveLength(0);
  });

  it("a long list of values gets a search box", async () => {
    table(["Name"], Array.from({ length: 12 }, (_, i) => ["Item " + i]).concat([["Item 0"]]));
    stop = enableTableTools(document);
    await frame();
    funnel("Name").click();
    const search = pop("Name").querySelector<HTMLInputElement>('input[type="search"]')!;
    expect(search).toBeTruthy();
    type(search, "item 1");
    const shown = [...pop("Name").querySelectorAll<HTMLElement>(".cc-col-pop-list label")].filter((l) => !l.hidden).map((l) => l.querySelector("input")!.value);
    expect(shown).toEqual(["Item 1", "Item 10", "Item 11"]);
    expect(pop("Name").dataset.kind).toBe("values");
  });

  it("many values: Contains, Starts with or Is, with a live count", async () => {
    table(["Error"], Array.from({ length: 20 }, (_, i) => [(i % 2 ? "Timeout at step " : "File missing ") + i]));
    stop = enableTableTools(document);
    await frame();
    funnel("Error").click();
    const p = pop("Error");
    expect(p.dataset.kind).toBe("text");
    const box = p.querySelector<HTMLInputElement>('[aria-label="Text to match in Error"]')!;
    type(box, "timeout");
    expect(p.querySelector(".cc-col-count")!.textContent).toBe("10 of 20 match");
    type(p.querySelector<HTMLSelectElement>('[aria-label="How to match Error"]')!, "starts");
    type(box, "file");
    expect(p.querySelector(".cc-col-count")!.textContent).toBe("10 of 20 match");
    type(p.querySelector<HTMLSelectElement>('[aria-label="How to match Error"]')!, "is");
    type(box, "File missing 4");
    expect(p.querySelector(".cc-col-count")!.textContent).toBe("1 of 20 match");
    button(p, "Apply").click();
    expect(hidden().filter((h) => !h)).toHaveLength(1);
  });

  it("durations: From and To in minutes, with quick choices", async () => {
    table(["Duration"], [["45 s"], ["3 min 4 s"], ["12 min 0 s"], ["1 h 5 min"]]);
    stop = enableTableTools(document);
    await frame();
    funnel("Duration").click();
    const p = pop("Duration");
    expect(p.dataset.kind).toBe("duration");
    expect([...p.querySelectorAll(".cc-col-chip")].map((c) => c.textContent)).toEqual(["Under 1 min", "1 to 5 min", "Over 5 min", "Over 1 hour"]);
    expect(p.querySelector('[aria-label="From (min)"]')).toBeTruthy();
    button(p, "Under 1 min").click();
    button(p, "Apply").click();
    expect(hidden()).toEqual([false, true, true, true]);
    funnel("Duration").click();
    const q = pop("Duration");
    type(q.querySelector<HTMLInputElement>('[aria-label="From (min)"]')!, "3");
    type(q.querySelector<HTMLInputElement>('[aria-label="To (min)"]')!, "15");
    button(q, "Apply").click();
    expect(hidden()).toEqual([true, false, false, true]);
  });

  it("numbers: From and To with the column's unit", async () => {
    table(["Share"], [["12 %"], ["50 %"], ["75 %"], ["99 %"]]);
    stop = enableTableTools(document);
    await frame();
    funnel("Share").click();
    const p = pop("Share");
    expect(p.dataset.kind).toBe("number");
    type(p.querySelector<HTMLInputElement>('[aria-label="From (%)"]')!, "50");
    button(p, "Apply").click();
    expect(hidden()).toEqual([true, false, false, false]);
  });

  it("dates: quick choices and From / To", async () => {
    // today's row at the current minute (not 10:00, which is still to come before ten in the morning: "Last 30 days" ends now)
    const now = new Date(), day = (n: number) => { const d = new Date(now); d.setDate(d.getDate() - n); if (n) d.setHours(10, 0, 0, 0); else d.setSeconds(0, 0); return d; };
    const fmt = (d: Date) => d.toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
    table(["Start time"], [[fmt(day(0))], [fmt(day(1))], [fmt(day(10))], [fmt(day(40))]]);
    stop = enableTableTools(document);
    await frame();
    funnel("Start time").click();
    const p = pop("Start time");
    expect(p.dataset.kind).toBe("date");
    expect([...p.querySelectorAll(".cc-col-choice")].map((c) => c.textContent)).toEqual(["Today", "Yesterday", "Last 7 days", "Last 30 days", "Last 90 days", "This month", "Last month"]);
    button(p, "Yesterday").click();
    expect(p.querySelector<HTMLInputElement>('[aria-label="From"]')!.value).not.toBe("");
    button(p, "Apply").click();
    expect(hidden()).toEqual([true, false, true, true]);
    funnel("Start time").click();
    button(pop("Start time"), "Last 30 days").click();
    button(pop("Start time"), "Apply").click();
    expect(hidden()).toEqual([false, false, false, true]);
  });

  it("Document Automation's tables get funnels too", async () => {
    table(["Status"], [["Ready"], ["Failed"]], "df-table");
    stop = enableTableTools(document);
    await frame();
    expect(funnel("Status")).toBeTruthy();
  });

  it("keeps a column's width once it is dragged, and puts it back for the same table", async () => {
    localStorage.setItem("ooa.columns.Runs", JSON.stringify({ Agent: 240 }));
    document.body.innerHTML = `<table class="cc-list-table" aria-label="Runs"><thead><tr><th>Status</th><th>Agent</th></tr></thead><tbody><tr><td>Failed</td><td>Sort</td></tr></tbody></table>`;
    stop = enableTableTools(document);
    await frame();
    const th = document.querySelectorAll("th")[1] as HTMLElement;
    expect(th.style.width).toBe("240px");
    expect(document.querySelector("table")!.classList.contains("cc-cols-fixed")).toBe(true);
    (th.querySelector(".cc-col-grip") as HTMLElement).dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));
    expect(th.style.width).toBe("");
    expect(localStorage.getItem("ooa.columns.Runs")).toBe("{}");
  });
});
