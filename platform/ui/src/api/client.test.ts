import { describe, expect, it } from "vitest";
import { ApiFailure, StudioApi, defaultBase } from "./client";

type Call = { url: string; method: string; body?: string };

function fakeFetch(answer: (c: Call) => [number, unknown]) {
  const calls: Call[] = [];
  const f = (async (url: string, init: RequestInit) => {
    const c = { url, method: String(init.method), body: init.body as string | undefined };
    calls.push(c);
    const [status, data] = answer(c);
    return new Response(JSON.stringify(data), { status });
  }) as unknown as typeof fetch;
  return { f, calls };
}

describe("StudioApi", () => {
  it("finds the API in the new Studio's folder (and next to the preview's old folder)", () => {
    expect(defaultBase("http://localhost:8730/")).toBe("http://localhost:8730/api/");
    expect(defaultBase("http://localhost:8720/studio/")).toBe("http://localhost:8720/studio/api/");
    expect(defaultBase("http://localhost:8720/studio/index.html")).toBe("http://localhost:8720/studio/api/");
    expect(defaultBase("http://localhost:8730/next/")).toBe("http://localhost:8730/api/");
    expect(defaultBase("http://localhost:8720/studio/next/index.html")).toBe("http://localhost:8720/studio/api/");
  });

  it("creates agents through files/bot and other items through files/item", async () => {
    const { f, calls } = fakeFetch((c) => [200, { path: c.url.endsWith("bot") ? "Finance/A.flow.json" : "Finance/F.form.json" }]);
    const api = new StudioApi("/api/", f);
    expect(await api.create("TaskBot", "Finance", "A")).toBe("Finance/A.flow.json");
    expect(await api.create("form", "Finance", "F")).toBe("Finance/F.form.json");
    expect(calls.map((c) => [c.method, c.url, JSON.parse(c.body!)])).toEqual([
      ["POST", "/api/files/bot", { parent: "Finance", name: "A", kind: "TaskBot" }],
      ["POST", "/api/files/item", { parent: "Finance", name: "F", type: "form" }],
    ]);
  });

  it("turns the server's error into an ApiFailure with its code and message", async () => {
    const { f } = fakeFetch(() => [400, { error: { code: "WORKSPACE.BAD_NAME", message: "Invalid name 'a/b'" } }]);
    const err = await new StudioApi("/api/", f).createFolder("", "a/b").catch((e) => e);
    expect(err).toBeInstanceOf(ApiFailure);
    expect(err.error.code).toBe("WORKSPACE.BAD_NAME");
    expect(err.message).toBe("Invalid name 'a/b'");
  });
});
