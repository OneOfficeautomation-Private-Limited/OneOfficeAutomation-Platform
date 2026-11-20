import { useEffect, useState } from "react";
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
  const [form, setForm] = useState({ model: "", language: "", instructions: "" });
  useEffect(() => { api.assistantsMine().then(setData, onError); }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (data) setForm({ ...data.assistants[area].mine }); }, [data, area]);
  if (!data) return <p className="st-muted" role="status">Loading…</p>;
  const a = data.assistants[area];
  const changed = form.model !== a.mine.model || form.language !== a.mine.language || form.instructions !== a.mine.instructions;
  const save = () => api.saveAssistantPrefs(area, form).then((d) => { setData(d); onNotice("Your choices for " + NAMES[area] + " are saved."); }, onError);
  const languages: [string, string][] = Object.entries(data.languages);
  return <div className="cc-ap" role="group" aria-label="AI assistants">
    <div className="st-seg" role="tablist" aria-label="Assistant">
      {(["chat", "studio"] as Area[]).map((k) => <button key={k} type="button" role="tab" aria-selected={area === k} className="st-seg-btn" onClick={() => setArea(k)}>{NAMES[k]}</button>)}
    </div>
    <div className="cc-set-form">
      <div className="cc-set-group">Model and language</div>
      <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-model" className="cc-set-name">My model</label>
        <div className="cc-set-hint">{a.models.length ? "From the models your administrator allows." : "Your administrator has not offered a choice."}</div></div>
        <div className="cc-set-ctl">{a.models.length
          ? <Select id="cc-ap-model" value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })}
              options={[{ value: "", label: "The company's default" + (a.default_model ? " (" + a.default_model + ")" : "") }, ...a.models.map((m) => ({ value: m, label: m }))]} />
          : <span id="cc-ap-model">{a.default_model || "Your first allowed One Model Connection"}</span>}</div></div>
      <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-lang" className="cc-set-name">Answer language</label>
        <div className="cc-set-hint">{a.language_fixed ? "Your administrator has set it." : "Agent names, codes and values are never translated."}</div></div>
        <div className="cc-set-ctl">{a.language_fixed
          ? <span id="cc-ap-lang">{data.languages[a.language_fixed] ?? a.language_fixed}</span>
          : <Select id="cc-ap-lang" value={form.language} onChange={(e) => setForm({ ...form, language: e.target.value })}
              options={[{ value: "", label: "My console language" }, ...languages.map(([k, l]) => ({ value: k, label: l }))]} />}</div></div>
      <div className="cc-set-group">My instructions</div>
      <div className="cc-set-row"><div className="cc-set-text"><span className="cc-set-name">Company instructions</span>
        <div className="cc-set-hint">Set by your administrator; they always come first.</div></div>
        <div className="cc-set-ctl"><p className="cc-ap-company">{a.company_instructions || "None."}</p></div></div>
      <div className="cc-set-row"><div className="cc-set-text"><label htmlFor="cc-ap-own" className="cc-set-name">My own instructions</label>
        <div className="cc-set-hint">{a.own_instructions ? "Added after the company's. Up to 2,000 characters." : "Your administrator does not allow your own instructions here."}</div></div>
        <div className="cc-set-ctl">{a.own_instructions
          ? <TextArea id="cc-ap-own" rows={3} maxLength={2000} value={form.instructions} placeholder="For example: I work in Accounts Payable for Germany. Show amounts in euros."
              onChange={(e) => setForm({ ...form, instructions: e.target.value })} />
          : <span id="cc-ap-own">{form.instructions || "None."}</span>}</div></div>
      {changed && <div className="cc-set-save" role="group" aria-label={"Changes for " + NAMES[area]}>
        <span><strong>Changed</strong> for {NAMES[area]}</span><span className="ov-fill" />
        <Button kind="plain" onClick={() => setForm({ ...a.mine })}>Undo changes</Button>
        <Button kind="primary" onClick={save}>Save</Button>
      </div>}
    </div>
  </div>;
}
