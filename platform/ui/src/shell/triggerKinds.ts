/**
 * The events that start an agent (Manage > Triggers, built as AA's event triggers): their settings and help, in three
 * groups: on the runner, checked by One CommandCenter, and reported by Document Automation. Shared by the console's
 * Triggers page and the command list of One AgentStudio's agent editor, so both offer the same triggers.
 */
export type F = [key: string, label: string, placeholder?: string, value?: string, type?: "number" | "check", checked?: boolean];
const FOLDER: F[] = [["folder", "Folder on the runner", "C:\\Invoices\\Inbox"], ["pattern", "File or folder name pattern", "*", "*"], ["recursive", "Include sub-folders", "", "", "check"]];
const INTERVAL: F = ["interval_seconds", "Check every (seconds, 15 or more)", "60", "60", "number"];
const DEBOUNCE: F = ["debounce_ms", "Ignore repeats within (milliseconds)", "500", "500", "number"];
const MAIL_FILTERS: F[] = [["from_contains", "From contains (optional)", "billing@"], ["subject_contains", "Subject contains (optional)", "Invoice"]];
/** Document Automation's events: DocFlow tells One CommandCenter as each happens, so nothing is checked on an interval. */
const DOCUMENT: [kind: string, label: string, when: string][] = [
  ["doc_processed", "Document processed", "a document has been read and checked"],
  ["doc_needs_review", "Document needs review", "a document waits for a person's review"],
  ["doc_approved", "Review approved", "a reviewer approves a document"],
  ["doc_rejected", "Review rejected", "a reviewer rejects a document"],
  ["doc_delivery_failed", "Delivery failed", "a process could not deliver a document's results"],
  ["doc_review_overdue", "Review overdue", "a review is past its process's review target"]];
export const DOCUMENT_KINDS = DOCUMENT.map(([k]) => k);
function documentKinds() {
  const fields: F[] = [["process", "Process (name or id; empty: any process)", "AP invoices"], ["doc_type", "Document type (empty: any type)", "Invoice"]];
  return Object.fromEntries(DOCUMENT.map(([k, label, when]) => [k, { label, fields, help: "Starts the agent when " + when + ". The trigger's owner must be able to see "
    + "the process's folder. Inputs, if the agent has them: document_id, process, process_id, status, event, file_name, doc_type, each field by its name "
    + "(invoice_number, total…) and trigger_event." }]));
}
export const KINDS: Record<string, { label: string; fields: F[]; help: string }> = {
  file: { label: "File arrives in a folder", fields: FOLDER, help: "Watched on the runner. The agent gets the path in trigger_file and all details in trigger_event." },
  file_changed: { label: "File changed", fields: FOLDER, help: "Fires when a matching file's size or time changes, once it stops changing. Input: trigger_file." },
  file_deleted: { label: "File deleted", fields: FOLDER, help: "Fires when a matching file disappears. Input: trigger_file." },
  folder_created: { label: "Folder created", fields: FOLDER, help: "Fires when a matching sub-folder appears. Input: trigger_file (the folder path)." },
  folder_deleted: { label: "Folder deleted", fields: FOLDER, help: "Fires when a matching sub-folder disappears. Input: trigger_file." },
  window_opened: { label: "Window opened", fields: [["title", "Window title pattern", "*Invoice*"]], help: "Windows runners. Text the title contains, a pattern with * and ?, or re: and a regular expression. Input: trigger_window." },
  window_closed: { label: "Window closed", fields: [["title", "Window title pattern", "*Invoice*"]], help: "Windows runners. Fires when a matching window goes away. Input: trigger_window." },
  process_started: { label: "Process started", fields: [["process", "Process name", "excel.exe"]], help: "Fires when a new process with this name starts on the runner. Input: trigger_process." },
  process_stopped: { label: "Process stopped", fields: [["process", "Process name", "excel.exe"]], help: "Fires when a process with this name ends. Input: trigger_process." },
  service_started: { label: "Service started", fields: [["service", "Windows service name", "Spooler"]], help: "Windows runners. Use the service name (sc query), not the display name. Input: trigger_service." },
  service_stopped: { label: "Service stopped", fields: [["service", "Windows service name", "Spooler"]], help: "Windows runners. Input: trigger_service." },
  hotkey: { label: "Hotkey pressed", fields: [["hotkey", "Key combination", "CTRL+ALT+F9"]], help: "Windows runners with a signed-in desktop. The combination must not be used by another program. Input: trigger_hotkey." },
  key_pressed: { label: "Key pressed in a window", fields: [["key", "Key or combination", "CTRL+ENTER"], ["window", "Only in this window (title, optional)", "*Invoice*"],
    ["process", "Only in this program (optional)", "erp.exe"], DEBOUNCE],
    help: "Windows runners with a signed-in desktop. Fires when this key or combination is pressed, such as F9, CTRL+ENTER or SHIFT+F2; the key still reaches the application. "
      + "Typed text is never recorded: the agent gets only the key, the window title and the program. Letters and digits need CTRL, ALT or WIN. Input: trigger_key." },
  mouse_clicked: { label: "Mouse click on an element or window", fields: [["window", "Window title pattern", "*Invoice*"], ["process", "Or program", "erp.exe"],
    ["button", "Mouse button (left, right, middle or any)", "left", "left"], ["element_name", "Element name (optional)", "Submit"], ["element_type", "Element type (optional)", "Button"],
    ["automation_id", "Automation id (optional)", "btnSubmit"], DEBOUNCE],
    help: "Windows runners with a signed-in desktop. Fires when the person clicks in this window, or on this element only. In One AgentStudio, pick this event to capture the element. "
      + "The agent gets the button, window, program and the element's name and type, never screen contents. Clicks made by programs are ignored. Input: trigger_click." },
  email: { label: "New email (IMAP mailbox)", fields: [["host", "IMAP server", "imap.example.com"], ["port", "Port", "993", "993", "number"], ["ssl", "Use SSL/TLS", "", "", "check", true],
    ["username", "User name", "invoices@example.com"], ["password_ref", "Password (vault reference)", "mail/imap/password"], ["mail_folder", "Mailbox folder", "INBOX", "INBOX"], ...MAIL_FILTERS, INTERVAL],
    help: "Checked by One CommandCenter. Each new message starts the agent once, with from, to, subject, date and a text preview in trigger_email." },
  outlook: { label: "New email (Microsoft 365 Outlook)", fields: [["token_ref", "Graph access token (vault reference)", "m365/graph/token"], ["mailbox", "Mailbox (blank for the token's own)", "invoices@contoso.com"],
    ["tenant_id", "Or app sign-in: tenant id"], ["client_id", "Application (client) id"], ["client_secret_ref", "Client secret (vault reference)", "m365/app/secret"],
    ["mail_folder", "Mail folder", "inbox", "inbox"], ...MAIL_FILTERS, INTERVAL],
    help: "Checked by One CommandCenter through Microsoft Graph. Give a token reference, or tenant id, client id, client secret reference and mailbox. Input: trigger_email." },
  servicenow: { label: "ServiceNow record new or updated", fields: [["instance_url", "Instance URL", "https://yourcompany.service-now.com"], ["table", "Table", "incident", "incident"],
    ["query", "Encoded query (optional)", "active=true^priority=1"], ["username", "User name", "integration.user"], ["password_ref", "Password (vault reference)", "snow/api/password"],
    ["token_ref", "Or OAuth token (vault reference)"], INTERVAL],
    help: "Checked by One CommandCenter through the Table API. Each new or changed record starts the agent once, with its fields in trigger_record." },
  http_poll: { label: "Universal poller (call a web address)", fields: [["url", "URL to call (GET)", "https://api.example.com/orders?status=new"], ["items_path", "Items JSON path", "$.items[*]", "$"],
    ["id_field", "Id field of each item", "id", "id"], ["auth_ref", "Bearer token (vault reference, optional)"], INTERVAL],
    help: "Checked by One CommandCenter. Each item with an id not seen before starts the agent once, with the item in trigger_item. Items present at the first check are only remembered." },
  webhook: { label: "Webhook (HTTP call)", fields: [], help: "The agent receives the request body in an input named payload. You get the address and secret after creating it." },
  ...documentKinds(),
};
export const TRIGGER_GROUPS: [string, string[]][] = [["On the runner", ["file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "window_opened", "window_closed", "process_started", "process_stopped", "service_started", "service_stopped", "hotkey", "key_pressed", "mouse_clicked"]],
  ["On One CommandCenter", ["email", "outlook", "servicenow", "http_poll", "webhook"]],
  ["On Document Automation", DOCUMENT_KINDS]];
