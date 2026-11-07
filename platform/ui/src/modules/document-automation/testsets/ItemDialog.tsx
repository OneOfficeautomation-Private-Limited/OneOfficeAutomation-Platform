import { useRef, useState } from "react";
import { Button, Dialog, Field, TextInput } from "../../../components";
import type { TestSetItem } from "../api";
import { value } from "./format";

/**
 * A test document's right values, one box per field of the set's document type (tables are not scored), and its tags.
 * For a new one, the file too. Empty boxes are not tested.
 */
export function ItemDialog({ item, fields, onSave, onClose }: {
  item: TestSetItem | null; fields: { key: string; label: string }[];
  onSave: (file: File | null, expected: Record<string, string>, tags: string[]) => Promise<void>; onClose: () => void;
}) {
  const picker = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  // a value saved for a field the type no longer has still shows, so it can be cleared
  const all = [...fields, ...Object.keys(item?.expected ?? {}).filter((k) => !fields.some((f) => f.key === k)).map((k) => ({ key: k, label: k }))];
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(all.map((f) => [f.key, item && item.expected[f.key] !== undefined ? value(item.expected[f.key]) : ""])));
  const [tags, setTags] = useState((item?.tags ?? []).join(", "));
  const [saving, setSaving] = useState(false);
  const filled = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v && v !== "—"));
  const ready = Object.keys(filled).length > 0 && (item || file);
  const save = async () => {
    setSaving(true);
    try { await onSave(file, filled, tags.split(",").map((t) => t.trim()).filter(Boolean)); } finally { setSaving(false); }
  };

  return (
    <Dialog open onClose={onClose} title={item ? `Right values of ${item.fileName}` : "Add a document"} width={560}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!ready || saving} onClick={save}>{item ? "Save" : "Add document"}</Button></>}>
      {!item && (
        <div className="ui-field">
          <span className="ui-field-label">Document</span>
          <input ref={picker} type="file" hidden accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff,.xml" aria-label="Choose a document"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <Button onClick={() => picker.current?.click()}>{file ? file.name : "Choose a document…"}</Button>
        </div>
      )}
      <p className="df-muted" style={{ margin: 0 }}>Type each value as it is right. Leave a box empty to not test that field.</p>
      <div className="df-grid df-g2" style={{ gap: 10 }}>
        {all.map((f) => (
          <Field key={f.key} label={f.label}>{(id) => <TextInput id={id} dir="auto" className="df-num" value={values[f.key] ?? ""}
            onChange={(e) => setValues({ ...values, [f.key]: e.target.value })} />}</Field>
        ))}
      </div>
      <Field label="Tags (optional)" hint="Comma separated, e.g. scan, bilingual">{(id) => <TextInput id={id} value={tags} onChange={(e) => setTags(e.target.value)} />}</Field>
    </Dialog>
  );
}
