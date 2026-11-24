import { useEffect, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../../components";
import type { DocFlowApi, Engine, EngineField } from "../api";
import { Loading, reportError } from "../ui";

const OTHER = "\u0000other";

/** An engine's own settings (address, key, model…). They apply to the whole server, so only its administrators can save them. */
export function EngineSettings({ api, engine, onClose }: { api: DocFlowApi; engine: Engine; onClose: () => void }) {
  const [fields, setFields] = useState<EngineField[] | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  // select settings that take a value not in their list (a newer model, another region): typed, with the list as suggestions
  const [own, setOwn] = useState<Set<string>>(new Set());
  useEffect(() => { api.engineSettings(engine.id).then((f) => { setFields(f); setValues(Object.fromEntries(f.map((x) => [x.key, x.value]))); }, (e) => { reportError(e); onClose(); }); }, [api, engine.id]);
  const save = async () => {
    setBusy(true);
    try { await api.saveEngineSettings(values); onClose(); } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title={"Configure " + engine.name} width={560}
      footer={<><Button onClick={onClose}>Cancel</Button>{fields && fields.length > 0 && <Button kind="primary" disabled={busy} onClick={save}>Save</Button>}</>}>
      {!fields ? <Loading /> : !fields.length ? <p style={{ margin: 0 }}>{engine.name} needs no settings.</p> : <>
        <p className="df-muted" style={{ margin: 0 }}>These settings apply to every client on this server. Keys are stored by DocFlow and never shown again.</p>
        {fields.map((f) => (
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
