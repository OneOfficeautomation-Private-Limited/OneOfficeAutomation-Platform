import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Notice } from "./Notice";

describe("Notice (usage review item 27)", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it("closes a notice by itself, but not while the pointer is on it", () => {
    const onClose = vi.fn();
    render(<Notice text="Saved Invoice." onClose={onClose} />);
    expect(screen.getByRole("status").textContent).toContain("Saved Invoice.");
    fireEvent.mouseEnter(screen.getByRole("status"));
    act(() => { vi.advanceTimersByTime(10000); });
    expect(onClose).not.toHaveBeenCalled();   // held while it is being read
    fireEvent.mouseLeave(screen.getByRole("status"));
    act(() => { vi.advanceTimersByTime(6000); });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("keeps an error until it is closed with its labelled Close", () => {
    const onClose = vi.fn();
    render(<Notice kind="error" text="Cannot reach One CommandCenter" onClose={onClose} />);
    act(() => { vi.advanceTimersByTime(60000); });
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
