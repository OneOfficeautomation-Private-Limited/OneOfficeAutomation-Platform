import { describe, expect, it } from "vitest";
import { hasReadState, readState, readStateArgs } from "./mailFields";

describe("read state as one choice (A4)", () => {
  it("is drawn only where both ticks exist", () => {
    expect(hasReadState({ unread_only: {}, read_only: {} })).toBe(true);
    expect(hasReadState({ unread_only: {} })).toBe(false);
  });
  it("reads the two ticks", () => {
    expect(readState(undefined)).toBe("all");
    expect(readState({ unread_only: true })).toBe("unread");
    expect(readState({ read_only: true })).toBe("read");
    expect(readState({ unread_only: false, read_only: false })).toBe("all");
  });
  it("stores the choice as the two ticks, never both", () => {
    expect(readStateArgs("all")).toEqual({ unread_only: undefined, read_only: undefined });
    expect(readStateArgs("unread")).toEqual({ unread_only: true, read_only: undefined });
    expect(readStateArgs("read")).toEqual({ unread_only: undefined, read_only: true });
  });
});
