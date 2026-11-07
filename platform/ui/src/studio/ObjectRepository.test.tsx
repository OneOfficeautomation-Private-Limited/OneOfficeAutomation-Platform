import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { StudioApi } from "../api/client";
import { foundBy, isObjectRef, ObjectRepositoryEditor, PickElementDialog, SaveElementDialog } from "./ObjectRepository";

const CATALOGUE = [
  { path: "Shared/Portal.objects.json", name: "Portal", elements: [
    { element: "Portal/Sign in/Name box", description: "The user name", technology: "web" },
    { element: "Portal/Sign in/Submit", description: "", technology: "web" },
    { element: "SAP GUI/Login/Client", description: "", technology: "desktop" }] },
];

function fakeApi() {
  const posted: unknown[] = [];
  const f = (async (url: string, init: RequestInit = {}) => {
    const route = url.replace(/^\/api\//, "").split("?")[0];
    if (route === "objects/catalogue") return new Response(JSON.stringify({ repositories: CATALOGUE }), { status: 200 });
    if (route === "objects/add") {
      const b = JSON.parse(init.body as string);
      posted.push(b);
      return new Response(JSON.stringify({ repository: b.repository, element: b.application + "/" + b.screen + "/" + b.name }), { status: 200 });
    }
    return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: route } }), { status: 404 });
  }) as unknown as typeof fetch;
  return { api: new StudioApi("/api/", f), posted };
}

const REPO = { kind: "ObjectRepository", name: "Portal", description: "", applications: [
  { name: "Portal", screens: [{ name: "Sign in", elements: [
    { name: "Name box", description: "", object: { technology: "web", properties: [{ name: "id", value: "user", match: "equals", use: true }] } },
    { name: "Submit", description: "", object: { technology: "web", search_by: "css", css: "#go", properties: [] } }] }] }] };

describe("object repositories (G42)", () => {
  it("tells a reference from a captured object, and says how an element is found", () => {
    expect(isObjectRef({ repository: "a.objects.json", element: "a/b/c" })).toBe(true);
    expect(isObjectRef({ repository: "a.objects.json", element: "a/b/c", technology: "web" })).toBe(false);
    expect(isObjectRef({ technology: "web" })).toBe(false);
    expect(foundBy({ properties: [{ name: "id", value: "x", match: "equals", use: true }, { name: "tag", value: "input", match: "equals", use: false }] } as never)).toBe("id");
    expect(foundBy({ search_by: "css" } as never)).toBe("CSS selector");
  });

  it("edits a repository: rename an element (no / and no duplicates), describe it, remove it", () => {
    let last: any = REPO;
    function Host() {
      const [d, setD] = useState<any>(REPO);
      return <ObjectRepositoryEditor data={d} onChange={(n) => { last = n; setD(n); }} />;
    }
    render(<Host />);
    expect(screen.getByText(/2 elements\./)).toBeTruthy();
    const screenGroup = screen.getByRole("group", { name: "Screen Sign in" });
    const names = within(screenGroup).getAllByLabelText("Element name") as HTMLInputElement[];
    fireEvent.change(names[0], { target: { value: "User/name" } }); fireEvent.blur(names[0]);
    expect(last).toBe(REPO);   // refused: a name cannot contain /
    fireEvent.change(names[0], { target: { value: "Submit" } }); fireEvent.blur(names[0]);
    expect(last).toBe(REPO);   // refused: the screen has a Submit already
    fireEvent.change(names[0], { target: { value: "User name" } }); fireEvent.blur(names[0]);
    expect(last.applications[0].screens[0].elements[0].name).toBe("User name");
    expect(within(screenGroup).getByText("CSS selector")).toBeTruthy();
    fireEvent.click(within(screenGroup).getByRole("button", { name: "Remove Submit" }));
    expect(last.applications[0].screens[0].elements.map((e: any) => e.name)).toEqual(["User name"]);
  });

  it("picks an element of the step's kind (web or desktop), with search", async () => {
    const { api } = fakeApi();
    let picked: unknown = null;
    render(<PickElementDialog api={api} technology="web" onPick={(r) => { picked = r; }} onClose={() => {}} />);
    const list = await screen.findByRole("listbox", { name: "Elements" });
    expect(within(list).getAllByRole("option").map((o) => o.querySelector("strong")!.textContent)).toEqual(["Portal › Sign in › Name box", "Portal › Sign in › Submit"]);
    fireEvent.change(screen.getByLabelText("Find an element"), { target: { value: "user name" } });
    const only = within(screen.getByRole("listbox")).getAllByRole("option");
    expect(only).toHaveLength(1);
    fireEvent.click(only[0]);
    fireEvent.click(screen.getByRole("button", { name: "Use this element" }));
    expect(picked).toEqual({ repository: "Shared/Portal.objects.json", element: "Portal/Sign in/Name box" });
  });

  it("saves a capture as Application › Screen › Element, asking before replacing one", async () => {
    const { api, posted } = fakeApi();
    let saved: unknown = null;
    render(<SaveElementDialog api={api} object={{ technology: "web", properties: [] } as never} suggested="Submit" onSaved={(r) => { saved = r; }} onClose={() => {}} />);
    await screen.findByRole("option", { name: /Portal \(Shared\/Portal.objects.json\)/ });
    const save = screen.getByRole("button", { name: "Save and use it" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Application"), { target: { value: "Portal" } });
    fireEvent.change(screen.getByLabelText("Screen"), { target: { value: "Sign in" } });
    expect(save.disabled).toBe(true);   // Submit is there already: replace must be ticked
    fireEvent.click(screen.getByRole("checkbox", { name: /Replace the element/ }));
    expect(save.disabled).toBe(false);
    await act(async () => { fireEvent.click(save); });
    expect(posted).toEqual([{ repository: "Shared/Portal.objects.json", application: "Portal", screen: "Sign in", name: "Submit", description: "", object: { technology: "web", properties: [] }, replace: true }]);
    expect(saved).toEqual({ repository: "Shared/Portal.objects.json", element: "Portal/Sign in/Submit" });
  });
});
