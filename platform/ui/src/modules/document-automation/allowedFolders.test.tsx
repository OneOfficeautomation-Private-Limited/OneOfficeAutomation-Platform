import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SampleApi, formatFolderList, inFolder, parseFolderList } from "./api";
import AllowedFolders from "./allowedFolders";

afterEach(cleanup);

describe("Folders processes may use (Settings > Document Automation, DA-1)", () => {
  it("lists the folders with DocFlow's access, adds and removes, warns about processes left outside, and saves", async () => {
    const api = new SampleApi();
    const onNotice = vi.fn(), onError = vi.fn();
    render(<AllowedFolders api={api} onNotice={onNotice} onError={onError} />);
    const table = await screen.findByRole("table", { name: "Folders processes may use" });
    await waitFor(() => expect(within(table).getAllByText("Can read and move files")).toHaveLength(2));
    expect(within(table).getAllByRole("row").slice(1).map((r) => (r as HTMLTableRowElement).cells[0].textContent)).toEqual(["\\\\scan01\\scans", "D:\\AP"]);
    expect((within(table).getByLabelText("Name people see for \\\\scan01\\scans") as HTMLInputElement).value).toBe("Scans");
    expect(within(table).getByText("Supplier credit notes")).toBeTruthy();                  // uses a folder inside Scans
    const save = screen.getByRole("button", { name: "Save folders" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);                                                        // nothing changed yet

    fireEvent.change(screen.getByLabelText("Folder to add"), { target: { value: "relative\\folder" } });
    expect(screen.getByRole("alert").textContent).toContain("Write the full path");
    fireEvent.change(screen.getByLabelText("Folder to add"), { target: { value: "\\\\nas01\\returns" } });
    fireEvent.change(screen.getByLabelText("Name people see for the folder to add"), { target: { value: "Returns=;" } });
    fireEvent.click(screen.getByRole("button", { name: "Add folder" }));
    await waitFor(() => expect(within(table).getByText("Not found")).toBeTruthy());          // the sample server has no such folder
    fireEvent.click(within(table).getByRole("button", { name: "Remove \\\\scan01\\scans" }));
    expect(await screen.findByText(/1 process uses a folder outside this list/)).toBeTruthy();
    expect(screen.getByText(/once you save, DocFlow does not read or write there/).textContent).toContain("Supplier credit notes (watched folder \\\\scan01\\scans\\invoices\\jeddah)");

    fireEvent.click(save);
    await waitFor(() => expect(onNotice).toHaveBeenCalledWith("Saved: processes may use 2 folders and the folders inside them."));
    expect(await api.allowedFolders()).toBe("Accounts payable=D:\\AP; Returns=\\\\nas01\\returns");
    expect(onError).not.toHaveBeenCalled();
  });

  it("says when DocFlow is too old for a list", async () => {
    const api = new SampleApi();
    api.allowedFolders = async () => null;
    render(<AllowedFolders api={api} onNotice={() => undefined} onError={() => undefined} />);
    expect(await screen.findByText(/Needs DocFlow 3.2.0 or newer/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save folders" })).toBeNull();
  });

  it("reads and writes the list the way DocFlow does", () => {
    expect(parseFolderList("Scans=\\\\scan01\\scans; D:\\AP ;; C:\\a=b")).toEqual([
      { name: "Scans", path: "\\\\scan01\\scans" }, { name: "AP", path: "D:\\AP" }, { name: "a=b", path: "C:\\a=b" }]);
    expect(formatFolderList([{ name: "Scans", path: "\\\\scan01\\scans" }, { name: "AP", path: "D:\\AP" }])).toBe("Scans=\\\\scan01\\scans; D:\\AP");
    expect(inFolder("\\\\SCAN01\\Scans\\x", "\\\\scan01\\scans\\")).toBe(true);
    expect(inFolder("\\\\scan01\\scans2", "\\\\scan01\\scans")).toBe(false);
  });
});
