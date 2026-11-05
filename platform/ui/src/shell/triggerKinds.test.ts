import { describe, expect, it } from "vitest";
import { DOCUMENT_KINDS, KINDS, TRIGGER_GROUPS } from "./triggerKinds";

describe("trigger kinds", () => {
  it("offers every kind in exactly one group, each with a label and help", () => {
    const grouped = TRIGGER_GROUPS.flatMap(([, ks]) => ks);
    expect(new Set(grouped).size).toBe(grouped.length);
    expect(grouped.sort()).toEqual(Object.keys(KINDS).sort());
    for (const k of grouped) expect(KINDS[k].label && KINDS[k].help).toBeTruthy();
  });

  it("has the Document Automation events in their own group, with an optional process and document type", () => {
    expect(TRIGGER_GROUPS.find(([g]) => g === "On Document Automation")?.[1]).toEqual(DOCUMENT_KINDS);
    expect(DOCUMENT_KINDS.map((k) => KINDS[k].label)).toEqual(["Document processed", "Document needs review", "Review approved", "Review rejected", "Delivery failed", "Review overdue"]);
    for (const k of DOCUMENT_KINDS) {
      expect(k.length).toBeLessThanOrEqual(20);   // the server keeps the kind in 20 characters
      expect(KINDS[k].fields.map((f) => f[0])).toEqual(["process", "doc_type"]);
    }
  });
});
