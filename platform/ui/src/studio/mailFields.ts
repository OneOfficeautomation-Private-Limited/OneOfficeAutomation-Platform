/**
 * A4 (command review, batch 7): the mail toolkits filter with two tick boxes, unread_only and read_only, which can
 * contradict each other. Studio shows them as one choice (AA's Read / Unread / Both) and stores the two ticks as before,
 * so agents and the runner are unchanged.
 */
export type ReadState = "all" | "unread" | "read";

export const READ_STATES: { value: ReadState; label: string }[] = [
  { value: "all", label: "All emails" },
  { value: "unread", label: "Unread only" },
  { value: "read", label: "Read only" },
];

/** True when an action has both ticks, so they are drawn as one choice. */
export const hasReadState = (props: Record<string, unknown>) => "unread_only" in props && "read_only" in props;

/** The choice the two ticks stand for; both ticked (which finds nothing) shows as Unread, the tick that came first. */
export function readState(args: Record<string, unknown> | undefined): ReadState {
  if (args?.unread_only === true) return "unread";
  if (args?.read_only === true) return "read";
  return "all";
}

/** The two ticks for a choice; All leaves both unset (their default). */
export function readStateArgs(s: ReadState): { unread_only: true | undefined; read_only: true | undefined } {
  return { unread_only: s === "unread" ? true : undefined, read_only: s === "read" ? true : undefined };
}
