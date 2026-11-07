import { useEffect, useState } from "react";
import { Button } from "../components";
import type { DocFlowApi, Language } from "./api";
import { Chip, FilterBar, FilterSelect, Loading, SearchBox, matches, reportError, useLoad } from "./ui";

const ENGINE: Record<string, string> = { tesseract: "Tesseract", paddleocr: "PaddleOCR" };
const readBy = (l: Language) => l.engines.map((e) => ENGINE[e] ?? e).join(", ");

/**
 * A document type's languages: the ones the administrator offers (any number can be ticked), and any the type has
 * that are no longer offered, so nothing is lost. A language no engine here can read says "not installed".
 */
export function LanguagePicker({ api, value, onChange }: { api: DocFlowApi; value: string[]; onChange: (codes: string[]) => void }) {
  const [all] = useLoad(() => api.languages(), [api]);
  const known = all ?? [];
  const shown: Language[] = [...known.filter((l) => l.offered),
    ...value.filter((c) => !known.some((l) => l.code === c && l.offered))
      .map((c) => known.find((l) => l.code === c) ?? { code: c, name: c, engines: [], installed: !all, offered: false })];
  return (
    <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
      <legend className="df-small" style={{ padding: 0, marginBottom: 6 }}>Languages</legend>
      <div className="df-langs">
        {shown.map((l) => (
          <label key={l.code} title={l.installed ? "Read by " + readBy(l) : "No engine on this server reads it yet"}>
            <input type="checkbox" checked={value.includes(l.code)}
              onChange={(e) => onChange(e.target.checked ? [...value, l.code] : value.filter((c) => c !== l.code))} />{l.name}
            {!l.installed && <span className="df-small df-text-warn">not installed</span>}
            {all && !l.offered && <span className="df-small">not offered</span>}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/**
 * Engines, Languages: which languages people may choose for document types (administrators). Every language the
 * engines know is listed, with the engines that read it here; ticking one that is not installed is allowed, and it
 * shows as "not installed" until its language data is added (Tesseract: tessdata).
 */
export function LanguagesPanel({ api }: { api: DocFlowApi }) {
  const [all, reload] = useLoad(() => api.languages(), [api]);
  const [chosen, setChosen] = useState<string[]>([]);
  const [q, setQ] = useState("");
  const [show, setShow] = useState<"all" | "offered" | "installed" | "missing">("all");
  const [saved, setSaved] = useState(false);
  useEffect(() => { if (all) setChosen(all.filter((l) => l.offered).map((l) => l.code)); }, [all]);
  if (!all) return <Loading />;

  const was = all.filter((l) => l.offered).map((l) => l.code);
  const dirty = JSON.stringify(was) !== JSON.stringify(chosen);
  const rows = all.filter((l) => matches(q, l.name, l.code) && (show === "all" || (show === "offered" ? chosen.includes(l.code)
    : show === "installed" ? l.installed : !l.installed)));
  const save = async () => {
    try { await api.saveOfferedLanguages(chosen); setSaved(true); reload(); } catch (e) { reportError(e); }
  };

  return (
    <section className="df-card" aria-label="Languages" style={{ overflow: "hidden" }}>
      <div className="df-head-row df-pad" style={{ paddingBottom: 0 }}>
        <div><h3 className="df-section-title">Languages</h3>
          <p className="df-muted" style={{ margin: "2px 0 0" }}>The languages people can choose for a document type. The engines read a page in the languages of its type.</p></div>
        <span className="df-grow" />
        {dirty && <span className="df-text-warn" role="status">Unsaved changes</span>}
        <Button kind="primary" disabled={!dirty || !chosen.length} onClick={save}>{saved && !dirty ? "Saved" : "Save languages"}</Button>
      </div>
      <FilterBar onClear={() => { setQ(""); setShow("all"); }} count={`${chosen.length} offered · ${all.filter((l) => l.installed).length} installed`}>
        <SearchBox value={q} onChange={setQ} placeholder="Search languages" />
        <FilterSelect label="Show" value={show} onChange={setShow} options={[{ value: "all", label: "All languages" }, { value: "offered", label: "Offered" },
          { value: "installed", label: "Installed" }, { value: "missing", label: "Not installed" }]} />
      </FilterBar>
      <div className="df-table-wrap">
        <table className="df-table" style={{ minWidth: 560 }}>
          <thead><tr><th>Offer</th><th>Language</th><th>Code</th><th>Read here by</th></tr></thead>
          <tbody>
            {rows.map((l) => (
              <tr key={l.code}>
                <td><input type="checkbox" aria-label={"Offer " + l.name} checked={chosen.includes(l.code)}
                  onChange={(e) => { setSaved(false); setChosen(e.target.checked ? [...chosen, l.code] : chosen.filter((c) => c !== l.code)); }} /></td>
                <td><b>{l.name}</b></td>
                <td className="ui-mono">{l.code}</td>
                <td>{l.installed ? readBy(l) : <Chip tone="warn">Not installed</Chip>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && <div className="df-empty">No language matches these filters.</div>}
      </div>
      <p className="df-small df-pad" style={{ margin: 0 }}>To read a language that is not installed, add its Tesseract language data (tessdata) on the DocFlow server, or install PaddleOCR.</p>
    </section>
  );
}
