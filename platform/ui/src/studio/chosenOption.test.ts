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
  it("a new AI step shows the saved connection, whatever defaults the other option has (I1, batch 9)", () => {
    const g = { choice: "Model to use", options: [{ label: "A saved connection", fields: ["connection"] }, { label: "Set up here", fields: ["provider", "model"] }] } as unknown as Extract<PanelGroup, { choice: string }>;
    const ai = (args: Record<string, unknown>) => ({ id: "s", action: "ooa.genai/prompt", args }) as FlowStep;
    const provider = { provider: { default: "openai" } } as unknown as Record<string, FieldSpec>;
    expect(chosenOption(g, ai({}), provider)).toBe(0);
    expect(chosenOption(g, ai({ model: "gpt-4o" }), provider)).toBe(1);
  });
  it("other steps still show the option whose field has a default", () => {
    const rows = { choice: "Rows from", options: [{ label: "A Data Table", fields: ["table"] }, { label: "A delimited file", fields: ["file", "delimiter"] }] } as unknown as Extract<PanelGroup, { choice: string }>;
    const delimiter = { delimiter: { default: "," } } as unknown as Record<string, FieldSpec>;
    expect(chosenOption(rows, { id: "s", action: "ooa.database/bulk_insert", args: {} } as FlowStep, delimiter)).toBe(1);
  });
});
