import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { SampleApi } from "./api";
import { ExtractStage } from "./stages";

/** Step 4 (28 Nov 2026): a field read "By AI" that the engine reading its type cannot work out is named, before it stays empty. */
describe("Step 4, Data extraction", () => {
  afterEach(cleanup);

  async function show(extraction: string) {
    const api = new SampleApi();
    const design = await api.processDesign("supplier-invoices-ksa");
    const type = (await api.docTypes()).find((t) => t.id === design.docTypeIds[0])!;
    await api.saveDocType({ ...type, fields: type.fields.map((f, i) => i === 0 ? { ...f, method: "work-out" as const } : f) });
    render(<ExtractStage s={{ ...design.settings, extraction }} set={() => undefined} api={api} processId={design.id}
      types={[{ id: type.id, name: type.name }]} />);
    return type.fields[0].label;
  }

  it("names the field the default rules extractor cannot work out", async () => {
    const field = await show("auto");
    const warn = await screen.findByLabelText("Fields the engine cannot read");
    expect(warn.textContent).toContain(field + " is read By AI, which Rules extractor cannot do: it will stay empty.");
  });

  it("says nothing when an AI engine reads the fields", async () => {
    await show("claude");
    await screen.findByRole("table", { name: /^Fields of / });
    await waitFor(() => expect(screen.getByText(/This process uses its own engine/)).toBeTruthy());
    expect(screen.queryByLabelText("Fields the engine cannot read")).toBeNull();
  });
});
