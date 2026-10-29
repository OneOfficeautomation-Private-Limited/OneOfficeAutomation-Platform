import { describe, expect, it } from "vitest";
import type { Flow, FlowStep } from "../api/client";
import { actionFor, desktopActionFor, fillWindowFromCapture, longRecordedVariables, numberedName, recordedVariables, shortenRecordedNames, withAnchorOffset } from "./capture";

const flow = (variables: Flow["variables"] = []): Flow => ({ kind: "TaskBot", id: "a", name: "A", variables, steps: [] });
const page = (title: string, url = "https://x.test/" + title) => ({ page: { title, url } });

describe("capture and recording", () => {
  it("numbers names: Window_1, then the next free one", () => {
    expect(numberedName(flow(), "Window")).toBe("Window_1");
    expect(numberedName(flow([{ name: "Window_1", type: "Dictionary" }, { name: "window_2", type: "Dictionary" }]), "Window")).toBe("Window_3");
  });

  it("puts a captured page in a numbered window variable, the same one for the same window", () => {
    const s1: FlowStep = { id: "s1", action: "ooa.browser/click", args: {} };
    let f = fillWindowFromCapture(flow(), s1, page("The Internet"), true);
    expect(s1.args!.window).toBe("$Window_1");
    expect(f.variables![0]).toMatchObject({ name: "Window_1", type: "Dictionary",
      default: { source: "browser", title: "The Internet", title_match: "equals", link: "https://x.test/The Internet", browser: "edge" } });
    const s2: FlowStep = { id: "s2", action: "ooa.browser/click", args: {} };
    f = fillWindowFromCapture(f, s2, page("The Internet", "https://x.test/other"), true);
    expect(s2.args!.window).toBe("$Window_1");
    const s3: FlowStep = { id: "s3", action: "ooa.browser/click", args: {} };
    f = fillWindowFromCapture(f, s3, page("Secure Area"), true);
    expect(s3.args!.window).toBe("$Window_2");
    const set: FlowStep = { id: "s4", action: "ooa.browser/click", args: { window: "$mine" } };
    expect(fillWindowFromCapture(f, set, page("X"), true)).toBe(f);   // a window already chosen stays
  });

  it("makes variables for a recording: one per window, Text_1 and Choice_1 with the recorded values, a password credential", () => {
    const win = { source: "browser", title: "Web form", title_match: "equals", link: "https://x.test/form", browser: "edge" };
    const steps: FlowStep[] = [
      { id: "a", action: "ooa.browser/type_text", args: { window: { ...win }, text: "tomsmith", object: { properties: [{ name: "label", value: "Username" }, { name: "name", value: "user" }] } } },
      { id: "b", action: "ooa.browser/type_text", args: { window: { ...win }, text: "$password" } },
      { id: "c", action: "ooa.browser/select_option", args: { option: "Option 2", object: { properties: [{ name: "id", value: "dropdown" }] } } },
      { id: "d", action: "ooa.desktop_ui/object_action", args: { action: "set_text", text: "Ann", object: { properties: [{ name: "Name", value: "Customer name" }] } } },
    ];
    const r = recordedVariables(flow(), steps);
    expect(r.made).toEqual(["Window_1", "Text_1", "Choice_1", "Text_2", "password"]);
    expect(steps.map((s) => s.args!.window ?? null).slice(0, 2)).toEqual(["$Window_1", "$Window_1"]);
    expect([steps[0].args!.text, steps[1].args!.text, steps[2].args!.option, steps[3].args!.text]).toEqual(["$Text_1", "$password", "$Choice_1", "$Text_2"]);
    const v = (n: string) => r.flow.variables!.find((x) => x.name === n)!;
    expect(v("Text_1")).toMatchObject({ type: "String", direction: "input", default: "tomsmith" });
    expect(v("Text_1").description).toContain("Username");
    expect(v("Text_2").description).toContain("Customer name");
    expect(v("password")).toMatchObject({ type: "Credential", direction: "input" });
  });

  it("offers to shorten long names from older recordings, and renames their uses", () => {
    const f: Flow = { ...flow([
      { name: "window_contact_us_acme", type: "Dictionary", default: { title: "Contact us" }, description: "Window the recording worked in (browser title “Contact us”)." },
      { name: "tell_us_more", type: "String", default: "x", description: "Recorded typing (change it here or pass it in)." },
      { name: "Window_1", type: "Dictionary", description: "Window made by Capture." }]),
      steps: [{ id: "s1", action: "ooa.browser/type_text", args: { window: "$window_contact_us_acme", text: "$tell_us_more" } }] };
    expect(longRecordedVariables(f).map((v) => v.name)).toEqual(["window_contact_us_acme", "tell_us_more"]);
    const g = shortenRecordedNames(f);
    expect(g.variables!.map((v) => v.name)).toEqual(["Window_2", "Text_1", "Window_1"]);
    expect(g.steps[0].args).toEqual({ window: "$Window_2", text: "$Text_1" });
    expect(longRecordedVariables(g)).toEqual([]);
  });

  it("works out the anchor's offset and the action a capture suggests", () => {
    expect(withAnchorOffset({ rect: { left: 100, top: 60 }, anchor: { rect: { left: 100, top: 36 } } }).anchor!.offset).toEqual({ dx: 0, dy: 24 });
    const desk = withAnchorOffset({ properties: [{ name: "BoundingRectangle", value: "40,100,10,10" }], anchor: { properties: [{ name: "BoundingRectangle", value: "10,100,5,5" }] } });
    expect(desk.anchor!.offset).toEqual({ dx: 30, dy: 0 });
    expect(actionFor({ tag: "input", type: "text" })).toBe("set_text");
    expect(actionFor({ tag: "input", type: "checkbox" })).toBe("check");
    expect(actionFor({ tag: "select" })).toBe("select_by_text");
    expect(actionFor({ tag: "a" })).toBe("click");
    expect(desktopActionFor({ properties: [{ name: "ControlType", value: "CheckBox" }] })).toBe("check");
  });
});
