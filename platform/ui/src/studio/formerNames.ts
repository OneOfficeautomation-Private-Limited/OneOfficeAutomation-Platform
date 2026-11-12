// Names commands had before they were renamed, so the action library still finds them by the old name (shown as
// "Formerly: ..."). Add a line when a command is renamed; keep it for a few releases.
export const FORMER_NAMES: Record<string, string[]> = {
  // command review A8, 12 Nov 2026: Gmail commands named like the other mail services
  "ooa.google/gmail_delete_all": ["Delete all matching Gmail messages"],
  "ooa.google/gmail_delete_message": ["Delete Gmail message"],
  "ooa.google/gmail_forward_message": ["Forward Gmail message"],
  "ooa.google/gmail_get_message": ["Read Gmail message"],
  "ooa.google/gmail_label_exists": ["Check Gmail label exists"],
  "ooa.google/gmail_list_messages": ["List Gmail messages"],
  "ooa.google/gmail_mark_message": ["Change Gmail message status"],
  "ooa.google/gmail_move_all": ["Move all matching Gmail messages"],
  "ooa.google/gmail_move_message": ["Move Gmail message"],
  "ooa.google/gmail_reply_all": ["Reply all to Gmail message"],
  "ooa.google/gmail_reply_message": ["Reply to Gmail message"],
  "ooa.google/gmail_save_all_attachments": ["Save attachments of all matching Gmail messages"],
  "ooa.google/gmail_save_attachments": ["Save Gmail attachments"],
  "ooa.google/gmail_save_message_as_eml": ["Save Gmail message as .eml"],
  "ooa.google/gmail_send": ["Send Gmail"],
};
