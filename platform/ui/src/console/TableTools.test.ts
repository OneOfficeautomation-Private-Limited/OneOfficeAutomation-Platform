import { afterEach, describe, expect, it } from "vitest";
import { enableTableTools } from "./TableTools";

const frame = () => new Promise((r) => requestAnimationFrame(() => r(null)));

describe("column tools on every table", () => {
  let stop: (() => void) | null = null;
  afterEach(() => { stop?.(); document.body.innerHTML = ""; localStorage.clear(); });

  it("adds a filter to each named column, filters by the values ticked, keeps day rows, and skips tick-box and menu columns", async () => {
    document.body.innerHTML = `<table class="cc-list-table" aria-label="Runs"><thead><tr><th class="cc-check"></th><th>Status</th><th>Agent</th><th class="cc-list-menu"><span class="st-sr">Actions</span></th></tr></thead>
      <tbody><tr class="cc-au-day is-static"><td colspan="4">Wed 28 Oct</td></tr>
      <tr><td></td><td>Failed</td><td>Sort tickets</td><td></td></tr><tr><td></td><td>Completed</td><td>Sort tickets</td><td></td></tr><tr><td></td><td>Failed</td><td>Collect emails</td><td></td></tr></tbody></table>`;
    stop = enableTableTools(document);
    await frame();
    const buttons = [...document.querySelectorAll<HTMLButtonElement>(".cc-col-filter")];
    expect(buttons.map((b) => b.getAttribute("aria-label"))).toEqual(["Filter Status", "Filter Agent"]);
    expect(document.querySelectorAll(".cc-col-grip")).toHaveLength(2);

    buttons[0].click();
    const pop = document.querySelector('[role="dialog"][aria-label="Filter Status"]')!;
    const boxes = [...pop.querySelectorAll<HTMLInputElement>('[aria-label="Values of Status"] input')];
    expect(boxes.map((b) => b.value)).toEqual(["Completed", "Failed"]);
    boxes[0].click();   // untick Completed
    const rows = [...document.querySelectorAll("tbody tr")];
    expect(rows.map((r) => (r as HTMLElement).hidden)).toEqual([false, false, true, false]);   // the day heading stays while a row under it shows
    expect(buttons[0].classList.contains("is-on")).toBe(true);

    for (const b of boxes) if (b.checked) b.click();   // nothing ticked: the day heading goes too
    expect(rows.map((r) => (r as HTMLElement).hidden)).toEqual([true, true, true, true]);
    (pop.querySelector("button") as HTMLButtonElement).click();   // Clear filter
    expect(rows.every((r) => !(r as HTMLElement).hidden)).toBe(true);
    expect(buttons[0].classList.contains("is-on")).toBe(false);
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
