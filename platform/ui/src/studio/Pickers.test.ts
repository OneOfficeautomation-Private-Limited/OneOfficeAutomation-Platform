import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { buildDn, connectMissing, isHtmlBody, pickerFor } from "./Pickers";

describe("pickers (gap round 2)", () => {
  afterEach(cleanup);
  it("builds distinguished names from a path, as the toolkit does", () => {
    expect(buildDn("Corp/Sales/EMEA", "corp.example.com")).toBe("OU=EMEA,OU=Sales,OU=Corp,DC=corp,DC=example,DC=com");
    expect(buildDn("corp.example.com/Staff", "", "Lee, Ann")).toBe("CN=Lee\\, Ann,OU=Staff,DC=corp,DC=example,DC=com");
    expect(buildDn("Computers", "corp.local", "PC-0042")).toBe("CN=PC-0042,CN=Computers,DC=corp,DC=local");
    expect(buildDn("Corp", "")).toBe("");
  });
  it("offers the formatted editor only for HTML email bodies", () => {
    const props = { body: {}, html: {} };
    expect(isHtmlBody({ id: "s", action: "ooa.email/send", args: { html: true } }, "body", props)).toBe(true);
    expect(isHtmlBody({ id: "s", action: "ooa.email/send", args: { html: false } }, "body", props)).toBe(false);
    expect(isHtmlBody({ id: "s", action: "ooa.m365/send_mail", args: { body_type: "HTML" } }, "body", { body: {}, body_type: {} })).toBe(true);
    expect(isHtmlBody({ id: "s", action: "ooa.file/write", args: { html: true } }, "body", props)).toBe(false);
    expect(isHtmlBody({ id: "s", action: "ooa.email/forward", args: { html: true } }, "note", { note: {}, html: {} })).toBe(true);
  });
  it("says which Connect inputs browsing the directory still needs", () => {
    expect(connectMissing(null)).toEqual([]);
    expect(connectMissing({ url: "ldaps://dc.corp.local", bind_user: "svc@corp.local", password: { $credential: "ad/svc" } })).toEqual([]);
    expect(connectMissing({ url: "ldap://dc", base_dn: "DC=corp" })).toEqual(["user (bind_user)", "password"]);
  });
  it("puts each picker on the fields of the toolkits that really exist", () => {
    // the toolkit ids come from the package manifests: a wrong id (e.g. ooa.ad for ooa.active_directory) hides the picker
    const step = (action: string) => ({ id: "s", action, args: {} });
    expect(pickerFor(step("ooa.soap/send"), "url", {})).not.toBeNull();
    expect(pickerFor(step("ooa.soap/send"), "body", {})).toBeNull();
    expect(pickerFor(step("ooa.active_directory/move_user"), "target_ou_dn", {})).not.toBeNull();
    expect(pickerFor(step("ooa.active_directory/create_user"), "container_dn", {})).not.toBeNull();
    expect(pickerFor(step("ooa.active_directory/create_user"), "username", {})).toBeNull();
    expect(pickerFor(step("ooa.pdf/extract_area_text"), "x", {})).not.toBeNull();
    expect(pickerFor(step("ooa.pdf/extract_area_text"), "y", {})).toBeNull();
    expect(pickerFor(step("ooa.microsoft365/send_mail"), "body", { body: {}, html: {} })).toBeNull();
    expect(pickerFor({ id: "s", action: "ooa.microsoft365/send_mail", args: { html: true } }, "body", { body: {}, html: {} })).not.toBeNull();
  });
  it("opens the HTML editor with the step's body in it, in both views", async () => {
    // found in the live check on the PC: the dialog mounts its content after opening, so the body was not shown
    const step = { id: "m", action: "ooa.email/send", args: { html: true, body: "<p>Hello <b>team</b></p>" } };
    render(pickerFor(step, "body", { body: {}, html: {} })!);
    fireEvent.click(screen.getByRole("button", { name: "Edit as formatted text…" }));
    const area = await screen.findByRole("textbox", { name: "Body" });
    expect(area.innerHTML).toBe("<p>Hello <b>team</b></p>");
    fireEvent.click(screen.getByRole("radio", { name: "HTML" }));
    expect((screen.getByRole("textbox", { name: "HTML" }) as HTMLTextAreaElement).value).toBe("<p>Hello <b>team</b></p>");
    fireEvent.change(screen.getByRole("textbox", { name: "HTML" }), { target: { value: "<p>Changed</p>" } });
    fireEvent.click(screen.getByRole("radio", { name: "Design" }));
    expect((await screen.findByRole("textbox", { name: "Body" })).innerHTML).toBe("<p>Changed</p>");
  });
});
