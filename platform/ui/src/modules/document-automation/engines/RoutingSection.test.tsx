import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { Engine, Routing } from "../api";
import { RoutingSection } from "./RoutingSection";

afterEach(cleanup);

const routing: Routing = { cloudOptIn: false, retryBelow: 0.85, localRetry: "paddleocr", cloudEngine: "" };
const engine = (id: string, name: string, status: Engine["status"]): Engine =>
  ({ id, name, does: "", status, runs: "server", price: "Free", free: true, key: "—" });
const show = (engines: Engine[]) => render(<RoutingSection routing={routing} cloudEngines={[]} engines={engines} saved onChange={() => {}} onSave={() => {}} />);

/** The routing card says what DocFlow really has, not "Ready" for every local engine. */
describe("RoutingSection", () => {
  it("shows an engine that is not installed as needing setting up, and its step as skipped", () => {
    show([engine("tesseract", "Tesseract", "ready"), engine("paddleocr", "PaddleOCR", "setup")]);
    const card = screen.getByRole("region", { name: "How a page is routed" });
    expect(within(card).getByText("Tesseract · Ready")).toBeTruthy();
    expect(within(card).getByText("PaddleOCR · Needs setting up")).toBeTruthy();
    expect(within(card).getByText("3 · SKIPPED")).toBeTruthy();
    expect(within(card).getByText(/PaddleOCR is not installed on DocFlow's server/)).toBeTruthy();
    expect(within(card).getByText("PDF text · Ready")).toBeTruthy();   // built in: always there
  });

  it("shows engines that are there as ready", () => {
    show([engine("tesseract", "Tesseract", "ready"), engine("paddleocr", "PaddleOCR", "ready")]);
    const card = screen.getByRole("region", { name: "How a page is routed" });
    expect(within(card).getByText("PaddleOCR · Ready")).toBeTruthy();
    expect(within(card).getByText("3 · LOCAL")).toBeTruthy();
  });

  it("shows Tesseract as needing setting up when DocFlow says so", () => {
    show([engine("tesseract", "Tesseract", "setup")]);
    expect(screen.getByText("Tesseract · Needs setting up")).toBeTruthy();
  });
});
