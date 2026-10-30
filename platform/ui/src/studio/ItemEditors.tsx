import { useState, type ReactNode } from "react";
import type { WorkspaceNode } from "../api/client";
import { Button, Field, IconTile, Select, TextInput, type IconName } from "../components";

/**
 * Editors for the items other than agents: forms, workflows, document types, Prompt Skills and scripts. Each works on
 * the item's own file format, the one the runtime uses, and hands every change up as a whole new copy, so Undo
 * is keeping the old one.
 * A text field reports its change when it loses focus or on Enter, so typing a word is one undo step.
 */

type Obj = Record<string, unknown>;
type Change<T> = (next: T) => void;

/** Sets a key, or removes it when the value is empty, as Classic Studio's setOrDelete does. */
function withKey<T extends Obj>(o: T, k: string, v: unknown): T {
  const out = { ...o } as Obj;
  if (v === undefined || v === "" || v === false || (Array.isArray(v) && !v.length)) delete out[k]; else out[k] = v;
  return out as T;
}
const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));
const uniqueKey = (taken: string[], base: string) => { let n = 1, k = base; while (taken.includes(k)) k = base + "_" + ++n; return k; };
const ID = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

function TextField({ label, value, onCommit, multiline, placeholder, hint, mono, type }:
  { label: string; value: string; onCommit: (v: string) => void; multiline?: boolean; placeholder?: string; hint?: string; mono?: boolean; type?: string }) {
  return (
    <Field label={label} hint={hint}>{(id) => multiline
      ? <textarea key={value} id={id} className={"ui-input ui-textarea" + (mono ? " ui-mono" : "")} rows={4} defaultValue={value} placeholder={placeholder}
          onBlur={(e) => { if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); }} />
      : <TextInput key={value} id={id} type={type} mono={mono} defaultValue={value} placeholder={placeholder}
          onBlur={(e) => { if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); }}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); } }} />}
    </Field>
  );
}

/** A list of parts (form fields, workflow stages) with add, select, move and remove, and the selected part's settings. */
function PartsEditor<P extends Obj>({ title, parts, kinds, describe, onParts, settings, partSettings, newPart, addLabel }:
  { title: string; parts: P[]; kinds: Record<string, { label: string; icon: IconName; color: string }>;
    describe: (p: P) => { name: string; meta: string }; onParts: (next: P[], select?: number | null) => void;
    settings: ReactNode; partSettings: (p: P, set: (next: P) => void) => ReactNode; newPart: (kind: string) => P; addLabel: string }) {
  const [sel, setSel] = useState<number | null>(null);
  const [kind, setKind] = useState(Object.keys(kinds)[0]);
  const chosen = sel !== null && parts[sel] ? parts[sel] : null;
  const move = (i: number, by: number) => {
    const j = i + by;
    if (j < 0 || j >= parts.length) return;
    const next = [...parts]; [next[i], next[j]] = [next[j], next[i]];
    onParts(next); setSel(j);
  };
  return (
    <div className="st-item">
      <section className="st-item-main" aria-label={title}>
        <div className="st-edit-bar">
          <label className="st-add-part">{addLabel}
            <Select aria-label={addLabel} value={kind} onChange={(e) => setKind(e.target.value)} options={Object.entries(kinds).map(([k, m]) => ({ value: k, label: m.label }))} />
          </label>
          <Button kind="secondary" icon="plus" onClick={() => { const p = newPart(kind); onParts([...parts, p]); setSel(parts.length); }}>Add</Button>
          <span className="st-bar-gap" />
          <Button kind="plain" disabled={sel === null} onClick={() => sel !== null && move(sel, -1)}>Move up</Button>
          <Button kind="plain" disabled={sel === null} onClick={() => sel !== null && move(sel, 1)}>Move down</Button>
          <Button kind="plain" disabled={sel === null} onClick={() => { if (sel !== null) { onParts(parts.filter((_, k) => k !== sel)); setSel(null); } }}>Delete</Button>
        </div>
        <div className="st-marker st-start">START</div>
        <ol className="st-step-list">
          {parts.map((p, i) => {
            const m = kinds[String(p.type)] ?? Object.values(kinds)[0];
            const d = describe(p);
            return (
              <li key={i}>
                <button type="button" className={"st-step" + (sel === i ? " is-selected" : "")} aria-pressed={sel === i} onClick={() => setSel(sel === i ? null : i)}>
                  <span className="st-step-n">{i + 1}</span>
                  <IconTile name={m.icon} color={m.color} size={24} />
                  <strong>{d.name}</strong>
                  <span className="st-step-sum">{m.label}{d.meta ? " · " + d.meta : ""}</span>
                </button>
              </li>
            );
          })}
        </ol>
        {!parts.length && <p className="st-empty-steps">Nothing here yet. Choose what to add above and press Add.</p>}
        <div className="st-marker st-end">END</div>
      </section>
      <aside className="st-right st-item-props" aria-label="Settings">
        <div className="ui-panel-bar"><strong>{chosen ? describe(chosen).name : "Settings"}</strong>
          {chosen && <Button kind="plain" onClick={() => setSel(null)}>All settings</Button>}</div>
        <div className="st-prop-fields st-item-fields">
          {chosen ? partSettings(chosen, (next) => onParts(parts.map((x, k) => k === sel ? next : x))) : settings}
        </div>
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------- forms

const FORM_FIELDS: Record<string, { label: string; icon: IconName; color: string }> = {
  text: { label: "Text", icon: "form", color: "#0f766e" }, multiline: { label: "Multi-line text", icon: "doc", color: "#0f766e" },
  number: { label: "Number", icon: "code", color: "#0f766e" }, email: { label: "Email", icon: "mail", color: "#0f766e" },
  date: { label: "Date", icon: "pulse", color: "#0f766e" }, dropdown: { label: "Drop-down", icon: "chevronDown", color: "#0f766e" },
  radio: { label: "Choice", icon: "chevronRight", color: "#0f766e" }, checkbox: { label: "Checkbox", icon: "plus", color: "#0f766e" },
  file: { label: "File upload", icon: "folder", color: "#0f766e" }, heading: { label: "Heading", icon: "form", color: "#334155" },
  paragraph: { label: "Text block", icon: "doc", color: "#334155" }, button: { label: "Button", icon: "play", color: "#2f6fde" },
  area: { label: "Dynamic area", icon: "grid", color: "#2f6fde" },
};
const NOT_INPUT = ["heading", "paragraph", "button", "area"];

export function FormEditor({ data, onChange }: { data: Obj; onChange: Change<Obj> }) {
  const fields = (Array.isArray(data.fields) ? data.fields : []) as Obj[];
  const [idError, setIdError] = useState<string | null>(null);
  return (
    <PartsEditor title="Form fields" addLabel="Add a field" parts={fields} kinds={FORM_FIELDS}
      describe={(f) => ({ name: str(f.label) || str(f.id), meta: (NOT_INPUT.slice(0, 2).includes(str(f.type)) ? "" : str(f.id)) + (f.required ? " · required" : "") })}
      onParts={(next) => onChange({ ...data, fields: next })}
      newPart={(t) => {
        const f: Obj = { id: uniqueKey(fields.map((x) => str(x.id)), NOT_INPUT.includes(t) ? t : t + "_field"), type: t, label: FORM_FIELDS[t].label };
        if (t === "dropdown" || t === "radio") f.options = ["Option 1", "Option 2"];
        if (t === "paragraph") f.text = "Explain what to fill in.";
        return f;
      }}
      settings={<>
        <TextField label="Form name" value={str(data.name)} onCommit={(v) => onChange(withKey(data, "name", v))} />
        <TextField label="Description" multiline value={str(data.description)} onCommit={(v) => onChange(withKey(data, "description", v))} />
        <TextField label="Submit button" value={str(data.submitLabel) || "Submit"} onCommit={(v) => onChange(withKey(data, "submitLabel", v))} />
        <p className="st-muted">Answers come back as a Dictionary keyed by field id. Select a field to change it.</p>
      </>}
      partSettings={(f, set) => {
        const t = str(f.type), input = !NOT_INPUT.includes(t);
        const put = (k: string, v: unknown) => set(withKey(f, k, v));
        const setId = (v: string) => {
          const id = v.trim();
          if (!ID.test(id) || fields.some((x) => x !== f && x.id === id)) { setIdError("Field ids are unique and use letters, digits and _."); return; }
          setIdError(null); put("id", id);
        };
        return <>
          <TextField label="Label" value={str(f.label)} onCommit={(v) => put("label", v)} />
          <Field label="Type">{(id) => <Select id={id} value={t} onChange={(e) => put("type", e.target.value)} options={Object.entries(FORM_FIELDS).map(([k, m]) => ({ value: k, label: m.label }))} />}</Field>
          {(input || t === "button" || t === "area") && <TextField label={t === "button" ? "Button id" : t === "area" ? "Area id" : "Field id"} mono value={str(f.id)} onCommit={setId}
            hint={input ? "Agents and workflows read the answer as form." + str(f.id) + "." : t === "button" ? "The agent learns which button was pressed by this id." : "The agent fills this area while the form is open."} />}
          {idError && <p role="alert" className="st-error">{idError}</p>}
          {input && <label className="st-check-line"><input type="checkbox" checked={!!f.required} onChange={(e) => put("required", e.target.checked)} /> Required</label>}
          {["text", "multiline", "number", "email"].includes(t) && <TextField label="Placeholder" value={str(f.placeholder)} onCommit={(v) => put("placeholder", v)} />}
          {["dropdown", "radio"].includes(t) && <TextField label="Options, one per line" multiline value={((f.options as string[]) ?? []).join("\n")}
            onCommit={(v) => put("options", v.split("\n").map((x) => x.trim()).filter(Boolean))} />}
          {t === "number" && <>
            <TextField label="Minimum" type="number" value={str(f.min)} onCommit={(v) => put("min", v === "" ? undefined : Number(v))} />
            <TextField label="Maximum" type="number" value={str(f.max)} onCommit={(v) => put("max", v === "" ? undefined : Number(v))} />
          </>}
          {input && t !== "file" && <TextField label="Default value" value={str(f.default)} onCommit={(v) => put("default", v)} />}
          {input && <TextField label="Help text" value={str(f.help)} onCommit={(v) => put("help", v)} />}
          {t === "paragraph" && <TextField label="Text" multiline value={str(f.text)} onCommit={(v) => put("text", v)} />}
        </>;
      }} />
  );
}

// ---------------------------------------------------------------- workflows

const STAGES: Record<string, { label: string; icon: IconName; color: string }> = {
  bot: { label: "Agent task", icon: "bolt", color: "#2f6fde" }, form: { label: "Human task", icon: "form", color: "#0f766e" },
  approval: { label: "Approval", icon: "person", color: "#b45309" }, decision: { label: "Decision", icon: "branch", color: "#7c3aed" },
  notify: { label: "Notification", icon: "mail", color: "#0e7490" },
};

const everything = (n: WorkspaceNode): WorkspaceNode[] => (n.children ?? []).flatMap((c) => [c, ...everything(c)]);

export function WorkflowEditor({ data, onChange, tree }: { data: Obj; onChange: Change<Obj>; tree: WorkspaceNode | null }) {
  const stages = (Array.isArray(data.stages) ? data.stages : []) as Obj[];
  const items = tree ? everything(tree) : [];
  const pick = (type: string, none: string) => [{ value: "", label: none }, ...items.filter((x) => x.type === type).map((x) => ({ value: x.path, label: x.path }))];
  const nameOf = (id: unknown) => id === "end" ? "End" : str(stages.find((s) => s.id === id)?.name) || "the next stage";
  const targets = (self: Obj) => [{ value: "", label: "The next stage" }, { value: "end", label: "End the workflow" },
    ...stages.filter((s) => s !== self).map((s) => ({ value: str(s.id), label: str(s.name) || str(s.id) }))];
  return (
    <PartsEditor title="Workflow stages" addLabel="Add a stage" parts={stages} kinds={STAGES}
      describe={(s) => {
        const t = str(s.type);
        const meta = t === "bot" ? str(s.bot) || "No agent chosen" : t === "form" ? (str(s.form) || "No form chosen") + (s.assignee ? " · " + str(s.assignee) : "")
          : t === "approval" ? str(s.assignee) || "No approver" : t === "decision" ? (str(s.condition) || "No condition") + " → yes: " + nameOf(s.if_true) + ", no: " + nameOf(s.if_false)
          : str(s.to) || "No recipient";
        return { name: str(s.name) || str(s.id), meta };
      }}
      onParts={(next) => onChange({ ...data, stages: next })}
      newPart={(t) => ({ id: uniqueKey(stages.map((x) => str(x.id)), t), type: t, name: STAGES[t].label + " " + (stages.filter((x) => x.type === t).length + 1) })}
      settings={<>
        <TextField label="Name" value={str(data.name)} onCommit={(v) => onChange(withKey(data, "name", v))} />
        <TextField label="Description" multiline value={str(data.description)} onCommit={(v) => onChange(withKey(data, "description", v))} />
        <p className="st-muted">Stages run from top to bottom; decisions and approvals can jump to another stage. Publish the workflow with its agents and forms to the Command Center to start it there.</p>
      </>}
      partSettings={(s, set) => {
        const t = str(s.type);
        const put = (k: string, v: unknown) => set(withKey(s, k, v));
        const sel = (label: string, k: string, options: { value: string; label: string }[], value = str(s[k])) =>
          <Field label={label}>{(id) => <Select id={id} value={value} onChange={(e) => put(k, e.target.value)} options={options} />}</Field>;
        return <>
          <TextField label="Name" value={str(s.name)} onCommit={(v) => put("name", v)} />
          {t === "bot" && <>
            {sel("Agent", "bot", pick("bot", "Choose an agent"))}
            <TextField label="Inputs (JSON)" mono multiline value={s.inputs ? JSON.stringify(s.inputs, null, 2) : ""} placeholder='For example: {"order_id": "$form.order_id"}'
              onCommit={(v) => { try { const o = v.trim() ? JSON.parse(v) : undefined; if (o === undefined || (typeof o === "object" && !Array.isArray(o))) put("inputs", o); } catch { /* not JSON: kept as it was */ } }}
              hint="A JSON object of the agent's inputs; values can use $form.field." />
          </>}
          {t === "form" && <>
            {sel("Form", "form", pick("form", "Choose a form"))}
            <TextField label="Assign to" value={str(s.assignee)} placeholder="role:bot_runner or user:jane" onCommit={(v) => put("assignee", v)} />
            <TextField label="Due within (hours)" type="number" value={str(s.due_hours)} onCommit={(v) => put("due_hours", v === "" ? undefined : Number(v))} />
          </>}
          {t === "approval" && <>
            <TextField label="Approver" value={str(s.assignee)} placeholder="role:admin or user:fin.lead" onCommit={(v) => put("assignee", v)} />
            <TextField label="Due within (hours)" type="number" value={str(s.due_hours)} onCommit={(v) => put("due_hours", v === "" ? undefined : Number(v))} />
            {sel("If rejected", "on_reject", [{ value: "end", label: "End the workflow" }, { value: "continue", label: "Continue anyway" },
              ...stages.filter((x) => x !== s).map((x) => ({ value: str(x.id), label: "Go to " + (str(x.name) || str(x.id)) }))], str(s.on_reject) || "end")}
          </>}
          {t === "decision" && <>
            <TextField label="Condition" mono value={str(s.condition)} placeholder="$amount > 1000" onCommit={(v) => put("condition", v)} />
            {sel("If true, go to", "if_true", targets(s))}
            {sel("If false, go to", "if_false", targets(s))}
          </>}
          {t === "notify" && <>
            <TextField label="To" value={str(s.to)} placeholder="user:jane, role:admin or an email address" onCommit={(v) => put("to", v)} />
            <TextField label="E-mail subject" value={str(s.subject)} placeholder="Order ${order} approved" onCommit={(v) => put("subject", v)} hint="For e-mail addresses." />
            <TextField label="Message" multiline value={str(s.message)} onCommit={(v) => put("message", v)} />
          </>}
          <TextField label="Notes" multiline value={str(s.description)} onCommit={(v) => put("description", v)} />
        </>;
      }} />
  );
}

// ---------------------------------------------------------------- document types and Prompt Skills

const PROVIDERS = [["openai", "OpenAI"], ["azure_openai", "Azure OpenAI"], ["anthropic", "Anthropic"], ["openai_compatible", "OpenAI-compatible (local or other)"],
  ["huggingface", "Hugging Face"], ["aws_bedrock", "Amazon Bedrock"], ["google_vertex", "Google Vertex AI"]].map(([value, label]) => ({ value, label }));
const DOC_TYPES = ["pdf", "txt", "csv", "xml", "json", "md", "html"];
const VALUE_TYPES = ["String", "Number", "DateTime", "Boolean", "List"].map((t) => ({ value: t, label: t }));

export function DocumentEditor({ data, onChange }: { data: Obj; onChange: Change<Obj> }) {
  const fields = (Array.isArray(data.fields) ? data.fields : []) as Obj[];
  const accept = (Array.isArray(data.accept) ? data.accept : ["pdf", "txt"]) as string[];
  const setField = (i: number, k: string, v: unknown) => onChange({ ...data, fields: fields.map((f, j) => j === i ? withKey(f, k, v) : f) });
  return (
    <div className="st-doc">
      <section className="st-card" aria-label="Document type">
        <h2>Document type</h2>
        <div className="st-grid2">
          <TextField label="Name" value={str(data.name)} onCommit={(v) => onChange(withKey(data, "name", v))} />
          <Field label="AI provider">{(id) => <Select id={id} value={str(data.provider) || "openai"} onChange={(e) => onChange(withKey(data, "provider", e.target.value))} options={PROVIDERS} />}</Field>
          <TextField label="Description" value={str(data.description)} placeholder="Supplier invoices in English" onCommit={(v) => onChange(withKey(data, "description", v))} />
          <TextField label="Model" value={str(data.model)} placeholder="gpt-4o-mini" onCommit={(v) => onChange(withKey(data, "model", v))} />
        </div>
        <fieldset className="st-chips"><legend>Accepted files</legend>
          {DOC_TYPES.map((t) => <label key={t} className="st-check-line"><input type="checkbox" checked={accept.includes(t)}
            onChange={(e) => onChange({ ...data, accept: e.target.checked ? [...new Set([...accept, t])] : accept.filter((x) => x !== t) })} /> {t.toUpperCase()}</label>)}
        </fieldset>
      </section>
      <section className="st-card" aria-label="Fields to extract">
        <h2>Fields to extract</h2>
        <table className="st-table st-doc-fields">
          <thead><tr><th scope="col">Field</th><th scope="col">What to extract</th><th scope="col">Type</th><th scope="col">Required</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
          <tbody>
            {fields.map((f, i) => (
              <tr key={i}>
                <td><TextInput key={str(f.name)} aria-label="Field name" mono defaultValue={str(f.name)}
                  onBlur={(e) => { const v = e.currentTarget.value.trim(); if (ID.test(v) && v !== f.name) setField(i, "name", v); else e.currentTarget.value = str(f.name); }} /></td>
                <td><TextInput key={str(f.description)} aria-label="What to extract" defaultValue={str(f.description)} placeholder="Describe the value for the AI"
                  onBlur={(e) => { if (e.currentTarget.value !== str(f.description)) setField(i, "description", e.currentTarget.value); }} /></td>
                <td><Select aria-label="Type" value={str(f.type) || "String"} onChange={(e) => setField(i, "type", e.target.value)} options={VALUE_TYPES} /></td>
                <td className="st-center"><input type="checkbox" aria-label="Required" checked={!!f.required} onChange={(e) => setField(i, "required", e.target.checked)} /></td>
                <td><Button kind="plain" aria-label={"Remove " + str(f.name)} onClick={() => onChange({ ...data, fields: fields.filter((_, j) => j !== i) })}>Remove</Button></td>
              </tr>
            ))}
            {!fields.length && <tr><td colSpan={5} className="st-empty">No fields yet.</td></tr>}
          </tbody>
        </table>
        <Button kind="secondary" icon="plus" onClick={() => onChange({ ...data, fields: [...fields, { name: uniqueKey(fields.map((f) => str(f.name)), "field"), description: "", type: "String" }] })}>Add a field</Button>
      </section>
    </div>
  );
}

export function SkillEditor({ data, onChange, name }: { data: Obj; onChange: Change<Obj>; name: string }) {
  const put = (k: string, v: unknown) => onChange(withKey(data, k, v));
  return (
    <div className="st-doc">
      <section className="st-card" aria-label="Prompt Skill">
        <h2>Prompt Skill: {name}</h2>
        <TextField label="Description" value={str(data.description)} placeholder="What the skill does, for people and for AI agents" onCommit={(v) => put("description", v)} />
        <TextField label="Instructions (system)" multiline value={str(data.system)} onCommit={(v) => put("system", v)} />
        <TextField label="Prompt template" multiline mono value={str(data.prompt)} onCommit={(v) => put("prompt", v)} hint="Use ${name} for values the agent passes in." />
        <div className="st-grid2">
          <Field label="Provider">{(id) => <Select id={id} value={str(data.provider) || "openai"} onChange={(e) => put("provider", e.target.value)} options={PROVIDERS} />}</Field>
          <TextField label="Model" value={str(data.model)} placeholder="gpt-4o-mini" onCommit={(v) => put("model", v)} />
          <Field label="Answer as">{(id) => <Select id={id} value={str(data.output) || "text"} onChange={(e) => put("output", e.target.value)}
            options={[{ value: "text", label: "Text" }, { value: "json", label: "JSON (Dictionary)" }]} />}</Field>
          <TextField label="Temperature" type="number" value={str(data.temperature)} onCommit={(v) => onChange({ ...data, temperature: v === "" ? 0 : Number(v) })} />
          <TextField label="Base URL (optional)" value={str(data.base_url)} placeholder="For a local or other compatible service" onCommit={(v) => put("base_url", v)} />
        </div>
        <label className="st-check-line"><input type="checkbox" checked={data.mask_pii !== false} onChange={(e) => onChange({ ...data, mask_pii: e.target.checked })} /> Hide personal data from the model</label>
        <p className="st-muted">Agents run this skill with the Run Prompt Skill action (skill = {name}). API keys never go in skill files.</p>
      </section>
    </div>
  );
}

/** Scripts and other text: a plain editor with line numbers' count; each change is kept when the field loses focus. */
export function TextEditor({ text, onChange, language }: { text: string; onChange: Change<string>; language: string }) {
  return (
    <div className="st-doc">
      <section className="st-card st-code" aria-label="Script">
        <h2>{language}</h2>
        <textarea key={text} className="ui-input ui-textarea ui-mono st-code-area" aria-label={language} spellCheck={false} defaultValue={text}
          onBlur={(e) => { if (e.currentTarget.value !== text) onChange(e.currentTarget.value); }}
          onKeyDown={(e) => {
            // Tab indents instead of leaving the editor; Esc then Tab leaves it, so keyboard users are never trapped
            const t0 = e.currentTarget;
            if (e.key === "Escape") { t0.dataset.leave = "1"; return; }
            if (e.key !== "Tab" || e.shiftKey || t0.dataset.leave === "1") { delete t0.dataset.leave; return; }
            e.preventDefault();
            const t = e.currentTarget, a = t.selectionStart;
            t.setRangeText("    ", a, t.selectionEnd, "end");
          }} />
        <p className="st-muted">{text.split("\n").length} lines. Tab indents. Agents run it with the Scripts toolkit (Run Python, Run PowerShell and so on).</p>
      </section>
    </div>
  );
}
