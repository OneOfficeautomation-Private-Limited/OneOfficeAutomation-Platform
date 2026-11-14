import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AgentInputs, missingInputs, parseCsv, type InputSpec, type VaultRef } from "./AgentInputs";

function Host({ inputs, start = {}, onValue, onJsonError, refs, credentials }: { inputs: InputSpec[] | null; start?: Record<string, unknown>; onValue?: (v: Record<string, unknown>) => void;
  onJsonError?: (e: string | null) => void; refs?: string; credentials?: () => Promise<VaultRef[]> }) {
  const [v, setV] = useState(start);
  return <AgentInputs inputs={inputs} value={v} onChange={(x) => { setV(x); onValue?.(x); }} onJsonError={onJsonError} refs={refs} credentials={credentials} />;
}
const last = (f: ReturnType<typeof vi.fn>) => f.mock.calls[f.mock.calls.length - 1][0];

describe("AgentInputs", () => {
  afterEach(cleanup);
  it("shows one field per input with its type word, description and required mark, typed as numbers, yes/no and dates", () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} inputs={[{ name: "vendor", type: "String", required: true, description: "Who sent it" }, { name: "total", type: "Number", default: 0 },
      { name: "urgent", type: "Boolean" }, { name: "due", type: "Date" }, { name: "region", type: "String", enum: ["EU", "US"] }]} />);
    expect(screen.getByText("Text · Who sent it")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("vendor *"), { target: { value: "Acme" } });
    const total = screen.getByLabelText("total") as HTMLInputElement;
    expect(total.type).toBe("number");
    expect(total.placeholder).toBe("Default: 0");
    fireEvent.change(total, { target: { value: "1200" } });
    fireEvent.change(screen.getByLabelText("urgent"), { target: { value: "true" } });
    expect((screen.getByLabelText("due") as HTMLInputElement).type).toBe("date");
    fireEvent.change(screen.getByLabelText("due"), { target: { value: "2026-12-01" } });
    fireEvent.change(screen.getByLabelText("region"), { target: { value: "US" } });
    expect(last(onValue)).toEqual({ vendor: "Acme", total: 1200, urgent: true, due: "2026-12-01", region: "US" });
    fireEvent.change(screen.getByLabelText("vendor *"), { target: { value: "" } });   // empty: not set
    expect(last(onValue)).not.toHaveProperty("vendor");
  });

  it("edits a List with Add and Remove and a Dictionary as name and value rows", () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} inputs={[{ name: "ids", type: "List" }, { name: "headers", type: "Dictionary" }]} />);
    const ids = screen.getByRole("button", { name: "Add an item" });
    fireEvent.click(ids); fireEvent.click(ids);
    fireEvent.change(screen.getByLabelText("Item 1"), { target: { value: "INV-1" } });
    fireEvent.change(screen.getByLabelText("Item 2"), { target: { value: "42" } });
    expect(last(onValue).ids).toEqual(["INV-1", 42]);
    fireEvent.click(screen.getByRole("button", { name: "Remove item 1" }));
    expect(last(onValue).ids).toEqual([42]);
    fireEvent.click(screen.getByRole("button", { name: "Add a name and value" }));
    fireEvent.change(screen.getByLabelText("Name 1"), { target: { value: "Accept" } });
    fireEvent.change(screen.getByLabelText("Value 1"), { target: { value: "json" } });
    expect(last(onValue).headers).toEqual({ Accept: "json" });
  });

  it("edits a Table in a grid or from pasted CSV, kept as columns and rows", () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} inputs={[{ name: "lines", type: "Table" }]} />);
    fireEvent.click(screen.getByRole("button", { name: "Paste CSV" }));
    fireEvent.change(screen.getByLabelText("Table as CSV"), { target: { value: 'Name,Amount\nAcme,1200\n"Smith, J",5' } });
    fireEvent.click(screen.getByRole("button", { name: "Use this table" }));
    expect(last(onValue).lines).toEqual({ columns: ["Name", "Amount"], rows: [["Acme", 1200], ["Smith, J", 5]] });
    fireEvent.click(screen.getByRole("button", { name: "Add a row" }));
    fireEvent.change(screen.getByLabelText("Name, row 3"), { target: { value: "Zed" } });
    expect(last(onValue).lines.rows[2]).toEqual(["Zed", ""]);
    fireEvent.click(screen.getByRole("button", { name: "Remove column Amount" }));
    expect(last(onValue).lines.columns).toEqual(["Name"]);
  });

  it("picks a Credential by vault folder, credential and attribute", async () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} inputs={[{ name: "login", type: "Credential" }]}
      credentials={async () => [{ locker: "Finance", name: "SAP", attribute: "password" }, { locker: "Finance", name: "SAP", attribute: "username" }, { locker: "HR", name: "Portal", attribute: "token" }]} />);
    await act(async () => {});
    const g = screen.getByRole("group", { name: "Vault reference" });
    fireEvent.change(within(g).getByLabelText("Vault folder"), { target: { value: "Finance" } });
    fireEvent.change(within(g).getByLabelText("Credential"), { target: { value: "SAP" } });
    expect(within(g).getAllByRole("option").map((o) => o.textContent)).toContain("username");
    fireEvent.change(within(g).getByLabelText("Attribute"), { target: { value: "password" } });
    expect(last(onValue)).toEqual({ login: { $credential: "Finance/SAP/password" } });
  });

  it("types the vault reference when the vault cannot be read", async () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} inputs={[{ name: "login", type: "Credential" }]} credentials={async () => { throw new Error("not connected"); }} />);
    await act(async () => {});
    expect(screen.getByText(/The vault could not be read here/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Vault folder"), { target: { value: "Finance" } });
    fireEvent.change(screen.getByLabelText("Credential"), { target: { value: "SAP" } });
    fireEvent.change(screen.getByLabelText("Attribute"), { target: { value: "password" } });
    expect(last(onValue)).toEqual({ login: { $credential: "Finance/SAP/password" } });
  });

  it("takes a reference instead of a fixed value where the place allows it", () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} refs="$form.field$" inputs={[{ name: "order", type: "String" }, { name: "amount", type: "Number" }]} />);
    expect(screen.getByText("Text · may use $form.field$")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("order"), { target: { value: "$form.order_id$" } });
    fireEvent.change(screen.getByLabelText("How amount is given"), { target: { value: "ref" } });
    fireEvent.change(screen.getByLabelText("amount"), { target: { value: "$form.amount$" } });
    expect(last(onValue)).toEqual({ order: "$form.order_id$", amount: "$form.amount$" });
  });

  it("edits as JSON, shows bad JSON as an error and keeps the last good values", () => {
    const onValue = vi.fn(), onJsonError = vi.fn();
    render(<Host onValue={onValue} onJsonError={onJsonError} start={{ vendor: "Acme" }} inputs={[{ name: "vendor", type: "String" }]} />);
    fireEvent.click(screen.getByRole("button", { name: "Edit as JSON" }));
    const box = screen.getByLabelText("Inputs as JSON") as HTMLTextAreaElement;
    expect(JSON.parse(box.value)).toEqual({ vendor: "Acme" });
    fireEvent.change(box, { target: { value: '{"vendor": "Bolt"' } });
    expect(screen.getByRole("alert").textContent).toMatch(/^Not valid JSON, so nothing is kept yet/);
    expect(last(onJsonError)).toMatch(/Not valid JSON/);
    expect(onValue).not.toHaveBeenCalled();
    expect((screen.getByRole("button", { name: "Back to the fields" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(box, { target: { value: '{"vendor": "Bolt", "extra": 1}' } });
    expect(last(onJsonError)).toBeNull();
    expect(last(onValue)).toEqual({ vendor: "Bolt", extra: 1 });
    fireEvent.click(screen.getByRole("button", { name: "Back to the fields" }));
    expect((screen.getByLabelText("vendor") as HTMLInputElement).value).toBe("Bolt");
    expect(screen.getByText(/Also set, but not an input of this agent/)).toBeTruthy();   // nothing is dropped
  });

  it("gives free name and value rows when the agent's inputs are not known", () => {
    const onValue = vi.fn();
    render(<Host onValue={onValue} inputs={null} />);
    fireEvent.click(screen.getByRole("button", { name: "Add a name and value" }));
    fireEvent.change(screen.getByLabelText("Name 1"), { target: { value: "amount" } });
    fireEvent.change(screen.getByLabelText("Value 1"), { target: { value: "1200" } });
    expect(last(onValue)).toEqual({ amount: 1200 });
  });

  it("names the required inputs left empty, and reads CSV with quotes, semicolons and tabs", () => {
    expect(missingInputs([{ name: "a", required: true }, { name: "b", required: true, default: 1 }, { name: "c" }], {})).toEqual(["a"]);
    expect(parseCsv("A;B\n1;2")).toEqual({ columns: ["A", "B"], rows: [["1", "2"]] });
    expect(parseCsv("A\tB\r\nx\ty")).toEqual({ columns: ["A", "B"], rows: [["x", "y"]] });
    expect(parseCsv('A,B\n"say ""hi""",2')).toEqual({ columns: ["A", "B"], rows: [['say "hi"', "2"]] });
  });
});
