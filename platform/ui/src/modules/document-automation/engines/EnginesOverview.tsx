import { Button, Select } from "../../../components";
import type { Engine, EngineOption, Routing } from "../api";
import { Chip, dfHash } from "../ui";
import { engineStatus } from "./EngineCatalog";

/** The three groups, in plain words (artboard "DA-14"). */
const GROUPS: { id: EngineOption["id"]; title: string; note: string }[] = [
  { id: "onecore", title: "On this server", note: "free, nothing leaves" },
  { id: "oneconnect", title: "Cloud document services", note: "need cloud opt-in" },
  { id: "onemind", title: "AI models", note: "through model connections" },
];
/** AI models read fields and recognise types; they do not read pages on their own. */
const AI_MODELS = ["claude", "gemini", "bedrock", "azure-openai", "llm-cloud", "llm-local"];
/** Engines that only support others (the PDF text layer) are not offered as a job's engine. */
const NOT_A_READER = new Set(["pdf-text", "builtin-rules", "abbyy", ...AI_MODELS]);
const FIELD_READERS = ["builtin-rules", ...AI_MODELS, "azure-di", "aws-textract", "google-docai"];

/**
 * Engines, Overview (28 Nov 2026, DA-14, artboard "Engines and AI connections"): who does each job for every process
 * (read the page, recognise and split, read the fields, read again when unsure), and the engines in three groups with
 * their state. Testing and configuring an engine, and the routing details, stay in their own sections.
 */
export function EnginesOverview({ routing, options, dirty, onChange, onSave }:
  { routing: Routing; options: EngineOption[]; dirty: boolean; onChange: (r: Routing) => void; onSave: () => void }) {
  const all = options.flatMap((o) => o.engines);
  const byId = (id: string) => all.find((e) => e.id === id);
  const label = (e: Engine) => e.name + (e.runs === "cloud" && !routing.cloudOptIn ? " (cloud opt-in is off)" : e.status === "setup" ? " (needs setting up)" : "");
  // cloud recognise-and-split services (they can "split") only recognise; they do not read pages or fields
  const pageReaders = all.filter((e) => !NOT_A_READER.has(e.id) && !e.can?.includes("split"));
  const fieldReaders = FIELD_READERS.map(byId).filter((e): e is Engine => !!e);
  const ocr = routing.ocr || "tesseract", fields = routing.extraction || "builtin-rules";
  const retry = routing.localRetry.split(",").map((x) => x.trim()).filter(Boolean)[0];
  const cloud = routing.cloudOptIn && routing.cloudEngine ? byId(routing.cloudEngine) : undefined;
  // engines that can recognise a document type (DocFlow says so; 3.4.0 uses the chosen one in processes)
  const recognisers = all.filter((e) => e.can?.includes("classify"));
  const connections = location.hash.startsWith("#/docautomation") ? "#/connections" : undefined;
  return (
    <div className="df-engines-overview">
      <section className="df-sec" aria-label="Who does each job">
        <div className="df-sec-head"><b>Who does each job</b><span className="df-small">the default for every process; a process may choose another in its steps</span>
          <span className="df-grow" />{dirty && <Button kind="primary" onClick={onSave}>Save</Button>}</div>
        <div className="df-jobs">
          <div className="df-job">
            <b>Read the page</b><span className="df-small">Turn pictures and scans into text.</span>
            <Select aria-label="Read the page" value={ocr} onChange={(e) => onChange({ ...routing, ocr: e.target.value })}
              options={pageReaders.map((e) => ({ value: e.id, label: label(e) }))} />
          </div>
          <div className="df-job">
            <b>Recognise and split</b><span className="df-small">Which type each document is, from each type's description; batch scans cut into documents.</span>
            {routing.classify !== undefined && recognisers.length
              ? <Select aria-label="Recognise and split" value={routing.classify} onChange={(e) => onChange({ ...routing, classify: e.target.value })}
                  options={[{ value: "", label: "Words on the page (this server)" }, ...recognisers.map((e) => ({ value: e.id, label: label(e) }))]} />
              : <span className="df-job-fixed">Words on the page (this server). An AI engine can recognise types from DocFlow 3.4.0.</span>}
          </div>
          <div className="df-job">
            <b>Read the fields</b><span className="df-small">Fill each type's fields from the page.</span>
            <Select aria-label="Read the fields" value={fields} onChange={(e) => onChange({ ...routing, extraction: e.target.value })}
              options={fieldReaders.map((e) => ({ value: e.id, label: label(e) }))} />
          </div>
          <div className="df-job">
            <b>Read again when unsure</b><span className="df-small">A second look before a person reviews it.</span>
            <span className="df-job-fixed">{routing.retryBelow > 0 ? `Pages under ${Math.round(routing.retryBelow * 100)}%: ${retry ? byId(retry)?.name ?? retry : "no retry"}${cloud ? `, then ${cloud.name}` : ""}` : "Never"}
              {" · "}<a href={dfHash() + "engines/routing"}>Change</a></span>
          </div>
        </div>
      </section>

      <div className="df-engine-groups">
        {GROUPS.map((g) => {
          const option = options.find((o) => o.id === g.id);
          if (!option) return null;
          return (
            <section key={g.id} className="df-sec" aria-label={g.title}>
              <div className="df-sec-head"><b>{g.title}</b><span className="df-small">· {g.note}</span><span className="df-grow" />
                <a href={dfHash() + "engines/catalog/" + g.id} style={{ fontWeight: 600 }}>Test or configure ↗</a></div>
              {option.engines.map((e) => {
                const st = engineStatus(e);
                return (
                  <div key={e.id} className="df-engine-line">
                    <span className="df-engine-line-main"><b>{e.name}</b><span className="df-small">{e.does}</span></span>
                    <Chip tone={st.tone}>{st.label}</Chip>
                  </div>
                );
              })}
              {g.id === "onemind" && <p className="df-small df-engine-note">
                Cloud AI API connects any OpenAI-compatible service: OpenAI, Azure OpenAI, Google Gemini, Mistral, Amazon Bedrock through an
                OpenAI-compatible gateway, or models on your own servers. Every AI model gets the same document type (its description and fields)
                and returns the same fields, so switching models needs no change to processes or types.
                {connections && <> Keys and addresses are kept in <a href={connections}>Model connections</a>.</>}</p>}
            </section>
          );
        })}
      </div>
    </div>
  );
}
