/**
 * "How to use this page": a few short steps for every Document Automation screen, and one-line hints for the options
 * people ask about. Plain words, one action per step, so anyone can follow them. Kept in one place for translation.
 */
export type HelpPage = "overview" | "inbox" | "process" | "review" | "processes" | "types" | "refdata" | "engines" | "testlab" | "testsets";

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
      "Click a field to see its box on the page. Click a box to find its field.",
      "Not found on the page? Look for the value yourself and type the right one.",
      "Tables: fix a cell, or add and remove rows.",
      "Click Approve and next (Ctrl+Enter) to save and open the next one.",
      "Wrong document? Click Reject or Change document type.",
    ],
  },
  processes: {
    summary: "Build a process step by step, from where documents come in to going live.",
    steps: [
      "Work through the steps left to right, from 1 Document ingestion to 7 Testing & go-live.",
      "In step 1, keep Read e-invoice data first on for ZATCA and GST invoices.",
      "In step 2, choose the document type and check its fields on the same screen.",
      "In step 5, give important fields, such as the total, a stricter threshold.",
      "Click Save draft as you go. On a live process, Save changes applies at once.",
      "In step 7, try 1–3 sample documents, then click Go live.",
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
      "How pages are routed: choose when weak pages are read again, and by which engine.",
      "Engines: click OneCore, OneConnect or OneMind, then Test or Configure an engine.",
      "Monthly cost: enter the client's volumes before turning the cloud on.",
    ],
  },
  testlab: {
    summary: "Try a document before going live. Nothing is saved.",
    steps: [
      "Choose a sample document and its type.",
      "Pick an engine, or switch on Compare two.",
      "Click Run test.",
      "Fix the process where a row differs or fails a check, then test again.",
      "Many documents to check? Open Test sets.",
    ],
  },
  testsets: {
    summary: "Compare engines on documents whose right values you know.",
    steps: [
      "Click New test set, or Add our sample set to start with generated invoices.",
      "Open a set. Click Add a document and type its right values.",
      "Or, in Review, open an approved document and click Add to test set.",
      "Click Run test and choose the engines to compare.",
      "Compare the results. The best in each column is marked.",
      "Check the documents with a wrong field, fix the process, then run again.",
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
  einvoiceFirst: "Uses the invoice's own XML instead of OCR, and checks the values read against its QR code.",
  fieldThresholds: "A field listed here goes to review under its own confidence, not the one above.",
  wronglyAutoApproved: "Went straight through with a wrong value, so no one checked it. Keep this at 0.",
  addToTestSet: "Copies this document and its approved values into a test set, to test engines on it later.",
};
