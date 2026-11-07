import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi } from "../api/cr";
import { HelpProvider } from "../components";
import { Settings } from "./Settings";

afterEach(cleanup);

function server(modules: unknown[] | null) {
  const f = (async (url: string) => {
    const ok = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });
    if (url === "/api/settings") return ok({});
    if (url === "/api/database") return ok({ engine: "SQL Server", version: "16.0", database: "ooa", schema_version: 46, schema_latest: 46, encrypted: false });
    if (url === "/api/cluster") return ok({ nodes: [{ node_id: "n1", leader: true, this_node: true }] });
    if (url === "/api/modules" && modules) return ok({ modules });
    return new Response(JSON.stringify({ error: { code: "CR.NOT_FOUND", message: "no" } }), { status: 404 });
  }) as typeof fetch;
  return new CrApi("/api/", f);
}

const openAbout = async () => {
  fireEvent.click(await screen.findByRole("button", { name: /This One CommandCenter/ }));
  return screen.findByText(/node\(s\)/);
};

describe("Settings > This One CommandCenter", () => {
  it("lists each feature module with its own version (phase 8)", async () => {
    render(<HelpProvider><Settings api={server([
      { id: "document-automation", name: "Document Automation", version: "1.2.0", schema: 0 },
      { id: "insights", name: "Insights", version: "1.0.0", schema: 2 }])} onNotice={() => {}} /></HelpProvider>);
    await openAbout();
    const table = await screen.findByRole("table", { name: "Modules" });
    const rows = within(table).getAllByRole("row").map((r) => r.textContent);
    expect(rows).toEqual(["ModuleVersionOwn database steps", "Document Automation1.2.0none (kernel tables)", "Insights1.0.02"]);
  });

  it("shows the rest when the modules cannot be read", async () => {
    render(<HelpProvider><Settings api={server(null)} onNotice={() => {}} /></HelpProvider>);
    await openAbout();
    expect(screen.queryByRole("table", { name: "Modules" })).toBeNull();
    expect(screen.getByText("SQL Server 16.0")).toBeTruthy();
  });
});
