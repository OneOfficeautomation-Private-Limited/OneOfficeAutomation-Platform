import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, PackageManifest } from "../api/client";
import { enumWord } from "./enumWords";
import { newStep } from "./flowEdit";
import { DatePatternField, LocaleField, sample, TimeZoneField } from "./PickerFields";

describe("plain words in lists (command review X1)", () => {
  it("has words for common codes and tidies the rest; the value is not touched", () => {
    expect(enumWord("gt")).toBe("is greater than");
    expect(enumWord("ISO_LOCAL_DATE")).toBe("ISO date (2026-11-10)");
    expect(enumWord("azure_openai")).toBe("Azure OpenAI");
    expect(enumWord("day_of_week")).toBe("Day of week");
    expect(enumWord("SQL_SERVER")).toBe("SQL server");
    expect(enumWord("minutes")).toBe("Minutes");
  });
});

describe("pickers (command review D1, D2, D8)", () => {
  afterEach(cleanup);

  it("new date steps work in the runner's time zone; other steps are untouched", () => {
    const packages: PackageManifest[] = [{ id: "ooa.datetime", name: "Date and time", version: "1", actions: [
      { id: "format", name: "Format date", inputs: { properties: { value: {}, time_zone: { type: "string", default: "UTC" } } } }, { id: "now", name: "Now", inputs: { properties: {} } }] }];
    const flow: Flow = { kind: "TaskBot", id: "t", name: "t", steps: [] };
    expect(newStep("ooa.datetime/format", flow, packages).args).toEqual({ time_zone: "local" });
    expect(newStep("ooa.datetime/now", flow, packages).args).toBeUndefined();
  });

  it("offers Local and UTC first for a date step's time zone", () => {
    const onCommit = vi.fn();
    render(<TimeZoneField id="tz" value="UTC" local onCommit={onCommit} />);
    fireEvent.focus(screen.getByRole("combobox"));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "loc" } });
    fireEvent.mouseDown(screen.getByRole("option", { name: /^Local/ }));
    expect(onCommit).toHaveBeenLastCalledWith("local");
  });

  it("shows today in a date pattern and takes your own", () => {
    expect(sample("dd/MM/yyyy HH:mm", new Date(2026, 10, 9, 7, 5))).toBe("09/11/2026 07:05");
    expect(sample("d MMM yyyy, EEEE", new Date(2026, 10, 9))).toBe("9 Nov 2026, Monday");
    const onCommit = vi.fn();
    render(<DatePatternField id="p" value={undefined} fallback="yyyy-MM-dd" onCommit={onCommit} />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "dd/MM/yyyy" } });
    expect(onCommit).toHaveBeenLastCalledWith("dd/MM/yyyy");
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "\u0000own" } });
    const own = screen.getByLabelText("Your own pattern");
    fireEvent.change(own, { target: { value: "yyyy/MM" } });
    fireEvent.blur(own);
    expect(onCommit).toHaveBeenLastCalledWith("yyyy/MM");
  });

  it("names languages and regions", () => {
    const onCommit = vi.fn();
    render(<LocaleField id="l" value="en-IN" fallback="en-US" onCommit={onCommit} />);
    const pick = screen.getByRole("combobox") as HTMLSelectElement;
    expect(pick.selectedOptions[0].text).toBe("English (India) (en-IN)");
    expect(pick.options[0].text).toBe("Default: English (United States)");
  });
});
