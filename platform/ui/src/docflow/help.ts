/**
 * "How to use this page": a few short steps for every Document Automation screen, and one-line hints for the options
 * people ask about. Plain words, one action per step, so anyone can follow them. Kept in one place for translation.
 */
export type HelpPage = "overview" | "inbox" | "process" | "review" | "processes" | "types" | "refdata" | "engines" | "testlab";

export const HELP: Record<HelpPage, { summary: string; steps: string[] }> = {
  overview: {
    summary: "How documents are doing today, across all processes.",
    steps: [
      "Read the numbers at the top: received, straight through, waiting, failed.",
      "Click an item under Needs attention to go where action is needed.",
      "Use the chart to compare the last 14 days.",
      "Check Cloud cost if cloud opt-in is on.",
    ],
  },
  inbox: {
    summary: "Add documents and see every process at a glance.",
    steps: [
      "Click Add documents, or drop files on the box.",
      "Choose a process, or let DocFlow recognise each document.",
      "Watch the Status column: Processing, Done or Needs review.",
      "Click a process to see its documents.",
      "No process yet? Click New process.",
    ],
  },
  process: {
    summary: "Every document of this process.",
    steps: [
      "Use the filters to find documents.",
      "Needs review? Click Open to check it.",
      "Failed? The note says why. Fix the file and add it again.",
      "Click Export to download the results.",
    ],
  },
  review: {
    summary: "Check the values DocFlow was not sure about.",
    steps: [
      "Start with the highlighted field. The reason is shown under it.",
      "Compare it with the page on the left and type the right value.",
      "Click Approve and next (Ctrl+Enter) to save and open the next one.",
      "Wrong document? Click Reject or Change document type.",
    ],
  },
  processes: {
    summary: "Decide how a process reads, checks and delivers documents.",
    steps: [
      "Click a stage at the top to set it up, left to right.",
      "Under Checks, switch each check on or off and choose what happens if it fails.",
      "Click Save draft. On a live process, Save changes applies at once.",
      "Click Go live: new documents then use this version.",
    ],
  },
  types: {
    summary: "What DocFlow reads from each kind of document.",
    steps: [
      "Pick a type on the left to see its fields.",
      "Labels are the words DocFlow looks for on the page.",
      "Learned shows labels picked up from reviewers' corrections.",
      "Need a new type? Click Teach from samples and add 3 to 5 example files.",
    ],
  },
  refdata: {
    summary: "Lists that checks look values up in, such as known suppliers.",
    steps: [
      "Click Upload a table and choose an Excel or CSV file.",
      "Click a table to see its rows. Search or filter to find a value.",
      "Use it in a process: Processes, Checks, Add check, Reference data lookup.",
      "Upload again when the list changes. The old list is replaced.",
    ],
  },
  engines: {
    summary: "Which engines read the pages, and when the cloud may be used.",
    steps: [
      "Leave Cloud opt-in off unless the client agreed to it.",
      "Choose when weak pages are read again, and by which engine.",
      "Click OneCore, OneConnect or OneMind to see their engines.",
      "Check the monthly cost before turning the cloud on.",
    ],
  },
  testlab: {
    summary: "Try a document before going live. Nothing is saved.",
    steps: [
      "Choose a sample document and its type.",
      "Pick an engine, or switch on Compare two.",
      "Click Run test.",
      "Fix the process where a row differs or fails a check, then test again.",
    ],
  },
};

export const HINTS = {
  cloudOptIn: "Off: documents never leave this server. On: weak pages may go to paid cloud engines.",
  retryBelow: "Pages read with less confidence than this are read again.",
  localRetry: "A second free engine on this server. The better reading wins.",
  cloudEngine: "Reads pages that are still weak. Only with cloud opt-in on. Costs per page.",
  processName: "A name people recognise, for example Supplier invoices KSA.",
  watchedFolder: "DocFlow checks this folder and reads every new file.",
  ifFails: "Review: a person checks it. Reject: the document is refused. Warn: noted, nothing stops.",
  recognise: "DocFlow finds out what each document is and sends it to the matching process.",
  reviewReject: "The document is marked as rejected and nothing is delivered.",
  changeType: "Read the document again as another document type.",
};
