import { describe, expect, it } from "vitest";
import type { Engine } from "../api";
import { PLAIN, engineStatus, optionStatus } from "./EngineCatalog";

const engine = (status: Engine["status"], runs: Engine["runs"]): Engine =>
  ({ id: "x", name: "X", does: "", status, runs, price: "Free", free: true, key: "—" });

/** DA-1: the engine options and engines say in plain words whether they are ready, off or need something. */
describe("engine status in plain words", () => {
  it("names the three options plainly", () => {
    expect(PLAIN).toEqual({ onecore: "On this server", oneconnect: "Cloud reading", onemind: "AI reading" });
  });

  it("says Ready, Off: cloud not allowed, or what an engine still needs", () => {
    expect(engineStatus(engine("ready", "server")).label).toBe("Ready");
    expect(engineStatus(engine("optin", "cloud")).label).toBe("Off: cloud not allowed");
    expect(engineStatus(engine("setup", "cloud")).label).toBe("Needs a key");
    expect(engineStatus(engine("setup", "server")).label).toBe("Needs setting up");
  });

  it("sums an option up: ready with how many, off until cloud opt-in, or needs a key", () => {
    expect(optionStatus({ engines: [engine("ready", "server"), engine("setup", "server")] }).label).toBe("Ready · 1 of 2");
    expect(optionStatus({ engines: [engine("optin", "cloud"), engine("setup", "server")] }).label).toBe("Off: cloud not allowed");
    expect(optionStatus({ engines: [engine("setup", "cloud")] })).toEqual({ tone: "warn", label: "Needs a key" });
  });
});
