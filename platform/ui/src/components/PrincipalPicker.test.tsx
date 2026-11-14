import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PrincipalPicker, principalDirectory, principalLabel, type PrincipalDirectory, type PrincipalKind } from "./PrincipalPicker";

const DIR: PrincipalDirectory = { users: [{ name: "jane", label: "Jane Roe" }, { name: "carl" }], teams: ["finance"], roles: ["admin", "bot_runner"] };

function Host({ start = "", onValue, ...rest }: { start?: string; onValue?: (v: string) => void; source?: (() => Promise<PrincipalDirectory>) | null;
  kinds?: PrincipalKind[]; everyone?: string; emails?: boolean; bareUsers?: boolean; single?: boolean; disabled?: boolean }) {
  const [v, setV] = useState(start);
  return <PrincipalPicker label="Members" value={v} onChange={(x) => { setV(x); onValue?.(x); }} {...rest} />;
}
const box = () => screen.getByRole("combobox", { name: "Members" });
const search = (text: string) => { fireEvent.focus(box()); fireEvent.change(box(), { target: { value: text } }); };
const choose = (name: string | RegExp) => fireEvent.mouseDown(screen.getByRole("option", { name }));

describe("PrincipalPicker", () => {
  afterEach(cleanup);
  it("lists users, teams and roles, picks several, and writes the stored text", async () => {
    const onValue = vi.fn();
    render(<Host source={async () => DIR} onValue={onValue} />);
    await act(async () => {});
    fireEvent.focus(box());
    const list = screen.getByRole("listbox", { name: "Members: choices" });
    expect(within(list).getAllByRole("option").map((o) => o.getAttribute("aria-label"))).toEqual(
      ["User: jane, Jane Roe", "User: carl", "Team: finance", "Role: admin", "Role: bot_runner"]);
    search("roe");   // the display name is searched too
    choose("User: jane, Jane Roe");
    search("fin");
    choose("Team: finance");
    expect(onValue).toHaveBeenLastCalledWith("user:jane, team:finance");
    const chips = screen.getByRole("list", { name: "Members: chosen" });
    expect(within(chips).getAllByRole("listitem").map((x) => x.textContent)).toEqual(["User: jane", "Team: finance"]);
    // a chosen one is not offered again
    search("jane");
    expect(screen.getByText("Nothing matches “jane”.")).toBeTruthy();
  });

  it("removes a chip with its labelled button, and the last one with Backspace", async () => {
    const onValue = vi.fn();
    render(<Host start="role:admin, team:finance, user:carl" source={async () => DIR} onValue={onValue} />);
    await act(async () => {});
    fireEvent.click(screen.getByRole("button", { name: "Remove Team: finance" }));
    expect(onValue).toHaveBeenLastCalledWith("role:admin, user:carl");
    fireEvent.keyDown(box(), { key: "Backspace" });
    expect(onValue).toHaveBeenLastCalledWith("role:admin");
  });

  it("chooses with the keyboard", async () => {
    const onValue = vi.fn();
    render(<Host source={async () => DIR} kinds={["role"]} onValue={onValue} />);
    await act(async () => {});
    search("bot");
    fireEvent.keyDown(box(), { key: "Enter" });
    expect(onValue).toHaveBeenLastCalledWith("role:bot_runner");
  });

  it("without lists, takes a typed name marked as a user, team or role, and says why", () => {
    const onValue = vi.fn();
    render(<Host source={null} kinds={["user", "role"]} onValue={onValue} />);
    expect(screen.getByText("The lists could not be loaded here: type a name, then choose whether it is a user or role.")).toBeTruthy();
    search("fin.lead");
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["Add user fin.lead", "Add role fin.lead"]);
    choose("Add role fin.lead");
    expect(onValue).toHaveBeenLastCalledWith("role:fin.lead");
  });

  it("offers a typed name for a list the person may not read, keeps plain user names, everyone and e-mail addresses", async () => {
    const owners = vi.fn();
    const { unmount } = render(<Host source={async () => ({ users: null, teams: ["finance"], roles: ["admin"] })} kinds={["user"]} bareUsers onValue={owners} />);
    await act(async () => {});
    search("ann");
    choose("Add user ann");
    expect(owners).toHaveBeenLastCalledWith("ann");
    expect(screen.getByText("User: ann")).toBeTruthy();
    unmount();
    const shared = vi.fn();
    render(<Host source={async () => DIR} everyone="*" emails kinds={["user", "role"]} onValue={shared} />);
    await act(async () => {});
    search("every");
    choose("Everyone");
    search("cfo@example.com");
    choose("Add e-mail cfo@example.com");
    expect(shared).toHaveBeenLastCalledWith("*, cfo@example.com");
    expect(screen.getByText("E-mail: cfo@example.com")).toBeTruthy();
  });

  it("holds one choice when single, and shows chips without Remove when off", async () => {
    const onValue = vi.fn();
    const { unmount } = render(<Host single source={async () => DIR} onValue={onValue} />);
    await act(async () => {});
    search("carl");
    choose("User: carl");
    expect(screen.queryByRole("combobox")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Change" }));
    search("admin");
    choose("Role: admin");
    expect(onValue).toHaveBeenLastCalledWith("role:admin");
    unmount();
    render(<Host disabled start="user:carl" source={async () => DIR} />);
    await act(async () => {});
    expect(screen.getByText("User: carl")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Remove/ })).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("reads the lists once per connection, and a list that fails is null", async () => {
    const api = { users: vi.fn(async () => { throw new Error("no right"); }), teams: vi.fn(async () => [{ name: "finance" }]), roles: vi.fn(async () => [{ name: "admin" }]) };
    const a = await principalDirectory(api);
    await principalDirectory(api);
    expect(a).toEqual({ users: null, teams: ["finance"], roles: ["admin"] });
    expect(api.teams).toHaveBeenCalledTimes(1);
  });

  it("labels stored tokens in words", () => {
    expect(principalLabel("role:admin")).toBe("Role: admin");
    expect(principalLabel("role:*")).toBe("Everyone");
    expect(principalLabel("*", false, "*")).toBe("Everyone");
    expect(principalLabel("jane", true)).toBe("User: jane");
  });
});
