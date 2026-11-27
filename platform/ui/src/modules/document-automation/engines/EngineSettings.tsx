import { useEffect, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../../components";
import type { DocFlowApi, Engine, EngineConnections, EngineField } from "../api";
import { Loading, reportError } from "../ui";

const OTHER = "\u0000other";
/** I-4: the settings a Model connection gives (the key, model and address); the escalation model and region stay the engine's own. */
const FROM_CONNECTION = /(_KEY|_ACCESS_KEY_ID|_MODEL|_BASE_URL|_ENDPOINT|_DEPLOYMENT|_SERVICE)$/;
const fromConnection = (f: EngineField) => !f.key.endsWith("_ESCALATION_MODEL") && (f.type === "secret" || FROM_CONNECTION.test(f.key));

/** An engine's own settings (address, key, model…). They apply to the whole server, so only its administrators can save them. */
export function EngineSettings({ api, engine, onClose, onSaved }: { api: DocFlowApi; engine: Engine; onClose: () => void; onSaved?: () => void }) {
  const [fields, setFields] = useState<EngineField[] | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  // select settings that take a value not in their list (a newer model, another region): typed, with the list as suggestions
  const [own, setOwn] = useState<Set<string>>(new Set());
  // I-4: an AI engine may take its key from a Model connection ("" = typed here)
  const [conns, setConns] = useState<EngineConnections | null>(null);
  const [conn, setConn] = useState("");
  useEffect(() => { api.engineSettings(engine.id).then((f) => { setFields(f); setValues(Object.fromEntries(f.map((x) => [x.key, x.value]))); }, (e) => { reportError(e); onClose(); }); }, [api, engine.id]);
  useEffect(() => { api.engineConnections().then((c) => { setConns(c); setConn(c.choices[engine.id] ?? ""); }, () => setConns(null)); }, [api, engine.id]);
  const fits = conns?.fits[engine.id];
  const usable = fits && conns ? conns.connections.filter((c) => fits.includes(c.provider)) : [];
  const shown = (fields ?? []).filter((f) => !conn || !fromConnection(f));
  const save = async () => {
    setBusy(true);
    try {
      if (fits && conns && conn !== (conns.choices[engine.id] ?? "")) await api.saveEngineConnection(engine.id, conn);
      const keys = new Set(shown.map((f) => f.key));
      await api.saveEngineSettings(Object.fromEntries(Object.entries(values).filter(([k]) => keys.has(k))));
      onSaved?.();
      onClose();
    } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title={"Configure " + engine.name} width={560}
      footer={<><Button onClick={onClose}>Cancel</Button>{fields && fields.length > 0 && <Button kind="primary" disabled={busy} onClick={save}>Save</Button>}</>}>
      {!fields ? <Loading /> : !fields.length ? <p style={{ margin: 0 }}>{engine.name} needs no settings.</p> : <>
        <p className="df-muted" style={{ margin: 0 }}>These settings apply to every client on this server. Keys are stored by DocFlow and never shown again.</p>
        {fits && <Field label="Where the key comes from"
          hint={usable.length ? "A Model connection keeps the key once, in the vault; DocFlow asks for it when it loads its engines."
            : `No ${fits.join(" or ")} Model connection yet: add one in Model connections, or type the key here.`}>
          {(id) => <Select id={id} value={conn} onChange={(e) => setConn(e.target.value)}
            options={[{ value: "", label: "Typed here" }, ...usable.map((c) => ({ value: c.name, label: "Model connection: " + c.name + (c.model ? ` (${c.model})` : "") }))]} />}
        </Field>}
        {shown.map((f) => (
          <Field key={f.key} label={f.label} hint={f.help}>
            {(id) => f.type === "bool"
              ? <Select id={id} value={values[f.key] === "true" ? "true" : "false"} onChange={(e) => setValues({ ...values, [f.key]: e.target.value })} options={[{ value: "true", label: "Yes" }, { value: "false", label: "No" }]} />
              : f.type === "select" && f.options && !own.has(f.key) && (f.options.includes(values[f.key]) || !values[f.key])
                ? <Select id={id} value={values[f.key]} onChange={(e) => e.target.value === OTHER ? setOwn(new Set([...own, f.key])) : setValues({ ...values, [f.key]: e.target.value })}
                    options={[...f.options.map((o) => ({ value: o, label: o || "(none)" })), { value: OTHER, label: "Other…" }]} />
                : <><TextInput id={id} type={f.type === "secret" ? "password" : "text"} autoComplete="off" value={values[f.key]} placeholder={f.placeholder}
                    list={f.options || f.suggestions ? id + "-list" : undefined} onChange={(e) => setValues({ ...values, [f.key]: e.target.value })} />
                  {(f.options || f.suggestions) && <datalist id={id + "-list"}>{[...(f.options ?? []), ...(f.suggestions ?? [])].filter(Boolean).map((o) => <option key={o} value={o} />)}</datalist>}</>}
          </Field>
        ))}
      </>}
    </Dialog>
  );
}
