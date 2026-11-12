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
