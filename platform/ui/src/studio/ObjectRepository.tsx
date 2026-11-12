import { useEffect, useMemo, useState } from "react";
import { type ObjectRef, type RepoCatalogue, type StudioApi, errorText } from "../api/client";
import { Button, Dialog, Field, Icon, Select, TextInput } from "../components";
import type { Captured } from "./capture";

/**
 * Shared object repositories (G42): captured objects kept once, as Application › Screen › Element, and used by many
 * agents. A step's captured object can be saved to a repository (the step then uses it from there) or chosen from one.
 */
type Obj = Record<string, unknown>;
const arr = (v: unknown) => (Array.isArray(v) ? v : []) as Obj[];
const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));
const NAME_OK = (v: string) => v.trim().length > 0 && v.trim().length <= 120 && !v.includes("/");

export const isObjectRef = (v: unknown): v is ObjectRef => !!v && typeof v === "object" && !Array.isArray(v)
  && Object.keys(v).length === 2 && typeof (v as ObjectRef).repository === "string" && typeof (v as ObjectRef).element === "string";

/** How an element is found, in a few words: by CSS, XPath, or the ticked properties' names. */
export function foundBy(o: Captured | undefined): string {
  if (!o) return "Nothing captured";
  if (o.search_by === "css") return "CSS selector";
  if (o.search_by === "xpath") return "XPath";
  const used = (o.properties ?? []).filter((p) => p.use).map((p) => p.name);
  return used.length ? used.slice(0, 4).join(", ") + (used.length > 4 ? " +" + (used.length - 4) : "") : "No property ticked";
}

/** The repository file as an editor: name, description, then each application with its screens and their elements. */
export function ObjectRepositoryEditor({ data, onChange }: { data: Obj; onChange: (next: Obj) => void }) {
  const apps = arr(data.applications);
  const setApps = (next: Obj[]) => onChange({ ...data, applications: next });
  const setApp = (ai: number, a: Obj) => setApps(apps.map((x, i) => (i === ai ? a : x)));
  const setScreen = (ai: number, si: number, s: Obj) => setApp(ai, { ...apps[ai], screens: arr(apps[ai].screens).map((x, i) => (i === si ? s : x)) });
  const nameInput = (label: string, value: string, taken: string[], commit: (v: string) => void) => (
    <TextInput key={value} aria-label={label} defaultValue={value} className="st-objrepo-name"
      onBlur={(e) => { const v = e.currentTarget.value.trim(); if (NAME_OK(v) && v !== value && !taken.includes(v)) commit(v); else e.currentTarget.value = value; }} />
  );
  const count = apps.reduce((n, a) => n + arr(a.screens).reduce((m, s) => m + arr(s.elements).length, 0), 0);
  return (
    <div className="st-doc">
      <section className="st-card" aria-label="Object repository">
        <h2>Object repository</h2>
        <div className="st-grid2">
          <Field label="Name">{(id) => <TextInput id={id} key={str(data.name)} defaultValue={str(data.name)} onBlur={(e) => { if (e.currentTarget.value !== str(data.name)) onChange({ ...data, name: e.currentTarget.value }); }} />}</Field>
          <Field label="Description">{(id) => <TextInput id={id} key={str(data.description)} defaultValue={str(data.description)} placeholder="SAP GUI screens used by finance agents"
            onBlur={(e) => { if (e.currentTarget.value !== str(data.description)) onChange({ ...data, description: e.currentTarget.value }); }} />}</Field>
        </div>
        <p className="st-muted">{count} element{count === 1 ? "" : "s"}. Agents use these elements by name, and a change here reaches every agent that uses
          them on its next run. To add one, capture it in an agent's step and choose <strong>Save to repository</strong>. Renaming changes the name agents use:
          an agent that still uses the old name shows a problem when it is saved.</p>
      </section>
      {apps.map((a, ai) => {
        const screens = arr(a.screens);
        return (
          <section key={ai} className="st-card" aria-label={"Application " + str(a.name)}>
            <div className="st-objrepo-head">
              <Icon name="desk" size={16} />
              {nameInput("Application name", str(a.name), apps.map((x) => str(x.name)), (v) => setApp(ai, { ...a, name: v }))}
              <span className="st-bar-fill" />
              <button type="button" className="st-tool" aria-label={"Remove application " + str(a.name)} title="Remove this application and everything in it"
                onClick={() => { if (window.confirm("Remove " + str(a.name) + " and its " + screens.length + " screen(s)?")) setApps(apps.filter((_, i) => i !== ai)); }}><Icon name="trash" size={15} /></button>
            </div>
            {screens.map((s, si) => {
              const els = arr(s.elements);
              return (
                <div key={si} className="st-objrepo-screen" role="group" aria-label={"Screen " + str(s.name)}>
                  <div className="st-objrepo-head">
                    <Icon name="grid" size={15} />
                    {nameInput("Screen name", str(s.name), screens.map((x) => str(x.name)), (v) => setScreen(ai, si, { ...s, name: v }))}
                    <span className="st-bar-fill" />
                    <button type="button" className="st-tool" aria-label={"Remove screen " + str(s.name)} title="Remove this screen and its elements"
                      onClick={() => { if (window.confirm("Remove the screen " + str(s.name) + " and its " + els.length + " element(s)?")) setApp(ai, { ...a, screens: screens.filter((_, i) => i !== si) }); }}><Icon name="trash" size={15} /></button>
                  </div>
                  <table className="st-table">
                    <thead><tr><th scope="col">Element</th><th scope="col">Kind</th><th scope="col">Found by</th><th scope="col">Description</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
                    <tbody>
                      {els.map((e, ei) => {
                        const o = e.object as Captured | undefined;
                        const setEl = (next: Obj) => setScreen(ai, si, { ...s, elements: els.map((x, i) => (i === ei ? next : x)) });
                        return (
                          <tr key={ei}>
                            <td>{nameInput("Element name", str(e.name), els.map((x) => str(x.name)), (v) => setEl({ ...e, name: v }))}</td>
                            <td>{o?.technology === "desktop" ? "Desktop" : "Web"}</td>
                            <td className="st-muted">{foundBy(o)}</td>
                            <td><TextInput key={str(e.description)} aria-label="Element description" defaultValue={str(e.description)}
                              onBlur={(ev) => { if (ev.currentTarget.value !== str(e.description)) setEl({ ...e, description: ev.currentTarget.value }); }} /></td>
                            <td><button type="button" className="st-tool" aria-label={"Remove " + str(e.name)} title={"Remove " + str(e.name)}
                              onClick={() => setScreen(ai, si, { ...s, elements: els.filter((_, i) => i !== ei) })}><Icon name="trash" size={15} /></button></td>
                          </tr>
                        );
                      })}
                      {!els.length && <tr><td colSpan={5} className="st-empty">No elements on this screen.</td></tr>}
                    </tbody>
                  </table>
                </div>
              );
            })}
          </section>
        );
      })}
      {!apps.length && <p className="st-empty st-pad">No elements yet. In an agent, capture an element, then choose Save to repository.</p>}
    </div>
  );
}

const failText = errorText;

/** Choose an element from any object repository in the workspace. */
export function PickElementDialog({ api, technology, onPick, onClose }:
  { api: StudioApi; technology: "web" | "desktop"; onPick: (ref: ObjectRef) => void; onClose: () => void }) {
  const [cat, setCat] = useState<RepoCatalogue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [chosen, setChosen] = useState<ObjectRef | null>(null);
  useEffect(() => { api.objectCatalogue().then(setCat, (e) => setError(failText(e))); }, [api]);
  const rows = useMemo(() => (cat ?? []).flatMap((r) => r.elements.filter((e) => (e.technology || "web") === technology)
    .filter((e) => !q.trim() || (r.name + " " + e.element + " " + e.description).toLowerCase().includes(q.trim().toLowerCase()))
    .map((e) => ({ repo: r, el: e }))), [cat, q, technology]);
  return (
    <Dialog open onClose={onClose} title="Use an element from a repository" width={680}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><span className="st-bar-fill" />
        <Button kind="primary" disabled={!chosen} onClick={() => chosen && onPick(chosen)}>Use this element</Button></>}>
      <TextInput type="search" aria-label="Find an element" placeholder="Find an element" value={q} onChange={(e) => setQ(e.target.value)} />
      {error ? <p className="st-error" role="alert">{error}</p> : !cat ? <p className="st-muted">Reading the repositories…</p>
        : !rows.length ? <p className="st-muted">{cat.length ? "No " + (technology === "desktop" ? "desktop" : "web") + " element matches." : "No object repository in the workspace yet. Create one with + Create > Object repository."}</p>
        : <ul className="st-objrepo-pick" role="listbox" aria-label="Elements">
            {rows.map(({ repo, el }) => {
              const sel = chosen?.repository === repo.path && chosen.element === el.element;
              return <li key={repo.path + "#" + el.element} role="option" aria-selected={sel} className={sel ? "is-selected" : ""} tabIndex={0}
                onClick={() => setChosen({ repository: repo.path, element: el.element })}
                onDoubleClick={() => onPick({ repository: repo.path, element: el.element })}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setChosen({ repository: repo.path, element: el.element }); } }}>
                <strong>{el.element.split("/").join(" › ")}</strong>
                <span className="st-muted">{repo.name}{el.description ? " · " + el.description : ""}</span>
              </li>;
            })}
          </ul>}
    </Dialog>
  );
}

/** Save the step's captured object to a repository as Application › Screen › Element; the step then uses it from there. */
export function SaveElementDialog({ api, object, suggested, onSaved, onClose }:
  { api: StudioApi; object: Captured; suggested: string; onSaved: (ref: ObjectRef) => void; onClose: () => void }) {
  const [cat, setCat] = useState<RepoCatalogue | null>(null);
  const [repo, setRepo] = useState("");
  const [app, setApp] = useState("");
  const [screen, setScreen] = useState("");
  const [name, setName] = useState(suggested);
  const [description, setDescription] = useState("");
  const [replace, setReplace] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api.objectCatalogue().then((c) => { setCat(c); if (c.length) setRepo(c[0].path); }, (e) => setError(failText(e))); }, [api]);
  const parts = (cat?.find((r) => r.path === repo)?.elements ?? []).map((e) => e.element.split("/"));
  const apps = [...new Set(parts.map((p) => p[0]))], screens = [...new Set(parts.filter((p) => p[0] === app).map((p) => p[1]))];
  const exists = parts.some((p) => p[0] === app.trim() && p[1] === screen.trim() && p[2] === name.trim());
  const ok = !!repo && NAME_OK(app) && NAME_OK(screen) && NAME_OK(name) && (!exists || replace);
  const save = async () => {
    setBusy(true); setError(null);
    try { onSaved(await api.addToRepository({ repository: repo, application: app.trim(), screen: screen.trim(), name: name.trim(), description, object, replace })); }
    catch (e) { setError(failText(e)); }
    finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title="Save to an object repository" width={560}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><span className="st-bar-fill" />
        <Button kind="primary" disabled={!ok || busy} onClick={save}>{busy ? "Saving…" : "Save and use it"}</Button></>}>
      {cat && !cat.length ? <p className="st-alert" role="note">There is no object repository in the workspace yet. Create one with <strong>+ Create &gt; Object repository</strong>, then save here.</p> : <>
        <Field label="Repository">{(id) => <Select id={id} value={repo} onChange={(e) => setRepo(e.target.value)} options={(cat ?? []).map((r) => ({ value: r.path, label: r.name + " (" + r.path + ")" }))} />}</Field>
        <Field label="Application" hint="The program or website, such as SAP GUI or Supplier portal.">{(id) => <>
          <TextInput id={id} list="st-objrepo-apps" value={app} onChange={(e) => setApp(e.target.value)} />
          <datalist id="st-objrepo-apps">{apps.map((a) => <option key={a} value={a} />)}</datalist></>}</Field>
        <Field label="Screen" hint="The window or page, such as Login or Invoice entry.">{(id) => <>
          <TextInput id={id} list="st-objrepo-screens" value={screen} onChange={(e) => setScreen(e.target.value)} />
          <datalist id="st-objrepo-screens">{screens.map((s) => <option key={s} value={s} />)}</datalist></>}</Field>
        <Field label="Element name">{(id) => <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description">{(id) => <TextInput id={id} value={description} placeholder="Optional" onChange={(e) => setDescription(e.target.value)} />}</Field>
        {[app, screen, name].some((v) => v.includes("/")) && <p className="st-error">Names cannot contain /.</p>}
        {exists && <label className="st-check-line"><input type="checkbox" checked={replace} onChange={(e) => setReplace(e.target.checked)} /> Replace the element already saved with this name (agents using it get this object)</label>}
      </>}
      {error && <p className="st-error" role="alert">{error}</p>}
    </Dialog>
  );
}
