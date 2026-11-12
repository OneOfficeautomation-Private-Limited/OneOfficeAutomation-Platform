import type { Flow, FlowStep, PackageManifest } from "../api/client";

/**
 * A2 (command review, batch 7): "Switch mail service" turns the mail steps of an agent from one service into another
 * (e.g. Exchange or IMAP to Microsoft 365, which Microsoft and AA now ask for), as one change that can be undone.
 * Each mail command has its match in the other service; fields are renamed where the services name them differently,
 * values are converted where they differ (read yes/no and status; folders and Gmail labels), and every field is
 * checked against the target toolkit's catalogue. What has no match is left out and listed, never guessed.
 */
export type MailService = "email" | "exchange" | "microsoft365" | "gmail" | "outlook";

export const MAIL_SERVICES: { id: MailService; pkg: string; name: string; sessions: boolean }[] = [
  { id: "email", pkg: "ooa.email", name: "Email (IMAP and SMTP)", sessions: true },
  { id: "exchange", pkg: "ooa.exchange", name: "Exchange on-premises (EWS)", sessions: true },
  { id: "microsoft365", pkg: "ooa.microsoft365", name: "Microsoft 365 (Outlook)", sessions: false },
  { id: "gmail", pkg: "ooa.google", name: "Gmail", sessions: false },
  { id: "outlook", pkg: "ooa.outlook_desktop", name: "Outlook desktop", sessions: false },
];

/** The same mail command on each service (null: the service has none). Order: email, exchange, microsoft365, gmail, outlook. */
const OPS: Record<string, (string | null)[]> = {
  send: ["send", "send", "send_mail", "gmail_send", "send"],
  find: ["list", "list", "list_mail", "gmail_list_messages", "list"],
  read: ["get", "get", "get_mail", "gmail_get_message", "get"],
  reply: ["reply", "reply", "reply_mail", "gmail_reply_message", "reply"],
  reply_all: ["reply_all", "reply_all", "reply_all_mail", "gmail_reply_all", null],
  forward: ["forward", "forward", "forward_mail", "gmail_forward_message", "forward"],
  move: ["move", "move", "move_mail", "gmail_move_message", "move"],
  mark: ["mark", "mark", "mark_mail", "gmail_mark_message", "mark"],
  delete: ["delete", "delete", "delete_mail", "gmail_delete_message", "delete"],
  save_attachments: ["save_attachments", "save_attachments", "save_mail_attachments", "gmail_save_attachments", "save_attachments"],
  save_eml: ["save_email_as_eml", "save_email_as_eml", "save_mail_as_eml", "gmail_save_message_as_eml", null],
  save_msg: ["save_email_as_msg", null, "save_mail_as_msg", null, null],
  folders: ["get_folders", "get_folders", null, null, "get_folders"],
  folder_exists: ["folder_exists", null, "mail_folder_exists", "gmail_label_exists", null],
  delete_all: ["delete_all", null, "delete_all_mail", "gmail_delete_all", null],
  move_all: ["move_all", null, "move_all_mail", "gmail_move_all", null],
  save_all_attachments: ["save_all_attachments", null, "save_all_mail_attachments", "gmail_save_all_attachments", null],
  connect: ["connect", "connect", null, null, null],
  disconnect: ["disconnect", "disconnect", null, null, null],
};
/** The each-email loop type of each service. */
const LOOPS: (string | null)[] = ["emails", "emails", "emails", "gmail_messages", "emails"];

const index = (s: MailService) => MAIL_SERVICES.findIndex((x) => x.id === s);
const pkgOf = (s: MailService) => MAIL_SERVICES[index(s)].pkg;

/** A field's common name (Email's), from a service's own name for it in a command. */
function common(s: MailService, op: string, name: string): string {
  if (s === "gmail" && name === "message_id") return "id";
  if (s === "microsoft365" && name === "comment") return op === "forward" ? "note" : "body";
  if (s === "outlook" && op === "forward" && name === "body") return "note";
  if (s === "outlook" && name === "folder") return op === "move" ? "to_folder" : op === "save_attachments" ? "target_folder" : "folder";
  if (s === "outlook" && name === "max") return "max_results";
  if (s === "outlook" && name === "received_after") return "since";
  if (s === "outlook" && name === "from_account") return "from";
  if (s === "gmail" && name === "label") return "folder";
  return name;
}

/** The service's own name for a field's common name in a command. */
function own(s: MailService, op: string, name: string): string {
  if (s === "gmail" && name === "id") return "message_id";
  if (s === "gmail" && name === "folder") return "label";
  if (s === "microsoft365" && ((op === "forward" && name === "note") || ((op === "reply" || op === "reply_all") && name === "body"))) return "comment";
  if (s === "outlook" && op === "forward" && name === "note") return "body";
  if (s === "outlook" && ((op === "move" && name === "to_folder") || (op === "save_attachments" && name === "target_folder"))) return "folder";
  if (s === "outlook" && name === "max_results") return "max";
  if (s === "outlook" && name === "since") return "received_after";
  if (s === "outlook" && name === "from") return "from_account";
  return name;
}

/** Arguments whose meaning is written differently: read state, mark status, Gmail labels for folders. */
function convertValues(from: MailService, to: MailService, op: string, args: Record<string, unknown>, notes: string[]): Record<string, unknown> {
  const a = { ...args };
  const statusful = (s: MailService) => s === "gmail" || s === "microsoft365";
  if (op === "mark") {
    if (statusful(from) && !statusful(to) && typeof a.status === "string") {
      if (a.status === "read" || a.status === "unread") a.read = a.status === "read"; else notes.push("status " + a.status + " has no match: only read or unread can be set");
      delete a.status;
    } else if (!statusful(from) && statusful(to) && a.read !== undefined) { a.status = a.read === false ? "unread" : "read"; delete a.read; }
  }
  // find, loops and bulk steps: Gmail's status choice and the others' two ticks
  if (from === "gmail" && to !== "gmail" && typeof a.status === "string" && op !== "mark") {
    if (a.status === "unread") a.unread_only = true; else if (a.status === "read") a.read_only = true;
    delete a.status;
  } else if (from !== "gmail" && to === "gmail" && op !== "mark" && (a.unread_only !== undefined || a.read_only !== undefined)) {
    a.status = a.unread_only === true ? "unread" : a.read_only === true ? "read" : "any";
    delete a.unread_only; delete a.read_only;
  }
  if (to === "gmail" && (op === "move" || op === "move_all") && a.to_folder !== undefined) {
    a.add_labels = a.to_folder; delete a.to_folder;
    if (op === "move") a.remove_labels = "INBOX";
    notes.push("the folder became a label to add, and INBOX is taken off (Gmail has labels, not folders)");
  } else if (from === "gmail" && to !== "gmail" && (op === "move" || op === "move_all") && a.add_labels !== undefined) {
    a.to_folder = a.add_labels; delete a.add_labels; delete a.remove_labels; delete a.keep_other_labels;
    notes.push("the label to add became the folder to move to");
  }
  return a;
}

export type SwitchReport = { changed: number; lines: string[] };

/** What the switch does to one step; null when it is not a mail step of the service switched from. */
function switchStep(step: FlowStep, from: MailService, to: MailService, packages: PackageManifest[], lines: string[], label: (s: FlowStep) => string): FlowStep | null {
  const fi = index(from), ti = index(to), fromPkg = pkgOf(from), toPkg = pkgOf(to);
  const target = packages.find((p) => p.id === toPkg);
  // a loop over the emails of the service switched from
  if (step.action === "core/loop" && typeof step.args?.iterator === "string" && step.args.iterator === fromPkg + "/" + LOOPS[fi]) {
    const it = LOOPS[ti];
    if (!it) { lines.push(label(step) + ": " + MAIL_SERVICES[ti].name + " has no each-email loop; left as it was"); return step; }
    const spec = target?.iterators?.find((x) => x.id === it);
    const notes: string[] = [];
    const src = (step.args.iterator_args ?? {}) as Record<string, unknown>;
    const renamed: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(src)) renamed[common(from, "find", k)] = v;
    const conv = convertValues(from, to, "find", renamed, notes);
    const out: Record<string, unknown> = {}, dropped: string[] = [];
    for (const [k, v] of Object.entries(conv)) { const n = own(to, "find", k); if (spec?.inputs?.properties?.[n]) out[n] = v; else dropped.push(k); }
    lines.push(label(step) + ": each email in " + MAIL_SERVICES[ti].name + (dropped.length ? "; left out (no match): " + dropped.join(", ") : "") + (notes.length ? "; " + notes.join("; ") : ""));
    return { ...step, args: { ...step.args, iterator: toPkg + "/" + it, iterator_args: out } };
  }
  if (!step.action.startsWith(fromPkg + "/")) return null;
  const actionId = step.action.slice(fromPkg.length + 1);
  const op = Object.keys(OPS).find((k) => OPS[k][fi] === actionId);
  if (!op) return null;   // not a mail command (Google Drive, Microsoft 365 calendar...)
  const toId = OPS[op][ti];
  if (!toId) {
    if (op === "connect" || op === "disconnect") { lines.push(label(step) + ": " + MAIL_SERVICES[ti].name + " needs no " + op + " step; removed"); return { ...step, action: "", args: {} }; }
    lines.push(label(step) + ": " + MAIL_SERVICES[ti].name + " has no match for this command; left as it was (it still uses " + MAIL_SERVICES[fi].name + ")");
    return step;
  }
  const spec = target?.actions.find((a) => a.id === toId);
  const notes: string[] = [];
  const renamed: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(step.args ?? {})) renamed[common(from, op, k)] = v;
  const conv = convertValues(from, to, op, renamed, notes);
  const args: Record<string, unknown> = {}, dropped: string[] = [];
  for (const [k, v] of Object.entries(conv)) {
    const n = own(to, op, k);
    if (spec?.inputs?.properties?.[n]) args[n] = v;
    else if (!["session", "access_token", "mailbox", "base_url", "endpoint_override"].includes(k)) dropped.push(k);
  }
  const outputs = spec?.outputs?.properties ?? {};
  const out: Record<string, string> = {}, lostOut: string[] = [];
  for (const [k, v] of Object.entries(step.out ?? {})) {
    const n = k === "emails" && outputs.table ? "table" : k;
    if (outputs[n]) out[n] = v; else lostOut.push(k);
  }
  const next: FlowStep = { ...step, action: toPkg + "/" + toId, args };
  if (Object.keys(out).length || step.out) next.out = out;
  delete next.connection;
  const needs: string[] = [];
  if (MAIL_SERVICES[ti].sessions && op !== "connect" && !MAIL_SERVICES[fi].sessions) needs.push("a Connect step before it");
  if ((to === "microsoft365" || to === "gmail") && op !== "connect") needs.push("a connection or an access token");
  if (MAIL_SERVICES[fi].sessions && op === "connect" && !MAIL_SERVICES[ti].sessions) needs.push("nothing to connect: sign in on each step instead");
  lines.push(label(step) + ": " + (spec?.name ?? toId)
    + (dropped.length ? "; left out (no match): " + dropped.join(", ") : "")
    + (lostOut.length ? "; outputs with no match: " + lostOut.join(", ") : "")
    + (notes.length ? "; " + notes.join("; ") : "")
    + (step.connection ? "; its connection was for " + MAIL_SERVICES[fi].name + ": choose one for " + MAIL_SERVICES[ti].name : "")
    + (needs.length ? "; needs " + needs.join(" and ") : ""));
  return next;
}

/** Every step list of a flow, nested ones and subflows included, with each step passed through f (an empty action removes it). */
function mapSteps(node: unknown, f: (s: FlowStep) => FlowStep): unknown {
  if (Array.isArray(node)) {
    const isSteps = node.length > 0 && node.every((x) => x && typeof x === "object" && typeof (x as { action?: unknown }).action === "string");
    const mapped = node.map((x) => mapSteps(x, f));
    return isSteps ? (mapped as FlowStep[]).map(f).filter((s) => s.action !== "") : mapped;
  }
  if (node && typeof node === "object") {
    const o: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(node)) o[k] = k === "args" ? v : mapSteps(v, f);
    return o;
  }
  return node;
}

/** The services an agent's steps use, with how many steps each. */
export function servicesUsed(flow: Flow): { id: MailService; steps: number }[] {
  const n = new Map<MailService, number>();
  mapSteps(flow, (s) => {
    for (const svc of MAIL_SERVICES) {
      const i = index(svc.id);
      const isLoop = s.action === "core/loop" && s.args?.iterator === svc.pkg + "/" + LOOPS[i];
      const isOp = s.action.startsWith(svc.pkg + "/") && Object.values(OPS).some((o) => o[i] === s.action.slice(svc.pkg.length + 1));
      if (isLoop || isOp) n.set(svc.id, (n.get(svc.id) ?? 0) + 1);
    }
    return s;
  });
  return MAIL_SERVICES.filter((s) => n.has(s.id)).map((s) => ({ id: s.id, steps: n.get(s.id)! }));
}

/** The flow with its mail steps switched, and what was done to each. */
export function switchMailService(flow: Flow, from: MailService, to: MailService, packages: PackageManifest[]): { flow: Flow; report: SwitchReport } {
  const lines: string[] = [];
  let changed = 0;
  const label = (s: FlowStep) => "Step " + s.id + (s.note ? " (" + s.note + ")" : "");
  const next = mapSteps(flow, (s) => {
    const r = switchStep(s, from, to, packages, lines, label);
    if (r && r !== s) changed++;
    return r ?? s;
  }) as Flow;
  return { flow: next, report: { changed, lines } };
}
