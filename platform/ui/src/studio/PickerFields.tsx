import { useMemo, useState } from "react";
import { SearchSelect, Select } from "../components";
import { CommitInput } from "./ValueSources";

/**
 * Pickers for fields people used to type and get wrong (command review D1, D2, D8, 10 Nov 2026): a time zone, a date
 * pattern, a language and region. Each keeps a way to type your own value or a $variable, so nothing that worked
 * before is lost; the value saved is the same text as before.
 */
const OWN = "\u0000own";
const VARIABLE = "\u0000variable";

/** Every time zone the browser knows (IANA names), or a short list when it cannot say. */
function allZones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (k: string) => string[] };
  try { const z = intl.supportedValuesOf?.("timeZone"); if (z?.length) return z; } catch { /* older browsers */ }
  return ["Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Asia/Tokyo", "Europe/London", "Europe/Berlin", "Europe/Paris", "America/New_York", "America/Chicago",
    "America/Los_Angeles", "Australia/Sydney"];
}

/** A time zone: Local (the runner's own, date steps only), UTC, or any zone by name; or a $variable. */
export function TimeZoneField({ id, value, local, onCommit }: { id: string; value: unknown; local: boolean; onCommit: (v: string | undefined) => void }) {
  const text = value === undefined || value === null ? "" : String(value);
  const options = useMemo(() => [
    ...(local ? [{ value: "local", label: "Local", description: "The time zone of the computer the agent runs on" }] : []),
    { value: "UTC", label: "UTC", description: "Coordinated Universal Time" },
    ...allZones().filter((z) => z !== "UTC").map((z) => ({ value: z, label: z.replace(/_/g, " "), description: z.split("/")[0] })),
    { value: VARIABLE, label: "From a variable…", description: "Its value is read when the step runs" }], [local]);
  const [asVariable, setAsVariable] = useState(text.startsWith("$"));
  if (asVariable) return <CommitInput id={id} initial={text} mono want="String" placeholder="$time_zone$" onCommit={(t) => { if (!t.trim()) setAsVariable(false); onCommit(t.trim() || undefined); }} />;
  return <SearchSelect id={id} value={text.toLowerCase() === "local" ? "local" : text} options={options} placeholder="Type a city or region"
    onChange={(v) => { if (v === VARIABLE) setAsVariable(true); else onCommit(v || undefined); }} />;
}

/** Common date patterns, as AA's To string offers them; the hint shows today in the pattern. */
const PATTERNS = ["dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd-MMM-yyyy", "d MMMM yyyy", "MMM d, yyyy", "dd/MM/yyyy HH:mm", "MM/dd/yyyy hh:mm a",
  "yyyy-MM-dd HH:mm:ss", "HH:mm", "hh:mm a", "dd.MM.yyyy", "yyyyMMdd", "EEEE, d MMMM yyyy"];
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

/** Today (or a given date) in a Java-style pattern: enough letters for the hint (y M d H h m s a E). */
export function sample(pattern: string, at = new Date()): string {
  const two = (n: number) => String(n).padStart(2, "0");
  return pattern.replace(/'[^']*'|yyyy|yy|MMMM|MMM|MM|M|dd|d|HH|H|hh|h|mm|ss|a|EEEE|EEE/g, (t) => {
    switch (t) {
      case "yyyy": return String(at.getFullYear()); case "yy": return String(at.getFullYear()).slice(2);
      case "MMMM": return MONTHS[at.getMonth()]; case "MMM": return MONTHS[at.getMonth()].slice(0, 3);
      case "MM": return two(at.getMonth() + 1); case "M": return String(at.getMonth() + 1);
      case "dd": return two(at.getDate()); case "d": return String(at.getDate());
      case "HH": return two(at.getHours()); case "H": return String(at.getHours());
      case "hh": return two(at.getHours() % 12 || 12); case "h": return String(at.getHours() % 12 || 12);
      case "mm": return two(at.getMinutes()); case "ss": return two(at.getSeconds());
      case "a": return at.getHours() < 12 ? "AM" : "PM";
      case "EEEE": return DAYS[at.getDay()]; case "EEE": return DAYS[at.getDay()].slice(0, 3);
      default: return t.slice(1, -1);
    }
  });
}

/** A date pattern from the list or your own (letters: d day, M month, y year, H hour, m minute, s second, a AM/PM). */
export function DatePatternField({ id, value, fallback, onCommit }: { id: string; value: unknown; fallback?: string; onCommit: (v: string | undefined) => void }) {
  const text = value === undefined || value === null ? "" : String(value);
  const [own, setOwn] = useState(text !== "" && !PATTERNS.includes(text));
  const shown = text || fallback || "";
  return <div className="st-picker">
    <Select id={id} value={own ? OWN : text} onChange={(e) => { if (e.target.value === OWN) setOwn(true); else { setOwn(false); onCommit(e.target.value || undefined); } }}
      options={[{ value: "", label: fallback ? "Default: " + fallback : "Choose…" }, ...PATTERNS.map((p) => ({ value: p, label: p + "   →   " + sample(p) })),
        { value: OWN, label: "A pattern of your own…" }]} />
    {own && <CommitInput label="Your own pattern" mono want="String" initial={text} placeholder="dd/MM/yyyy" onCommit={(t) => onCommit(t.trim() || undefined)} />}
    {shown && !shown.startsWith("$") && <div className="ui-field-hint">Today: {sample(shown)}. Letters: d day, M month, y year, H hour (h with a for AM/PM), m minute, s second.</div>}
  </div>;
}

/** Common languages and regions (locale codes), or your own. */
const LOCALES: [string, string][] = [["en", "English"], ["en-US", "English (United States)"], ["en-GB", "English (United Kingdom)"], ["en-IN", "English (India)"],
  ["hi-IN", "Hindi (India)"], ["de-DE", "German (Germany)"], ["fr-FR", "French (France)"], ["es-ES", "Spanish (Spain)"], ["it-IT", "Italian (Italy)"],
  ["nl-NL", "Dutch (Netherlands)"], ["pt-BR", "Portuguese (Brazil)"], ["ja-JP", "Japanese (Japan)"], ["zh-CN", "Chinese (China)"], ["ar-AE", "Arabic (UAE)"]];

export function LocaleField({ id, value, fallback, onCommit }: { id: string; value: unknown; fallback?: string; onCommit: (v: string | undefined) => void }) {
  const text = value === undefined || value === null ? "" : String(value);
  const [own, setOwn] = useState(text !== "" && !LOCALES.some(([c]) => c === text));
  return <div className="st-picker">
    <Select id={id} value={own ? OWN : text} onChange={(e) => { if (e.target.value === OWN) setOwn(true); else { setOwn(false); onCommit(e.target.value || undefined); } }}
      options={[{ value: "", label: fallback ? "Default: " + (LOCALES.find(([c]) => c === fallback)?.[1] ?? fallback) : "Choose…" },
        ...LOCALES.map(([c, words]) => ({ value: c, label: words + " (" + c + ")" })), { value: OWN, label: "Another language or a $variable…" }]} />
    {own && <CommitInput label="Language and region code" mono want="String" initial={text} placeholder="sv-SE" onCommit={(t) => onCommit(t.trim() || undefined)} />}
  </div>;
}

/** A choice from a list of words the toolkits read, or your own value (F2, F3). */
function ListOrOwn({ id, value, fallback, choices, ownLabel, placeholder, onCommit }: { id: string; value: unknown; fallback?: string; choices: [string, string][];
  ownLabel: string; placeholder: string; onCommit: (v: string | undefined) => void }) {
  const text = value === undefined || value === null ? "" : String(value);
  const [own, setOwn] = useState(text !== "" && !choices.some(([v]) => v === text));
  const words = (v?: string) => choices.find(([c]) => c === v)?.[1] ?? v;
  return <div className="st-picker">
    <Select id={id} value={own ? OWN : text} onChange={(e) => { if (e.target.value === OWN) setOwn(true); else { setOwn(false); onCommit(e.target.value || undefined); } }}
      options={[{ value: "", label: fallback ? "Default: " + words(fallback) : "Choose…" }, ...choices.map(([v, w]) => ({ value: v, label: w })), { value: OWN, label: ownLabel }]} />
    {own && <CommitInput label={ownLabel.replace(/…$/, "")} mono want="String" initial={text} placeholder={placeholder} onCommit={(t) => onCommit(t === "" ? undefined : t)} />}
  </div>;
}

const DELIMITERS: [string, string][] = [[",", "Comma ( , )"], [";", "Semicolon ( ; )"], ["tab", "Tab"], ["|", "Pipe ( | )"],
  ["regional", "Regional (the runner's list separator)"]];
/** A delimiter (F2, as AA's Comma, Tab, Regional, Other); the words are read the same by every toolkit. */
export function DelimiterField(p: { id: string; value: unknown; fallback?: string; onCommit: (v: string | undefined) => void }) {
  return <ListOrOwn {...p} choices={DELIMITERS} ownLabel="Another delimiter or a $variable…" placeholder="~" />;
}

const ENCODINGS: [string, string][] = [["utf-8", "UTF-8"], ["utf-8-bom", "UTF-8 with BOM (for Excel)"], ["ansi", "ANSI (Windows)"], ["unicode", "Unicode (UTF-16 LE)"],
  ["utf-16be", "UTF-16 BE"], ["windows-1252", "Western European (Windows-1252)"], ["iso-8859-1", "Latin-1 (ISO-8859-1)"], ["windows-1251", "Cyrillic (Windows-1251)"],
  ["shift_jis", "Japanese (Shift-JIS)"]];
/** A text encoding (F3, as AA's list); reading a file can also detect it. */
export function EncodingField(p: { id: string; value: unknown; fallback?: string; detect: boolean; onCommit: (v: string | undefined) => void }) {
  const choices: [string, string][] = p.detect ? [["auto", "Detect it (byte order mark)"], ...ENCODINGS] : ENCODINGS;
  return <ListOrOwn id={p.id} value={p.value} fallback={p.fallback} onCommit={p.onCommit} choices={choices} ownLabel="Another encoding or a $variable…" placeholder="gb18030" />;
}

const UNITS: [string, number][] = [["bytes", 1], ["KB", 1024], ["MB", 1048576], ["GB", 1073741824]];
/** A size typed with a unit (F4); the agent keeps bytes, as before. */
export function SizeField({ id, value, onCommit }: { id: string; value: unknown; onCommit: (v: number | string | undefined) => void }) {
  const bytes = typeof value === "number" ? value : typeof value === "string" && /^\d+$/.test(value) ? Number(value) : undefined;
  const best = bytes === undefined ? UNITS[2] : [...UNITS].reverse().find(([, f]) => bytes >= f && bytes % f === 0) ?? UNITS[0];
  const [unit, setUnit] = useState(best[0]);
  const factor = UNITS.find(([u]) => u === unit)![1];
  if (typeof value === "string" && value.startsWith("$"))
    return <CommitInput id={id} initial={value} mono want="Number" placeholder="$size$" onCommit={(t) => onCommit(t === "" ? undefined : t)} />;
  return <div className="st-row-tight">
    <input id={id} className="ui-input" type="number" min={0} step="any" key={unit + String(bytes)} defaultValue={bytes === undefined ? "" : String(bytes / factor)}
      onBlur={(e) => { const n = e.currentTarget.value === "" ? undefined : Math.round(Number(e.currentTarget.value) * factor); if (n !== bytes) onCommit(n); }} />
    <Select aria-label="Unit" value={unit} onChange={(e) => setUnit(e.target.value)} options={UNITS.map(([u]) => ({ value: u, label: u }))} />
  </div>;
}
