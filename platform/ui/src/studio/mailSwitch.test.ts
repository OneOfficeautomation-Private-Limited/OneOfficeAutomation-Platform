import { describe, expect, it } from "vitest";
import type { Flow, PackageManifest } from "../api/client";
import { servicesUsed, switchMailService } from "./mailSwitch";

const props = (...names: string[]) => ({ properties: Object.fromEntries(names.map((n) => [n, {}])) });
const act = (id: string, inputs: string[], outputs: string[] = [], name = id) => ({ id, name, inputs: props(...inputs), outputs: props(...outputs) });
const PACKAGES = [
  { id: "ooa.microsoft365", name: "Microsoft 365", version: "1", actions: [
    act("list_mail", ["access_token", "folder", "unread_only", "read_only", "from_contains", "subject_contains", "max_results", "mailbox"], ["table", "count"], "Find emails (Outlook)"),
    act("get_mail", ["access_token", "id", "mark_read"], ["email"]), act("move_mail", ["access_token", "id", "to_folder"], ["new_id"]),
    act("mark_mail", ["access_token", "id", "status"]), act("reply_mail", ["access_token", "id", "comment", "attachments"])],
    iterators: [act("emails", ["access_token", "folder", "unread_only", "read_only", "subject_contains", "with_body"])] },
  { id: "ooa.google", name: "Google", version: "1", actions: [
    act("gmail_list_messages", ["access_token", "query", "label", "status", "from_contains", "max_results"], ["table", "count"]),
    act("gmail_move_message", ["access_token", "message_id", "add_labels", "remove_labels"], ["labels"])] },
] as unknown as PackageManifest[];

const flow = (steps: unknown[]): Flow => ({ kind: "TaskBot", id: "a", name: "a", steps } as unknown as Flow);

describe("switch mail service (A2)", () => {
  it("turns Exchange steps into Microsoft 365 ones, nested ones and loops included", () => {
    const f = flow([
      { id: "c", action: "ooa.exchange/connect", args: { ews_url: "https://mail/EWS", user: "bot" } },
      { id: "l", action: "ooa.exchange/list", args: { folder: "inbox", unread_only: true, body_contains: "overdue", session: "exchange" }, out: { table: "t", count: "n" } },
      { id: "loop", action: "core/loop", args: { iterator: "ooa.exchange/emails", iterator_args: { folder: "inbox", unread_only: true, body_contains: "x" } }, children: [
        { id: "m", action: "ooa.exchange/mark", args: { id: "$e.id", read: true } },
        { id: "r", action: "ooa.exchange/reply", args: { id: "$e.id", body: "Thanks", attachments: ["a.pdf"] }, connection: "old" }] },
      { id: "x", action: "core/log", args: { message: "done" } }]);
    const r = switchMailService(f, "exchange", "microsoft365", PACKAGES);
    const steps = (r.flow as unknown as { steps: Record<string, unknown>[] }).steps;
    expect(steps.map((s) => s.id)).toEqual(["l", "loop", "x"]);   // Connect removed: Microsoft 365 has no sessions
    expect(steps[0]).toMatchObject({ action: "ooa.microsoft365/list_mail", args: { folder: "inbox", unread_only: true }, out: { table: "t", count: "n" } });
    expect((steps[0].args as Record<string, unknown>).body_contains).toBeUndefined();
    const loop = steps[1] as { args: Record<string, unknown>; children: Record<string, unknown>[] };
    expect(loop.args.iterator).toBe("ooa.microsoft365/emails");
    expect(loop.args.iterator_args).toEqual({ folder: "inbox", unread_only: true });
    expect(loop.children[0]).toMatchObject({ action: "ooa.microsoft365/mark_mail", args: { id: "$e.id", status: "read" } });
    expect(loop.children[1]).toMatchObject({ action: "ooa.microsoft365/reply_mail", args: { id: "$e.id", comment: "Thanks", attachments: ["a.pdf"] } });
    expect(loop.children[1].connection).toBeUndefined();
    expect(steps[2]).toEqual({ id: "x", action: "core/log", args: { message: "done" } });
    const text = r.report.lines.join("\n");
    expect(text).toContain("Step c: Microsoft 365 (Outlook) needs no connect step; removed");
    expect(text).toContain("left out (no match): body_contains");
    expect(text).toContain("its connection was for Exchange on-premises (EWS)");
    expect(r.report.changed).toBe(5);
  });

  it("turns folders into Gmail labels and the read state into Gmail's status", () => {
    const f = flow([
      { id: "f", action: "ooa.email/list", args: { folder: "INBOX", read_only: true, from_contains: "acme" }, out: { table: "t" } },
      { id: "mv", action: "ooa.email/move", args: { id: "$id", to_folder: "Processed" } }]);
    const r = switchMailService(f, "email", "gmail", PACKAGES);
    const steps = (r.flow as unknown as { steps: Record<string, unknown>[] }).steps;
    expect(steps[0]).toMatchObject({ action: "ooa.google/gmail_list_messages", args: { label: "INBOX", status: "read", from_contains: "acme" } });
    expect(steps[1]).toMatchObject({ action: "ooa.google/gmail_move_message", args: { message_id: "$id", add_labels: "Processed", remove_labels: "INBOX" } });
    expect(r.report.lines.join("\n")).toContain("needs a connection or an access token");
  });

  it("lists the services an agent uses", () => {
    const f = flow([{ id: "a", action: "ooa.exchange/send", args: {} }, { id: "b", action: "ooa.exchange/list", args: {} },
      { id: "c", action: "ooa.microsoft365/upload_file", args: {} }, { id: "d", action: "core/loop", args: { iterator: "ooa.google/gmail_messages" } }]);
    expect(servicesUsed(f)).toEqual([{ id: "exchange", steps: 2 }, { id: "gmail", steps: 1 }]);
  });
});
