import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow } from "../api/client";
import { knownColumns, MoreConditions } from "./TableFields";

describe("Data Table fields (command review D3, D4)", () => {
  afterEach(cleanup);

  it("knows a table's columns from its variable and from the other steps on it", () => {
    const flow: Flow = { kind: "TaskBot", id: "t", name: "t", variables: [{ name: "orders", type: "Table", default: { columns: ["id", "amount"], rows: [] } }], steps: [
      { id: "s1", action: "ooa.datatable/sort", args: { table: "$orders", column: "city" } },
      { id: "s2", action: "core/if", children: [{ id: "s3", action: "ooa.datatable/filter", args: { table: "$orders", columns: ["status"] } }] },
      { id: "s4", action: "ooa.datatable/sort", args: { table: "$other", column: "zip" } }] };
    expect(knownColumns(flow, "$orders")).toEqual(["amount", "city", "id", "status"]);
  });

  it("adds conditions of column, comparison and value", () => {
    const onCommit = vi.fn();
    render(<MoreConditions value={undefined} columns={["amount", "city"]} onCommit={onCommit} />);
    fireEvent.click(screen.getByRole("button", { name: "Add a condition" }));
    const column = screen.getByPlaceholderText("Pick or type a column");
    fireEvent.change(column, { target: { value: "amount" } });
    fireEvent.blur(column);
    expect(onCommit).toHaveBeenLastCalledWith([{ operator: "eq", column: "amount" }]);
    fireEvent.change(screen.getByRole("combobox", { name: "Comparison" }), { target: { value: "gt" } });
    const value = screen.getByRole("textbox", { name: "Value" });
    fireEvent.change(value, { target: { value: "1000" } });
    fireEvent.blur(value);
    expect(onCommit).toHaveBeenLastCalledWith([{ operator: "gt", column: "amount", value: 1000 }]);
    expect((screen.getByRole("combobox", { name: "Comparison" }) as HTMLSelectElement).selectedOptions[0].text).toBe("is greater than");
  });
});
