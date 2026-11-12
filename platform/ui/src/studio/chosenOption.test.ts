import { describe, expect, it } from "vitest";
import type { FieldSpec, FlowStep } from "../api/client";
import type { PanelGroup } from "./flowEdit";
import { chosenOption } from "./StepForm";

const signIn = { choice: "Sign in", options: [
  { label: "No sign-in", fields: [] }, { label: "User name and password", fields: ["basic_user", "basic_password"] },
  { label: "OAuth 2.0", fields: ["oauth_token_url", "oauth_client_auth"] }] } as unknown as Extract<PanelGroup, { choice: string }>;
const props = { oauth_client_auth: { default: "basic" } } as unknown as Record<string, FieldSpec>;
const step = (args: Record<string, unknown>) => ({ id: "s", action: "ooa.rest/request", args }) as FlowStep;

describe("which option of a choice a step shows (R3)", () => {
  it("shows the option whose fields are filled in", () => {
    expect(chosenOption(signIn, step({ basic_user: "bot" }), props)).toBe(1);
    expect(chosenOption(signIn, step({ oauth_token_url: "https://t" }), props)).toBe(2);
  });
  it("shows the empty option on a new step, not one that only has a default", () => {
    expect(chosenOption(signIn, step({}), props)).toBe(0);
  });
  it("falls back to an option with a default when there is no empty one", () => {
    const g = { choice: "Connect using", options: [{ label: "Details", fields: ["server"] }, { label: "Address", fields: ["url"] }] } as unknown as Extract<PanelGroup, { choice: string }>;
    expect(chosenOption(g, step({}), { url: { default: "x" } } as unknown as Record<string, FieldSpec>)).toBe(1);
  });
});
