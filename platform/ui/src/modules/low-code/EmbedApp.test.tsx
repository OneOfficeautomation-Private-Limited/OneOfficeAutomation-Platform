import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi, type CrUser } from "../../api/cr";
import { HelpProvider } from "../../components";
import { Apps, AppView } from "./Apps";
import { EmbedApi, readEmbedLocation } from "./EmbedApp";

afterEach(cleanup);

const definition = { pages: [{ id: "p", title: "Notes", components: [
  { id: "who", kind: "input", name: "caller", label: "Caller" },
  { id: "save", kind: "button", label: "Save", action: { kind: "create_record" } },
  { id: "r", kind: "result" }] }] };

describe("Apps embedded in other sites", () => {
  it("reads the link id from the path, the key from the fragment and the fields from the query", () => {
    expect(readEmbedLocation({ pathname: "/embed/emb_0123456789abcdef", hash: "#k=ooae_secret", search: "?caller=%2B91%2098450&bad-name=x" }))
      .toEqual({ id: "emb_0123456789abcdef", key: "ooae_secret", initial: { caller: "+91 98450" } });
  });

  it("sends the key as a header to the embed calls only, and fills the fields from the host page", async () => {
    const calls: { url: string; key: string | null; body?: string; credentials?: string }[] = [];
    const f = (async (url: string, init: RequestInit) => {
      calls.push({ url, key: new Headers(init.headers).get("X-OOA-Embed-Key"), body: init.body as string | undefined, credentials: init.credentials });
      if (url.endsWith("/app")) return new Response(JSON.stringify({ id: "app_1", name: "Call notes", audience: [], published_by: "cara", definition }), { status: 200 });
      return new Response(JSON.stringify({ record_id: "rec_1", message: "Saved." }), { status: 200 });
    }) as typeof fetch;
    const api = new EmbedApi("emb_1", "ooae_k", f);
    render(<HelpProvider><AppView api={api} appId="emb_1" initial={{ caller: "+91 98450" }} /></HelpProvider>);
    expect(((await screen.findByLabelText("Caller")) as HTMLInputElement).value).toBe("+91 98450");
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    expect(await screen.findByText("Saved.")).toBeTruthy();
    expect(calls.map((c) => c.url)).toEqual(["/api/embed/emb_1/app", "/api/embed/emb_1/act"]);
    expect(calls.every((c) => c.key === "ooae_k" && c.credentials === "omit")).toBe(true);
    expect(JSON.parse(calls[1].body!)).toEqual({ component: "save", fields: { caller: "+91 98450" } });
  });

  it("lets app makers make an embed link, shown once, with the sites that may show it", async () => {
    const posts: unknown[] = [];
    const f = (async (url: string, init: RequestInit) => {
      const ok = (d: unknown) => new Response(JSON.stringify(d), { status: 200 });
      if (url === "/api/apps") return ok({ apps: [{ id: "app_1", name: "Call notes", audience: [], published_by: "cara" }] });
      if (url === "/api/apps/app_1/embeds" && init.method === "POST") { posts.push(JSON.parse(String(init.body))); return ok({ id: "emb_1", app_id: "app_1", name: "SF", origins: ["https://acme.lightning.force.com"], created: "2026-11-06T08:00:00Z", key: "ooae_k", path: "/embed/emb_1#k=ooae_k" }); }
      if (url === "/api/apps/app_1/embeds") return ok({ embeds: [] });
      return new Response("{}", { status: 404 });
    }) as typeof fetch;
    const user = { id: "1", username: "cara", display_name: "Cara", role: "bot_creator", permissions: ["apps.manage"] } as unknown as CrUser;
    render(<HelpProvider><Apps api={new CrApi("/api/", f)} user={user} onNotice={() => {}} /></HelpProvider>);
    fireEvent.keyDown(await screen.findByRole("button", { name: "Actions for Call notes" }), { key: "Enter" });
    fireEvent.click(await screen.findByRole("menuitem", { name: "Embed links…" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "SF" } });
    fireEvent.change(within(dialog).getByLabelText("Sites that may show it"), { target: { value: "https://acme.lightning.force.com, https://*.my.salesforce.com" } });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Make the link" })); });
    expect(posts[0]).toEqual({ name: "SF", origins: ["https://acme.lightning.force.com", "https://*.my.salesforce.com"] });
    expect((within(dialog).getByLabelText("Embed link") as HTMLInputElement).value).toMatch(/\/embed\/emb_1#k=ooae_k$/);
  });
});
