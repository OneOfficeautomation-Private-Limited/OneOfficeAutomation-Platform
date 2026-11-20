import { useEffect, useRef, useState } from "react";
import type { CrApi } from "../../api/cr";
import { Button, Select, TextArea } from "../../components";
import "./consoleApi";
import type { AssistantsMine } from "./consoleApi";

/**
 * My account > AI assistants (S2, 20 Nov 2026; the design plan's AIS-MyAccount, layout A): for One Chat and for Studio's
 * assistant, the person's own model (from the models the administrator allows), answer language (unless the
 * administrator has set one), and their own instructions, shown under the company's and added after them. Memory and
 * "always ask me first" come with the next steps (S3, S4).
 */
type Area = "chat" | "studio";
const NAMES: Record<Area, string> = { chat: "One Chat", studio: "Studio assistant" };

export function AssistantPrefs({ api, onNotice, onError }: { api: CrApi; onNotice: (t: string) => void; onError: (e: unknown) => void }) {
  const [data, setData] = useState<AssistantsMine | null>(null);
  const [area, setArea] = useState<Area>("chat");
  const [form, setForm] = useState<{ model: string; language: string; instructions: string; ask_first?: boolean }>({ model: "", language: "", instructions: "" });
  // the form is filled from the server once, and again only on switching assistant or after saving, so an answer
  // that arrives late (a second load) never undoes what the person is typing
  const filled = useRef(false);
  useEffect(() => { api.assistantsMine().then((d) => { setData(d); if (!filled.current) { filled.current = true; setForm({ ...d.assistants.chat.mine }); } }, onError); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  const choose = (k: Area) => { setArea(k); if (data) setForm({ ...data.assistants[k].mine }); };
  if (!data) return <p className="st-muted" role="status">Loading…</p>;
  const a = data.assistants[area];
  const changed = form.model !== a.mine.model || form.language !== a.mine.language || form.instructions !== a.mine.instructions || !!form.ask_first !== !!a.mine.ask_first;
  const save = () => api.saveAssistantPrefs(area, form).then((d) => { setData(d); setForm({ ...d.assistants[area].mine }); onNotice("Your choices for " + NAMES[area] + " are saved."); }, onError);
  const languages: [string, string][] = Object.entries(data.languages);
  return <div className="cc-ap" role="group" aria-label="AI assistants">
    <div className="st-seg" role="tablist" aria-label="Assistant">
      {(["chat", "studio"] as Area[]).map((k) => <button key={k} type="button" role="tab" aria-selected={area === k} className="st-seg-btn" onClick={() => choose(k)}>{NAMES[k]}</button>)}
    </div>
    <div className="cc-set-form">
      <div className="cc-set-group">Model and language</div>
      <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-model" className="cc-set-name">My model</label>
        <div className="cc-set-hint">{a.models.length ? "From the models your administrator allows." : "Your administrator has not offered a choice."}</div></div>
        <div className="cc-set-ctl">{a.models.length
          ? <Select id="cc-ap-model" value={form.model} onChange={(e) => { const v = e.target.value; setForm((f) => ({ ...f, model: v })); }}
              options={[{ value: "", label: "The company's default" + (a.default_model ? " (" + a.default_model + ")" : "") }, ...a.models.map((m) => ({ value: m, label: m }))]} />
          : <span id="cc-ap-model">{a.default_model || "Your first allowed One Model Connection"}</span>}</div></div>
      <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-lang" className="cc-set-name">Answer language</label>
        <div className="cc-set-hint">{a.language_fixed ? "Your administrator has set it." : "Agent names, codes and values are never translated."}</div></div>
        <div className="cc-set-ctl">{a.language_fixed
          ? <span id="cc-ap-lang">{data.languages[a.language_fixed] ?? a.language_fixed}</span>
          : <Select id="cc-ap-lang" value={form.language} onChange={(e) => { const v = e.target.value; setForm((f) => ({ ...f, language: v })); }}
              options={[{ value: "", label: "My console language" }, ...languages.map(([k, l]) => ({ value: k, label: l }))]} />}</div></div>
      <div className="cc-set-group">My instructions</div>
      <div className="cc-set-row"><div className="cc-set-text"><span className="cc-set-name">Company instructions</span>
        <div className="cc-set-hint">Set by your administrator; they always come first.</div></div>
        <div className="cc-set-ctl"><p className="cc-ap-company">{a.company_instructions || "None."}</p></div></div>
      <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-own" className="cc-set-name">My own instructions</label>
        <div className="cc-set-hint">{a.own_instructions ? "Added after the company's. Up to 2,000 characters." : "Your administrator does not allow your own instructions here."}</div></div>
        <div className="cc-set-ctl">{a.own_instructions
          ? <TextArea id="cc-ap-own" rows={3} maxLength={2000} value={form.instructions} placeholder="For example: I work in Accounts Payable for Germany. Show amounts in euros."
              onChange={(e) => { const v = e.target.value; setForm((f) => ({ ...f, instructions: v })); }} />
          : <span id="cc-ap-own">{form.instructions || "None."}</span>}</div></div>
      {area === "chat" && <>
        <div className="cc-set-group">Asking first</div>
        <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-ask" className="cc-set-name">Always ask me before an agent runs or something changes</label>
          <div className="cc-set-hint">Stricter than the company rule: even agents that would start at once wait for your yes.</div></div>
          <div className="cc-set-ctl"><label className="cc-switch"><input id="cc-ap-ask" type="checkbox" role="switch" aria-label="Always ask me before an agent runs or something changes" checked={!!form.ask_first}
            onChange={(e) => { const v = e.target.checked; setForm((f) => ({ ...f, ask_first: v })); }} /><span className="cc-switch-track" aria-hidden="true" />{form.ask_first ? "On" : "Off"}</label></div></div>
      </>}
      {area === "chat" && data.memory && <>
        <div className="cc-set-group">Memory</div>
        <div className="cc-set-row"><div className="cc-set-text"><span className="cc-set-name">What One Chat remembers for you</span>
          <div className="cc-set-hint">{data.memory.mode === "across" ? "Say \u201cremember that \u2026\u201d in One Chat to add a note. Only you see them."
            : "Your administrator has set memory to " + (data.memory.mode === "off" ? "off" : "this chat only") + ", so new notes are not kept."}</div></div>
          <div className="cc-set-ctl">{data.memory.notes.length ? <span className="cc-set-hint">{data.memory.notes.length} note{data.memory.notes.length === 1 ? "" : "s"}</span>
            : <span className="cc-set-hint">No notes.</span>}</div></div>
        {data.memory.notes.length > 0 && <ul className="cc-ap-notes" aria-label="Notes One Chat remembers">
          {data.memory.notes.map((n) => <li key={n.id}><span>{n.note}</span>
            <Button kind="plain" onClick={() => api.forgetNotes(n.id).then((d) => { setData(d); onNotice("Forgotten."); }, onError)}>Forget</Button></li>)}
          <li className="cc-ap-forget-all"><Button kind="plain" onClick={() => api.forgetNotes().then((d) => { setData(d); onNotice("One Chat has forgotten every note."); }, onError)}>Forget everything</Button></li>
        </ul>}
      </>}
      {changed && <div className="cc-set-save" role="group" aria-label={"Changes for " + NAMES[area]}>
        <span><strong>Changed</strong> for {NAMES[area]}</span><span className="ov-fill" />
        <Button kind="plain" onClick={() => setForm({ ...a.mine })}>Undo changes</Button>
        <Button kind="primary" onClick={save}>Save</Button>
      </div>}
    </div>
  </div>;
}
