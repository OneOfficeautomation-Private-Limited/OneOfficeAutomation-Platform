import { describe, expect, it } from "vitest";
import { sapRecordingToSteps } from "./sapRecording";

const RECORDING = [
  'If Not IsObject(application) Then',
  '   Set SapGuiAuto  = GetObject("SAPGUI")',
  'End If',
  'session.findById("wnd[0]").maximize',
  'session.findById("wnd[0]/tbar[0]/okcd").text = "/nVA01"',
  'session.findById("wnd[0]").sendVKey 0',
  'session.findById("wnd[0]/usr/ctxtVBAK-AUART").text = "OR"',
  'session.findById("wnd[0]/usr/ctxtVBAK-AUART").caretPosition = 2',
  'session.findById("wnd[0]/usr/txtNOTE").text = "Say ""hello"""',
  'session.findById("wnd[0]/usr/chkFLAG").selected = true',
  'session.findById("wnd[0]/usr/cmbPRIO").key = "2"',
  'session.findById("wnd[0]/usr/radOPT2").select',
  'session.findById("wnd[0]/mbar/menu[3]/menu[1]").select',
  'session.findById("wnd[1]/tbar[0]/btn[0]").press',
  'session.findById("wnd[1]").sendVKey 3',
  'session.findById("wnd[0]/usr/cntlGRID/shellcont/shell").doubleClickCurrentCell',
  'session.findById("wnd[0]/usr/cntlGRID/shellcont/shell").setCurrentCell 2,"MATNR"',
].join("\r\n");

describe("SAP GUI recording import (G60)", () => {
  it("turns each recorded line into the SAP GUI toolkit's step", () => {
    const r = sapRecordingToSteps(RECORDING);
    expect(r.steps.map((s) => [s.action, s.args])).toEqual([
      ["ooa.sap/run_transaction", { tcode: "VA01" }],
      ["ooa.sap/send_key", { vkey: 0, window: 0 }],
      ["ooa.sap/set_field", { id: "wnd[0]/usr/ctxtVBAK-AUART", value: "OR" }],
      ["ooa.sap/set_field", { id: "wnd[0]/usr/txtNOTE", value: 'Say "hello"' }],
      ["ooa.sap/check_box", { id: "wnd[0]/usr/chkFLAG", checked: true }],
      ["ooa.sap/select_item", { id: "wnd[0]/usr/cmbPRIO", value: "2", match_by: "key" }],
      ["ooa.sap/select_radio", { id: "wnd[0]/usr/radOPT2" }],
      ["ooa.sap/click", { id: "wnd[0]/mbar/menu[3]/menu[1]" }],
      ["ooa.sap/press", { id: "wnd[1]/tbar[0]/btn[0]" }],
      ["ooa.sap/send_key", { vkey: 3, window: 1 }],
      ["ooa.sap/double_click", { id: "wnd[0]/usr/cntlGRID/shellcont/shell" }],
      ["core/comment", { text: 'From the SAP recording: session.findById("wnd[0]/usr/cntlGRID/shellcont/shell").setCurrentCell 2,"MATNR"' }],
    ]);
    expect(r.skipped).toBe(2);
    expect(r.kept).toBe(1);
  });
});
