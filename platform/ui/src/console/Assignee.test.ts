import { describe, expect, it } from "vitest";
import { assigneeText } from "./Inbox";

/** The Task inbox says who a task is given to in words, never as user:, role: or perm: codes. */
describe("assigneeText", () => {
  it("turns each kind of assignee into words", () => {
    expect(assigneeText("perm:docs.review")).toBe("Everyone who may review documents");
    expect(assigneeText("perm:queues.work")).toBe("Everyone who may use queues.work");
    expect(assigneeText("role:approver")).toBe("Role approver");
    expect(assigneeText("role:*")).toBe("Everyone");
    expect(assigneeText("user:rita")).toBe("rita");
    expect(assigneeText("role:finance, user:rita")).toBe("Role finance, rita");
  });

  it("says when nobody is named", () => {
    expect(assigneeText(null)).toBe("Nobody yet");
    expect(assigneeText("")).toBe("Nobody yet");
  });
});
