import { useEffect, useMemo, useState } from "react";
import { Button, IconButton, TextInput } from "../../components";
import { formatFolderList, lastPart, parseFolderList, type DocFlowApi, type FolderAccess, type FolderRoots } from "./api";
import { HttpDocFlowApi } from "./http";

/**
 * DA-1 item 5: Administration > Settings > Document Automation, "Folders processes may use" (DocFlow 3.2.0,
 * DOCAUTO_ALLOWED_FOLDERS), laid out as the design plan's DA1-FolderAdmin artboard (approved 15 Nov 2026). Each folder
 * with the name people see, DocFlow's access to it and the processes using it; before saving, the processes a change
 * would leave outside. Needs docs.engines (DocFlow's admin settings). Shown in the Settings page as a module part.
 */

const ACCESS: Record<FolderAccess, [string, string]> = {
  ok: ["is-success", "Can read and move files"], read: ["is-pending", "Can read, cannot move files"],
  missing: ["is-failed", "Not found"], denied: ["is-failed", "No access: DocFlow's account cannot open it"],
};
const FULL_PATH = /^([A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+|\/)/;

export default function AllowedFolders({ api: given, onNotice, onError }: { api?: DocFlowApi; onNotice: (t: string) => void; onError: (e: string) => void }) {
  const api = useMemo(() => given ?? new HttpDocFlowApi(), [given]);
  const [saved, setSaved] = useState<string | null | undefined>(undefined);   // undefined: loading; null: not available
  const [problem, setProblem] = useState("");
  const [rows, setRows] = useState<{ name: string; path: string }[]>([]);
  const [preview, setPreview] = useState<FolderRoots | null>(null);
  const [path, setPath] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const text = (e: unknown) => (e instanceof Error ? e.message : String(e));
  useEffect(() => {
    api.allowedFolders().then((v) => { setSaved(v); setRows(parseFolderList(v ?? "")); }, (e) => { setSaved(null); setProblem(text(e)); });
  }, [api]);
  const list = formatFolderList(rows);
  useEffect(() => {   // DocFlow's access to each folder, and the processes a change would leave outside
    if (saved === undefined || saved === null) return;
    const t = setTimeout(() => { api.folderPreview(list).then(setPreview, () => setPreview(null)); }, 300);
    return () => clearTimeout(t);
  }, [api, list, saved]);
  const changed = saved !== undefined && saved !== null && list !== formatFolderList(parseFolderList(saved));
  const p = path.trim();
  const badPath = p && !FULL_PATH.test(p) ? "Write the full path, such as D:\\Scans or \\\\server\\share\\folder." : p.includes(";") ? "A folder path cannot contain ;" : "";
  const twice = !!p && rows.some((r) => r.path.toLowerCase() === p.toLowerCase());
  const add = () => { setRows([...rows, { name: name.trim() || lastPart(p), path: p }]); setPath(""); setName(""); };
  const save = async () => {
    setBusy(true);
    try {
      await api.saveAllowedFolders(list);
      setSaved(list);
      onNotice(rows.length ? `Saved: processes may use ${rows.length} folder${rows.length === 1 ? "" : "s"} and the folders inside them.` : "Saved: processes may use any folder again.");
    } catch (e) { onError(text(e)); } finally { setBusy(false); }
  };
  const outside = preview?.outside ?? [];
  const byProcess = [...new Map(outside.map((o) => [o.id, o.name])).entries()];

  return <section className="df-af" aria-labelledby="df-af-title">
    <h3 id="df-af-title">Folders processes may use</h3>
    <p className="st-muted">Processes can only watch, and save results in, these folders on DocFlow's server and the folders inside them. People choose from this list
      instead of typing a path.{saved !== undefined && saved !== null && !rows.length ? " No folders listed yet: any folder on DocFlow's server can be typed, as before." : ""}</p>
    {saved === undefined && <p className="st-muted">Loading the folders…</p>}
    {saved === null && <p className="st-muted">{problem ? "Not available: " + problem : "Needs DocFlow 3.2.0 or newer: update DocFlow to choose the folders processes may use."}</p>}
    {saved !== undefined && saved !== null && <>
      {rows.length > 0 && <table className="st-table" aria-label="Folders processes may use">
        <thead><tr><th scope="col">Folder on DocFlow's server</th><th scope="col">Name people see</th><th scope="col">DocFlow's access</th><th scope="col">Used by</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
        <tbody>{rows.map((r, i) => {
          const a = preview?.roots.find((x) => x.path === r.path);
          return <tr key={r.path}>
            <td className="df-af-path">{r.path}</td>
            <td><TextInput aria-label={"Name people see for " + r.path} value={r.name}
              onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, name: e.target.value.replace(/[;=\\/:]/g, "") } : x)))} /></td>
            <td>{a ? <span className={"cc-pill " + ACCESS[a.access][0]}>{ACCESS[a.access][1]}</span> : <span className="st-muted">Checking…</span>}</td>
            <td>{a?.usedBy.length ? <span title={a.usedBy.map((u) => u.name).join(", ")}>{a.usedBy.map((u) => u.name).join(", ")}</span> : <span className="st-muted">None</span>}</td>
            <td><IconButton icon="close" label={"Remove " + r.path} onClick={() => setRows(rows.filter((_, j) => j !== i))} /></td>
          </tr>;
        })}</tbody>
      </table>}
      <div className="cc-inline df-af-add">
        <TextInput aria-label="Folder to add" placeholder="\\server\share\folder or D:\folder" value={path} onChange={(e) => setPath(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && p && !badPath && !twice) add(); }} />
        <TextInput aria-label="Name people see for the folder to add" placeholder="Name people see, such as Scans" value={name} onChange={(e) => setName(e.target.value.replace(/[;=\\/:]/g, ""))} />
        <Button kind="secondary" disabled={!p || !!badPath || twice} onClick={add}>Add folder</Button>
      </div>
      {(badPath || twice) && <p className="st-muted" role="alert">{badPath || "That folder is already listed."}</p>}
      {byProcess.length > 0 && <div className="st-notice" role="status"><span>
        <b>{byProcess.length} process{byProcess.length === 1 ? " uses" : "es use"} a folder outside this list</b>; {changed ? "once you save, " : ""}DocFlow does not read or write there:{" "}
        {outside.map((o) => `${o.name} (${o.use === "watched" ? "watched folder" : "results"} ${o.path})`).join("; ")}. Add those folders, or choose others in the processes.</span></div>}
      <div className="cc-inline df-af-foot">
        <span className="st-muted">Network folders: the Windows account DocFlow runs as needs read and write access to the share.</span>
        <Button kind="plain" disabled={!changed || busy} onClick={() => setRows(parseFolderList(saved))}>Undo changes</Button>
        <Button kind="primary" disabled={!changed || busy} onClick={() => void save()}>{busy ? "Saving…" : "Save folders"}</Button>
      </div>
    </>}
  </section>;
}
