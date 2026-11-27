import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Menu, pointerOf, type MenuPoint } from "./Menu";
import { PickerDialog, filterTree } from "./PickerLayout";
import { placePopup } from "./placePopup";
import { SearchSelect } from "./SearchSelect";
import { Select } from "./Field";
import type { TreeNode } from "./Tree";

// Item 3 of the fix list of 27 Nov 2026, round 2: menus, pickers and lists (3a to 3e)
beforeEach(() => { try { localStorage.clear(); } catch { /* no storage */ } });
afterEach(cleanup);

describe("menus (3a, 3b)", () => {
  const items = [
    { key: "open", label: "Open", description: "Opens it in the editor", shortcut: "Enter" },
    { key: "delete", label: "Delete…", separatorBefore: true },
  ];

  it("open from their button with the right edges lined up (align end), one line per entry, Delete in the error colour", async () => {
    render(<Menu label="Row menu" items={items} trigger={<button type="button">More</button>} />);
    const button = screen.getByRole("button", { name: "More" });
    await act(async () => { button.focus(); fireEvent.keyDown(button, { key: "ArrowDown" }); });
    const menu = await screen.findByRole("menu");
    expect(menu.getAttribute("data-align")).toBe("end");
    expect(menu.getAttribute("data-side")).toBe("bottom");
    expect(menu.getAttribute("data-at")).toBe("button");
    const open = within(menu).getByRole("menuitem", { name: "Open, Opens it in the editor" });
    expect(open.textContent).toBe("OpenEnter");            // the description is not a second line…
    expect(open.title).toBe("Opens it in the editor");     // …it shows on hover
    expect(within(menu).getByRole("separator")).toBeTruthy();
    expect(within(menu).getByRole("menuitem", { name: "Delete…" }).classList.contains("is-danger")).toBe(true);
  });

  it("open at the pointer after a right-click, and give the focus back to their button when closed", async () => {
    function Row() {
      const [open, setOpen] = useState(false);
      const [at, setAt] = useState<MenuPoint | null>(null);
      return <div data-testid="row" onContextMenu={(e) => { e.preventDefault(); setAt(pointerOf(e)); setOpen(true); }}>
        <Menu label="Row menu" items={items} open={open} at={at} onOpenChange={(o) => { if (o) setAt(null); setOpen(o); }}
          trigger={<button type="button">More</button>} />
      </div>;
    }
    render(<Row />);
    await act(async () => { fireEvent.contextMenu(screen.getByTestId("row"), { clientX: 420, clientY: 300 }); });
    const menu = await screen.findByRole("menu");
    expect(menu.getAttribute("data-at")).toBe("pointer");
    expect(menu.getAttribute("data-align")).toBe("start");
    const anchor = document.querySelector<HTMLElement>(".ui-menu-at")!;
    expect([anchor.style.left, anchor.style.top]).toEqual(["420px", "300px"]);
    await act(async () => { fireEvent.keyDown(menu, { key: "Escape" }); await new Promise((r) => setTimeout(r, 5)); });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.querySelector(".ui-menu-at")).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "More" }));
  });

  it("take the pointer only from a right-click (the keyboard's menu key opens at the button)", () => {
    expect(pointerOf({ type: "contextmenu", clientX: 10, clientY: 20 })).toEqual({ x: 10, y: 20 });
    expect(pointerOf({ type: "contextmenu", clientX: 0, clientY: 0 })).toBeNull();
    expect(pointerOf({ type: "keydown" })).toBeNull();
  });
});

describe("one placing rule for pop-ups that are not menus (3b)", () => {
  const view = { w: 1000, h: 800 };
  it("goes below its field, moved in from the right edge", () => {
    expect(placePopup({ left: 900, top: 100, right: 980, bottom: 130 }, { w: 380, h: 300 }, {}, view)).toMatchObject({ left: 612, top: 134, side: "bottom" });
  });
  it("opens above when there is no room below", () => {
    expect(placePopup({ left: 100, top: 700, right: 300, bottom: 730 }, { w: 380, h: 300 }, {}, view)).toMatchObject({ top: 396, side: "top" });
  });
  it("lines up its right edge with align end", () => {
    expect(placePopup({ left: 500, top: 100, right: 600, bottom: 130 }, { w: 200, h: 100 }, { align: "end" }, view).left).toBe(400);
  });
  it("a fly-out goes right of its icon, or left when there is no room, and up from the window's foot", () => {
    expect(placePopup({ left: 0, top: 700, right: 40, bottom: 740 }, { w: 220, h: 300 }, { side: "right" }, view)).toMatchObject({ left: 44, top: 492, side: "right" });
    expect(placePopup({ left: 900, top: 10, right: 940, bottom: 50 }, { w: 220, h: 100 }, { side: "right" }, view)).toMatchObject({ left: 676, side: "left" });
  });
});

describe("the picker layout (3c, 3e)", () => {
  const nodes: TreeNode[] = [{ id: "", label: "Agents", children: [
    { id: "fin", label: "Finance", children: [{ id: "fin/inv", label: "Invoices received from suppliers abroad", children: [] }] },
    { id: "hr", label: "HR", children: [{ id: "hr/on", label: "Onboarding" }] }] }];
  function Picker({ onChoose = () => {} }: { onChoose?: () => void }) {
    const [open, setOpen] = useState(new Set([""]));
    const [sel, setSel] = useState("");
    return <PickerDialog id="test" title="Find a thing" onClose={() => {}} canChoose={!!sel} onChoose={onChoose} chosen={sel ? "Agents › " + sel : undefined}
      search={{ value: "", onChange: () => {}, label: "Search in Agents" }} count="3 items"
      tree={{ label: "Folders", nodes, selected: sel, onSelect: setSel, expanded: open, onExpanded: setOpen, pathOf: (n) => "Agents › " + n.label }}>
      <table><tbody><tr><td>One</td></tr></tbody></table>
    </PickerDialog>;
  }

  it("has its title with Maximise and Close, the tool bar, the tree with its tools, and Chosen, Cancel and Choose", () => {
    const onChoose = vi.fn();
    render(<Picker onChoose={onChoose} />);
    const d = screen.getByRole("dialog", { name: "Find a thing" });
    expect(within(d).getByRole("button", { name: "Maximise" })).toBeTruthy();
    expect(within(d).getByText("3 items")).toBeTruthy();
    expect(within(d).getByLabelText("Search in Agents")).toBeTruthy();
    expect((within(d).getByRole("button", { name: "Choose" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(d).getByText("Finance"));
    expect(within(d).getByText("Agents › fin")).toBeTruthy();
    fireEvent.click(within(d).getByRole("button", { name: "Choose" }));
    expect(onChoose).toHaveBeenCalled();
  });

  it("shows full folder names (wrapping, not cut) with the full path on hover, 16 px a level", () => {
    render(<Picker />);
    fireEvent.click(screen.getByRole("button", { name: "Expand all folders" }));
    const label = screen.getByText("Invoices received from suppliers abroad");
    expect(label.title).toBe("Agents › Invoices received from suppliers abroad");
    expect(label.closest(".ui-tree")!.classList.contains("is-wrap")).toBe(true);
    expect((label.closest("[role=treeitem]") as HTMLElement).style.paddingLeft).toBe("38px");   // 6 + 2 levels × 16
  });

  it("opens and closes every folder with Expand all and Collapse all, and finds a folder by name", () => {
    render(<Picker />);
    expect(screen.queryByText("Onboarding")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Expand all folders" }));
    expect(screen.getByText("Onboarding")).toBeTruthy();
    expect(screen.getByText("Invoices received from suppliers abroad")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Collapse all folders" }));
    expect(screen.queryByText("Onboarding")).toBeNull();
    expect(screen.getByText("Finance")).toBeTruthy();          // the top folder stays open
    fireEvent.change(screen.getByLabelText("Find a folder"), { target: { value: "board" } });
    expect(screen.getByText("Onboarding")).toBeTruthy();
    expect(screen.queryByText("Finance")).toBeNull();
  });

  it("remembers the tree's width per picker", () => {
    render(<Picker />);
    const bar = screen.getByRole("separator", { name: "Resize the folders pane" });
    fireEvent.keyDown(bar, { key: "End" });
    expect(JSON.parse(localStorage.getItem("ooa.studio.size.picker.test")!)).toBe(560);
    cleanup();
    render(<Picker />);
    expect((screen.getByRole("group", { name: "Folders" }) as HTMLElement).style.width).toBe("560px");
  });

  it("filters a tree to the matching folders and the folders above them", () => {
    const f = filterTree(nodes, "onb");
    expect(f.nodes[0].children!.map((n) => n.label)).toEqual(["HR"]);
    expect([...f.open]).toEqual(["hr", ""]);
  });
});

describe("searchable dropdowns (3d)", () => {
  const many = Array.from({ length: 20 }, (_, i) => ({ value: "v" + i, label: "Thing " + i, group: i < 10 ? "Text" : "Number" }));

  it("a list of more than 12 is searchable; 12 or fewer stay the browser's own", () => {
    const { rerender } = render(<Select aria-label="Pick" value="v1" onChange={() => {}} options={many.slice(0, 12)} />);
    expect(screen.getByLabelText("Pick").tagName).toBe("SELECT");
    rerender(<Select aria-label="Pick" value="v1" onChange={() => {}} options={many} />);
    const box = screen.getByRole("combobox", { name: "Pick" }) as HTMLInputElement;
    expect(box.tagName).toBe("INPUT");
    expect(box.value).toBe("Thing 1");
  });

  it("filters as you type, moves with the arrows, takes Enter, and counts what it shows", () => {
    const onChange = vi.fn();
    render(<Select aria-label="Pick" value="" onChange={(e) => onChange(e.target.value)} options={many} noun="things" />);
    const box = screen.getByRole("combobox", { name: "Pick" });
    fireEvent.focus(box);
    expect(screen.getByText("20 things")).toBeTruthy();
    expect(screen.getAllByRole("presentation").map((h) => h.textContent)).toEqual(["Text", "Number"]);   // group headings
    fireEvent.change(box, { target: { value: "thing 1" } });
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["Thing 1", "Thing 10", "Thing 11", "Thing 12", "Thing 13", "Thing 14",
      "Thing 15", "Thing 16", "Thing 17", "Thing 18", "Thing 19"]);
    expect(screen.getByText("11 of 20 things")).toBeTruthy();
    fireEvent.keyDown(box, { key: "ArrowDown" });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith("v10");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("switches between the short and the tall view with ⤢, and remembers the height per kind of list", () => {
    render(<SearchSelect aria-label="Zone" value="" options={many} onChange={() => {}} kind="zones" noun="zones" />);
    fireEvent.focus(screen.getByRole("combobox", { name: "Zone" }));
    const list = () => screen.getByRole("listbox");
    expect(list().style.height).toBe("232px");                      // eight rows
    fireEvent.click(screen.getByRole("button", { name: "Tall list" }));
    expect(localStorage.getItem("ooa.studio.list.height.zones")).not.toBeNull();
    expect(screen.getByRole("button", { name: "Short list" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(screen.getByRole("button", { name: "Short list" }));
    expect(localStorage.getItem("ooa.studio.list.height.zones")).toBe("null");
    // the grip: a row taller with the arrow key, kept for the next list of that kind
    fireEvent.keyDown(screen.getByRole("separator", { name: "List height (drag)" }), { key: "ArrowDown" });
    expect(localStorage.getItem("ooa.studio.list.height.zones")).toBe("260");
    cleanup();
    render(<SearchSelect aria-label="Zone" value="" options={many} onChange={() => {}} kind="zones" />);
    fireEvent.focus(screen.getByRole("combobox", { name: "Zone" }));
    expect(screen.getByRole("listbox").style.height).toBe("260px");
  });

  it("Esc closes the list and keeps the choice", () => {
    render(<SearchSelect aria-label="Zone" value="v3" options={many} onChange={() => {}} />);
    const box = screen.getByRole("combobox", { name: "Zone" }) as HTMLInputElement;
    fireEvent.focus(box);
    fireEvent.change(box, { target: { value: "x" } });
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(box.value).toBe("Thing 3");
  });
});
