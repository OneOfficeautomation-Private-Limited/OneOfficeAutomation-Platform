import { describe, expect, it } from "vitest";
import type { FieldSpec } from "../api/client";
import { choiceWords } from "./stepKinds";

describe("help names choices as the list does (K8, batch 10)", () => {
  it("replaces each choice's code at the start of a clause", () => {
    const spec = { enum: ["client_credentials", "password"], description: "client_credentials: the connected app's run-as user; password: a named user." } as FieldSpec;
    expect(choiceWords(spec)).toBe("Client credentials: the connected app's run-as user; Password: a named user.");
  });
  it("leaves the same word alone inside a sentence, and help without choices", () => {
    const spec = { enum: ["password"], description: "Sign in with a password: the one in the vault." } as FieldSpec;
    expect(choiceWords(spec)).toBe("Sign in with a password: the one in the vault.");
    expect(choiceWords({ description: "token: x" } as FieldSpec)).toBe("token: x");
  });
});
