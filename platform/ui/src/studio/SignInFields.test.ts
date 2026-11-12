import { describe, expect, it } from "vitest";
import { takesAccessToken, withSignIn } from "./SignInFields";
import { connectionInputs } from "./stepKinds";
import type { PackageManifest } from "../api/client";

describe("connection sign-in (A1)", () => {
  it("keeps settings as values and the secret and refresh token as vault references", () => {
    let b = withSignIn({ values: { base_url: "x" }, credentials: {} }, "sign_in_provider", "microsoft");
    b = withSignIn(b, "sign_in_client_id", " app-9 ");
    b = withSignIn(b, "sign_in_client_secret", "apps/mail/secret");
    expect(b.values).toEqual({ base_url: "x", sign_in_provider: "microsoft", sign_in_client_id: "app-9" });
    expect(b.credentials).toEqual({ sign_in_client_secret: "apps/mail/secret" });
    // no sign-in clears every sign-in key, and only those
    expect(withSignIn(b, "sign_in_provider", "")).toEqual({ values: { base_url: "x" }, credentials: {} });
  });

  it("is offered for toolkits whose steps take an access token", () => {
    const tok = { inputs: { properties: { access_token: { "x-ooa-type": "Credential" } } } };
    expect(takesAccessToken([{}, tok])).toBe(true);
    expect(takesAccessToken([{ inputs: { properties: { password: { "x-ooa-type": "Credential" } } } }])).toBe(false);
  });

  it("gives Email (IMAP with OAuth) a connection with its Connect step's settings", () => {
    const p = (props: Record<string, { "x-ooa-type"?: string }>) => ({ inputs: { properties: props } });
    const email = { id: "ooa.email", name: "Email", actions: [
      { id: "connect", ...p({ imap_host: {}, user: {}, password: { "x-ooa-type": "Credential" }, access_token: { "x-ooa-type": "Credential" }, session: {}, timeout_seconds: {} }) },
      { id: "move", ...p({ id: {}, session: {} }) }, { id: "delete", ...p({ id: {}, session: {} }) }, { id: "mark", ...p({ id: {}, session: {} }) }],
    } as unknown as PackageManifest;
    expect([...connectionInputs(email)].sort()).toEqual(["access_token", "imap_host", "password", "user"]);
  });
});

describe("web connections (R2, batch 8)", () => {
  it("keep the base address, headers and sign-in of REST and SOAP steps, not the address of each call", () => {
    const p = (...names: string[]) => ({ inputs: { properties: Object.fromEntries(names.map((n) => [n, n.endsWith("token") || n.endsWith("password") ? { "x-ooa-type": "Credential" } : {}])) } });
    const rest = { id: "ooa.rest", name: "REST", actions: [{ id: "request", ...p("url", "method", "base_url", "headers", "bearer_token", "basic_user", "basic_password", "timeout_seconds") }] } as unknown as PackageManifest;
    expect([...connectionInputs(rest)]).toEqual(["base_url", "headers", "basic_user", "basic_password", "bearer_token"]);
    expect(takesAccessToken(rest.actions)).toBe(true);
  });
});

describe("sign-in for every connector (K1, batch 10)", () => {
  it("is offered for a toolkit that marks the input taking the token", async () => {
    const { takesAccessToken: takes, defaultGrant } = await import("./SignInFields");
    expect(takes([{ inputs: { properties: { bot_token: { "x-ooa-type": "Credential", "x-ooa-sign-in-token": true } } } }])).toBe(true);
    expect(takes([{ inputs: { properties: { password: { "x-ooa-type": "Credential" } } } }])).toBe(false);
    expect(defaultGrant("github")).toBe("github_app");
    expect(defaultGrant("snowflake")).toBe("snowflake_key_pair");
    expect(defaultGrant("other")).toBe("client_credentials");
  });
});
