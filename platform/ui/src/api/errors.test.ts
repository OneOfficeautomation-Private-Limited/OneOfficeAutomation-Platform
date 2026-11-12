import { describe, expect, it } from "vitest";
import { ApiFailure, StudioApi } from "./client";
import { CrApi, CrFailure, noAnswer, withFix } from "./cr";

/** Improvement list 12 Nov 2026, item 11: a request with no usable answer says what happened and what to do. */
describe("errors without the API's own answer", () => {
  it("says the server is not answering, instead of the browser's Failed to fetch", async () => {
    const api = new CrApi("/api/", (async () => { throw new TypeError("Failed to fetch"); }) as unknown as typeof fetch);
    const e = await api.request("GET", "me").catch((x) => x);
    expect(e).toBeInstanceOf(CrFailure);
    expect(e.code).toBe("NET.UNREACHABLE");
    expect(e.message).toBe("One CommandCenter is not answering. Check that it is running and that this computer can reach it (network, VPN, proxy), then try again.");
  });

  it("explains a proxy's error page and an error status without the API's error", async () => {
    const page = new CrApi("/api/", (async () => new Response("<html>Bad gateway</html>", { status: 502, statusText: "Bad Gateway" })) as unknown as typeof fetch);
    const e = await page.request("GET", "me").catch((x) => x);
    expect(e.code).toBe("HTTP.502");
    expect(e.message).toMatch(/^One CommandCenter answered 502 \(Bad Gateway\)\. A proxy or gateway/);
    const html = new CrApi("/api/", (async () => new Response("<html>Sign in</html>", { status: 200 })) as unknown as typeof fetch);
    expect((await html.request("GET", "me").catch((x) => x)).code).toBe("HTTP.NOT_JSON");
    const studio = new StudioApi("/api/", (async () => new Response("", { status: 403 })) as unknown as typeof fetch);
    const s = await studio.info().catch((x) => x);
    expect(s).toBeInstanceOf(ApiFailure);
    expect(s.message).toBe("One AgentStudio answered 403. You do not have permission for this. An administrator can give it to your role.");
  });

  it("keeps the API's own error and adds its fix to the message once", async () => {
    const api = new CrApi("/api/", (async () => new Response(JSON.stringify({ error: { code: "X.Y", message: "The queue is full", suggestedFix: "Raise its limit." } }), { status: 409 })) as unknown as typeof fetch);
    const e = await api.request("POST", "q").catch((x) => x);
    expect(e.error.message).toBe("The queue is full");
    expect(e.message).toBe("The queue is full. Raise its limit.");
    expect(withFix({ message: "Done.", suggestedFix: "Done." })).toBe("Done.");
    expect(noAnswer("X", 500).suggestedFix).toMatch(/server log/);
  });
});
