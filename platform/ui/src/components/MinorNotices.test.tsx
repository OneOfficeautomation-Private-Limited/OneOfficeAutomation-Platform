import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MinorNotices, reportMinor } from "./MinorNotices";

/** Error batch E2: a side load that fails says so (amber, it closes by itself), instead of leaving a list empty. */
describe("minor notices", () => {
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it("says what could not be loaded and why, once, and closes after 8 seconds", () => {
    vi.useFakeTimers();
    render(<MinorNotices />);
    act(() => { reportMinor("the list of agents", new Error("One CommandCenter is not answering. Check that it is running.")); });
    act(() => { reportMinor("the list of agents", new Error("One CommandCenter is not answering. Check that it is running.")); });
    const shown = screen.getAllByRole("status");
    expect(shown).toHaveLength(1);
    expect(shown[0].textContent).toBe("Could not load the list of agents: One CommandCenter is not answering. Check that it is running.");
    act(() => { vi.advanceTimersByTime(8100); });
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("stays while the pointer is on it, and closes on Close", () => {
    vi.useFakeTimers();
    render(<MinorNotices />);
    act(() => { reportMinor("the backups", "timeout"); });
    fireEvent.mouseEnter(screen.getByRole("status"));
    act(() => { vi.advanceTimersByTime(20000); });
    expect(screen.getByRole("status").textContent).toContain("Could not load the backups: timeout");
    fireEvent.click(screen.getByRole("button", { name: "Close the notice" }));
    expect(screen.queryByRole("status")).toBeNull();
  });
});
